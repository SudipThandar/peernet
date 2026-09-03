package com.peernet.wifiextender.ui.home

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peernet.wifiextender.ads.AdManager
import com.peernet.wifiextender.client.ClientLinkManager
import com.peernet.wifiextender.host.ShareDuration
import com.peernet.wifiextender.core.RustCoreBridge
import com.peernet.wifiextender.mesh.MeshRelay
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
    val wifiState: String = "Unknown",
    val engineVersion: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirect: WifiDirectManager,
    private val linkManager: ClientLinkManager,
    private val rustCore: RustCoreBridge,
    val adManager: AdManager,
    private val meshRelay: MeshRelay
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _meshEnabled = MutableStateFlow(false)
    val meshEnabled: StateFlow<Boolean> = _meshEnabled.asStateFlow()

    val meshSsid: StateFlow<String?> = meshRelay.ssid
    val meshError: StateFlow<String?> = meshRelay.error

    private var pollJob: Job? = null

    fun loadAd(onLoaded: (() -> Unit)? = null) { adManager.loadAd(onLoaded) }

    fun showAd(activity: Activity, onRewarded: () -> Unit) {
        adManager.showAd(activity, onRewarded)
    }

    fun isAdReady(): Boolean = adManager.isReady

    fun loadInterstitial(onLoaded: (() -> Unit)? = null) { adManager.loadInterstitial(onLoaded) }

    fun showInterstitial(activity: Activity): Boolean = adManager.showInterstitial(activity)

    fun toggleMesh() {
        val newState = !_meshEnabled.value
        _meshEnabled.value = newState
        if (newState) {
            meshRelay.start()
        } else {
            meshRelay.stop()
        }
    }

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
                s.creating -> "Creating network\u2026"
                hosting -> "Sharing"
                linkedHost != null -> "Connected to host"
                else -> "Idle"
            },
            isHosting = hosting,
            linkedHostName = linkedHost?.name,
            internetAvailable = internet,
            wifiState = wifi,
            engineVersion = rustCore.version()
        )
    }
}
