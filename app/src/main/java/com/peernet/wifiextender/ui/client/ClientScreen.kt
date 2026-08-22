package com.peernet.wifiextender.ui.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

    LaunchedEffect(Unit) { viewModel.startObserving() }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopObserving() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Client Mode", style = MaterialTheme.typography.titleLarge)

        if (state.connectedHost != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("LINKED") })
                        Text(state.connectedHost?.name ?: "", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(state.status)
                    Button(
                        onClick = {
                            viewModel.refreshHosts()
                            // Disconnect semantics: forget the link; next scan rebuilds state.
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(state.status, style = MaterialTheme.typography.bodyLarge)
                }
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
            onClick = viewModel::searchNearbyNetworks,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Search Nearby Networks")
        }

        OutlinedButton(
            onClick = viewModel::refreshHosts,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.discovering
        ) {
            Text(if (state.discovering) "Searching…" else "Refresh")
        }

        if (state.joinedToHostAddress != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Joined host network (${state.joinedToHostAddress})",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        if (state.nearbyPeers.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nearby networks", style = MaterialTheme.typography.bodyLarge)
                    state.nearbyPeers.forEach { peer ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.name, style = MaterialTheme.typography.bodyLarge)
                                Text(peer.address ?: "", style = MaterialTheme.typography.labelMedium)
                            }
                            Button(onClick = { peer.address?.let(viewModel::joinPeer) }) {
                                Text("Join")
                            }
                        }
                    }
                }
            }
        }

        state.discoveredHosts.forEach { host ->
            val connected = state.connectedHost
            val isConnected = connected != null && connected.hostId != null &&
                connected.hostId == host.hostId
            val isSaved = host.hostId != null && host.hostId in state.savedHostIds

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(host.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            buildString {
                                append(host.address ?: "address pending")
                                if (isSaved) append("  • saved")
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Button(
                        onClick = { viewModel.connect(host) },
                        enabled = !isConnected && state.connectingTo == null && host.address != null
                    ) {
                        Text(
                            when {
                                isConnected -> "Linked"
                                state.connectingTo == host.name -> "…"
                                else -> "Connect"
                            }
                        )
                    }
                }
            }
        }
    }
}
