package com.peernet.wifiextender

import com.peernet.wifiextender.service.TunnelSupervisorPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for the VPN service supervising its own tunnel.
 *
 * The reported defect: after disconnecting Wi-Fi on the client, the Android VPN
 * key and the "internet is arriving through the host" notification stayed up,
 * and the phone had no internet - because the default-route TUN was still
 * installed and swallowing every packet. Nothing owned by the service was
 * watching the link: supervision lived in `ClientViewModel`'s `viewModelScope`,
 * which dies with the UI.
 */
class TunnelSupervisorPolicyTest {

    // ---- attributing a network loss to this tunnel ----
    //
    // A true result no longer means "tear down": it opens a replacement window
    // (see UnderlyingNetworkPolicyTest). It still means "this event is about my
    // tunnel", which is what these gates check.

    @Test
    fun `losing the tunnel's own network concerns this tunnel`() {
        assertTrue(
            "the tunnel must react to its own network going away - ignoring it " +
                "keeps the VPN key up and blackholes traffic through a dead route",
            TunnelSupervisorPolicy.lossConcernsTunnel(
                lostNetwork = "101",
                underlyingNetwork = "101",
                tunInstalled = true
            )
        )
    }

    @Test
    fun `losing an unrelated network leaves the tunnel alone`() {
        // Cellular handover, another Wi-Fi going away, etc. Acting on these
        // would make a healthy tunnel flap for reasons unrelated to it.
        assertFalse(
            "only the tunnel's own underlying network may end it",
            TunnelSupervisorPolicy.lossConcernsTunnel(
                lostNetwork = "202",
                underlyingNetwork = "101",
                tunInstalled = true
            )
        )
    }

    @Test
    fun `an unknown underlying network makes a loss unattributable`() {
        // A loss that cannot be attributed must not be guessed at, or a healthy
        // session reacts whenever any unrelated network disappears.
        assertFalse(
            "an unattributable loss must not touch the tunnel",
            TunnelSupervisorPolicy.lossConcernsTunnel(
                lostNetwork = "101",
                underlyingNetwork = null,
                tunInstalled = true
            )
        )
    }

    @Test
    fun `with no TUN installed there is nothing to supervise`() {
        assertFalse(
            TunnelSupervisorPolicy.lossConcernsTunnel(
                lostNetwork = "101",
                underlyingNetwork = "101",
                tunInstalled = false
            )
        )
    }

    // ---- the client Wi-Fi lock (screen-off stalls) ----

    @Test
    fun `the client holds a wifi lock exactly while it owns a TUN`() {
        // The host has held this lock since run #108; the client never did, so a
        // screen-off stall could originate at either end with no way to tell.
        assertTrue(
            "Wi-Fi Direct power-saves with the screen off and stalls the tunnel",
            TunnelSupervisorPolicy.shouldHoldWifiLock(tunInstalled = true)
        )
        assertFalse(
            "holding the radio awake with no tunnel would only cost battery",
            TunnelSupervisorPolicy.shouldHoldWifiLock(tunInstalled = false)
        )
    }

    // ---- recovering the underlying network from the host address ----

    @Test
    fun `the network holding the host's subnet can reach the host`() {
        assertTrue(
            TunnelSupervisorPolicy.canReachHost(
                hostAddress = "192.168.49.1:4433",
                isVpn = false,
                addresses = listOf("192.168.49.37")
            )
        )
    }

    @Test
    fun `the VPN itself is never the route to the host`() {
        // The TUN's own 10.215.17.x address must never be mistaken for a route
        // to the host: the tunnel would be pinned to itself and could never
        // observe its link dying - which is the bug this class guards.
        assertFalse(
            "the VPN must never be selected as its own underlying network",
            TunnelSupervisorPolicy.canReachHost(
                hostAddress = "192.168.49.1:4433",
                isVpn = true,
                addresses = listOf("192.168.49.37", "10.215.17.2")
            )
        )
    }

    @Test
    fun `a network on a different subnet cannot reach the host`() {
        assertFalse(
            TunnelSupervisorPolicy.canReachHost(
                hostAddress = "192.168.49.1:4433",
                isVpn = false,
                addresses = listOf("10.0.0.5", "172.16.3.9")
            )
        )
    }

    @Test
    fun `a host address without a port still resolves its subnet`() {
        assertTrue(
            "the host address is stored with a :port, but must not depend on it",
            TunnelSupervisorPolicy.canReachHost(
                hostAddress = "192.168.49.1",
                isVpn = false,
                addresses = listOf("192.168.49.37")
            )
        )
    }

    @Test
    fun `an unknown host address selects nothing`() {
        assertFalse(
            TunnelSupervisorPolicy.canReachHost(
                hostAddress = null,
                isVpn = false,
                addresses = listOf("192.168.49.37")
            )
        )
        assertFalse(
            TunnelSupervisorPolicy.canReachHost(
                hostAddress = "",
                isVpn = false,
                addresses = listOf("192.168.49.37")
            )
        )
    }

    @Test
    fun `subnet matching is not a loose prefix match`() {
        // "192.168.4" must not match "192.168.49.x": a sloppy startsWith would
        // pin the tunnel to the wrong network entirely.
        assertEquals(
            false,
            TunnelSupervisorPolicy.canReachHost(
                hostAddress = "192.168.4.1:4433",
                isVpn = false,
                addresses = listOf("192.168.49.37")
            )
        )
    }
}
