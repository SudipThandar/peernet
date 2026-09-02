package com.peernet.wifiextender.host

import android.content.Context
import com.peernet.wifiextender.diag.Diagnostics
import com.peernet.wifiextender.discovery.HostIdentity
import com.peernet.wifiextender.discovery.NsdHostAdvertiser
import com.peernet.wifiextender.power.DozeExemption
import com.peernet.wifiextender.power.WifiLockPolicy
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
import timber.log.Timber
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
    /** When the current share began, for the auto-stop clock. 0 when not sharing. */
    @Volatile private var shareStartedAtMs = 0L
    /** The limit being enforced for this share, resolved at start. */
    @Volatile private var shareDuration = ShareTimerPolicy.DEFAULT

    /** Latches HOST_READY so it is reported on the edge, not on every emission. */
    @Volatile
    private var reportedReady = false

    /** Monotonic sharing-session id, stamped on host diagnostics. */
    private val sessions = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var sessionId = 0

    /**
     * Keeps the Wi-Fi radio out of power save while sharing.
     *
     * This is NOT a `PowerManager` wake lock: it does not keep the CPU or the
     * screen awake, and the phone still sleeps normally. It tells the Wi-Fi
     * driver not to park the radio, which is what stopped the client's internet
     * when the host's screen turned off. A Wi-Fi Direct group owner is an access
     * point plus a router; with the screen off the driver enters power save,
     * stops servicing the group promptly, and both the QUIC tunnel and the
     * plain TCP probes to :4434 start timing out. Nothing in the app stops when
     * the screen turns off - the foreground service, the responder thread, the
     * Rust engine and the P2P group all keep running - so the radio is the
     * remaining mechanism.
     *
     * `WIFI_MODE_FULL_LOW_LATENCY` (API 29+) is deliberately NOT used: it is only
     * in effect while the screen is on and this app is in the foreground, which
     * is the exact opposite of what is needed here. [WifiLockPolicy] holds the
     * reasoning and the mode choice.
     */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = context.getSystemService(Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager ?: return
        val mode = WifiLockPolicy.lockMode()
        try {
            wifiLock = wm.createWifiLock(mode, "peernet-host").apply {
                setReferenceCounted(false)
                acquire()
            }
            Diagnostics.note(
                "host",
                "WIFI_LOCK_ACQUIRED mode=${WifiLockPolicy.describe(mode)}"
            )
            val granted = DozeExemption.isExempt(context)
            Diagnostics.note(
                "host",
                if (granted) "DOZE_EXEMPTION_GRANTED"
                else "DOZE_EXEMPTION_NOT_GRANTED (system may suspend this app when idle)"
            )
        } catch (t: Throwable) {
            // Never fail a share over this; it degrades screen-off behaviour
            // only, and the report must say so rather than looking healthy.
            Timber.w(t, "wifi lock unavailable")
            Diagnostics.note(
                "host",
                "WIFI_LOCK_FAILED (${t.javaClass.simpleName}) — internet may pause when the screen sleeps"
            )
        }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        runCatching { if (lock.isHeld) lock.release() }
        wifiLock = null
        Diagnostics.note("host", "WIFI_LOCK_RELEASED")
    }

    /**
     * Reports what is still alive, so a screen-off failure can be attributed.
     * Called from the screen-state receiver in [HostForegroundService].
     */
    fun reportAliveness(trigger: String) {
        if (!sharingActive) return
        val s = wifiDirect.state.value
        Diagnostics.note(
            "host",
            "LINKSERVER_ALIVE ($trigger) id=$sessionId listening=${linkServer.listening} " +
                "probes=${linkServer.probesAnswered} group=${s.hosting} ssid=${s.ssid ?: "?"} " +
                "engine=$engineReady wifiLock=${wifiLock?.isHeld == true}"
        )
        linkServer.failure?.let {
            Diagnostics.note("host", "LINKSERVER_STOPPED reason=$it ($trigger)")
        }
    }

    /** Session id a foreground-service instance stamps itself with. */
    fun currentSessionId(): Int = sessionId

    /**
     * Told by [HostForegroundService] that hosting appears to have ended.
     *
     * Deliberately **not** `stopSharing()`. "Hosting ended" is an unreliable
     * signal - a transient null from `requestGroupInfo` right after a successful
     * `createGroup`, or a stale `onDestroy` arriving after the user started a new
     * share - and build #108 tore down live groups by trusting it.
     * [HostSessionPolicy.shouldReleaseLatch] decides.
     *
     * @return true only if hosting really has ended, in which case the caller may
     *         also stop itself. False means "ignore this signal, keep running".
     */
    fun noteHostingEnded(reason: String, serviceSession: Int): Boolean {
        val s = wifiDirect.state.value
        if (!HostSessionPolicy.shouldReleaseLatch(
                sharingActive = sharingActive,
                serviceSession = serviceSession,
                currentSession = sessionId,
                groupLive = s.hosting,
                groupForming = s.creating,
                hostingIntended = wifiDirect.hostingIntended
            )
        ) {
            if (sharingActive) {
                Diagnostics.note(
                    "host",
                    "HOSTING_END_IGNORED ($reason) session=$serviceSession current=$sessionId " +
                        "group=${s.hosting} creating=${s.creating} intended=${wifiDirect.hostingIntended}"
                )
            }
            return false
        }
        Diagnostics.note("host", "HOSTING_ENDED ($reason) id=$sessionId — releasing share state")
        resetSessionState()
        return true
    }

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
                // Auto-stop. Checked here rather than with a scheduled job so the
                // deadline cannot outlive the process that owns it and stop a
                // share that a later session started.
                if (shareStartedAtMs != 0L &&
                    ShareTimerPolicy.hasExpired(
                        startedAtMs = shareStartedAtMs,
                        nowMs = System.currentTimeMillis(),
                        duration = shareDuration
                    )
                ) {
                    stopSharing(reason = HostStopReason.TIMER_EXPIRED)
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

        val s0 = wifiDirect.state.value
        if (sharingActive && !HostSessionPolicy.shouldStartFresh(sharingActive, s0.hosting, s0.creating)) {
            // A genuinely live share (group up or forming): a second tap must
            // not remove and recreate the group, dropping connected clients.
            Diagnostics.note("host", "SHARE_ALREADY_ACTIVE (ignored, group is live)")
            return
        }
        if (sharingActive) {
            // Latch set but nothing hosting: left over from a session that ended
            // without stopSharing() (process killed, app swiped, platform
            // dropped the group). Honouring it made SHARE permanently dead until
            // app data was cleared - the "I had to clear app data" symptom.
            Diagnostics.note(
                "host",
                "SHARE_STALE_STATE_RECOVERED (sharingActive with no group) - restarting cleanly"
            )
            resetSessionState()
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
        // Resolved once, at the start, so changing the setting mid-share cannot
        // retroactively shorten a share that is already running.
        shareDuration = ShareTimerSetting.load(context)
        shareStartedAtMs = System.currentTimeMillis()
        val sid = sessions.incrementAndGet()
        sessionId = sid
        Diagnostics.note("host", "SHARE_SESSION_CREATED id=$sid")
        Diagnostics.note(
            "host",
            "SHARE_TIMER_ARMED id=$sid limit=${shareDuration.label} " +
                "premium=${Entitlements.isPremium(context)}"
        )

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

        // Keep the radio itself out of power save, or the group stops being
        // serviced when this phone's screen turns off and every client loses
        // internet while the app still looks healthy.
        acquireWifiLock()

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

        // Stable group credentials. The passphrase does not change between shares
        // (that was what forced the user to retype it on the client every time)
        // and it is the user's own if they set one. See GroupCredentialsPolicy for
        // why the default is derived rather than random.
        val passphrase = HostCredentials.passphrase(context)
        Diagnostics.note(
            "host",
            "GROUP_CREDENTIALS custom=${HostCredentials.isCustom(context)} len=${passphrase.length}"
        )
        wifiDirect.startHosting(
            ssid = com.peernet.wifiextender.wifi.GroupCredentialsPolicy
                .networkName(HostIdentity.id(context)),
            passphrase = passphrase
        )
    }

    /**
     * @param reason recorded verbatim as `HOST_STOP_REASON`. The timer passes its
     *        own value so a share that stopped because the user asked for a
     *        30-minute limit can never be mistaken, in a diagnostics dump, for the
     *        unexplained host death that is still under investigation.
     */
    fun stopSharing(reason: String = HostStopReason.USER) {
        Diagnostics.note("host", "HOST_STOP_REASON=$reason id=$sessionId")
        Diagnostics.note("host", "SHARE_STOP_REQUESTED id=$sessionId")
        // Intent cleared first so nothing the teardown triggers (state
        // emissions, group-removal callbacks) can restart the responder or the
        // advertisement behind us.
        sharingActive = false
        shareStartedAtMs = 0L
        reportedReady = false
        stopResponder()
        withdrawAdvert()
        wifiDirect.stopHosting()
        wifiDirect.releaseMulticast()
        releaseWifiLock()
        rustCore.stopHost()
        engineFingerprint = null
        engineError = null
        context.stopService(android.content.Intent(context, com.peernet.wifiextender.service.HostForegroundService::class.java))
        Diagnostics.note(
            "host",
            "SHARE_STOP_COMPLETED id=$sessionId link=${linkServer.listening} " +
                "advert=${advertisedFingerprint != null}"
        )
    }

    /**
     * Clears everything that belongs to one sharing session, leaving persistent
     * device identity (host id, saved credentials) untouched.
     *
     * Reached when a share ended without [stopSharing] - the group was dropped
     * by the platform, or the service stopped itself - so the next SHARE starts
     * from a known state instead of requiring the app's data to be cleared.
     */
    private fun resetSessionState() {
        sharingActive = false
        shareStartedAtMs = 0L
        reportedReady = false
        stopResponder()
        withdrawAdvert()
        releaseWifiLock()
        runCatching { rustCore.stopHost() }
        engineFingerprint = null
        engineError = null
    }

    /** The limit being enforced for the current (or next) share. */
    val shareDurationInEffect: ShareDuration
        get() = if (sharingActive) shareDuration else ShareTimerSetting.load(context)

    /** True when the unlimited option is owned. Hard-coded false until billing ships. */
    val premium: Boolean get() = Entitlements.isPremium(context)

    /**
     * Stores the user's choice for the next share.
     *
     * A running share keeps the limit it started with: silently shortening a share
     * already in progress would look exactly like the host dying on its own.
     */
    fun setShareDuration(duration: ShareDuration) {
        val resolved = ShareTimerPolicy.resolve(duration, Entitlements.isPremium(context))
        ShareTimerSetting.save(context, resolved)
        if (!sharingActive) shareDuration = resolved
        Diagnostics.note(
            "host",
            "SHARE_TIMER_SET requested=${duration.label} stored=${resolved.label} " +
                "activeShareUnchanged=$sharingActive"
        )
    }

    /**
     * Milliseconds until auto-stop, or null when this share has no limit or none
     * is running. Read by the screen's existing 2-second poll.
     */
    fun shareTimeRemainingMs(): Long? {
        if (shareStartedAtMs == 0L) return null
        return ShareTimerPolicy.remainingMs(
            startedAtMs = shareStartedAtMs,
            nowMs = System.currentTimeMillis(),
            duration = shareDuration
        )
    }

    /**
     * True while the user's request to share is still standing — set by
     * [startSharing], cleared by [stopSharing] and by [resetSessionState].
     *
     * This is the **intent**, as opposed to `WifiDirectManager.state`, which is an
     * **observation** of the platform. The two disagree whenever the platform
     * transiently reports no group during a healthy share, so the screen needs
     * both: see `HostStatePolicy`, which exists because deriving the UI from the
     * observation alone rendered IDLE during a blip and invited the re-tap that
     * churned the group and the :4434 responder.
     */
    val sharingIntended: Boolean
        get() = sharingActive

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
