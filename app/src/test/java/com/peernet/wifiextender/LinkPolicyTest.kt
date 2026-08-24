package com.peernet.wifiextender

import com.peernet.wifiextender.client.LinkPolicy
import com.peernet.wifiextender.client.LinkPolicy.GatewayCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for the client link decisions that build #106 got wrong.
 *
 * Each test names the field symptom it prevents, so a future change that
 * reintroduces the behaviour fails with an explanation rather than a diff.
 */
class LinkPolicyTest {

    // ---- gateway selection: do not probe the user's own router ----

    @Test
    fun `ordinary router is never probed on its own`() {
        // Field report: a client on "AirFiber21" probed 192.168.31.1:4434 every
        // few seconds forever, treating the household router as a PeerNet host.
        val ranked = LinkPolicy.rankGateways(
            listOf(GatewayCandidate("192.168.31.1", "wlan0", "AirFiber21")),
            userInitiated = false
        )
        assertTrue(
            "an ordinary router must not be probed without corroborating evidence",
            ranked.isEmpty()
        )
    }

    @Test
    fun `wifi direct group owner is always probed`() {
        val ranked = LinkPolicy.rankGateways(
            listOf(GatewayCandidate("192.168.49.1", "wlan0", "DIRECT-PeerNet-ab12")),
            userInitiated = false
        )
        assertEquals(listOf("192.168.49.1"), ranked.map { it.address })
    }

    @Test
    fun `group owner outranks the router when both are present`() {
        // Both interfaces are up during a DIRECT to Wi-Fi transition; the host
        // must win, and the router must be dropped entirely.
        val ranked = LinkPolicy.rankGateways(
            listOf(
                GatewayCandidate("192.168.31.1", "wlan0", "AirFiber21"),
                GatewayCandidate("192.168.49.1", "p2p-wlan0-0", "DIRECT-PeerNet-ab12")
            ),
            userInitiated = false
        )
        assertEquals(listOf("192.168.49.1"), ranked.map { it.address })
    }

    @Test
    fun `remembered host address is probed on a normal subnet`() {
        // A host that shared over ordinary Wi-Fi before is legitimate evidence.
        val ranked = LinkPolicy.rankGateways(
            listOf(
                GatewayCandidate(
                    address = "192.168.31.1",
                    interfaceName = "wlan0",
                    ssid = "AirFiber21",
                    knownHostAddresses = setOf("192.168.31.1")
                )
            ),
            userInitiated = false
        )
        assertEquals(listOf("192.168.31.1"), ranked.map { it.address })
    }

    @Test
    fun `explicit connect tap allows probing the current network`() {
        val candidate = GatewayCandidate("10.0.0.1", "wlan0", "Cafe")
        assertTrue(LinkPolicy.rankGateways(listOf(candidate), userInitiated = true).isNotEmpty())
        assertTrue(LinkPolicy.rankGateways(listOf(candidate), userInitiated = false).isEmpty())
    }

    @Test
    fun `p2p interface is probed even on an unexpected subnet`() {
        // Some OEM builds hand out a non-49 subnet on a p2p interface.
        val ranked = LinkPolicy.rankGateways(
            listOf(GatewayCandidate("192.168.200.1", "p2p-p2p0-3", null)),
            userInitiated = false
        )
        assertEquals(listOf("192.168.200.1"), ranked.map { it.address })
    }

    @Test
    fun `direct ssid vouches for an unusual gateway address`() {
        val ranked = LinkPolicy.rankGateways(
            listOf(GatewayCandidate("192.168.1.1", "wlan0", "DIRECT-Ab-PeerNet-ab12")),
            userInitiated = false
        )
        assertEquals(listOf("192.168.1.1"), ranked.map { it.address })
    }

    @Test
    fun `wifi direct subnet is recognised regardless of interface name`() {
        // The picker join lands on wlan0, not p2p-*: the address is the evidence.
        assertTrue(LinkPolicy.isWifiDirectAddress("192.168.49.1"))
        assertTrue(LinkPolicy.isWifiDirectAddress("192.168.49.213"))
        assertFalse(LinkPolicy.isWifiDirectAddress("192.168.31.1"))
        assertFalse(LinkPolicy.isWifiDirectAddress(null))
    }

    // ---- liveness: do not drop healthy links ----

    @Test
    fun `connected tunnel is never dropped over missed probes`() {
        // Field report: HOST_LOST every ~25s while YouTube was still playing.
        assertFalse(
            LinkPolicy.shouldDropLink(
                consecutiveMisses = 99,
                missThreshold = 2,
                tunnelConnected = true,
                tunnelDelivering = false,
                joinedAsClient = false,
                hostIsWifiDirect = true,
                hostNetworkPresent = true
            )
        )
    }

    @Test
    fun `idle tunnel with no inbound packets is not treated as dead`() {
        // "delivering" is false whenever the user simply is not loading
        // anything; build #106 read that as proof the host had gone.
        assertFalse(
            LinkPolicy.shouldDropLink(
                consecutiveMisses = 2,
                missThreshold = 2,
                tunnelConnected = true,
                tunnelDelivering = false,
                joinedAsClient = false,
                hostIsWifiDirect = true,
                hostNetworkPresent = true
            )
        )
    }

    @Test
    fun `picker joined direct session survives probe loss without p2p callbacks`() {
        // joinedAsClient is false for a passphrase join: the host address is
        // what proves this is a Wi-Fi Direct session.
        assertFalse(
            LinkPolicy.shouldDropLink(
                consecutiveMisses = 3,
                missThreshold = 2,
                tunnelConnected = false,
                tunnelDelivering = false,
                joinedAsClient = false,
                hostIsWifiDirect = true,
                hostNetworkPresent = true
            )
        )
    }

    @Test
    fun `a single missed probe never drops the link`() {
        assertFalse(
            LinkPolicy.shouldDropLink(
                consecutiveMisses = 1,
                missThreshold = 2,
                tunnelConnected = false,
                tunnelDelivering = false,
                joinedAsClient = false,
                hostIsWifiDirect = false,
                hostNetworkPresent = true
            )
        )
    }

    // ---- liveness: still end sessions that really ended ----

    @Test
    fun `host network disappearing ends the session immediately`() {
        // Host tapped STOP: the group is gone, so the route into its subnet is
        // gone. This must clear the link (and therefore the VPN) even though
        // probe failures alone no longer do.
        assertTrue(
            LinkPolicy.shouldDropLink(
                consecutiveMisses = 0,
                missThreshold = 2,
                tunnelConnected = true,
                tunnelDelivering = true,
                joinedAsClient = true,
                hostIsWifiDirect = true,
                hostNetworkPresent = false
            )
        )
    }

    @Test
    fun `dead direct session is eventually dropped, not stranded forever`() {
        // The grace period is bounded: an unbounded veto would leave the UI
        // "connected" to a host that is never coming back.
        val grace = 2 * LinkPolicy.DIRECT_MISS_FACTOR
        assertFalse(
            LinkPolicy.shouldDropLink(
                consecutiveMisses = grace - 1,
                missThreshold = 2,
                tunnelConnected = false,
                tunnelDelivering = false,
                joinedAsClient = true,
                hostIsWifiDirect = true,
                hostNetworkPresent = true
            )
        )
        assertTrue(
            LinkPolicy.shouldDropLink(
                consecutiveMisses = grace,
                missThreshold = 2,
                tunnelConnected = false,
                tunnelDelivering = false,
                joinedAsClient = true,
                hostIsWifiDirect = true,
                hostNetworkPresent = true
            )
        )
    }

    @Test
    fun `non direct host with a dead tunnel is dropped at the threshold`() {
        assertTrue(
            LinkPolicy.shouldDropLink(
                consecutiveMisses = 2,
                missThreshold = 2,
                tunnelConnected = false,
                tunnelDelivering = false,
                joinedAsClient = false,
                hostIsWifiDirect = false,
                hostNetworkPresent = true
            )
        )
    }

    @Test
    fun `keep reason names the evidence and is null when dropping`() {
        assertEquals(
            "QUIC tunnel still connected",
            LinkPolicy.keepReason(true, false, false, false)
        )
        assertNotNull(LinkPolicy.keepReason(false, false, false, true))
        assertNull(
            "nothing is keeping this link; the report must not claim otherwise",
            LinkPolicy.keepReason(false, false, false, false)
        )
    }
}
