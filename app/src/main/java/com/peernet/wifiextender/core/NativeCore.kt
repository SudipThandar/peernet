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
}
