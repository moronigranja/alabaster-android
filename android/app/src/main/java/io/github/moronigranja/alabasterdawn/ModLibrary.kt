package io.github.moronigranja.alabasterdawn

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The on-device CCLoader and the mods installed under it.
 *
 * Everything CCLoader needs is one game-root subtree: the loader itself under `ccloader/` and one
 * directory per mod under `assets/mods/<id>/` (CCLoader's own default `modsDirs`). Mods own their id
 * directory and never collide, so this is a **single root plus a directory convention**, not an
 * overlay filesystem:
 *
 * ```
 * files/cc/
 *   ccloader/           the loader (main.html, dist/, common/, runtime/)
 *   assets/mods/<id>/   one directory per enabled mod
 *   disabled/<id>/      a mod moved out of `assets/mods/` to turn it off
 * ```
 *
 * Two install shapes are accepted from the same code path:
 *  * the pinned **CrossCode-Esp distribution** (downloaded, SHA-256 verified) — it carries the loader
 *    plus its own mods, exactly the layout above;
 *  * a **user-picked archive** — a `.ccmod`/`.zip` that is one mod (a root `ccmod.json`), a raw
 *    CCLoader build (a root `main.html`), or a distribution (a root `ccloader/` and/or
 *    `assets/mods/<id>/ccmod.json`). A single wrapper directory is stripped.
 *
 * The loader is what makes the port boot the game through CCLoader; [locale] forces a language only
 * while the pack's own mod is installed. Nothing here reads or writes the picked game folder.
 */
object ModLibrary {

    /** The CrossCode-Esp v1.1.0 distribution: the loader plus the community Spanish translation. */
    const val CC_ESP_URL =
        "https://github.com/tralph3/CrossCode-Esp/releases/download/v1.1.0/CrossCode-Esp_v1.1.0.zip"
    const val CC_ESP_SHA256 = "d541a543e437f8ec6986b74c3df9a1fc982bd9daff6ee72a0ea044cba7edc14d"
    const val CC_ESP_SIZE = 2537080L
    const val CC_ESP_VERSION = "1.1.0"
    /** The three mods that distribution installs. */
    val CC_ESP_MODS = listOf("CrossCode-Esp", "Localize-me", "ultimate-localized-ui")

    /**
     * The locale the CrossCode-Esp mod provides. `CrossCode-Esp` adds it to the game's language table
     * (via `Localize-me`) but does not select it, and the game picks `ig.currentLang` from
     * `ig.FORCE_LANG` → `window.IG_LANG` → stored `IG_LANG` → auto-detect. The port sets
     * `window.IG_LANG` on the CCLoader entry page while this mod is installed.
     */
    const val LOCALE = "es_ES"
    const val LOCALE_MOD = "CrossCode-Esp"

    /** Where the library lives under `filesDir`. */
    const val DIR = "cc"

    const val LOADER_DIR = "ccloader"
    const val MODS_DIR = "assets/mods"
    const val DISABLED_DIR = "disabled"

    /** A raw CCLoader build's entry page, at the archive root. */
    private const val LOADER_ENTRY_NAME = "main.html"
    /** The loader's entry page, relative to the library root. */
    private const val LOADER_ENTRY = "$LOADER_DIR/$LOADER_ENTRY_NAME"
    private const val LOADER_METADATA = "$LOADER_DIR/metadata.json"
    private const val MOD_MANIFEST = "ccmod.json"

    private const val TAG = "RfPort"
    private const val TIMEOUT_MS = 15000
    private const val COPY_BUFFER = 8192

    /** One installed mod, from its own `ccmod.json`. */
    data class InstalledMod(
        val id: String,
        val title: String,
        val version: String,
        val enabled: Boolean,
    )

    /** What an archive turned out to be. */
    data class Installed(val loader: Boolean, val mods: List<String>)

    fun root(ctx: Context): File = File(ctx.filesDir, DIR)

    fun loaderInstalled(ctx: Context): Boolean = loaderInstalled(root(ctx))

    internal fun loaderInstalled(root: File): Boolean = File(root, LOADER_ENTRY).isFile

    /** The served view of the library, or null when no loader is installed. */
    fun open(ctx: Context): ModRoot? = open(root(ctx))

    internal fun open(root: File): ModRoot? =
        root.takeIf { File(it, LOADER_ENTRY).isFile }?.let { ModRoot(it) }

    /** The loader's own version, from its `metadata.json`; null before it is installed. */
    fun loaderVersion(ctx: Context): String? = loaderVersion(root(ctx))

    internal fun loaderVersion(root: File): String? = try {
        val text = File(root, LOADER_METADATA).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        text?.let { JSONObject(it).optString("version", "").takeIf { v -> v.isNotEmpty() } }
    } catch (e: Exception) {
        null
    }

    /** The locale to force on the game, or null. Only the pack's own mod turns Spanish on. */
    fun locale(ctx: Context): String? = locale(root(ctx))

    internal fun locale(root: File): String? =
        if (File(root, "$MODS_DIR/$LOCALE_MOD/$MOD_MANIFEST").isFile) LOCALE else null

    /**
     * Removes the loader and every mod the CrossCode-Esp distribution installs, leaving mods the user
     * added themselves. (Without a loader none of them can run — the Mods dialog says so.)
     */
    fun removeDistribution(ctx: Context) = removeDistribution(root(ctx))

    internal fun removeDistribution(root: File) {
        for (id in CC_ESP_MODS) {
            File(root, "$MODS_DIR/$id").deleteRecursively()
            File(root, "$DISABLED_DIR/$id").deleteRecursively()
        }
        File(root, LOADER_DIR).deleteRecursively()
    }

    /** Every installed mod, enabled ones first then disabled, each alphabetically by title. */
    fun list(ctx: Context): List<InstalledMod> = list(root(ctx))

    internal fun list(root: File): List<InstalledMod> {
        val out = ArrayList<InstalledMod>()
        for ((dir, enabled) in listOf(MODS_DIR to true, DISABLED_DIR to false)) {
            for (child in File(root, dir).listFiles().orEmpty()) {
                if (!child.isDirectory) continue
                val manifest = File(child, MOD_MANIFEST)
                if (!manifest.isFile) continue
                out.add(readMod(child.name, manifest, enabled))
            }
        }
        return out.sortedWith(compareByDescending<InstalledMod> { it.enabled }.thenBy { it.title.lowercase() })
    }

    /** Turns a mod on or off by moving it between `assets/mods/` and `disabled/`. */
    fun setEnabled(ctx: Context, id: String, enabled: Boolean): Boolean =
        setEnabled(root(ctx), id, enabled)

    internal fun setEnabled(root: File, id: String, enabled: Boolean): Boolean {
        val from = File(root, "${if (enabled) DISABLED_DIR else MODS_DIR}/$id")
        val to = File(root, "${if (enabled) MODS_DIR else DISABLED_DIR}/$id")
        if (!from.isDirectory || to.exists()) return false
        to.parentFile?.mkdirs()
        return from.renameTo(to)
    }

    fun remove(ctx: Context, id: String): Boolean = remove(root(ctx), id)

    internal fun remove(root: File, id: String): Boolean {
        var removed = false
        for (dir in listOf(MODS_DIR, DISABLED_DIR)) {
            val f = File(root, "$dir/$id")
            if (f.isDirectory) removed = f.deleteRecursively() || removed
        }
        return removed
    }

    /** Downloads and installs the pinned CrossCode-Esp distribution. */
    fun installDistribution(ctx: Context) {
        val root = root(ctx)
        root.parentFile?.mkdirs()
        val tmp = File(root.parentFile, "dist.tmp")
        try {
            val conn = (URL(CC_ESP_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            conn.inputStream.use { installDistributionFrom(root, it, CC_ESP_SHA256) }
        } catch (e: Exception) {
            Log.e(TAG, "distribution download failed", e)
            throw e
        } finally {
            tmp.delete()
        }
    }

    /**
     * Streams [stream] to a temp file, verifies its SHA-256 against [expectedSha], then extracts only
     * the loader and the mods into [root]. Nothing half-installed is left on a failure.
     */
    internal fun installDistributionFrom(root: File, stream: InputStream, expectedSha: String) {
        root.parentFile?.mkdirs()
        val tmp = File(root.parentFile, "dist.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.outputStream().use { out ->
                val buf = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != expectedSha) throw IOException("mod distribution checksum mismatch")
            extract(tmp, root, strip = "", dest = "") { name ->
                name.startsWith("$LOADER_DIR/") || name.startsWith("$MODS_DIR/")
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Installs a user-picked archive: stages it, works out its shape from the entry names, and
     * extracts it accordingly. Returns what was installed. [name] is the picked file's display name,
     * used only for the fallback mod id and the log.
     */
    fun installArchive(ctx: Context, name: String, stream: InputStream): Installed {
        val root = root(ctx)
        root.parentFile?.mkdirs()
        val tmp = File(root.parentFile, "mod.tmp")
        try {
            tmp.outputStream().use { stream.copyTo(it) }
            val installed = installArchiveFrom(root, tmp, name)
            Log.i(TAG, "installed ${name}: loader=${installed.loader} mods=${installed.mods}")
            return installed
        } finally {
            tmp.delete()
        }
    }

    internal fun installArchiveFrom(root: File, archive: File, name: String): Installed {
        val names = zipNames(archive)
        if (names.isEmpty()) throw IOException("the archive is empty")
        val strip = wrapperPrefix(names)
        val stripped = names.map { it.removePrefix(strip) }
        val packedLoader = stripped.contains(LOADER_ENTRY)
        val rawLoader = !packedLoader && stripped.contains(LOADER_ENTRY_NAME)
        val distMods = stripped.filter { it.startsWith("$MODS_DIR/") && it.endsWith("/$MOD_MANIFEST") }
        val rootManifest = stripped.contains(MOD_MANIFEST)

        return when {
            packedLoader || distMods.isNotEmpty() -> {
                /* A distribution: its own layout is the library layout, so it lands as-is. */
                extract(archive, root, strip, dest = "")
                Installed(packedLoader, distMods.map { modIdOf(it) })
            }
            rootManifest -> {
                val id = manifestId(archive, strip, name)
                extract(archive, root, strip, dest = "$MODS_DIR/$id")
                Installed(false, listOf(id))
            }
            rawLoader -> {
                /* A bare CCLoader build: its root becomes the `ccloader/` package. */
                extract(archive, root, strip, dest = LOADER_DIR)
                Installed(true, emptyList())
            }
            else -> throw IOException("no ccmod.json, assets/mods/ or ccloader build in the archive")
        }
    }

    /** The read-only view of the library, rooted at a game root. Same path rule `FsBridge` applies. */
    class ModRoot(private val root: File) {

        fun read(rel: String): ByteArray? = file(rel)?.takeIf { it.isFile }?.readBytes()

        fun exists(rel: String): Boolean = file(rel)?.exists() == true

        fun list(rel: String): List<String>? =
            file(rel)?.takeIf { it.isDirectory }?.list()?.toList()

        fun stat(rel: String): StatInfo? {
            val f = file(rel) ?: return null
            if (!f.exists()) return null
            return StatInfo(f.lastModified(), f.length(), f.isDirectory)
        }

        private fun file(rel: String): File? {
            val stripped = rel.trimStart('/')
            if (stripped.split('/').any { it == ".." }) return null
            return File(root, stripped)
        }
    }

    /* ---------------------------------------------------------------- internals ---- */

    /** File entry names (directories dropped), in archive order; `..` segments rejected. */
    private fun zipNames(archive: File): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(archive.inputStream()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val n = entry.name
                if (n.endsWith("/")) continue
                if (n.split('/').any { it == ".." }) continue
                names.add(n)
            }
        }
        return names
    }

    /**
     * A single top-level directory every entry shares, when that directory is not itself part of the
     * library layout (`ccloader/`, `assets/`) — the wrapper a zip-of-a-folder like `Foo-1.2.3/…`
     * carries. Empty when the entries are rooted at the archive.
     */
    private fun wrapperPrefix(names: List<String>): String {
        if (names.size < 2) return ""
        val tops = names.map { it.substringBefore('/') }
        if (tops.toSet().size != 1) return ""
        val dir = tops.first()
        if (dir.isEmpty() || dir == LOADER_DIR || dir == "assets") return ""
        return "$dir/"
    }

    /** `assets/mods/<id>/ccmod.json` -> `<id>`. */
    private fun modIdOf(manifestPath: String): String =
        manifestPath.removePrefix("$MODS_DIR/").removeSuffix("/$MOD_MANIFEST")

    /** The id a single-mod archive should be filed under: its manifest's, made path-safe. */
    private fun manifestId(archive: File, strip: String, name: String): String {
        val id = try {
            readEntry(archive, "$strip$MOD_MANIFEST")
                ?.let { JSONObject(String(it, Charsets.UTF_8)).optString("id", "") }
                .orEmpty()
        } catch (e: Exception) {
            ""
        }
        return safeId(id.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'))
    }

    /** Ids must be one directory name; anything else (spaces, `+`, `..`) becomes underscores. */
    private fun safeId(raw: String): String {
        val cleaned = raw.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return if (cleaned.isEmpty() || cleaned.all { it == '_' }) "mod" else cleaned
    }

    private fun readMod(dir: String, manifest: File, enabled: Boolean): InstalledMod {
        var id = dir
        var title = dir
        var version = ""
        try {
            val json = JSONObject(manifest.readText(Charsets.UTF_8))
            id = json.optString("id", "").takeIf { it.isNotEmpty() } ?: dir
            title = json.optString("title", "").takeIf { it.isNotEmpty() } ?: id
            version = json.optString("version", "")
        } catch (e: Exception) {
            Log.w(TAG, "unreadable manifest ${manifest.path}", e)
        }
        return InstalledMod(id, title, version, enabled)
    }

    private fun readEntry(archive: File, name: String): ByteArray? {
        ZipInputStream(archive.inputStream()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                if (entry.name == name) return z.readBytes()
            }
        }
        return null
    }

    /**
     * Writes an archive's files under `root/<dest>/`, after dropping [strip] from the front of every
     * entry name. Directory entries, `.xcf` GIMP sources and any `..` segment are skipped; [keep] can
     * narrow the set further (the distribution extracts only the loader and the mods).
     */
    private fun extract(
        archive: File,
        root: File,
        strip: String,
        dest: String,
        keep: (String) -> Boolean = { true },
    ) {
        ZipInputStream(archive.inputStream()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                var name = entry.name
                if (name.endsWith("/")) continue
                if (strip.isNotEmpty()) {
                    if (!name.startsWith(strip)) continue
                    name = name.removePrefix(strip)
                }
                if (name.isEmpty() || name.endsWith(".xcf")) continue
                if (name.split('/').any { it == ".." }) continue
                if (!keep(name)) continue
                val target = if (dest.isEmpty()) File(root, name) else File(root, "$dest/$name")
                target.parentFile?.mkdirs()
                target.outputStream().use { z.copyTo(it) }
            }
        }
    }
}
