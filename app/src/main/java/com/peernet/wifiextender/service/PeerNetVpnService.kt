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

        // A start with no live link can only install a TUN that swallows
        // traffic. Happens on the START_STICKY restart path after a process
        // kill, where the intent is null and the session is long gone.
        if (linkManager.linkedHost.value == null) {
            fail("No host link — reconnect once the host is sharing.")
            return START_NOT_STICKY
        }
        generation = linkManager.generation
        tornDown.set(false)

        if (tunFd != -1) {
            // Already capturing; just refresh socket pinning in case the
            // network changed while we stayed up.
            pinSocketsToUnderlying()
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
        val notification: Notification =
            NotificationCompat.Builder(this, PeerNetApp.CHANNEL_TUNNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("PeerNet tunnel active")
                .setContentText("Internet is arriving through the host phone")
                .setOngoing(true)
                .setContentIntent(openIntent)
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
