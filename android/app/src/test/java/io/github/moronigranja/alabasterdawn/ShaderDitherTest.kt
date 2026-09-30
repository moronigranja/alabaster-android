package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The world shaders' ordered dither. Five of them index it by `gl_FragCoord.xy / (u_screenScale *
 * u_ditherScale)` — one cell per art pixel, so on a phone panel the dots are as large as the art
 * (FINDINGS §18). The port serves them indexed by the render pixel, which is what the engine's own
 * `solid.frag` and the water's radial dither already do. Nothing else in those bytes may change.
 */
class ShaderDitherTest {

    private val ART_GRID = "/ (u_screenScale * u_ditherScale);"
    private val RENDER_GRID = "/ u_ditherScale;"

    private fun shader(divisor: String) = """
        #version 300 es
        precision highp float;
        uniform float u_screenScale;
        uniform float u_ditherScale;
        layout(location = 0) out vec4 albedo;
        void main() {
            vec2 screenCoord = gl_FragCoord.xy $divisor
            if (dither(0.5, screenCoord, 0u) < 0.0) discard;
            albedo = vec4(1.0);
        }
    """.trimIndent()

    private val artGrid = listOf(
        "solid-simple-light.frag",
        "solid-back.frag",
        "solid-overlap.frag",
        "solid-simple.frag",
        "shadow-map.frag",
    )

    @Test
    fun `the five art-grid dithers are served on the render grid`() {
        for (name in artGrid) {
            val rel = "terra/data/shader/fragment/$name"
            val text = shader(ART_GRID)
            val out = ShaderDither.rewrite(rel, text)
            assertEquals("$name: only the divisor changed", text.replace(ART_GRID, RENDER_GRID), out)
            assertTrue("$name: the art grid is gone", !out.contains("u_screenScale * u_ditherScale"))
            assertTrue("$name: cached", ShaderDither.rewrites(rel))
        }
    }

    @Test
    fun `a shader that already dithers at the render pixel is untouched`() {
        for (rel in listOf(
            "terra/data/shader/fragment/solid.frag",
            "terra/data/shader/fragment/water-plane.frag",
            "terra/data/shader/lib/water.glsl",
        )) {
            val text = shader(RENDER_GRID)
            assertSame("$rel: untouched", text, ShaderDither.rewrite(rel, text))
            assertTrue("$rel: not cached for this", !ShaderDither.rewrites(rel))
        }
    }

    @Test
    fun `rewriting the served text again changes nothing`() {
        for (name in artGrid) {
            val rel = "terra/data/shader/fragment/$name"
            val once = ShaderDither.rewrite(rel, shader(ART_GRID))
            assertSame("$name: idempotent", once, ShaderDither.rewrite(rel, once))
        }
    }

    @Test
    fun `a shader without the line, and a non-shader, are left alone`() {
        val none = shader("/ 2.0;")
        assertSame(none, ShaderDither.rewrite("terra/data/shader/fragment/solid-simple.frag", none))
        val bundle = "const TEX_SLOT_COUNT = 256;"
        assertSame(bundle, ShaderDither.rewrite("terra/dist/bundle.js", bundle))
    }
}
