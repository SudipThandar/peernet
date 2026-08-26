package com.peernet.wifiextender.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.peernet.wifiextender.client.ClientLinkManager
import com.peernet.wifiextender.diag.Diagnostics
import com.peernet.wifiextender.wifi.WifiDirectManager
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Handles the tunnel notification's "Stop" action on the client.
 *
 * A manifest-declared receiver for the same reason as
 * [HostNotificationActionReceiver]: delivery does not depend on process state or
 * on OEM handling of service intents, and Hilt can inject even after a restart.
 *
 * The work is split deliberately. [ClientLinkManager.requestStop] lets
 * `ClientViewModel` run its own disconnect so the visible state stays owned by
 * one place; the remaining calls are the guarantee that a stop still happens when
 * no ViewModel is alive to hear it - which, with the screen off, is the normal
 * case rather than the exception.
 */
@AndroidEntryPoint
class VpnNotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var linkManager: ClientLinkManager
    @Inject lateinit var wifiDirect: WifiDirectManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PeerNetVpnService.ACTION_STOP) return
        Timber.i("Tunnel notification Stop received")

        // A notification left over from a previous session must not stop the
        // current one. The service stamps the link generation it built the
        // action for; if that generation has moved on, the session this Stop
        // referred to is already gone and there is nothing to do.
        //
        // A missing stamp (-1) means the action predates this check, so it is
        // honoured: failing to stop is worse than stopping something already
        // dead, because the tunnel is a default route.
        val stampedGen = intent.getIntExtra(PeerNetVpnService.EXTRA_LINK_GEN, -1)
        if (stampedGen >= 0 && !linkManager.isCurrent(stampedGen)) {
            Timber.i("ignoring stale Stop for generation %d", stampedGen)
            Diagnostics.note(
                "vpn",
                "STOP_ACTION_IGNORED_STALE gen=$stampedGen now=${linkManager.generation}"
            )
            return
        }

        // Ask the UI to disconnect properly if it exists.
        runCatching { linkManager.requestStop() }
            .onFailure { Timber.w(it, "stop request failed") }

        // Independent of any UI: invalidate the session so the service tears the
        // TUN down, and leave the group so auto-connect has nothing to re-link to.
        runCatching { linkManager.setLinked(null) }
            .onFailure { Timber.w(it, "clearing the link failed") }
        runCatching { wifiDirect.leaveCurrentGroup() }
            .onFailure { Timber.w(it, "leaving the group failed") }

        runCatching {
            context.startService(
                Intent(context, PeerNetVpnService::class.java)
                    .setAction(PeerNetVpnService.ACTION_STOP)
            )
        }.onFailure { Timber.w(it, "service stop poke failed") }
    }
}
