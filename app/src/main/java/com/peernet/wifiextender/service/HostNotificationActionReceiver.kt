package com.peernet.wifiextender.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.peernet.wifiextender.host.HostRuntime
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles the notification "Stop" action.
 *
 * A manifest-declared receiver is used instead of a service intent because it
 * is delivered reliably on every OEM build, and Hilt injects [HostRuntime]
 * even when the rest of the app process was recently restarted.
 */
@AndroidEntryPoint
class HostNotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var hostRuntime: HostRuntime

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HostForegroundService.ACTION_STOP) return
        Timber.i("Notification Stop received")
        runCatching { hostRuntime.stopSharing() }
            .onFailure { Timber.w(it, "stopSharing from notification failed") }

        // Ask the (possibly running) foreground service to remove its bar.
        runCatching {
            context.startService(
                Intent(context, HostForegroundService::class.java)
                    .setAction(HostForegroundService.ACTION_STOP)
            )
        }.onFailure { Timber.w(it, "service stop poke failed") }
    }
}
