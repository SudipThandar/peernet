package com.peernet.wifiextender.host

import android.content.Context
import com.peernet.wifiextender.diag.Diagnostics
import com.peernet.wifiextender.discovery.HostIdentity
import com.peernet.wifiextender.discovery.NsdHostAdvertiser
import com.peernet.wifiextender.util.Permissions
import com.peernet.wifiextender.wifi.LinkServer
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    /**
     * Whether the user asked *this* phone to share. Single source of truth for
     * the responder's lifetime.
     *
     * Before this existed the responder was started from inside the
     * `wifiDirect.state` collector, i.e. once per state emission — and because
     * the framework emits several times while a group forms, two starts
     * overlapped and fought over port 4434 (see [LinkServer]). It also meant a
     * *client* phone, which the old code mislabelled as hosting, started a
     * responder and an mDNS advertisement of its own.
     */
    @Volatile
    private var sharingActive = false

    /** Latches HOST_READY so it is reported on the edge, not on every emission. */
    @Volatile
    private var reportedReady = false

    init {
        scope.launch {
            wifiDirect.state.collect { s ->
                val groupUp = s.hosting && s.ssid != null
                if (sharingActive && groupUp) {
                    publishAdvert()
                    // Idempotent: no-op while already bound. Present so a
                    // responder that died on its own is revived as soon as the
                    // group reports in, rather than leaving a share that
                    // clients can see but never link to.
                    ensureResponder()
                    if (!reportedReady) {
                        reportedReady = true
                        Diagnostics.note(
                            "host",
                            "HOST_READY ssid=${s.ssid} go=${s.groupOwnerAddress ?: "?"} " +
                                "link=${linkServer.listening} engine=$engineReady"
                        )
                    }
                } else if (!groupUp) {
                    // The advertisement must never outlive the group it points
                    // at; the responder is tied to intent, not to this signal,
                    // so a group blip does not churn the port.
                    reportedReady = false
                    withdrawAdvert()
                    if (!sharingActive && !s.creating) stopResponder()
                }
            }
        }

        // Supervision tick. The collector only runs on state *changes*, so two
        // things would otherwise go unnoticed for the rest of the share:
        // a responder whose accept loop died (clients silently stop linking),
        // and an engine that finished starting after the group appeared, leaving
        // the advertised pin empty forever.
        scope.launch {
            while (true) {
                delay(SUPERVISE_MS)
                if (!sharingActive) continue
                if (!wifiDirect.state.value.hosting) continue
                if (!linkServer.listening) {
                    Diagnostics.note(
                        "host",
                        "LINK_RESPONDER_RESTART (${linkServer.failure ?: "not listening"})"
                    )
                    ensureResponder()
                }
                if (advertisedFingerprint != null && advertisedFingerprint != (engineFingerprint ?: "")) {
                    publishAdvert()
                }
            }
        }
    }

    fun canStart(): Boolean = Permissions.missing(context).isEmpty()

    /** Binds the banner responder unless it is already bound. Idempotent. */
    private fun ensureResponder() {
        linkServer.start(hostId) {
            com.peernet.wifiextender.wifi.HostLinkDetails(
                fingerprint = engineFingerprint ?: "",
                tunnelPort = NsdHostAdvertiser.PNTP_PORT
            )
        }
    }

    private fun stopResponder() {
        linkServer.stop()
    }

    /**
     * Publishes (or re-publishes) the mDNS record. The engine can finish
     * binding after the group appears, so a record carrying an empty or stale
     * pin is replaced once the real fingerprint is known — otherwise clients
     * read a pin that never matches and the tunnel silently never opens.
     */
    private fun publishAdvert() {
        val fp = engineFingerprint ?: ""
        if (advertisedFingerprint != null && advertisedFingerprint != fp) {
            advertiser.unregister()
            advertisedFingerprint = null
            Diagnostics.note("host", "ADVERT_REPUBLISH pin changed")
        }
        advertiser.register(
            displayName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            fingerprint = fp
        )
        advertisedFingerprint = fp
    }

    private fun withdrawAdvert() {
        if (advertisedFingerprint == null) return
        advertiser.unregister()
        advertisedFingerprint = null
    }

    fun startSharing() {
        val shortId = HostIdentity.shortId(context)

        if (sharingActive) {
            // A second SHARE tap (or a re-entrant call from the service) must
            // not re-run any of this: it would remove and recreate the group,
            // dropping every connected client for no reason.
            Diagnostics.note("host", "SHARE_ALREADY_ACTIVE (ignored)")
            return
        }
        Diagnostics.note("host", "SHARE_START_REQUESTED id=$shortId")

        // Wi-Fi Direct group creation is also gated on location *services*
        // being on, not just the permission grant: with location off the
        // platform accepts the call and then never creates a group, which is
        // indistinguishable from a broken app.
        if (!locationServicesEnabled()) {
            engineError = "Turn on Location in system settings — Android blocks Wi-Fi Direct without it."
            wifiDirect.reportError(engineError!!)
            Diagnostics.note("host", "SHARE_ABORTED location services off")
            return
        }

        sharingActive = true
        reportedReady = false

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
            Diagnostics.note("host", "ENGINE_START_FAILED ${engineError ?: "unknown"}")
        } else {
            Diagnostics.note("host", "ENGINE_STARTED port=${NsdHostAdvertiser.PNTP_PORT}")
        }

        // Bind the banner responder here, exactly once per share, on a known
        // thread — not from the state collector, which fires repeatedly while
        // the group forms and used to race itself for the port.
        ensureResponder()

        // Stable group credentials (honored on API 33+): the network always
        // appears as DIRECT-PeerNet-xxxx with an unchanging passphrase, so a
        // client that joined once auto-rejoins on every future share.
        wifiDirect.startHosting(
            ssid = "DIRECT-PeerNet-$shortId",
            passphrase = "pn-${HostIdentity.id(context)}"
        )
    }

    fun stopSharing() {
        Diagnostics.note("host", "SHARE_STOP_REQUESTED")
        // Intent cleared first so nothing the teardown triggers (state
        // emissions, group-removal callbacks) can restart the responder or the
        // advertisement behind us.
        sharingActive = false
        reportedReady = false
        stopResponder()
        withdrawAdvert()
        wifiDirect.stopHosting()
        wifiDirect.releaseMulticast()
        rustCore.stopHost()
        engineFingerprint = null
        context.stopService(android.content.Intent(context, com.peernet.wifiextender.service.HostForegroundService::class.java))
        Diagnostics.note(
            "host",
            "SHARE_STOP_COMPLETED link=${linkServer.listening} advert=${advertisedFingerprint != null}"
        )
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
     * Whether the link responder clients probe on 4434 is actually bound, and
     * how many probes it has answered. A share with a dead responder is
     * invisible otherwise: clients simply never link.
     */
    val linkServerListening: Boolean
        get() = linkServer.listening

    val linkServerFailure: String?
        get() = linkServer.failure

    val probesAnswered: Int
        get() = linkServer.probesAnswered

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
     * Whether the system location toggle is on. Android requires it for
     * Wi-Fi Direct group creation and peer discovery on every release that
     * matters here, independent of the runtime permission grant.
     */
    private fun locationServicesEnabled(): Boolean = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
    }.getOrDefault(true)

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

    private companion object {
        /**
         * Supervision cadence. Cheap (two volatile reads and a boolean) and
         * fast enough that a dead responder is revived well inside the client's
         * own retry window instead of stranding the share.
         */
        const val SUPERVISE_MS = 2_000L
    }
}
