package com.peernet.wifiextender

import com.peernet.wifiextender.wifi.GroupLifecyclePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Create/refresh ordering for the host Wi-Fi Direct group (connection-refused
 * post-mortem, host SM-J400F / Android 29).
 *
 * `createGroup` reported success, the code cleared its `pendingCreate` guard,
 * and the very next `requestGroupInfo` returned the framework's transient null —
 * which was then treated as "session ended" and cleared the just-created group:
 *
 * ```
 * WIFI_DIRECT_GROUP_CREATED
 * WIFI_DIRECT_SESSION_CLEARED (was hosting=true)
 * ```
 *
 * That false teardown flipped the host UI to IDLE and churned the :4434
 * responder, so clients hit `TCP connection refused`. These gates lock the
 * invariant: a create is only "done" once a real group is actually observed, and
 * until then a null report must never clear the live session.
 */
class GroupLifecyclePolicyTest {

    @Test
    fun `transient null right after create keeps the live session`() {
        // The exact moment the old code broke: createGroup succeeded, so a create
        // is in flight, and the first group report is null.
        val action = GroupLifecyclePolicy.onGroupReport(
            groupPresent = false,
            createInFlight = true
        )
        assertFalse("must not clear a group we just created", action.clearSession)
        assertTrue("create is still unconfirmed until a real group is seen", action.createStillInFlight)
    }

    @Test
    fun `observing a real group confirms the create is complete`() {
        val action = GroupLifecyclePolicy.onGroupReport(
            groupPresent = true,
            createInFlight = true
        )
        assertFalse("a present group is never a teardown", action.clearSession)
        assertFalse("create is confirmed once the group is real", action.createStillInFlight)
    }

    @Test
    fun `a null report after the group was confirmed is a genuine teardown`() {
        // create no longer in flight -> a null now means the session really ended
        // and must clear (incl. client fields, so a dead host is not linked forever).
        val action = GroupLifecyclePolicy.onGroupReport(
            groupPresent = false,
            createInFlight = false
        )
        assertTrue("a null with no create in flight ends the session", action.clearSession)
        assertFalse(action.createStillInFlight)
    }

    @Test
    fun `a present group with no create in flight is steady-state hosting`() {
        val action = GroupLifecyclePolicy.onGroupReport(
            groupPresent = true,
            createInFlight = false
        )
        assertFalse(action.clearSession)
        assertFalse(action.createStillInFlight)
    }

    @Test
    fun `full create sequence never clears the session until the real teardown`() {
        // Replays the log: create in flight, transient null, group appears, then
        // a later null when the host actually stops. Only the last step clears.
        var createInFlight = true
        val reports = listOf(
            false to false, // transient null during create -> keep
            true to false,  // group registers -> confirm, keep
            true to false,  // steady broadcast -> keep
            false to true   // host stopped, group gone -> clear
        )
        val cleared = mutableListOf<Boolean>()
        for ((present, expectClear) in reports) {
            val action = GroupLifecyclePolicy.onGroupReport(present, createInFlight)
            createInFlight = action.createStillInFlight
            cleared += action.clearSession
            assertTrue(
                "clearSession for present=$present should be $expectClear",
                action.clearSession == expectClear
            )
        }
        assertTrue("exactly one teardown across the sequence", cleared.count { it } == 1)
    }
}
