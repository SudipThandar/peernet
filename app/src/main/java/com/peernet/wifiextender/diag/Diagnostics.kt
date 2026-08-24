package com.peernet.wifiextender.diag

import timber.log.Timber

/**
 * In-app diagnostic log.
 *
 * The tester has no adb, so anything that only reaches logcat is invisible.
 * Every stage of linking and tunnelling records here, and the whole buffer can
 * be shared out of the app as text. This exists because the previous debugging
 * approach — reasoning about the code and asking the tester to read a few
 * counters off the screen — cost one round trip per defect and could not
 * distinguish "not on the network" from "host not answering".
 */
object Diagnostics {

    private const val CAP = 400

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val startedAt = System.currentTimeMillis()

    /** Last recorded stage, to collapse the 4-second poll into one line. */
    private var lastKey: String? = null
    private var lastRepeats = 0

    /**
     * Records one stage outcome. Repeats of the same [stage]+[detail] collapse
     * into "(xN)" so a polling loop cannot flood the report.
     */
    fun note(stage: String, detail: String) {
        val key = "$stage|$detail"
        synchronized(lock) {
            if (key == lastKey && lines.isNotEmpty()) {
                lastRepeats++
                lines.removeLast()
                lines.addLast("${stamp()} $stage: $detail (x${lastRepeats + 1})")
                return
            }
            lastKey = key
            lastRepeats = 0
            lines.addLast("${stamp()} $stage: $detail")
            while (lines.size > CAP) lines.removeFirst()
        }
    }

    /** The full report, oldest first. */
    fun snapshot(): String = synchronized(lock) {
        if (lines.isEmpty()) "(no diagnostics recorded yet)" else lines.joinToString("\n")
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            lastKey = null
            lastRepeats = 0
        }
    }

    private fun stamp(): String {
        val ms = System.currentTimeMillis() - startedAt
        return "+%d.%03ds".format(ms / 1000, ms % 1000)
    }

    /**
     * Feeds Timber warnings and errors into the same buffer, so a failure that
     * only logs still shows up in the shared report.
     */
    class Tree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean =
            priority >= android.util.Log.WARN

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val level = if (priority >= android.util.Log.ERROR) "error" else "warn"
            val cause = t?.let { " <- ${it.javaClass.simpleName}: ${it.message.orEmpty().take(120)}" }.orEmpty()
            note(level, "${tag.orEmpty()} $message$cause".trim())
        }
    }
}
