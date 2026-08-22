package com.peernet.wifiextender.ui.client

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class PeerNetwork(
    val name: String,
    val address: String?
)

data class ClientUiState(
    val status: String = "Not connected to any host.",
    val discovering: Boolean = false,
    val connectingTo: String? = null,
    val connectedHost: DiscoveredHost? = null,
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val savedHostIds: Set<String> = emptySet(),
    val nearbyPeers: List<PeerNetwork> = emptyList(),
    val joinedToHostAddress: String? = null
)

@HiltViewModel
class ClientViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val wifiDirect: WifiDirectManager,
    private val linkManager: ClientLinkManager
) : ViewModel() {

    private val discovery = NsdClientDiscovery(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val scanning = AtomicBoolean(false)
    private var observeJob: Job? = null

    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(savedHostIds = loadSavedHostIds()) }
        wifiDirect.initialize()

        // Restore link state if a previous session linked (process restart keeps prefs,
        // live link re-verifies on next scan via auto-reconnect).
        linkManager.linkedHost.value?.let { host ->
            _uiState.update { it.copy(connectedHost = host, status = "Linked to ${host.name}.") }
        }

        // React to P2P join events: once we land on the host's network, scan for it.
        viewModelScope.launch(Dispatchers.Default) {
            var wasJoined = false
            while (true) {
                val s = wifiDirect.state.value
                _uiState.update {
                    it.copy(
                        joinedToHostAddress = s.joinedGroupOwnerAddress,
                        nearbyPeers = s.peers.map { p -> PeerNetwork(p.deviceName ?: p.deviceAddress, p.deviceAddress) }
                    )
                }
                if (!s.joinedAsClient && wasJoined && _uiState.value.connectedHost != null) {
                    // Host group vanished while linked.
                    unlink("Host network disconnected.")
                }
                if (s.joinedAsClient && !wasJoined) {
                    delay(2_000) // let DHCP settle on the P2P interface
                    scan()
                }
                wasJoined = s.joinedAsClient
                delay(1_000)
            }
        }
    }

    // ---------- Wi-Fi Direct join ----------

    fun searchNearbyNetworks() {
        wifiDirect.initialize()
        wifiDirect.startPeerDiscovery()
        _uiState.update { it.copy(status = "Searching for nearby PeerNet networksâ€¦") }
        viewModelScope.launch(Dispatchers.Default) {
            delay(6_000)
            wifiDirect.stopPeerDiscovery()
            // Also run mDNS in parallel for hosts reachable via any shared LAN.
            scan()
        }
    }

    fun joinPeer(address: String) {
        wifiDirect.connectToPeer(address)
        _uiState.update { it.copy(status = "Joining host networkâ€¦") }
    }

    // ---------- Discovery ----------

    fun startObserving() {
        if (observeJob != null) return
        observeJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                scan()
                delay(30_000)
            }
        }
    }

    fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }

    fun refreshHosts() {
        viewModelScope.launch(Dispatchers.Default) { scan() }
    }

    private suspend fun scan() {
        if (!scanning.compareAndSet(false, true)) return
        try {
            _uiState.update {
                it.copy(
                    discovering = true,
                    status = if (it.connectedHost == null) "Searching for PeerNet hosts nearbyâ€¦" else it.status
                )
            }
            val hosts = discovery.discoverOnce()
            val savedIds = loadSavedHostIds()
            val hadConnected = _uiState.value.connectedHost

            _uiState.update { prev ->
                val stillConnected = prev.connectedHost?.let { c -> hosts.any { it.hostId == c.hostId } }
                prev.copy(
                    discoveredHosts = hosts,
                    savedHostIds = savedIds,
                    connectedHost = if (stillConnected == true) prev.connectedHost else null,
                    discovering = false,
                    status = when {
                        prev.connectingTo != null -> prev.status
                        stillConnected == false -> "Lost connection to host. Searchingâ€¦"
                        hosts.isEmpty() && prev.joinedToHostAddress != null ->
                            "On a PeerNet network but no host answered. Is the other phone still sharing?"
                        hosts.isEmpty() ->
                            if (savedIds.isNotEmpty()) "No PeerNet hosts found nearby."
                            else "No hosts found. Tap 'Search Nearby Networks' to find and join one."
                        else -> prev.status
                    }
                )
            }

            if (hadConnected != null && _uiState.value.connectedHost == null) {
                linkManager.setLinked(null)
            }

            // Auto-reconnect to a known/paired host (FR-CLIENT-006).
            if (_uiState.value.connectedHost == null && _uiState.value.connectingTo == null) {
                val known = hosts.firstOrNull { it.hostId != null && it.hostId in savedIds }
                if (known != null) connect(known, auto = true)
            }
        } finally {
            scanning.set(false)
        }
    }

    // ---------- Connect ----------

    fun connect(host: DiscoveredHost, auto: Boolean = false) {
        if (_uiState.value.connectingTo != null || host.address == null) return
        _uiState.update {
            it.copy(
                connectingTo = host.name,
                status = "${if (auto) "Auto-connecting" else "Connecting"} to ${host.name}â€¦"
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            val reachable = probe(host)

            if (reachable) {
                saveProfile(host)
                linkManager.setLinked(host)
                _uiState.update {
                    it.copy(
                        connectingTo = null,
                        connectedHost = host,
                        savedHostIds = loadSavedHostIds(),
                        status = "Linked to ${host.name}. Internet forwarding starts once the tunnel engine is enabled."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        connectingTo = null,
                        status = "Could not reach ${host.name}. Join its Wi-Fi Direct network first."
                    )
                }
            }
        }
    }

    private suspend fun probe(host: DiscoveredHost): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { s ->
                s.soTimeout = 3_000
                s.connect(InetSocketAddress(host.address, host.port), 3_000)
                val banner = s.getInputStream().bufferedReader().readLine() ?: ""
                Timber.d("Probe banner from %s: %s", host.name, banner)
                banner.startsWith(com.peernet.wifiextender.wifi.LinkServer.BANNER_PREFIX)
            }
        }.getOrElse {
            Timber.d("Probe failed for %s: %s", host.name, it.message)
            false
        }
    }

    // ---------- Disconnect ----------

    /** Drops the link and leaves the host's Wi-Fi Direct group. */
    fun disconnect() {
        unlink("Disconnected.")
        wifiDirect.leaveCurrentGroup()
    }

    private fun unlink(message: String) {
        linkManager.setLinked(null)
        _uiState.update {
            it.copy(
                connectedHost = null,
                status = message
            )
        }
    }

    // ---------- Persistence ----------

    private fun saveProfile(host: DiscoveredHost) {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        val obj = JSONObject().apply {
            put("hid", host.hostId ?: "")
            put("name", host.name)
            put("port", host.port)
            put("address", host.address ?: "")
        }
        val out = JSONArray()
        out.put(obj)
        for (i in 0 until arr.length()) {
            val existing = arr.getJSONObject(i)
            if (existing.optString("hid") != (host.hostId ?: "")) out.put(existing)
        }
        prefs.edit().putString(KEY_PROFILES, out.toString()).apply()
    }

    private fun loadSavedHostIds(): Set<String> {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        return buildSet {
            for (i in 0 until arr.length()) add(arr.getJSONObject(i).optString("hid"))
        }.filter { it.isNotBlank() }.toSet()
    }

    override fun onCleared() {
        stopObserving()
        super.onCleared()
    }

    companion object {
        private const val PREFS = "peernet_client_profiles"
        private const val KEY_PROFILES = "profiles"
    }
}
