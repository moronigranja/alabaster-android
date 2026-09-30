package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GUI shaders' precision declaration. They ship as `mediump` while every world shader is
 * `highp`, which is invisible on the desktop build (desktop GL promotes mediump) and visible on a
 * mobile GPU: the GUI's screen-space and tiled maths quantises (FINDINGS §16). The port raises the
 * declaration; nothing else in those bytes may change.
 */
class ShaderPrecisionTest {

    private fun shader(rel: String, precision: String) =
        ("#version 300 es\r\n$precision\r\n\r\nuniform sampler2D u_sampler;\r\n").toByteArray()

    @Test
    fun `the gui shaders are raised to highp, line endings and all`() {
        for (rel in listOf("gui.frag", "gui.vert", "gui-bg.frag", "gui-blur.frag")) {
            val path = "terra/data/shader/fragment/$rel"
            val body = shader(path, "precision mediump float;")
            val out = String(ShaderPrecision.rewrite(path, body), Charsets.UTF_8)
            assertTrue("$rel: raised", out.contains("precision highp float;"))
            assertTrue("$rel: no mediump left", !out.contains("mediump"))
            assertEquals("$rel: only the declaration changed",
                String(body, Charsets.UTF_8).replace("mediump", "highp"), out)
            assertTrue("$rel: cached", ShaderPrecision.rewrites(path))
        }
    }

    @Test
    fun `a world shader is served as it came`() {
        for (path in listOf(
            "terra/data/shader/fragment/solid.frag",
            "terra/data/shader/vertex/solid.vert",
            "terra/data/shader/fragment/post/analog-filter.frag",
        )) {
            val body = shader(path, "precision mediump float;")
            assertSame("$path: untouched", body, ShaderPrecision.rewrite(path, body))
            assertTrue("$path: not cached for this", !ShaderPrecision.rewrites(path))
        }
    }

    @Test
    fun `a gui shader without the declaration, or a non-shader, is left alone`() {
        val high = shader("terra/data/shader/fragment/gui.frag", "precision highp float;")
        assertSame(high, ShaderPrecision.rewrite("terra/data/shader/fragment/gui.frag", high))
        val bundle = "const TEX_SLOT_COUNT = 256;".toByteArray()
        assertSame(bundle, ShaderPrecision.rewrite("terra/dist/bundle.js", bundle))
    }
}
