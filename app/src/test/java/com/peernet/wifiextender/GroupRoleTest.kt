package com.peernet.wifiextender

import com.peernet.wifiextender.wifi.GroupRole
import com.peernet.wifiextender.wifi.classifyGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Role classification for a live Wi-Fi Direct group (build #105 post-mortem).
 *
 * `WifiP2pManager.requestGroupInfo` returns a group to **both** members, so a
 * non-null group says nothing about which side we are. The old code set
 * `hosting = true` whenever a group existed, so a phone that joined a host
 * reported `hosting = true` *and* `joinedAsClient = true`. Every client
 * auto-link path is gated on NOT hosting, so joining the group was precisely
 * what disabled auto-connect — and the client also started its own link
 * responder and mDNS advertisement.
 *
 * Pure function, so the decision is testable without a device or Robolectric.
 */
class GroupRoleTest {

    @Test
    fun `group we own while sharing means hosting`() {
        assertEquals(
            GroupRole.OWNER,
            classifyGroup(isGroupOwner = true, hostingRequested = true)
        )
    }

    @Test
    fun `group we did not create means client even while sharing was requested`() {
        // Both flags true is the ambiguous case that caused the bug: the group
        // owner flag is authoritative, intent is not.
        assertEquals(
            GroupRole.CLIENT,
            classifyGroup(isGroupOwner = false, hostingRequested = true)
        )
    }

    @Test
    fun `joining a host is never classified as hosting`() {
        assertEquals(
            GroupRole.CLIENT,
            classifyGroup(isGroupOwner = false, hostingRequested = false)
        )
    }

    @Test
    fun `owning a group nobody asked for is stale, not hosting`() {
        // Reached when a late CONNECTION_CHANGED broadcast arrives after STOP
        // SHARE. Treating it as hosting resurrected the share and left the
        // DIRECT network alive on the client.
        assertEquals(
            GroupRole.STALE_OWNER,
            classifyGroup(isGroupOwner = true, hostingRequested = false)
        )
    }

    @Test
    fun `only a requested owned group is hosting`() {
        val hostingCases = listOf(true to true, true to false, false to true, false to false)
            .filter { (owner, requested) ->
                classifyGroup(owner, requested) == GroupRole.OWNER
            }
        assertEquals(listOf(true to true), hostingCases)
    }
}
