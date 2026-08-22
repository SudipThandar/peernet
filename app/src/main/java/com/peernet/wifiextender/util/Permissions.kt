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
 */
object Permissions {

    fun runtimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Coarse location is implied by FINE but requested explicitly on 9 and below.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun missing(context: Context): List<String> =
        runtimePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasAllRequired(context: Context): Boolean = missing(context).isEmpty()
}
