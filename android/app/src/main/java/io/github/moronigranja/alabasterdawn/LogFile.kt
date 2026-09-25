package io.github.moronigranja.alabasterdawn

/**
 * The persistent record: the same text the diagnostics panel renders, kept as a file in the saves
 * folder so a user who force-stops the app or reboots the device still has it.
 *
 * Two rules, both pure, so they are unit-tested on the JVM (see `LogFileTest`):
 *
 *  - The toggle is on by default and switches *itself* off after the first boot that completes —
 *    but only while the user has never touched it. One tap, in either direction, sets
 *    [PREF_SET_KEY] and ends the auto-off for good.
 *  - A flush needs the toggle on, a ring that changed since the last write, no write in flight, and
 *    attempts left ([MAX_FAILURES]): a store that keeps failing must not be retried forever.
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

    fun shouldAutoDisable(userSet: Boolean, enabled: Boolean): Boolean = !userSet && enabled

    fun shouldFlush(enabled: Boolean, dirty: Boolean, writing: Boolean, failures: Int): Boolean =
        enabled && dirty && !writing && failures < MAX_FAILURES
}
