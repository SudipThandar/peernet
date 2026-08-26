package com.peernet.wifiextender.service

/**
 * Pure decisions for keeping the tunnel attached to a live underlying network.
 *
 * ## The defect this replaces (audit finding RC-1)
 *
 * The service used to treat one event - `onLost(ourNetwork)` - as proof that the
 * session was over, and responded by clearing the link and tearing the tunnel
 * down. It implemented `onLost` and nothing else: no `onAvailable`, no
 * `onLinkPropertiesChanged`. Network identity was the ephemeral netId string, so
 * the code had no way to express "the network was *replaced*" as distinct from
 * "the network is *gone*".
 *
 * With the screen off, Wi-Fi Direct re-associates. Android then destroys the old
 * `Network` object and publishes a new one for the same physical group - the
 * `network=723` then `network=726` pair seen in the field reports. The old code
 * saw only the first half of that and killed a session whose group was still
 * there, seconds before its replacement arrived.
 *
 * ## Why the tunnel cannot simply be left alone
 *
 * Doing nothing is not an option either. The process is attached to its network
 * with `bindProcessToNetwork`, and the Rust core has no `Endpoint::rebind`, no
 * `setsockopt` and no `SO_BINDTODEVICE` - so once the old `Network` dies, every
 * socket in the process is bound to a dead handle and no amount of waiting will
 * revive them. The QUIC socket in particular is bound at creation and cannot be
 * re-pinned.
 *
 * So the correct response is neither "kill the session" nor "carry on": it is a
 * **controlled re-establish** - rebind the process to the replacement network
 * and rebuild only the parts that cannot survive it (the QUIC endpoint), while
 * keeping the TUN file descriptor exactly as it is. Keeping the fd matters: it
 * avoids a second owner for the descriptor, keeps the VPN key steady, and does
 * not reset the sockets of every app using the tunnel.
 *
 * ## On the grace window
 *
 * [REPLACEMENT_GRACE_MS] is not a timeout being raised to paper over a failure.
 * The previous behaviour had no window at all - it acted on the first event and
 * could not be corrected. This is a bounded window in which a replacement is
 * allowed to appear, after which the session still ends exactly as before. It is
 * deliberately far below the 90 s QUIC idle timeout so recovery completes while
 * the host still considers the connection live, and short enough that a genuinely
 * dead link is reported promptly rather than hanging.
 */
object UnderlyingNetworkPolicy {

    /**
     * How long a replacement network may take to appear before the session ends.
     *
     * A Wi-Fi Direct re-association completes in a few seconds; this allows
     * generous margin without approaching `IDLE_TIMEOUT_SECS = 90`.
     */
    const val REPLACEMENT_GRACE_MS = 12_000L

    /**
     * Whether a network that has just appeared (or just acquired addresses) may
     * become the tunnel's underlying network.
     *
     * Ordering of the rejections is deliberate:
     *  - with no TUN there is no tunnel to attach, and the bring-up path owns
     *    selection instead;
     *  - the VPN is never its own underlying network. Adopting it would pin the
     *    tunnel to itself, and it could then never observe its own link dying -
     *    the failure mode `canReachHost` already guards;
     *  - a network that cannot reach the host is not a candidate, however
     *    healthy it looks. Internet capability is irrelevant here: a Wi-Fi
     *    Direct group has none.
     *
     * The final clause is the anti-churn rule. A network is adopted only when
     * the tunnel is actually looking for one - either because its own network
     * just went away ([awaitingReplacement]), or because it never had an
     * attributable one ([haveUnderlying] false, which used to leave supervision
     * silently inert). A healthy tunnel with a known network ignores unrelated
     * networks appearing, so routine events elsewhere on the device cannot move
     * it.
     */
    fun shouldAdopt(
        candidateIsVpn: Boolean,
        candidateReachesHost: Boolean,
        haveUnderlying: Boolean,
        awaitingReplacement: Boolean,
        tunInstalled: Boolean
    ): Boolean {
        if (!tunInstalled) return false
        if (candidateIsVpn) return false
        if (!candidateReachesHost) return false
        return awaitingReplacement || !haveUnderlying
    }

    /**
     * Whether adopting [newId] in place of [oldId] is a replacement of a network
     * the tunnel was already using, rather than a first-time resolution.
     *
     * Only a replacement requires the QUIC endpoint to be rebuilt. A first
     * resolution has no stale sockets to discard, and rebuilding there would
     * interrupt a bring-up that is still in progress.
     */
    fun isReplacement(oldId: String?, newId: String?): Boolean {
        if (oldId.isNullOrBlank() || newId.isNullOrBlank()) return false
        return oldId != newId
    }

    /**
     * Whether the replacement window has expired and the session must now end.
     *
     * This is the only path to teardown on network change. It exists so that
     * "the link is really gone" remains reportable, instead of the tunnel
     * hanging on a network that will never come back.
     */
    fun shouldTeardownAfterGrace(
        awaitingReplacement: Boolean,
        elapsedMs: Long,
        graceMs: Long = REPLACEMENT_GRACE_MS
    ): Boolean {
        if (!awaitingReplacement) return false
        return elapsedMs >= graceMs
    }

    /**
     * Whether losing a network should start the replacement window at all.
     *
     * Mirrors [TunnelSupervisorPolicy.lossConcernsTunnel] but stated in terms of
     * the action taken, so the service reads as a state machine rather than a
     * chain of booleans.
     */
    fun shouldAwaitReplacement(lossConcernsTunnel: Boolean): Boolean = lossConcernsTunnel
}
