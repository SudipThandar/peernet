package com.peernet.wifiextender.ui.client

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peernet.wifiextender.client.ClientLinkManager
import com.peernet.wifiextender.client.AutoConnectPolicy
import com.peernet.wifiextender.client.LinkPolicy
import com.peernet.wifiextender.diag.Diagnostics
import com.peernet.wifiextender.discovery.DiscoveredHost
import com.peernet.wifiextender.discovery.NsdClientDiscovery
import com.peernet.wifiextender.wifi.LinkServer
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class ClientUiState(
    val status: String = "",
    val searching: Boolean = false,
    val connectedHost: DiscoveredHost? = null,
    val savedHostIds: Set<String> = emptySet(),
    /**
     * Why linking has not happened yet, in plain language, refreshed on every
     * attempt. Empty once linked. Silence here was the single biggest obstacle
     * to diagnosing "it just doesn't connect".
     */
    val linkDiagnostic: String = ""
)

/**
 * Client logic — discovery runs when the user taps CONNECT and automatically
 * whenever this device joins a Wi-Fi Direct network (reconnect case): once a
 * known host's network is joined, linking happens without further taps.
 */
@HiltViewModel
class ClientViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val wifiDirect: WifiDirectManager,
    private val linkManager: ClientLinkManager,
    private val rustCore: com.peernet.wifiextender.core.RustCoreBridge
) : ViewModel() {

    private val discovery = NsdClientDiscovery(context)
    private val appContext: Context = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val busy = AtomicBoolean(false)
    private var livenessJob: Job? = null

    /** Reason the most recent link probe failed, for the on-screen diagnostic. */
    private var lastProbeFailure: String? = null

    /** Counts auto-link attempts so the user can see the app is still trying. */
    private var linkAttempts = 0

    /**
     * Monotonic auto-connect session id, stamped on every client diagnostic as
     * `s=<n>`. Reports mixed several join/leave cycles together and there was
     * no way to tell which attempt a line belonged to.
     */
    private val sessions = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var session = 0

    /** Consecutive polls with no usable network, driving the backoff. */
    @Volatile
    private var pollMisses = 0

    /** Last reported network picture, so only changes are recorded. */
    @Volatile
    private var lastNetworkFingerprint: String? = null

    /**
     * The network as of the most recent poll, updated every time rather than only
     * on change, because [AutoConnectPolicy] needs the *current* value at the
     * moment the user stops - and [lastNetworkFingerprint] is deliberately reset
     * to null by `clearLink` to force the next change to be logged.
     */
    private var currentNetworkFingerprint: String? = null

    /** Set by an explicit user stop; suppresses auto-connect until intent changes. */
    private var userStopped = false
    private var stoppedOnFingerprint: String? = null

    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(savedHostIds = loadSavedHostIds()) }
        // The notification's Stop action runs in a receiver, which has no access
        // to this ViewModel's state. It raises a counter here instead, so the stop
        // goes through the same tested path as the on-screen DISCONNECT button and
        // the screen can never be left reading "Connected" with no tunnel.
        viewModelScope.launch {
            var seen = linkManager.stopRequests.value
            linkManager.stopRequests.collect { n ->
                if (n == seen) return@collect
                seen = n
                Diagnostics.note("client", "STOP_FROM_NOTIFICATION s=$session")
                disconnect()
            }
        }
        viewModelScope.launch {
            var wasJoined = false
            wifiDirect.state.collect { s ->
                // `!s.hosting` is essential and was the bug: a phone that joined
                // a group used to report hosting=true as well (see
                // WifiDirectManager.classifyGroup), so `joined` never became
                // true and auto-link never ran on the client.
                val joined = s.joinedAsClient && !s.hosting
                if (joined && !wasJoined) {
                    session = sessions.incrementAndGet()
                    pollMisses = 0
                    Diagnostics.note(
                        "client",
                        "DIRECT_NETWORK_DETECTED s=$session p2p join go=${s.joinedGroupOwnerAddress ?: "?"}"
                    )
                    autoLink()
                }
                if (!joined && wasJoined && !s.hosting && _uiState.value.connectedHost != null) {
                    // Host tore down the group; drop the stale link.
                    Diagnostics.note("client", "HOST_LOST s=$session p2p group gone")
                    clearLink("Host disconnected.")
                }
                wasJoined = joined
            }
        }
        // Legacy-join watcher: users who associate through Android's own
        // Wi-Fi picker (typing the passphrase) never fire Wi-Fi Direct
        // callbacks on the client side, so joinedAsClient stays false
        // forever. Poll for a reachable host on the link instead: the
        // Wi-Fi Direct group owner is our gateway, so one cheap TCP probe
        // confirms it. SSID text is only a hint — some builds hide it
        // ("<unknown ssid>") which used to make the app claim no network
        // was found while it was demonstrably connected.
        viewModelScope.launch(Dispatchers.Default) {
            val wm = appContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            Diagnostics.note("client", "AUTOCONNECT_START poll=${LEGACY_POLL_MS}ms")
            try {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    // Back off once it is clear nothing is there: at a fixed 4 s
                    // the log filled with identical failures, which buried the
                    // one line that mattered and kept probing the radio.
                    delay(if (pollMisses >= MISS_BACKOFF_AFTER) LEGACY_POLL_IDLE_MS else LEGACY_POLL_MS)
                    if (_uiState.value.connectedHost != null) continue
                    if (_uiState.value.searching || busy.get()) continue
                    // Only a real host skips the poll. Previously this also
                    // matched a client that had been mislabelled as hosting,
                    // which disabled auto-connect permanently.
                    if (wifiDirect.state.value.hosting) continue
                    val ssid = runCatching {
                        // Permission-guarded (and SecurityException-throwing when
                        // location is revoked); the runCatching below is the
                        // handler, and the SSID is only a log hint anyway.
                        @Suppress("DEPRECATION")
                        @android.annotation.SuppressLint("MissingPermission")
                        wm.connectionInfo?.ssid?.removeSurrounding("\"")
                    }.getOrNull().orEmpty()
                    linkAttempts++
                    val networks = describeNetworks()
                    noteNetworkChange(networks, ssid)
                    val candidate = gatewayCandidate()
                    if (candidate == null) {
                        // Not on a usable IPv4 network yet, or on plain Wi-Fi
                        // with nothing to suggest a PeerNet host is there. Say
                        // so instead of retrying invisibly forever.
                        pollMisses++
                        if (pollMisses == MISS_BACKOFF_AFTER) {
                            Diagnostics.note(
                                "client",
                                "AUTOCONNECT_RETRY s=$session backing off to ${LEGACY_POLL_IDLE_MS}ms " +
                                    "after $pollMisses empty rounds"
                            )
                        }
                        reportLinkDiagnostic(
                            "Not on the host's network yet (try $linkAttempts). " +
                                "Join the DIRECT-… Wi-Fi in settings. Seen: $networks"
                        )
                        continue
                    }
                    pollMisses = 0
                    Diagnostics.note(
                        "client",
                        "HOST_IP_DETECTED s=$session ${candidate.address}:${candidate.port} ssid=$ssid"
                    )
                    lastProbeFailure = null
                    Diagnostics.note(
                        "client",
                        "LINK_ATTEMPT s=$session try=$linkAttempts ${candidate.address}:${candidate.port}"
                    )
                    val verified = probeDetails(candidate)
                    if (verified == null) {
                        Diagnostics.note(
                            "client",
                            "LINK_FAILED s=$session try=$linkAttempts " +
                                (lastProbeFailure ?: "did not verify")
                        )
                        reportLinkDiagnostic(
                            "On ${networks.ifBlank { "the network" }}, but " +
                                "${candidate.address}:${candidate.port} " +
                                (lastProbeFailure ?: "did not verify") +
                                " (try $linkAttempts)"
                        )
                        continue
                    }
                    Timber.i("Host detected on joined network (ssid=%s, gw=%s)", ssid, candidate.address)
                    Diagnostics.note("link", "host verified at ${candidate.address} (ssid=$ssid)")
                    if (busy.compareAndSet(false, true)) {
                        try {
                            link(verified, viaP2p = true)
                        } finally {
                            busy.set(false)
                        }
                    }
                }
            } finally {
                Diagnostics.note("client", "AUTOCONNECT_STOP s=$session (scope ended)")
            }
        }
    }

    /**
     * Records the network picture only when it changes. Emitting it every poll
     * turned the report into noise; the transition is the diagnostic.
     */
    private fun noteNetworkChange(networks: String, ssid: String) {
        val fingerprint = "$networks|$ssid"
        currentNetworkFingerprint = fingerprint
        if (fingerprint == lastNetworkFingerprint) return
        lastNetworkFingerprint = fingerprint
        Diagnostics.note("client", "NETWORK_DETECTED s=$session ssid=${ssid.ifBlank { "?" }} [$networks]")
        val looksDirect = ssid.startsWith("DIRECT-", ignoreCase = true) || networks.contains("p2p")
        if (looksDirect) {
            Diagnostics.note("client", "DIRECT_NETWORK_DETECTED s=$session via=${if (ssid.startsWith("DIRECT-", true)) "ssid" else "iface"}")
        }
    }

    /** Publishes a link-stage reason to the screen and to the shared report. */
    private fun reportLinkDiagnostic(message: String) {
        Diagnostics.note("link", message)
        _uiState.update { it.copy(linkDiagnostic = message) }
    }

    /**
     * Interfaces with an IPv4 address, as the system sees them. This is the
     * evidence needed to tell "the phone never joined the group" apart from
     * "joined, but the host is not answering".
     */
    private fun describeNetworks(): String {
        val cm = appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val parts = buildList {
            for (network in runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())) {
                val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
                val v4 = lp.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }
                    ?: continue
                add("${lp.interfaceName.orEmpty()}=${v4.address.hostAddress}/${v4.prefixLength}")
            }
        }
        return if (parts.isEmpty()) "no IPv4 network" else parts.joinToString(", ")
    }

    /** Live TUN capture counter for the UI (0 when not capturing). */
    fun packetCount(): Long = rustCore.tunPacketCount()

    /** QUIC tunnel state: 0 disconnected, 1 connecting, 2 connected, 3 backoff. */
    fun tunnelState(): Int = rustCore.tunnelState()

    /**
     * Engine data-path counters, e.g.
     * "tun=120 udp=44 tcp=9 in=3011 lost=0 cap=up eng=up".
     * `cap` is the TUN capture loop, `eng` the TCP terminator intake: with
     * `eng=up` a stuck `tcp=0` means the phone sent no SYN, not a dead engine.
     */
    fun engineStats(): String = rustCore.engineStats()

    /** Bytes/packets pushed toward the host (udp + tcp counters). */
    fun outboundCount(): Long = statValue("udp") + statValue("tcp")

    /** Payload bytes delivered back into the TUN; 0 means nothing reached the phone. */
    fun inboundCount(): Long = statValue("in")

    /**
     * Replies that arrived from the host but could not be handed to the phone.
     * Distinguishes "the host cannot reach the internet" from "the local
     * delivery path is broken" — they look identical on screen otherwise.
     */
    fun undeliveredCount(): Long = statValue("lost")

    /**
     * Whether the TUN capture loop is still running. A dead loop just freezes
     * the counters, which reads as "the phone sent nothing".
     */
    fun captureAlive(): Boolean = engineStats().contains("cap=up")

    private fun statValue(key: String): Long =
        engineStats()
            .split(' ')
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.toLongOrNull()
            ?: 0L

    /** Plain-language tunnel progress/error for the single screen. */
    val tunnelStatus: StateFlow<String> = linkManager.tunnelStatus

    /** Lets the UI report what only it can observe (e.g. denied VPN consent). */
    fun reportTunnelStatus(message: String) = linkManager.setTunnelStatus(message)

    /**
     * CONNECT button. Priority order:
     *  1. Learn the host id via mDNS, then JOIN its Wi-Fi Direct network with
     *     the stable credentials (API 33+) — the phone actually associates
     *     with DIRECT-PeerNet-xxxx, visible in Wi-Fi settings.
     *  2. Otherwise find a peer advertising a PeerNet name and invite it.
     *  3. Last resort: link over whatever network the phone is on right now
     *     (e.g. both phones on the same router). Such links carry a liveness
     *     watchdog so they die when the host stops sharing.
     */
    fun connectNow() {
        if (!busy.compareAndSet(false, true)) return
        // Tapping CONNECT is unambiguous new intent, so a previous stop must not
        // keep suppressing auto-connect afterwards.
        if (AutoConnectPolicy.clearsStop(userTappedConnect = true)) {
            userStopped = false
            stoppedOnFingerprint = null
        }
        _uiState.update {
            it.copy(searching = true, status = "Searching this network for a PeerNet host…")
        }
        viewModelScope.launch(Dispatchers.Default) {
            var joined = false
            try {
                wifiDirect.acquireMulticast()

                val hid = discoverHostId()
                // Both strings come from the shared policy so the client cannot
                // drift from what the host actually created. Auto-join only works
                // for the derived default; a host with a custom password has to be
                // joined from Wi-Fi settings, which the P2P_JOIN_FAILED note says.
                if (hid != null && wifiDirect.joinByCredentials(
                        ssid = com.peernet.wifiextender.wifi.GroupCredentialsPolicy.networkName(hid),
                        passphrase = com.peernet.wifiextender.wifi.GroupCredentialsPolicy
                            .derivePassphrase(hid)
                    )
                ) {
                    _uiState.update { it.copy(status = "Joining the PeerNet network…") }
                    joined = awaitJoined(JOIN_WAIT_MS)
                }

                if (!joined) {
                    val peer = findPeerNetPeer()
                    if (peer != null) {
                        _uiState.update { it.copy(status = "Joining ${peer.deviceName}…") }
                        wifiDirect.connectToPeer(peer.deviceAddress)
                        joined = awaitJoined(JOIN_WAIT_MS)
                    }
                }

                if (joined) {
                    _uiState.update { it.copy(status = "PeerNet network joined — establishing link…") }
                    val target = findVerifiedHost(rounds = AUTO_ROUNDS, userInitiated = true)
                    if (target != null) {
                        _uiState.update { it.copy(searching = false) }
                        link(target, viaP2p = true)
                        return@launch
                    }
                }

                val fallback = findVerifiedHost(rounds = MANUAL_ROUNDS, userInitiated = true)
                if (fallback != null) {
                    _uiState.update { it.copy(searching = false) }
                    link(fallback, viaP2p = joined)
                } else {
                    _uiState.update {
                        it.copy(
                            searching = false,
                            status = "No PeerNet host found. Join the host's DIRECT-xx network in Wi-Fi settings " +
                                "(password is on the host phone), then tap Connect."
                        )
                    }
                }
            } finally {
                wifiDirect.releaseMulticast()
                busy.set(false)
            }
        }
    }

    /** First mDNS round used purely to read the host's identity (hid TXT). */
    private suspend fun discoverHostId(): String? =
        runCatching { discovery.discoverOnce(timeoutMs = ROUND_TIMEOUT_MS) }
            .getOrDefault(emptyList())
            .firstOrNull { !it.hostId.isNullOrBlank() }?.hostId

    /** Polls P2P state until the device reports membership in a group as client. */
    private suspend fun awaitJoined(waitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (wifiDirect.state.value.joinedAsClient) return true
            delay(JOIN_POLL_MS)
        }
        return wifiDirect.state.value.joinedAsClient
    }

    /** Wi-Fi Direct scan for a peer whose advertised name starts with PeerNet. */
    private suspend fun findPeerNetPeer(): android.net.wifi.p2p.WifiP2pDevice? {
        wifiDirect.startPeerDiscovery()
        return try {
            kotlinx.coroutines.withTimeout(PEER_SCAN_MS) {
                wifiDirect.state.first { s -> s.peers.isNotEmpty() }.peers
                    .firstOrNull { it.deviceName.startsWith("PeerNet", ignoreCase = true) }
            }
        } catch (t: Throwable) {
            Timber.d("No PeerNet-named peer discovered: %s", t.message)
            null
        } finally {
            wifiDirect.stopPeerDiscovery()
        }
    }

    /**
     * Rising edge of "joined a Wi-Fi Direct group as client" (includes app
     * cold-start while already joined). Probes with patience so slow mDNS
     * propagation after a fresh join cannot produce a false "not available".
     */
    private fun autoLink() {
        if (_uiState.value.connectedHost != null) return
        if (!AutoConnectPolicy.shouldAutoLink(
                userStopped = userStopped,
                networkFingerprint = currentNetworkFingerprint,
                stoppedOnFingerprint = stoppedOnFingerprint
            )
        ) {
            // Without this the stop is undone within one poll, because clearLink
            // deliberately re-arms auto-connect for error recovery.
            Diagnostics.note("client", "AUTOCONNECT_SUPPRESSED s=$session (stopped by user)")
            return
        }
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                wifiDirect.acquireMulticast()
                _uiState.update {
                    it.copy(searching = true, status = "PeerNet network detected — looking for host…")
                }
                val target = findVerifiedHost(rounds = AUTO_ROUNDS)
                if (target != null) {
                    _uiState.update { it.copy(searching = false) }
                    link(target, viaP2p = true)
                } else {
                    // Used to go silent here ("the user can always tap
                    // CONNECT"), which hid every real cause. Report the last
                    // probe reason instead; the poll keeps retrying.
                    _uiState.update { it.copy(searching = false, status = "") }
                    reportLinkDiagnostic(
                        "Joined the PeerNet network but no host verified: " +
                            (lastProbeFailure ?: "no host answered on port ${LinkServer.PORT}")
                    )
                }
            } finally {
                wifiDirect.releaseMulticast()
                busy.set(false)
            }
        }
    }

    /**
     * Up to [rounds] NSD rounds; returns the first host whose link banner
     * verifies. Unreachable entries are retried in later rounds instead of
     * consuming the whole attempt.
     *
     * The gateway shortcut runs first: when this phone sits on a Wi-Fi Direct
     * network, the host IS the gateway, so one TCP probe finds it even when
     * mDNS is blocked, slow, or answering on the wrong interface (the most
     * common cause of "no PeerNet network found" while actually connected).
     */
    private suspend fun findVerifiedHost(rounds: Int, userInitiated: Boolean = false): DiscoveredHost? {
        gatewayCandidate(userInitiated)?.let { candidate ->
            probeDetails(candidate)?.let { verified ->
                Timber.i("Host found via link gateway %s", candidate.address)
                return verified
            }
        }
        repeat(rounds) { attempt ->
            val hosts = runCatching { discovery.discoverOnce(timeoutMs = ROUND_TIMEOUT_MS) }
                .getOrDefault(emptyList())
            for (host in hosts) {
                probeDetails(host)?.let { return it }
            }
            Timber.d("Round %d/%d: %d hosts advertised, none verified", attempt + 1, rounds, hosts.size)
            if (attempt < rounds - 1) {
                // A late-forming group can hand out its gateway between
                // rounds; keep retrying the cheap path too.
                gatewayCandidate(userInitiated)?.let { candidate ->
                    probeDetails(candidate)?.let { return it }
                }
                delay(ROUND_GAP_MS)
            }
        }
        return null
    }

    /**
     * The Wi-Fi Direct group owner (= the host) derived from routing state:
     * the default gateway of the P2P/Wi-Fi link, typically 192.168.49.1.
     * Needs no callbacks, no mDNS and no permissions.
     *
     * Candidates are ranked and filtered by [LinkPolicy]. Build #106 took the
     * first gateway it found, so a phone on an ordinary router probed
     * `192.168.31.1:4434` every few seconds forever — a retry storm against the
     * user's own router that also risked treating it as a host. Plain Wi-Fi is
     * now only probed with corroborating evidence (a remembered host at that
     * address, or an explicit CONNECT tap).
     */
    @SuppressLint("MissingPermission")
    private fun gatewayCandidate(userInitiated: Boolean = false): DiscoveredHost? {
        val cm = appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val known = savedHostAddresses()
        val ssid = currentSsid()
        val candidates = buildList {
            for (network in runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())) {
                val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: continue
                val iface = lp.interfaceName.orEmpty()
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                val isWifiLike = iface.startsWith("p2p", ignoreCase = true) ||
                    caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                if (!isWifiLike) continue
                // Never treat our own tunnel as a route to the host.
                if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) continue
                for (route in lp.routes) {
                    val gw = route.gateway ?: continue
                    if (gw is java.net.Inet4Address && !gw.isAnyLocalAddress) {
                        add(LinkPolicy.GatewayCandidate(gw.hostAddress ?: continue, iface, ssid, known))
                    }
                }
                // A /24 P2P group has no default route entry on some OEM
                // builds; the group owner still owns .1 of our own subnet.
                for (addr in lp.linkAddresses) {
                    val ip = addr.address
                    if (ip is java.net.Inet4Address && addr.prefixLength >= 24) {
                        val o = ip.address
                        val guess = "${o[0].toInt() and 0xFF}.${o[1].toInt() and 0xFF}." +
                            "${o[2].toInt() and 0xFF}.1"
                        add(LinkPolicy.GatewayCandidate(guess, iface, ssid, known))
                    }
                }
            }
        }.distinctBy { it.address }

        val ranked = LinkPolicy.rankGateways(candidates, userInitiated)
        val best = ranked.firstOrNull()
        if (best == null) {
            if (candidates.isNotEmpty()) {
                Diagnostics.note(
                    "client",
                    "AUTOCONNECT_IDLE s=$session no PeerNet evidence on " +
                        candidates.joinToString(", ") { "${it.address}@${it.interfaceName}" }
                )
            }
            return null
        }
        Diagnostics.note(
            "client",
            "P2P_NETWORK_SELECTED s=$session ${best.address}@${best.interfaceName} " +
                "ssid=${ssid ?: "?"} direct=${LinkPolicy.isWifiDirectAddress(best.address)}"
        )
        return DiscoveredHost(
            name = "PeerNet host",
            port = com.peernet.wifiextender.wifi.LinkServer.PORT,
            address = best.address,
            hostId = null
        )
    }

    /** Addresses of hosts this phone has linked to before. */
    private fun savedHostAddresses(): Set<String> {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        return buildSet {
            for (i in 0 until arr.length()) {
                arr.getJSONObject(i).optString("address").takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    /** Current Wi-Fi SSID, or null when unreadable (permission/OEM masking). */
    private fun currentSsid(): String? = runCatching {
        @Suppress("DEPRECATION")
        @android.annotation.SuppressLint("MissingPermission")
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        wm.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf {
            it.isNotBlank() && !it.contains("unknown", ignoreCase = true)
        }
    }.getOrNull()

    private fun link(host: DiscoveredHost, viaP2p: Boolean) {
        saveProfile(host)
        val gen = linkManager.setLinked(host, currentWifiNetwork())
        val pinMissing = host.fingerprint.isNullOrBlank()
        Diagnostics.note(
            "link",
            "linked to ${host.address}:${host.tunnelPort} pin=${if (pinMissing) "MISSING" else "yes"}"
        )
        Diagnostics.note(
            "client",
            "LINK_SUCCESS s=$session gen=$gen ${host.address}:${host.tunnelPort} " +
                "p2p=$viaP2p direct=${LinkPolicy.isWifiDirectAddress(host.address)} " +
                "pin=${if (pinMissing) "MISSING" else "yes"}"
        )
        _uiState.update {
            it.copy(
                connectedHost = host,
                savedHostIds = loadSavedHostIds(),
                linkDiagnostic = "",
                status = when {
                    pinMissing ->
                        "Linked to ${host.name}, but the host's tunnel engine is not ready. " +
                            "Tap SHARE off/on on the host phone, then reconnect."
                    viaP2p -> "Linked to ${host.name} via the PeerNet network."
                    else -> "Linked to ${host.name} over your current Wi-Fi."
                }
            )
        }
        startLiveness(host, gen)
    }

    /** Live network for VPN socket pinning (null = let the system choose). */
    fun linkedNetwork(): android.net.Network? = linkManager.linkedNetwork.value

    /**
     * The network the host link should ride on. Prefers the P2P interface
     * (name starts with "p2p"); falls back to any plain Wi-Fi transport.
     * Critical because Android routes app traffic over the DEFAULT network,
     * and a "connected without internet" P2P Wi-Fi loses that role to
     * cellular — where the host's private address is unreachable.
     */
    @SuppressLint("MissingPermission")
    private fun currentWifiNetwork(): android.net.Network? {
        val cm = appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networks = cm.allNetworks.toList()
        val p2p = networks.firstOrNull { n ->
            val lp = runCatching { cm.getLinkProperties(n) }.getOrNull()
            lp?.interfaceName?.startsWith("p2p", ignoreCase = true) == true
        }
        if (p2p != null) return p2p
        return networks.firstOrNull { n ->
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull()
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Watchdog for links that are NOT backed by a joined P2P group (e.g. both
     * phones on the same router): if the host stops answering, drop the link
     * instead of showing a stale "Connected". P2P-backed links are owned by
     * the group-teardown watcher above and are left alone here.
     */
    private fun startLiveness(host: DiscoveredHost, gen: Int) {
        livenessJob?.cancel()
        livenessJob = viewModelScope.launch(Dispatchers.Default) {
            var misses = 0
            while (isActive) {
                delay(LIVENESS_INTERVAL_MS)
                // Session guard: a job from a previous link must never judge
                // (or tear down) the session that replaced it.
                if (!linkManager.isCurrent(gen)) {
                    Diagnostics.note("liveness", "probe abandoned: session $gen superseded")
                    break
                }

                val net = probeNetwork()
                Diagnostics.note(
                    "client",
                    "LIVENESS_PROBE s=$session gen=$gen network=${net ?: "default"} " +
                        "interface=${networkLabel(net)} dst=${host.address}:${host.port}"
                )
                val alive = probe(host)
                if (!linkManager.isCurrent(gen)) break

                if (alive) {
                    misses = 0
                    Diagnostics.note("client", "LIVENESS_PROBE_SUCCESS s=$session gen=$gen")
                    continue
                }
                misses++
                val p2p = wifiDirect.state.value.joinedAsClient
                val hostIsDirect = LinkPolicy.isWifiDirectAddress(host.address)
                val connected = tunnelState() == TUNNEL_CONNECTED
                val delivering = tunnelDelivering()
                val routed = hostNetworkPresent(host.address)
                Diagnostics.note(
                    "client",
                    "LIVENESS_PROBE_TIMEOUT s=$session gen=$gen miss=$misses " +
                        "dst=${host.address} interface=${networkLabel(net)} " +
                        "p2p=$p2p directHost=$hostIsDirect quic=${tunnelState()} " +
                        "tun=${linkManager.tunnelActive.value} routed=$routed " +
                        "(${lastProbeFailure ?: "no reason recorded"})"
                )

                if (!LinkPolicy.shouldDropLink(
                        consecutiveMisses = misses,
                        missThreshold = LIVENESS_MISSES,
                        tunnelConnected = connected,
                        tunnelDelivering = delivering,
                        joinedAsClient = p2p,
                        hostIsWifiDirect = hostIsDirect,
                        hostNetworkPresent = routed
                    )
                ) {
                    LinkPolicy.keepReason(connected, delivering, p2p, hostIsDirect)?.let {
                        Diagnostics.note("liveness", "keeping link despite $misses misses: $it")
                    }
                    continue
                }

                Diagnostics.note(
                    "liveness",
                    "dropping link after $misses missed probes " +
                        "(${lastProbeFailure ?: "no reason recorded"})"
                )
                Diagnostics.note(
                    "client",
                    "HOST_LOST s=$session gen=$gen $misses missed probes, " +
                        if (!routed) {
                            "host network gone (group ended)"
                        } else {
                            "no tunnel, quic=${tunnelState()}"
                        }
                )
                clearLink(
                    if (!routed) "Host stopped sharing." else "Host disconnected."
                )
                break
            }
        }
    }

    /**
     * Whether this phone still holds an address on the host's /24.
     *
     * This is the signal that a share genuinely ended: when the host taps STOP
     * the group disappears, the client's Wi-Fi drops it, and the route into
     * 192.168.49.x goes with it. Unlike a probe timeout it cannot be produced by
     * a sleeping radio, so it is safe to act on immediately — and it is what
     * makes the link (and therefore the VPN) clear on a real STOP even though
     * probe failures alone no longer tear anything down.
     *
     * The tunnel's own interface is excluded: its 10.215.17.x address must never
     * be mistaken for evidence that the host is reachable.
     */
    @SuppressLint("MissingPermission")
    private fun hostNetworkPresent(hostAddress: String?): Boolean {
        val subnet = hostAddress?.substringBeforeLast('.', "")?.takeIf { it.isNotBlank() }
            ?: return true // Unknown address: do not invent a loss.
        return runCatching {
            val cm = appContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) {
                    return@any false
                }
                val lp = cm.getLinkProperties(network) ?: return@any false
                lp.linkAddresses.any { la ->
                    la.address is java.net.Inet4Address &&
                        la.address.hostAddress?.startsWith("$subnet.") == true
                }
            }
        }.getOrDefault(true) // Never drop a link because the query failed.
    }

    /**
     * True while replies are still arriving through the tunnel.
     *
     * A missed probe alone must never tear the link down: the probe is a plain
     * TCP connection that can fail for routing reasons while the QUIC tunnel
     * keeps delivering. Tearing down here restarted the VPN, which reset the
     * engine counters and re-ran auto-link — a flap loop that looks exactly
     * like "connects, then no internet".
     */
    private var lastInboundSeen = 0L

    private fun tunnelDelivering(): Boolean {
        val now = inboundCount()
        val progressed = now > lastInboundSeen
        lastInboundSeen = now
        return progressed
    }

    /**
     * Ends the session: cancels liveness, clears the link (which is what
     * `PeerNetVpnService` watches to tear the tunnel down) and reports
     * completion only once the VPN has actually gone away.
     *
     * Build #106 logged `CLIENT_CLEANUP_COMPLETED` here immediately, while
     * `tun0` and the Android VPN key were still up — the report claimed a
     * cleanup that had not happened. Safe to call repeatedly.
     */
    private fun clearLink(message: String) {
        val had = _uiState.value.connectedHost != null
        Diagnostics.note("link", "cleared: $message")
        livenessJob?.cancel()
        livenessJob = null
        // Invalidates the generation, so every in-flight probe, retry and VPN
        // bring-up belonging to this session abandons itself.
        linkManager.setLinked(null)
        _uiState.update { it.copy(connectedHost = null, status = message) }
        // Re-arm auto-connect: the next poll should probe immediately rather
        // than inherit the backoff from whatever ended the previous session.
        pollMisses = 0
        lastNetworkFingerprint = null
        lastInboundSeen = 0L
        Diagnostics.note("client", "AUTOCONNECT_RESET s=$session ($message)")

        if (!had) return
        viewModelScope.launch {
            // The service tears down asynchronously; wait for its own report
            // rather than assuming. Bounded so a wedged service cannot hide the
            // fact that cleanup did not finish.
            val stopped = withTimeoutOrNull(CLEANUP_WAIT_MS) {
                linkManager.tunnelActive.first { !it }
                true
            } != null
            Diagnostics.note(
                "client",
                if (stopped) {
                    "CLIENT_CLEANUP_COMPLETED s=$session ($message) tun=closed"
                } else {
                    "CLIENT_CLEANUP_INCOMPLETE s=$session ($message) " +
                        "VPN still active after ${CLEANUP_WAIT_MS}ms"
                }
            )
        }
    }

    /** Drops the link and leaves any joined Wi-Fi Direct group. */
    fun disconnect() {
        // Recorded before clearing, because clearLink re-arms auto-connect and the
        // poll would otherwise re-link straight away.
        userStopped = true
        stoppedOnFingerprint = currentNetworkFingerprint
        Diagnostics.note("client", "USER_STOPPED s=$session net=${currentNetworkFingerprint ?: "?"}")
        clearLink("Disconnected.")
        wifiDirect.leaveCurrentGroup()
    }

    fun forget(hostId: String) {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("hid") != hostId) out.put(arr.getJSONObject(i))
        }
        prefs.edit().putString(KEY_PROFILES, out.toString()).apply()
        _uiState.update { it.copy(savedHostIds = loadSavedHostIds()) }
    }

    private suspend fun probe(host: DiscoveredHost): Boolean = probeDetails(host) != null

    /**
     * The network every host-facing socket must ride on.
     *
     * Android routes an unbound socket over the DEFAULT network. A Wi-Fi Direct
     * group is marked "no internet", so on any phone with mobile data the
     * default is cellular — where the host's 192.168.49.x address does not
     * exist. An unbound probe therefore times out even though the host is one
     * hop away, which is exactly how "it stopped connecting automatically"
     * looks from the outside. `PeerNetVpnService` binds the whole process once
     * the tunnel is up, but discovery and auto-link run BEFORE that, so every
     * probe has to pin its own socket.
     */
    private fun probeNetwork(): android.net.Network? =
        linkManager.linkedNetwork.value ?: currentWifiNetwork()

    /** A socket pinned to [probeNetwork], or an unbound one if there is none. */
    private fun openProbeSocket(): Socket {
        val net = probeNetwork() ?: return Socket()
        return runCatching { net.socketFactory.createSocket() }.getOrNull() ?: Socket()
    }

    /** Interface name behind a network, for diagnostics ("p2p-p2p0-6", "wlan0"). */
    @SuppressLint("MissingPermission")
    private fun networkLabel(net: android.net.Network?): String {
        if (net == null) return "the default network"
        val cm = appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val name = runCatching { cm.getLinkProperties(net)?.interfaceName }.getOrNull()
        return name ?: net.toString()
    }

    /**
     * Verifies a host and returns it enriched with whatever the banner
     * reports. `PN-LINK-2` carries the QUIC certificate fingerprint and
     * tunnel port, which is the authoritative source: mDNS TXT records are
     * often stale (engine started after advertising) or dropped entirely,
     * and a client without the pin cannot open the tunnel at all.
     *
     * Every failure is recorded with its reason: a bare null here used to make
     * "not on the host's network", "host app not running" and "host engine not
     * ready" look identical on screen, which is precisely what made the
     * no-internet reports impossible to act on.
     */
    private suspend fun probeDetails(host: DiscoveredHost): DiscoveredHost? =
        when (val outcome = probeHost(host)) {
            is ProbeOutcome.Verified -> outcome.host
            is ProbeOutcome.Failed -> {
                val via = networkLabel(probeNetwork())
                lastProbeFailure = "${outcome.reason} (tried over $via)"
                Diagnostics.note("probe", "${host.address}:${host.port} ${outcome.reason} via $via")
                null
            }
        }

    private sealed interface ProbeOutcome {
        data class Verified(val host: DiscoveredHost) : ProbeOutcome
        data class Failed(val reason: String) : ProbeOutcome
    }

    private suspend fun probeHost(host: DiscoveredHost): ProbeOutcome =
        withContext(Dispatchers.IO) {
            try {
                openProbeSocket().use { s ->
                    s.soTimeout = 3_000
                    s.connect(InetSocketAddress(host.address, host.port), 3_000)
                    val banner = s.getInputStream().bufferedReader().readLine()
                    Timber.d("Probe banner from %s: %s", host.address, banner)
                    if (banner.isNullOrBlank()) {
                        return@withContext ProbeOutcome.Failed(
                            "connected but the host sent no banner"
                        )
                    }
                    if (!banner.startsWith(com.peernet.wifiextender.wifi.LinkServer.BANNER_PREFIX)) {
                        return@withContext ProbeOutcome.Failed(
                            "answered with something else: ${banner.take(40)}"
                        )
                    }
                    val parts = banner.trim().split(" ")
                    val bannerHid = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    val bannerFp = parts.getOrNull(2)
                        ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                    val bannerPort = parts.getOrNull(3)?.toIntOrNull()
                    Diagnostics.note(
                        "probe",
                        "${host.address}:${host.port} banner ok, " +
                            "pin=${if (bannerFp != null) "yes" else "MISSING"}, " +
                            "tunnelPort=${bannerPort ?: host.tunnelPort}"
                    )
                    ProbeOutcome.Verified(
                        host.copy(
                            hostId = host.hostId ?: bannerHid,
                            fingerprint = bannerFp ?: host.fingerprint,
                            tunnelPort = bannerPort ?: host.tunnelPort,
                            name = if (host.name == "PeerNet host" && bannerHid != null) {
                                "PeerNet-${bannerHid.takeLast(4)}"
                            } else {
                                host.name
                            }
                        )
                    )
                }
            } catch (e: java.net.ConnectException) {
                // Reachable address, nothing accepting: the host app is not
                // sharing (or was killed).
                ProbeOutcome.Failed("refused the connection — is SHARE on, on the host phone?")
            } catch (e: java.net.SocketTimeoutException) {
                ProbeOutcome.Failed("did not answer within 3s — wrong network, or the host app is asleep")
            } catch (e: java.io.IOException) {
                ProbeOutcome.Failed("unreachable (${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)})")
            }
        }

    private fun saveProfile(host: DiscoveredHost) {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        val obj = JSONObject().apply {
            put("hid", host.hostId ?: "")
            put("name", host.name)
            put("port", host.port)
            put("address", host.address ?: "")
            put("fp", host.fingerprint ?: "")
            put("tp", host.tunnelPort)
        }
        val out = JSONArray()
        out.put(obj)
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("hid") != (host.hostId ?: "")) out.put(arr.getJSONObject(i))
        }
        prefs.edit().putString(KEY_PROFILES, out.toString()).apply()
    }

    private fun loadSavedHostIds(): Set<String> {
        val arr = JSONArray(prefs.getString(KEY_PROFILES, "[]") ?: "[]")
        return buildSet {
            for (i in 0 until arr.length()) add(arr.getJSONObject(i).optString("hid"))
        }.filter { it.isNotBlank() }.toSet()
    }

    companion object {
        private const val PREFS = "peernet_client_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val MANUAL_ROUNDS = 3
        private const val AUTO_ROUNDS = 8
        private const val ROUND_TIMEOUT_MS = 4_000L
        private const val ROUND_GAP_MS = 1_200L
        private const val JOIN_WAIT_MS = 15_000L
        private const val JOIN_POLL_MS = 750L
        private const val PEER_SCAN_MS = 8_000L
        private const val LIVENESS_INTERVAL_MS = 5_000L
        private const val LIVENESS_MISSES = 2
        private const val LEGACY_POLL_MS = 4_000L

        /**
         * Slower cadence once the phone is clearly not on any host network.
         * A fixed 4 s retry produced a wall of identical diagnostics that hid
         * real failures and kept waking the Wi-Fi stack; the fast cadence is
         * restored the moment a candidate gateway or a join edge appears.
         */
        private const val LEGACY_POLL_IDLE_MS = 15_000L
        private const val MISS_BACKOFF_AFTER = 5

        /** `RustCoreBridge.tunnelState()` value meaning the QUIC tunnel is up. */
        private const val TUNNEL_CONNECTED = 2

        /**
         * How long cleanup waits for the VPN service to confirm the TUN is
         * closed. Bounded so a wedged service is reported rather than hiding
         * behind a cleanup message that never arrives.
         */
        private const val CLEANUP_WAIT_MS = 5_000L
    }
}
