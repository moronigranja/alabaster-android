package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The table size served to a device, and the two constants that must agree about it.
 *
 * The rule exists because of issue #1: the game's `uniform vec2 u_texSlotCoords[256]` costs 256 of
 * the GLES3 minimum's 256 vertex uniform vectors, so on the devices that freeze every vertex shader
 * fails with `too many uniforms`. 256 is kept wherever the device can take it - that is the game's
 * own atlas ceiling and nothing here should lower it needlessly.
 */
class ShaderSlotsTest {

    /** The object holds what the page reported; one test must not see another's device. */
    @Before
    fun reset() {
        ShaderSlots.reset()
    }

    @Test
    fun `the page's own link decides before the reported count does`() {
        // Adreno reports the GLES3 minimum of 256 *and* links the game's own 256-slot table, because
        // its compiler packs two vec2 slots into one vector. Rewriting there would shrink the engine's
        // atlas and pack gui.vert for nothing, so the link is what decides.
        ShaderSlots.vertexUniformVectors = 256
        assertEquals(192, ShaderSlots.slots())
        assertTrue(ShaderSlots.rewrites())

        // the page answered in time: the game's own 256 and no packing
        ShaderSlots.oneTableLinked = true
        ShaderSlots.twoTablesLinked = true
        assertEquals(256, ShaderSlots.slots())
        assertFalse(ShaderSlots.packs())
        assertFalse(ShaderSlots.rewrites())

        // one table links, gui.vert's two do not: keep 256, pack only gui.vert
        ShaderSlots.twoTablesLinked = false
        assertEquals(256, ShaderSlots.slots())
        assertTrue(ShaderSlots.packs())
        assertTrue(ShaderSlots.rewrites())

        // neither links (SwiftShader, issue #1): the count-based path, unchanged
        ShaderSlots.oneTableLinked = false
        assertEquals(192, ShaderSlots.slots())
        assertTrue(ShaderSlots.packs())
        assertTrue(ShaderSlots.rewrites())
    }

    @Test
    fun `serving the bundle locks the decision, so the engine and the shaders cannot disagree`() {
        // What the Fold 7 does: the bundle is asked for before the page's link answer lands, so it is
        // served with the count-based plan...
        ShaderSlots.vertexUniformVectors = 256
        ShaderSlots.lock()
        assertEquals(192, ShaderSlots.slots())

        // ...and the answer that arrives afterwards must not move it, or the shaders would declare a
        // table the engine's own constant does not upload.
        ShaderSlots.oneTableLinked = true
        ShaderSlots.twoTablesLinked = true
        assertEquals("a late link does not move a served constant", 192, ShaderSlots.slots())
        assertTrue(ShaderSlots.packs())

        // a device that answered in time locks the game's own bytes for the whole session
        ShaderSlots.reset()
        ShaderSlots.vertexUniformVectors = 256
        ShaderSlots.oneTableLinked = true
        ShaderSlots.twoTablesLinked = true
        ShaderSlots.lock()
        assertEquals(256, ShaderSlots.slots())
        assertFalse(ShaderSlots.packs())

        // and the lock is a no-op once taken
        ShaderSlots.vertexUniformVectors = 1024
        ShaderSlots.lock()
        assertEquals(256, ShaderSlots.slots())
    }

    @Test
    fun `a page that never answers holds the wait for its own report`() {
        assertFalse(ShaderSlots.awaitReport(1))
        ShaderSlots.report(true, true)
        assertTrue(ShaderSlots.awaitReport(1))
    }

    @Test
    fun `the game's own 256 is kept when the device has the room`() {
        assertEquals(256, ShaderSlots.slotsFor(1024))
        assertFalse(ShaderSlots.rewrites(1024))
        // two tables plus the reserve are what gui.vert needs, so this is the threshold
        assertEquals(256, ShaderSlots.slotsFor(2 * ShaderSlots.GAME + ShaderSlots.PACK_RESERVE))
        assertFalse(ShaderSlots.rewrites(2 * ShaderSlots.GAME + ShaderSlots.PACK_RESERVE))
    }

    @Test
    fun `a device at the GLES3 minimum gets a table that fits`() {
        assertEquals(192, ShaderSlots.slotsFor(256))
        // one table plus the shaders' own uniforms fits the budget...
        assertTrue(ShaderSlots.slotsFor(256) + 48 <= 256)
        // ...and gui.vert's two tables only fit because they are packed, which is what this asks for
        assertTrue(ShaderSlots.packsTables(256))
        assertTrue(ShaderSlots.slotsFor(256) + ShaderSlots.PACK_RESERVE <= 256)
        assertTrue(ShaderSlots.rewrites(256))
    }

    @Test
    fun `only a shader with two tables is packed`() {
        val one = "uniform vec2 u_texSlotCoords[TEX_SLOT_COUNT];\n"
        val two = "uniform vec2 u_texSlotCoords[TEX_SLOT_COUNT];\nuniform vec2 u_fontSlotCoords[TEX_SLOT_COUNT];\n"
        assertEquals(1, ShaderSlots.tables(one))
        assertEquals(2, ShaderSlots.tables(two))
        assertFalse(ShaderSlots.rewriteShader(one, 192, true).contains("vec4"))
        assertTrue(ShaderSlots.rewriteShader(two, 192, true).contains("uniform vec4"))
    }

    @Test
    fun `a budget that never arrived is treated as the minimum`() {
        assertEquals(ShaderSlots.slotsFor(256), ShaderSlots.slotsFor(0))
    }

    @Test
    fun `a small budget still leaves the floor`() {
        assertEquals(ShaderSlots.FLOOR, ShaderSlots.slotsFor(100))
        assertEquals(ShaderSlots.FLOOR, ShaderSlots.slotsFor(ShaderSlots.FLOOR))
    }

    @Test
    fun `both constants move together`() {
        val shader = "#version 300 es\r\n#define TEX_SLOT_COUNT 256\r\nuniform vec2 u_texSlotCoords[TEX_SLOT_COUNT];\r\n"
        val engine = "const TEX_SLOT_COUNT = 256;\nthis.texSlotCoords = new Array(TEX_SLOT_COUNT * 2);\n"
        val rewrittenEngine = ShaderSlots.rewrite(engine, 192)
        assertTrue(rewrittenEngine.contains("const TEX_SLOT_COUNT = 192;"))
        // the array is sized from the same constant, so both sides of the upload agree
        assertTrue(rewrittenEngine.contains("new Array(TEX_SLOT_COUNT * 2)"))
        assertEquals(engine.replace("256", "192"), rewrittenEngine)
        assertTrue(ShaderSlots.rewriteShader(shader, 192, false).contains("#define TEX_SLOT_COUNT 192"))
    }

    @Test
    fun `a device that keeps the game's table gets the bytes unchanged`() {
        val shader = "#define TEX_SLOT_COUNT 256\nuniform vec2 u_texSlotCoords[TEX_SLOT_COUNT];\n"
        assertSame(shader, ShaderSlots.rewriteShader(shader, 256, false))
        assertSame(shader, ShaderSlots.rewrite(shader, 256))
        assertSame(shader, ShaderSlots.rewriteShader(shader, 300, false))
    }

    @Test
    fun `text without the constant is returned as it came`() {
        val other = "uniform mat4 u_cameraProjM;\n"
        assertSame(other, ShaderSlots.rewrite(other, 192))
        assertSame(other, ShaderSlots.rewriteShader(other, 192, true))
        assertSame(other, ShaderSlots.packTables(other))
    }

    @Test
    fun `the served value follows the reported budget`() {
        ShaderSlots.vertexUniformVectors = 256
        assertEquals(192, ShaderSlots.slots())
        assertTrue(ShaderSlots.rewrites(ShaderSlots.vertexUniformVectors))

        // a lock already taken holds its value even as a newer budget arrives
        ShaderSlots.lock()
        ShaderSlots.vertexUniformVectors = 1024
        assertEquals(192, ShaderSlots.slots())

        // a device with room locks the game's own 256
        ShaderSlots.reset()
        ShaderSlots.vertexUniformVectors = 1024
        ShaderSlots.lock()
        assertEquals(256, ShaderSlots.slots())
        assertFalse(ShaderSlots.rewrites(ShaderSlots.vertexUniformVectors))
    }
}
