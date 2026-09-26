package io.github.moronigranja.alabasterdawn

/**
 * The persistent record: the same text the diagnostics panel renders, kept as a file in the saves
 * folder so a user who force-stops the app or reboots the device still has it.
 *
 * The rules, all pure, so they are unit-tested on the JVM (see `LogFileTest`):
 *
 *  - The toggle is on by default and switches *itself* off after the first boot that completes —
 *    but only while the user has never touched it. One tap, in either direction, sets
 *    [PREF_SET_KEY] and ends the auto-off for good.
 *  - A flush needs the toggle on, a ring that changed since the last write, no write in flight, and
 *    attempts left ([MAX_FAILURES]): a store that keeps failing must not be retried forever.
 *  - A new session carries the previous file's stamped lines back into the record ([previousLines]),
 *    so a user who restarts after a freeze still sees what happened instead of an empty panel.
 *
 * The name lives here and nowhere else.
 */
object LogFile {
    /** At the saves root, next to `pad-layout.json`; the engine ignores unknown root entries. */
    const val FILE = "ada-diagnostics.log"

    /** Boolean, default true: whether the record is written to [FILE]. */
    const val PREF_KEY = "log_to_saves"

    /** Boolean, default false: whether the user has ever changed [PREF_KEY] themselves. */
    const val PREF_SET_KEY = "log_to_saves_user_set"

    const val MAX_FAILURES = 3

    /**
     * Carried into the ring between a previous session's lines and this one's. It never starts with
     * `+`, so [previousLines] drops it on the next restart and markers cannot accumulate.
     */
    const val PREVIOUS_MARKER = "--- previous session, carried from $FILE ---"

    fun shouldAutoDisable(userSet: Boolean, enabled: Boolean): Boolean = !userSet && enabled

    fun shouldFlush(enabled: Boolean, dirty: Boolean, writing: Boolean, failures: Int): Boolean =
        enabled && dirty && !writing && failures < MAX_FAILURES

    /**
     * What a new session carries over from a previous [FILE]'s text: the newest stamped log lines,
     * behind one [PREVIOUS_MARKER], filling at most **half** of [capacity] (so a 400-line ring
     * carries the newest 199 lines).
     *
     * Half, not all: the carried lines sit at the front of the ring — that is what makes them the
     * oldest — so they are the first thing a later write trims. A carry that filled the ring would
     * lose its marker, and its oldest lines, to the very next log line (including this session's own
     * "diagnostics opened"), which is exactly the confusing record this exists to avoid.
     *
     * Header lines and any earlier [PREVIOUS_MARKER] are dropped, so a record carried over
     * repeatedly does not accumulate them.
     */
    fun previousLines(fileText: String, capacity: Int): List<String> {
        val room = capacity / 2 - 1
        if (room < 1) return emptyList()
        val lines = fileText.lineSequence().filter { it.startsWith("+") }.toList().takeLast(room)
        return if (lines.isEmpty()) emptyList() else listOf(PREVIOUS_MARKER) + lines
    }
}
