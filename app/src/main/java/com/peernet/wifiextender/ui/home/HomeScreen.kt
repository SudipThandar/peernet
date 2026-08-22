package com.peernet.wifiextender.ui.home

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
fun HomeScreen(
    onOpenHost: () -> Unit,
    onOpenClient: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "PeerNet",
            style = MaterialTheme.typography.titleLarge
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Status: ${state.mode}", style = MaterialTheme.typography.bodyLarge)
                Text("Internet: ${if (state.internetAvailable) "Connected" else "Not connected"}")
                Text("Wi-Fi: ${state.wifiState}")
            }
        }

        Button(
            onClick = onOpenHost,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Sharing")
        }

        Button(
            onClick = onOpenClient,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Connect to Host")
        }
    }
}
