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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.peernet.wifiextender.ads.BannerAdView
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
    val meshEnabled by homeViewModel.meshEnabled.collectAsStateWithLifecycle()

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
        homeViewModel.loadInterstitial {
            if (activity != null) homeViewModel.showInterstitial(activity)
        }
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
    var passwordVisible by remember { mutableStateOf(false) }
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

    var showSamsungDialog by remember { mutableStateOf(false) }
    LaunchedEffect(sessionActive) {
        if (DozeExemptionPolicy.isSamsungDevice() && sessionActive && !DozeExemption.isExempt(context)) {
            // Show the Samsung dialog each time a session starts if the user has
            // not yet granted the exemption. Previous behavior only asked once
            // ever, so a user who skipped it was never asked again.
            showSamsungDialog = true
        }
    }
    if (showSamsungDialog) {
        AlertDialog(
            onDismissRequest = { showSamsungDialog = false },
            title = { Text("Keep sharing alive") },
            text = {
                Text(
                    "Samsung may stop sharing when the screen turns off.\n\n" +
                        "Steps to fix:\n" +
                        "1. Tap \"Open Settings\" below\n" +
                        "2. Find PeerNet in the list\n" +
                        "3. Set it to \"Don't optimize\"\n" +
                        "4. Go back, then tap Battery > Background usage limits\n" +
                        "5. Add PeerNet to \"Never sleeping apps\"\n\n" +
                        "This keeps sharing running when the screen is off."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSamsungDialog = false
                    DozeExemption.requestSamsungExemption(context)
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showSamsungDialog = false }) { Text("Skip") }
            }
        )
    }

    LaunchedEffect(sessionActive) {
        if (!DozeExemptionPolicy.shouldPrompt(sessionActive, DozeExemption.isExempt(context), DozeExemption.wasAsked(context))) return@LaunchedEffect
        DozeExemption.requestExemption(context)
    }

    if (showAdDialog) {
        AlertDialog(
            onDismissRequest = { showAdDialog = false },
            title = { Text("Unlock Unlimited") },
            text = { Text("Watch a short ad to share with no time limit.") },
            confirmButton = {
                TextButton(onClick = {
                    if (activity != null && homeViewModel.isAdReady()) {
                        showAdDialog = false
                        homeViewModel.showAd(activity) {
                            hostViewModel.setShareDuration(ShareDuration.UNLIMITED)
                        }
                    } else {
                        homeViewModel.loadAd {
                            showAdDialog = false
                            if (activity != null && homeViewModel.isAdReady()) {
                                homeViewModel.showAd(activity) {
                                    hostViewModel.setShareDuration(ShareDuration.UNLIMITED)
                                }
                            }
                        }
                    }
                }) { Text("Watch Ad") }
            },
            dismissButton = {
                TextButton(onClick = { showAdDialog = false }) { Text("Cancel") }
            }
        )
    }

    val linkedHost = client.connectedHost
    val isHosting = host.hostState == HostState.READY || host.hostState == HostState.CREATING_GROUP
    val isClientConnected = linkedHost != null && !isHosting

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            Text(
                "PeerNet",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            val statusText = when {
                host.hostState == HostState.ERROR -> "Error"
                host.hostState == HostState.READY -> "Sharing"
                host.hostState == HostState.CREATING_GROUP -> "Starting\u2026"
                client.searching -> "Searching\u2026"
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

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(18.dp))
                Text(statusText, style = MaterialTheme.typography.titleMedium, color = statusColor, fontWeight = FontWeight.Medium)
            }

            if (tunnelStatus.isNotBlank() && quicState != STATE_CONNECTED) {
                Text(tunnelStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD93025))
            }

            if (!isHosting) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShareDuration.entries.forEach { option ->
                        val selected = option == host.shareDuration
                        val isUnlimited = option == ShareDuration.UNLIMITED && !host.premium
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (isUnlimited) {
                                    showAdDialog = true
                                } else if (ShareTimerPolicy.isSelectable(option, host.premium)) {
                                    hostViewModel.setShareDuration(option)
                                }
                            },
                            label = { Text(option.label, fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    if (option == ShareDuration.UNLIMITED) Icons.Filled.AllInclusive else Icons.Filled.Timer,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            trailingIcon = if (isUnlimited) {
                                { Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFF9AB00)) }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            } else {
                var tick by remember { mutableStateOf(0L) }
                LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_000L); tick++ } }
                @Suppress("UNUSED_EXPRESSION")
                tick
                val remaining = ShareTimerPolicy.formatRemaining(host.shareRemainingMs)
                if (remaining != null) {
                    Text(remaining, style = MaterialTheme.typography.headlineSmall, color = Color(0xFF5F6368), fontWeight = FontWeight.Light)
                }
            }

            host.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEA)), modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(10.dp), color = Color(0xFFD93025), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (host.hostState == HostState.READY) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F3F4)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoRow("Network", host.ssid ?: "\u2014")

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
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    M3IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                isError = liveRejection != null || passwordError != null,
                                supportingText = {
                                    val error = passwordError ?: liveRejection
                                    val msg = error ?: passwordNotice
                                    if (msg != null) Text(msg, color = if (error != null) MaterialTheme.colorScheme.error else Color(0xFF1E8E3E))
                                    else if (canSave) Text("Done to save")
                                    else Text("Min ${GroupCredentialsPolicy.MIN_LENGTH} chars")
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFF1F1F1F),
                                    unfocusedTextColor = Color(0xFF1F1F1F),
                                    cursorColor = MaterialTheme.colorScheme.primary
                                ),
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

                        InfoRow("Address", host.groupOwnerAddress ?: "\u2014")
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

            if (isClientConnected) {
                val tunnelUp = quicState == STATE_CONNECTED
                val signalStrength = when {
                    !tunnelUp -> 0.15f
                    tunPackets > 100 -> 0.9f
                    tunPackets > 10 -> 0.6f
                    else -> 0.35f
                }
                val signalLabel = when {
                    !tunnelUp -> "Connecting\u2026"
                    signalStrength > 0.7f -> "Strong signal"
                    signalStrength > 0.4f -> "Medium signal"
                    else -> "Weak signal \u2014 move host closer"
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F3F4)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SignalRadar(signalStrength = signalStrength, signalLabel = signalLabel)
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F3F4)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Mesh Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                val meshSsid by homeViewModel.meshSsid.collectAsStateWithLifecycle()
                                val meshErr by homeViewModel.meshError.collectAsStateWithLifecycle()
                                Text(
                                    when {
                                        meshErr != null -> "Error: ${meshErr}"
                                        meshEnabled && meshSsid != null -> "Hotspot: $meshSsid"
                                        meshEnabled -> "Starting hotspot\u2026"
                                        else -> "Off"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (meshErr != null) Color(0xFFD93025) else Color(0xFF5F6368)
                                )
                            }
                        }
                        Switch(
                            checked = meshEnabled,
                            onCheckedChange = { homeViewModel.toggleMesh() },
                            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        ) {
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
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isHosting) Color(0xFFD93025) else Color(0xFF1A73E8)
                )
            ) {
                Icon(
                    if (isHosting) Icons.Filled.Stop else Icons.Filled.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp)
                )
                Text(
                    if (isHosting) "Stop Sharing" else "Share",
                    style = MaterialTheme.typography.titleSmall,
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
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !client.searching
            ) {
                Icon(
                    when {
                        client.searching -> Icons.Filled.Search
                        linkedHost != null && !isHosting -> Icons.Filled.LinkOff
                        else -> Icons.Filled.PhoneAndroid
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp)
                )
                Text(
                    when {
                        client.searching -> "Searching\u2026"
                        linkedHost != null && !isHosting -> "Disconnect"
                        else -> "Connect"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        BannerAdView(modifier = Modifier.fillMaxWidth())
    }
}

private const val STATE_CONNECTED = 2
private const val SILENT_TUNNEL_MS = 10_000L

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF5F6368), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = Color(0xFF1F1F1F))
    }
}
