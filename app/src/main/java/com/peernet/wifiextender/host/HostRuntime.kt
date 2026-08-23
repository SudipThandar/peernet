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

    init {
        scope.launch {
            wifiDirect.state.collect { s ->
                if (s.hosting && s.ssid != null) {
                    advertiser.register(
                        displayName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        fingerprint = engineFingerprint ?: ""
                    )
                    linkServer.start(hostId)
                } else if (!s.hosting && !s.creating) {
                    linkServer.stop()
                    advertiser.unregister()
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
        // fingerprint is advertised so clients can pin it.
        engineFingerprint = rustCore.startHost(
            com.peernet.wifiextender.discovery.NsdHostAdvertiser.PNTP_PORT,
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        )
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
        wifiDirect.stopHosting()
        wifiDirect.releaseMulticast()
        rustCore.stopHost()
        engineFingerprint = null
        context.stopService(android.content.Intent(context, com.peernet.wifiextender.service.HostForegroundService::class.java))
    }
}
