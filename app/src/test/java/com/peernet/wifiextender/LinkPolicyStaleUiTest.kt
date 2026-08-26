package com.peernet.wifiextender

import com.peernet.wifiextender.client.LinkPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client screen must not outlive the link it is describing.
 *
 * Reported symptom: the host taps STOP, the client's Wi-Fi disconnects, and the
 * app still says "Connected to PeerNet-4f93".
 *
 * `ClientLinkManager.linkedHost` is the authority, and `setLinked(null)` is called
 * from three places outside the view model - `PeerNetVpnService` twice and the
 * notification's Stop action. None of them touched the view model's own
 * `connectedHost`, and the view model never observed the manager, so the screen
 * had no way to find out the session had ended.
 *
 * The watcher intended to catch this compared `joinedAsClient` falling edges. That
 * flag is documented in `ClientViewModel` as staying false forever when the user
 * joins by typing the passphrase in Android's Wi-Fi settings - this app's normal
 * client flow - so its falling edge never happened and the clearing code was
 * unreachable in practice.
 */
class LinkPolicyStaleUiTest {

    @Test
    fun `the screen is cleared when the manager drops a link it was showing`() {
        // The reported bug: host tapped STOP, something nulled the manager's link,
        // the screen still showed a host.
        assertTrue(
            LinkPolicy.shouldClearStaleUi(
                hadManagerLink = true,
                hasManagerLink = false,
                uiShowsLink = true
            )
        )
    }

    @Test
    fun `a link being established is never cleared`() {
        // linkedHost is null before a link exists too. A level check ("manager has
        // no link") would clear the screen mid-connection and fight the view model
        // while it sets the link up, which is why this is an edge.
        assertFalse(
            LinkPolicy.shouldClearStaleUi(
                hadManagerLink = false,
                hasManagerLink = false,
                uiShowsLink = true
            )
        )
    }

    @Test
    fun `a healthy link is left alone`() {
        assertFalse(
            LinkPolicy.shouldClearStaleUi(
                hadManagerLink = true,
                hasManagerLink = true,
                uiShowsLink = true
            )
        )
    }

    @Test
    fun `clearing is not repeated once the screen is already empty`() {
        // clearLink nulls the manager itself, so the watcher sees that same edge.
        // Without this the local clear would run twice per teardown.
        assertFalse(
            LinkPolicy.shouldClearStaleUi(
                hadManagerLink = true,
                hasManagerLink = false,
                uiShowsLink = false
            )
        )
    }

    @Test
    fun `a reconnect that replaces the link does not clear the screen`() {
        // setLinked(newHost) moves linked -> linked. Treating any change as an end
        // of session would tear down the session that just replaced it.
        assertFalse(
            LinkPolicy.shouldClearStaleUi(
                hadManagerLink = true,
                hasManagerLink = true,
                uiShowsLink = false
            )
        )
    }

    @Test
    fun `only the linked to unlinked transition clears, across every combination`() {
        val clearing = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        for (had in listOf(true, false)) {
            for (has in listOf(true, false)) {
                for (ui in listOf(true, false)) {
                    if (LinkPolicy.shouldClearStaleUi(had, has, ui)) {
                        clearing += Triple(had, has, ui)
                    }
                }
            }
        }
        // Exactly one of the eight combinations may clear the screen.
        assertTrue(
            "unexpected clearing combinations: $clearing",
            clearing == listOf(Triple(true, false, true))
        )
    }
}
