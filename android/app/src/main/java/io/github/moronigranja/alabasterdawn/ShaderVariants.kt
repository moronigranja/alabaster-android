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
 * on the user's disk, and every edit comes from [ShaderArrays]' table — the one the port actually serves
 * — so a case cannot drift from it.
 */
object ShaderVariants {

    /** The index the page asks for first: every case name, one per line. */
    const val INDEX = "ada-variants"

    /** What a case is: a file, and whether the port's lift is applied to it. */
    private class Case(
        val name: String,
        val file: String,
        /** Applied to every file the expansion touches, as the port's lift is ([ShaderArrays]). */
        val lift: Boolean = false,
    )

    private const val ANALOG = "fragment/post/analog-filter.frag"

    private val CASES: List<Case> = listOf(
        /* Each lifted file twice: the game's own bytes, and the bytes the port would serve — so a
         * device that starts refusing either spelling is seen in one line of the record. */
        Case("analog-filter~original", ANALOG),
        Case("analog-filter~lifted", ANALOG, lift = true),
        /* The water family, whose lift boots on the reporting device. */
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
        val expanded = expand(case.file, case, read, seen) ?: return null
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
        /* The game's files can be CRLF (a copy made on Windows, or moved through one): the port's own
         * rewrite normalises them the same way, and the self-test's edits have to match, not the line
         * endings. A run on the maintainer's phone found this: the single-line edits matched and the
         * multi-line ones did not, and a nested failure then put the word `null` in the shader. */
        var body = read(rel)?.replace("\r\n", "\n") ?: return null
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
                    /* A nested file that cannot be read or lifted fails **this case**: the caller
                     * serves nothing rather than half a shader, and says why. */
                    val nested = expand(imported, case, read, seen, depth + 1) ?: return null
                    out.append(nested).append('\n')
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
