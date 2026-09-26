package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the log-to-saves toggle: when it switches off by itself, when it may write, and
 * what a new session carries over from the previous record.
 */
class LogFileTest {

    @Test
    fun `only a toggle the user never touched is switched off automatically`() {
        assertTrue(LogFile.shouldAutoDisable(userSet = false, enabled = true))
        assertFalse("an explicit choice is kept", LogFile.shouldAutoDisable(userSet = true, enabled = true))
        assertFalse("already off", LogFile.shouldAutoDisable(userSet = false, enabled = false))
    }

    @Test
    fun `a flush needs an enabled, dirty, idle log with attempts left`() {
        assertTrue(LogFile.shouldFlush(enabled = true, dirty = true, writing = false, failures = 0))
        assertFalse(
            "off",
            LogFile.shouldFlush(enabled = false, dirty = true, writing = false, failures = 0)
        )
        assertFalse(
            "nothing new to write",
            LogFile.shouldFlush(enabled = true, dirty = false, writing = false, failures = 0)
        )
        assertFalse(
            "a write is in flight",
            LogFile.shouldFlush(enabled = true, dirty = true, writing = true, failures = 0)
        )
        assertFalse(
            "the store keeps failing",
            LogFile.shouldFlush(
                enabled = true, dirty = true, writing = false, failures = LogFile.MAX_FAILURES
            )
        )
    }

    @Test
    fun `a previous record's log lines are carried, its header and markers are not`() {
        val fileText = listOf(
            "app 0.4 (4) on AYN AYN Thor, Android 13 (SDK 33, arm64-v8a)",
            "saves: Saves",
            "engine: silent for 1089ms, last bridge call: -",
            "",
            "--- log (3 lines, oldest first) ---",
            "+0ms port 0.4 (4) on AYN AYN Thor",
            "+10ms indexing content://…",
            LogFile.PREVIOUS_MARKER,
            "+20ms diagnostics opened",
        ).joinToString("\n")
        assertEquals(
            listOf(
                LogFile.PREVIOUS_MARKER,
                "+0ms port 0.4 (4) on AYN AYN Thor",
                "+10ms indexing content://…",
                "+20ms diagnostics opened",
            ),
            LogFile.previousLines(fileText, 400)
        )
        assertEquals(
            "the carry fills half the ring at most",
            listOf(LogFile.PREVIOUS_MARKER, "+20ms diagnostics opened"),
            LogFile.previousLines(fileText, 4)
        )
        assertEquals("too small a ring to carry anything", emptyList<String>(), LogFile.previousLines(fileText, 2))
        assertEquals("no room to carry", emptyList<String>(), LogFile.previousLines(fileText, 0))
        assertEquals("no record at all", emptyList<String>(), LogFile.previousLines("app 0.4 (4)\n", 400))
        val full = LogFile.previousLines((1..10).joinToString("\n") { "+$it ms line" }, 10)
        assertEquals("marker plus half the ring", 5, full.size)
        assertEquals(LogFile.PREVIOUS_MARKER, full.first())
        assertEquals(listOf("+7 ms line", "+8 ms line", "+9 ms line", "+10 ms line"), full.drop(1))
    }

    @Test
    fun `carrying a carried record does not stack markers`() {
        val second = LogFile.previousLines("--- log (1 lines, oldest first) ---\n+0ms a", 400)
        val third = LogFile.previousLines(
            "--- log (2 lines, oldest first) ---\n" + second.joinToString("\n"),
            400
        )
        assertEquals(listOf(LogFile.PREVIOUS_MARKER, "+0ms a"), third)
    }
}
