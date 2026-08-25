package com.peernet.wifiextender.power

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Pure decision for the one-time "let PeerNet run in the background" prompt.
 *
 * Doze is the last unaddressed cause of the screen-off stall. Both phones now
 * hold a `WifiManager.WifiLock` (host since run #108, client since run #114),
 * which keeps the Wi-Fi radio serviceable - but a lock on the radio does not
 * stop the system from suspending the app itself. `PowerManager` wake locks are
 * ruled out by design here, so a user-granted Doze exemption is the only
 * remaining lever.
 */
object DozeExemptionPolicy {

    /**
     * Whether to show the system exemption dialog.
     *
     * Deliberately narrow, because this app is meant to be two buttons and no
     * settings:
     *  - only once a session is actually running, so the dialog is never the
     *    first thing a new user sees and always has visible context;
     *  - never when the exemption is already granted;
     *  - never twice. A user who declined has answered, and re-asking every
     *    session would turn a two-button app into a nag screen.
     */
    fun shouldPrompt(
        sessionActive: Boolean,
        alreadyExempt: Boolean,
        alreadyAsked: Boolean
    ): Boolean = sessionActive && !alreadyExempt && !alreadyAsked
}

/**
 * The Android side of the exemption, kept apart from the rule above so the rule
 * stays unit-testable (this project has no Robolectric).
 */
object DozeExemption {

    private const val PREFS = "peernet_power"
    private const val KEY_ASKED = "doze_exemption_asked"

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    fun wasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED, true)
            .apply()
    }

    /**
     * Fires the system dialog, recording the attempt first so a failure to launch
     * can never turn into a prompt loop.
     *
     * Returns false if the dialog could not be shown; some OEM builds and work
     * profiles have no activity for this action. Callers must treat that as a
     * degraded but working session, never as a reason to fail a tunnel.
     */
    fun requestExemption(context: Context): Boolean {
        markAsked(context)
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
