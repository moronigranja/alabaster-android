package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shader self-test's cases (FINDINGS §22.11): the game's own fragment shaders, expanded the engine's
 * way, each in a spelling the port could serve — so a device can say which one *its* front end takes.
 *
 * The fixture carries the post pass's own call and parameter verbatim (they are what the cases edit) and
 * builds the water files out of the port's own edit table: what these tests check is the pipeline
 * (expansion, edits, `#define`s), while the game's real bytes are verified by `glslang`, the boot A/B
 * through the shim (§22.6) and the device probe (§22.10).
 */
class ShaderVariantsTest {

    private val files = mutableMapOf<String, String>()

    private val read: (String) -> String? = { files[it] }

    private fun fixture() {
        files["fragment/post/analog-filter.frag"] = """
            #version 300 es
            precision mediump float;
            #import "lib/color-utils";
            in vec2 v_texCoord;
            vec3 recolor(vec4 noise, float time) {
                vec3 midColor = vec3(0.5);
                vec3 color = colorRamp(noise.r, vec3[5](
                rgb(0.0, 0.0, 20.),
                rgb(37., 40., 50.),
                midColor, //rgb(122., 101., 78.),
                rgb(184., 170., 132.),
                rgb(255., 255., 255.)
                ));
                return color;
            }
            void main() { }
        """.trimIndent() + "\n"
        files["lib/color-utils.glsl"] = """
            vec3 rgb(float r, float g, float b) { return vec3(r, g, b) / 255.0; }
            #ifndef COLOR_RAMP_COUNT
            #define COLOR_RAMP_COUNT 5
            #endif
            vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) {
                return colors[1];
            }
        """.trimIndent() + "\n"
        for (rel in listOf(
            "fragment/water-plane.frag",
            "fragment/water-fx-wall.frag",
            "fragment/plane-depth.frag",
        )) {
            /* The varying the lift respells, in the game's own spelling: `water-plane` and
             * `plane-depth` carry an edit for it, `water-fx-wall` only the define. */
            files[rel] = "#version 300 es\nprecision mediump float;\n#import \"lib/water\";\n" +
                "flat in vec2[4] v_flowDirs;\nvoid main() { }\n"
        }
        files["lib/water.glsl"] = ShaderArrays.edits(WATER_LIB).joinToString("\n") { it.first } + "\n"
    }

    @Test
    fun `the cases are the shaders the lift touches, each spelling of the post pass`() {
        val names = ShaderVariants.names()
        assertEquals(names.size, names.toSet().size)
        for (expected in listOf(
            "analog-filter~original",
            "analog-filter~lifted",
            "analog-filter~lifted-ctor-only",
            "analog-filter~lifted-param-only",
            "analog-filter~local-ramp",
            "analog-filter~global-ramp",
            "analog-filter~mediump-param",
            "analog-filter~highp-param",
            "water-plane~original",
            "water-plane~lifted",
            "water-fx-wall~original",
            "water-fx-wall~lifted",
            "plane-depth~original",
            "plane-depth~lifted",
        )) {
            assertTrue("missing $expected", expected in names)
        }
    }

    @Test
    fun `the original expands its imports and keeps the game's spelling`() {
        fixture()
        val text = ShaderVariants.text("analog-filter~original", read)!!
        assertTrue("the import is inlined", text.contains("vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) {"))
        assertTrue("the game's constructor is kept", text.contains("colorRamp(noise.r, vec3[5]("))
        assertFalse("nothing is invented", text.contains("adaRamp"))
    }

    @Test
    fun `the lifted case is the port's own lift, both halves`() {
        fixture()
        val text = ShaderVariants.text("analog-filter~lifted", read)!!
        assertTrue(text.contains("colorRamp(noise.r, vec3[]("))
        assertTrue(text.contains("vec3 colorRamp(float t, vec3 colors[COLOR_RAMP_COUNT]) {"))
        assertFalse("the sized spelling is gone", text.contains("vec3[5]("))
    }

    @Test
    fun `each half of the lift is its own case`() {
        fixture()
        val constructor = ShaderVariants.text("analog-filter~lifted-ctor-only", read)!!
        assertTrue(constructor.contains("colorRamp(noise.r, vec3[]("))
        assertTrue("the parameter is left as the game writes it", constructor.contains("vec3[COLOR_RAMP_COUNT] colors"))

        val parameter = ShaderVariants.text("analog-filter~lifted-param-only", read)!!
        assertTrue(parameter.contains("vec3 colorRamp(float t, vec3 colors[COLOR_RAMP_COUNT]) {"))
        assertTrue("the call is left as the game writes it", parameter.contains("colorRamp(noise.r, vec3[5]("))
    }

    @Test
    fun `the candidate repairs replace the whole call`() {
        fixture()
        val local = ShaderVariants.text("analog-filter~local-ramp", read)!!
        assertTrue(local.contains("vec3 adaRamp[5];"))
        assertTrue(local.contains("colorRamp(noise.r, adaRamp)"))
        assertFalse(local.contains("vec3[5]("))

        val global = ShaderVariants.text("analog-filter~global-ramp", read)!!
        assertTrue(global.contains("const vec3 adaRamp[5] = vec3[]("))
        assertTrue(global.contains("colorRamp(noise.r, adaRamp)"))
        assertTrue(
            "the const follows the precision it needs",
            global.indexOf("const vec3 adaRamp") > global.indexOf("precision mediump float;"),
        )
    }

    @Test
    fun `the precision qualifier cases keep the sized spelling`() {
        fixture()
        for ((name, qualifier) in listOf(
            "analog-filter~mediump-param" to "mediump",
            "analog-filter~highp-param" to "highp",
        )) {
            val text = ShaderVariants.text(name, read)!!
            assertTrue(text.contains("vec3 colorRamp(float t, $qualifier vec3[COLOR_RAMP_COUNT] colors) {"))
            assertTrue("the call is untouched", text.contains("colorRamp(noise.r, vec3[5]("))
        }
    }

    @Test
    fun `the water cases lift the port's whole table, and the define lands before the code`() {
        fixture()
        val original = ShaderVariants.text("water-fx-wall~original", read)!!
        assertTrue(original.contains("#define WATERFALL_SHADER"))
        assertTrue(
            "the define is after the precision, like the engine's own",
            original.indexOf("#define WATERFALL_SHADER") > original.indexOf("precision mediump float;"),
        )
        assertTrue("the game's water text is kept", original.contains("vec4[4] computeWaveFactors"))

        val lifted = ShaderVariants.text("water-plane~lifted", read)!!
        for ((find, replace) in ShaderArrays.edits(WATER_LIB)) {
            assertFalse("still finds: $find", lifted.contains(find))
            assertTrue("missing: $replace", lifted.contains(replace))
        }
        assertTrue("the varying moves to the name", lifted.contains("flat in vec2 v_flowDirs[4];"))
        assertFalse(lifted.contains("flat in vec2[4] v_flowDirs;"))
    }

    @Test
    fun `an unknown case, or a file that cannot be read, is nothing to serve`() {
        fixture()
        assertNull(ShaderVariants.text("analog-filter~no-such-variant", read))
        assertNull(ShaderVariants.text("analog-filter~lifted", { null }))
        assertNull(ShaderVariants.text("analog-filter~lifted-ctor-only", { null }))
    }

    private companion object {
        const val WATER_LIB = "terra/data/shader/lib/water.glsl"
    }
}
