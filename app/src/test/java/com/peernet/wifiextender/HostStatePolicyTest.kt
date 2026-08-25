package com.peernet.wifiextender

import com.peernet.wifiextender.ui.host.HostState
import com.peernet.wifiextender.ui.host.HostStatePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The host screen's state must come from intent **and** observation.
 *
 * `HostUiState` was derived purely from `WifiDirectManager.state`. That state is
 * an observation of the platform, and it reports `hosting = false` for a moment
 * even while a share is healthy (a transient null from `requestGroupInfo`). The
 * screen therefore rendered IDLE during a blip: the button flipped from STOP
 * SHARING back to SHARE and the details card vanished while the engine, the
 * :4434 responder and the group were all up.
 *
 * That was never cosmetic. IDLE invited the user to tap SHARE again, which ran a
 * full stop/start, closed :4434 and recreated the group — which is what produced
 * `TCP connection refused` on the client. Fixing one producer of the transient
 * removed one trigger; these gates lock the *derivation* so any other transient
 * cannot reproduce it.
 */
class HostStatePolicyTest {

    @Test
    fun `a transient no-group report during a live share is not IDLE`() {
        // The exact regression: the user is sharing, the platform momentarily
        // reports no group, nothing has actually failed.
        assertEquals(
            HostState.CREATING_GROUP,
            HostStatePolicy.evaluate(
                sharingIntended = true,
                groupLive = false,
                error = null
            )
        )
    }

    @Test
    fun `a live group is READY`() {
        assertEquals(
            HostState.READY,
            HostStatePolicy.evaluate(sharingIntended = true, groupLive = true, error = null)
        )
    }

    @Test
    fun `not sharing is IDLE`() {
        assertEquals(
            HostState.IDLE,
            HostStatePolicy.evaluate(sharingIntended = false, groupLive = false, error = null)
        )
    }

    @Test
    fun `a live group outranks a stale error`() {
        // The staged credential fallback records an error on the failed attempt
        // and then succeeds. Reporting ERROR over a working share hid the details
        // card and contradicted the "Sharing internet" headline.
        assertEquals(
            HostState.READY,
            HostStatePolicy.evaluate(
                sharingIntended = true,
                groupLive = true,
                error = "createGroup failed: BUSY"
            )
        )
    }

    @Test
    fun `an error is reported even before the intent latch is set`() {
        // startSharing() aborts *before* setting its intent latch when location
        // services are off. Checking intent first would show IDLE and swallow the
        // one message telling the user what to fix.
        assertEquals(
            HostState.ERROR,
            HostStatePolicy.evaluate(
                sharingIntended = false,
                groupLive = false,
                error = "Turn on Location in system settings"
            )
        )
    }

    @Test
    fun `a real failure during a share is ERROR, not a permanent creating state`() {
        // Once the fallback is exhausted there is no group, no forming group and a
        // real error. Treating "intent with no group" as CREATING_GROUP
        // unconditionally would spin forever and hide the failure.
        assertEquals(
            HostState.ERROR,
            HostStatePolicy.evaluate(
                sharingIntended = true,
                groupLive = false,
                error = "Could not create the local network: ERROR"
            )
        )
    }

    @Test
    fun `the button never offers SHARE while a share is intended and nothing failed`() {
        // HomeScreen treats READY and CREATING_GROUP alike as "hosting", so the
        // button reads STOP SHARING for both. IDLE is what produced the damaging
        // re-tap, so it must be unreachable while intent stands without an error.
        val reachable = listOf(true, false).map { groupLive ->
            HostStatePolicy.evaluate(
                sharingIntended = true,
                groupLive = groupLive,
                error = null
            )
        }
        assertEquals(listOf(HostState.READY, HostState.CREATING_GROUP), reachable)
        assertEquals(0, reachable.count { it == HostState.IDLE })
    }
}
