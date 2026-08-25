package com.peernet.wifiextender.client

/**
 * Whether the client may link itself to a host without being asked.
 *
 * Auto-connect is what makes the app usable - the user joins the host's network
 * once and never touches the app again. But it also means a **stop** has to be
 * remembered, or it does nothing: `clearLink` deliberately re-arms auto-connect
 * (`pollMisses = 0`, `AUTOCONNECT_RESET`) so that a link lost to an error is
 * retried immediately. Applied to a stop the user asked for, that same re-arm
 * reconnects within one poll, and the Stop button looks broken.
 *
 * Leaving the Wi-Fi Direct group is not enough on its own. On API levels below
 * 29 the user associates through Android's Wi-Fi picker, and `removeGroup()`
 * does not drop a plain Wi-Fi association - so the client is still sitting on
 * the host's network with a host that is still reachable, and re-links.
 */
object AutoConnectPolicy {

    /**
     * @param userStopped the user explicitly ended the last session (button or
     *   notification), as opposed to it failing on its own
     * @param networkFingerprint identifies the network currently joined
     * @param stoppedOnFingerprint the network that was joined when the user stopped
     */
    fun shouldAutoLink(
        userStopped: Boolean,
        networkFingerprint: String?,
        stoppedOnFingerprint: String?
    ): Boolean {
        if (!userStopped) return true
        // Still on the network they stopped on: honour the stop.
        if (networkFingerprint != null && networkFingerprint == stoppedOnFingerprint) return false
        // A different network - or none identifiable - is a fresh situation the
        // user has not refused, so normal auto-connect resumes. Without this,
        // one Stop would disable auto-connect until the app was restarted.
        return true
    }

    /**
     * Whether an explicit stop should be forgotten.
     *
     * Tapping CONNECT is unambiguous new intent and must always clear it,
     * otherwise the user has a button that does nothing.
     */
    fun clearsStop(userTappedConnect: Boolean): Boolean = userTappedConnect
}
