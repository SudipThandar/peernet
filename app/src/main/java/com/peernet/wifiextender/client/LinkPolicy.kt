package com.peernet.wifiextender.client

/**
 * Pure decision logic for the client link, extracted so it can be unit-tested
 * without a device (the module has no Robolectric, so anything touching
 * `ConnectivityManager` is untestable on the JVM).
 *
 * Every function here encodes a rule that was previously implicit in
 * `ClientViewModel` and got it wrong in build #106.
 */
object LinkPolicy {

    /**
     * The /24 Android always assigns to a Wi-Fi Direct group owner.
     *
     * This is the strongest evidence available that a gateway is a PeerNet host
     * rather than an ordinary router: the framework hands the group owner
     * 192.168.49.1 and the members 192.168.49.x. It holds whether the client
     * joined through the app (interface `p2p-…`) or by typing the passphrase in
     * Android's Wi-Fi picker (interface `wlan0`), which is why an interface-name
     * test is not enough.
     */
    const val P2P_PREFIX = "192.168.49."

    /** True when [address] is inside the Wi-Fi Direct group-owner subnet. */
    fun isWifiDirectAddress(address: String?): Boolean =
        address != null && address.startsWith(P2P_PREFIX)

    /**
     * How much a gateway looks like a PeerNet host. Higher is better;
     * candidates scoring [SCORE_REJECT] must not be probed at all.
     *
     * Build #106 took `gateways.firstOrNull()`, so a client sitting on an
     * ordinary router probed `192.168.31.1:4434` forever — burning battery,
     * filling the report with failures, and (worst) treating the router as a
     * possible host. Ordinary Wi-Fi is now only probed when something else
     * vouches for it: a remembered host at that exact address, or an explicit
     * user-initiated CONNECT.
     */
    fun scoreGateway(
        address: String,
        interfaceName: String,
        ssid: String?,
        knownHostAddresses: Set<String>,
        userInitiated: Boolean
    ): Int = when {
        // The group owner's own address: unambiguous.
        isWifiDirectAddress(address) -> SCORE_P2P_SUBNET
        // A P2P interface with an unusual subnet (OEM builds do exist).
        interfaceName.startsWith("p2p", ignoreCase = true) -> SCORE_P2P_INTERFACE
        // Associated with a group by SSID even though the address looks normal.
        ssid != null && ssid.startsWith("DIRECT-", ignoreCase = true) -> SCORE_DIRECT_SSID
        // We have linked to a host at this exact address before.
        knownHostAddresses.contains(address) -> SCORE_REMEMBERED
        // The user pressed CONNECT: probing their current network is expected.
        userInitiated -> SCORE_USER_REQUEST
        // Anything else is just a router. Staying idle is the correct behaviour.
        else -> SCORE_REJECT
    }

    /**
     * Orders gateway candidates best-first and drops the ones that are not
     * PeerNet-plausible. Stable within a score so the caller's discovery order
     * is preserved.
     */
    fun rankGateways(candidates: List<GatewayCandidate>, userInitiated: Boolean): List<GatewayCandidate> =
        candidates
            .map { it to scoreGateway(it.address, it.interfaceName, it.ssid, it.knownHostAddresses, userInitiated) }
            .filter { (_, score) -> score > SCORE_REJECT }
            .sortedByDescending { (_, score) -> score }
            .map { (candidate, _) -> candidate }

    /**
     * A gateway seen on a live interface, with the evidence needed to judge it.
     */
    data class GatewayCandidate(
        val address: String,
        val interfaceName: String,
        val ssid: String? = null,
        val knownHostAddresses: Set<String> = emptySet()
    )

    /**
     * Whether a liveness watchdog may tear down the link.
     *
     * Build #106 dropped healthy tunnels roughly every 25 s. Three independent
     * reasons, all fixed here:
     *
     *  1. `p2pBacked` was read from `WifiDirectState.joinedAsClient`, which is
     *     **false** when the user joined by typing the passphrase in Android's
     *     Wi-Fi picker (no P2P client callbacks ever fire). The guard meant to
     *     protect P2P links therefore never applied to the most common way of
     *     joining one. [hostIsWifiDirect] is now the evidence instead.
     *  2. Liveness treated "no inbound packets since the last check" as proof
     *     the tunnel was dead. An **idle** tunnel — user not loading anything —
     *     looks identical. A CONNECTED QUIC tunnel is now sufficient to keep
     *     the link regardless of the probe.
     *  3. A single probe timeout counted the same as a real loss. The probe is
     *     a plain TCP connect that a sleeping host radio drops routinely.
     *
     * The signal that genuinely ends a session is [hostNetworkPresent]: when the
     * host stops sharing, its group disappears and the client's route into the
     * host subnet goes with it. That is unambiguous and immediate, so the link
     * still clears promptly on a real STOP — which the probe-based rules above
     * must never be relied on to detect.
     */
    fun shouldDropLink(
        consecutiveMisses: Int,
        missThreshold: Int,
        tunnelConnected: Boolean,
        tunnelDelivering: Boolean,
        joinedAsClient: Boolean,
        hostIsWifiDirect: Boolean,
        hostNetworkPresent: Boolean
    ): Boolean {
        // The host's network is gone: the group was torn down or Wi-Fi dropped.
        // The session is over regardless of what the tunnel last reported.
        if (!hostNetworkPresent) return true
        if (consecutiveMisses < missThreshold) return false
        // The tunnel itself is the authority: if QUIC says connected, or data is
        // still arriving, the host is demonstrably there and the probe is wrong.
        if (tunnelConnected || tunnelDelivering) return false
        // A Direct session gets a longer grace period rather than an absolute
        // veto. The probe is a plain TCP connect against a radio that power
        // saves, so occasional loss is normal — but an unbounded veto would
        // strand the UI in a "connected" state that can never end.
        val threshold =
            if (joinedAsClient || hostIsWifiDirect) missThreshold * DIRECT_MISS_FACTOR else missThreshold
        return consecutiveMisses >= threshold
    }

    /**
     * Why liveness kept the link, for the diagnostics report. Returns null when
     * nothing is keeping it (i.e. the link is being dropped).
     */
    fun keepReason(
        tunnelConnected: Boolean,
        tunnelDelivering: Boolean,
        joinedAsClient: Boolean,
        hostIsWifiDirect: Boolean
    ): String? = when {
        tunnelConnected -> "QUIC tunnel still connected"
        tunnelDelivering -> "data still arriving through the tunnel"
        joinedAsClient -> "still a member of the P2P group"
        hostIsWifiDirect -> "host is a Wi-Fi Direct group owner, network still present"
        else -> null
    }

    /**
     * Extra probe grace for Wi-Fi Direct sessions, on top of [missThreshold].
     * Bounded on purpose: the definitive end-of-session signal is the host's
     * network disappearing, not this counter.
     */
    const val DIRECT_MISS_FACTOR = 6

    const val SCORE_REJECT = 0
    const val SCORE_USER_REQUEST = 1
    const val SCORE_REMEMBERED = 2
    const val SCORE_DIRECT_SSID = 3
    const val SCORE_P2P_INTERFACE = 4
    const val SCORE_P2P_SUBNET = 5
}
