package io.github.moronigranja.alabasterdawn

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * The `window.AdaBridge` object the injected shim talks to.
 *
 * Every method here is synchronous on purpose: the shim mirrors Node's synchronous fs, so the app
 * must answer inline. Nothing throws across the boundary - failures come back as `false` / `null`.
 */
class AdaBridge(
    private val fs: FsBridge,
    /** Read once per engine frame by the shim, which polls like it polls `getGamepadJson`. */
    private val viewAlign: () -> ViewAlign,
    private val statsEnabled: () -> Boolean,
    /** Battery/thermal numbers, asked for only when the shim repaints the readout. */
    private val telemetry: Telemetry,
    /** The app's own log, so a device-only failure is reportable without adb. */
    private val diag: Diag,
) {

    @JavascriptInterface
    fun getGamepadJson(): String {
        diag.noteFrame()
        return Gamepad.json()
    }

    /** Where the picture goes: "top" | "center" | "bottom" (see [ViewAlign]). */
    @JavascriptInterface
    fun getViewAlign(): String = viewAlign().wire

    /** Whether the shim draws its frame-rate/resolution/battery readout. */
    @JavascriptInterface
    fun getStatsEnabled(): Boolean = statsEnabled()

    /** `{"level":65,"temp":388,"thermal":"critical"}`; a field is null when it is unavailable. */
    @JavascriptInterface
    fun getTelemetry(): String = telemetry.json()

    @JavascriptInterface
    fun fsExists(path: String): Boolean = traced("fsExists", path) { fs.exists(path) }

    @JavascriptInterface
    fun fsMkdir(path: String): Boolean = traced("fsMkdir", path) { fs.mkdir(path) }

    /** JSON `[{"n":name,"d":isDirectory}, ...]`, or `""` when the directory cannot be listed. */
    @JavascriptInterface
    fun fsReaddir(path: String): String = traced("fsReaddir", path) {
        val entries = fs.list(path) ?: return@traced ""
        val json = StringBuilder(entries.size * 24 + 2).append('[')
        for ((i, entry) in entries.withIndex()) {
            if (i > 0) json.append(',')
            json.append("{\"n\":").append(quote(entry.name))
                .append(",\"d\":").append(entry.isDir).append('}')
        }
        json.append(']').toString()
    }

    /** JSON `{"mtime":epochMillis,"size":bytes,"dir":bool}`, or `null` when the path is absent. */
    @JavascriptInterface
    fun fsStatSync(path: String): String? = traced("fsStatSync", path) {
        val stat = fs.stat(path) ?: return@traced null
        "{\"mtime\":" + stat.mtimeMillis + ",\"size\":" + stat.size + ",\"dir\":" + stat.isDir + "}"
    }

    @JavascriptInterface
    fun fsReadFile(path: String): String? = traced("fsReadFile", path) { fs.read(path) }

    @JavascriptInterface
    fun fsWriteFile(path: String, data: String): Boolean =
        traced("fsWriteFile", path) { fs.write(path, data) }

    @JavascriptInterface
    fun fsRename(from: String, to: String): Boolean = traced("fsRename", from) { fs.rename(from, to) }

    @JavascriptInterface
    fun fsRm(path: String): Boolean = traced("fsRm", path) { fs.rm(path) }

    @JavascriptInterface
    fun fsCopyFile(from: String, to: String): Boolean = traced("fsCopyFile", from) { fs.copy(from, to) }

    /**
     * The vertex uniform budget the page's own GL stack reports, sent once from the shim before the
     * engine compiles anything. It decides how big a `TEX_SLOT_COUNT` the served shaders can carry
     * (see [ShaderSlots]); the shaders are fetched after this lands, so a late answer only ever means
     * the safe default.
     */
    @JavascriptInterface
    fun setVertexUniformVectors(vectors: Int) {
        ShaderSlots.vertexUniformVectors = vectors
        diag.line("gl limits: vertex uniforms $vectors -> TEX_SLOT_COUNT ${ShaderSlots.slots()}")
    }

    /** Device-only failures would otherwise be a black screen with nothing in logcat. */
    @JavascriptInterface
    fun reportJsError(message: String) {
        Log.e(TAG, "js: $message")
        diag.line("JS ERROR $message")
    }

    /**
     * The shim's own reports: boot progress, a boot that stopped advancing, and the GL/audio facts
     * it measured. [kind] is a short label, [payload] is a one-line summary (it is truncated).
     */
    @JavascriptInterface
    fun reportDiag(kind: String, payload: String) {
        diag.event(kind.take(40), payload.take(600))
    }

    /**
     * Names the call for the diagnostics while it runs, so a JS thread blocked inside a synchronous
     * SAF read is identified instead of guessed at. The name costs one small string per call.
     */
    private inline fun <T> traced(name: String, path: String, body: () -> T): T {
        diag.noteCall("$name $path")
        try {
            return body()
        } finally {
            diag.noteCall("-")
        }
    }

    private fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    companion object {
        private const val TAG = "AdaPort"
    }
}
