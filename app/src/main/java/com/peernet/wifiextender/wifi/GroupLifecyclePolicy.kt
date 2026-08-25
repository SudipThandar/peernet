package com.peernet.wifiextender.wifi

/**
 * Pure decision logic for the host's Wi-Fi Direct group lifecycle, extracted so
 * the create/refresh ordering can be unit-tested without a device (the module
 * has no Robolectric, so anything touching `WifiP2pManager` is untestable).
 *
 * ## Why this exists (the "group created then immediately cleared" bug)
 *
 * `WifiP2pManager.createGroup` reports success through its `ActionListener`
 * **before** `requestGroupInfo` will return the new group: for a short window
 * after the success callback the framework still answers `requestGroupInfo` with
 * `null`. The manager treats a null group report as "the session ended" and
 * clears its state.
 *
 * The old code cleared its `pendingCreate` guard at the *top* of the success
 * callback and then immediately called `requestGroupInfo`. That first report was
 * the transient null, `pendingCreate` was already false, so the just-created,
 * perfectly healthy group was wiped:
 *
 * ```
 * WIFI_DIRECT_GROUP_CREATED
 * WIFI_DIRECT_SESSION_CLEARED (was hosting=true)   ← the bug
 * ...HOST_READY only later, once a broadcast re-read the group
 * ```
 *
 * That false "hosting ended" made the host card flip to IDLE (the SHARE button
 * and details card vanished though hosting was fine), which invited the user to
 * tap SHARE again and churn the group and the :4434 responder — which in turn is
 * why clients saw `TCP connection refused` (the responder was mid-restart when
 * they knocked).
 *
 * The invariant this encodes: a create is only "done" once a real group has
 * actually been observed. Until then, a null report is creation latency, not a
 * teardown, and must not clear the session.
 */
object GroupLifecyclePolicy {

    /**
     * What to do with a `requestGroupInfo` report.
     *
     * @param groupPresent the framework returned a non-null group
     * @param createInFlight a `createGroup` we started has not yet been confirmed
     *        by a real group report (the manager's `pendingCreate`)
     */
    fun onGroupReport(groupPresent: Boolean, createInFlight: Boolean): GroupReportAction =
        if (groupPresent) {
            // A real group exists: whatever create was in flight is now complete.
            // From here a null report is a genuine teardown, not creation latency.
            GroupReportAction(clearSession = false, createStillInFlight = false)
        } else {
            // No group. While a create is in flight this is the expected transient
            // null that appears between the success callback and the group
            // registering, so the live session must be kept. Otherwise the group
            // really is gone and the session is cleared.
            GroupReportAction(clearSession = !createInFlight, createStillInFlight = createInFlight)
        }
}

/**
 * The outcome of [GroupLifecyclePolicy.onGroupReport].
 *
 * @param clearSession clear all group-derived state (the session has ended)
 * @param createStillInFlight the new value for the manager's `pendingCreate`
 */
data class GroupReportAction(
    val clearSession: Boolean,
    val createStillInFlight: Boolean
)
