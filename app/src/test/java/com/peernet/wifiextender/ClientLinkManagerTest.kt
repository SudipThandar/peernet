package com.peernet.wifiextender

import com.peernet.wifiextender.client.ClientLinkManager
import com.peernet.wifiextender.discovery.DiscoveredHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for session identity on the client link.
 *
 * Without generations, work started by one session (a delayed retry, a liveness
 * probe, a VPN bring-up waiting on the QUIC handshake) could finish after the
 * user had disconnected and re-establish a link nobody asked for — one of the
 * ways auto-connect became unreliable in build #106.
 */
class ClientLinkManagerTest {

    private fun host(address: String = "192.168.49.1") = DiscoveredHost(
        name = "PeerNet host",
        port = 4434,
        address = address,
        hostId = "ab12"
    )

    @Test
    fun `linking publishes the host and a fresh generation`() {
        val m = ClientLinkManager()
        assertNull(m.linkedHost.value)
        val gen = m.setLinked(host())
        assertEquals("192.168.49.1", m.linkedHost.value?.address)
        assertEquals(gen, m.generation)
        assertTrue(m.isCurrent(gen))
    }

    @Test
    fun `clearing the link also advances the generation`() {
        // The clear must invalidate in-flight work, so it cannot reuse the
        // number that the work is holding.
        val m = ClientLinkManager()
        val first = m.setLinked(host())
        val cleared = m.setLinked(null)
        assertNull(m.linkedHost.value)
        assertTrue("a clear must start a new generation", cleared > first)
        assertFalse("work from the previous session must be abandoned", m.isCurrent(first))
    }

    @Test
    fun `stale session cannot be mistaken for the live one after reconnect`() {
        val m = ClientLinkManager()
        val first = m.setLinked(host())
        m.setLinked(null)
        val second = m.setLinked(host("192.168.49.2"))
        assertFalse(m.isCurrent(first))
        assertTrue(m.isCurrent(second))
        assertEquals("192.168.49.2", m.linkedHost.value?.address)
    }

    @Test
    fun `relinking to the same host still counts as a new session`() {
        // Repeat SHARE/connect cycles must not inherit a previous session's
        // liveness job; identical host details are not identical sessions.
        val m = ClientLinkManager()
        val first = m.setLinked(host())
        val second = m.setLinked(host())
        assertTrue(second > first)
        assertFalse(m.isCurrent(first))
    }

    @Test
    fun `clearing drops the pinned network and the status text`() {
        val m = ClientLinkManager()
        m.setLinked(host())
        m.setTunnelStatus("Connecting…")
        m.setLinked(null)
        assertNull("a cleared link must not keep pinning a dead network", m.linkedNetwork.value)
        assertEquals("", m.tunnelStatus.value)
    }

    @Test
    fun `tunnel active is published for cleanup reporting`() {
        // CLIENT_CLEANUP_COMPLETED is gated on this going false, so it must be
        // observable and default to "not running".
        val m = ClientLinkManager()
        assertFalse(m.tunnelActive.value)
        m.setTunnelActive(true)
        assertTrue(m.tunnelActive.value)
        m.setTunnelActive(false)
        assertFalse(m.tunnelActive.value)
    }
}
