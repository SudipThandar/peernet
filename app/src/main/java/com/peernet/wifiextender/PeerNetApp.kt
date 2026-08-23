package com.peernet.wifiextender

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.peernet.wifiextender.host.HostRuntime
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class PeerNetApp : Application() {

    @Inject
    lateinit var hostRuntime: HostRuntime

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Constraint 8: notification channels must be created at app startup, never lazily.
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        // IMPORTANCE_DEFAULT, not LOW: a LOW channel is filed under "Silent"
        // and shows no status-bar icon on many OEM builds, which is why the
        // sharing notification appeared to come and go.
        val host = NotificationChannel(
            CHANNEL_HOST,
            getString(R.string.channel_host_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_host_description)
            setShowBadge(false)
        }

        // Separate channel: a client's tunnel is not "hosting status", and one
        // shared channel means turning off one notification hides both.
        val tunnel = NotificationChannel(
            CHANNEL_TUNNEL,
            getString(R.string.channel_tunnel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_tunnel_description)
            setShowBadge(false)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(host)
        manager.createNotificationChannel(tunnel)
    }

    companion object {
        const val CHANNEL_HOST = "peernet_host"
        const val CHANNEL_TUNNEL = "peernet_tunnel"
    }
}
