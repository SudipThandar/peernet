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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.peernet.wifiextender.ui.host.HostState
import com.peernet.wifiextender.util.Permissions

/**
 * Two buttons. That's the whole app:
 *  SHARE   – share this phone's internet (host)
 *  CONNECT – link to a host whose Wi-Fi Direct network you joined (client)
 *
 * Flow: join DIRECT-xx in phone settings with its password, open PeerNet,
 * it detects and links automatically.
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
        // No auto-search: scanning happens only when the user taps CONNECT.
    }

    val scope = rememberCoroutineScope()

    // ---- VPN consent + TUN start once a host link exists (Milestone 6) ----
    var tunPackets by remember { mutableStateOf(0L) }
    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            context.startForegroundService(
                android.content.Intent(context, com.peernet.wifiextender.service.PeerNetVpnService::class.java)
            )
        }
    }

    fun stopVpn() {
        context.stopService(
            android.content.Intent(context, com.peernet.wifiextender.service.PeerNetVpnService::class.java)
        )
    }

    LaunchedEffect(client.connectedHost?.hostId) {
        if (client.connectedHost == null) return@LaunchedEffect
        val prepare = android.net.VpnService.prepare(context)
        if (prepare != null) {
            vpnLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(prepare).build()
            )
        } else {
            context.startForegroundService(
                android.content.Intent(context, com.peernet.wifiextender.service.PeerNetVpnService::class.java)
            )
        }
        // Surface capture proof in the status line.
        while (true) {
            tunPackets = clientViewModel.packetCount()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        // ---- Status ----
        val linkedHost = client.connectedHost
        val isHosting = host.hostState == HostState.READY || host.hostState == HostState.CREATING_GROUP

        val statusText = when {
            host.hostState == HostState.ERROR -> "Error"
            host.hostState == HostState.READY -> "Sharing internet"
            host.hostState == HostState.CREATING_GROUP -> "Creating network…"
            linkedHost != null -> "Connected to ${linkedHost.name}"
            else -> "Ready"
        }
        val statusColor = when {
            host.hostState == HostState.ERROR -> MaterialTheme.colorScheme.error
            host.hostState == HostState.READY || linkedHost != null -> Color(0xFF2E7D32)
            else -> Color.Gray
        }

        Text(statusText, style = MaterialTheme.typography.headlineMedium, color = statusColor)

        Text(
            text = buildString {
                append(if (home.internetAvailable) "Internet: connected" else "Internet: not connected")
                if (tunPackets > 0) append("  •  TUN: $tunPackets packets")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        home.engineVersion?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9E9E9E)
            )
        }

        Spacer(Modifier.height(32.dp))

        // ---- SHARE ----
        Button(
            onClick = {
                if (isHosting) {
                    hostViewModel.stopSharing()
                } else if (missingPerms.isNotEmpty()) {
                    permissionLauncher.launch(Permissions.runtimePermissions().toTypedArray())
                } else {
                    hostViewModel.startSharing()
                }
            },
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(56.dp),
            colors = if (isHosting) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Text(if (isHosting) "STOP SHARING" else "SHARE", style = MaterialTheme.typography.titleMedium)
        }

        // ---- CONNECT / DISCONNECT ----
        OutlinedButton(
            onClick = {
                when {
                    // Client disconnect
                    linkedHost != null && !isHosting -> {
                        stopVpn()
                        clientViewModel.disconnect()
                    }
                    // Host tapping CONNECT: stop sharing first, then search as client
                    isHosting -> scope.launch {
                        hostViewModel.stopSharing()
                        delay(2_500)
                        clientViewModel.connectNow()
                    }
                    else -> clientViewModel.connectNow()
                }
            },
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(56.dp),
            enabled = !client.searching
        ) {
            Text(
                text = when {
                    client.searching -> "SEARCHING…"
                    linkedHost != null && !isHosting -> "DISCONNECT"
                    else -> "CONNECT"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }

        // ---- Client result line (no lists) ----
        if (client.status.isNotBlank()) {
            Text(
                client.status,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // ---- Errors ----
        host.error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        // ---- Sharing details (password needed for manual join) ----
        if (host.hostState == HostState.READY) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("Network", host.ssid ?: "—")
                    InfoRow("Password", host.passphrase ?: "unavailable — see Wi-Fi settings")
                    InfoRow("Address", host.groupOwnerAddress ?: "acquiring…")
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
