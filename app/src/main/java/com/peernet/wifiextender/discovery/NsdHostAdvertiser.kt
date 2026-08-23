package com.peernet.wifiextender.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-side mDNS/Bonjour advertisement (spec Sections 7.1 FR-HOST-005, 15.1).
 * Advertises `_peernet._udp` with PNTP TXT metadata so nearby clients can
 * discover this host while it is sharing.
 *
 * Ports: the advertised [port] is the link-probe port (4434, banner
 * responder). The QUIC tunnel endpoint owns 4433 and is advertised via the
 * "tp" TXT attribute so M7+ clients know where to connect.
 */
class NsdHostAdvertiser(context: Context) {

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val appContext = context.applicationContext
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val registered = AtomicBoolean(false)

    private fun hostId(): String = HostIdentity.id(appContext)

    fun register(
        displayName: String,
        port: Int = LINK_PORT,
        fingerprint: String = "",
        mode: String = "host"
    ) {
        if (!registered.compareAndSet(false, true)) return

        val hid = hostId()
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "PeerNet-${hid.takeLast(4)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "1")
            setAttribute("hid", hid)
            setAttribute("name", displayName)
            setAttribute("port", port.toString())
            setAttribute("tp", PNTP_PORT.toString())
            if (fingerprint.isNotEmpty()) setAttribute("fp", fingerprint)
            setAttribute("mode", mode)
            setAttribute("cap", "quic,udp,rtc")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Timber.i("NSD registered as %s", serviceInfo?.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Timber.w("NSD registration failed: %d", errorCode)
                registered.set(false)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        }

        registrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Timber.w(t, "registerService threw")
            registered.set(false)
        }
    }

    fun unregister() {
        val listener = registrationListener ?: return
        try {
            nsdManager.unregisterService(listener)
        } catch (t: Throwable) {
            Timber.w(t, "unregisterService threw")
        }
        registrationListener = null
        registered.set(false)
    }

    companion object {
        const val SERVICE_TYPE = "_peernet._udp."
        const val PNTP_PORT = 4433
        const val LINK_PORT = 4434
    }
}
