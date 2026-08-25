package com.peernet.wifiextender.ui.host

/**
 * Decides the host's visible [HostState] from **intent plus observation**.
 *
 * ## Why this exists (the recurring "share looks dead while it is fine" defect)
 *
 * `HostUiState` used to be derived *purely* from `WifiDirectManager.state`:
 *
 * ```
 * s.hosting && s.ssid != null -> READY
 * else                        -> IDLE
 * ```
 *
 * `WifiDirectManager.state` is an **observation** of the platform, and that
 * observation is not continuously true even while hosting is perfectly healthy.
 * `requestGroupInfo` transiently returns null (right after a successful
 * `createGroup`, and on later re-reads while the radio power-saves), which
 * publishes `hosting = false` for a moment. Deriving the screen from that alone
 * meant a momentary platform blip rendered **IDLE**: the button flipped from
 * STOP SHARING back to SHARE and the details card vanished, while the engine,
 * the :4434 responder, the foreground service and the group were all still up.
 *
 * That false IDLE was never only cosmetic. It invited the user to tap SHARE
 * again, which ran a full stop/start — closing :4434 and recreating the group —
 * and *that* is what produced `TCP connection refused` on the client. The same
 * mechanism is the likeliest explanation for "the host stopped sharing when the
 * screen went off".
 *
 * The missing input was **intent**: `HostRuntime.sharingIntended`. Fixing one
 * producer of the transient (the create-time null) removed one trigger and left
 * the fragility in place; any other transient reproduces the symptom. So the
 * screen now asks both "does the user want to share?" and "what does the
 * platform currently report?", and only calls it IDLE when the answer to the
 * first is no.
 *
 * ## Why the ordering is what it is
 *
 * 1. **A live group wins outright.** It is the strongest evidence that exists,
 *    and it must override a stale `error` left behind by an earlier attempt that
 *    later succeeded through the staged credential fallback. Reporting ERROR over
 *    a working share was its own contradiction.
 * 2. **An error outranks intent.** `HostRuntime.startSharing` aborts *before*
 *    setting its intent latch when location services are off, so at that point
 *    intent is false and an error is set. Checking intent first would show IDLE
 *    and swallow the one message that tells the user what to fix.
 * 3. **Intent decides the remaining ambiguity.** With no live group and no error:
 *    if the user is not sharing this is genuinely IDLE; if the user *is* sharing
 *    then the group is either forming or briefly unreported, and both mean "still
 *    working on it" — never IDLE.
 *
 * Note that `CREATING_GROUP` is what keeps the button showing STOP SHARING
 * (`HomeScreen` treats READY and CREATING_GROUP alike as "hosting"), which is
 * precisely what stops a blip from inviting the re-tap that churns the port.
 *
 * Pure function: the module has no Robolectric, so a decision that touches
 * `WifiP2pManager` or a ViewModel cannot be tested at all unless it is extracted.
 */
object HostStatePolicy {

    /**
     * @param sharingIntended the user asked to share and it has not been stopped
     *        (`HostRuntime.sharingIntended`) — the authority on intent
     * @param groupLive the platform currently reports a group we own *and* an SSID
     *        (`state.hosting && state.ssid != null`) — the authority on observation
     * @param error the Wi-Fi Direct layer's current error, if any
     */
    fun evaluate(
        sharingIntended: Boolean,
        groupLive: Boolean,
        error: String?
    ): HostState = when {
        groupLive -> HostState.READY
        error != null -> HostState.ERROR
        !sharingIntended -> HostState.IDLE
        else -> HostState.CREATING_GROUP
    }
}
