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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Client logic — discovery runs when the user taps CONNECT and automatically
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
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val busy = AtomicBoolean(false)

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
                    linkManager.setLinked(null)
                    _uiState.update { it.copy(connectedHost = null, status = "Host disconnected.") }
                }
                wasJoined = joined
            }
        }
    }

    /** Live TUN capture counter for the UI (0 when not capturing). */
    fun packetCount(): Long = rustCore.tunPacketCount()

    /**
     * One explicit round: scan current network -> link to the first
     * PeerNet host found. Called only from the CONNECT button.
     * Runs several NSD rounds because mDNS after a fresh join needs time.
     */
    fun connectNow() {
        if (!busy.compareAndSet(false, true)) return
        _uiState.update {
            it.copy(searching = true, status = "Searching this network for a PeerNet host…")
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                wifiDirect.acquireMulticast()
                val target = findVerifiedHost(rounds = MANUAL_ROUNDS)
                if (target == null) {
                    _uiState.update {
                        it.copy(
                            searching = false,
                            status = "No PeerNet host on this network. Join the host's DIRECT-xx network " +
                                "in Wi-Fi settings (password is on the host phone), then tap Connect."
                        )
                    }
                } else {
                    _uiState.update { it.copy(searching = false) }
                    link(target)
                }
            } finally {
                wifiDirect.releaseMulticast()
                busy.set(false)
            }
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
                    it.copy(searching = true, status = "PeerNet network detected — looking for host…")
                }
                val target = findVerifiedHost(rounds = AUTO_ROUNDS)
                if (target != null) {
                    _uiState.update { it.copy(searching = false) }
                    link(target)
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
     */
    private suspend fun findVerifiedHost(rounds: Int): DiscoveredHost? {
        repeat(rounds) { attempt ->
            val hosts = runCatching { discovery.discoverOnce(timeoutMs = ROUND_TIMEOUT_MS) }
                .getOrDefault(emptyList())
            for (host in hosts) {
                if (probe(host)) return host
            }
            Timber.d("Round %d/%d: %d hosts advertised, none verified", attempt + 1, rounds, hosts.size)
            if (attempt < rounds - 1) delay(ROUND_GAP_MS)
        }
        return null
    }

    private fun link(host: DiscoveredHost) {
        saveProfile(host)
        linkManager.setLinked(host)
        _uiState.update {
            it.copy(
                connectedHost = host,
                savedHostIds = loadSavedHostIds(),
                status = "Linked to ${host.name}."
            )
        }
    }

    /** Drops the link and leaves any joined Wi-Fi Direct group. */
    fun disconnect() {
        linkManager.setLinked(null)
        wifiDirect.leaveCurrentGroup()
        _uiState.update { it.copy(connectedHost = null, status = "Disconnected.") }
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
    }
}
