package com.peernet.wifiextender

import com.peernet.wifiextender.service.UnderlyingNetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates the network-replacement state machine (audit finding RC-1).
 *
 * The regression these tests exist to prevent is the screen-off bug: the service
 * implemented `onLost` and nothing else, and treated the loss of the tunnel's own
 * network as the end of the session. Android destroys the old `Network` object
 * and publishes a replacement when a Wi-Fi Direct group re-associates - the
 * `network=723` then `network=726` pair in the field reports - so the first half
 * of a routine transition killed a session whose group was still there.
 */
class UnderlyingNetworkPolicyTest {

    // ---- adopting a replacement ----

    @Test
    fun `a replacement on the host subnet is adopted while awaiting one`() {
        assertTrue(
            "this is the screen-off recovery: without it the session ends on every " +
                "Wi-Fi Direct re-association",
            UnderlyingNetworkPolicy.shouldAdopt(
                candidateIsVpn = false,
                candidateReachesHost = true,
                haveUnderlying = false,
                awaitingReplacement = true,
                tunInstalled = true
            )
        )
    }

    @Test
    fun `a network that cannot reach the host is never adopted`() {
        // Cellular, or another Wi-Fi. Adopting it would bind the process away
        // from the P2P group and the tunnel could never reach the host.
        assertFalse(
            UnderlyingNetworkPolicy.shouldAdopt(
                candidateIsVpn = false,
                candidateReachesHost = false,
                haveUnderlying = false,
                awaitingReplacement = true,
                tunInstalled = true
            )
        )
    }

    @Test
    fun `the VPN is never adopted as its own underlying network`() {
        // Pinning the tunnel to itself makes it structurally unable to observe
        // its own link dying.
        assertFalse(
            "the VPN must never become its own underlying network",
            UnderlyingNetworkPolicy.shouldAdopt(
                candidateIsVpn = true,
                candidateReachesHost = true,
                haveUnderlying = false,
                awaitingReplacement = true,
                tunInstalled = true
            )
        )
    }

    @Test
    fun `a healthy tunnel ignores unrelated networks appearing`() {
        // Anti-churn: a tunnel that already has a known network must not move
        // just because some other network showed up.
        assertFalse(
            "a healthy attached tunnel must not be moved by unrelated events",
            UnderlyingNetworkPolicy.shouldAdopt(
                candidateIsVpn = false,
                candidateReachesHost = true,
                haveUnderlying = true,
                awaitingReplacement = false,
                tunInstalled = true
            )
        )
    }

    @Test
    fun `a tunnel with no attributable network adopts one`() {
        // This repairs the hole that made supervision silently inert: with no
        // known underlying network, a loss could never be attributed.
        assertTrue(
            UnderlyingNetworkPolicy.shouldAdopt(
                candidateIsVpn = false,
                candidateReachesHost = true,
                haveUnderlying = false,
                awaitingReplacement = false,
                tunInstalled = true
            )
        )
    }

    @Test
    fun `nothing is adopted without a TUN`() {
        // With no TUN the bring-up path owns network selection.
        assertFalse(
            UnderlyingNetworkPolicy.shouldAdopt(
                candidateIsVpn = false,
                candidateReachesHost = true,
                haveUnderlying = false,
                awaitingReplacement = true,
                tunInstalled = false
            )
        )
    }

    // ---- replacement vs first resolution ----

    @Test
    fun `a different netId is a replacement and requires a rebuild`() {
        // The QUIC socket is bound at creation and the core has no rebind, so a
        // replacement must rebuild the endpoint or the tunnel stays attached to
        // a dead handle for ever.
        assertTrue(UnderlyingNetworkPolicy.isReplacement("723", "726"))
    }

    @Test
    fun `the same netId is not a replacement`() {
        assertFalse(UnderlyingNetworkPolicy.isReplacement("726", "726"))
    }

    @Test
    fun `a first resolution is not a replacement`() {
        // Rebuilding here would interrupt a bring-up still in progress.
        assertFalse(UnderlyingNetworkPolicy.isReplacement(null, "726"))
        assertFalse(UnderlyingNetworkPolicy.isReplacement("", "726"))
        assertFalse(UnderlyingNetworkPolicy.isReplacement("723", null))
        assertFalse(UnderlyingNetworkPolicy.isReplacement("723", ""))
    }

    // ---- the bounded window ----

    @Test
    fun `the session ends when no replacement arrives in the window`() {
        // The tunnel must not wait for ever on a network that is never coming
        // back: that is the "connected but no internet" state.
        assertTrue(
            UnderlyingNetworkPolicy.shouldTeardownAfterGrace(
                awaitingReplacement = true,
                elapsedMs = UnderlyingNetworkPolicy.REPLACEMENT_GRACE_MS
            )
        )
        assertTrue(
            UnderlyingNetworkPolicy.shouldTeardownAfterGrace(
                awaitingReplacement = true,
                elapsedMs = UnderlyingNetworkPolicy.REPLACEMENT_GRACE_MS + 5_000
            )
        )
    }

    @Test
    fun `the session survives inside the window`() {
        assertFalse(
            "ending the session early is the bug being fixed",
            UnderlyingNetworkPolicy.shouldTeardownAfterGrace(
                awaitingReplacement = true,
                elapsedMs = 0
            )
        )
        assertFalse(
            UnderlyingNetworkPolicy.shouldTeardownAfterGrace(
                awaitingReplacement = true,
                elapsedMs = UnderlyingNetworkPolicy.REPLACEMENT_GRACE_MS - 1
            )
        )
    }

    @Test
    fun `a tunnel that is not awaiting a replacement is never torn down by the window`() {
        assertFalse(
            UnderlyingNetworkPolicy.shouldTeardownAfterGrace(
                awaitingReplacement = false,
                elapsedMs = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `the window is well inside the QUIC idle timeout`() {
        // IDLE_TIMEOUT_SECS = 90 in core/peernet-proto. Recovery must complete
        // while the host still considers the connection live, otherwise the
        // rebuild races the host giving up and the fix would be unreliable
        // rather than wrong.
        assertTrue(
            "grace window must leave room for the rebuild inside 90s",
            UnderlyingNetworkPolicy.REPLACEMENT_GRACE_MS < 90_000 / 2
        )
    }

    // ---- loss handling ----

    @Test
    fun `a loss that concerns the tunnel opens the replacement window`() {
        assertTrue(UnderlyingNetworkPolicy.shouldAwaitReplacement(true))
    }

    @Test
    fun `a loss that does not concern the tunnel does nothing`() {
        assertFalse(UnderlyingNetworkPolicy.shouldAwaitReplacement(false))
    }

    // ---- exhaustive ----

    @Test
    fun `only a host-reaching non-VPN network is ever adopted`() {
        var adopted = 0
        for (isVpn in listOf(false, true)) {
            for (reaches in listOf(false, true)) {
                for (have in listOf(false, true)) {
                    for (awaiting in listOf(false, true)) {
                        for (tun in listOf(false, true)) {
                            if (UnderlyingNetworkPolicy.shouldAdopt(
                                    isVpn, reaches, have, awaiting, tun
                                )
                            ) {
                                adopted++
                                assertFalse("adopted the VPN", isVpn)
                                assertTrue("adopted an unreachable network", reaches)
                                assertTrue("adopted without a TUN", tun)
                                assertTrue(
                                    "adopted while healthy and not looking",
                                    awaiting || !have
                                )
                            }
                        }
                    }
                }
            }
        }
        // reaches=true, isVpn=false, tun=true, and (awaiting || !have) -> 3 of 4
        assertEquals(3, adopted)
    }
}
