package io.github.moronigranja.alabasterdawn

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Serves the picked game folder to the WebView under `https://appassets.androidplatform.net/game/`.
 *
 * Every request is answered from the immutable [GameIndex]; anything missing returns an empty
 * response immediately, in-process. That matters: each sound resource probes `.flac` (which this
 * build does not ship) before falling back, and those probes must not become network round trips.
 *
 * `handle` is called on background threads and may run concurrently, so the mutable state it holds
 * ([rewrite], [barycentric]) is concurrent and it never touches a view.
 */
class GameAssetHandler(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val index: GameIndex,
    private val assets: AssetManager,
    /** Fallback mode: pull the shim in with a script tag, for WebViews without document-start scripts. */
    private val injectShim: Boolean,
    /** Records every request, and any request that stays in flight too long (a hung SAF read). */
    private val diag: Diag,
) : WebViewAssetLoader.PathHandler {

    private val tracker = AssetTracker()
    private val slowLogged = java.util.concurrent.atomic.AtomicInteger()
    private val missedLogged = java.util.concurrent.atomic.AtomicInteger()

    /** The rewritten body of every request that was rewritten, so a re-request costs no SAF read. */
    private val rewrite = RewriteCache()

    /** Whether a fragment shader's paired `.vert` declares the varying: a second SAF read, memoised. */
    private val barycentric = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Paths already reported as too large to cache, so the warning is logged once each. */
    private val cacheWarned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun handle(path: String): WebResourceResponse {
        val rel = Uri.decode(path).trimStart('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return miss(rel)
        tracker.started(rel)
        try {
            return serve(rel)
        } finally {
            val took = tracker.finished(rel)
            /* A tree on a slow card shows up here long before it fails outright. */
            if (took >= SLOW_MS && slowLogged.incrementAndGet() <= SLOW_LOG_LIMIT) {
                diag.line("slow asset ${took}ms $rel")
            }
        }
    }

    private fun serve(rel: String): WebResourceResponse {
        if (rel == SHIM_PATH) {
            if (!injectShim) return miss(rel)
            val shim = try {
                assets.open(SHIM_ASSET).use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "cannot read $SHIM_ASSET", e)
                return miss(rel)
            }
            return ok("application/javascript", "utf-8", shim)
        }

        val entry = index.find(rel) ?: return miss(rel)
        if (entry.isDir) return miss(rel)

        /* A rewritten asset served before: the bytes are the same for the process lifetime, so this
         * skips both the 12.7 MB read and the rewrite. ok() builds a fresh stream per call. */
        rewrite.get(rel)?.let { cached ->
            val mime = mimeOf(rel)
            return ok(mime, encodingOf(mime), cached)
        }

        var body = read(entry) ?: return miss(rel)
        /* Only the assets that are actually rewritten are cached: everything else (images, audio,
         * `.vert`, JSON) is served verbatim and read once per boot, and caching all 2 652 of them
         * would cost tens of megabytes for nothing. */
        val cacheable = rel == BUNDLE_JS || rel == OPTIONS_DB || rel.endsWith(".frag") ||
            (injectShim && rel == INDEX_HTML)
        if (injectShim && rel == INDEX_HTML) body = injectShimTag(body)
        if (rel.endsWith(".frag")) body = keepBarycentricAlive(rel, body)
        if (rel == BUNDLE_JS) body = phoneResolutionLadder(rel, body)
        if (rel == OPTIONS_DB) body = relabelResolutions(rel, body)
        if (cacheable && !rewrite.put(rel, body) && cacheWarned.add(rel)) {
            Log.w(TAG, "not caching $rel: the rewritten set no longer fits the rewrite cache")
        }

        val mime = mimeOf(rel)
        return ok(mime, encodingOf(mime), body)
    }

    /** Requests still unfinished after [thresholdMs]: a path here is a stalled SAF read, not a stall. */
    fun stuck(thresholdMs: Long): List<String> = tracker.stuck(thresholdMs)

    /** One line for the diagnostics header: what the asset path has done so far. */
    fun counters(): String = tracker.counters() + "; rewrite " + rewrite.summary()

    /**
     * The engine renders at `SCREEN (640x360) x SCALE` and offers SCALE from `RESOLUTION_MAP`, whose
     * integer rungs (1, 2, 3, 4, 6) are too coarse on a phone: 1280x720 saturates the Adreno 730
     * (measured 99.9% GPU busy, ~20-40 fps) while 640x360 leaves it at 55-65% at a locked 60 fps.
     * This rewrites the ladder to 640x360 / 960x540 / 1280x720 / 1920x1080 / 2560x1440 so the middle
     * rung exists, and relabels the option accordingly. Value 0 keeps its old meaning, so existing
     * players' saved choice is unchanged; the game's own files are never modified.
     */
    private fun phoneResolutionLadder(rel: String, source: ByteArray): ByteArray {
        val text = String(source, Charsets.UTF_8)
        if (!text.contains(RESOLUTION_MAP_ORIGINAL)) {
            Log.w(TAG, "$rel has no '$RESOLUTION_MAP_ORIGINAL'; leaving it unmodified")
            return source
        }
        Log.i(TAG, "rewriting the resolution ladder in $rel")
        return text.replace(RESOLUTION_MAP_ORIGINAL, RESOLUTION_MAP_PHONE).toByteArray(Charsets.UTF_8)
    }

    /** Matches the resolution labels in the option database and gives them the phone ladder's text. */
    private fun relabelResolutions(rel: String, source: ByteArray): ByteArray {
        var text = String(source, Charsets.UTF_8)
        if (!text.contains(RESOLUTION_FIRST_LABEL)) {
            Log.w(TAG, "$rel has no '$RESOLUTION_FIRST_LABEL'; leaving it unmodified")
            return source
        }
        var changed = false
        for ((langId, label) in RESOLUTION_LABELS) {
            val pattern = Regex("\"en_US\":\"[^\"]*\",\"langID\":$langId\\b")
            val replaced = pattern.replaceFirst(text, "\"en_US\":\"$label\",\"langID\":$langId")
            if (replaced != text) changed = true
            text = replaced
        }
        /* A fresh profile should land on the rung that holds 60 fps, not on 1280x720. */
        val defaulted = text.replace(RESOLUTION_DEFAULT_ORIGINAL, RESOLUTION_DEFAULT_PHONE)
        if (defaulted != text) changed = true
        if (!changed) {
            Log.w(TAG, "could not relabel $rel; resolution labels may not match the scale ladder")
            return source
        }
        Log.i(TAG, "relabelled the resolution option in $rel")
        return text.toByteArray(Charsets.UTF_8)
    }

    /**
     * The engine registers vertex attributes straight from the linked program
     * (`gl.getActiveAttrib`, bundle ~38760) and looks them up in that global table when it builds a
     * mesh. Drivers may drop a declared-but-dead attribute, and on Android WebView's native GLES
     * backend (Adreno 730, "OpenGL ES 3.0 Chromium") `a_barycentricIdx` is dropped: several fragment
     * shaders declare `v_barycentric` but only ever mention it in commented-out code, so the chain
     * attribute -> varying -> fragment input is dead. The title screen then throws "Attribute does
     * not exist!" while creating billboard geometry. ANGLE on desktop keeps the attribute, so this
     * never shows up on a PC.
     *
     * The fix is to make the declaration honest: one unreachable, data-dependent use of the varying
     * inside main(). Barycentric coordinates are never below -1e30, so the branch never runs and
     * rendering is unchanged. It is a `return` rather than a `discard` on purpose - a `discard`
     * anywhere in a shader typically turns off early-Z on tile-based GPUs, which this game's
     * overdraw-heavy scenes would pay for. Only the served bytes are rewritten; the file on disk is
     * untouched.
     *
     * A fragment shader is only patched when its same-named vertex shader actually declares the
     * varying - `plane-depth.frag` and `fx-decal.frag` declare it with a vertex shader that does not,
     * and using it there would break the link instead.
     */
    private fun keepBarycentricAlive(rel: String, source: ByteArray): ByteArray {
        val text = String(source, Charsets.UTF_8)
        if (!text.contains(BARYCENTRIC_DECL)) return source
        if (text.split(BARYCENTRIC).size != 2) return source // already used somewhere: nothing to do
        if (!vertexProvidesBarycentric(rel)) return source
        val mainAt = text.lastIndexOf("void main")
        if (mainAt == -1) return source
        val braceAt = text.indexOf('{', mainAt)
        if (braceAt == -1) return source
        Log.i(TAG, "keeping $BARYCENTRIC live in $rel")
        return (text.substring(0, braceAt + 1) + KEEP_ALIVE + text.substring(braceAt + 1))
            .toByteArray(Charsets.UTF_8)
    }

    /** Memoised: the answer needs a second SAF read of the paired `.vert`, and never changes. */
    private fun vertexProvidesBarycentric(fragRel: String): Boolean =
        barycentric.getOrPut(fragRel) { computeVertexProvidesBarycentric(fragRel) }

    private fun computeVertexProvidesBarycentric(fragRel: String): Boolean {
        val name = fragRel.substringAfterLast('/').removeSuffix(".frag")
        val vertRel = "terra/data/shader/vertex/$name.vert"
        val entry = index.find(vertRel) ?: return false
        val vert = read(entry) ?: return false
        return String(vert, Charsets.UTF_8).contains(OUT_BARYCENTRIC)
    }

    private fun read(entry: GameEntry): ByteArray? = try {
        resolver.openInputStream(documentUri(entry.docId))?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.e(TAG, "cannot read game document ${entry.docId}", e)
        null
    }

    private fun documentUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun ok(mime: String, encoding: String?, bytes: ByteArray): WebResourceResponse {
        val headers = HashMap<String, String>(1)
        headers["Content-Length"] = bytes.size.toString()
        return WebResourceResponse(mime, encoding, 200, "OK", headers, ByteArrayInputStream(bytes))
    }

    /** No body, no round trip. Misses are rare (the build ships no .flac, and each sound probes one). */
    private fun miss(rel: String): WebResourceResponse {
        tracker.missed(rel)
        if (missedLogged.incrementAndGet() <= 40) Log.w(TAG, "no such asset: $rel")
        return WebResourceResponse(null, null, null)
    }

    /**
     * Inserts `<script src="ada-shim.js"></script>` right before the bundle tag. The bytes are
     * rewritten in memory; the file on the user's disk is never touched.
     */
    private fun injectShimTag(html: ByteArray): ByteArray {
        val text = String(html, Charsets.UTF_8)
        val anchor = text.indexOf(BUNDLE_TAG)
        if (anchor == -1) {
            Log.e(TAG, "$INDEX_HTML does not contain $BUNDLE_TAG; serving it unmodified")
            return html
        }
        return (text.substring(0, anchor) + SHIM_TAG + text.substring(anchor)).toByteArray(Charsets.UTF_8)
    }

    private fun encodingOf(mime: String): String? = when {
        mime.startsWith("text/") -> "utf-8"
        mime == "application/javascript" || mime == "application/json" || mime == "image/svg+xml" -> "utf-8"
        else -> null
    }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ico" -> "image/x-icon"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "frag", "vert", "glsl" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "AdaPort"
        /* An asset read this slow means the tree's provider is struggling (a card, a slow volume). */
        private const val SLOW_MS = 1500L
        private const val SLOW_LOG_LIMIT = 20
        const val SHIM_PATH = "terra/ada-shim.js"
        const val SHIM_ASSET = "ada-shim.js"
        const val INDEX_HTML = "terra/index.html"
        private const val BUNDLE_TAG = "<script src=\"dist/bundle.js\""
        private const val SHIM_TAG = "<script src=\"ada-shim.js\"></script>\n"

        private const val BUNDLE_JS = "terra/dist/bundle.js"
        private const val OPTIONS_DB = "terra/data/database/options.json"
        private const val RESOLUTION_MAP_ORIGINAL = "const RESOLUTION_MAP = [1, 2, 3, 4, 6];"
        private const val RESOLUTION_MAP_PHONE = "const RESOLUTION_MAP = [1, 1.5, 2, 3, 4];"
        private const val RESOLUTION_FIRST_LABEL = "{\"en_US\":\"640x360\",\"langID\":171}"
        private const val RESOLUTION_DEFAULT_ORIGINAL =
            "\"type\":{\"default\":1,\"list\":[{\"en_US\":\"640x360\""
        private const val RESOLUTION_DEFAULT_PHONE =
            "\"type\":{\"default\":0,\"list\":[{\"en_US\":\"640x360\""
        private val RESOLUTION_LABELS = mapOf(
            171 to "640x360",
            172 to "960x540",
            173 to "1280x720",
            203 to "1920x1080",
            204 to "2560x1440",
        )
        private const val BARYCENTRIC = "v_barycentric"
        private const val BARYCENTRIC_DECL = "in vec3 $BARYCENTRIC;"
        private const val OUT_BARYCENTRIC = "out vec3 $BARYCENTRIC;"
        private const val KEEP_ALIVE =
            "\n    if (any(lessThan($BARYCENTRIC, vec3(-1e30)))) return; // $BARYCENTRIC keep-alive\n"
    }
}
