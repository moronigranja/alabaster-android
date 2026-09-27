package io.github.moronigranja.alabasterdawn

import org.json.JSONObject

/**
 * Which build of the *game* the user's own files are, for the diagnostics record. An Early Access
 * title ships often, and a moved asset or a changed bundle is indistinguishable from a port bug
 * without it. Two sources, both the game's own: the changelog document (available as soon as the
 * tree is indexed) and the version the engine inlined into `bundle.js` - the numbers the game itself
 * compares against the changelog ("Version collision!") and the only source that carries the hotfix.
 * Pure string parsing; nothing is read here.
 */
object GameVersion {

    /** `entries[0].version` of the changelog document - the newest release - or null. */
    fun fromChangelog(json: String?): String? {
        if (json == null) return null
        return try {
            JSONObject(json)
                .getJSONArray("entries").getJSONObject(0).getString("version")
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The engine's own version string out of the bundle: `major.minor.patch[-hotfix][ suffix]`,
     * exactly as the engine's `getVersionString()`/`toString()` build it (hotfix `0` adds nothing;
     * an empty suffix adds neither). Null when the bundle is not the shape this was measured against
     * (a game update can change it), in which case the changelog version is what the record shows.
     */
    fun fromBundle(bundle: String): String? {
        val start = bundle.indexOf(VERSION_MANAGER)
        if (start < 0) return null
        val end = bundle.indexOf("getVersionString", start)
        if (end < 0 || end - start > MAX_BLOCK_CHARS) return null
        val block = bundle.substring(start, end)
        val numbers = NUMBERS.findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
        val major = numbers["major"] ?: return null
        val minor = numbers["minor"] ?: return null
        val patch = numbers["patch"] ?: return null
        val hotfix = numbers["hotfix"]?.toIntOrNull() ?: 0
        val suffix = SUFFIX.find(block)?.groupValues?.get(1)
        return buildString {
            append(major).append('.').append(minor).append('.').append(patch)
            if (hotfix > 0) append('-').append(hotfix)
            if (!suffix.isNullOrEmpty()) append(' ').append(suffix)
        }
    }

    /** The class the four numbers and the suffix live in; the block ends at its first method. */
    private const val VERSION_MANAGER = "class VersionManager {"
    private const val MAX_BLOCK_CHARS = 4000
    private val NUMBERS = Regex("""this\.(major|minor|patch|hotfix)\s*=\s*(\d+);""")
    private val SUFFIX = Regex("""this\.suffix\s*=\s*"([^"]*)";""")
}
