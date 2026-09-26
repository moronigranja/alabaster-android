package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        ShaderSlots.vertexUniformVectors = 1024
        assertEquals(256, ShaderSlots.slots())
        assertFalse(ShaderSlots.rewrites(ShaderSlots.vertexUniformVectors))
        ShaderSlots.vertexUniformVectors = 0
    }
}
