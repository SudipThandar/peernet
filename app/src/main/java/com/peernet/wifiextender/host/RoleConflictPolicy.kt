package com.peernet.wifiextender.host

/**
 * What tapping SHARE should do when the phone is already acting as a client.
 */
enum class ShareAction {
    /** No conflict - start sharing straight away. */
    PROCEED,

    /** Ask first: starting will end the session this phone is receiving on. */
    CONFIRM_REPLACING_CLIENT_LINK
}

/**
 * Guards the one-role-at-a-time rule.
 *
 * A phone cannot host and receive at the same time. `WifiP2pManager` gives a
 * device a single P2P group, and the chipsets in the test devices have no P2P
 * concurrency, so creating a group as host necessarily destroys the group this
 * phone joined as a client. That is a radio limitation, not something the app can
 * code around - a true daisy-chained extender is not implementable here.
 *
 * What *was* an app bug: `HostRuntime.startSharing` had no guard at all, so
 * tapping SHARE while connected as a client let Android tear the client group down
 * underneath the user with no warning and no explanation. The internet simply
 * stopped, and the reported symptom was the phone "silently reconnecting to the
 * router and starting to share".
 *
 * So the app asks. On confirmation the caller is expected to end the client
 * session *deliberately* - drop the VPN, leave the group - before hosting, so the
 * teardown is orderly and observable instead of a silent yank.
 */
object RoleConflictPolicy {

    /**
     * @param clientLinkActive this phone currently holds a link to a host.
     * @param tunnelActive the VPN tunnel is still up.
     *
     * Both are checked because they can disagree: the link can be cleared while
     * the VPN service is still tearing down, and the tunnel can be up for a link
     * this screen has already forgotten. Either one means the user is receiving
     * internet right now and would lose it.
     */
    fun evaluateShareRequest(clientLinkActive: Boolean, tunnelActive: Boolean): ShareAction =
        if (clientLinkActive || tunnelActive) {
            ShareAction.CONFIRM_REPLACING_CLIENT_LINK
        } else {
            ShareAction.PROCEED
        }
}
