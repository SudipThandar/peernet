package com.peernet.wifiextender.wifi

import com.peernet.wifiextender.diag.Diagnostics
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

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
 */
class LinkServer(private val port: Int = PORT) {

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var bindError: String? = null

    /** True once the socket is bound and accepting. */
    val listening: Boolean
        get() = running && serverSocket != null

    /**
     * Why the responder is not listening, or null. Without this a failed bind
     * looked like a healthy host: the card said READY while every client got
     * "connection refused" on 4434.
     */
    val failure: String?
        get() = if (listening) null else (bindError ?: "link responder not started")

    /** Probes answered so far — proof a client actually reached this host. */
    @Volatile
    var probesAnswered: Int = 0
        private set

    fun start(hostId: String, details: () -> HostLinkDetails) {
        stop()
        running = true
        bindError = null
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                Timber.i("Link server listening on :%d", port)
                Diagnostics.note("linkserver", "listening on :$port")
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (t: Throwable) {
                        break
                    }
                    handle(client, hostId, details)
                }
            } catch (t: Throwable) {
                bindError = "port $port unavailable (${t.javaClass.simpleName})"
                serverSocket = null
                if (running) {
                    Timber.w(t, "Link server stopped unexpectedly")
                    Diagnostics.note("linkserver", "failed: $bindError")
                }
            }
        }.apply {
            name = "peernet-link"
            isDaemon = true
        }.start()
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
                probesAnswered++
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

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        Timber.i("Link server stopped")
    }

    companion object {
        const val PORT = 4434
        const val BANNER_PREFIX = "PN-LINK-"
    }
}

/** What the host tells probing clients about its tunnel endpoint. */
data class HostLinkDetails(
    val fingerprint: String = "",
    val tunnelPort: Int = 4433
)
