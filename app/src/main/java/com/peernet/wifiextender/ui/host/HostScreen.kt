package com.peernet.wifiextender.ui.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HostScreen(
    viewModel: HostViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Host Mode", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("State: ${state.hostState}")
                Text(
                    text = when {
                        state.ssid != null -> "Network: ${state.ssid}"
                        else -> "Not sharing yet."
                    }
                )
                if (!state.passphraseAvailable) {
                    Text("Passphrase unavailable — connect manually to the network above.")
                }
            }
        }

        Button(
            onClick = { /* Milestone 3: start Wi-Fi Direct group */ },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.hostState == HostState.IDLE || state.hostState == HostState.ERROR
        ) {
            Text(if (state.hostState == HostState.READY) "Stop Sharing" else "Start Sharing")
        }
    }
}
