package com.peernet.wifiextender.ui.host

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peernet.wifiextender.discovery.NsdHostAdvertiser
import com.peernet.wifiextender.util.Permissions
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val error: String? = null
)

@HiltViewModel
class HostViewModel @Inject constructor(
    private val wifiDirect: WifiDirectManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val advertiser = NsdHostAdvertiser(appContext)
    private var advertisingJob: Job? = null

    val uiState: StateFlow<HostUiState> = wifiDirect.state.map { s ->
        when {
            s.error != null -> HostUiState(hostState = HostState.ERROR, error = s.error)
            s.creating -> HostUiState(hostState = HostState.CREATING_GROUP)
            s.hosting && s.ssid != null -> HostUiState(
                hostState = HostState.READY,
                ssid = s.ssid,
                passphrase = s.passphrase,
                passphraseAvailable = s.passphraseAvailable,
                groupOwnerAddress = s.groupOwnerAddress
            )
            else -> HostUiState(hostState = HostState.IDLE)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HostUiState())

    init {
        // Advertise on mDNS while hosting; stop when the group goes away.
        advertisingJob = viewModelScope.launch {
            wifiDirect.state.collect { s ->
                if (s.hosting && s.ssid != null) {
                    advertiser.register(displayName = "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                } else if (!s.hosting && !s.creating) {
                    advertiser.unregister()
                }
            }
        }
    }

    fun onScreenShown() {
        wifiDirect.initialize()
    }

    fun startSharing() {
        if (Permissions.missing(appContext).isNotEmpty()) return // UI requests first
        wifiDirect.startHosting()
    }

    fun stopSharing() {
        advertiser.unregister()
        wifiDirect.stopHosting()
    }

    override fun onCleared() {
        advertiser.unregister()
        super.onCleared()
    }
}
