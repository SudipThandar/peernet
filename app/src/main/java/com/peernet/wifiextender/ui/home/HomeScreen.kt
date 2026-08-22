package com.peernet.wifiextender.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    LaunchedEffect(Unit) { viewModel.startObserving() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.stopObserving() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "PeerNet", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusRow("Status", state.mode)
                StatusRow(
                    "Internet",
                    if (state.internetAvailable) "Connected" else "Not connected",
                    valueColor = if (state.internetAvailable) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
                StatusRow("Wi-Fi", state.wifiState)
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

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray)
        Text(value, color = valueColor)
    }
}
