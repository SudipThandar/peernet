package com.peernet.wifiextender.host

import android.content.Context
import com.peernet.wifiextender.discovery.HostIdentity
import com.peernet.wifiextender.discovery.NsdHostAdvertiser
import com.peernet.wifiextender.util.Permissions
import com.peernet.wifiextender.wifi.LinkServer
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-lifetime host runtime: owns the mDNS advertisement and the PNTP-port
 * link responder for as long as a Wi-Fi Direct group exists — independent of
 * which screen is visible (foreground-service ownership arrives in M10).
 */
@Singleton
class HostRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirect: WifiDirectManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val advertiser = NsdHostAdvertiser(context)
    private val linkServer = LinkServer()
    private val hostId = HostIdentity.id(context)

    init {
        scope.launch {
            wifiDirect.state.collect { s ->
                if (s.hosting && s.ssid != null) {
                    advertiser.register(displayName = "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    linkServer.start(hostId)
                } else if (!s.hosting && !s.creating) {
                    linkServer.stop()
                    advertiser.unregister()
                }
            }
        }
    }

    fun canStart(): Boolean = Permissions.missing(context).isEmpty()

    fun startSharing() {
        // Brand the Wi-Fi Direct identity so clients see "PeerNet-xxxx",
        // not the owner's personal device name.
        wifiDirect.setDeviceName("PeerNet-${HostIdentity.shortId(context)}")
        wifiDirect.startHosting()
    }

    fun stopSharing() {
        linkServer.stop()
        advertiser.unregister()
        wifiDirect.stopHosting()
    }
}
