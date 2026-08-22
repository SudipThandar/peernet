package com.peernet.wifiextender.ui.host

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peernet.wifiextender.util.Permissions

@Composable
fun HostScreen(
    viewModel: HostViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var missingPerms by remember { mutableStateOf(Permissions.missing(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        missingPerms = Permissions.missing(context)
        if (grants.values.all { it }) {
            viewModel.startSharing()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onScreenShown()
        missingPerms = Permissions.missing(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Host Mode", style = MaterialTheme.typography.titleLarge)

        if (missingPerms.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Permissions needed",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "PeerNet needs nearby-devices access to create the local network.",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Button(
                onClick = { permissionLauncher.launch(Permissions.runtimePermissions().toTypedArray()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant permissions")
            }
        }

        when (state.hostState) {
            HostState.IDLE -> StatusCard("Idle", "Not sharing yet. Tap Start Sharing below.")

            HostState.CREATING_GROUP -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Creating local network…", style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            HostState.READY -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = {}, label = { Text("SHARING") })
                            Spacer(Modifier.width(8.dp))
                            Text("${state.connectedClients} client(s)")
                        }
                        InfoRow("Network name (SSID)", state.ssid ?: "—")
                        if (!state.passphraseAvailable) {
                            InfoRow("Password", "Unavailable on this device")
                            Text(
                                "Connect manually: Settings > Wi-Fi > select the network above, then return to PeerNet.",
                                style = MaterialTheme.typography.labelMedium
                            )
                        } else {
                            InfoRow("Password", state.passphrase ?: "—")
                        }
                        InfoRow("Host address", state.groupOwnerAddress ?: "acquiring…")
                    }
                }
                OutlinedButton(
                    onClick = viewModel::stopSharing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Stop Sharing")
                }
            }

            HostState.ERROR -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(state.error ?: "Something went wrong.", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
