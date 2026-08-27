package com.peernet.wifiextender.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.peernet.wifiextender.MainActivity
import com.peernet.wifiextender.PeerNetApp
import com.peernet.wifiextender.R
import com.peernet.wifiextender.diag.Diagnostics
import com.peernet.wifiextender.host.HostRuntime
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
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

    /**
     * Reports screen transitions and what is still alive across them.
     *
     * "Client internet stops when the host screen turns off" was impossible to
     * attribute without this: the app looked identical before and after, so it
     * could equally have been the responder thread dying, the group being torn
     * down, the engine stopping, or the radio power-saving. These entries plus
     * the periodic [aliveTick] separate those cases in the shared diagnostics.
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Diagnostics.note("host", "HOST_SCREEN_OFF")
                    hostRuntime.reportAliveness("screen off")
                }
                Intent.ACTION_SCREEN_ON -> {
                    Diagnostics.note("host", "HOST_SCREEN_ON")
                    hostRuntime.reportAliveness("screen on")
                }
            }
        }
    }

    private var receiverRegistered = false

    /**
     * The sharing session this service instance belongs to.
     *
     * `stopService()` is asynchronous, so this instance's `onDestroy` can be
     * delivered *after* the user has started a new share. Without this stamp the
     * dying instance tore down the new session.
     */
    private var session = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = hostRuntime.currentSessionId()
        if (com.peernet.wifiextender.power.DozeExemptionPolicy.isSamsungDevice()) {
            com.peernet.wifiextender.diag.Diagnostics.note(
                "host",
                "SAMSUNG_DEVICE_SERVICE_STARTED - Samsung battery " +
                    "optimization may kill this service when the screen turns off"
            )
        }
        // Constraint: startForeground must happen within 5s of service creation.
        startAsForeground()
        registerScreenReceiver()
        observeState()
        aliveTick()
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // Screen intents are system-only and cannot be manifest-declared.
        runCatching { registerReceiver(screenReceiver, filter) }
            .onSuccess { receiverRegistered = true }
            .onFailure { Timber.w(it, "screen receiver not registered") }
    }

    /**
     * Periodic proof-of-life while sharing.
     *
     * The user has no adb, so a 60-second screen-off test must leave evidence
     * in the diagnostics buffer. A gap in these entries means the process was
     * frozen; entries that continue while the client loses internet point at the
     * radio instead, which is what the Wi-Fi lock addresses.
     */
    private fun aliveTick() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(ALIVE_TICK_MS)
                hostRuntime.reportAliveness("tick")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Remove the bar immediately, then tear down hosting.
                stopForeground(STOP_FOREGROUND_REMOVE)
                runCatching { hostRuntime.stopSharing() }
                stopSelf()
                return START_STICKY
            }
            else -> startAsForeground()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification(null)
        try {
            val type = ForegroundServiceType.current()
            if (type != null) {
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Hosting itself does not depend on the bar; never take the app
            // down (and into a sticky crash loop) over a notification.
            Timber.e(t, "host startForeground rejected")
            stopSelf()
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
            var reconciled = false
            wifiDirect.state.collect { s ->
                if (s.hosting || s.creating) sawHosting = true
                if (s.ssid != lastSsid) {
                    lastSsid = s.ssid
                    val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(s.ssid))
                }
                if (sawHosting && !s.hosting && !s.creating && !reconciled) {
                    // The group *appears* to have ended. This is NOT trusted:
                    // `refreshGroupInfo()` publishes exactly this when
                    // `requestGroupInfo` transiently returns null immediately
                    // after a successful createGroup, while the group is fine.
                    // Build #108 called `stopSharing()` here and killed live
                    // shares - "tapping SHARE does nothing". The runtime checks
                    // the user's hosting intent and only agrees when hosting has
                    // really ended; until then this service keeps running.
                    val ended = runCatching {
                        hostRuntime.noteHostingEnded("group ended", session)
                    }.getOrDefault(false)
                    if (ended) {
                        reconciled = true
                        Diagnostics.note("host", "HOST_GROUP_ENDED (service stopping)")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
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
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        // Non-destructive on purpose. This instance may be dying long after the
        // user started a *new* share (stopService is asynchronous), so it reports
        // its own session id and the runtime ignores it unless that session is
        // still current and nothing is hosting. Build #108 called stopSharing()
        // unconditionally here, which tore down brand-new shares.
        runCatching { hostRuntime.noteHostingEnded("service destroyed", session) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.peernet.wifiextender.action.STOP_HOSTING"
        private const val NOTIFICATION_ID = 1001

        /** Proof-of-life cadence; short enough to prove a 60s screen-off test. */
        private const val ALIVE_TICK_MS = 15_000L
    }
}
