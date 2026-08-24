package com.peernet.wifiextender

import com.peernet.wifiextender.wifi.HostLinkDetails
import com.peernet.wifiextender.wifi.LinkServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lifecycle contract for the banner responder (build #105 post-mortem).
 *
 * The field failure was `BindException: EADDRINUSE` on :4434 that never
 * recovered: the host card said "port 4434 unavailable" for the rest of the
 * process's life, and every client got connection refused. Cause was two
 * overlapping `start()` calls, because the old code started the responder from
 * inside a StateFlow collector (once per emission) and bound the socket
 * asynchronously inside the accept thread.
 *
 * These tests run on an ephemeral port so they never collide with a real device
 * or with each other, and they assert the properties that make the bug
 * impossible rather than the log lines it produced.
 */
class LinkServerLifecycleTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val details = { HostLinkDetails(fingerprint = "AA:BB", tunnelPort = 4433) }

    private fun readBanner(port: Int, timeoutMs: Int = 2_000): String? =
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMs)
                s.soTimeout = timeoutMs
                BufferedReader(s.getInputStream().reader()).readLine()
            }
        }.getOrNull()

    @Test
    fun `binds and answers a probe with the versioned banner`() {
        val port = freePort()
        val server = LinkServer(port)
        try {
            assertTrue("expected bind on :$port", server.start("host-1", details))
            assertTrue(server.listening)
            assertNull(server.failure)

            val banner = readBanner(port)
            assertNotNull("no banner received", banner)
            assertEquals("PN-LINK-2 host-1 AA:BB 4433", banner)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `repeated start is idempotent and keeps serving`() {
        val port = freePort()
        val server = LinkServer(port)
        try {
            assertTrue(server.start("host-1", details))
            // The old code called stop() then rebound asynchronously here, which
            // is what raced. Repeats must be no-ops.
            repeat(5) { assertTrue("start #$it must succeed", server.start("host-1", details)) }

            assertTrue("responder must still be listening", server.listening)
            assertNull(server.failure)
            assertNotNull("responder stopped answering after repeated starts", readBanner(port))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop releases the port so the same instance can rebind it`() {
        val port = freePort()
        val server = LinkServer(port)
        try {
            assertTrue(server.start("host-1", details))
            server.stop()
            assertFalse(server.listening)

            // This is the STOP SHARE -> SHARE cycle that used to fail forever.
            assertTrue("rebinding :$port after stop must succeed", server.start("host-2", details))
            assertTrue(server.listening)
            assertEquals("PN-LINK-2 host-2 AA:BB 4433", readBanner(port))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop actually frees the port for an unrelated listener`() {
        val port = freePort()
        val server = LinkServer(port)
        assertTrue(server.start("host-1", details))
        server.stop()

        // Proves the socket is closed rather than merely forgotten: the leaked
        // socket in build #105 kept the port bound while `listening` was false,
        // so no later bind could ever succeed.
        ServerSocket().use { probe ->
            probe.bind(InetSocketAddress(port), 1)
            assertTrue("port $port was not released by stop()", probe.isBound)
        }
    }

    @Test
    fun `concurrent starts never orphan the port`() {
        val port = freePort()
        val server = LinkServer(port)
        val threads = 8
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())

        repeat(threads) {
            Thread {
                go.await()
                results.add(server.start("host-1", details))
                done.countDown()
            }.apply { isDaemon = true }.start()
        }
        go.countDown()
        assertTrue("threads did not finish", done.await(10, TimeUnit.SECONDS))

        try {
            // Every caller must see success: with one owner there is no loser to
            // report EADDRINUSE.
            assertTrue("a concurrent start failed: $results", results.all { it })
            assertTrue(server.listening)
            assertNull(server.failure)
            assertNotNull("responder not answering after concurrent starts", readBanner(port))
        } finally {
            server.stop()
        }

        // And the port must still be reclaimable afterwards — the real symptom
        // was an unreachable socket surviving the race.
        ServerSocket().use { probe ->
            probe.bind(InetSocketAddress(port), 1)
            assertTrue("port $port leaked by concurrent starts", probe.isBound)
        }
    }

    @Test
    fun `a taken port is reported as a failure instead of looking healthy`() {
        ServerSocket().use { squatter ->
            squatter.bind(InetSocketAddress(0), 1)
            val server = LinkServer(squatter.localPort)
            try {
                assertFalse("bind must not claim success", server.start("host-1", details))
                assertFalse(server.listening)
                // A share whose responder is dead must say so; silently looking
                // READY is what made clients fail with no explanation.
                assertTrue(
                    "failure must name the port, got ${server.failure}",
                    server.failure?.contains("${squatter.localPort}") == true
                )
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `stop is safe before any start and repeatable`() {
        val server = LinkServer(freePort())
        server.stop()
        server.stop()
        assertFalse(server.listening)
        assertNotNull("failure must explain why it is not listening", server.failure)
    }

    @Test
    fun `probe counter resets per session and counts answers`() {
        val port = freePort()
        val server = LinkServer(port)
        try {
            assertTrue(server.start("host-1", details))
            assertEquals(0, server.probesAnswered)
            readBanner(port)
            readBanner(port)

            val deadline = System.currentTimeMillis() + 2_000
            while (server.probesAnswered < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(2, server.probesAnswered)

            // A fresh share must not inherit the previous session's count: the
            // host card uses it as proof that a client reached *this* share.
            server.stop()
            assertTrue(server.start("host-1", details))
            assertEquals(0, server.probesAnswered)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `missing engine fingerprint is reported as a dash not an empty field`() {
        val port = freePort()
        val server = LinkServer(port)
        try {
            // Engine still starting: the banner must stay parseable, otherwise
            // the client reads a malformed line and cannot pin anything.
            assertTrue(server.start("host-9", { HostLinkDetails(fingerprint = "", tunnelPort = 4433) }))
            assertEquals("PN-LINK-2 host-9 - 4433", readBanner(port))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a details provider that throws does not kill the responder`() {
        val port = freePort()
        val server = LinkServer(port)
        try {
            assertTrue(server.start("host-1", { error("engine exploded") }))
            // First probe hits the failing provider; the responder must survive
            // it and keep serving, because losing it strands every client.
            readBanner(port)
            assertTrue("responder died on a provider error", server.listening)
            assertNotNull("responder stopped serving", readBanner(port))
        } finally {
            server.stop()
        }
    }
}
