package com.peernet.wifiextender.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peernet.wifiextender.client.ClientLinkManager
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val mode: String = "Idle",
    val isHosting: Boolean = false,
    val linkedHostName: String? = null,
    val internetAvailable: Boolean = false,
    val wifiState: String = "Unknown"
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirect: WifiDirectManager,
    private val linkManager: ClientLinkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    /** Live status while the Home screen is visible. */
    fun startObserving() {
        if (pollJob != null) return
        pollJob = viewModelScope.launch {
            while (true) {
                refreshOnce()
                delay(2_000)
            }
        }
    }

    fun stopObserving() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun refreshOnce() {
        val s = wifiDirect.state.value
        val hosting = s.hosting || s.creating
        val linkedHost = linkManager.linkedHost.value

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val wifi = when {
            caps == null -> "Off"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected (Wi-Fi)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            else -> "No network"
        }

        _uiState.value = HomeUiState(
            mode = when {
                s.creating -> "Creating network…"
                hosting -> "Sharing"
                linkedHost != null -> "Connected to host"
                else -> "Idle"
            },
            isHosting = hosting,
            linkedHostName = linkedHost?.name,
            internetAvailable = internet,
            wifiState = wifi
        )
    }
}
