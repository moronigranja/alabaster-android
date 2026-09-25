package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing and threshold rules behind "which asset read never came back". */
class AssetTrackerTest {

    private var now = 0L
    private val tracker = AssetTracker(clock = { now })

    @Test
    fun `a stuck read is listed with its age, an unfinished one is not`() {
        tracker.started("terra/dist/bundle.js")
        tracker.started("terra/media/gui/menu.png")
        now += 20_000
        tracker.started("terra/media/gui/hud.png")

        val stuck = tracker.stuck(5_000)
        assertEquals(2, stuck.size)
        assertTrue(stuck.any { it == "terra/dist/bundle.js (20s)" })
        assertTrue(stuck.any { it == "terra/media/gui/menu.png (20s)" })
        assertTrue("a read younger than the threshold is not stuck", stuck.none { it.contains("hud") })
    }

    @Test
    fun `the threshold is inclusive and a finished read leaves the map`() {
        tracker.started("a.png")
        now += 5_000
        assertEquals(listOf("a.png (5s)"), tracker.stuck(5_000))
        tracker.finished("a.png")
        assertEquals(emptyList<String>(), tracker.stuck(5_000))
    }

    @Test
    fun `counters report served, missed, in-flight and the slowest read`() {
        tracker.started("slow.png")
        now += 900
        assertEquals(900L, tracker.finished("slow.png"))
        tracker.started("fast.png")
        now += 100
        tracker.finished("fast.png")
        tracker.started("missing.ogg")
        tracker.missed("missing.ogg")
        tracker.started("stuck.png")

        val counters = tracker.counters()
        assertTrue(counters, counters.contains("served=2 missed=1 inFlight=1 slowest=900ms slow.png"))
    }

    @Test
    fun `a miss is never counted as served`() {
        tracker.started("missing.ogg")
        tracker.missed("missing.ogg")
        assertEquals(0L, tracker.finished("missing.ogg"))
        assertTrue(tracker.counters().contains("served=0 missed=1 inFlight=0"))
    }

    @Test
    fun `finishing a read that was never started is not counted`() {
        assertEquals(0L, tracker.finished("never.png"))
        assertTrue(tracker.counters().contains("served=0"))
    }
}
