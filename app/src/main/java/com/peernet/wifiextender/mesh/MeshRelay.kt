package com.peernet.wifiextender.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import com.peernet.wifiextender.diag.Diagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Mesh relay: lets a connected client also serve as a sub-host.
 *
 * Creates a Wi-Fi Direct group using [WifiP2pManager.createGroup] so that
 * nearby PeerNet clients can discover and link to this node. Unlike
 * [android.net.wifi.WifiManager.startLocalOnlyHotspot], P2P groups use a
 * dedicated virtual interface that coexists with the device's existing Wi-Fi
 * connection on most hardware — the user's Wi-Fi or cellular stays active.
 *
 * The QUIC host engine is started alongside the existing QUIC client tunnel;
 * the process-level network binding set by PeerNetVpnService routes the host
 * engine's upstream sockets through the VPN tunnel to the original host.
 *
 * ## Limitations
 *
 * - Some budget or older devices only have a single radio interface and cannot
 *   maintain both a P2P client connection (to an upstream host) and a P2P
 *   group owner role (for mesh clients). When this happens, `createGroup`
 *   fails and the error is surfaced to the user.
 * - On API 29+ we can set a custom SSID and passphrase via
 *   [WifiP2pConfig.Builder]; on older APIs the system generates random ones.
 */
@Singleton
class MeshRelay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rustCore: com.peernet.wifiextender.core.RustCoreBridge
) {
    private var mgr: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    private val _isActive = MutableStateFlow(false)
    /** Whether the mesh P2P group is currently active. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _ssid = MutableStateFlow<String?>(null)
    /** The mesh group SSID, available once the group is created. */
    val ssid: StateFlow<String?> = _ssid.asStateFlow()

    private val _password = MutableStateFlow<String?>(null)
    /** The mesh group password. */
    val password: StateFlow<String?> = _password.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Error message if the group could not be created. */
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var hostEngineRunning = false

    private fun ensureChannel(): WifiP2pManager.Channel? {
        if (channel != null) return channel
        val m = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return null
        mgr = m
        val ch = m.initialize(context, Looper.getMainLooper(), null)
        channel = ch
        return ch
    }

    /**
     * Starts the mesh Wi-Fi Direct group.
     *
     * Call when the user enables mesh mode while connected to an upstream host.
     * Safe to call multiple times; subsequent calls are no-ops while active.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (_isActive.value) return
        val ch = ensureChannel()
        if (ch == null) {
            _error.value = "Wi-Fi Direct is not available on this device."
            Diagnostics.note("mesh", "MESH_START_FAILED p2p_null")
            return
        }
        val m = mgr ?: return
        Diagnostics.note("mesh", "MESH_GROUP_CREATE_REQUESTED")
        _error.value = null

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _isActive.value = true
                Diagnostics.note("mesh", "MESH_GROUP_CREATE_SUCCESS")
                fetchGroupInfo(m, ch)
            }

            override fun onFailure(reason: Int) {
                val msg = when (reason) {
                    WifiP2pManager.ERROR -> "Internal Wi-Fi Direct error."
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device."
                    WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Try again."
                    else -> "Could not create mesh group (code $reason)."
                }
                _error.value = msg
                Diagnostics.note("mesh", "MESH_GROUP_CREATE_FAILED reason=$reason $msg")
                Timber.w("createGroup failed: %d", reason)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val name = "DIRECT-peernet-${Random.nextInt(10000)}"
                val pass = "peernet${Random.nextInt(1000000)}"
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(name)
                    .setPassphrase(pass)
                    .build()
                m.createGroup(ch, config, listener)
            } else {
                m.createGroup(ch, listener)
            }
        } catch (t: Throwable) {
            _error.value = "Could not create mesh group: ${t.message}"
            Diagnostics.note("mesh", "MESH_GROUP_CREATE_EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
            Timber.w(t, "createGroup failed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchGroupInfo(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        try {
            m.requestGroupInfo(ch) { group ->
                if (group != null) {
                    val groupSsid = group.networkName?.removePrefix("DIRECT-")
                    _ssid.value = groupSsid ?: group.networkName
                    _password.value = runCatching {
                        val m2 = group.javaClass.getMethod("getPassphrase")
                        m2.invoke(group) as? String
                    }.getOrNull() ?: group.passphrase
                    Diagnostics.note("mesh", "MESH_GROUP_INFO ssid=${_ssid.value}")
                } else {
                    Diagnostics.note("mesh", "MESH_GROUP_INFO_NULL")
                }
            }
        } catch (t: Throwable) {
            Diagnostics.note("mesh", "MESH_GROUP_INFO_FAILED ${t.message}")
        }
    }

    /**
     * Starts the QUIC host engine so mesh clients can link.
     *
     * Must be called after [start] succeeds. The host engine listens on
     * the link server port and advertises via mDNS on the mesh network.
     */
    fun startHostEngine(port: Int, deviceName: String, dnsUpstream: String): Boolean {
        if (hostEngineRunning) return true
        Diagnostics.note("mesh", "MESH_ENGINE_START_REQUESTED port=$port dns=$dnsUpstream")
        val fp = rustCore.startHost(port, deviceName, dnsUpstream)
        if (fp.isNullOrBlank()) {
            val err = rustCore.lastError()
            Diagnostics.note("mesh", "MESH_ENGINE_START_FAILED ${err.ifBlank { "unknown" }}")
            Timber.w("Mesh QUIC host engine refused: %s", err)
            return false
        }
        hostEngineRunning = true
        Diagnostics.note("mesh", "MESH_ENGINE_STARTED port=$port")
        return true
    }

    /** Stops the QUIC host engine if it was started by the mesh relay. */
    fun stopHostEngine() {
        if (!hostEngineRunning) return
        rustCore.stopHost()
        hostEngineRunning = false
        Diagnostics.note("mesh", "MESH_ENGINE_STOPPED")
    }

    /**
     * Stops the mesh P2P group and tears down the host engine.
     * Safe to call multiple times.
     */
    fun stop() {
        stopHostEngine()
        val m = mgr
        val ch = channel
        if (m != null && ch != null) {
            try { m.removeGroup(ch, null) } catch (_: Throwable) {}
        }
        _ssid.value = null
        _password.value = null
        _isActive.value = false
        _error.value = null
        Diagnostics.note("mesh", "MESH_STOPPED")
    }
}
