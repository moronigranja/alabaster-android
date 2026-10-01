package io.github.moronigranja.alabasterdawn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Some mobile drivers' ES 3.0 front ends do not carry a shader's declared default precision onto an
 * **array type** written in the `type[size] name` spelling, and refuse the shader with
 * `S0032: no default precision defined for variable 'vec4[4]'` — the type, not the variable.
 *
 * Reported on a Poco X7 Pro (Mali-G720, Android 16, WebView 155 beta): the boot froze at 90-99 % with
 * three shaders pending, and the record carried
 *
 * ```
 * ENGINE console.groupCollapsed: Shader Errors: data/shader/fragment/post/analog-filter.frag
 * ENGINE console.groupCollapsed: 0:62: S0032: no default precision defined for variable 'vec3[5]'
 * ENGINE console.groupCollapsed: Shader Errors: data/shader/fragment/water-fx-wall.frag
 * ENGINE console.groupCollapsed: 0:308: S0032: no default precision defined for variable 'vec4[4]'
 * ENGINE console.groupCollapsed: Shader Errors: data/shader/fragment/water-plane.frag
 * ENGINE console.groupCollapsed: 0:275: S0032: no default precision defined for variable 'vec4[4]'
 * ```
 *
 * and a `boot stall … pending 3 (shader=3 …)` in the same record (FINDINGS §22). The declarations it
 * names are `vec3[5](…)` (a constructor argument in `analog-filter.frag`) and `vec4[4]` — a function
 * return type, a parameter and a local in `lib/water.glsl`, which both water fragment shaders import.
 * Nothing in those bytes is invalid ES 3.0: `glslangValidator` accepts all 39 served fragment shaders
 * with the port's rewrites applied, and so does ANGLE (§21.2). The default precision is declared in
 * every one of them (`precision mediump float;`), which is what the driver says it cannot find.
 *
 * The port therefore **lifts** the offending declarations into the *declarator* spelling, which is
 * the one shape the same driver is known to accept: the game's own vertex shaders write
 * `flat out vec2 v_flowDirs[4];`, and the global `const float[] BLUR10KERNEL = float[](…)` in
 * `lib/blur.glsl` — reached by `gui-blur.frag`, which compiles on that device — is the same
 * declaration with the array on the name. Nothing else about a shader changes: the base type, the
 * qualifiers, the precision and the values are the game's.
 *
 * Four constructs cannot simply move their brackets:
 *
 * * `vec4[4] computeWaveFactors(…)` is a **return type**, and ES 3.0 requires the size on a return
 *   type (there is no `vec4 f()[4]`), so it becomes an `out vec4 waves[4]` parameter and the caller
 *   declares `vec4 waves[4];` — the single call site is rewritten with it.
 * * `return vec4[](…)` is an array **constructor in an expression**, which has no precision to give,
 *   so it becomes four assignments to that parameter.
 * * the two `float[](…)` argument lists are constants, so they are hoisted to global `const float
 *   x[4] = float[](…)` declarations — the exact shape the device is known to compile.
 * * the post pass's `colorRamp(noise.r, vec3[5](…))` **argument** is an array *temporary*, and a
 *   temporary has no element precision to inherit in any spelling of its brackets — the device refused
 *   `vec3[5](`, `vec3[](` and every qualifier on the parameter alike — so the ramp is named
 *   (`vec3 adaRamp[5];`) and filled by assignment, which is the one repair measured to compile there
 *   (§22.12).
 *
 * Only the served bytes are rewritten, and only when the page's own GL stack rejects the constructs
 * ([report]): a device that compiles the game's declarations is served them, byte for byte.
 */
object ShaderArrays {

    /** A served shader's rewrite: what the game's bytes say, and what the port serves instead. */
    private class Edit(val find: String, val replace: String)

    /** The served text of a rewritten shader, and how much of its table actually matched. */
    data class Served(val text: String, val applied: Int, val expected: Int)

    /** What the page's GL stack reported through `AdaBridge.setShaderArrays`; null until it answers. */
    @Volatile
    var needed: Boolean? = null
        private set

    /** Released by [report]: the page has answered (or the wait gave up on it). */
    private val reported = AtomicReference(CountDownLatch(1))

    /** Records what the page's own compiler did with the game's declarations, and releases [awaitReport]. */
    fun report(needed: Boolean) {
        this.needed = needed
        reported.get().countDown()
    }

    /** Whether the page has answered yet. A page that never answers keeps the game's own bytes. */
    fun decided(): Boolean = needed != null

    /**
     * Waits up to [timeoutMs] for the page's answer, so the first shader is served with it in hand.
     * The answer comes from the document-start probe (see the shim), which runs before the engine asks
     * for anything.
     */
    fun awaitReport(timeoutMs: Long): Boolean = reported.get().await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Whether this device needs the lift at all. */
    fun rewriting(): Boolean = needed == true

    /** Whether [rel] is a shader the port lifts (whatever this device answered). */
    fun lifts(rel: String): Boolean = EDITS.containsKey(rel)

    /**
     * The port's own edits for [rel] — the table this file owns, for the shader self-test
     * ([ShaderVariants]) and the case generator (`tools/mali-probe/`), so none of them can drift from
     * what the port serves.
     */
    internal fun edits(rel: String): List<Pair<String, String>> =
        EDITS[rel].orEmpty().map { it.find to it.replace }

    /** Whether [rel] is one of the shaders the port lifts on a device that needs it — so worth caching. */
    fun rewrites(rel: String): Boolean = rewriting() && lifts(rel)

    /** Tests only: back to "the page has not answered". */
    internal fun reset() {
        needed = null
        reported.set(CountDownLatch(1))
    }

    /**
     * The served text of [rel]: the game's own bytes, or every edit [EDITS] carries for it applied.
     * A file whose bytes do not match (another build of the game) is served as it came, and the count
     * of applied edits says so — a partial match would be worse than none.
     */
    fun rewrite(rel: String, text: String): Served {
        val edits = EDITS[rel] ?: return Served(text, 0, 0)
        val expected = edits.size
        val crlf = text.contains("\r\n")
        var out = if (crlf) text.replace("\r\n", "\n") else text
        var applied = 0
        for (edit in edits) {
            val found = out.split(edit.find).size - 1
            if (found == 0) continue
            applied += found
            out = out.replace(edit.find, edit.replace)
        }
        if (applied == 0) return Served(text, 0, expected)
        return Served(if (crlf) out.replace("\n", "\r\n") else out, applied, expected)
    }

    private fun lines(vararg lines: String): String = lines.joinToString("\n")

    /**
     * The shaders the port lifts, and what it lifts. Every entry is the game's own text (lifted
     * verbatim from a copy of build `0.1.0-10`): a file the port does not know is served untouched.
     *
     * Only the fragment stage is here. The same constructs compile in the vertex stage on the
     * reporting device — all twelve `.vert` shaders loaded, and the failing device's record names
     * three fragment shaders — so the port leaves every vertex shader alone.
     */
    private val EDITS: Map<String, List<Edit>> = mapOf(

        /* The ramp the analogue film pass builds as a constructor argument. An array *temporary* has no
         * element precision to inherit, so this driver refuses it whatever the brackets say: on the
         * reporting device (Mali-G720, driver 49.1.0) `vec3[5](` and the unsized `vec3[](` both came
         * back `0:62: S0032: no default precision defined for variable 'vec3[5]'`, and so did moving
         * the parameter's brackets (FINDINGS §22.12). The one repair measured to compile there is to
         * name the array and assign it element by element — the game's own five values, in its order. */
        "terra/data/shader/fragment/post/analog-filter.frag" to listOf(
            Edit(
                find = lines(
                    "    vec3 color = colorRamp(noise.r, vec3[5](",
                    "    rgb(0.0, 0.0, 20.),",
                    "    rgb(37., 40., 50.),",
                    "    midColor, //rgb(122., 101., 78.),",
                    "    rgb(184., 170., 132.),",
                    "    rgb(255., 255., 255.)",
                    "    ));",
                ),
                replace = lines(
                    "    vec3 adaRamp[5];",
                    "    adaRamp[0] = rgb(0.0, 0.0, 20.);",
                    "    adaRamp[1] = rgb(37., 40., 50.);",
                    "    adaRamp[2] = midColor;",
                    "    adaRamp[3] = rgb(184., 170., 132.);",
                    "    adaRamp[4] = rgb(255., 255., 255.);",
                    "    vec3 color = colorRamp(noise.r, adaRamp);",
                ),
            ),
        ),

        /* The varying's fragment declaration, written in the spelling its own vertex shader uses, and the
         * default precision raised to the vertex stage's. The two water programs failed to *link* on the
         * reporting device once these fragments compiled:
         *
         *   Unable to initialize the shader program vertex/water-plane.vert + fragment/water-plane.frag:
         *   Uniforms with the same name but different type/precision: u_waveHeight
         *
         * `u_waveHeight` and `u_cameraProjM` are declared in `lib/water.glsl` with no precision of their
         * own, so they take the default: highp in the vertex language (ES 3.0 predeclares it there), and
         * mediump in a fragment that says `precision mediump float;`. The same phone's owner had fixed the
         * water by raising exactly this line, which is the one repair measured to work (§22.13). */
        "terra/data/shader/fragment/water-plane.frag" to listOf(
            Edit("flat in vec2[4] v_flowDirs;", "flat in vec2 v_flowDirs[4];"),
            Edit("precision mediump float;", "precision highp float;"),
        ),
        "terra/data/shader/fragment/plane-depth.frag" to listOf(
            Edit("flat in vec2[4] v_flowDirs;", "flat in vec2 v_flowDirs[4];"),
        ),
        "terra/data/shader/fragment/water-fx-wall.frag" to listOf(
            Edit("precision mediump float;", "precision highp float;"),
        ),

        /* `colorRamp`'s parameter carries a macro size; the array moves to the name. Reached by every
         * fragment shader that imports `lib/color-utils.glsl`, and by no vertex shader. */
        "terra/data/shader/lib/color-utils.glsl" to listOf(
            Edit(
                "vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) {",
                "vec3 colorRamp(float t, vec3 colors[COLOR_RAMP_COUNT]) {",
            ),
        ),

        /* The two water fragment shaders' whole wave path, imported from `lib/water.glsl`. */
        "terra/data/shader/lib/water.glsl" to listOf(
            Edit(
                "vec2 bilinear(vec2[4] v, float t0, float t1) {",
                "vec2 bilinear(vec2 v[4], float t0, float t1) {",
            ),
            Edit(
                "const vec2[12] DIRECTIONS = vec2[](",
                "const vec2 DIRECTIONS[12] = vec2[](",
            ),
            Edit(
                "const vec2[12] DIRECTIONS_DIAG = vec2[](",
                "const vec2 DIRECTIONS_DIAG[12] = vec2[](",
            ),
            /* The four wave constants the calls below pass as `float[](…)`: hoisted to the global
             * `const float x[4] = float[](…)` shape, which this device is known to compile. */
            Edit(
                find = "vec3 computeGerstnerOffset(vec3 pos, vec4[4] waves, out vec3 tangent, out vec3 binormal, float zScale, float wShift, float speed, float[4] amps, float[4] phases) {",
                replace = lines(
                    "const float adaAmpsWave[4] = float[](1.0, 0.3, 0.1, 0.0);",
                    "const float adaPhasesWave[4] = float[](0.6, 1.1, 2.3, 3.7);",
                    "const float adaAmpsFoam[4] = float[](0.5, 0.25, 0.125, 0.125);",
                    "const float adaPhasesFoam[4] = float[](0.0, 1.1, 2.5, 3.9);",
                    "",
                    "vec3 computeGerstnerOffset(vec3 pos, vec4 waves[4], out vec3 tangent, out vec3 binormal, float zScale, float wShift, float speed, float amps[4], float phases[4]) {",
                ),
            ),
            Edit(
                "float[](1.0, 0.3, 0.1, 0.0), float[](0.6, 1.1, 2.3, 3.7)",
                "adaAmpsWave, adaPhasesWave",
            ),
            Edit(
                "float[](0.5, 0.25, 0.125, 0.125), float[](0.0, 1.1, 2.5, 3.9)",
                "adaAmpsFoam, adaPhasesFoam",
            ),
            /* The one array return type in the game: ES 3.0 needs the size on a return type, so the
             * array travels out through a parameter and the constructor becomes assignments. */
            Edit(
                "vec4[4] computeWaveFactors(out vec2 globalFlow, vec2 flowDir) {",
                "void computeWaveFactors(out vec2 globalFlow, vec2 flowDir, out vec4 waves[4]) {",
            ),
            Edit(
                find = lines(
                    "    return vec4[](",
                    "        vec4(globalFlow, 1.0, 0.5),",
                    "        vec4(rotateV2(globalFlow, 0.5), 0.5, 0.25),",
                    "        vec4(rotateV2(globalFlow, -0.5), 0.25, 0.125),",
                    "        vec4(rotateV2(globalFlow, 2.0), 0.125, 0.125)",
                    "    );",
                ),
                replace = lines(
                    "    waves[0] = vec4(globalFlow, 1.0, 0.5);",
                    "    waves[1] = vec4(rotateV2(globalFlow, 0.5), 0.5, 0.25);",
                    "    waves[2] = vec4(rotateV2(globalFlow, -0.5), 0.25, 0.125);",
                    "    waves[3] = vec4(rotateV2(globalFlow, 2.0), 0.125, 0.125);",
                ),
            ),
            Edit(
                find = "    vec4[4] waves = computeWaveFactors(globalFlow, flowDir);",
                replace = lines(
                    "    vec4 waves[4];",
                    "    computeWaveFactors(globalFlow, flowDir, waves);",
                ),
            ),
        ),
    )
}
