package com.peernet.wifiextender.host

/**
 * Pure decision logic for host sharing-session state, extracted so it can be
 * unit-tested without a device.
 *
 * ## Why this exists (build #108 regression)
 *
 * `HostForegroundService` was made to call `HostRuntime.stopSharing()` when it
 * saw hosting end, to clear the stale `sharingActive` latch. That tore down live
 * shares, because "hosting ended" is not a reliable signal:
 *
 *  * `WifiDirectManager.refreshGroupInfo()` publishes `hosting=false,
 *    creating=false` whenever `requestGroupInfo` transiently returns null, which
 *    happens during ordinary Wi-Fi churn while a group is perfectly healthy; and
 *  * `Context.stopService()` is asynchronous, so a **previous** service
 *    instance's `onDestroy` can be delivered after the user has already started
 *    a new share - and it would tear down that new share.
 *
 * The rule is therefore: a service instance may only release the latch if it
 * still belongs to the current session **and** nothing is hosting or forming.
 * Anything else is a stale or transient signal and must be ignored.
 */
object HostSessionPolicy {

    /**
     * Whether a foreground-service instance may clear the sharing latch.
     *
     * @param sharingActive the runtime's current latch
     * @param serviceSession session id captured when that service instance started
     * @param currentSession the runtime's current session id
     * @param groupLive a Wi-Fi Direct group is up
     * @param groupForming a group is being created
     * @param hostingIntended the user still wants to host (`hostingRequested`)
     */
    fun shouldReleaseLatch(
        sharingActive: Boolean,
        serviceSession: Int,
        currentSession: Int,
        groupLive: Boolean,
        groupForming: Boolean,
        hostingIntended: Boolean
    ): Boolean {
        // Nothing to release.
        if (!sharingActive) return false
        // A service instance from an earlier share must never affect a later
        // one. This is the stopService() race.
        if (serviceSession != currentSession) return false
        // A live or forming group means the "hosting ended" signal was
        // transient. Acting on it kills a working share.
        if (groupLive || groupForming) return false
        // The user has not asked to stop, so a momentary "no group" reading is a
        // gap in the framework's own bookkeeping, not the end of the session.
        // This is the case that broke SHARE in build #108: a null from
        // `requestGroupInfo` immediately after a *successful* createGroup.
        if (hostingIntended) return false
        return true
    }

    /**
     * Whether SHARE should proceed with a fresh session.
     *
     * True when nothing is actually hosting, even if the latch claims a share is
     * active: that combination is left over from a session that ended without
     * `stopSharing()` (process killed, app swiped, platform dropped the group),
     * and honouring the latch made SHARE permanently dead until app data was
     * cleared. Evaluated at the moment of the tap against live group state,
     * which is why it cannot fire spuriously the way a background signal can.
     */
    fun shouldStartFresh(
        sharingActive: Boolean,
        groupLive: Boolean,
        groupForming: Boolean
    ): Boolean = !sharingActive || !(groupLive || groupForming)
}
