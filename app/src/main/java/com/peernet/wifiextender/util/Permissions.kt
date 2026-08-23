package com.peernet.wifiextender.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Central permission helper (Section 10.3 / 19.3).
 *
 * Returns only the permissions that still need a runtime grant for the
 * current Android version. Static manifest permissions (INTERNET, WAKE_LOCK,
 * FOREGROUND_SERVICE, etc.) are granted at install time and are not listed.
 *
 * Required vs optional matters: after a second denial Android stops showing
 * the dialog and returns "denied" immediately. Gating SHARE on a *cosmetic*
 * permission therefore disables the app permanently with no explanation, so
 * only permissions the radio work genuinely needs may be required.
 */
object Permissions {

    /** Permissions without which Wi-Fi Direct simply cannot run. */
    fun required(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Coarse location is implied by FINE but requested explicitly on 9 and below.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    /** Nice-to-have only: without it the service still runs, unseen. */
    fun optional(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Everything worth asking for at first launch, required first. */
    fun runtimePermissions(): List<String> = required() + optional()

    private fun denied(context: Context, permissions: List<String>): List<String> =
        permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /** Missing *required* permissions — the only ones that may block sharing. */
    fun missing(context: Context): List<String> = denied(context, required())

    /** Missing permissions of any kind, for the first-launch prompt. */
    fun missingAny(context: Context): List<String> = denied(context, runtimePermissions())

    fun hasAllRequired(context: Context): Boolean = missing(context).isEmpty()

    /** True when notifications were denied, so the UI can explain the silence. */
    fun notificationsBlocked(context: Context): Boolean = denied(context, optional()).isNotEmpty()
}
