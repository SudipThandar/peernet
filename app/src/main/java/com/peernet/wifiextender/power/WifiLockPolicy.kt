package com.peernet.wifiextender.power

/**
 * Chooses the `WifiManager.WifiLock` mode for both ends of the tunnel.
 *
 * ## Why this exists (audit finding RC-2)
 *
 * Both the host and the client used to acquire `WIFI_MODE_FULL_LOW_LATENCY` on
 * API 29+ and fall back to `WIFI_MODE_FULL_HIGH_PERF` only below that. That is
 * backwards for this app.
 *
 * `WIFI_MODE_FULL_LOW_LATENCY` is only *in effect* while the screen is ON and
 * the acquiring app is in the foreground. The entire reason this app holds a
 * Wi-Fi lock is to stop the radio entering power save when the screen goes OFF -
 * so on every device this app has been tested on (API 29 and API 31) the lock
 * was inactive at exactly the moment it was needed. Logs showed
 * `WIFI_LOCK_ACQUIRED` immediately before `UNDERLYING_NETWORK_LOST`: the line
 * was true and simultaneously meaningless.
 *
 * `WIFI_MODE_FULL_HIGH_PERF` is deprecated but is *not* a no-op: the framework
 * still disables radio power save for as long as the lock is held, and it does
 * so regardless of screen state or process importance. That screen-state
 * independence is the only property this app actually needs.
 *
 * (`WIFI_MODE_FULL` genuinely is a no-op from API 29 and is never used here.)
 *
 * This is not "another lock" - the count of locks held is unchanged. It is a
 * correction to the mode of the lock that was already there.
 *
 * ## Why the constants are duplicated
 *
 * This project has no Robolectric, so anything that touches `WifiManager`
 * cannot be unit tested. The literal values are stable platform API constants,
 * declared here so the decision is pure and gated by
 * `WifiLockPolicyTest`. [assertMatchesPlatform] pins them to the real framework
 * values, and is called from the instrumented tests where the framework exists.
 */
object WifiLockPolicy {

    /** `WifiManager.WIFI_MODE_FULL` - non-functional since API 29. Never used. */
    const val MODE_FULL = 1

    /** `WifiManager.WIFI_MODE_FULL_HIGH_PERF`. Deprecated, functional, screen-state independent. */
    const val MODE_FULL_HIGH_PERF = 3

    /** `WifiManager.WIFI_MODE_FULL_LOW_LATENCY`. API 29+, screen-on + foreground only. */
    const val MODE_FULL_LOW_LATENCY = 4

    /**
     * The mode to acquire, for any API level.
     *
     * Deliberately not a function of `Build.VERSION.SDK_INT`: the requirement
     * (survive screen-off) does not change with API level, and the mode that
     * satisfies it does not either. Taking no parameter is the point - a future
     * reader cannot reintroduce a version split without deleting this comment.
     */
    fun lockMode(): Int = MODE_FULL_HIGH_PERF

    /**
     * Whether [mode] still holds the radio out of power save once the screen is
     * off and the app is no longer foreground.
     *
     * This is the property the tunnel depends on, so it is asserted directly
     * rather than left as a comment.
     */
    fun isEffectiveWhileScreenOff(mode: Int): Boolean = when (mode) {
        MODE_FULL_HIGH_PERF -> true
        MODE_FULL_LOW_LATENCY -> false // screen-on + foreground only
        else -> false                  // MODE_FULL is a no-op; anything else is unknown
    }

    /**
     * Human-readable mode name for the diagnostics dump.
     *
     * The old log line printed the raw integer, which told the reader nothing
     * about whether the lock would survive screen-off - the one fact that
     * mattered when reading a screen-off failure report.
     */
    fun describe(mode: Int): String {
        val name = when (mode) {
            MODE_FULL -> "FULL"
            MODE_FULL_HIGH_PERF -> "FULL_HIGH_PERF"
            MODE_FULL_LOW_LATENCY -> "FULL_LOW_LATENCY"
            else -> "UNKNOWN($mode)"
        }
        val screenOff = if (isEffectiveWhileScreenOff(mode)) {
            "survives-screen-off"
        } else {
            "SCREEN-ON-ONLY"
        }
        return "$name $screenOff"
    }

    /**
     * Cross-check the duplicated constants against the real framework values.
     *
     * Returns null when they agree, or a description of the mismatch. Called
     * from instrumented tests, where `WifiManager` is real.
     */
    fun assertMatchesPlatform(
        platformFull: Int,
        platformHighPerf: Int,
        platformLowLatency: Int
    ): String? {
        val problems = buildList {
            if (platformFull != MODE_FULL) add("FULL $platformFull != $MODE_FULL")
            if (platformHighPerf != MODE_FULL_HIGH_PERF) {
                add("FULL_HIGH_PERF $platformHighPerf != $MODE_FULL_HIGH_PERF")
            }
            if (platformLowLatency != MODE_FULL_LOW_LATENCY) {
                add("FULL_LOW_LATENCY $platformLowLatency != $MODE_FULL_LOW_LATENCY")
            }
        }
        return if (problems.isEmpty()) null else problems.joinToString("; ")
    }
}
