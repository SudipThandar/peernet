package com.peernet.wifiextender.core

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw JNI surface. Symbol names must match peernet-ffi/src/lib.rs.
 * Never call directly from UI code — use [RustCoreBridge].
 */
internal object NativeCore {
    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("peernet_core")
            true
        } catch (t: Throwable) {
            Timber.w(t, "peernet_core native library unavailable")
            false
        }
        return loaded
    }

    external fun version(): String

    external fun newSessionId(): String

    /** Takes ownership of the TUN fd. Returns false when busy/invalid. */
    external fun startTunCapture(fd: Int, mtu: Int): Boolean

    external fun stopTunCapture(): Boolean

    external fun tunPacketCount(): Long

    /** Binds the QUIC host on 0.0.0.0:port; returns cert fingerprint or "". */
    external fun startHost(port: Int, deviceName: String, dnsUpstream: String): String

    external fun stopHost(): Boolean

    external fun hostSessionCount(): Int

    /** Connects to a pinned-fingerprint QUIC host. Progress via tunnelState(). */
    external fun startTunnel(serverAddr: String, fingerprintHex: String, deviceName: String): Boolean

    external fun stopTunnel(): Boolean

    /** 0 disconnected, 1 connecting, 2 connected, 3 backoff. */
    external fun tunnelState(): Int

    /** Human-readable last engine failure, or "" when healthy. */
    external fun lastError(): String

    /** Data-path counters, e.g. "tun=120 udp=44 tcp=9". */
    external fun engineStats(): String
}

/**
 * Safe Kotlin-facing bridge into the Rust core (spec Section 11.5).
 *
 * All calls are guarded: the app must never crash because the native engine
 * is missing or misbehaves. Milestone 4+ adds start/stop host/client and
 * stats; fd-passing callbacks land with VpnService in Milestone 6.
 */
@Singleton
class RustCoreBridge @Inject constructor() {

    val isAvailable: Boolean by lazy { NativeCore.ensureLoaded() }

    /** Engine version string, e.g. "peernet-core 0.1.0"; null when unavailable. */
    fun version(): String? {
        if (!isAvailable) return null
        return runCatching { NativeCore.version().ifEmpty { null } }
            .onFailure { Timber.w(it, "NativeCore.version failed") }
            .getOrNull()
    }

    /** Fresh 128-bit session id as 32-char hex; null when unavailable. */
    fun newSessionId(): String? {
        if (!isAvailable) return null
        return runCatching { NativeCore.newSessionId().ifEmpty { null } }
            .onFailure { Timber.w(it, "NativeCore.newSessionId failed") }
            .getOrNull()
    }

    /**
     * Hands a TUN file descriptor to the engine. The fd must already be
     * protected. After a successful call, Rust owns the fd — Kotlin must not
     * close it. Returns false when capture is already running or unavailable.
     */
    fun startTunCapture(fd: Int, mtu: Int): Boolean {
        if (!isAvailable) return false
        return runCatching { NativeCore.startTunCapture(fd, mtu) }
            .onFailure { Timber.w(it, "startTunCapture failed") }
            .getOrDefault(false)
    }

    fun stopTunCapture(): Boolean {
        if (!isAvailable) return false
        return runCatching { NativeCore.stopTunCapture() }.getOrDefault(false)
    }

    fun tunPacketCount(): Long {
        if (!isAvailable) return 0L
        return runCatching { NativeCore.tunPacketCount() }.getOrDefault(0L)
    }

    /** Cert fingerprint (lowercase hex) or null when the engine refused. */
    fun startHost(port: Int, deviceName: String, dnsUpstream: String): String? {
        if (!isAvailable) return null
        return runCatching { NativeCore.startHost(port, deviceName, dnsUpstream).ifEmpty { null } }
            .onFailure { Timber.w(it, "startHost failed") }
            .getOrNull()
    }

    fun stopHost(): Boolean {
        if (!isAvailable) return false
        return runCatching { NativeCore.stopHost() }.getOrDefault(false)
    }

    fun hostSessionCount(): Int {
        if (!isAvailable) return 0
        return runCatching { NativeCore.hostSessionCount() }.getOrDefault(0)
    }

    fun startTunnel(serverAddr: String, fingerprintHex: String, deviceName: String): Boolean {
        if (!isAvailable) return false
        return runCatching { NativeCore.startTunnel(serverAddr, fingerprintHex, deviceName) }
            .onFailure { Timber.w(it, "startTunnel failed") }
            .getOrDefault(false)
    }

    fun stopTunnel(): Boolean {
        if (!isAvailable) return false
        return runCatching { NativeCore.stopTunnel() }.getOrDefault(false)
    }

    /** 0 disconnected, 1 connecting, 2 connected, 3 backoff. */
    fun tunnelState(): Int {
        if (!isAvailable) return 0
        return runCatching { NativeCore.tunnelState() }.getOrDefault(0)
    }

    /** Last engine failure in plain words, or "" when nothing failed. */
    fun lastError(): String {
        if (!isAvailable) return "native engine missing from this build"
        return runCatching { NativeCore.lastError() }.getOrDefault("")
    }

    /** Data-path counters for on-screen diagnosis. */
    fun engineStats(): String {
        if (!isAvailable) return ""
        return runCatching { NativeCore.engineStats() }.getOrDefault("")
    }
}
