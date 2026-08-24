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
        // Always planted, debug or not: this is the only log the tester can
        // reach without adb.
        Timber.plant(com.peernet.wifiextender.diag.Diagnostics.Tree())
        com.peernet.wifiextender.diag.Diagnostics.note(
            "app",
            "started, Android ${android.os.Build.VERSION.SDK_INT} on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        )

        recordCrashesForNextLaunch()

        // Constraint 8: notification channels must be created at app startup, never lazily.
        createNotificationChannels()
    }

    /**
     * Persists the reason for any crash so the next launch can show it.
     *
     * The tester has no adb, so a crash was previously indistinguishable from
     * "the tunnel stopped working" — and a sticky service that crashes on start
     * loops invisibly, showing up only as data counters resetting.
     */
    private fun recordCrashesForNextLaunch() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val where = error.stackTrace.firstOrNull()
                    ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    .orEmpty()
                val message = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}$where"
                getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, "[${thread.name}] $message")
                    .commit() // synchronous: the process is about to die
            }
            previous?.uncaughtException(thread, error)
        }
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

        private const val CRASH_PREFS = "peernet_diagnostics"
        private const val KEY_LAST_CRASH = "last_crash"

        /** Last recorded crash, or null. Cleared once the user has seen it. */
        fun lastCrash(context: Context): String? =
            context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CRASH, null)

        fun clearLastCrash(context: Context) {
            context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH)
                .apply()
        }
    }
}
