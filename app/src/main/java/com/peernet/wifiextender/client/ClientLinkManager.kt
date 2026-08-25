package com.peernet.wifiextender.client

import android.net.Network
import com.peernet.wifiextender.discovery.DiscoveredHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide record of the client's current host link, so any screen
 * (Home, Client) can react to it. Also remembers the network the link
 * rides on so the VPN pins its QUIC sockets to it (the P2P Wi-Fi is
 * usually marked "no internet", making the default route cellular).
 *
 * ## Why this is the owner of the tunnel's lifetime (build #106 post-mortem)
 *
 * The VPN used to be started and stopped by a Compose `LaunchedEffect` keyed on
 * `client.connectedHost`, read through `collectAsStateWithLifecycle()`. That
 * collection **stops when the Activity stops**, so when the screen went off or
 * the app was backgrounded the UI never observed the link clearing — and
 * `stopVpn()` was never called. The tunnel, the TUN interface and the Android
 * VPN key all survived a host that had stopped sharing.
 *
 * The link is app state, not screen state, so it lives in this singleton and
 * `PeerNetVpnService` observes it directly. No composition, no lifecycle.
 */
@Singleton
class ClientLinkManager @Inject constructor() {

    private val _linkedHost = MutableStateFlow<DiscoveredHost?>(null)
    val linkedHost: StateFlow<DiscoveredHost?> = _linkedHost.asStateFlow()

    private val _linkedNetwork = MutableStateFlow<Network?>(null)
    val linkedNetwork: StateFlow<Network?> = _linkedNetwork.asStateFlow()

    /**
     * Plain-language tunnel progress written by the VPN service and read by
     * the single screen. Users have no adb, so every failure has to be
     * explainable from the UI alone.
     */
    private val _tunnelStatus = MutableStateFlow("")
    val tunnelStatus: StateFlow<String> = _tunnelStatus.asStateFlow()

    /**
     * True while `PeerNetVpnService` actually holds an established TUN.
     *
     * The client used to report `CLIENT_CLEANUP_COMPLETED` immediately after
     * clearing its own fields, while `tun0` and the VPN key were still up. This
     * is the real answer, published by the service itself, so cleanup can be
     * reported only once it has finished.
     */
    private val _tunnelActive = MutableStateFlow(false)
    val tunnelActive: StateFlow<Boolean> = _tunnelActive.asStateFlow()

    private val generations = AtomicInteger(0)

    /**
     * Identifies the current link attempt. Incremented on every [setLinked],
     * including clears, so any delayed retry, liveness probe or bring-up thread
     * started by a previous session can detect that it is obsolete and must not
     * resurrect it.
     */
    @Volatile
    var generation: Int = 0
        private set

    fun setTunnelStatus(text: String) {
        _tunnelStatus.value = text
    }

    fun setTunnelActive(active: Boolean) {
        _tunnelActive.value = active
    }

    /**
     * Publishes the link (or clears it) and starts a new generation.
     *
     * Returns the new generation so the caller can stamp the work it starts for
     * this session and abandon it once the number moves on.
     */
    fun setLinked(host: DiscoveredHost?, network: Network? = null): Int {
        val gen = generations.incrementAndGet()
        generation = gen
        _linkedHost.value = host
        _linkedNetwork.value = if (host != null) network else null
        if (host == null) _tunnelStatus.value = ""
        return gen
    }

    /** True while [gen] is still the live session. */
    fun isCurrent(gen: Int): Boolean = generation == gen

    /**
     * Counts explicit user requests to stop the tunnel from outside the UI - the
     * notification's Stop action.
     *
     * A counter rather than a flag or an event: it is observable by a `StateFlow`
     * collector that may not exist yet, it needs no reset, and a second Stop is
     * distinguishable from the first.
     *
     * The client's UI state is owned by `ClientViewModel`, not by this singleton,
     * so clearing the link here would tear the tunnel down while the screen still
     * read "Connected to …". The ViewModel watches this and runs its own tested
     * disconnect path, keeping one owner for the visible state.
     */
    private val _stopRequests = MutableStateFlow(0)
    val stopRequests: StateFlow<Int> = _stopRequests.asStateFlow()

    fun requestStop() {
        _stopRequests.value = _stopRequests.value + 1
    }
}
