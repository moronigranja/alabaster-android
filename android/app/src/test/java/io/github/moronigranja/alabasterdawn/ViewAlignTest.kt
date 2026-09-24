package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ViewAlignTest {

    @Test
    fun `every position keeps the wire value the page and the pref read`() {
        assertEquals("top", ViewAlign.TOP.wire)
        assertEquals("center", ViewAlign.CENTER.wire)
        assertEquals("bottom", ViewAlign.BOTTOM.wire)
    }

    @Test
    fun `a wire value round-trips`() {
        for (value in ViewAlign.entries) assertSame(value, ViewAlign.fromWire(value.wire))
    }

    @Test
    fun `a missing or unknown pref keeps the picture centred`() {
        assertSame(ViewAlign.CENTER, ViewAlign.fromWire(null))
        assertSame(ViewAlign.CENTER, ViewAlign.fromWire(""))
        assertSame(ViewAlign.CENTER, ViewAlign.fromWire("middle"))
    }
}
