package com.peernet.wifiextender.service

/**
 * Pure decisions for the VPN service's supervision of its own tunnel.
 *
 * Extracted so they can be unit-tested on the JVM: this project has no
 * Robolectric, so anything touching `Network`/`ConnectivityManager` directly is
 * untestable. The service passes network identities as strings
 * (`Network.toString()`, which is the netId) and keeps the Android types to
 * itself.
 *
 * Why this exists at all: the client's link supervision used to live entirely in
 * `ClientViewModel`, i.e. in `viewModelScope`. That scope dies with the UI, so
 * once the Activity went away (screen off long enough, app swiped, memory
 * pressure) nothing was left watching the link. The tunnel then outlived its own
 * network: the Android VPN key and the "internet is arriving through the host"
 * notification stayed up, while the default-route TUN quietly swallowed every
 * packet - which reads as "connected but no internet". The service owns a
 * foreground lifetime, so supervision belongs here.
 */
object TunnelSupervisorPolicy {

    /**
     * Whether losing [lostNetwork] must end the tunnel.
     *
     * Only the tunnel's *own* underlying network counts. Any other network
     * disappearing is routine (cellular handover, another Wi-Fi going away) and
     * must not touch a working tunnel, or the tunnel would flap for reasons that
     * have nothing to do with it.
     *
     * A loss is never actionable when the tunnel does not own a TUN
     * ([tunInstalled] false) - there is nothing to tear down - nor when the
     * underlying network is unknown, because then the loss cannot be attributed
     * and guessing would tear down a healthy session.
     */
    fun shouldTeardownOnLoss(
        lostNetwork: String?,
        underlyingNetwork: String?,
        tunInstalled: Boolean
    ): Boolean {
        if (!tunInstalled) return false
        if (lostNetwork.isNullOrBlank() || underlyingNetwork.isNullOrBlank()) return false
        return lostNetwork == underlyingNetwork
    }

    /**
     * The client holds a Wi-Fi lock exactly while it owns a live TUN.
     *
     * Wi-Fi Direct power-saves aggressively with the screen off: the group owner
     * stops servicing the group and the tunnel stalls. The host already holds
     * this lock (`HostRuntime`); the client never did, so the screen-off stall
     * could originate from either end. This is a `WifiManager` lock, not a
     * `PowerManager` wake lock - it does not hold the CPU and does not keep the
     * screen on.
     */
    fun shouldHoldWifiLock(tunInstalled: Boolean): Boolean = tunInstalled

    /**
     * Whether a network holding [addresses] is the one that can reach
     * [hostAddress] (which may carry a `:port` suffix).
     *
     * Used to recover the underlying network when the UI did not supply it. The
     * VPN itself is always rejected: its own `10.215.17.x` address must never be
     * mistaken for a route to the host, or the tunnel would be pinned to itself
     * and could never notice its link dying.
     */
    fun canReachHost(
        hostAddress: String?,
        isVpn: Boolean,
        addresses: List<String>
    ): Boolean {
        if (isVpn) return false
        val subnet = hostAddress
            ?.substringBefore(':')
            ?.substringBeforeLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return addresses.any { it.startsWith("$subnet.") }
    }
}
