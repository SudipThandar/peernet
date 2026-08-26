package com.peernet.wifiextender

import com.peernet.wifiextender.power.WifiLockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates the Wi-Fi lock mode decision (audit finding RC-2).
 *
 * The regression these tests exist to prevent: both ends of the tunnel used to
 * acquire `WIFI_MODE_FULL_LOW_LATENCY`, which the platform only honours while
 * the screen is on and the app is foreground. Every screen-off failure report
 * contained `WIFI_LOCK_ACQUIRED` immediately before the network was lost,
 * because the lock was inert precisely when it was needed.
 *
 * These are cheap assertions on a pure function, but they are the only
 * automated defence available: this project has no Robolectric, so no test can
 * observe a real `WifiManager`.
 */
class WifiLockPolicyTest {

    @Test
    fun `chosen mode survives screen off`() {
        // The single property the tunnel depends on. If this fails, the tunnel
        // will stall when the screen turns off, on every device.
        assertTrue(
            "lock mode must remain effective with the screen off",
            WifiLockPolicy.isEffectiveWhileScreenOff(WifiLockPolicy.lockMode())
        )
    }

    @Test
    fun `chosen mode is high perf, not low latency`() {
        assertEquals(WifiLockPolicy.MODE_FULL_HIGH_PERF, WifiLockPolicy.lockMode())
    }

    @Test
    fun `low latency is not treated as screen off safe`() {
        // Documents the platform behaviour that caused the bug, so a future
        // reader cannot "upgrade" to the newer constant without this failing.
        assertFalse(WifiLockPolicy.isEffectiveWhileScreenOff(WifiLockPolicy.MODE_FULL_LOW_LATENCY))
    }

    @Test
    fun `deprecated no-op full mode is never chosen and never trusted`() {
        assertFalse(WifiLockPolicy.isEffectiveWhileScreenOff(WifiLockPolicy.MODE_FULL))
        assertTrue(WifiLockPolicy.lockMode() != WifiLockPolicy.MODE_FULL)
    }

    @Test
    fun `unknown modes are not trusted`() {
        assertFalse(WifiLockPolicy.isEffectiveWhileScreenOff(0))
        assertFalse(WifiLockPolicy.isEffectiveWhileScreenOff(99))
        assertFalse(WifiLockPolicy.isEffectiveWhileScreenOff(-1))
    }

    @Test
    fun `description states screen off validity in words`() {
        // The old diagnostic printed the raw integer, which told a reader
        // nothing. A screen-off report must name the risk.
        val chosen = WifiLockPolicy.describe(WifiLockPolicy.lockMode())
        assertTrue("expected mode name, got: $chosen", chosen.contains("FULL_HIGH_PERF"))
        assertTrue("expected survival stated, got: $chosen", chosen.contains("survives-screen-off"))

        val bad = WifiLockPolicy.describe(WifiLockPolicy.MODE_FULL_LOW_LATENCY)
        assertTrue("expected the risk called out, got: $bad", bad.contains("SCREEN-ON-ONLY"))
    }

    @Test
    fun `unknown mode is described without pretending to know it`() {
        val text = WifiLockPolicy.describe(77)
        assertTrue(text.contains("UNKNOWN(77)"))
        assertTrue(text.contains("SCREEN-ON-ONLY"))
    }

    @Test
    fun `platform constant cross-check passes on correct values`() {
        // Real framework values for WIFI_MODE_FULL / _FULL_HIGH_PERF / _FULL_LOW_LATENCY.
        assertNull(WifiLockPolicy.assertMatchesPlatform(1, 3, 4))
    }

    @Test
    fun `platform constant cross-check reports every mismatch`() {
        val problem = WifiLockPolicy.assertMatchesPlatform(1, 9, 4)
        assertTrue("expected the mismatch named, got: $problem", problem!!.contains("FULL_HIGH_PERF"))

        val all = WifiLockPolicy.assertMatchesPlatform(7, 8, 9)
        assertTrue(all!!.contains("FULL"))
        assertTrue(all.contains("FULL_HIGH_PERF"))
        assertTrue(all.contains("FULL_LOW_LATENCY"))
    }
}
