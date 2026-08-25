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

    private val log = DiagLog(CAP)

    /**
     * Records one stage outcome. Repeats of the same [stage]+[detail] collapse
     * into one line spanning first and last occurrence, so a polling loop cannot
     * flood the report *and* a gap in that loop stays visible.
     */
    fun note(stage: String, detail: String) = log.note(stage, detail)

    /** The full report, oldest first. */
    fun snapshot(): String = log.snapshot()

    fun clear() = log.clear()

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
