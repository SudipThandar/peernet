package com.peernet.wifiextender.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wi-Fi Direct wrapper (spec Section 14).
 *
 * Handles group creation/removal, group info extraction with graceful
 * PASSPHRASE_UNAVAILABLE fallback, and P2P state broadcasts with recovery.
 */
data class WifiDirectState(
    val p2pSupported: Boolean = true,
    val p2pEnabled: Boolean = false,
    val creating: Boolean = false,
    val hosting: Boolean = false,
    val ssid: String? = null,
    val passphrase: String? = null,
    val passphraseAvailable: Boolean = true,
    val groupOwnerAddress: String? = null,
    val error: String? = null,
    // Client side
    val peers: List<WifiP2pDevice> = emptyList(),
    val joinedAsClient: Boolean = false,
    val joinedGroupOwnerAddress: String? = null
)

@Singleton
class WifiDirectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var pendingCreate = false

    private val _state = MutableStateFlow(WifiDirectState(p2pSupported = manager != null))
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _state.update {
                        it.copy(
                            p2pEnabled = enabled,
                            error = if (!enabled && it.creating) "Wi-Fi is turned off. Turn on Wi-Fi and try again." else it.error
                        )
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshGroupInfo()
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> refreshPeers()
            }
        }
    }

    /** Idempotent. Safe to call from every screen entry. */
    fun initialize() {
        val mgr = manager ?: return
        if (channel == null) {
            channel = mgr.initialize(context, Looper.getMainLooper(), null)
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
    }

    // ---------- Client side: peer discovery & join ----------

    @SuppressLint("MissingPermission")
    fun startPeerDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        _state.update { it.copy(error = null) }
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                _state.update {
                    it.copy(error = "Could not search nearby: ${reasonText(reason)}. Location services must be ON.")
                }
            }
        })
    }

    fun stopPeerDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching { mgr.stopPeerDiscovery(ch, null) }
    }

    @SuppressLint("MissingPermission")
    private fun refreshPeers() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.requestPeers(ch) { peers ->
            _state.update { it.copy(peers = peers.deviceList.toList()) }
        }
    }

    fun connectToPeer(deviceAddress: String) {
        val mgr = manager ?: return
        val ch = channel ?: return
        _state.update { it.copy(error = null) }
        @Suppress("DEPRECATION")
        val config = WifiP2pConfig().apply { deviceAddress = deviceAddress }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                _state.update { it.copy(error = "Join failed: ${reasonText(reason)}") }
            }
        })
    }

    /**
     * Creates this device as a Wi-Fi Direct Group Owner (Section 14.2):
     * remove stale group first, retry-once semantics handled by caller UI.
     */
    fun startHosting() {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            _state.update { it.copy(error = "Wi-Fi Direct is not available on this device.") }
            return
        }
        Timber.i("Starting Wi-Fi Direct hosting")
        _state.update { it.copy(error = null, creating = true) }
        pendingCreate = true

        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = createGroup(mgr, ch)
            override fun onFailure(reason: Int) = createGroup(mgr, ch)
        })
    }

    fun stopHosting() {
        val mgr = manager ?: return
        val ch = channel ?: return
        Timber.i("Stopping Wi-Fi Direct hosting")
        pendingCreate = false
        @Suppress("DEPRECATION")
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = clearGroupState()
            override fun onFailure(reason: Int) = clearGroupState()
        })
    }

    private fun createGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        @Suppress("DEPRECATION")
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.i("Wi-Fi Direct group created")
                pendingCreate = false
                _state.update { it.copy(creating = false, hosting = true, error = null) }
                refreshGroupInfo()
            }

            override fun onFailure(reason: Int) {
                Timber.w("createGroup failed: %d", reason)
                pendingCreate = false
                _state.update {
                    it.copy(
                        creating = false,
                        hosting = false,
                        error = "Could not create the local network: ${reasonText(reason)}. Retrying may help."
                    )
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun refreshGroupInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.requestGroupInfo(ch) { group ->
            if (group == null) {
                if (_state.value.hosting && !pendingCreate) clearGroupState()
                return@requestGroupInfo
            }
            val passphrase = readPassphrase(group)
            _state.update {
                it.copy(
                    hosting = true,
                    creating = false,
                    ssid = group.networkName,
                    passphrase = passphrase,
                    passphraseAvailable = !passphrase.isNullOrEmpty(),
                    error = null
                )
            }
            mgr.requestConnectionInfo(ch) { info ->
                val formed = info?.groupFormed == true
                val owner = info?.groupOwnerAddress?.hostAddress
                val isGo = info?.isGroupOwner == true
                _state.update {
                    it.copy(
                        groupOwnerAddress = if (formed && isGo) owner else it.groupOwnerAddress,
                        joinedAsClient = formed && !isGo,
                        joinedGroupOwnerAddress = if (formed && !isGo) owner else null
                    )
                }
            }
        }
    }

    /**
     * Constraint 7: never crash when the passphrase is unavailable
     * (hidden before Android 13 on some OEM builds).
     */
    private fun readPassphrase(group: WifiP2pGroup): String? = try {
        group.passphrase
    } catch (t: Throwable) {
        try {
            val method = WifiP2pGroup::class.java.getMethod("getPassphrase")
            method.invoke(group) as? String
        } catch (t2: Throwable) {
            Timber.w(t2, "Passphrase unavailable")
            null
        }
    }

    private fun clearGroupState() {
        _state.update {
            it.copy(
                creating = false,
                hosting = false,
                ssid = null,
                passphrase = null,
                passphraseAvailable = true,
                groupOwnerAddress = null
            )
        }
    }

    private fun reasonText(reason: Int) = when (reason) {
        WifiP2pManager.BUSY -> "system busy"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported"
        else -> "unknown ($reason)"
    }

    companion object {
        private const val TAG = "WifiDirectManager"
    }
}
