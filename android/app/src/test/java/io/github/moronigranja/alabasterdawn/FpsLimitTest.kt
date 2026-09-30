package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The side menu's frame-rate cap. The slider offers four rates and the app stores one of them; the
 * bridge sends the rate (or [FpsLimit.OFF] with the switch off) and the shim's gate turns it into an
 * interval (FINDINGS §20.2/§23). What matters here is that the two sides of the slider - the position
 * and the rate - always agree, including for a value another build wrote into the preferences.
 */
class FpsLimitTest {

    @Test
    fun `the slider's four positions map to the four rates, in menu order`() {
        assertEquals(listOf(20, 30, 45, 60), FpsLimit.CHOICES)
        for (index in FpsLimit.CHOICES.indices) {
            assertEquals(FpsLimit.CHOICES[index], FpsLimit.at(index))
            assertEquals("round trip at $index", index, FpsLimit.indexOf(FpsLimit.at(index)))
        }
    }

    @Test
    fun `a stored rate that is not a choice snaps to the nearest one, ties to the lower`() {
        assertEquals(20, FpsLimit.normalize(1))
        assertEquals(20, FpsLimit.normalize(24))
        assertEquals("a tie at 25 goes to the lower rate", 20, FpsLimit.normalize(25))
        assertEquals("the 30/45 boundary is 37.5", 30, FpsLimit.normalize(37))
        assertEquals(45, FpsLimit.normalize(38))
        assertEquals(45, FpsLimit.normalize(52))
        assertEquals(60, FpsLimit.normalize(53))
        assertEquals(60, FpsLimit.normalize(240))
        assertEquals("nothing stored, nothing chosen", 30, FpsLimit.normalize(FpsLimit.DEFAULT))
        assertEquals("off is not a rate: it reads as unchosen", 30, FpsLimit.normalize(FpsLimit.OFF))
    }

    @Test
    fun `a position outside the slider is clamped rather than crashing the menu`() {
        assertEquals(20, FpsLimit.at(-3))
        assertEquals(60, FpsLimit.at(FpsLimit.CHOICES.size + 4))
    }

    @Test
    fun `off is not a rate, and the switch's old default is the middle of the slider`() {
        assertEquals(0, FpsLimit.OFF)
        assertEquals(30, FpsLimit.DEFAULT)
        assertTrue("off is not one of the choices", !FpsLimit.CHOICES.contains(FpsLimit.OFF))
    }
}
