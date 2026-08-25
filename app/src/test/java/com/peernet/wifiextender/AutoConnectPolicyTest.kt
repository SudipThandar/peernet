package com.peernet.wifiextender

import com.peernet.wifiextender.client.AutoConnectPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for auto-connect versus an explicit stop.
 *
 * Context: the client's notification now has a Stop action. Auto-connect polls
 * continuously and `clearLink` deliberately re-arms it (`pollMisses = 0`) so a
 * link lost to an error retries at once. Applied to a stop the user asked for,
 * that same re-arm reconnects within one poll - so Stop would appear to do
 * nothing at all.
 */
class AutoConnectPolicyTest {

    @Test
    fun `a user stop is honoured while still on the same network`() {
        // Leaving the group is not enough on its own: below API 29 the user
        // associates through Android's Wi-Fi picker, and removeGroup() does not
        // drop a plain Wi-Fi association, so the host stays reachable.
        assertFalse(
            "auto-connect must not undo a stop the user asked for",
            AutoConnectPolicy.shouldAutoLink(
                userStopped = true,
                networkFingerprint = "wlan0,p2p0|DIRECT-PeerNet-0718",
                stoppedOnFingerprint = "wlan0,p2p0|DIRECT-PeerNet-0718"
            )
        )
    }

    @Test
    fun `auto-connect is the default when the user has not stopped`() {
        // Auto-connect is the entire point of the app: join the host's network
        // once and never open the app again.
        assertTrue(
            AutoConnectPolicy.shouldAutoLink(
                userStopped = false,
                networkFingerprint = "wlan0,p2p0|DIRECT-PeerNet-0718",
                stoppedOnFingerprint = null
            )
        )
    }

    @Test
    fun `joining a different network resumes auto-connect`() {
        // Otherwise one Stop would disable auto-connect until the app restarted,
        // including for a host the user has since deliberately joined.
        assertTrue(
            "a different network is a situation the user never refused",
            AutoConnectPolicy.shouldAutoLink(
                userStopped = true,
                networkFingerprint = "wlan0,p2p0|DIRECT-PeerNet-9999",
                stoppedOnFingerprint = "wlan0,p2p0|DIRECT-PeerNet-0718"
            )
        )
    }

    @Test
    fun `an unidentifiable network does not keep the stop latched forever`() {
        assertTrue(
            AutoConnectPolicy.shouldAutoLink(
                userStopped = true,
                networkFingerprint = null,
                stoppedOnFingerprint = "wlan0,p2p0|DIRECT-PeerNet-0718"
            )
        )
    }

    @Test
    fun `tapping CONNECT clears an earlier stop`() {
        assertTrue(
            "otherwise CONNECT is a button that does nothing after a Stop",
            AutoConnectPolicy.clearsStop(userTappedConnect = true)
        )
        assertFalse(AutoConnectPolicy.clearsStop(userTappedConnect = false))
    }
}
