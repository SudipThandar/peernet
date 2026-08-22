package com.peernet.wifiextender.ui.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ClientScreen(
    viewModel: ClientViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Client Mode", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(state.status, style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (state.discovering) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Button(
            onClick = { /* QR pairing arrives in Milestone 9 */ },
            modifier = Modifier.fillMaxWidth(),
            enabled = false
        ) {
            Text("Scan QR Code (coming soon)")
        }

        OutlinedButton(
            onClick = viewModel::discoverHosts,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.discovering
        ) {
            Text(if (state.discovering) "Searching…" else "Discover Hosts")
        }

        state.discoveredHosts.forEach { host ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(host.name, style = MaterialTheme.typography.bodyLarge)
                        Text(host.address ?: "address pending", style = MaterialTheme.typography.labelMedium)
                    }
                    Button(onClick = { /* tunnel connect arrives in Milestone 7 */ }, enabled = false) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}
