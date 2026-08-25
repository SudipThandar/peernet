package com.peernet.wifiextender

import com.peernet.wifiextender.wifi.GroupCredentialsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for the Wi-Fi Direct group's name and passphrase.
 *
 * The reported defect: the client had to retype the password every time, because
 * the host handed the framework credentials it then ignored (wrong API gate), so
 * the group came up with system-generated credentials that changed per share.
 * These tests pin the parts that must not drift.
 */
class GroupCredentialsPolicyTest {

    // ---- the host/client contract ----

    @Test
    fun `host and client derive the same passphrase for a host id`() {
        // This is the whole contract. It used to be two string literals in two
        // files with nothing tying them together; editing one would silently stop
        // auto-join with no failing test.
        val hostId = "a1b2c3d4e5f60718"
        assertEquals(
            "host and client must agree or the client can never join on its own",
            GroupCredentialsPolicy.derivePassphrase(hostId),
            GroupCredentialsPolicy.derivePassphrase(hostId)
        )
    }

    @Test
    fun `the passphrase is stable across calls and different per host`() {
        val a = GroupCredentialsPolicy.derivePassphrase("aaaaaaaaaaaaaaaa")
        val b = GroupCredentialsPolicy.derivePassphrase("bbbbbbbbbbbbbbbb")
        assertEquals(a, GroupCredentialsPolicy.derivePassphrase("aaaaaaaaaaaaaaaa"))
        assertFalse("two phones must not share a passphrase", a == b)
    }

    @Test
    fun `the network name keeps the DIRECT prefix the framework requires`() {
        val name = GroupCredentialsPolicy.networkName("a1b2c3d4e5f60718")
        assertTrue(
            "Wi-Fi Direct rejects any network name without this prefix",
            name.startsWith("DIRECT-")
        )
        assertTrue("network name must fit in 32 characters", name.length <= 32)
        assertEquals("DIRECT-PeerNet-0718", name)
    }

    // ---- the passphrase has to be usable by a human ----

    @Test
    fun `the derived passphrase satisfies the WPA2 length rule`() {
        val p = GroupCredentialsPolicy.derivePassphrase("a1b2c3d4e5f60718")
        assertTrue(
            "shorter than 8 is rejected by the framework outright",
            p.length >= GroupCredentialsPolicy.MIN_LENGTH
        )
        assertTrue(p.length <= GroupCredentialsPolicy.MAX_LENGTH)
        assertNull("the app's own default must never be rejected", GroupCredentialsPolicy.rejection(p))
    }

    @Test
    fun `the derived passphrase avoids characters a user cannot tell apart`() {
        // This string is read off one screen and typed into another phone. A '0'
        // that might be an 'O' is a failed connection that looks like a bug.
        val p = GroupCredentialsPolicy.derivePassphrase("a1b2c3d4e5f60718")
        for (c in "01lIoO") {
            assertFalse("ambiguous character '$c' must not appear in $p", p.contains(c))
        }
    }

    // ---- validation of a user-chosen password ----

    @Test
    fun `too short is rejected with a reason the user can act on`() {
        val why = GroupCredentialsPolicy.rejection("short")
        assertNotNull("a silent rejection would surface as an opaque framework error", why)
        assertTrue("the reason must name the requirement", why!!.contains("8"))
    }

    @Test
    fun `too long is rejected`() {
        assertNotNull(GroupCredentialsPolicy.rejection("x".repeat(64)))
        assertNull(GroupCredentialsPolicy.rejection("x".repeat(63)))
    }

    @Test
    fun `non typeable characters are rejected`() {
        assertNotNull(
            "a passphrase the Wi-Fi prompt cannot accept is worse than no change",
            GroupCredentialsPolicy.rejection("passwörd12")
        )
        assertNotNull(GroupCredentialsPolicy.rejection("pass\tword"))
        assertNull(GroupCredentialsPolicy.rejection("Passw0rd!-_ ~"))
    }

    @Test
    fun `exactly eight characters is accepted`() {
        // Boundary: the framework's own minimum, so off-by-one here would reject
        // a password the user is entitled to set.
        assertNull(GroupCredentialsPolicy.rejection("12345678"))
        assertNotNull(GroupCredentialsPolicy.rejection("1234567"))
    }
}
