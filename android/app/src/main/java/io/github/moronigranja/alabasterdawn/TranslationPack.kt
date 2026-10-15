package io.github.moronigranja.alabasterdawn

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The community Spanish translation for CrossCode, as shipped in the **CrossCode-Esp v1.1.0**
 * release: a CCLoader modpack (the loader itself plus `CrossCode-Esp`, `Localize-me` and their
 * dependencies). The user taps one entry-screen item, the pack is streamed over HTTPS, verified
 * against [SHA256] and unpacked into app storage; every boot then goes through the pack's own
 * CCLoader entry page, with Spanish selected by the pack.
 *
 * The pack is *community work*, not Radical Fish Games': the APK ships none of it, and the user's
 * picked game folder is neither read for it nor written. See `NOTICE.md`.
 *
 * Everything below runs on a [File] root so the unpack and the read view are JVM-testable; the
 * [Context] entry points are thin wrappers over `filesDir`.
 */
object TranslationPack {

    /** The released v1.1.0 asset. Re-hash on a re-cut; the menu label rounds [SIZE] up. */
    const val URL =
        "https://github.com/tralph3/CrossCode-Esp/releases/download/v1.1.0/CrossCode-Esp_v1.1.0.zip"
    const val SHA256 = "d541a543e437f8ec6986b74c3df9a1fc982bd9daff6ee72a0ea044cba7edc14d"
    const val SIZE = 2537080L
    const val VERSION = "1.1.0"

    /**
     * The locale the pack provides. `CrossCode-Esp` adds it to the game's language table (via
     * `Localize-me`) but does not select it, and the game picks `ig.currentLang` from
     * `ig.FORCE_LANG` → `window.IG_LANG` → stored `IG_LANG` → auto-detect. The port sets
     * `window.IG_LANG` on the pack's own entry page so the boot lands in Spanish.
     */
    const val LOCALE = "es_ES"

    /** Where the unpacked pack lives under `filesDir`. */
    const val DIR = "translations/cc-es"

    private const val PACK_JSON = "pack.json"
    private const val TAG = "RfPort"
    private const val TIMEOUT_MS = 15000

    /** The unpacked pack's root. */
    fun root(ctx: Context): File = File(ctx.filesDir, DIR)

    /** The read view of the pack, or null when it has not been installed (its marker file is the state). */
    fun open(ctx: Context): Pack? =
        root(ctx).takeIf { File(it, PACK_JSON).isFile }?.let { Pack(it) }

    fun remove(ctx: Context) {
        root(ctx).deleteRecursively()
    }

    /**
     * Downloads [URL] and unpacks it into [root]. On any failure — network, checksum, a bad zip — the
     * temp file is removed and the exception rethrown; nothing half-installed is left behind.
     */
    fun install(ctx: Context) {
        val root = root(ctx)
        root.parentFile?.mkdirs()
        val tmp = File(root.parentFile, "pack.tmp")
        try {
            val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            conn.inputStream.use { installFrom(root, it, SHA256) }
        } catch (e: Exception) {
            Log.e(TAG, "translation pack download failed", e)
            throw e
        } finally {
            tmp.delete()
        }
    }

    /**
     * Streams [stream] to a temp file, verifies its SHA-256 against [expectedSha], then extracts the
     * pack's own files into [root]: only `ccloader/` and `assets/mods/`, no directory entries, and no
     * `.xcf` (the pack's three GIMP sources, whose `.png` siblings remain). A path with a `..` segment
     * is skipped, so a crafted archive cannot escape [root]. Writes the [PACK_JSON] marker last.
     */
    internal fun installFrom(root: File, stream: InputStream, expectedSha: String) {
        root.parentFile?.mkdirs()
        val tmp = File(root.parentFile, "pack.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.outputStream().use { out ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != expectedSha) throw IOException("translation pack checksum mismatch")
            ZipInputStream(tmp.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name.endsWith("/")) continue
                    if (!name.startsWith("ccloader/") && !name.startsWith("assets/mods/")) continue
                    if (name.endsWith(".xcf")) continue
                    if (name.split('/').any { it == ".." }) continue
                    val target = File(root, name)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
            File(root, PACK_JSON)
                .writeText("{\"version\":\"$VERSION\",\"sha256\":\"$expectedSha\",\"size\":$SIZE}")
        } finally {
            tmp.delete()
        }
    }

    /**
     * A read-only view of the unpacked pack, rooted at a game root (the pack's own `ccloader/` and
     * `assets/mods/` trees). Its paths are game-root-relative, exactly the keys CCLoader passes to
     * `fs`; [file] applies the escape rule `FsBridge` does, so a `..` path reads nothing.
     */
    class Pack(private val root: File) {

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
}
