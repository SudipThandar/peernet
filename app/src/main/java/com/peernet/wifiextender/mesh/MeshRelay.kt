package com.peernet.wifiextender.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.peernet.wifiextender.diag.Diagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mesh relay: lets a connected client also serve as a sub-host.
 *
 * When mesh is enabled on a device that is already connected to an upstream
 * host, this class creates a [WifiManager.startLocalOnlyHotspot] so nearby
 * PeerNet clients can discover and link to this node. The QUIC host engine
 * is started alongside the existing QUIC client tunnel; the process-level
 * network binding set by [PeerNetVpnService.bindProcessToLink] routes the
 * host engine's upstream sockets through the VPN tunnel to the original host.
 *
 * ## Limitations
 *
 * - Requires Android 11+ (API 30) where [startLocalOnlyHotspot] uses SoftAP
 *   and does not conflict with an active P2P connection. On API 29 the hotspot
 *   uses P2P under the hood and would tear down the upstream group.
 * - Whether the Rust engine supports [startHost][com.peernet.wifiextender.core.RustCoreBridge.startHost]
 *   and [startTunnel][com.peernet.wifiextender.core.RustCoreBridge.startTunnel]
 *   simultaneously is device-dependent. Failure is reported gracefully.
 * - Hotspot traffic from mesh clients enters the device's network stack. On
 *   stock Android without root, tethered traffic bypasses per-app VPN routing.
 *   The QUIC host engine's upstream sockets go through the VPN because the
 *   process is bound to the VPN network, so application-layer forwarding works,
 *   but transparent IP-level forwarding does not.
 */
@Singleton
class MeshRelay @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rustCore: com.peernet.wifiextender.core.RustCoreBridge
) {
    private val handler = Handler(Looper.getMainLooper())

    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val _isActive = MutableStateFlow(false)
    /** Whether the mesh hotspot is currently active. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _ssid = MutableStateFlow<String?>(null)
    /** The hotspot SSID, available once the hotspot starts. */
    val ssid: StateFlow<String?> = _ssid.asStateFlow()

    private val _password = MutableStateFlow<String?>(null)
    /** The hotspot password. */
    val password: StateFlow<String?> = _password.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Error message if the hotspot could not be started. */
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var hostEngineRunning = false

    /**
     * Starts the mesh hotspot.
     *
     * Call when the user enables mesh mode while connected to an upstream host.
     * Safe to call multiple times; subsequent calls are no-ops while active.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (_isActive.value) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _error.value = "Mesh requires Android 11 or later."
            Diagnostics.note("mesh", "MESH_START_FAILED sdk=${Build.VERSION.SDK_INT} < 30")
            return
        }
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            _error.value = "Wi-Fi is not available on this device."
            Diagnostics.note("mesh", "MESH_START_FAILED wifi_manager_null")
            return
        }
        Diagnostics.note("mesh", "MESH_START_REQUESTED")
        _error.value = null
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    // Try the newer API first (API 31+), fall back to the legacy one.
                    // On API 30-32 getSoftApConfiguration() may not expose the SSID.
                    val apConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { reservation.softApConfiguration }.getOrNull()
                    } else null
                    val wifiConfig = reservation.wifiConfiguration
                    _ssid.value = runCatching {
                        apConfig?.let {
                            it.javaClass.getMethod("getSsid").invoke(it) as? String
                        }
                    }.getOrNull()
                        ?: wifiConfig?.SSID?.removeSurrounding("\"")
                    _password.value = runCatching {
                        apConfig?.let {
                            val m = it.javaClass.getMethod("getPassphrase")
                            m.invoke(it) as? String
                        }
                    }.getOrNull()
                        ?: runCatching {
                            @Suppress("DEPRECATION")
                            WifiConfiguration::class.java
                                .getMethod("getPassphrase")
                                .invoke(wifiConfig) as? String
                        }.getOrNull()
                    _isActive.value = true
                    Diagnostics.note(
                        "mesh",
                        "MESH_HOTSPOT_STARTED ssid=${_ssid.value}"
                    )
                }

                override fun onStopped() {
                    hotspotReservation = null
                    _ssid.value = null
                    _password.value = null
                    _isActive.value = false
                    Diagnostics.note("mesh", "MESH_HOTSPOT_STOPPED")
                }

                override fun onFailed(reason: Int) {
                    val msg = when (reason) {
                        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                            "Tethering is not allowed on this device."
                        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
                            "Could not start the hotspot."
                        else -> "Hotspot failed (code $reason)."
                    }
                    _error.value = msg
                    Diagnostics.note("mesh", "MESH_HOTSPOT_FAILED reason=$reason $msg")
                }
            }, handler)
        } catch (t: Throwable) {
            _error.value = "Could not start hotspot: ${t.message}"
            Diagnostics.note("mesh", "MESH_START_EXCEPTION ${t.javaClass.simpleName}: ${t.message}")
            Timber.w(t, "startLocalOnlyHotspot failed")
        }
    }

    /**
     * Starts the QUIC host engine so mesh clients can link.
     *
     * Must be called after [start] succeeds. The host engine listens on
     * the link server port and advertises via mDNS on the hotspot network.
     * If the Rust engine refuses (e.g. cannot run host + tunnel
     * simultaneously), the hotspot still works for direct PeerNet linking.
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
     * Stops the mesh hotspot and tears down the host engine.
     * Safe to call multiple times.
     */
    fun stop() {
        stopHostEngine()
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
        _ssid.value = null
        _password.value = null
        _isActive.value = false
        _error.value = null
        Diagnostics.note("mesh", "MESH_STOPPED")
    }
}
