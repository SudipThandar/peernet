package com.peernet.wifiextender

import com.peernet.wifiextender.host.RoleConflictPolicy
import com.peernet.wifiextender.host.ShareAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tapping SHARE while receiving must not silently kill the client session.
 *
 * A phone gets one P2P group from `WifiP2pManager`, and these chipsets have no P2P
 * concurrency, so hosting destroys the group joined as a client. `startSharing`
 * had no guard, so Android tore that group down with no warning - which is the
 * reported "tapping SHARE silently disconnects me from the host" behaviour.
 */
class RoleConflictPolicyTest {

    @Test
    fun `sharing starts immediately when this phone is not receiving`() {
        assertEquals(
            ShareAction.PROCEED,
            RoleConflictPolicy.evaluateShareRequest(
                clientLinkActive = false,
                tunnelActive = false
            )
        )
    }

    @Test
    fun `sharing asks first while a client link is held`() {
        assertEquals(
            ShareAction.CONFIRM_REPLACING_CLIENT_LINK,
            RoleConflictPolicy.evaluateShareRequest(
                clientLinkActive = true,
                tunnelActive = true
            )
        )
    }

    @Test
    fun `a live tunnel counts even after the link is cleared`() {
        // The link is nulled before the VPN service finishes tearing down. Sharing
        // in that window still yanks a tunnel the user is using.
        assertEquals(
            ShareAction.CONFIRM_REPLACING_CLIENT_LINK,
            RoleConflictPolicy.evaluateShareRequest(
                clientLinkActive = false,
                tunnelActive = true
            )
        )
    }

    @Test
    fun `a held link counts even before the tunnel comes up`() {
        // Mid-establishment: the group is joined, the tunnel is not up yet.
        // Hosting now destroys the group the client just joined.
        assertEquals(
            ShareAction.CONFIRM_REPLACING_CLIENT_LINK,
            RoleConflictPolicy.evaluateShareRequest(
                clientLinkActive = true,
                tunnelActive = false
            )
        )
    }

    @Test
    fun `only a fully idle phone shares without asking`() {
        val proceeding = mutableListOf<Pair<Boolean, Boolean>>()
        for (link in listOf(true, false)) {
            for (tunnel in listOf(true, false)) {
                if (RoleConflictPolicy.evaluateShareRequest(link, tunnel) == ShareAction.PROCEED) {
                    proceeding += link to tunnel
                }
            }
        }
        assertEquals(listOf(false to false), proceeding)
    }
}
