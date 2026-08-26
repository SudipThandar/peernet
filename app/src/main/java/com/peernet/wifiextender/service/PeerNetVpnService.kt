package com.peernet.wifiextender.service

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.app.Notification
import android.app.PendingIntent
import android.net.VpnService
import androidx.core.app.NotificationCompat
import com.peernet.wifiextender.MainActivity
import com.peernet.wifiextender.PeerNetApp
import com.peernet.wifiextender.R
import com.peernet.wifiextender.core.RustCoreBridge
import com.peernet.wifiextender.diag.Diagnostics
import com.peernet.wifiextender.power.DozeExemption
import com.peernet.wifiextender.power.WifiLockPolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Client-side VPN tunnel (spec Sections 10.5/10.6, Milestone 6).
 *
 * Establishes the TUN, protects the fd, and hands it to the Rust engine.
 * Ownership: after [RustCoreBridge.startTunCapture] succeeds, Rust owns the
 * fd; this service never touches it again (single-owner, no double close).
 */
@AndroidEntryPoint
class PeerNetVpnService : VpnService() {

    @Inject lateinit var rustCore: RustCoreBridge

    @Inject lateinit var linkManager: com.peernet.wifiextender.client.ClientLinkManager

    private var tunFd: Int = -1

    @Volatile private var hostAddr: String? = null

    @Volatile private var hostFp: String? = null

    @Volatile private var bringUp: Thread? = null

    /**
     * The link generation this tunnel belongs to. A bring-up thread from an
     * earlier session must not install a TUN for a host the client has already
     * abandoned.
     */
    @Volatile private var generation: Int = -1

    /** Guards [teardown] so repeated calls are safe and do the work once. */
    private val tornDown = java.util.concurrent.atomic.AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?) = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        watchLink()
    }

    // ---------- Service-owned supervision ----------
    //
    // The tunnel must not outlive its underlying network. Watching for that in
    // the UI layer was the defect: `ClientViewModel`'s liveness loop runs in
    // `viewModelScope`, which is cancelled with the Activity, so with the screen
    // off or the app swiped away nothing cleared the link. The VPN key and the
    // "internet is arriving through the host" notification then stayed up for
    // ever, and the default-route TUN kept swallowing traffic - the reported
    // "disconnected Wi-Fi but the VPN icon is still there, and no internet".
    //
    // A ConnectivityManager callback is the authoritative, event-driven signal:
    // it needs no polling, cannot be produced by a sleeping radio, and does not
    // "default to still connected" the way a failed probe has to.
    //
    // The second defect was in how that signal was interpreted. This section
    // used to implement `onLost` and nothing else, and treated a loss of the
    // tunnel's own network as the end of the session. Android destroys the old
    // `Network` object and publishes a replacement when a Wi-Fi Direct group
    // re-associates - which is exactly what happens when the screen turns off -
    // so a routine transition was being read as a fatal one. There is now a
    // three-state machine: attached, awaiting a replacement, or ended. See
    // `UnderlyingNetworkPolicy`.

    @Volatile private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * When the tunnel's own network went away, or 0 while it has one.
     *
     * Non-zero means "a replacement is allowed to appear". This is the state the
     * old code could not express: it had `onLost` and no notion of a network
     * being replaced, so a routine Wi-Fi Direct re-association read as the end
     * of the session.
     */
    @Volatile private var awaitingReplacementSince: Long = 0L

    private fun connectivityManager(): android.net.ConnectivityManager? =
        getSystemService(android.net.ConnectivityManager::class.java)

    /** Whether [net] holds an address on the host's subnet (and is not the VPN). */
    private fun networkReachesHost(
        cm: android.net.ConnectivityManager,
        net: android.net.Network
    ): Boolean {
        val addr = hostAddr ?: return false
        val caps = cm.getNetworkCapabilities(net)
        val isVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        val addresses = cm.getLinkProperties(net)
            ?.linkAddresses
            ?.mapNotNull { it.address?.hostAddress }
            .orEmpty()
        return TunnelSupervisorPolicy.canReachHost(addr, isVpn, addresses)
    }

    /**
     * Considers [net] as the tunnel's underlying network.
     *
     * Called from both `onAvailable` and `onLinkPropertiesChanged`: a Wi-Fi
     * Direct network is routinely published before it has an address, so
     * `onAvailable` alone cannot decide whether it reaches the host, and relying
     * on it would miss the replacement every time.
     */
    private fun considerNetwork(net: android.net.Network, trigger: String) {
        val cm = connectivityManager() ?: return
        val isVpn = cm.getNetworkCapabilities(net)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        if (!UnderlyingNetworkPolicy.shouldAdopt(
                candidateIsVpn = isVpn,
                candidateReachesHost = networkReachesHost(cm, net),
                haveUnderlying = underlying != null,
                awaitingReplacement = awaitingReplacementSince != 0L,
                tunInstalled = tunFd != -1
            )
        ) {
            return
        }
        adoptNetwork(net, trigger)
    }

    /**
     * Attaches the tunnel to [net], rebuilding the QUIC endpoint if this is a
     * replacement for a network the tunnel was already using.
     *
     * The TUN file descriptor is deliberately untouched. Rust owns it after
     * `startTunCapture`, and re-establishing capture here would create a second
     * owner for the same descriptor. Leaving it in place also keeps the VPN key
     * steady and avoids resetting every app socket using the tunnel.
     */
    private fun adoptNetwork(net: android.net.Network, trigger: String) {
        val previous = underlying?.toString()
        val replacing = UnderlyingNetworkPolicy.isReplacement(previous, net.toString())
        underlying = net
        awaitingReplacementSince = 0L
        Diagnostics.note(
            "vpn",
            "UNDERLYING_NETWORK_ADOPTED net=$net was=${previous ?: "-"} via=$trigger " +
                if (replacing) "(replacement)" else "(first)"
        )
        pinSocketsToUnderlying()
        // The process bind is the one that actually matters here:
        // setUnderlyingNetworks only labels the VPN for the system's accounting
        // and chooses no route. Without rebinding the process, every socket -
        // including the QUIC endpoint rebuilt below - stays attached to the dead
        // Network and the tunnel never carries a packet again.
        bindProcessToLink()
        if (replacing) reestablishQuic()
    }

    /**
     * Rebuilds the QUIC endpoint on the current underlying network.
     *
     * Necessary rather than optional: the core has no way to re-pin an existing
     * socket (no `Endpoint::rebind`, no `SO_BINDTODEVICE`), so a socket created
     * on the previous `Network` stays bound to a dead handle for ever. Only the
     * endpoint is rebuilt; TUN capture keeps running throughout.
     */
    private fun reestablishQuic() {
        val addr = hostAddr
        val fp = hostFp
        if (addr.isNullOrBlank() || fp.isNullOrBlank()) {
            Diagnostics.note("vpn", "QUIC_REESTABLISH_SKIPPED (host details unknown)")
            return
        }
        scope.launch {
            Diagnostics.note("vpn", "QUIC_REESTABLISH_STARTED host=$addr")
            runCatching { rustCore.stopTunnel() }
            val ok = runCatching { rustCore.startTunnel(addr, fp, Build.MODEL) }
                .getOrDefault(false)
            if (ok) {
                Diagnostics.note("vpn", "QUIC_REESTABLISHED (TUN kept, endpoint rebuilt)")
                linkManager.setTunnelStatus("Tunnel active")
            } else {
                // The group is back but the host is not answering on it. That is
                // a real failure and must be reported, not retried silently.
                val why = runCatching { rustCore.lastError() }.getOrDefault("")
                Diagnostics.note("vpn", "QUIC_REESTABLISH_FAILED $why")
                linkManager.setLinked(null)
                stopTunnel("host unreachable after network change")
            }
        }
    }

    /**
     * Ends the session if no replacement network arrives within the window.
     *
     * Without this the tunnel would wait for ever on a network that is never
     * coming back, which is the "connected but no internet" state this whole
     * mechanism exists to avoid.
     */
    private fun armReplacementWatchdog(startedAt: Long) {
        scope.launch {
            kotlinx.coroutines.delay(UnderlyingNetworkPolicy.REPLACEMENT_GRACE_MS)
            // A different loss may have restarted the window; only the newest
            // one may end the session.
            if (awaitingReplacementSince != startedAt) return@launch
            val elapsed = System.currentTimeMillis() - startedAt
            if (!UnderlyingNetworkPolicy.shouldTeardownAfterGrace(
                    awaitingReplacement = true,
                    elapsedMs = elapsed
                )
            ) {
                return@launch
            }
            Diagnostics.note(
                "vpn",
                "UNDERLYING_REPLACEMENT_TIMEOUT after ${elapsed}ms - ending the tunnel " +
                    "so the VPN route cannot outlive its link"
            )
            linkManager.setLinked(null)
            stopTunnel("no replacement network")
        }
    }

    private fun watchUnderlyingNetwork() {
        if (networkCallback != null) return
        val cm = connectivityManager() ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                considerNetwork(network, "onAvailable")
            }

            override fun onLinkPropertiesChanged(
                network: android.net.Network,
                linkProperties: android.net.LinkProperties
            ) {
                // The event that actually matters for a P2P group: the
                // replacement network usually appears before it has an address,
                // so this is where it becomes recognisable as the route to the
                // host.
                considerNetwork(network, "onLinkPropertiesChanged")
            }

            override fun onLost(network: android.net.Network) {
                val lost = network.toString()
                val mine = underlying?.toString()
                if (!UnderlyingNetworkPolicy.shouldAwaitReplacement(
                        TunnelSupervisorPolicy.lossConcernsTunnel(lost, mine, tunFd != -1)
                    )
                ) {
                    return
                }
                // Do NOT clear the link or tear down here. Android destroys the
                // old Network object and publishes a replacement when a Wi-Fi
                // Direct group re-associates (the 723 -> 726 pair in the field
                // reports); acting now would kill a session whose group is still
                // present. The sockets bound to `network` are already dead, so
                // the tunnel is unusable until a replacement is adopted - but
                // that is a stall to recover from, not a session to end.
                val startedAt = System.currentTimeMillis()
                awaitingReplacementSince = startedAt
                underlying = null
                Diagnostics.note(
                    "vpn",
                    "UNDERLYING_NETWORK_LOST net=$lost - awaiting replacement " +
                        "(${UnderlyingNetworkPolicy.REPLACEMENT_GRACE_MS}ms)"
                )
                Timber.i("underlying network %s lost; awaiting replacement", lost)
                // The tunnel is genuinely not carrying traffic during the
                // window, so the UI must say so. Claiming "Tunnel active" here
                // is the kind of faked state that made earlier reports useless.
                linkManager.setTunnelStatus("Reconnecting to the host...")
                // A replacement may already exist: this loss can be delivered
                // after the new network has come up.
                resolveUnderlyingFromHost()
                // Read once into a local: `underlying` is volatile and another
                // thread may clear it, so a smart cast is unavailable and `!!`
                // would be a race.
                val already = underlying
                if (already != null) {
                    adoptNetwork(already, "onLost/immediate")
                } else {
                    armReplacementWatchdog(startedAt)
                }
            }
        }
        // clearCapabilities() matters: a Wi-Fi Direct network has no
        // NET_CAPABILITY_INTERNET, so a default NetworkRequest would never match
        // it and the loss we care about most would never be delivered.
        val request = android.net.NetworkRequest.Builder()
            .clearCapabilities()
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess {
                networkCallback = callback
                Diagnostics.note("vpn", "NETWORK_WATCH_STARTED")
            }
            .onFailure {
                Timber.w(it, "registerNetworkCallback failed")
                Diagnostics.note(
                    "vpn",
                    "NETWORK_WATCH_FAILED (${it.javaClass.simpleName}) — a lost link " +
                        "may leave the tunnel up until you disconnect manually"
                )
            }
    }

    private fun stopWatchingNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { connectivityManager()?.unregisterNetworkCallback(callback) }
    }

    /**
     * Recovers the underlying network when the UI did not supply one.
     *
     * `EXTRA_NETWORK` comes from `linkedNetwork()`, which can be null. Without a
     * known underlying network a lost link cannot be attributed, so the watch
     * above would silently do nothing - the exact "fix that quietly no-ops" this
     * change exists to remove. Resolving it from the host's own subnet also
     * repairs socket pinning on that path.
     *
     * Ambiguity is reported rather than hidden. This matches on the host's /24,
     * so two networks on the same subnet both qualify and the old
     * `firstOrNull` picked one silently - pinning the tunnel to the wrong
     * network is indistinguishable from a dead host from the user's side. When
     * there is more than one candidate the Wi-Fi Direct range wins, because that
     * is where a PeerNet host always lives, and the choice is logged.
     */
    private fun resolveUnderlyingFromHost() {
        if (underlying != null) return
        val cm = connectivityManager() ?: return
        val addr = hostAddr ?: return
        runCatching {
            val candidates = cm.allNetworks.filter { net ->
                val caps = cm.getNetworkCapabilities(net)
                val isVpn =
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                val addresses = cm.getLinkProperties(net)
                    ?.linkAddresses
                    ?.mapNotNull { it.address?.hostAddress }
                    .orEmpty()
                TunnelSupervisorPolicy.canReachHost(addr, isVpn, addresses)
            }
            if (candidates.size > 1) {
                Diagnostics.note(
                    "vpn",
                    "UNDERLYING_AMBIGUOUS ${candidates.size} networks match ${addr}'s subnet " +
                        "- preferring the Wi-Fi Direct range"
                )
            }
            candidates.firstOrNull { net ->
                cm.getLinkProperties(net)
                    ?.linkAddresses
                    ?.mapNotNull { it.address?.hostAddress }
                    .orEmpty()
                    .any { com.peernet.wifiextender.client.LinkPolicy.isWifiDirectAddress(it) }
            } ?: candidates.firstOrNull()
        }.onSuccess { found ->
            if (found != null) {
                underlying = found
                Diagnostics.note("vpn", "UNDERLYING_RESOLVED net=$found (from host subnet)")
            }
        }.onFailure { Timber.w(it, "underlying network lookup failed") }
    }

    /**
     * Keeps the Wi-Fi radio out of power save while the tunnel owns a TUN.
     *
     * Wi-Fi Direct power-saves hard with the screen off, which stalls the tunnel.
     * [HostRuntime][com.peernet.wifiextender.host.HostRuntime] already holds this
     * lock on the sharing phone; the client never did, so a screen-off stall
     * could come from either end and there was no way to tell them apart.
     *
     * This is a `WifiManager` lock, not a `PowerManager` wake lock: it does not
     * hold the CPU and never keeps the screen on.
     *
     * The mode comes from [WifiLockPolicy]: this used to ask for
     * `WIFI_MODE_FULL_LOW_LATENCY`, which is only active while the screen is on
     * and the app is foreground, so it did nothing in the one situation it was
     * added for. See that class for the full reasoning.
     */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireWifiLock() {
        if (!TunnelSupervisorPolicy.shouldHoldWifiLock(tunFd != -1)) return
        if (wifiLock?.isHeld == true) return
        val wm = getSystemService(android.net.wifi.WifiManager::class.java) ?: return
        val mode = WifiLockPolicy.lockMode()
        try {
            wifiLock = wm.createWifiLock(mode, "peernet-client").apply {
                setReferenceCounted(false)
                acquire()
            }
            Diagnostics.note(
                "vpn",
                "WIFI_LOCK_ACQUIRED mode=${WifiLockPolicy.describe(mode)}"
            )
            noteDozeState()
        } catch (t: Throwable) {
            // Never fail a tunnel over this; it degrades screen-off behaviour
            // only, and the report must say so rather than looking healthy.
            Timber.w(t, "wifi lock unavailable")
            Diagnostics.note(
                "vpn",
                "WIFI_LOCK_FAILED (${t.javaClass.simpleName}) — internet may pause when the screen sleeps"
            )
        }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        runCatching { if (lock.isHeld) lock.release() }
        wifiLock = null
        Diagnostics.note("vpn", "WIFI_LOCK_RELEASED")
    }

    /**
     * Records whether the Doze exemption is actually GRANTED.
     *
     * Audit finding: the only Doze line a report ever contained was
     * `DOZE_EXEMPTION_PROMPTED`, written when the dialog was *shown*. A reader
     * could not tell whether the user granted it, declined it, or never saw it -
     * which made every screen-off report ambiguous about the one variable being
     * investigated. `DozeExemption.isExempt` already existed and was only ever
     * used as an input to the prompt decision, never reported.
     */
    private fun noteDozeState() {
        val granted = DozeExemption.isExempt(this)
        Diagnostics.note(
            "vpn",
            if (granted) "DOZE_EXEMPTION_GRANTED"
            else "DOZE_EXEMPTION_NOT_GRANTED (system may suspend this app when idle)"
        )
    }

    /**
     * Ends the tunnel as soon as the client link is gone.
     *
     * This used to live in a Compose `LaunchedEffect` reading
     * `collectAsStateWithLifecycle()`, which stops collecting when the Activity
     * stops — so with the screen off or the app backgrounded nothing observed
     * the link clearing and the TUN, the tunnel and the Android VPN key all
     * outlived the session. The service now owns its own death: no UI required.
     */
    private fun watchLink() {
        scope.launch {
            // Ignore the replayed initial value: the link is published before
            // this service is started, so the first emission is our own host.
            linkManager.linkedHost.collect { host ->
                if (host == null && tunFd != -1) {
                    Diagnostics.note("vpn", "VPN_STOP_REQUESTED reason=link cleared")
                    stopTunnel("link cleared")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diagnostics.note(
            "vpn",
            "onStartCommand action=${intent?.action ?: "start"} startId=$startId capturing=${tunFd != -1}"
        )
        when (intent?.action) {
            ACTION_STOP -> {
                Diagnostics.note("vpn", "VPN_STOP_REQUESTED reason=explicit stop")
                stopTunnel("explicit stop")
                return START_NOT_STICKY
            }
        }

        // Remember the latest host endpoint so a restart (START_STICKY path
        // or re-start while capturing) reconnects to the right host.
        intent?.getStringExtra(EXTRA_HOST_ADDR)?.let { hostAddr = it }
        intent?.getStringExtra(EXTRA_HOST_FP)?.let { hostFp = it }
        readUnderlyingNetwork(intent)?.let { underlying = it }
        resolveUnderlyingFromHost()

        // A start with no live link can only install a TUN that swallows
        // traffic. Happens on the START_STICKY restart path after a process
        // kill, where the intent is null and the session is long gone.
        if (linkManager.linkedHost.value == null) {
            fail("No host link — reconnect once the host is sharing.")
            return START_NOT_STICKY
        }
        generation = linkManager.generation
        tornDown.set(false)
        // Refresh the notification so its Stop action carries this session's
        // generation rather than the -1 stamped during onCreate.
        startAsForeground()

        if (tunFd != -1) {
            // Already capturing. Refresh socket pinning AND the process bind: a
            // re-delivery is one of the ways we learn the network changed while
            // we stayed up, and pinSocketsToUnderlying alone only updates the
            // system's accounting - it chooses no route, so without the process
            // bind every socket stays attached to the previous network.
            pinSocketsToUnderlying()
            bindProcessToLink()
            return START_STICKY
        }
        if (bringUp?.isAlive == true) return START_STICKY

        val addr = hostAddr
        val fp = hostFp
        if (addr.isNullOrBlank() || fp.isNullOrBlank()) {
            // No endpoint = the TUN could only swallow traffic. Refuse to
            // install it; the phone keeps whatever connectivity it has.
            fail("Host tunnel details missing — reconnect once the host is sharing.")
            return START_NOT_STICKY
        }

        // Order matters: bring the QUIC tunnel UP FIRST, and only install the
        // default-route TUN once it is carrying traffic. Establishing the TUN
        // before the tunnel works turns every app offline for as long as the
        // handshake is failing, which is indistinguishable from a broken
        // phone (and was exactly the reported symptom).
        bindProcessToLink()
        linkManager.setTunnelStatus("Connecting to host…")
        if (!rustCore.startTunnel(addr, fp, Build.MODEL)) {
            fail(rustCore.lastError().ifBlank { "Tunnel refused by engine." })
            return START_NOT_STICKY
        }

        bringUp = Thread { awaitTunnelThenCapture() }.apply {
            name = "peernet-vpn-bringup"
            isDaemon = true
            start()
        }
        return START_STICKY
    }

    /**
     * Waits for the handshake, then hands the TUN to the engine. Runs off the
     * main thread: onStartCommand must never block.
     */
    private fun awaitTunnelThenCapture() {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (rustCore.tunnelState()) {
                STATE_CONNECTED -> break
                STATE_DISCONNECTED -> {
                    val err = rustCore.lastError()
                    if (err.isNotBlank()) {
                        fail(err)
                        return
                    }
                }
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (t: InterruptedException) {
                return
            }
        }
        if (rustCore.tunnelState() != STATE_CONNECTED) {
            fail(
                rustCore.lastError().ifBlank {
                    "Could not reach the host tunnel. Check that SHARE is still on."
                }
            )
            return
        }

        // The link may have been cleared (host stopped sharing, user
        // disconnected, network changed) during the handshake. Installing a
        // default-route TUN for a dead session takes the phone offline.
        if (!linkManager.isCurrent(generation) || linkManager.linkedHost.value == null) {
            Diagnostics.note("vpn", "bring-up abandoned: link gen=$generation is stale")
            teardown("stale session")
            return
        }

        val fd = establishTun()
        if (fd < 0) {
            fail(
                "Android refused to create the VPN interface — " +
                    "another VPN may be active, or VPN permission was withdrawn."
            )
            return
        }
        tunFd = fd

        if (!rustCore.startTunCapture(fd, MTU)) {
            // Kotlin detached this fd, so it is ours to close — otherwise it
            // leaks (Rust only closes the fd it actually accepted). Also tear
            // down any stale capture that caused the refusal so the next
            // start attempt begins from a clean slate.
            Timber.w("Rust refused TUN capture; resetting engine state")
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            runCatching { rustCore.stopTunCapture() }
            tunFd = -1
            fail("Engine refused the tunnel interface.")
            return
        }

        pinSocketsToUnderlying()
        // Only now does the service own a TUN, so this is the point where the
        // radio must stay awake and the underlying network must be watched.
        acquireWifiLock()
        watchUnderlyingNetwork()
        linkManager.setTunnelStatus("Tunnel active")
        linkManager.setTunnelActive(true)
        Diagnostics.note("vpn", "TUN capture started (fd=$fd mtu=$MTU)")
        Timber.i("TUN capture started (fd=%d mtu=%d)", fd, MTU)
    }

    /** Reports why the tunnel is not up and leaves the phone as it was. */
    private fun fail(reason: String) {
        Diagnostics.note("vpn", "bring-up failed: $reason")
        Timber.w("VPN bring-up failed: %s", reason)
        linkManager.setTunnelStatus(reason)
        teardown("bring-up failed")
    }

    /**
     * Releases everything this service owns, exactly once, in the order the
     * resources depend on each other:
     *
     *   stop QUIC -> stop capture (closes the TUN fd in Rust) -> unbind the
     *   process -> drop the notification -> stop the service.
     *
     * Idempotent: [tornDown] makes repeated calls (link cleared *and* explicit
     * stop *and* onDestroy, which routinely overlap) safe.
     *
     * Note the TUN fd is not closed here. Rust took ownership of it in
     * `startTunCapture`, and `stopTunCapture` closes it (and its two dups)
     * exactly once — closing it here as well would be a double close.
     */
    private fun teardown(reason: String) {
        if (!tornDown.compareAndSet(false, true)) return
        val hadTun = tunFd != -1
        bringUp?.interrupt()
        bringUp = null
        stopWatchingNetwork()
        releaseWifiLock()
        runCatching { rustCore.stopTunnel() }
        runCatching { rustCore.stopTunCapture() }
        tunFd = -1
        if (hadTun) Diagnostics.note("vpn", "TUN_CLOSED ($reason)")
        linkManager.setTunnelActive(false)
        unbindProcessFromLink()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Diagnostics.note("vpn", "VPN_SERVICE_STOPPED ($reason)")
    }

    @Volatile private var underlying: android.net.Network? = null

    private fun readUnderlyingNetwork(intent: Intent?): android.net.Network? = try {
        intent?.let {
            androidx.core.content.IntentCompat.getParcelableExtra(
                it, EXTRA_NETWORK, android.net.Network::class.java
            )
        }
    } catch (t: Throwable) {
        Timber.w(t, "underlying network extra unreadable")
        null
    }

    /**
     * Pins the tunnel's protected sockets onto the link network. Without
     * this, Android routes them via the DEFAULT network — and a P2P Wi-Fi
     * marked "no internet" loses that role to cellular, where the host's
     * private address does not exist (handshake times out forever).
     */
    private fun pinSocketsToUnderlying() {
        val net = underlying ?: return
        runCatching { setUnderlyingNetworks(arrayOf(net)) }
            .onFailure { Timber.w(it, "setUnderlyingNetworks failed") }
            .onSuccess { Timber.i("Tunnel pinned to network %s", net) }
    }

    /**
     * Routes this process's own sockets (the QUIC tunnel included) over the
     * link network. `setUnderlyingNetworks` only labels the VPN for the
     * system's accounting — it does NOT choose a route, so without this the
     * engine's UDP socket follows the DEFAULT network. On a phone with mobile
     * data that means the handshake is sent to the carrier, where the host's
     * private address does not exist, and the tunnel silently never connects.
     */
    private fun bindProcessToLink() {
        val net = underlying ?: return
        runCatching {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            cm?.bindProcessToNetwork(net)
        }.onSuccess { Timber.i("Process bound to link network %s", net) }
            .onFailure { Timber.w(it, "bindProcessToNetwork failed") }
    }

    private fun unbindProcessFromLink() {
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)
                ?.bindProcessToNetwork(null)
        }
    }

    /**
     * Establishes the TUN per spec 10.6 and returns the detached fd,
     * or -1 on any failure. protect() runs BEFORE detach so a failed
     * protection aborts cleanly with the pfd still owned here.
     */
    private fun establishTun(): Int {
        val builder = Builder()
            .setSession(SESSION)
            .setMtu(MTU)
            .addAddress(VPN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(VIRTUAL_DNS)
            .addDisallowedApplication(packageName) // never route our own tunnel
            .setBlocking(false)

        val pfd: ParcelFileDescriptor = builder.establish() ?: return -1
        return try {
            // No protect() here: it applies to *sockets* and fails with
            // ENOTSOCK on a TUN fd, which used to abort every tunnel. The
            // routing-loop guard is addDisallowedApplication(packageName)
            // above — our own QUIC socket never enters the tunnel.
            pfd.detachFd()
        } catch (t: Throwable) {
            Timber.w(t, "tun handoff failed")
            runCatching { pfd.close() }
            -1
        }
    }

    private fun stopTunnel(reason: String) {
        Diagnostics.note("vpn", "stopTunnel ($reason)")
        linkManager.setTunnelStatus("")
        teardown(reason)
    }

    override fun onRevoke() {
        // User revoked VPN permission from system settings, or another VPN app
        // took over. The link is dead either way, so clear it too - otherwise
        // the client keeps reporting "Connected" with no tunnel underneath.
        Diagnostics.note("vpn", "VPN_STOP_REQUESTED reason=permission revoked")
        Timber.i("VPN permission revoked by user")
        linkManager.setLinked(null)
        stopTunnel("permission revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        Diagnostics.note("vpn", "service destroyed")
        // Safety net: a system-initiated destroy (task removed, low memory)
        // never goes through stopTunnel, and skipping this is what left tun0
        // and the VPN key alive after the session ended.
        teardown("service destroyed")
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // The notification is setOngoing(true), so it cannot be swiped away: this
        // action is the only way to end the tunnel without opening the app, which
        // matters because the tunnel is a default route and users reach for the
        // notification first. Mirrors the host's Stop action.
        //
        // EXTRA_LINK_GEN stamps the session this action belongs to. Without it a
        // Stop tap on a notification left over from a previous session cleared
        // the *current* link, killing a healthy tunnel. FLAG_UPDATE_CURRENT plus
        // the fixed request code means there is only ever one of these, so
        // re-issuing the notification refreshes the stamp.
        val stopIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, VpnNotificationActionReceiver::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_LINK_GEN, generation),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification =
            NotificationCompat.Builder(this, PeerNetApp.CHANNEL_TUNNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("PeerNet tunnel active")
                .setContentText("Internet is arriving through the host phone")
                .setOngoing(true)
                .setContentIntent(openIntent)
                .addAction(R.drawable.ic_notification, "Stop", stopIntent)
                .build()

        // A throw here used to kill the service the moment it started, and the
        // sticky restart re-crashed it in a loop (visible only as tunnel
        // counters resetting). Report instead of dying silently.
        try {
            val type = ForegroundServiceType.current()
            if (type != null) {
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Reporting and stopping beats crashing: a crash here is restarted
            // by the system and simply crashes again.
            Timber.e(t, "startForeground rejected")
            fail("Android refused to start the tunnel service (${t.javaClass.simpleName}).")
        }
    }

    companion object {
        const val ACTION_STOP = "com.peernet.wifiextender.action.STOP_VPN"
        const val EXTRA_HOST_ADDR = "host_addr"
        const val EXTRA_HOST_FP = "host_fp"
        const val EXTRA_NETWORK = "host_network"

        /**
         * The link generation the notification's Stop action belongs to.
         *
         * A Stop tap from a previous session must not clear the current link.
         */
        const val EXTRA_LINK_GEN = "link_gen"
        const val SESSION = "PeerNet"
        const val MTU = 1280
        const val VPN_ADDRESS = "10.215.17.2"
        const val VIRTUAL_DNS = "10.215.17.1"
        private const val NOTIFICATION_ID = 1002
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val POLL_MS = 250L
        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTED = 2
    }
}
