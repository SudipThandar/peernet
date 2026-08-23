package com.peernet.wifiextender.client

import android.net.Network
import com.peernet.wifiextender.discovery.DiscoveredHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide record of the client's current host link, so any screen
 * (Home, Client) can react to it. Also remembers the network the link
 * rides on so the VPN pins its QUIC sockets to it (the P2P Wi-Fi is
 * usually marked "no internet", making the default route cellular).
 */
@Singleton
class ClientLinkManager @Inject constructor() {

    private val _linkedHost = MutableStateFlow<DiscoveredHost?>(null)
    val linkedHost: StateFlow<DiscoveredHost?> = _linkedHost.asStateFlow()

    private val _linkedNetwork = MutableStateFlow<Network?>(null)
    val linkedNetwork: StateFlow<Network?> = _linkedNetwork.asStateFlow()

    fun setLinked(host: DiscoveredHost?, network: Network? = null) {
        _linkedHost.value = host
        _linkedNetwork.value = if (host != null) network else null
    }
}
