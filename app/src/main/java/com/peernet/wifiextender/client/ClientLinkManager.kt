package com.peernet.wifiextender.client

import com.peernet.wifiextender.discovery.DiscoveredHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide record of the client's current host link, so any screen
 * (Home, Client) can react to it.
 */
@Singleton
class ClientLinkManager @Inject constructor() {

    private val _linkedHost = MutableStateFlow<DiscoveredHost?>(null)
    val linkedHost: StateFlow<DiscoveredHost?> = _linkedHost.asStateFlow()

    fun setLinked(host: DiscoveredHost?) {
        _linkedHost.value = host
    }
}
