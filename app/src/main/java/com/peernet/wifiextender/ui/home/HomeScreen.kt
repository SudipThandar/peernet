package com.peernet.wifiextender.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.peernet.wifiextender.ui.host.HostState
import com.peernet.wifiextender.util.Permissions

/**
 * NetShare-style single screen: one status area, one big action button,
 * everything else secondary. All PeerNet functionality, zero tab confusion.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    homeViewModel: HomeViewModel = hiltViewModel(),
    hostViewModel: com.peernet.wifiextender.ui.host.HostViewModel = hiltViewModel(),
    clientViewModel: com.peernet.wifiextender.ui.client.ClientViewModel = hiltViewModel()
) {
    val home by homeViewModel.uiState.collectAsStateWithLifecycle()
    val host by hostViewModel.uiState.collectAsStateWithLifecycle()
    val client by clientViewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var missingPerms by remember { mutableStateOf(Permissions.missing(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        missingPerms = Permissions.missing(context)
        if (grants.values.all { it }) {
            hostViewModel.startSharing()
        }
    }

    LaunchedEffect(Unit) {
        homeViewModel.startObserving()
        hostViewModel.onScreenShown()
        missingPerms = Permissions.missing(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- Status ----
        val linkedHost = client.connectedHost
        val (statusText, statusColor) = when {
            host.hostState == HostState.CREATING_GROUP -> "Creating network…" to Color.Gray
            host.hostState == HostState.READY -> "Sharing internet" to Color(0xFF2E7D32)
            linkedHost != null -> "Connected to ${linkedHost.name}" to Color(0xFF2E7D32)
            host.hostState == HostState.ERROR -> "Error" to MaterialTheme.colorScheme.error
            else -> "Ready" to Color.Gray
        }
        Text(statusText, style = MaterialTheme.typography.headlineMedium, color = statusColor)

        Text(
            text = buildString {
                append(if (home.internetAvailable) "Internet: connected" else "Internet: not connected")
                if (linkedHost != null) append("  •  via ${linkedHost.name}")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Spacer(Modifier.height(16.dp))

        // ---- One big primary button ----
        val isHosting = host.hostState == HostState.READY || host.hostState == HostState.CREATING_GROUP
        val (buttonLabel, buttonAction) = when {
            isHosting -> "STOP SHARING" to { hostViewModel.stopSharing() }
            linkedHost != null -> "DISCONNECT" to { clientViewModel.disconnect() }
            else -> "START SHARING" to {
                if (missingPerms.isNotEmpty()) {
                    permissionLauncher.launch(Permissions.runtimePermissions().toTypedArray())
                } else {
                    hostViewModel.startSharing()
                }
            }
        }

        Button(
            onClick = buttonAction,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(56.dp),
            colors = if (isHosting) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Text(buttonLabel, style = MaterialTheme.typography.titleMedium)
        }

        // ---- Secondary: find hosts (only when idle) ----
        if (!isHosting && linkedHost == null) {
            OutlinedButton(
                onClick = { clientViewModel.searchNearbyNetworks(); clientViewModel.refreshHosts() },
                modifier = Modifier.fillMaxWidth(0.85f),
                enabled = !client.discovering
            ) {
                Text(if (client.discovering) "SEARCHING…" else "FIND HOSTS")
            }
        }

        // ---- Errors ----
        host.error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        // ---- Sharing details (NetShare-style info card) ----
        if (host.hostState == HostState.READY) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("Network", host.ssid ?: "—")
                    if (host.passphraseAvailable) {
                        InfoRow("Password", host.passphrase ?: "—")
                    } else {
                        InfoRow("Password", "unavailable — join via Wi-Fi settings")
                    }
                    InfoRow("Address", host.groupOwnerAddress ?: "acquiring…")
                    InfoRow("Clients", "${host.connectedClients}")
                }
            }
        }

        // ---- Nearby networks (join directly) ----
        if (client.nearbyPeers.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nearby hosts", style = MaterialTheme.typography.titleSmall)
                    client.nearbyPeers.forEach { peer ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.name)
                                Text(peer.address ?: "", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            }
                            OutlinedButton(onClick = { peer.address?.let(clientViewModel::joinPeer) }) {
                                Text("Join")
                            }
                        }
                    }
                }
            }
        }

        // ---- Discovered hosts on joined/shared network ----
        client.discoveredHosts.forEach { h ->
            val linked = linkedHost != null && linkedHost.hostId != null &&
                linkedHost.hostId == h.hostId
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(h.name)
                        Text(h.address ?: "", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    }
                    Button(onClick = { clientViewModel.connect(h) }, enabled = !linked) {
                        Text(if (linked) "LINKED" else "Connect")
                    }
                }
            }
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
