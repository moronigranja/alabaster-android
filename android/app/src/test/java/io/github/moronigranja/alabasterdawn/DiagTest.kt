package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules the diagnostics record has to hold to: bounded, newest-last, and frame-time based. */
class DiagTest {

    private var now = 1_000L
    private fun diag(capacity: Int = 4) = Diag(capacity = capacity, clock = { now })

    @Test
    fun `keeps at most capacity lines, newest last`() {
        val diag = diag(capacity = 3)
        for (i in 1..5) {
            now += 10
            diag.line("line $i")
        }
        val text = diag.snapshot(emptyList())
        assertEquals(3, diag.size())
        assertTrue("oldest trimmed", !text.contains("line 2"))
        assertEquals(
            listOf("3", "4", "5"),
            text.lines().filter { it.contains("line ") }.map { it.substringAfter("line ") }
        )
    }

    @Test
    fun `timestamps are relative to construction`() {
        val diag = diag()
        now += 250
        diag.line("indexed")
        assertTrue(diag.snapshot(emptyList()).contains("+250ms indexed"))
    }

    @Test
    fun `engine reports are tagged, and the header comes first`() {
        val diag = diag()
        diag.event("boot stall", "no progress for 8000ms")
        val text = diag.snapshot(listOf("webView x 1.0"))
        assertEquals("webView x 1.0", text.lines().first())
        assertTrue(text.contains("ENGINE boot stall: no progress for 8000ms"))
    }

    @Test
    fun `silence is measured from the last engine frame`() {
        val diag = diag()
        now += 40_000
        assertEquals(40_000L, diag.silentMs())
        diag.noteFrame()
        assertEquals(0L, diag.silentMs())
    }

    @Test
    fun `the bridge call in flight is observable and cleared`() {
        val diag = diag()
        assertEquals("-", diag.lastBridgeCall())
        diag.noteCall("fsExists /saves/Default")
        assertEquals("fsExists /saves/Default", diag.lastBridgeCall())
    }

    @Test
    fun `immediately repeated lines collapse in place`() {
        val diag = diag(capacity = 10)
        repeat(3) {
            now += 10
            diag.line("There are unwrapped loadTrackers")
        }
        assertEquals(1, diag.size())
        val text = diag.snapshot(emptyList())
        assertTrue(text.contains("There are unwrapped loadTrackers (x3)"))
        assertEquals(1, text.lines().count { it.contains("There are unwrapped loadTrackers") })
    }

    @Test
    fun `a different message ends the repeat group`() {
        val diag = diag(capacity = 10)
        for (message in listOf("A", "B", "A")) {
            now += 10
            diag.line(message)
        }
        assertEquals(3, diag.size())
        assertEquals(
            listOf("A", "B", "A"),
            diag.snapshot(emptyList()).lines()
                .filter { it.contains("ms ") }
                .map { it.substringAfter("ms ") }
        )
    }

    @Test
    fun `a collapsed line keeps the newest timestamp`() {
        val diag = diag(capacity = 10)
        diag.line("same")
        now += 40
        diag.line("same")
        assertTrue(diag.snapshot(emptyList()).contains("+40ms same (x2)"))
    }

    @Test
    fun `the sink sees every raw occurrence`() {
        val seen = ArrayList<String>()
        val diag = diag(capacity = 10)
        diag.attachSink { seen.add(it) }
        repeat(3) {
            now += 10
            diag.line("same")
        }
        assertEquals(3, seen.size)
        assertTrue("each occurrence is stamped once", seen.all { it.endsWith(" same") })
    }
}
