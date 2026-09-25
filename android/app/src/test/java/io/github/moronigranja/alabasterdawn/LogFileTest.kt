package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two rules behind the log-to-saves toggle: when it switches off by itself, and when to write. */
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
}
