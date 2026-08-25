package com.peernet.wifiextender

import com.peernet.wifiextender.diag.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics buffer must not destroy the one signal the screen-off failure
 * is diagnosed by.
 *
 * `HostForegroundService` ticks every 15s while sharing and documents the rule:
 * a gap in those entries means the process was frozen by Doze or OEM standby,
 * whereas ticks that continue while the client has no internet point at the radio
 * or the group. Those have opposite fixes, so the report has to tell them apart.
 *
 * The tick's text is identical every time (same session, same counters), so it
 * hits the collapsing path on every emission. Collapsing to a single line with
 * only the newest stamp made a ten-minute freeze and ten minutes of healthy
 * ticking render identically - which is why the tester's dumps could not settle
 * the question no matter how many times the failure was reproduced.
 */
class DiagLogTest {

    private class FakeClock(var now: Long = 0) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `a frozen tick loop is visible as a span holding too few ticks`() {
        val clock = FakeClock()
        val log = DiagLog(clock = clock)
        val tick = "LINKSERVER_ALIVE (tick) id=1 listening=true probes=0"

        // Two healthy 15s ticks, then the process is frozen for 480s, then it
        // resumes and ticks once more.
        log.note("host", tick)
        clock.advance(15_000)
        log.note("host", tick)
        clock.advance(480_000)
        log.note("host", tick)

        val out = log.snapshot()
        // The span must survive: +0.000 to +495.000 holding only 3 ticks of a
        // 15s loop is ~30 missing ticks, which names the freeze.
        assertEquals("+0.000s..+495.000s host: $tick (x3)", out)
    }

    @Test
    fun `healthy ticking is distinguishable from a freeze over the same span`() {
        val clock = FakeClock()
        val frozen = DiagLog(clock = clock)
        frozen.note("host", "tick")
        clock.advance(300_000)
        frozen.note("host", "tick")

        val clock2 = FakeClock()
        val healthy = DiagLog(clock = clock2)
        repeat(21) {
            healthy.note("host", "tick")
            clock2.advance(15_000)
        }

        // Same elapsed window, different counts. Before this change both
        // collapsed to a single line carrying only the final stamp.
        assertTrue(frozen.snapshot().endsWith("(x2)"))
        assertTrue(healthy.snapshot().endsWith("(x21)"))
        assertTrue(frozen.snapshot().startsWith("+0.000s..+300.000s"))
        assertTrue(healthy.snapshot().startsWith("+0.000s..+300.000s"))
    }

    @Test
    fun `a single occurrence keeps the plain unspanned format`() {
        val clock = FakeClock(1_500)
        val log = DiagLog(clock = clock)
        clock.advance(2_250)
        log.note("client", "HOST_IP_DETECTED 192.168.49.1:4434")

        assertEquals("+2.250s client: HOST_IP_DETECTED 192.168.49.1:4434", log.snapshot())
    }

    @Test
    fun `different details are not collapsed together`() {
        val clock = FakeClock()
        val log = DiagLog(clock = clock)
        log.note("host", "HOST_SCREEN_OFF")
        clock.advance(1_000)
        log.note("host", "tick")
        clock.advance(1_000)
        log.note("host", "HOST_SCREEN_ON")

        assertEquals(
            listOf(
                "+0.000s host: HOST_SCREEN_OFF",
                "+1.000s host: tick",
                "+2.000s host: HOST_SCREEN_ON"
            ),
            log.snapshot().lines()
        )
    }

    @Test
    fun `an interleaved line ends the run so a later repeat starts a new span`() {
        val clock = FakeClock()
        val log = DiagLog(clock = clock)
        log.note("host", "tick")
        clock.advance(15_000)
        log.note("host", "LINKSERVER_STOPPED reason=EADDRINUSE")
        clock.advance(15_000)
        log.note("host", "tick")

        // The second tick must not be folded back into the first run: doing so
        // would place the responder failure inside a span that continues past it.
        assertEquals(
            listOf(
                "+0.000s host: tick",
                "+15.000s host: LINKSERVER_STOPPED reason=EADDRINUSE",
                "+30.000s host: tick"
            ),
            log.snapshot().lines()
        )
    }

    @Test
    fun `the buffer stays capped so a poll loop cannot evict the failure`() {
        val clock = FakeClock()
        val log = DiagLog(cap = 5, clock = clock)
        repeat(40) { i ->
            log.note("client", "probe $i")
            clock.advance(4_000)
        }

        val lines = log.snapshot().lines()
        assertEquals(5, lines.size)
        assertTrue(lines.last().endsWith("probe 39"))
    }

    @Test
    fun `an empty buffer says so rather than returning blank`() {
        assertEquals("(no diagnostics recorded yet)", DiagLog().snapshot())
    }

    @Test
    fun `clear resets the collapsing run`() {
        val clock = FakeClock()
        val log = DiagLog(clock = clock)
        log.note("host", "tick")
        log.clear()
        clock.advance(9_000)
        log.note("host", "tick")

        // Not "(x2)": the earlier run is gone, so the new line must stand alone.
        assertEquals("+9.000s host: tick", log.snapshot())
    }
}
