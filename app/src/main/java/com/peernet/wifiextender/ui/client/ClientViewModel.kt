package com.peernet.wifiextender.ui.client

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peernet.wifiextender.client.ClientLinkManager
import com.peernet.wifiextender.discovery.DiscoveredHost
import com.peernet.wifiextender.discovery.NsdClientDiscovery
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class ClientUiState(
    val status: String = "",
    val searching: Boolean = false,
    val connectedHost: DiscoveredHost? = null,
    val savedHostIds: Set<String> = emptySet()
)

/**
 * Client logic � discovery runs when the user taps CONNECT and automatically
 * whenever this device joins a Wi-Fi Direct network (reconnect case): once a
 * known host's network is joined, linking happens without further taps.
 */
@HiltViewModel
class ClientViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val wifiDirect: WifiDirectManager,
    private val linkManager: ClientLinkManager,
    private val rustCore: com.peernet.wifiextender.core.RustCoreBridge
) : ViewModel() {

    private val discovery = NsdClientDiscovery(context)
    private val appContext: Context = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val busy = AtomicBoolean(false)
    private var livenessJob: Job? = null

    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(savedHostIds = loadSavedHostIds()) }
        viewModelScope.launch {
            var wasJoined = false
            wifiDirect.state.collect { s ->
                val joined = s.joinedAsClient && !s.hosting
                if (joined && !wasJoined) autoLink()
                if (!joined && wasJoined && !s.hosting && _uiState.value.connectedHost != null) {
                    // Host tore down the group; drop the stale link.
                    clearLink("Host disconnected.")
                }
                wasJoined = joined
            }
        }
        // Legacy-join watcher: users who associate through Android's own
        // Wi-Fi picker (typing the passphrase) never fire Wi-Fi Direct
        // callbacks on the client side, so joinedAsClient stays false
        // forever. Poll for a reachable host on the link instead: the
        // Wi-Fi Direct group owner is our gateway, so one cheap TCP probe
        // confirms it. SSID text is only a hint — some builds hide it
        // ("<unknown ssid>") which used to make the app claim no network
        // was found while it was demonstrably connected.
        viewModelScope.launch(Dispatchers.Default) {
            val wm = appContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(LEGACY_POLL_MS)
                if (_uiState.value.connectedHost != null) continue
                if (_uiState.value.searching || busy.get()) continue
                if (wifiDirect.state.value.hosting) continue
                val ssid = runCatching {
                    @Suppress("DEPRECATION")
                    wm.connectionInfo?.ssid?.removeSurrounding("\"")
                }.getOrNull().orEmpty()
                val candidate = gatewayCandidate() ?: continue
                val verified = probeDetails(candidate) ?: continue
                Timber.i("Host detected on joined network (ssid=%s, gw=%s)", ssid, candidate.address)
                if (busy.compareAndSet(false, true)) {
                    try {
                        link(verified, viaP2p = true)
                    } finally {
                        busy.set(false)
                    }
                }
            }
        }
    }

    /** Live TUN capture counter for the UI (0 when not capturing). */
    fun packetCount(): Long = rustCore.tunPacketCount()

    /** QUIC tunnel state: 0 disconnected, 1 connecting, 2 connected, 3 backoff. */
    fun tunnelState(): Int = rustCore.tunnelState()

    /** Engine data-path counters, e.g. "tun=120 udp=44 tcp=9". */
    fun engineStats(): String = rustCore.engineStats()

    /** Plain-language tunnel progress/error for the single screen. */
    val tunnelStatus: StateFlow<String> = linkManager.tunnelStatus

    /**
     * CONNECT button. Priority order:
     *  1. Learn the host id via mDNS, then JOIN its Wi-Fi Direct network with
     *     the stable credentials (API 33+) � the phone actually associates
     *     with DIRECT-PeerNet-xxxx, visible in Wi-Fi settings.
     *  2. Otherwise find a peer advertising a PeerNet name and invite it.
     *  3. Last resort: link over whatever network the phone is on right now
     *     (e.g. both phones on the same router). Such links carry a liveness
     *     watchdog so they die when the host stops sharing.
     */
    fun connectNow() {
        if (!busy.compareAndSet(false, true)) return
        _uiState.update {
            it.copy(searching = true, status = "Searching this network for a PeerNet host�")
        }
        viewModelScope.launch(Dispatchers.Default) {
            var joined = false
            try {
                wifiDirect.acquireMulticast()

                val hid = discoverHostId()
                if (hid != null && wifiDirect.joinByCredentials(
                        ssid = "DIRECT-PeerNet-${hid.takeLast(4)}",
                        passphrase = "pn-$hid"
                    )
                ) {
                    _uiState.update { it.copy(status = "Joining the PeerNet network�") }
                    joined = awaitJoined(JOIN_WAIT_MS)
                }

                if (!joined) {
                    val peer = findPeerNetPeer()
                    if (peer != null) {
                        _uiState.update { it.copy(status = "Joining ${peer.deviceName}�") }
                        wifiDirect.connectToPeer(peer.deviceAddress)
                        joined = awaitJoined(JOIN_WAIT_MS)
                    }
                }

                if (joined) {
                    _uiState.update { it.copy(status = "PeerNet network joined � establishing link�") }
                    val target = findVerifiedHost(rounds = AUTO_ROUNDS)
                    if (target != null) {
                        _uiState.update { it.copy(searching = false) }
                        link(target, viaP2p = true)
                        return@launch
                    }
                }

                val fallback = findVerifiedHost(rounds = MANUAL_ROUNDS)
                if (fallback != null) {
                    _uiState.update { it.copy(searching = false) }
                    link(fallback, viaP2p = joined)
                } else {
                    _uiState.update {
                        it.copy(
                            searching = false,
                            status = "No PeerNet host found. Join the host's DIRECT-xx network in Wi-Fi settings " +
                                "(password is on the host phone), then tap Connect."
                        )
                    }
                }
            } finally {
                wifiDirect.releaseMulticast()
                busy.set(false)
            }
        }
    }

    /** First mDNS round used purely to read the host's identity (hid TXT). */
    private suspend fun discoverHostId(): String? =
        runCatching { discovery.discoverOnce(timeoutMs = ROUND_TIMEOUT_MS) }
            .getOrDefault(emptyList())
            .firstOrNull { !it.hostId.isNullOrBlank() }?.hostId

    /** Polls P2P state until the device reports membership in a group as client. */
    private suspend fun awaitJoined(waitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (wifiDirect.state.value.joinedAsClient) return true
            delay(JOIN_POLL_MS)
        }
        return wifiDirect.state.value.joinedAsClient
    }

    /** Wi-Fi Direct scan for a peer whose advertised name starts with PeerNet. */
    private suspend fun findPeerNetPeer(): android.net.wifi.p2p.WifiP2pDevice? {
        wifiDirect.startPeerDiscovery()
        return try {
            kotlinx.coroutines.withTimeout(PEER_SCAN_MS) {
                wifiDirect.state.first { s -> s.peers.isNotEmpty() }.peers
                    .firstOrNull { it.deviceName.startsWith("PeerNet", ignoreCase = true) }
            }
        } catch (t: Throwable) {
            Timber.d("No PeerNet-named peer discovered: %s", t.message)
            null
        } finally {
            wifiDirect.stopPeerDiscovery()
        }
    }

    /**
     * Rising edge of "joined a Wi-Fi Direct group as client" (includes app
     * cold-start while already joined). Probes with patience so slow mDNS
     * propagation after a fresh join cannot produce a false "not available".
     */
    private fun autoLink() {
        if (_uiState.value.connectedHost != null) return
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                wifiDirect.acquireMulticast()
                _uiState.update {
                    it.copy(searching = true, status = "PeerNet network detected � looking for host�")
                }
                val target = findVerifiedHost(rounds = AUTO_ROUNDS)
                if (target != null) {
                    _uiState.update { it.copy(searching = false) }
                    link(target, viaP2p = true)
                } else {
                    // Stay quiet: the user can always tap CONNECT manually.
                    _uiState.update { it.copy(searching = false, status = "") }
                }
            } finally {
                wifiDirect.releaseMulticast()
                busy.set(false)
            }
        }
    }

    /**
     * Up to [rounds] NSD rounds; returns the first host whose link banner
     * verifies. Unreachable entries are retried in later rounds instead of
     * consuming the whole attempt.
     *
     * The gateway shortcut runs first: when this phone sits on a Wi-Fi Direct
     * network, the host IS the gateway, so one TCP probe finds it even when
     * mDNS is blocked, slow, or answering on the wrong interface (the most
     * common cause of "no PeerNet network found" while actually connected).
     */
    private suspend fun findVerifiedHost(rounds: Int): DiscoveredHost? {
        gatewayCandidate()?.let { candidate ->
            probeDetails(candidate)?.let { verified ->
                Timber.i("Host found via link gateway %s", candidate.address)
                return verified
            }
        }
        repeat(rounds) { attempt ->
            val hosts = runCatching { discovery.discoverOnce(timeoutMs = ROUND_TIMEOUT_MS) }
                .getOrDefault(emptyList())
            for (host in hosts) {
                probeDetails(host)?.let { return it }
            }
            Timber.d("Round %d/%d: %d hosts advertised, none verified", attempt + 1, rounds, hosts.size)
            if (attempt < rounds - 1) {
                // A late-forming group can hand out its gateway between
                // rounds; keep retrying the cheap path too.
                gatewayCandidate()?.let { candidate ->
                    probeDetails(candidate)?.let { return it }
                }
                delay(ROUND_GAP_MS)
            }
        }
        return null
    }

    /**
     * The Wi-Fi Direct group owner (= the host) derived from routing state:
     * the default gateway of the P2P/Wi-Fi link, typically 192.168.49.1.
     * Needs no callbacks, no mDNS and no permissions.
     */
    @SuppressLint("MissingPermission")
    private fun gatewayCandidate(): DiscoveredHost? {
        val cm = appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val gateways = buildList {
            for (network in runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())) {
                val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
                val iface = lp.interfaceName.orEmpty()
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                val isWifiLike = iface.startsWith("p2p", ignoreCase = true) ||
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                if (!isWifiLike) continue
                for (route in lp.routes) {
                    val gw = route.gateway ?: continue
                    if (gw is java.net.Inet4Address && !gw.isAnyLocalAddress) add(gw.hostAddress)
                }
                // A /24 P2P group has no default route entry on some OEM
                // builds; the group owner still owns .1 of our own subnet.
                for (addr in lp.linkAddresses) {
                    val ip = addr.address
                    if (ip is java.net.Inet4Address && addr.prefixLength >= 24) {
                        val o = ip.address
                        add("${o[0].toInt() and 0xFF}.${o[1].toInt() and 0xFF}.${o[2].toInt() and 0xFF}.1")
                    }
                }
            }
        }.filterNotNull().distinct()

        val gateway = gateways.firstOrNull() ?: return null
        return DiscoveredHost(
            name = "PeerNet host",
            port = com.peernet.wifiextender.wifi.LinkServer.PORT,
            address = gateway,
            hostId = null
        )
    }

    private fun link(host: DiscoveredHost, viaP2p: Boolean) {
        saveProfile(host)
        linkManager.setLinked(host, currentWifiNetwork())
        val pinMissing = host.fingerprint.isNullOrBlank()
        _uiState.update {
            it.copy(
                connectedHost = host,
                savedHostIds = loadSavedHostIds(),
                status = when {
                    pinMissing ->
                        "Linked to ${host.name}, but the host's tunnel engine is not ready. " +
                            "Tap SHARE off/on on the host phone, then reconnect."
                    viaP2p -> "Linked to ${host.name} via the PeerNet network."
                    else -> "Linked to ${host.name} over your current Wi-Fi."
                }
            )
        }
        startLiveness(host)
    }

    /** Live network for VPN socket pinning (null = let the system choose). */
    fun linkedNetwork(): android.net.Network? = linkManager.linkedNetwork.value

    /**
     * The network the host link should ride on. Prefers the P2P interface
     * (name starts with "p2p"); falls back to any plain Wi-Fi transport.
     * Critical because Android routes app traffic over the DEFAULT network,
     * and a "connected without internet" P2P Wi-Fi loses that role to
     * cellular � where the host's private address is unreachable.
     */
    @SuppressLint("MissingPermission")
    private fun currentWifiNetwork(): android.net.Network? {
        val cm = appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networks = cm.allNetworks.toList()
        val p2p = networks.firstOrNull { n ->
            val lp = runCatching { cm.getLinkProperties(n) }.getOrNull()
            lp?.interfaceName?.startsWith("p2p", ignoreCase = true) == true
        }
        if (p2p != null) return p2p
        return networks.firstOrNull { n ->
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull()
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Watchdog for links that are NOT backed by a joined P2P group (e.g. both
     * phones on the same router): if the host stops answering, drop the link
     * instead of showing a stale "Connected". P2P-backed links are owned by
     * the group-teardown watcher above and are left alone here.
     */
    private fun startLiveness(host: DiscoveredHost) {
        livenessJob?.cancel()
        livenessJob = viewModelScope.launch(Dispatchers.Default) {
            var misses = 0
            while (isActive) {
                delay(LIVENESS_INTERVAL_MS)
                misses = if (probe(host)) 0 else misses + 1
                val p2pBacked = wifiDirect.state.value.joinedAsClient
                if (misses >= LIVENESS_MISSES && !p2pBacked) {
                    clearLink("Host disconnected.")
                    break
                }
            }
        }
    }

    private fun clearLink(message: String) {
        livenessJob?.cancel()
        livenessJob = null
        linkManager.setLinked(null)
        _uiState.update { it.copy(connectedHost = null, status = message) }
    }

    /** Drops the link and leaves any joined Wi-Fi Direct group. */
    fun disconnect() {
        clearLink("Disconnected.")
        wifiDirect.leaveCurrentGroup()
    }

    fun forget(hostId: String) {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("hid") != hostId) out.put(arr.getJSONObject(i))
        }
        prefs.edit().putString(KEY_PROFILES, out.toString()).apply()
        _uiState.update { it.copy(savedHostIds = loadSavedHostIds()) }
    }

    private suspend fun probe(host: DiscoveredHost): Boolean = probeDetails(host) != null

    /**
     * Verifies a host and returns it enriched with whatever the banner
     * reports. `PN-LINK-2` carries the QUIC certificate fingerprint and
     * tunnel port, which is the authoritative source: mDNS TXT records are
     * often stale (engine started after advertising) or dropped entirely,
     * and a client without the pin cannot open the tunnel at all.
     */
    private suspend fun probeDetails(host: DiscoveredHost): DiscoveredHost? =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { s ->
                    s.soTimeout = 3_000
                    s.connect(InetSocketAddress(host.address, host.port), 3_000)
                    val banner = s.getInputStream().bufferedReader().readLine() ?: ""
                    Timber.d("Probe banner from %s: %s", host.address, banner)
                    if (!banner.startsWith(com.peernet.wifiextender.wifi.LinkServer.BANNER_PREFIX)) {
                        return@runCatching null
                    }
                    val parts = banner.trim().split(" ")
                    val bannerHid = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    val bannerFp = parts.getOrNull(2)
                        ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                    val bannerPort = parts.getOrNull(3)?.toIntOrNull()
                    host.copy(
                        hostId = host.hostId ?: bannerHid,
                        fingerprint = bannerFp ?: host.fingerprint,
                        tunnelPort = bannerPort ?: host.tunnelPort,
                        name = if (host.name == "PeerNet host" && bannerHid != null) {
                            "PeerNet-${bannerHid.takeLast(4)}"
                        } else {
                            host.name
                        }
                    )
                }
            }.getOrElse {
                Timber.d("Probe failed for %s: %s", host.address, it.message)
                null
            }
        }

    private fun saveProfile(host: DiscoveredHost) {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        val obj = JSONObject().apply {
            put("hid", host.hostId ?: "")
            put("name", host.name)
            put("port", host.port)
            put("address", host.address ?: "")
            put("fp", host.fingerprint ?: "")
            put("tp", host.tunnelPort)
        }
        val out = JSONArray()
        out.put(obj)
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("hid") != (host.hostId ?: "")) out.put(arr.getJSONObject(i))
        }
        prefs.edit().putString(KEY_PROFILES, out.toString()).apply()
    }

    private fun loadSavedHostIds(): Set<String> {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        return buildSet {
            for (i in 0 until arr.length()) add(arr.getJSONObject(i).optString("hid"))
        }.filter { it.isNotBlank() }.toSet()
    }

    companion object {
        private const val PREFS = "peernet_client_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val MANUAL_ROUNDS = 3
        private const val AUTO_ROUNDS = 8
        private const val ROUND_TIMEOUT_MS = 4_000L
        private const val ROUND_GAP_MS = 1_200L
        private const val JOIN_WAIT_MS = 15_000L
        private const val JOIN_POLL_MS = 750L
        private const val PEER_SCAN_MS = 8_000L
        private const val LIVENESS_INTERVAL_MS = 5_000L
        private const val LIVENESS_MISSES = 2
        private const val LEGACY_POLL_MS = 4_000L
    }
}
