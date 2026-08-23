package com.peernet.wifiextender.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.peernet.wifiextender.MainActivity
import com.peernet.wifiextender.PeerNetApp
import com.peernet.wifiextender.R
import com.peernet.wifiextender.host.HostRuntime
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground host service (spec Section 10.4 / 18.1).
 *
 * Keeps hosting alive while the app is backgrounded or swiped away,
 * shows the persistent sharing notification, and restarts if killed.
 */
@AndroidEntryPoint
class HostForegroundService : Service() {

    @Inject lateinit var wifiDirect: WifiDirectManager
    @Inject lateinit var hostRuntime: HostRuntime

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Constraint: startForeground must happen within 5s of service creation.
        startAsForeground()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Remove the bar immediately, then tear down hosting.
                stopForeground(STOP_FOREGROUND_REMOVE)
                runCatching { hostRuntime.stopSharing() }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startAsForeground()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeState() {
        scope.launch {
            var lastSsid: String? = null
            // The state is a StateFlow, so collect() replays the CURRENT value
            // immediately. Acting on it would stop the service the instant it
            // starts (notification flashes and disappears) whenever hosting has
            // not been flagged yet — so wait until hosting is actually seen.
            var sawHosting = false
            wifiDirect.state.collect { s ->
                if (s.hosting || s.creating) sawHosting = true
                if (s.ssid != lastSsid) {
                    lastSsid = s.ssid
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(s.ssid))
                }
                if (sawHosting && !s.hosting && !s.creating) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(ssid: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, HostNotificationActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, PeerNetApp.CHANNEL_HOST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PeerNet is sharing your internet")
            .setContentText(ssid?.let { "Network: $it" } ?: "Local network starting…")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_notification, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.peernet.wifiextender.action.STOP_HOSTING"
        private const val NOTIFICATION_ID = 1001
    }
}
