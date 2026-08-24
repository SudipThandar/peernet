package com.peernet.wifiextender

import com.peernet.wifiextender.host.HostSessionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for host sharing-session state.
 *
 * The first test is the build #108 regression: SHARE appeared to do nothing
 * because the foreground service tore the group down during a *successful*
 * group formation.
 */
class HostSessionPolicyTest {

    // ---- the #108 regression: SHARE did nothing ----

    @Test
    fun `transient no-group during formation must not end the share`() {
        // `groupListener.onSuccess()` clears `pendingCreate` and then calls
        // `refreshGroupInfo()`. If `requestGroupInfo` returns null before the
        // framework has registered the brand-new group, `clearGroupState()`
        // publishes hosting=false, creating=false - while the group is fine.
        // Build #108 called stopSharing() on that and removed the group.
        assertFalse(
            "a momentary no-group reading while the user still wants to host " +
                "must never end the session - this is what broke SHARE",
            HostSessionPolicy.shouldReleaseLatch(
                sharingActive = true,
                serviceSession = 1,
                currentSession = 1,
                groupLive = false,
                groupForming = false,
                hostingIntended = true
            )
        )
    }

    @Test
    fun `a dying service instance cannot end a newer share`() {
        // stopService() is asynchronous: STOP then SHARE quickly, and the old
        // instance's onDestroy arrives after session 2 has started.
        assertFalse(
            "a service instance from session 1 must not touch session 2",
            HostSessionPolicy.shouldReleaseLatch(
                sharingActive = true,
                serviceSession = 1,
                currentSession = 2,
                groupLive = false,
                groupForming = false,
                hostingIntended = false
            )
        )
    }

    @Test
    fun `a live group is never released`() {
        assertFalse(
            HostSessionPolicy.shouldReleaseLatch(
                sharingActive = true,
                serviceSession = 3,
                currentSession = 3,
                groupLive = true,
                groupForming = false,
                hostingIntended = true
            )
        )
    }

    @Test
    fun `a forming group is never released`() {
        assertFalse(
            HostSessionPolicy.shouldReleaseLatch(
                sharingActive = true,
                serviceSession = 3,
                currentSession = 3,
                groupLive = false,
                groupForming = true,
                hostingIntended = true
            )
        )
    }

    @Test
    fun `hosting that really ended is released`() {
        // User stopped (intent cleared), no group, current session: this is the
        // genuine case, and the latch must not be left set.
        assertTrue(
            HostSessionPolicy.shouldReleaseLatch(
                sharingActive = true,
                serviceSession = 4,
                currentSession = 4,
                groupLive = false,
                groupForming = false,
                hostingIntended = false
            )
        )
    }

    @Test
    fun `nothing to release when not sharing`() {
        assertFalse(
            HostSessionPolicy.shouldReleaseLatch(
                sharingActive = false,
                serviceSession = 4,
                currentSession = 4,
                groupLive = false,
                groupForming = false,
                hostingIntended = false
            )
        )
    }

    // ---- the #106 defect: SHARE dead until app data was cleared ----

    @Test
    fun `stale latch with no group starts a fresh share`() {
        // Session ended without stopSharing() (process killed, app swiped).
        // Honouring the latch made SHARE permanently dead.
        assertTrue(
            "SHARE must recover from a stale latch without clearing app data",
            HostSessionPolicy.shouldStartFresh(
                sharingActive = true,
                groupLive = false,
                groupForming = false
            )
        )
    }

    @Test
    fun `a live share is not restarted by a second tap`() {
        // Restarting would remove and recreate the group, dropping clients.
        assertFalse(
            HostSessionPolicy.shouldStartFresh(
                sharingActive = true,
                groupLive = true,
                groupForming = false
            )
        )
        assertFalse(
            HostSessionPolicy.shouldStartFresh(
                sharingActive = true,
                groupLive = false,
                groupForming = true
            )
        )
    }

    @Test
    fun `first share always starts`() {
        assertTrue(
            HostSessionPolicy.shouldStartFresh(
                sharingActive = false,
                groupLive = false,
                groupForming = false
            )
        )
    }
}
