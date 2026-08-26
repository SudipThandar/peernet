package com.peernet.wifiextender

import com.peernet.wifiextender.host.HostStopReason
import com.peernet.wifiextender.host.ShareDuration
import com.peernet.wifiextender.host.ShareTimerPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share auto-stop clock.
 *
 * The hard requirement is that this can never be confused with the unexplained
 * host death after 5-10 minutes, which is still open. That means it must not expire
 * early under any clock behaviour, and a timer stop must carry its own reason.
 */
class ShareTimerPolicyTest {

    private val minute = 60_000L

    @Test
    fun `a thirty minute share expires at thirty minutes`() {
        val start = 1_000_000L
        assertFalse(
            ShareTimerPolicy.hasExpired(start, start + 29 * minute, ShareDuration.MIN_30)
        )
        assertTrue(
            ShareTimerPolicy.hasExpired(start, start + 30 * minute, ShareDuration.MIN_30)
        )
    }

    @Test
    fun `an unlimited share never expires`() {
        val start = 0L
        assertFalse(
            ShareTimerPolicy.hasExpired(start, start + 365 * 24 * 60 * minute, ShareDuration.UNLIMITED)
        )
        assertNull(ShareTimerPolicy.remainingMs(start, start + minute, ShareDuration.UNLIMITED))
    }

    @Test
    fun `a clock that jumps backwards does not expire the share`() {
        // NTP correction, the user changing the time, a reboot mid-share. Reading a
        // negative elapsed time as a spent budget would stop a healthy share and
        // look exactly like the bug still under investigation.
        val start = 5_000_000L
        val now = start - 10 * minute
        assertFalse(ShareTimerPolicy.hasExpired(start, now, ShareDuration.MIN_45))
        assertEquals(
            45 * minute,
            ShareTimerPolicy.remainingMs(start, now, ShareDuration.MIN_45)
        )
    }

    @Test
    fun `remaining time never goes negative`() {
        val start = 0L
        assertEquals(
            0L,
            ShareTimerPolicy.remainingMs(start, 99 * minute, ShareDuration.MIN_60)
        )
    }

    @Test
    fun `remaining time counts down`() {
        val start = 0L
        assertEquals(
            60 * minute,
            ShareTimerPolicy.remainingMs(start, start, ShareDuration.MIN_60)
        )
        assertEquals(
            20 * minute,
            ShareTimerPolicy.remainingMs(start, 40 * minute, ShareDuration.MIN_60)
        )
    }

    @Test
    fun `every duration except unlimited is free`() {
        assertTrue(ShareTimerPolicy.isSelectable(ShareDuration.MIN_30, premium = false))
        assertTrue(ShareTimerPolicy.isSelectable(ShareDuration.MIN_45, premium = false))
        assertTrue(ShareTimerPolicy.isSelectable(ShareDuration.MIN_60, premium = false))
        assertFalse(ShareTimerPolicy.isSelectable(ShareDuration.UNLIMITED, premium = false))
        assertTrue(ShareTimerPolicy.isSelectable(ShareDuration.UNLIMITED, premium = true))
    }

    @Test
    fun `unlimited cannot be inherited without an entitlement`() {
        // A stored UNLIMITED from a lapsed subscription, or from a build where the
        // option was not yet gated, must not grant unlimited sharing.
        assertEquals(
            ShareTimerPolicy.DEFAULT,
            ShareTimerPolicy.resolve(ShareDuration.UNLIMITED, premium = false)
        )
        assertEquals(
            ShareDuration.UNLIMITED,
            ShareTimerPolicy.resolve(ShareDuration.UNLIMITED, premium = true)
        )
    }

    @Test
    fun `a free choice is never downgraded`() {
        assertEquals(
            ShareDuration.MIN_30,
            ShareTimerPolicy.resolve(ShareDuration.MIN_30, premium = false)
        )
    }

    @Test
    fun `the default limit is finite`() {
        // A default of unlimited would make the free tier unlimited by accident.
        assertFalse(ShareTimerPolicy.DEFAULT.requiresPremium)
    }

    @Test
    fun `the countdown is formatted as minutes and seconds`() {
        assertEquals("30:00", ShareTimerPolicy.formatRemaining(30 * minute))
        assertEquals("0:05", ShareTimerPolicy.formatRemaining(5_000L))
        assertEquals("0:00", ShareTimerPolicy.formatRemaining(0L))
        assertEquals("9:09", ShareTimerPolicy.formatRemaining(9 * minute + 9_000L))
        assertNull(ShareTimerPolicy.formatRemaining(null))
    }

    @Test
    fun `a timer stop is distinguishable from a user stop`() {
        assertNotEquals(HostStopReason.USER, HostStopReason.TIMER_EXPIRED)
        assertEquals("timer-expired", HostStopReason.TIMER_EXPIRED)
    }

    @Test
    fun `only unlimited has no minute value`() {
        val unlimited = ShareDuration.values().filter { it.minutes == null }
        assertEquals(listOf(ShareDuration.UNLIMITED), unlimited)
        ShareDuration.values().filter { it.minutes != null }.forEach {
            assertTrue("${it.name} should be free", ShareTimerPolicy.isSelectable(it, premium = false))
        }
    }
}
