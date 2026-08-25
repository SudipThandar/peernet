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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peernet.wifiextender.power.DozeExemption
import com.peernet.wifiextender.power.DozeExemptionPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.peernet.wifiextender.ui.host.HostState
import com.peernet.wifiextender.util.Permissions

/**
 * Two buttons. That's the whole app:
 *  SHARE   – share this phone's internet (host)
 *  CONNECT – link to a host whose Wi-Fi Direct network you joined (client)
 *
 * Flow: first time, join the DIRECT-xx network in phone settings with its
 * password. On later shares the network is remembered by the OS and the app
 * detects + links automatically; CONNECT stays as a manual fallback.
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
    // Distinguishes "user tapped SHARE" from the startup prompt so granting
    // permissions at first launch never silently starts hosting.
    var pendingStartSharing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Re-read instead of trusting the result map: it only contains what was
        // asked for, and only *required* permissions may gate sharing (a denied
        // notification permission must never disable the app).
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
        // Ask for location/nearby-devices up front: first-run users grant
        // once here instead of being interrupted mid-SHARE or mid-CONNECT.
        val firstRunAsk = Permissions.missingAny(context)
        if (firstRunAsk.isNotEmpty()) {
            permissionLauncher.launch(firstRunAsk.toTypedArray())
        }
    }

    val scope = rememberCoroutineScope()

    // ---- VPN consent + TUN start once a host link exists (Milestone 6/7) ----
    var tunPackets by remember { mutableStateOf(0L) }
    var quicState by remember { mutableStateOf(0) }
    var engineStats by remember { mutableStateOf("") }
    // Read once per composition entry, then cleared: the message survives the
    // process death that produced it, which is the whole point.
    var lastCrash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        lastCrash = com.peernet.wifiextender.PeerNetApp.lastCrash(context)
        if (lastCrash != null) {
            com.peernet.wifiextender.PeerNetApp.clearLastCrash(context)
        }
    }
    val tunnelStatus by clientViewModel.tunnelStatus.collectAsStateWithLifecycle()

    fun vpnIntent(): android.content.Intent =
        android.content.Intent(context, com.peernet.wifiextender.service.PeerNetVpnService::class.java).apply {
            val h = client.connectedHost
            if (h?.address != null) {
                putExtra(
                    com.peernet.wifiextender.service.PeerNetVpnService.EXTRA_HOST_ADDR,
                    "${h.address}:${h.tunnelPort}"
                )
                putExtra(
                    com.peernet.wifiextender.service.PeerNetVpnService.EXTRA_HOST_FP,
                    h.fingerprint ?: ""
                )
            }
            // Pin QUIC sockets to the link's network (P2P Wi-Fi is usually
            // "no internet" and would otherwise lose the default route to
            // cellular, where the host is unreachable).
            clientViewModel.linkedNetwork()?.let {
                putExtra(
                    com.peernet.wifiextender.service.PeerNetVpnService.EXTRA_NETWORK,
                    it
                )
            }
        }
    /**
     * Android 12+ throws ForegroundServiceStartNotAllowedException when a
     * foreground service is started while the app is not visible, and the link
     * event that triggers this can arrive from a background coroutine. A crash
     * there looks exactly like "the app is broken", so it is reported instead.
     */
    fun startVpnService() {
        try {
            context.startForegroundService(vpnIntent())
        } catch (t: Throwable) {
            clientViewModel.reportTunnelStatus(
                "Could not start the tunnel while the app was in the background — open PeerNet and reconnect."
            )
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startVpnService()
        } else {
            // Silent denial used to look identical to a broken tunnel.
            clientViewModel.reportTunnelStatus(
                "VPN permission denied — internet cannot be routed. Disconnect and connect again to allow it."
            )
        }
    }

    fun stopVpn() {
        context.stopService(
            android.content.Intent(context, com.peernet.wifiextender.service.PeerNetVpnService::class.java)
        )
    }

    LaunchedEffect(client.connectedHost?.hostId) {
        // Starting the tunnel needs an Activity (VPN consent is a dialog), so
        // the UI still triggers it. Stopping it must NOT live here: this effect
        // and `collectAsStateWithLifecycle` both stop when the Activity stops,
        // so with the screen off nothing observed the link clearing and the TUN,
        // the tunnel and the Android VPN key all outlived the session.
        // `PeerNetVpnService` now watches `ClientLinkManager.linkedHost` itself.
        if (client.connectedHost == null) return@LaunchedEffect
        val prepare = android.net.VpnService.prepare(context)
        if (prepare != null) {
            vpnLauncher.launch(prepare)
        } else {
            startVpnService()
        }
        // Surface capture + tunnel proof in the status line.
        var silentSince = 0L
        while (true) {
            tunPackets = clientViewModel.packetCount()
            quicState = clientViewModel.tunnelState()
            engineStats = clientViewModel.engineStats()

            // "Connected but nothing loads" is otherwise invisible: the tunnel
            // reports healthy while the host relays nothing back. Sending with
            // zero bytes returned for several seconds is that failure.
            val sending = clientViewModel.outboundCount() > 0
            val receiving = clientViewModel.inboundCount() > 0
            val undelivered = clientViewModel.undeliveredCount() > 0

            // A dead capture loop only shows up as frozen counters, which reads
            // as "the phone sent nothing" — name it instead.
            if (quicState == STATE_CONNECTED && tunPackets > 0 && !clientViewModel.captureAlive()) {
                clientViewModel.reportTunnelStatus(
                    "Packet capture stopped — reconnect to restart the tunnel."
                )
            } else if (quicState == STATE_CONNECTED && sending && !receiving) {
                if (silentSince == 0L) silentSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - silentSince > SILENT_TUNNEL_MS) {
                    clientViewModel.reportTunnelStatus(
                        if (undelivered) {
                            "Replies are arriving but cannot be delivered to this phone — " +
                                "reconnect to rebuild the tunnel."
                        } else {
                            "Tunnel is up but the host is not sending anything back — " +
                                "check that the host phone still has working internet."
                        }
                    )
                }
            } else {
                silentSince = 0L
            }
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

        // ---- one-time "allow background running" prompt ----
        //
        // Doze is the last unaddressed cause of the screen-off stall: both phones
        // now hold a Wi-Fi lock, but that keeps the radio alive, not the app.
        // `PowerManager` wake locks are ruled out by design, so a user-granted
        // exemption is the only lever left.
        //
        // The trigger is deliberately "a session is genuinely up" - host READY, or
        // the client's tunnel actually connected - rather than the button tap:
        //  - at tap time the client still has the VPN consent dialog pending, and
        //    a second system dialog would cover it;
        //  - starting an activity moves the app off-screen, and on Android 12+ a
        //    foreground service cannot be started from the background. Waiting
        //    until the service is already running avoids that entirely.
        val sessionActive = host.hostState == HostState.READY || quicState == STATE_CONNECTED
        LaunchedEffect(sessionActive) {
            if (!DozeExemptionPolicy.shouldPrompt(
                    sessionActive = sessionActive,
                    alreadyExempt = DozeExemption.isExempt(context),
                    alreadyAsked = DozeExemption.wasAsked(context)
                )
            ) {
                return@LaunchedEffect
            }
            val shown = DozeExemption.requestExemption(context)
            com.peernet.wifiextender.diag.Diagnostics.note(
                "power",
                if (shown) "DOZE_EXEMPTION_PROMPTED" else
                    "DOZE_EXEMPTION_PROMPT_UNAVAILABLE - no activity for this action on this build"
            )
        }

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
                when (quicState) {
                    1 -> append("  •  tunnel connecting…")
                    2 -> append("  •  tunnel up")
                    3 -> append("  •  tunnel reconnecting…")
                }
                if (tunPackets > 0) append("  •  $engineStats")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        // Tunnel progress/failure in plain words — the only diagnostic a
        // user without adb can act on.
        if (tunnelStatus.isNotBlank()) {
            Text(
                text = tunnelStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (quicState == 2) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
            )
        }

        // A crash is otherwise invisible without adb: the app just reappears
        // and the tunnel counters restart. Show what killed it, once.
        if (lastCrash != null) {
            Text(
                text = "Recovered from a crash: $lastCrash",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

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
                    pendingStartSharing = true
                    permissionLauncher.launch(missingPerms.toTypedArray())
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

        // ---- Why linking has not happened (never stay silent) ----
        if (client.linkDiagnostic.isNotBlank() && linkedHost == null && !isHosting) {
            Text(
                client.linkDiagnostic,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // ---- Diagnostics out of the app (the tester has no adb) ----
        TextButton(onClick = {
            val report = buildString {
                appendLine("PeerNet diagnostics")
                appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.SDK_INT}")
                appendLine("engine: ${home.engineVersion ?: "unknown"}")
                appendLine("stats: ${engineStats.ifBlank { "(none)" }}")
                appendLine("quicState=$quicState tunPackets=$tunPackets")
                appendLine("hostState=${host.hostState} engineReady=${host.engineReady} engineFailure=${host.engineFailure ?: "-"}")
                appendLine("linkedHost=${linkedHost?.address ?: "-"}:${linkedHost?.tunnelPort ?: 0}")
                appendLine("tunnelStatus=${tunnelStatus.ifBlank { "-" }}")
                appendLine("lastCrash=${lastCrash ?: "-"}")
                appendLine("linkDiagnostic=${client.linkDiagnostic.ifBlank { "-" }}")
                appendLine()
                append(com.peernet.wifiextender.diag.Diagnostics.snapshot())
            }
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "PeerNet diagnostics")
                putExtra(android.content.Intent.EXTRA_TEXT, report)
            }
            runCatching {
                context.startActivity(
                    android.content.Intent.createChooser(send, "Share PeerNet diagnostics")
                )
            }
        }) {
            Text("SHARE DIAGNOSTICS", style = MaterialTheme.typography.labelSmall)
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
                    InfoRow(
                        "Clients probed",
                        "${host.probesAnswered}" +
                            if (host.probesAnswered == 0) " — no client has reached this phone yet" else ""
                    )
                    if (!host.linkServerListening) {
                        // Clients look for this responder to learn the pin; a
                        // dead one means they never link, with no other symptom.
                        Text(
                            "Clients cannot reach this phone: ${host.linkServerFailure ?: "link responder down"}.\n" +
                                "Tap STOP SHARING then SHARE again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (!host.engineReady) {
                        // Without the engine there is no pin and no relay, so
                        // a client would join the network and get nothing.
                        Text(
                            "Tunnel engine not running — clients cannot get internet.\n" +
                                "Reason: ${host.engineFailure ?: "unknown"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** Tunnel state reported by the engine: 2 = connected. */
private const val STATE_CONNECTED = 2

/** How long a connected-but-silent tunnel is tolerated before it is called out. */
private const val SILENT_TUNNEL_MS = 10_000L

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
