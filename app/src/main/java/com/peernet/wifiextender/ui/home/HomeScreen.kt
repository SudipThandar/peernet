package com.peernet.wifiextender.ui.home

import android.app.Activity
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import com.peernet.wifiextender.host.HostCredentials
import com.peernet.wifiextender.host.RoleConflictPolicy
import com.peernet.wifiextender.host.ShareAction
import com.peernet.wifiextender.host.ShareDuration
import com.peernet.wifiextender.host.ShareTimerPolicy
import com.peernet.wifiextender.power.DozeExemption
import com.peernet.wifiextender.power.DozeExemptionPolicy
import com.peernet.wifiextender.wifi.GroupCredentialsPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.peernet.wifiextender.ui.host.HostState
import com.peernet.wifiextender.util.Permissions

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
    val activity = context as? Activity
    var missingPerms by remember { mutableStateOf(Permissions.missing(context)) }
    var pendingStartSharing by remember { mutableStateOf(false) }
    var shareRoleConflict by remember { mutableStateOf(false) }
    var showAdDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        missingPerms = Permissions.missing(context)
        if (missingPerms.isEmpty() && pendingStartSharing) {
            pendingStartSharing = false
            hostViewModel.startSharing()
        }
    }

    LaunchedEffect(Unit) {
        homeViewModel.startObserving()
        hostViewModel.onScreenShown()
        missingPerms = Permissions.missing(context)
        homeViewModel.loadAd()
        val firstRunAsk = Permissions.missingAny(context)
        if (firstRunAsk.isNotEmpty()) {
            permissionLauncher.launch(firstRunAsk.toTypedArray())
        }
    }

    val scope = rememberCoroutineScope()

    var tunPackets by remember { mutableStateOf(0L) }
    var quicState by remember { mutableStateOf(0) }
    val tunnelStatus by clientViewModel.tunnelStatus.collectAsStateWithLifecycle()
    val tunnelActive by clientViewModel.tunnelActive.collectAsStateWithLifecycle()

    var passwordDraft by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var passwordNotice by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun vpnIntent(): android.content.Intent =
        android.content.Intent(context, com.peernet.wifiextender.service.PeerNetVpnService::class.java).apply {
            val h = client.connectedHost
            if (h?.address != null) {
                putExtra(com.peernet.wifiextender.service.PeerNetVpnService.EXTRA_HOST_ADDR, "${h.address}:${h.tunnelPort}")
                putExtra(com.peernet.wifiextender.service.PeerNetVpnService.EXTRA_HOST_FP, h.fingerprint ?: "")
            }
            clientViewModel.linkedNetwork()?.let {
                putExtra(com.peernet.wifiextender.service.PeerNetVpnService.EXTRA_NETWORK, it)
            }
        }

    fun startVpnService() {
        try {
            context.startForegroundService(vpnIntent())
        } catch (t: Throwable) {
            clientViewModel.reportTunnelStatus("Could not start tunnel. Open PeerNet and reconnect.")
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startVpnService()
        } else {
            clientViewModel.reportTunnelStatus("VPN permission denied. Disconnect and reconnect.")
        }
    }

    fun stopVpn() {
        context.stopService(android.content.Intent(context, com.peernet.wifiextender.service.PeerNetVpnService::class.java))
    }

    LaunchedEffect(client.connectedHost?.hostId) {
        if (client.connectedHost == null) return@LaunchedEffect
        val prepare = android.net.VpnService.prepare(context)
        if (prepare != null) vpnLauncher.launch(prepare) else startVpnService()
        var silentSince = 0L
        while (true) {
            tunPackets = clientViewModel.packetCount()
            quicState = clientViewModel.tunnelState()
            val sending = clientViewModel.outboundCount() > 0
            val receiving = clientViewModel.inboundCount() > 0
            if (quicState == STATE_CONNECTED && tunPackets > 0 && !clientViewModel.captureAlive()) {
                clientViewModel.reportTunnelStatus("Capture stopped. Reconnect.")
            } else if (quicState == STATE_CONNECTED && sending && !receiving) {
                if (silentSince == 0L) silentSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - silentSince > SILENT_TUNNEL_MS) {
                    clientViewModel.reportTunnelStatus("Tunnel up but no data returning. Check host internet.")
                }
            } else {
                silentSince = 0L
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    val sessionActive = host.hostState == HostState.READY || quicState == STATE_CONNECTED

    // Samsung battery optimization dialog
    var showSamsungDialog by remember { mutableStateOf(false) }
    LaunchedEffect(sessionActive) {
        if (DozeExemptionPolicy.isSamsungDevice() && !DozeExemption.wasSamsungAsked(context) && sessionActive) {
            showSamsungDialog = true
        }
    }
    if (showSamsungDialog) {
        AlertDialog(
            onDismissRequest = { showSamsungDialog = false; DozeExemption.markSamsungAsked(context) },
            title = { Text("Keep sharing alive") },
            text = {
                Text("Samsung may stop sharing when the screen turns off.\n\nOpen Settings > Battery > Background usage limits > Never sleeping apps > Add PeerNet.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSamsungDialog = false
                    DozeExemption.markSamsungAsked(context)
                    DozeExemption.requestSamsungExemption(context)
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showSamsungDialog = false; DozeExemption.markSamsungAsked(context) }) { Text("Skip") }
            }
        )
    }

    // Standard Doze exemption
    LaunchedEffect(sessionActive) {
        if (!DozeExemptionPolicy.shouldPrompt(sessionActive, DozeExemption.isExempt(context), DozeExemption.wasAsked(context))) return@LaunchedEffect
        DozeExemption.requestExemption(context)
    }

    // Ad reward dialog
    if (showAdDialog) {
        AlertDialog(
            onDismissRequest = { showAdDialog = false },
            title = { Text("Unlock Unlimited") },
            text = { Text("Watch a short ad to share with no time limit.") },
            confirmButton = {
                TextButton(onClick = {
                    showAdDialog = false
                    if (activity != null && homeViewModel.isAdReady()) {
                        homeViewModel.showAd(activity) {
                            hostViewModel.setShareDuration(ShareDuration.UNLIMITED)
                        }
                    } else {
                        homeViewModel.loadAd()
                        hostViewModel.setShareDuration(ShareDuration.UNLIMITED)
                    }
                }) { Text("Watch Ad") }
            },
            dismissButton = {
                TextButton(onClick = { showAdDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(40.dp))

        // ---- App title ----
        Text(
            "PeerNet",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(8.dp))

        // ---- Status ----
        val linkedHost = client.connectedHost
        val isHosting = host.hostState == HostState.READY || host.hostState == HostState.CREATING_GROUP

        val statusText = when {
            host.hostState == HostState.ERROR -> "Error"
            host.hostState == HostState.READY -> "Sharing"
            host.hostState == HostState.CREATING_GROUP -> "Starting..."
            client.searching -> "Searching..."
            linkedHost != null -> "Connected"
            else -> "Ready"
        }
        val statusColor = when {
            host.hostState == HostState.ERROR -> Color(0xFFD93025)
            host.hostState == HostState.READY -> Color(0xFF1E8E3E)
            host.hostState == HostState.CREATING_GROUP -> Color(0xFFF9AB00)
            linkedHost != null -> Color(0xFF1E8E3E)
            else -> Color(0xFF9AA0A6)
        }
        val statusIcon = when {
            host.hostState == HostState.ERROR -> Icons.Filled.Warning
            host.hostState == HostState.READY -> Icons.Filled.CheckCircle
            host.hostState == HostState.CREATING_GROUP -> Icons.Filled.Tune
            linkedHost != null -> Icons.Filled.PhoneAndroid
            else -> Icons.Filled.SignalWifiOff
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
            Text(statusText, style = MaterialTheme.typography.titleLarge, color = statusColor, fontWeight = FontWeight.Medium)
        }

        // ---- Tunnel status (errors only) ----
        if (tunnelStatus.isNotBlank() && quicState != STATE_CONNECTED) {
            Text(tunnelStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD93025))
        }

        Spacer(Modifier.height(16.dp))

        // ---- Duration picker (before sharing only) ----
        if (!isHosting) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                ShareDuration.entries.forEach { option ->
                    val selected = option == host.shareDuration
                    val isUnlimited = option == ShareDuration.UNLIMITED && !host.premium
                    TextButton(
                        onClick = {
                            if (isUnlimited) {
                                showAdDialog = true
                            } else if (ShareTimerPolicy.isSelectable(option, host.premium)) {
                                hostViewModel.setShareDuration(option)
                            }
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (isUnlimited) {
                                Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFF9AB00))
                            }
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    selected -> MaterialTheme.colorScheme.primary
                                    isUnlimited -> Color(0xFFF9AB00)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Running timer
            var tick by remember { mutableStateOf(0L) }
            LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_000L); tick++ } }
            @Suppress("UNUSED_EXPRESSION")
            tick
            val remaining = ShareTimerPolicy.formatRemaining(host.shareRemainingMs)
            if (remaining != null) {
                Text(remaining, style = MaterialTheme.typography.titleMedium, color = Color(0xFF5F6368), fontWeight = FontWeight.Light)
            }
        }

        Spacer(Modifier.weight(1f))

        // ---- SHARE button ----
        fun requestShare() {
            if (missingPerms.isNotEmpty()) {
                pendingStartSharing = true
                permissionLauncher.launch(missingPerms.toTypedArray())
            } else {
                hostViewModel.startSharing()
            }
        }

        Button(
            onClick = {
                if (isHosting) {
                    hostViewModel.stopSharing()
                } else {
                    when (RoleConflictPolicy.evaluateShareRequest(clientLinkActive = linkedHost != null, tunnelActive = tunnelActive)) {
                        ShareAction.CONFIRM_REPLACING_CLIENT_LINK -> shareRoleConflict = true
                        ShareAction.PROCEED -> requestShare()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isHosting) Color(0xFFD93025) else Color(0xFF1A73E8)
            )
        ) {
            Icon(
                if (isHosting) Icons.Filled.Stop else Icons.Filled.CloudUpload,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).size(20.dp)
            )
            Text(
                if (isHosting) "Stop Sharing" else "Share",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (shareRoleConflict) {
            AlertDialog(
                onDismissRequest = { shareRoleConflict = false },
                title = { Text("Switch roles?") },
                text = { Text("This will disconnect from the current host and start sharing instead.") },
                confirmButton = {
                    TextButton(onClick = {
                        shareRoleConflict = false
                        scope.launch {
                            stopVpn()
                            clientViewModel.disconnect()
                            delay(2_500)
                            requestShare()
                        }
                    }) { Text("Switch") }
                },
                dismissButton = {
                    TextButton(onClick = { shareRoleConflict = false }) { Text("Cancel") }
                }
            )
        }

        // ---- CONNECT button ----
        OutlinedButton(
            onClick = {
                when {
                    linkedHost != null && !isHosting -> { stopVpn(); clientViewModel.disconnect() }
                    isHosting -> scope.launch {
                        hostViewModel.stopSharing()
                        delay(2_500)
                        clientViewModel.connectNow()
                    }
                    else -> clientViewModel.connectNow()
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !client.searching
        ) {
            Icon(
                when {
                    client.searching -> Icons.Filled.Search
                    linkedHost != null && !isHosting -> Icons.Filled.LinkOff
                    else -> Icons.Filled.PhoneAndroid
                },
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp).size(20.dp)
            )
            Text(
                when {
                    client.searching -> "Searching..."
                    linkedHost != null && !isHosting -> "Disconnect"
                    else -> "Connect"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        // ---- Share details card (host only) ----
        host.error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEA)), modifier = Modifier.fillMaxWidth()) {
                Text(it, modifier = Modifier.padding(12.dp), color = Color(0xFFD93025), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (host.hostState == HostState.READY) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Network", host.ssid ?: "—")

                    val effective = host.passphrase
                    if (!effective.isNullOrEmpty()) {
                        val shown = passwordDraft ?: effective
                        val changed = shown != effective
                        val liveRejection = if (changed) GroupCredentialsPolicy.rejection(shown) else null
                        val canSave = changed && liveRejection == null
                        OutlinedTextField(
                            value = shown,
                            onValueChange = { passwordDraft = it; passwordError = null; passwordNotice = null },
                            label = { Text("Password") },
                            singleLine = true,
                            isError = liveRejection != null || passwordError != null,
                            supportingText = {
                                val error = passwordError ?: liveRejection
                                val msg = error ?: passwordNotice
                                if (msg != null) Text(msg, color = if (error != null) MaterialTheme.colorScheme.error else Color(0xFF1E8E3E))
                                else if (canSave) Text("Done to save")
                                else Text("Min ${GroupCredentialsPolicy.MIN_LENGTH} chars")
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                when {
                                    !changed -> { passwordError = null; passwordNotice = null; keyboard?.hide() }
                                    liveRejection != null -> { passwordError = liveRejection; passwordNotice = null }
                                    else -> {
                                        val rejected = HostCredentials.setPassphrase(context, shown)
                                        passwordError = rejected
                                        passwordNotice = if (rejected == null) "Saved. Restart sharing to apply." else null
                                        if (rejected == null) keyboard?.hide()
                                    }
                                }
                            }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        InfoRow("Password", "See Wi-Fi settings")
                    }

                    InfoRow("Address", host.groupOwnerAddress ?: "—")
                    InfoRow("Clients", "${host.probesAnswered}")

                    if (!host.linkServerListening || !host.engineReady) {
                        Text(
                            if (!host.engineReady) "Engine: ${host.engineFailure ?: "not running"}"
                            else "Link server: ${host.linkServerFailure ?: "down"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD93025)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

private const val STATE_CONNECTED = 2
private const val SILENT_TUNNEL_MS = 10_000L

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF5F6368), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
