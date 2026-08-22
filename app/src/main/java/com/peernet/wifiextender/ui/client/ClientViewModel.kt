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
 * Client logic — strictly on-demand:
 * nothing scans until the user taps CONNECT.
 */
@HiltViewModel
class ClientViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val wifiDirect: WifiDirectManager,
    private val linkManager: ClientLinkManager
) : ViewModel() {

    private val discovery = NsdClientDiscovery(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val busy = AtomicBoolean(false)

    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(savedHostIds = loadSavedHostIds()) }
    }

    /**
     * One explicit round: scan current network -> link to the first
     * PeerNet host found. Called only from the CONNECT button.
     */
    fun connectNow() {
        if (!busy.compareAndSet(false, true)) return
        _uiState.update {
            it.copy(searching = true, status = "Searching this network for a PeerNet host…")
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val hosts = runCatching { discovery.discoverOnce() }.getOrDefault(emptyList())
                val target = hosts.firstOrNull()
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
                busy.set(false)
            }
        }
    }

    private fun link(host: DiscoveredHost) {
        _uiState.update { it.copy(status = "Connecting to ${host.name}…") }
        viewModelScope.launch(Dispatchers.Default) {
            val ok = probe(host)
            if (ok) {
                saveProfile(host)
                linkManager.setLinked(host)
                _uiState.update {
                    it.copy(
                        connectedHost = host,
                        savedHostIds = loadSavedHostIds(),
                        status = "Linked to ${host.name}."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(status = "Found ${host.name} but could not reach it. Make sure the host phone is still sharing.")
                }
            }
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
    }
}
