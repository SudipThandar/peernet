package com.peernet.wifiextender.diag

/**
 * The diagnostic ring buffer, with an injectable clock so its collapsing rules
 * are testable.
 *
 * Repeats have to collapse: the host ticks every 15s and the client polls every
 * 4s, so without collapsing a 400-line buffer holds a few minutes of one loop
 * and evicts the link failure that is being investigated.
 *
 * But collapsing must not destroy *when*. The screen-off failure ("client loses
 * internet when the host display turns off") is diagnosed by exactly one signal:
 * whether the host's periodic tick kept firing. A gap means the process was
 * frozen by Doze or the OEM's standby; ticks continuing while the client is dead
 * points at the radio or the group instead. Those need opposite fixes.
 *
 * The previous version rewrote the collapsed line with the newest stamp only, so
 * a ten-minute freeze and ten minutes of healthy ticking rendered identically:
 * one line, one timestamp. This keeps the first and last stamp of a run, which
 * makes a freeze arithmetic rather than a guess - a 480s span holding 8 ticks of
 * a 15s loop is 32 missing ticks.
 */
class DiagLog(
    private val cap: Int = 400,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val startedAt = clock()

    /** The run currently being collapsed, or null if the last line was unique. */
    private var runKey: String? = null
    private var runStage: String = ""
    private var runDetail: String = ""
    private var runFirstMs: Long = 0
    private var runCount: Int = 0

    /**
     * Records one stage outcome. Consecutive repeats of the same [stage] and
     * [detail] collapse into a single line spanning first and last occurrence.
     */
    fun note(stage: String, detail: String) {
        val key = "$stage|$detail"
        val now = clock() - startedAt
        synchronized(lock) {
            if (key == runKey && lines.isNotEmpty()) {
                runCount++
                lines.removeLast()
                lines.addLast(collapsed(now))
                return
            }
            runKey = key
            runStage = stage
            runDetail = detail
            runFirstMs = now
            runCount = 1
            lines.addLast("${stamp(now)} $stage: $detail")
            evict()
        }
    }

    /** The full report, oldest first. */
    fun snapshot(): String = synchronized(lock) {
        if (lines.isEmpty()) "(no diagnostics recorded yet)" else lines.joinToString("\n")
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            runKey = null
            runCount = 0
        }
    }

    private fun collapsed(lastMs: Long): String =
        "${stamp(runFirstMs)}..${stamp(lastMs)} $runStage: $runDetail (x$runCount)"

    private fun evict() {
        while (lines.size > cap) {
            lines.removeFirst()
            // The collapsing run may itself have been evicted; starting a fresh
            // run is correct, and cheaper than tracking identity of the head.
            if (lines.isEmpty()) runKey = null
        }
    }

    private fun stamp(ms: Long): String = "+%d.%03ds".format(ms / 1000, ms % 1000)
}
