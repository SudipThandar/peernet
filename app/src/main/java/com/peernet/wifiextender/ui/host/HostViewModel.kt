package com.peernet.wifiextender.ui.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
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
    val engineReady: Boolean = false,
    /** The engine's own reason, when it refused to start. */
    val engineFailure: String? = null,
    /** Whether clients can reach the link responder on 4434 at all. */
    val linkServerListening: Boolean = false,
    val linkServerFailure: String? = null,
    /** Probes answered: >0 proves a client reached this host. */
    val probesAnswered: Int = 0
)

@HiltViewModel
class HostViewModel @Inject constructor(
    private val wifiDirect: WifiDirectManager,
    private val hostRuntime: com.peernet.wifiextender.host.HostRuntime
) : ViewModel() {

    val uiState: StateFlow<HostUiState> = combine(
        wifiDirect.state,
        // The engine and the link responder come up *after* the group, and can
        // die later. Sampling them only on Wi-Fi Direct changes left the card
        // showing stale information, which is indistinguishable from a bug in
        // the tunnel itself.
        flow {
            while (true) {
                emit(Unit)
                delay(HEALTH_POLL_MS)
            }
        }
    ) { s, _ ->
        // Intent (`sharingIntended`) and observation (`s`) are different things,
        // and they disagree whenever the platform transiently reports no group
        // during a healthy share. Deriving this from `s` alone rendered IDLE on
        // such a blip, which flipped the button back to SHARE and invited the
        // re-tap that recreated the group and closed :4434 — the actual cause of
        // the client's "connection refused". HostStatePolicy owns the decision.
        val groupLive = s.hosting && s.ssid != null
        val hostState = HostStatePolicy.evaluate(
            sharingIntended = hostRuntime.sharingIntended,
            groupLive = groupLive,
            error = s.error
        )
        HostUiState(
            hostState = hostState,
            ssid = s.ssid,
            passphrase = s.passphrase,
            passphraseAvailable = s.passphraseAvailable,
            groupOwnerAddress = s.groupOwnerAddress,
            // Surfaced only when we are actually calling this an error. A live
            // group outranks a stale error left by an earlier attempt that later
            // succeeded through the staged credential fallback, and showing both
            // "Sharing internet" and an error message at once is a contradiction.
            error = if (hostState == HostState.ERROR) s.error else null,
            engineReady = hostRuntime.engineReady,
            engineFailure = hostRuntime.engineFailure,
            linkServerListening = hostRuntime.linkServerListening,
            linkServerFailure = hostRuntime.linkServerFailure,
            probesAnswered = hostRuntime.probesAnswered
        )
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

    private companion object {
        const val HEALTH_POLL_MS = 2_000L
    }
}
