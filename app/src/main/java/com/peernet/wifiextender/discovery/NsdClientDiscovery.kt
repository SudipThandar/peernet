package com.peernet.wifiextender.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class DiscoveredHost(
    val name: String,
    val port: Int,
    val address: String?,
    val hostId: String?
)

/**
 * Client-side mDNS/Bonjour discovery (spec Sections 15.2/15.3).
 */
class NsdClientDiscovery(context: Context) {

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun discoverOnce(
        serviceType: String = SERVICE_TYPE,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS
    ): List<DiscoveredHost> = suspendCancellableCoroutine { cont ->
        val found = LinkedHashMap<String, DiscoveredHost>()
        val finished = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())

        var discoveryListener: NsdManager.DiscoveryListener? = null

        fun finish() {
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacksAndMessages(null)
                discoveryListener?.let {
                    runCatching { nsdManager.stopServiceDiscovery(it) }
                }
                if (cont.isActive) cont.resume(found.values.toList())
            }
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                Timber.w("NSD resolve failed: %d", errorCode)
            }

            @Suppress("DEPRECATION")
            override fun onServiceResolved(info: NsdServiceInfo?) {
                info ?: return
                Timber.d("NSD resolved host: %s", info.serviceName)
                @Suppress("DEPRECATION")
                val attrs = info.attributes
                val hid = attrs["hid"]?.toString(Charsets.UTF_8)
                found[info.serviceName] = DiscoveredHost(
                    name = info.serviceName,
                    port = info.port,
                    address = info.host?.hostAddress,
                    hostId = hid
                )
                if (found.size >= MAX_HOSTS) finish()
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo ?: return
                runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { found.remove(it.serviceName) }
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Timber.w("NSD start failed: %d", errorCode)
                finish()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        }

        handler.postDelayed({ finish() }, timeoutMs)
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (t: Throwable) {
            Timber.w(t, "discoverServices threw")
            finish()
        }

        cont.invokeOnCancellation {
            finished.set(true)
            handler.removeCallbacksAndMessages(null)
        }
    }

    companion object {
        const val SERVICE_TYPE = "_peernet._udp."
        const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val MAX_HOSTS = 32
    }
}
