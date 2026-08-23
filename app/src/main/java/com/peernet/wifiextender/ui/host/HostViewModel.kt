package com.peernet.wifiextender.ui.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class HostState {
    IDLE,
    CREATING_GROUP,
    READY,
    ERROR
}

data class HostUiState(
    val hostState: HostState = HostState.IDLE,
    val ssid: String? = null,
    val passphrase: String? = null,
    val passphraseAvailable: Boolean = true,
    val groupOwnerAddress: String? = null,
    val connectedClients: Int = 0,
    val error: String? = null,
    /** False when the QUIC engine never came up — clients cannot tunnel. */
    val engineReady: Boolean = false
)

@HiltViewModel
class HostViewModel @Inject constructor(
    private val wifiDirect: WifiDirectManager,
    private val hostRuntime: com.peernet.wifiextender.host.HostRuntime
) : ViewModel() {

    val uiState: StateFlow<HostUiState> = wifiDirect.state.map { s ->
        when {
            s.error != null -> HostUiState(hostState = HostState.ERROR, error = s.error)
            s.creating -> HostUiState(hostState = HostState.CREATING_GROUP)
            s.hosting && s.ssid != null -> HostUiState(
                hostState = HostState.READY,
                ssid = s.ssid,
                passphrase = s.passphrase,
                passphraseAvailable = s.passphraseAvailable,
                groupOwnerAddress = s.groupOwnerAddress,
                engineReady = hostRuntime.engineReady
            )
            else -> HostUiState(hostState = HostState.IDLE)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HostUiState())

    fun onScreenShown() {
        wifiDirect.initialize()
    }

    fun startSharing() {
        hostRuntime.startSharing()
    }

    fun stopSharing() {
        hostRuntime.stopSharing()
    }
}
