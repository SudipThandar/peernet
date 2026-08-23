package com.peernet.wifiextender.host

import android.content.Context
import com.peernet.wifiextender.discovery.HostIdentity
import com.peernet.wifiextender.discovery.NsdHostAdvertiser
import com.peernet.wifiextender.util.Permissions
import com.peernet.wifiextender.wifi.LinkServer
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-lifetime host runtime: owns the mDNS advertisement and the PNTP-port
 * link responder for as long as a Wi-Fi Direct group exists — independent of
 * which screen is visible (foreground-service ownership arrives in M10).
 */
@Singleton
class HostRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirect: WifiDirectManager,
    private val rustCore: com.peernet.wifiextender.core.RustCoreBridge
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val advertiser = NsdHostAdvertiser(context)
    private val linkServer = LinkServer()
    private val hostId = HostIdentity.id(context)

    @Volatile
    private var engineFingerprint: String? = null

    /** What the mDNS record currently claims, so stale pins get corrected. */
    @Volatile
    private var advertisedFingerprint: String? = null

    /** Engine's own reason for refusing to start, shown on the host screen. */
    @Volatile
    private var engineError: String? = null

    init {
        scope.launch {
            wifiDirect.state.collect { s ->
                if (s.hosting && s.ssid != null) {
                    val fp = engineFingerprint ?: ""
                    // The engine can finish binding after the group appears;
                    // re-publish so clients never read an empty/stale pin.
                    if (advertisedFingerprint != null && advertisedFingerprint != fp) {
                        advertiser.unregister()
                        advertisedFingerprint = null
                    }
                    advertiser.register(
                        displayName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        fingerprint = fp
                    )
                    advertisedFingerprint = fp
                    linkServer.start(hostId) {
                        com.peernet.wifiextender.wifi.HostLinkDetails(
                            fingerprint = engineFingerprint ?: "",
                            tunnelPort = NsdHostAdvertiser.PNTP_PORT
                        )
                    }
                } else if (!s.hosting && !s.creating) {
                    linkServer.stop()
                    advertiser.unregister()
                    advertisedFingerprint = null
                }
            }
        }
    }

    fun canStart(): Boolean = Permissions.missing(context).isEmpty()

    fun startSharing() {
        val shortId = HostIdentity.shortId(context)

        // Brand the Wi-Fi Direct identity so clients see "PeerNet-xxxx",
        // not the owner's personal device name. Reflection-based; no-op where
        // the platform blocks it (API 33+ brands via explicit group SSID below).
        wifiDirect.setDeviceName("PeerNet-$shortId")

        // Foreground service keeps hosting alive when the app is backgrounded
        // or swiped away (Section 18.1). Must be started before/with hosting.
        val intent = android.content.Intent(context, com.peernet.wifiextender.service.HostForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        // Keep mDNS queries from clients reachable: Wi-Fi power save drops
        // multicast frames on P2P groups otherwise.
        wifiDirect.acquireMulticast()

        // PNTP QUIC engine (M7): owns the tunnel port; its certificate
        // fingerprint is advertised so clients can pin it. The resolver we
        // hand over is where client DNS queries (aimed at the tunnel's
        // virtual DNS address) get redirected — without it nothing resolves
        // on the client and browsing fails even with a healthy tunnel.
        engineFingerprint = startEngine()
        if (engineFingerprint == null) {
            android.util.Log.w("HostRuntime", "QUIC engine unavailable; clients will not be able to tunnel")
        }

        // Stable group credentials (honored on API 33+): the network always
        // appears as DIRECT-PeerNet-xxxx with an unchanging passphrase, so a
        // client that joined once auto-rejoins on every future share.
        wifiDirect.startHosting(
            ssid = "DIRECT-PeerNet-$shortId",
            passphrase = "pn-${HostIdentity.id(context)}"
        )
    }

    fun stopSharing() {
        linkServer.stop()
        advertiser.unregister()
        advertisedFingerprint = null
        wifiDirect.stopHosting()
        wifiDirect.releaseMulticast()
        rustCore.stopHost()
        engineFingerprint = null
        context.stopService(android.content.Intent(context, com.peernet.wifiextender.service.HostForegroundService::class.java))
    }

    /**
     * True once the QUIC engine holds the tunnel port and has a certificate
     * to pin. Clients cannot tunnel without it, so the UI shows this state
     * instead of letting a share look healthy while it is useless.
     */
    val engineReady: Boolean
        get() = !engineFingerprint.isNullOrBlank()

    /**
     * Why the engine is not usable, in the engine's own words — the host phone
     * is the only place this is visible, and a share without an engine looks
     * healthy while being useless to every client.
     */
    val engineFailure: String?
        get() = if (engineReady) null else (engineError ?: "tunnel engine did not start")

    /**
     * Starts the tunnel engine, retrying once through a stop: a leftover
     * server from an earlier share still owns the port and would make every
     * later share unusable (the engine refuses and clients get no pin).
     */
    private fun startEngine(): String? {
        val port = NsdHostAdvertiser.PNTP_PORT
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val dns = systemDnsUpstream()
        engineError = null
        rustCore.startHost(port, name, dns)?.let { return it }
        android.util.Log.w("HostRuntime", "engine start refused (${rustCore.lastError()}); recycling")
        rustCore.stopHost()
        val second = rustCore.startHost(port, name, dns)
        if (second == null) {
            engineError = rustCore.lastError()?.ifBlank { null }
        }
        return second
    }

    /**
     * This phone's real resolver ("ip:53"), taken from the network that
     * currently carries internet. Client queries land on the tunnel's virtual
     * DNS address, so the host must forward them somewhere real; a public
     * resolver is the fallback when the system list is unreadable.
     */
    private fun systemDnsUpstream(): String {
        val fallback = "1.1.1.1:53"
        return runCatching {
            val cm = context
                .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val active = cm.activeNetwork ?: return fallback
            val dns = cm.getLinkProperties(active)?.dnsServers.orEmpty()
            val v4 = dns.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            if (v4.isNullOrBlank()) fallback else "$v4:53"
        }.getOrDefault(fallback)
    }
}
