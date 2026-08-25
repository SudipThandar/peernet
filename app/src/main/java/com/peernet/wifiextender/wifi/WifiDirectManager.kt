package com.peernet.wifiextender.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.peernet.wifiextender.diag.Diagnostics
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

/**
 * What a P2P group report means for this device.
 *
 * Kept as a pure function because getting it wrong is what broke client
 * auto-connect: `requestGroupInfo` returns a group for *both* roles, and the
 * old code set `hosting = true` for any non-null group. A phone that had just
 * joined a host therefore reported `hosting = true` **and**
 * `joinedAsClient = true` at the same time, and every client auto-link path is
 * gated on NOT hosting — so joining the group was exactly what disabled
 * auto-connect.
 */
internal enum class GroupRole {
    /** We own the group and the user asked for it: real hosting. */
    OWNER,

    /** We joined someone else's group: a client, never a host. */
    CLIENT,

    /** We own a group nobody asked for — left over after STOP SHARE. */
    STALE_OWNER
}

internal fun classifyGroup(isGroupOwner: Boolean, hostingRequested: Boolean): GroupRole = when {
    !isGroupOwner -> GroupRole.CLIENT
    hostingRequested -> GroupRole.OWNER
    else -> GroupRole.STALE_OWNER
}

@Singleton
class WifiDirectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var pendingCreate = false
    private var requestedSsid: String? = null
    private var requestedPassphrase: String? = null

    /**
     * Whether this share should still try for a persistent group.
     *
     * Cleared by the first `createGroup` failure so the retry drops persistence
     * before it drops the stable passphrase, and re-armed by every [startHosting]
     * so one stubborn attempt does not permanently downgrade later shares.
     */
    private var requestedPersistent = true
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Whether the user currently wants this phone to host. This is the *intent*,
     * as opposed to what the framework happens to report: without it a late
     * `WIFI_P2P_CONNECTION_CHANGED` broadcast arriving after STOP SHARE (group
     * removal is asynchronous) put `hosting = true` back, which restarted the
     * link responder and the mDNS advertisement behind the user's back.
     */
    @Volatile
    private var hostingRequested = false

    /**
     * The user's hosting intent, readable by the host service.
     *
     * While this is true a momentary "no group" reading must never be treated as
     * the end of a session: `refreshGroupInfo()` publishes `hosting=false,
     * creating=false` whenever `requestGroupInfo` transiently returns null, which
     * happens right after a successful `createGroup` before the framework has
     * registered the group.
     */
    val hostingIntended: Boolean
        get() = hostingRequested

    /** Bounded re-removal attempts for a group that outlives STOP SHARE. */
    private var staleRemovals = 0

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
        val mac = deviceAddress.trim().uppercase()
        if (!MAC_REGEX.matches(mac)) {
            _state.update { it.copy(error = "Invalid device address, cannot join.") }
            return
        }
        _state.update { it.copy(error = null) }
        try {
            val config = WifiP2pConfig.Builder()
                .setDeviceAddress(android.net.MacAddress.fromString(mac))
                .build()
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    _state.update { it.copy(error = "Join failed: ${reasonText(reason)}") }
                }
            })
        } catch (e: SecurityException) {
            reportP2pDenied("connect", e)
        } catch (t: Throwable) {
            Timber.w(t, "connectToPeer threw")
            _state.update { it.copy(error = "Join failed unexpectedly. Please retry.") }
        }
    }

    /**
     * Renames this phone's Wi-Fi Direct identity (e.g. "PeerNet-4A3F").
     * Uses a hidden platform API via reflection; silently no-ops where blocked.
     */
    fun setDeviceName(name: String) {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            val method = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(
                mgr, ch, name,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Timber.i("Wi-Fi Direct device name set to %s", name)
                    }

                    override fun onFailure(reason: Int) {
                        Timber.w("setDeviceName failed: %d", reason)
                    }
                }
            )
        } catch (t: Throwable) {
            Timber.w(t, "setDeviceName unavailable on this build")
        }
    }

    /**
     * Requests joining a group by exact credentials (API 33+). This makes the
     * OS associate with the group owner's network — the connection then shows
     * up in Wi-Fi settings like any manually joined network.
     *
     * Returns true when the request was submitted; association completes
     * asynchronously and is observable through [state] (joinedAsClient).
     */
    fun joinByCredentials(ssid: String, passphrase: String): Boolean {
        val mgr = manager ?: return false
        val ch = channel ?: return false
        // Q, not TIRAMISU: WifiP2pConfig.Builder and both setters are API 29.
        // The wrong gate meant every API 29-32 client fell back to typing the
        // password into Wi-Fi settings by hand, on a phone that was perfectly
        // capable of joining on its own.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Diagnostics.note(
                "wifidirect",
                "P2P_JOIN_UNSUPPORTED sdk=${Build.VERSION.SDK_INT} — join the network from Wi-Fi settings"
            )
            return false
        }
        return try {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                // Lets the framework store the credentials, so later joins to the
                // same host need neither this call nor the user.
                .enablePersistentMode(true)
                .build()
            Diagnostics.note("wifidirect", "P2P_JOIN_REQUESTED ssid=$ssid")
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Join requested for %s", ssid)
                }

                override fun onFailure(reason: Int) {
                    Timber.w("Join %s failed: %d", ssid, reason)
                    Diagnostics.note(
                        "wifidirect",
                        "P2P_JOIN_FAILED ssid=$ssid reason=${reasonText(reason)} — " +
                            "if the host set its own password, join from Wi-Fi settings instead"
                    )
                }
            })
            true
        } catch (e: SecurityException) {
            reportP2pDenied("connect(byCredentials)", e)
            false
        } catch (t: Throwable) {
            Timber.w(t, "joinByCredentials threw")
            false
        }
    }

    /**
     * Surfaces a precondition failure (e.g. location services off) through the
     * same state the host card already renders, so callers never fail silently.
     */
    fun reportError(message: String) {
        _state.update { it.copy(creating = false, hosting = false, error = message) }
    }

    /**
     * Reports a Wi-Fi Direct call rejected for a missing NEARBY_WIFI_DEVICES /
     * location grant. Every guarded call catches SecurityException explicitly:
     * the grant can be revoked mid-session, and an uncaught throw there looks
     * to the user like the app simply doing nothing.
     *
     * Note this cannot be a helper that wraps a lambda — lint only credits a
     * catch clause in the same method as the guarded call.
     */
    private fun reportP2pDenied(what: String, e: SecurityException) {
        Timber.w(e, "%s rejected for missing Wi-Fi Direct permission", what)
        _state.update {
            it.copy(
                creating = false,
                error = "Wi-Fi Direct permission was denied. Allow \"Nearby devices\" for PeerNet in Settings."
            )
        }
    }

    /** Client-side leave: drops the Wi-Fi Direct connection to the host. */
    fun leaveCurrentGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            @Suppress("DEPRECATION")
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (e: SecurityException) {
            reportP2pDenied("removeGroup", e)
        }
    }

    /**
     * Creates this device as a Wi-Fi Direct Group Owner (Section 14.2):
     * remove stale group first, retry-once semantics handled by caller UI.
     *
     * When [ssid]/[passphrase] are provided and the platform supports it
     * (API 33+), the group is created with those exact credentials so the
     * network shows up as "DIRECT-PeerNet-xxxx" in Wi-Fi settings and stays
     * stable across shares — clients that joined once auto-rejoin. On older
     * builds the credentials are system-chosen; the reflection-based device
     * rename remains the only branding path there.
     */
    fun startHosting(ssid: String? = null, passphrase: String? = null) {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            _state.update { it.copy(error = "Wi-Fi Direct is not available on this device.") }
            return
        }
        Timber.i("Starting Wi-Fi Direct hosting (ssid=%s)", ssid ?: "<system>")
        Diagnostics.note("wifidirect", "WIFI_DIRECT_CREATE_REQUESTED ssid=${ssid ?: "<system>"}")
        hostingRequested = true
        staleRemovals = 0
        _state.update { it.copy(error = null, creating = true) }
        requestedSsid = ssid
        requestedPassphrase = passphrase
        // Re-armed per share: a previous failure must not silently downgrade
        // every future share on this phone.
        requestedPersistent = true
        pendingCreate = true

        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = createGroup(mgr, ch)
                override fun onFailure(reason: Int) = createGroup(mgr, ch)
            })
        } catch (e: SecurityException) {
            reportP2pDenied("removeGroup(before create)", e)
        }
    }

    fun stopHosting() {
        Timber.i("Stopping Wi-Fi Direct hosting")
        Diagnostics.note("wifidirect", "WIFI_DIRECT_STOP_REQUESTED")
        // Intent first: every asynchronous callback still in flight (a group
        // that is mid-creation, a late CONNECTION_CHANGED broadcast) checks this
        // and must not re-enter or re-create hosting after this point.
        hostingRequested = false
        pendingCreate = false
        staleRemovals = 0
        requestedSsid = null
        requestedPassphrase = null

        // Optimistic immediate reset: UI and services must react instantly even
        // if the platform removeGroup callback never arrives (OEM quirk guard).
        clearGroupState()

        requestRemoveGroup("stop")
    }

    /**
     * Asks the framework to tear the group down and then *verifies* it, instead
     * of trusting the callback. A silently-failed removal is what left the
     * DIRECT-… network in the client's Wi-Fi list after STOP SHARE: the host
     * believed it had stopped while the group was still up and advertising.
     */
    @SuppressLint("MissingPermission")
    private fun requestRemoveGroup(reason: String) {
        val mgr = manager ?: return
        val ch = channel ?: return
        Diagnostics.note("wifidirect", "WIFI_DIRECT_REMOVE_GROUP_REQUESTED ($reason)")
        try {
            @Suppress("DEPRECATION")
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Diagnostics.note("wifidirect", "WIFI_DIRECT_GROUP_REMOVED ($reason)")
                    // Confirm: on several OEM builds the call reports success
                    // while requestGroupInfo still returns the group.
                    refreshGroupInfo()
                }

                override fun onFailure(failureReason: Int) {
                    Diagnostics.note(
                        "wifidirect",
                        "WIFI_DIRECT_REMOVE_GROUP_FAILED ($reason: ${reasonText(failureReason)})"
                    )
                    refreshGroupInfo()
                }
            })
        } catch (e: SecurityException) {
            reportP2pDenied("removeGroup($reason)", e)
        }
    }

    private fun createGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        // STOP raced ahead of the removeGroup callback that leads here. Creating
        // now would produce a group the app does not consider itself to own —
        // visible to clients forever, removable by nobody.
        if (!hostingRequested) {
            Diagnostics.note("wifidirect", "WIFI_DIRECT_CREATE_ABORTED (stop requested first)")
            pendingCreate = false
            return
        }

        // Local copies: mutable properties cannot be smart-cast to non-null.
        val ssid = requestedSsid
        val passphrase = requestedPassphrase
        // Q, not TIRAMISU. WifiP2pConfig.Builder, setNetworkName, setPassphrase
        // and enablePersistentMode are all API 29. Gating them at 33 silently
        // dropped every API 29-32 phone onto system-generated credentials that
        // change on every share - which is precisely why a client had to retype
        // the password each time.
        val customConfig: WifiP2pConfig? = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !ssid.isNullOrEmpty() && !passphrase.isNullOrEmpty()
        ) {
            try {
                WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(passphrase)
                    // A temporary group discards its credentials when it ends, so
                    // the next share looks like a brand-new network to the client.
                    // Persistent mode is what lets a client rejoin silently.
                    .apply { if (requestedPersistent) enablePersistentMode(true) }
                    .build()
            } catch (t: Throwable) {
                Timber.w(t, "custom group config rejected; falling back to system credentials")
                null
            }
        } else {
            null
        }

        Diagnostics.note(
            "wifidirect",
            "WIFI_DIRECT_CREATE_ATTEMPT custom=${customConfig != null} " +
                "persistent=${customConfig != null && requestedPersistent} sdk=${Build.VERSION.SDK_INT}"
        )

        try {
            if (customConfig != null) {
                mgr.createGroup(ch, customConfig, groupListener(mgr, ch))
            } else {
                @Suppress("DEPRECATION")
                mgr.createGroup(ch, groupListener(mgr, ch))
            }
        } catch (e: SecurityException) {
            reportP2pDenied("createGroup", e)
        }
    }

    /**
     * Shared success/failure handling for both createGroup variants. A failed
     * custom-credential attempt falls back once to the legacy call so hosting
     * still works on builds that reject explicit group names.
     */
    private fun groupListener(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            Timber.i("Wi-Fi Direct group created")
            if (!hostingRequested) {
                // STOP arrived while the group was forming. Remove it at once:
                // otherwise it stays up with nobody tracking it, which is
                // exactly how a DIRECT-… network outlived STOP SHARE.
                pendingCreate = false
                Diagnostics.note(
                    "wifidirect",
                    "WIFI_DIRECT_GROUP_CREATED after stop — removing immediately"
                )
                clearGroupState()
                requestRemoveGroup("created-after-stop")
                return
            }
            Diagnostics.note("wifidirect", "WIFI_DIRECT_GROUP_CREATED")
            // Do NOT clear pendingCreate here. createGroup reports success before
            // requestGroupInfo will return the new group, so the refresh below
            // (and any broadcast that races it) gets a transient null. Clearing
            // the flag now let that null wipe the just-created group — the
            // WIFI_DIRECT_SESSION_CLEARED-immediately-after-GROUP_CREATED bug that
            // flipped the host UI to IDLE and churned the :4434 responder.
            // GroupLifecyclePolicy keeps the session until a real group is seen.
            _state.update { it.copy(creating = false, hosting = true, error = null) }
            refreshGroupInfo()
        }

        override fun onFailure(reason: Int) {
            if (!hostingRequested) {
                Diagnostics.note("wifidirect", "WIFI_DIRECT_CREATE_ABANDONED (stop requested)")
                pendingCreate = false
                clearGroupState()
                return
            }
            // Degrade one step at a time, most valuable capability last. Dropping
            // straight to system credentials on the first failure would throw away
            // the stable passphrase - the whole point of the custom config - just
            // because a build refused persistent mode.
            if (requestedPersistent && (requestedSsid != null || requestedPassphrase != null)) {
                Timber.w("createGroup(persistent) failed: %d — retrying without persistence", reason)
                Diagnostics.note(
                    "wifidirect",
                    "WIFI_DIRECT_CREATE_RETRY reason=${reasonText(reason)} dropping=persistent"
                )
                requestedPersistent = false
                pendingCreate = true
                createGroup(mgr, ch)
                return
            }
            if (requestedSsid != null || requestedPassphrase != null) {
                Timber.w("createGroup(custom) failed: %d — retrying with system credentials", reason)
                Diagnostics.note(
                    "wifidirect",
                    "WIFI_DIRECT_CREATE_RETRY reason=${reasonText(reason)} dropping=credentials — " +
                        "the password will change every share on this phone"
                )
                requestedSsid = null
                requestedPassphrase = null
                pendingCreate = true
                createGroup(mgr, ch)
                return
            }
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
    }

    @SuppressLint("MissingPermission")
    fun refreshGroupInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.requestGroupInfo(ch) { group ->
            val action = GroupLifecyclePolicy.onGroupReport(
                groupPresent = group != null,
                createInFlight = pendingCreate
            )
            pendingCreate = action.createStillInFlight
            if (group == null) {
                // No group. While a create is still in flight this is the
                // expected transient null between the success callback and the
                // group registering, so the live session is kept. Otherwise this
                // is the authoritative "session is over" signal, and it must also
                // clear the *client* fields — leaving joinedAsClient=true kept a
                // dead host linked forever.
                if (action.clearSession) {
                    clearGroupState()
                } else {
                    Diagnostics.note(
                        "wifidirect",
                        "WIFI_DIRECT_GROUP_PENDING (transient null during create — keeping session)"
                    )
                }
                return@requestGroupInfo
            }

            val isOwner = runCatching { group.isGroupOwner }.getOrDefault(hostingRequested)
            when (classifyGroup(isOwner, hostingRequested)) {
                GroupRole.STALE_OWNER -> {
                    // The user stopped sharing but the framework still has our
                    // group. Never re-enter hosting here; remove it again (and
                    // keep the network from lingering in the client's Wi-Fi
                    // list) with a bounded number of attempts.
                    clearGroupState()
                    if (staleRemovals < MAX_STALE_REMOVALS) {
                        staleRemovals++
                        Diagnostics.note(
                            "wifidirect",
                            "WIFI_DIRECT_GROUP_STILL_PRESENT ${group.networkName} " +
                                "after stop (removal try $staleRemovals)"
                        )
                        requestRemoveGroup("stale-group")
                    } else {
                        Diagnostics.note(
                            "wifidirect",
                            "WIFI_DIRECT_GROUP_STUCK ${group.networkName} — " +
                                "survived $MAX_STALE_REMOVALS removals"
                        )
                    }
                    return@requestGroupInfo
                }

                GroupRole.CLIENT -> {
                    // Member of someone else's group. Explicitly NOT hosting.
                    _state.update {
                        it.copy(
                            hosting = false,
                            creating = false,
                            ssid = null,
                            passphrase = null,
                            passphraseAvailable = true,
                            error = null
                        )
                    }
                }

                GroupRole.OWNER -> {
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
                }
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

    /**
     * Resets everything derived from a live group — host *and* client fields.
     *
     * The client fields used to survive here, so after the host tore the group
     * down the client still reported `joinedAsClient = true`: it never saw the
     * falling edge, never cleared `connectedHost`, and kept probing a host that
     * no longer existed.
     */
    private fun clearGroupState() {
        val had = _state.value
        _state.update {
            it.copy(
                creating = false,
                hosting = false,
                ssid = null,
                passphrase = null,
                passphraseAvailable = true,
                groupOwnerAddress = null,
                joinedAsClient = false,
                joinedGroupOwnerAddress = null
            )
        }
        if (had.hosting || had.joinedAsClient) {
            Diagnostics.note(
                "wifidirect",
                "WIFI_DIRECT_SESSION_CLEARED (was hosting=${had.hosting} joined=${had.joinedAsClient})"
            )
        }
    }

    /**
     * Multicast reception guard. Without it, Wi-Fi power save silently drops
     * mDNS frames on P2P groups and discovery "randomly" finds nothing.
     * Non-refcounted: acquire/release are idempotent across both roles
     * (host advertises, client discovers) which never run simultaneously.
     *
     * Requires CHANGE_WIFI_MULTICAST_STATE. A missing grant must never break
     * hosting or linking — mDNS is an accelerator, and the gateway probe finds
     * the host without it — so the failure is recorded and swallowed.
     */
    fun acquireMulticast(): Boolean {
        multicastLock?.let { return it.isHeld }
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return try {
            val lock = wm.createMulticastLock("peernet-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
            true
        } catch (e: SecurityException) {
            // Explicit catch in the same method as the guarded call: lint only
            // credits it here, and the grant can be absent on OEM builds.
            Timber.w(e, "multicast lock denied")
            Diagnostics.note(
                "wifidirect",
                "MULTICAST_LOCK_DENIED (${e.javaClass.simpleName}) — mDNS may be slower, " +
                    "gateway probing still works"
            )
            false
        } catch (t: Throwable) {
            Timber.w(t, "multicast lock unavailable")
            Diagnostics.note("wifidirect", "MULTICAST_LOCK_UNAVAILABLE (${t.javaClass.simpleName})")
            false
        }
    }

    fun releaseMulticast() {
        val lock = multicastLock ?: return
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            Timber.w(t, "multicast lock release failed")
        }
        // Drop the reference: a lock kept here after a failed release made every
        // later acquire() return a stale isHeld instead of retrying.
        multicastLock = null
    }

    private fun reasonText(reason: Int) = when (reason) {
        WifiP2pManager.BUSY -> "system busy"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported"
        else -> "unknown ($reason)"
    }

    companion object {
        private const val TAG = "WifiDirectManager"
        private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        /**
         * How many times a group that survives STOP SHARE is re-removed before
         * giving up and saying so. Bounded because each attempt is driven by a
         * `requestGroupInfo` callback, and an unbounded retry would spin
         * forever against a group the platform refuses to drop (for example a
         * network the *user* added by hand from Wi-Fi settings, which no app is
         * allowed to remove).
         */
        private const val MAX_STALE_REMOVALS = 3
    }
}
