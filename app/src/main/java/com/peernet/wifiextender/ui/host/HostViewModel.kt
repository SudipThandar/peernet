package com.peernet.wifiextender.ui.host

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val connectedClients: Int = 0
)

@HiltViewModel
class HostViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(HostUiState())
    val uiState: StateFlow<HostUiState> = _uiState.asStateFlow()

    // Milestone 3: WifiDirectManager wiring (createGroup, requestGroupInfo, fallbacks).
}
