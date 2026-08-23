package com.peernet.wifiextender.wifi

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
 * Wire format: "PN-LINK-1 <host_id>\n"
 */
class LinkServer(private val port: Int = PORT) {

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start(hostId: String) {
        stop()
        running = true
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                Timber.i("Link server listening on :%d", port)
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (t: Throwable) {
                        break
                    }
                    handle(client, hostId)
                }
            } catch (t: Throwable) {
                if (running) Timber.w(t, "Link server stopped unexpectedly")
            }
        }.apply {
            name = "peernet-link"
            isDaemon = true
        }.start()
    }

    private fun handle(client: Socket, hostId: String) {
        Thread {
            runCatching {
                client.soTimeout = 3_000
                client.getOutputStream().apply {
                    write("PN-LINK-1 $hostId\n".toByteArray())
                    flush()
                }
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
