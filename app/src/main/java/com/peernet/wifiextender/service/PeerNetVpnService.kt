package com.peernet.wifiextender.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.app.Notification
import android.app.PendingIntent
import android.net.VpnService
import androidx.core.app.NotificationCompat
import com.peernet.wifiextender.MainActivity
import com.peernet.wifiextender.PeerNetApp
import com.peernet.wifiextender.R
import com.peernet.wifiextender.core.RustCoreBridge
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Client-side VPN tunnel (spec Sections 10.5/10.6, Milestone 6).
 *
 * Establishes the TUN, protects the fd, and hands it to the Rust engine.
 * Ownership: after [RustCoreBridge.startTunCapture] succeeds, Rust owns the
 * fd; this service never touches it again (single-owner, no double close).
 */
@AndroidEntryPoint
class PeerNetVpnService : VpnService() {

    @Inject lateinit var rustCore: RustCoreBridge

    private var tunFd: Int = -1

    override fun onBind(intent: Intent?) = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
        }

        if (tunFd != -1) {
            // Already capturing.
            return START_STICKY
        }

        val fd = establishTun()
        if (fd < 0) {
            Timber.w("TUN establishment failed; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        tunFd = fd

        val started = rustCore.startTunCapture(fd, MTU)
        if (!started) {
            Timber.w("Rust refused TUN capture; stopping")
            stopTunnel()
            return START_NOT_STICKY
        }

        Timber.i("TUN capture started (fd=%d mtu=%d)", fd, MTU)
        return START_STICKY
    }

    /**
     * Establishes the TUN per spec 10.6 and returns the detached fd,
     * or -1 on any failure. protect() runs BEFORE detach so a failed
     * protection aborts cleanly with the pfd still owned here.
     */
    private fun establishTun(): Int {
        val builder = Builder()
            .setSession(SESSION)
            .setMtu(MTU)
            .addAddress(VPN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(VIRTUAL_DNS)
            .addDisallowedApplication(packageName) // never route our own tunnel
            .setBlocking(false)

        val pfd: ParcelFileDescriptor = builder.establish() ?: return -1
        return try {
            // protect(int) before ownership transfer — routing-loop guard.
            val currentFd: Int = pfd.fd
            val ok: Boolean = protect(currentFd)
            if (!ok) {
                Timber.w("protect(fd) failed — aborting tunnel to avoid routing loops")
                runCatching { pfd.close() }
                return -1
            }
            pfd.detachFd()
        } catch (t: Throwable) {
            Timber.w(t, "tun handoff failed")
            runCatching { pfd.close() }
            -1
        }
    }

    private fun stopTunnel() {
        rustCore.stopTunCapture()
        tunFd = -1
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification =
            NotificationCompat.Builder(this, PeerNetApp.CHANNEL_HOST)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("PeerNet tunnel active")
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(openIntent)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.peernet.wifiextender.action.STOP_VPN"
        const val SESSION = "PeerNet"
        const val MTU = 1280
        const val VPN_ADDRESS = "10.215.17.2"
        const val VIRTUAL_DNS = "10.215.17.1"
        private const val NOTIFICATION_ID = 1002
    }
}
