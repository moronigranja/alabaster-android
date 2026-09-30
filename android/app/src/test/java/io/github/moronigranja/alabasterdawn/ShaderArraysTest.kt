package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The array declarations some ES 3.0 fragment front ends refuse (FINDINGS §22). The reporting device —
 * a Mali-G720 — answers a shader with `precision mediump float;` declared with
 *
 * ```
 * S0032: no default precision defined for variable 'vec4[4]'
 * S0032: no default precision defined for variable 'vec3[5]'
 * ```
 *
 * for the `type[size] name` and `type[size](…)` spellings, and freezes the boot with those three
 * shaders pending. The port serves them lifted into the `type name[size]` spelling the same device is
 * known to accept, and only to a device whose own compiler refused them.
 */
class ShaderArraysTest {

    /** The object holds what the page reported; one test must not see another's device. */
    @Before
    fun reset() {
        ShaderArrays.reset()
    }

    /** The game's `lib/water.glsl` around the wave path, verbatim but for the untouched bodies. */
    private val water = """
        vec2 bilinear(vec2[4] v, float t0, float t1) {
            vec2 v0 = mix(v[0], v[1], t0);
            return mix(v0, v0, t1);
        }

        const vec2[12] DIRECTIONS = vec2[](
        vec2(0.0, -1.0)
        );

        const vec2[12] DIRECTIONS_DIAG = vec2[](
        vec2(0.0, -1.0)
        );

        vec4[4] computeWaveFactors(out vec2 globalFlow, vec2 flowDir) {
            globalFlow = u_globalFlowDir;
            return vec4[](
                vec4(globalFlow, 1.0, 0.5),
                vec4(rotateV2(globalFlow, 0.5), 0.5, 0.25),
                vec4(rotateV2(globalFlow, -0.5), 0.25, 0.125),
                vec4(rotateV2(globalFlow, 2.0), 0.125, 0.125)
            );
        }

        vec3 computeGerstnerOffset(vec3 pos, vec4[4] waves, out vec3 tangent, out vec3 binormal, float zScale, float wShift, float speed, float[4] amps, float[4] phases) {
            return pos;
        }

        void waterMain(vec3 pos, vec3 origPos, vec2 flowDir) {
            vec2 globalFlow;
            vec4[4] waves = computeWaveFactors(globalFlow, flowDir);
            vec3 offset = computeGerstnerOffset(pos.xyz, waves, tangent, binormal, u_waveScale, u_waveShift, 1.0, float[](1.0, 0.3, 0.1, 0.0), float[](0.6, 1.1, 2.3, 3.7));
            vec3 offsetFoam = computeGerstnerOffset(origPos.xyz, waves, tangent, binormal, u_foamScale, u_waveShift, 10.0, float[](0.5, 0.25, 0.125, 0.125), float[](0.0, 1.1, 2.5, 3.9));
        }
    """.trimIndent()

    private val waterRel = "terra/data/shader/lib/water.glsl"

    private fun lines(vararg lines: String): String = lines.joinToString("\n")

    @Test
    fun `every array in the water lib moves its brackets to the name`() {
        ShaderArrays.report(true)
        val served = ShaderArrays.rewrite(waterRel, water)

        assertEquals("all nine edits matched", 9, served.applied)
        assertEquals(9, served.expected)
        for (type in listOf("vec2[4]", "vec2[12]", "vec4[4]", "float[4]")) {
            assertTrue("$type is gone", !served.text.contains(type))
        }
        assertTrue("the parameter kept its size", served.text.contains("vec2 bilinear(vec2 v[4], float t0, float t1) {"))
        assertTrue("the const kept its size", served.text.contains("const vec2 DIRECTIONS[12] = vec2[]("))
        assertTrue(
            "the return type became an out parameter",
            served.text.contains(
                "void computeWaveFactors(out vec2 globalFlow, vec2 flowDir, out vec4 waves[4]) {",
            ),
        )
        assertTrue("the constructor became assignments", served.text.contains("waves[0] = vec4(globalFlow, 1.0, 0.5);"))
        assertTrue("the caller declares its own array", served.text.contains("vec4 waves[4];\n    computeWaveFactors(globalFlow, flowDir, waves);"))
        assertTrue(
            "the wave constants are global consts",
            served.text.contains("const float adaAmpsWave[4] = float[](1.0, 0.3, 0.1, 0.0);") &&
                served.text.contains("const float adaPhasesFoam[4] = float[](0.0, 1.1, 2.5, 3.9);"),
        )
        assertTrue(
            "the calls name them",
            served.text.contains("1.0, adaAmpsWave, adaPhasesWave);") &&
                served.text.contains("10.0, adaAmpsFoam, adaPhasesFoam);"),
        )
        assertTrue("cached for this device", ShaderArrays.rewrites(waterRel))
    }

    @Test
    fun `the fragment declarations and the ramp constructor are lifted too`() {
        ShaderArrays.report(true)

        val varying = "flat in vec2[4] v_flowDirs;"
        for (rel in listOf(
            "terra/data/shader/fragment/water-plane.frag",
            "terra/data/shader/fragment/plane-depth.frag",
        )) {
            val served = ShaderArrays.rewrite(rel, varying)
            assertEquals("$rel: lifted", "flat in vec2 v_flowDirs[4];", served.text)
            assertEquals(1, served.applied)
        }

        val ramp = ShaderArrays.rewrite(
            "terra/data/shader/fragment/post/analog-filter.frag",
            lines(
                "    vec3 midColor = cos(vec3(0., 1., 2.) + time) * 0.5 + 0.5;",
                "",
                "    vec3 color = colorRamp(noise.r, vec3[5](",
                "    rgb(0.0, 0.0, 20.),",
                "    rgb(37., 40., 50.),",
                "    midColor, //rgb(122., 101., 78.),",
                "    rgb(184., 170., 132.),",
                "    rgb(255., 255., 255.)",
                "    ));",
                "",
                "    return color;",
            ),
        )
        assertEquals(1, ramp.applied)
        assertTrue("only the size goes", ramp.text.contains("colorRamp(noise.r, vec3[]("))
        assertTrue("the elements are untouched", ramp.text.contains("    midColor, //rgb(122., 101., 78.),"))

        val colorUtils = ShaderArrays.rewrite(
            "terra/data/shader/lib/color-utils.glsl",
            "vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) {",
        )
        assertEquals(1, colorUtils.applied)
        assertEquals("vec3 colorRamp(float t, vec3 colors[COLOR_RAMP_COUNT]) {", colorUtils.text)
    }

    @Test
    fun `line endings are the file's own, and a second pass changes nothing`() {
        ShaderArrays.report(true)
        val lf = ShaderArrays.rewrite(waterRel, water)
        val crlf = ShaderArrays.rewrite(waterRel, water.replace("\n", "\r\n"))
        assertEquals("the file's own endings are kept", lf.text.replace("\n", "\r\n"), crlf.text)
        assertTrue("no bare newline was produced", crlf.text.contains("\r\n") && !crlf.text.replace("\r\n", "").contains("\n"))

        val again = ShaderArrays.rewrite(waterRel, lf.text)
        assertEquals("nothing left to lift", 0, again.applied)
        assertSame("idempotent", lf.text, again.text)
    }

    @Test
    fun `a device that compiles the game's declarations keeps them`() {
        ShaderArrays.report(false)
        assertFalse("nothing is lifted for a compiler that accepts them", ShaderArrays.rewriting())
        assertFalse(ShaderArrays.rewrites(waterRel))
        assertTrue("but the file is still known", ShaderArrays.lifts(waterRel))

        ShaderArrays.report(true)
        assertTrue(ShaderArrays.rewriting())
        assertTrue(ShaderArrays.rewrites(waterRel))
        assertTrue(ShaderArrays.decided())
    }

    @Test
    fun `a page that has not answered yet is not rewritten, and can still be waited for`() {
        assertFalse(ShaderArrays.decided())
        assertFalse(ShaderArrays.rewriting())
        assertFalse("the safe default is the game's own bytes", ShaderArrays.rewrites(waterRel))
        assertFalse("a timeout decides nothing", ShaderArrays.awaitReport(10))
        ShaderArrays.report(true)
        assertTrue("the answer releases the wait", ShaderArrays.awaitReport(10))
        assertTrue(ShaderArrays.rewrites(waterRel))
    }

    @Test
    fun `another build's bytes, and a non-shader, are served as they came`() {
        ShaderArrays.report(true)
        val other = "vec4[4] somethingElse(vec2 p) { return vec4[4](vec4(1.0), vec4(1.0), vec4(1.0), vec4(1.0)); }"
        val served = ShaderArrays.rewrite(waterRel, other)
        assertEquals("nothing of this file's table matched", 0, served.applied)
        assertSame("served as it came", other, served.text)
        assertTrue("but the file is expected to carry nine", served.expected > 0)

        val bundle = "const TEX_SLOT_COUNT = 256;"
        assertFalse(ShaderArrays.lifts("terra/dist/bundle.js"))
        assertEquals("a file the port does not lift has no table", 0, ShaderArrays.rewrite("terra/dist/bundle.js", bundle).expected)
        assertSame(bundle, ShaderArrays.rewrite("terra/dist/bundle.js", bundle).text)
    }
}
