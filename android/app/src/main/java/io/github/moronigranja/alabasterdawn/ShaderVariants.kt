package io.github.moronigranja.alabasterdawn

/**
 * The shader self-test's cases: the game's own fragment shaders, **expanded the engine's way**, each in
 * the spelling the port could serve, so a device can say which spelling its own front end accepts
 * (FINDINGS §22.10, §22.11).
 *
 * Why it exists: the Mali refusal this project chases is a property of the *driver revision*, not of a
 * GPU generation — four Mali generations probed from a Firebase Test Lab run all accept the game's own
 * bytes, while the reporting phone (Mali-G720, driver r49) refuses them (FINDINGS §22.10). The only
 * refusing front end reachable is a user's, so the port asks *its* device, in the right stack (the
 * WebView's own context), and puts the verdicts in the record the user already knows how to send.
 *
 * The cases are served to the page over a reserved path (`ada-variants/…`, see [GameAssetHandler]) and
 * compiled by the shim; the texts never leave the device. Every text is built from the game's own bytes
 * on the user's disk, and every edit comes from [ShaderArrays]' table or is defined here — the tests
 * assert that each edit's find-string is present, so nothing can silently do nothing.
 */
object ShaderVariants {

    /** The index the page asks for first: every case name, one per line. */
    const val INDEX = "ada-variants"

    /** What a case is: a file, and the edits that spell it the way this case asks. */
    private class Case(
        val name: String,
        val file: String,
        /** Applied to every file the expansion touches, as the port's lift is ([ShaderArrays]). */
        val lift: Boolean = false,
        /** Applied to the expanded text of [file]: the candidate spellings of one declaration. */
        val edits: List<Pair<String, String>> = emptyList(),
        /** Text prepended after the shader's own `#version`/`precision` preamble. */
        val prefix: String = "",
    )

    /* The post pass's declaration, as the file writes it: a parameter sized by a macro, called with a
     * sized constructor. Each candidate below changes one half, or the whole call. */
    private const val RAMP_PARAM = "vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) {"
    private const val RAMP_CALL = "colorRamp(noise.r, vec3[5]("
    private const val RAMP_BLOCK =
        "    vec3 color = colorRamp(noise.r, vec3[5](\n" +
            "    rgb(0.0, 0.0, 20.),\n" +
            "    rgb(37., 40., 50.),\n" +
            "    midColor, //rgb(122., 101., 78.),\n" +
            "    rgb(184., 170., 132.),\n" +
            "    rgb(255., 255., 255.)\n" +
            "    ));"
    private const val RAMP_LOCAL =
        "    vec3 adaRamp[5];\n" +
            "    adaRamp[0] = rgb(0.0, 0.0, 20.);\n" +
            "    adaRamp[1] = rgb(37., 40., 50.);\n" +
            "    adaRamp[2] = midColor;\n" +
            "    adaRamp[3] = rgb(184., 170., 132.);\n" +
            "    adaRamp[4] = rgb(255., 255., 255.);\n" +
            "    vec3 color = colorRamp(noise.r, adaRamp);"
    private const val RAMP_GLOBAL =
        "const vec3 adaRamp[5] = vec3[](" +
            "vec3(0.0, 0.0, 0.07843137255), vec3(0.14509803922, 0.15686274510, 0.0)," +
            " vec3(0.47843137255, 0.39607843137, 0.30588235294)," +
            " vec3(0.72156862745, 0.66666666667, 0.51764705882), vec3(1.0, 1.0, 1.0));\n"

    private const val ANALOG = "fragment/post/analog-filter.frag"

    private val CASES: List<Case> = listOf(
        /* The post pass: the game's bytes, the port's lift, each half of it alone, and every candidate
         * spelling the probes measured to compile elsewhere (FINDINGS §22.10). */
        Case("analog-filter~original", ANALOG),
        Case("analog-filter~lifted", ANALOG, lift = true),
        Case(
            "analog-filter~lifted-ctor-only", ANALOG,
            edits = listOf(RAMP_CALL to "colorRamp(noise.r, vec3[]("),
        ),
        Case(
            "analog-filter~lifted-param-only", ANALOG,
            edits = listOf(RAMP_PARAM to "vec3 colorRamp(float t, vec3 colors[COLOR_RAMP_COUNT]) {"),
        ),
        Case("analog-filter~local-ramp", ANALOG, edits = listOf(RAMP_BLOCK to RAMP_LOCAL)),
        Case(
            "analog-filter~global-ramp", ANALOG,
            edits = listOf(RAMP_BLOCK to "    vec3 color = colorRamp(noise.r, adaRamp);"),
            prefix = RAMP_GLOBAL,
        ),
        Case(
            "analog-filter~mediump-param", ANALOG,
            edits = listOf(RAMP_PARAM to "vec3 colorRamp(float t, mediump vec3[COLOR_RAMP_COUNT] colors) {"),
        ),
        Case(
            "analog-filter~highp-param", ANALOG,
            edits = listOf(RAMP_PARAM to "vec3 colorRamp(float t, highp vec3[COLOR_RAMP_COUNT] colors) {"),
        ),
        /* The water family, whose lift already boots on the reporting device: the two spellings, so a
         * device that starts refusing them is seen the same way. */
        Case("water-plane~original", "fragment/water-plane.frag"),
        Case("water-plane~lifted", "fragment/water-plane.frag", lift = true),
        Case("water-fx-wall~original", "fragment/water-fx-wall.frag"),
        Case("water-fx-wall~lifted", "fragment/water-fx-wall.frag", lift = true),
        Case("plane-depth~original", "fragment/plane-depth.frag"),
        Case("plane-depth~lifted", "fragment/plane-depth.frag", lift = true),
    )

    /** The `#define`s the engine inserts before compiling a file (its `insertDefinitions`). */
    private val DEFINES = mapOf("fragment/water-fx-wall.frag" to "WATERFALL_SHADER")

    private val IMPORT = Regex("""^\s*#import\s+"([^"]+)"\s*;?\s*$""")

    /** Every case name, in the order the page should try them. */
    fun names(): List<String> = CASES.map { it.name }

    /**
     * The text of [name]: the named file expanded, with the case's edits applied. [read] serves a
     * shader file by its path below `terra/data/shader/` — the game's own bytes, not the port's
     * rewrites, because the cases are about the spellings the port *could* serve.
     *
     * Returns null for an unknown name or a file that cannot be read, which the caller reports.
     */
    fun text(name: String, read: (String) -> String?): String? {
        val case = CASES.firstOrNull { it.name == name } ?: return null
        val seen = mutableSetOf<String>()
        var expanded = expand(case.file, case, read, seen) ?: return null
        for ((find, replace) in case.edits) {
            if (!expanded.contains(find)) return null
            expanded = expanded.replace(find, replace)
        }
        if (case.prefix.isNotEmpty()) expanded = insertAfterPreamble(expanded, case.prefix)
        return DEFINES[case.file]?.let { define(expanded, it) } ?: expanded
    }

    /** The engine's own `#import "lib/x";` expansion: the file, then its imports in place. */
    private fun expand(
        rel: String,
        case: Case,
        read: (String) -> String?,
        seen: MutableSet<String>,
        depth: Int = 0,
    ): String? {
        var body = read(rel) ?: return null
        if (case.lift) {
            /* The lift's table is keyed by the **served** path, which carries the tree's own prefix. */
            for ((find, replace) in ShaderArrays.edits(SERVED_PREFIX + rel)) {
                if (!body.contains(find)) return null
                body = body.replace(find, replace)
            }
        }
        val out = StringBuilder()
        for (line in body.split("\n")) {
            val match = IMPORT.matchEntire(line)
            if (match != null && depth < MAX_IMPORT_DEPTH) {
                val imported = resolve(match.groupValues[1], read)
                if (imported == null) {
                    out.append("// missing import ").append(match.groupValues[1]).append('\n')
                } else if (seen.add(imported)) {
                    out.append(expand(imported, case, read, seen, depth + 1)).append('\n')
                }
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    /** `lib/x` may be shipped as `lib/x.glsl` (the game's own files are). */
    private fun resolve(name: String, read: (String) -> String?): String? =
        when {
            read(name) != null -> name
            read("$name.glsl") != null -> "$name.glsl"
            else -> null
        }

    /** The engine's `insertDefinitions`: the `#define` goes before anything that can read it. */
    private fun define(text: String, name: String): String =
        insertAfterPreamble(text, "#define $name")

    /** A line after `#version`/`precision` — a float literal before the default precision is an error. */
    private fun insertAfterPreamble(text: String, line: String): String {
        val lines = text.split("\n").toMutableList()
        var at = -1
        for ((i, l) in lines.withIndex()) {
            if (l.trimStart().startsWith("#version")) at = i + 1
            else if (l.trimStart().startsWith("precision")) {
                at = i + 1
                break
            }
        }
        if (at < 0) return line + "\n" + text
        lines.add(at, line)
        return lines.joinToString("\n")
    }

    private const val MAX_IMPORT_DEPTH = 12

    /** The tree prefix the port's own lift table is keyed by (`ShaderArrays`). */
    private const val SERVED_PREFIX = "terra/data/shader/"
}
