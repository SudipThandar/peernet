package com.peernet.wifiextender.ui.client

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peernet.wifiextender.discovery.DiscoveredHost
import com.peernet.wifiextender.discovery.NsdClientDiscovery
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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class ClientUiState(
    val status: String = "Not connected to any host.",
    val discovering: Boolean = false,
    val connectingTo: String? = null,
    val connectedHost: DiscoveredHost? = null,
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val savedHostIds: Set<String> = emptySet()
)

@HiltViewModel
class ClientViewModel @Inject constructor(
    @ApplicationContext context: Context
) : ViewModel() {

    private val discovery = NsdClientDiscovery(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val scanning = AtomicBoolean(false)
    private var observeJob: Job? = null

    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(savedHostIds = loadSavedHostIds()) }
    }

    // ---------- Discovery ----------

    /** Background rescan loop while the Client screen is open (spec 15.3: every 30s). */
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

    /** Manual refresh — always rebuilds the list, so hosts that stopped sharing disappear. */
    fun refreshHosts() {
        viewModelScope.launch(Dispatchers.Default) { scan() }
    }

    private suspend fun scan() {
        if (!scanning.compareAndSet(false, true)) return
        try {
            _uiState.update {
                it.copy(
                    discovering = true,
                    status = if (it.connectedHost == null) "Searching for PeerNet hosts nearby…" else it.status
                )
            }
            val hosts = discovery.discoverOnce()
            val savedIds = loadSavedHostIds()

            _uiState.update { prev ->
                val stillConnected = prev.connectedHost?.let { c -> hosts.any { it.hostId == c.hostId } }
                copy(
                    prev,
                    hosts = hosts,
                    savedHostIds = savedIds,
                    connectedHost = if (stillConnected == true) prev.connectedHost else null,
                    discovering = false,
                    status = when {
                        prev.connectingTo != null -> prev.status
                        stillConnected == false -> "Lost connection to host. Searching…"
                        hosts.isEmpty() -> if (savedIds.isNotEmpty()) "No PeerNet hosts found nearby." else "No PeerNet hosts found. Start sharing on the other phone first."
                        else -> prev.status
                    }
                )
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

    /**
     * Links with a discovered host and verifies reachability.
     * Full internet forwarding activates once the PNTP tunnel engine ships (M5–M7).
     */
    fun connect(host: DiscoveredHost, auto: Boolean = false) {
        if (_uiState.value.connectingTo != null || host.address == null) return
        _uiState.update {
            it.copy(
                connectingTo = host.name,
                status = "${if (auto) "Auto-connecting" else "Connecting"} to ${host.name}…"
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            val reachable = probe(host)

            if (reachable) {
                saveProfile(host)
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
                        status = "Could not reach ${host.name}. Make sure you are connected to its Wi-Fi Direct network."
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
                true
            }
        }.getOrElse {
            Timber.d("Probe failed for %s: %s", host.name, it.message)
            false
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

        private fun copy(
            prev: ClientUiState,
            hosts: List<DiscoveredHost>,
            savedHostIds: Set<String>,
            connectedHost: DiscoveredHost?,
            discovering: Boolean,
            status: String
        ) = ClientUiState(
            status = status,
            discovering = discovering,
            connectingTo = prev.connectingTo,
            connectedHost = connectedHost,
            discoveredHosts = hosts,
            savedHostIds = savedHostIds
        )
    }
}
