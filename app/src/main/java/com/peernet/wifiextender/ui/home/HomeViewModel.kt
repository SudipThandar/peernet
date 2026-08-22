package com.peernet.wifiextender.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class HomeUiState(
    val mode: String = "Idle",
    val internetAvailable: Boolean = false,
    val wifiState: String = "Unknown"
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirect: WifiDirectManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun refresh() {
        wifiDirect.initialize()

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
            mode = if (wifiDirect.state.value.hosting) "Hosting" else "Idle",
            internetAvailable = internet,
            wifiState = wifi
        )
    }

    fun startHosting() {
        wifiDirect.startHosting()
    }
}
