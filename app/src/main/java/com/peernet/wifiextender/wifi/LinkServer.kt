package com.peernet.wifiextender.wifi

import com.peernet.wifiextender.diag.Diagnostics
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal PNTP-port link responder.
 *
 * Answers probes with a version banner so clients can verify they reached a
 * genuine PeerNet host before pairing.
 *
 * Port note: the QUIC tunnel endpoint (M7) owns 4433; this banner responder
 * lives on 4434 so both can coexist. NSD advertises this port for probes.
 *
 * Wire format: "PN-LINK-2 <host_id> <cert_fingerprint|-> <tunnel_port>\n"
 * (legacy "PN-LINK-1 <host_id>" is still accepted by clients).
 *
 * The fingerprint travels here because mDNS TXT records proved unreliable in
 * the field: a client that cannot learn the pin cannot open the tunnel, and
 * the failure looked like "linked but no internet". The banner is generated
 * per connection, so an engine that starts late is still reported correctly.
 *
 * ## Ownership rules (build #105 post-mortem)
 *
 * The previous version bound the socket **inside the accept thread** and
 * published the reference only afterwards, while [start] unconditionally called
 * [stop] first. Two overlapping starts therefore produced two `ServerSocket`s
 * racing for 4434: `stop()` saw `serverSocket == null` (the winner had not
 * published it yet) and closed nothing, so the loser reported
 * `BindException: EADDRINUSE`, nulled the shared field, and left a *bound*
 * socket that nothing could ever close again. Every later start failed, and the
 * host card reported "port 4434 unavailable" while clients got connection
 * refused.
 *
 * The invariants that prevent that from recurring:
 *  1. [start] and [stop] are mutually exclusive ([lock]).
 *  2. The bind happens on the **caller's** thread, so when [start] returns the
 *     socket is either published or definitively failed — no window in which a
 *     concurrent [stop] cannot see it.
 *  3. [start] is idempotent: an already-bound responder is left alone.
 *  4. Each socket carries a [generation]; only the current generation may
 *     publish state, so a dying loop can never clobber its replacement.
 *  5. A socket never outlives its accept loop — the loop closes it in `finally`,
 *     so the port cannot be orphaned even if the loop dies unexpectedly.
 */
class LinkServer(private val port: Int = PORT) {

    private val lock = Any()

    /**
     * Identity of the current socket + accept loop. Bumped on every start and
     * stop so a stale loop can recognise that it is no longer authoritative.
     */
    @Volatile
    private var generation = 0

    /** Guarded by [lock]; only ever published/cleared while holding it. */
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var bound = false

    @Volatile
    private var bindError: String? = null

    private val probes = AtomicInteger(0)

    /** True once the socket is bound and accepting. */
    val listening: Boolean
        get() = bound

    /**
     * Why the responder is not listening, or null. Without this a failed bind
     * looked like a healthy host: the card said READY while every client got
     * "connection refused" on 4434.
     */
    val failure: String?
        get() = if (bound) null else (bindError ?: "link responder not started")

    /** Probes answered so far — proof a client actually reached this host. */
    val probesAnswered: Int
        get() = probes.get()

    /**
     * Binds the responder unless it is already bound, and starts accepting.
     *
     * Safe to call repeatedly and from any thread: repeated calls while bound
     * are no-ops that keep the existing socket. Returns true when the port is
     * bound (already or newly) — the caller can surface a failed bind instead
     * of advertising a host nobody can reach.
     */
    fun start(hostId: String, details: () -> HostLinkDetails): Boolean {
        synchronized(lock) {
            val existing = serverSocket
            if (bound && existing != null && !existing.isClosed) {
                Diagnostics.note(
                    "linkserver",
                    "LINKSERVER_ALREADY_RUNNING gen=$generation port=$port"
                )
                return true
            }

            // Reclaim anything half-dead (a loop that exited on its own) before
            // rebinding, otherwise the old socket still owns the port.
            closeCurrentLocked("restart")

            val gen = generation + 1
            generation = gen
            Diagnostics.note("linkserver", "LINKSERVER_START_REQUESTED gen=$gen port=$port")

            val socket = try {
                // No SO_REUSEADDR: with a single owner it is unnecessary, and
                // it would mask exactly the ownership bug fixed here by letting
                // a second listener bind over a leaked one.
                ServerSocket().apply { bind(InetSocketAddress(port), BACKLOG) }
            } catch (t: Throwable) {
                bound = false
                bindError = "port $port unavailable (${t.javaClass.simpleName})"
                Timber.w(t, "Link server could not bind :%d", port)
                Diagnostics.note("linkserver", "LINKSERVER_BIND_FAILED gen=$gen $bindError")
                return false
            }

            serverSocket = socket
            bindError = null
            bound = true
            probes.set(0)
            Timber.i("Link server listening on :%d (gen=%d)", port, gen)
            Diagnostics.note("linkserver", "LINKSERVER_BOUND gen=$gen port=$port")

            acceptThread = Thread { acceptLoop(gen, socket, hostId, details) }.apply {
                name = "peernet-link-$gen"
                isDaemon = true
                start()
            }
            return true
        }
    }

    /**
     * Stops accepting, closes the socket and clears all state, so a later
     * [start] can bind the same port again. Idempotent.
     */
    fun stop() {
        synchronized(lock) {
            if (serverSocket == null && acceptThread == null && !bound) {
                // Silent: a phone acting as a client calls this on every P2P
                // state emission, and logging it would bury the real events.
                return
            }
            Diagnostics.note("linkserver", "LINKSERVER_STOP_REQUESTED gen=$generation")
            closeCurrentLocked("stop")
            bindError = null
            Diagnostics.note("linkserver", "LINKSERVER_STOP_COMPLETED gen=$generation")
            Timber.i("Link server stopped")
        }
    }

    /**
     * Releases the current socket and loop. Must hold [lock].
     *
     * The generation is bumped *first*: from this point the outgoing loop is no
     * longer authoritative and cannot publish `bound`/`bindError` over whatever
     * replaces it.
     */
    private fun closeCurrentLocked(why: String) {
        val socket = serverSocket
        val thread = acceptThread
        generation++
        serverSocket = null
        acceptThread = null
        bound = false

        if (socket != null) {
            runCatching { socket.close() }
            Diagnostics.note("linkserver", "LINKSERVER_SOCKET_CLOSED ($why) port=$port")
        }
        if (thread != null && thread !== Thread.currentThread()) {
            // Closing the socket makes the blocked accept() throw at once, so
            // this returns immediately. Waiting matters: it guarantees the port
            // is free before a later start() tries to rebind it.
            runCatching { thread.join(JOIN_MS) }
            if (thread.isAlive) {
                Diagnostics.note(
                    "linkserver",
                    "LINKSERVER_LOOP_LINGERING ($why) — socket already closed, port released"
                )
            }
        }
    }

    private fun acceptLoop(
        gen: Int,
        socket: ServerSocket,
        hostId: String,
        details: () -> HostLinkDetails
    ) {
        try {
            while (gen == generation && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (t: Throwable) {
                    break
                }
                handle(client, hostId, details)
            }
        } finally {
            // A socket never outlives its loop. An accept loop that exits while
            // leaving the port bound is what held 4434 hostage for the rest of
            // the process's life.
            runCatching { socket.close() }
            if (gen == generation) {
                bound = false
                if (bindError == null) bindError = "link responder stopped unexpectedly"
                Timber.w("Link server accept loop ended (gen=%d)", gen)
                Diagnostics.note("linkserver", "LINKSERVER_LOOP_ENDED gen=$gen (unexpected)")
            }
        }
    }

    private fun handle(client: Socket, hostId: String, details: () -> HostLinkDetails) {
        Thread {
            runCatching {
                val d = runCatching { details() }.getOrDefault(HostLinkDetails())
                val fp = d.fingerprint.ifBlank { "-" }
                client.soTimeout = 3_000
                client.getOutputStream().apply {
                    write("PN-LINK-2 $hostId $fp ${d.tunnelPort}\n".toByteArray())
                    flush()
                }
                probes.incrementAndGet()
                Diagnostics.note(
                    "linkserver",
                    "answered ${client.inetAddress?.hostAddress} pin=${if (fp == "-") "MISSING" else "yes"}"
                )
            }
            runCatching { client.close() }
        }.apply {
            name = "peernet-link-conn"
            isDaemon = true
        }.start()
    }

    companion object {
        const val PORT = 4434
        const val BANNER_PREFIX = "PN-LINK-"
        private const val BACKLOG = 16
        private const val JOIN_MS = 500L
    }
}

/** What the host tells probing clients about its tunnel endpoint. */
data class HostLinkDetails(
    val fingerprint: String = "",
    val tunnelPort: Int = 4433
)
