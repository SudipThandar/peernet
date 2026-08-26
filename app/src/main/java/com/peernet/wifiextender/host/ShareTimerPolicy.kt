package com.peernet.wifiextender.host

import android.content.Context

/**
 * How long a share may run before it stops itself.
 *
 * `UNLIMITED` is the paid option. Every other value is available to everyone, so
 * the free tier is time-limited rather than crippled - a 30-minute share is a
 * complete, working share.
 */
enum class ShareDuration(val minutes: Int?) {
    MIN_30(30),
    MIN_45(45),
    MIN_60(60),
    UNLIMITED(null);

    /** Paid, because it is the only option with no ceiling. */
    val requiresPremium: Boolean get() = minutes == null

    val label: String get() = minutes?.let { "$it min" } ?: "No limit"
}

/**
 * Whether the user has paid.
 *
 * Deliberately a single hard-coded `false` for now. Play Billing is not wired yet,
 * and this is the one place that changes when it is - so no other code has to
 * learn about entitlements, and the unlimited option cannot accidentally become
 * free through a forgotten check elsewhere.
 */
object Entitlements {
    fun isPremium(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false
}

/**
 * The share auto-stop clock.
 *
 * Two things this must never do, both of which would be indistinguishable from the
 * unexplained 5-10 minute host death that is still open:
 *
 * 1. Expire early. A backwards clock jump (NTP correction, user changing the time,
 *    device rebooting mid-share) must not be read as "the whole budget elapsed".
 * 2. Expire silently. Every timer stop records its own reason, so a deliberate
 *    stop can always be told apart from the bug.
 */
object ShareTimerPolicy {

    /** Used when nothing is stored, and when a paid choice is no longer paid for. */
    val DEFAULT = ShareDuration.MIN_60

    fun isSelectable(duration: ShareDuration, premium: Boolean): Boolean =
        !duration.requiresPremium || premium

    /**
     * The duration that will actually be enforced.
     *
     * A stored `UNLIMITED` from a lapsed subscription (or from a build where the
     * option was free) falls back to [DEFAULT] instead of being honoured, so
     * unlimited sharing can never be inherited without an entitlement.
     */
    fun resolve(requested: ShareDuration, premium: Boolean): ShareDuration =
        if (isSelectable(requested, premium)) requested else DEFAULT

    /**
     * Milliseconds left, or null when the share has no time limit.
     *
     * Clamped at zero so callers never see a negative countdown, and a clock that
     * moved backwards yields the full budget rather than an instant expiry.
     */
    fun remainingMs(startedAtMs: Long, nowMs: Long, duration: ShareDuration): Long? {
        val budget = duration.minutes?.times(60_000L) ?: return null
        val elapsed = nowMs - startedAtMs
        if (elapsed < 0L) return budget
        return (budget - elapsed).coerceAtLeast(0L)
    }

    /** True once the budget is spent. Never true for an unlimited share. */
    fun hasExpired(startedAtMs: Long, nowMs: Long, duration: ShareDuration): Boolean =
        remainingMs(startedAtMs, nowMs, duration) == 0L

    /** `mm:ss` for the countdown, or null when there is nothing to count down. */
    fun formatRemaining(remainingMs: Long?): String? {
        if (remainingMs == null) return null
        val totalSeconds = remainingMs / 1000L
        return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }
}

/**
 * Why a share ended, recorded as `HOST_STOP_REASON` in the diagnostics dump.
 *
 * These must stay distinct. The host dying by itself after 5-10 minutes is still
 * unexplained, and a timer that stopped a share without saying so would make that
 * investigation unreadable - every future dump has to answer "did the user's limit
 * do this, or the bug?" without ambiguity.
 */
object HostStopReason {
    const val USER = "user"
    const val TIMER_EXPIRED = "timer-expired"
}

/** Persists the user's choice across shares and restarts. */
object ShareTimerSetting {

    private const val PREFS = "peernet_group"
    private const val KEY_DURATION = "share_duration"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The stored choice, already resolved against the current entitlement. An
     * unreadable or unknown stored value is ignored rather than crashing a share.
     */
    fun load(context: Context): ShareDuration {
        val stored = prefs(context).getString(KEY_DURATION, null)
        val requested = ShareDuration.values().firstOrNull { it.name == stored }
            ?: ShareTimerPolicy.DEFAULT
        return ShareTimerPolicy.resolve(requested, Entitlements.isPremium(context))
    }

    fun save(context: Context, duration: ShareDuration) {
        prefs(context).edit().putString(KEY_DURATION, duration.name).apply()
    }
}
