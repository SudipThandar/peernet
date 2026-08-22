package com.peernet.wifiextender.ui.client

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ClientUiState(
    val status: String = "Not connected to any host.",
    val latencyMs: Int? = null,
    val dataUsedBytes: Long = 0L
)

@HiltViewModel
class ClientViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    // Milestone 9: NsdClientDiscovery + QrParser wiring.
}
