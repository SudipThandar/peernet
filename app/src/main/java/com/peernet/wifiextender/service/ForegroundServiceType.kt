package com.peernet.wifiextender.service

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Decides what to pass to `startForeground()`.
 *
 * `specialUse` — both the manifest attribute and
 * [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE] — only exists from API 34.
 * On older releases the platform parses the unknown manifest value into a
 * declared type mask of `0x0`, and then rejects the call:
 *
 *     IllegalArgumentException: foregroundServiceType 0x40000000 is not a
 *     subset of foregroundServiceType attribute 0x0 in service element
 *
 * That throws the instant the service starts, and because the services are
 * sticky Android restarts and re-crashes them in a loop. Below API 34 the
 * two-argument `startForeground()` is the correct call: those platforms derive
 * no type at all, which is exactly what they expect.
 */
object ForegroundServiceType {

    /** Type for [sdkInt], or null when that platform must not be given one. */
    fun forSdk(sdkInt: Int): Int? =
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            null
        }

    /** Type for the device this code is running on. */
    fun current(): Int? = forSdk(Build.VERSION.SDK_INT)
}
