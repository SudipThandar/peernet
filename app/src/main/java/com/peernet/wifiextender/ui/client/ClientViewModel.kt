package com.peernet.wifiextender.ui.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peernet.wifiextender.discovery.DiscoveredHost
import com.peernet.wifiextender.discovery.NsdClientDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ClientUiState(
    val status: String = "Not connected to any host.",
    val discovering: Boolean = false,
    val discoveredHosts: List<DiscoveredHost> = emptyList()
)

@HiltViewModel
class ClientViewModel @Inject constructor(
    @ApplicationContext context: Context
) : ViewModel() {

    private val discovery = NsdClientDiscovery(context)

    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    fun discoverHosts() {
        if (_uiState.value.discovering) return
        _uiState.update {
            it.copy(
                discovering = true,
                status = "Searching for PeerNet hosts nearby…",
                discoveredHosts = emptyList()
            )
        }
        viewModelScope.launch(Dispatchers.Default) {
            val hosts = discovery.discoverOnce(timeoutMs = 10_000)
            _uiState.update {
                it.copy(
                    discovering = false,
                    discoveredHosts = hosts,
                    status = when {
                        hosts.isEmpty() -> "No PeerNet hosts found. Make sure the host phone is sharing and both phones are on its network."
                        else -> "${hosts.size} host(s) found."
                    }
                )
            }
        }
    }
}
