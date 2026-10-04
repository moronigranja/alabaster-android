package io.github.moronigranja.alabasterdawn

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * The `window.PortBridge` object the injected shim talks to.
 *
 * Every method here is synchronous on purpose: the shim mirrors Node's synchronous fs, so the app
 * must answer inline. Nothing throws across the boundary - failures come back as `false` / `null`.
 */
class PortBridge(
    private val fs: FsBridge,
    /** Read once per engine frame by the shim, which polls like it polls `getGamepadJson`. */
    private val viewAlign: () -> ViewAlign,
    private val statsEnabled: () -> Boolean,
    /** Whether the shim caps the page's frame rate, and at what — read once per engine frame. */
    private val fpsLimit: () -> Int,
    /** Battery/thermal numbers, asked for only when the shim repaints the readout. */
    private val telemetry: Telemetry,
    /** The app's own log, so a device-only failure is reportable without adb. */
    private val diag: Diag,
    /** The engine asked to leave the game (the shim's `quitApp`, from `nw.App.quit`/`Window.close`). */
    private val onQuit: () -> Unit,
) {

    /**
     * Set by the owner: the page's shader self-test finished (FINDINGS §22.11). Called on the
     * JavaBridge thread, so the owner posts to the UI thread.
     */
    var onShaderSelfTestDone: ((String) -> Unit)? = null

    @JavascriptInterface
    fun getGamepadJson(): String {
        diag.noteFrame()
        return Gamepad.json()
    }

    /**
     * The engine's own exit (the shim's `quitApp`). Called on the JavaBridge thread, so the owner
     * posts to the UI thread; this must return immediately.
     */
    @JavascriptInterface
    fun quit() = onQuit()

    /** Where the picture goes: "top" | "center" | "bottom" (see [ViewAlign]). */
    @JavascriptInterface
    fun getViewAlign(): String = viewAlign().wire

    /** Whether the shim draws its frame-rate/resolution/battery readout. */
    @JavascriptInterface
    fun getStatsEnabled(): Boolean = statsEnabled()

    /**
     * The frame-rate cap the shim enforces, in frames per second, or [FpsLimit.OFF] for none. Read on
     * the same once-per-frame poll as the overlays, so a change lands on the next frame.
     */
    @JavascriptInterface
    fun getFpsLimit(): Int = fpsLimit()

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
        /* The decision is not this number: it is what the page's own compiler linked, reported next. */
        diag.line("gl limits: vertex uniforms $vectors")
    }

    /**
     * Whether the page's throwaway shaders *linked* with the game's own 256-slot table shapes. The
     * shaders are fetched after this lands, so it decides whether the game's own bytes are served:
     * `true`/`true` keeps `TEX_SLOT_COUNT` at 256 and leaves `gui.vert` unpacked (see [ShaderSlots]).
     */
    @JavascriptInterface
    fun setShaderTables(oneTable: Boolean, twoTables: Boolean) {
        ShaderSlots.report(oneTable, twoTables)
        diag.line(
            "shader tables: 256-slot " + (if (oneTable) "links" else "does not link") +
                ", gui two-table " + (if (twoTables) "links" else "does not link") +
                " -> TEX_SLOT_COUNT ${ShaderSlots.slots()}" +
                (if (ShaderSlots.packs()) " packed" else ""),
        )
    }

    /**
     * Whether the page's compiler accepted the array declarations the game's fragment shaders are
     * written with, sent once from the shim before the engine compiles anything. Some mobile front
     * ends refuse them with `S0032: no default precision defined for variable 'vec4[4]'` (see
     * [ShaderArrays]); the shaders are fetched after this lands, so a late answer only ever means the
     * game's own bytes.
     *
     * [detail] is the probe's own line: the renderer of the context it asked on and what each shape
     * did. The answer used to be one bit from one synthetic shader, and on the device this exists for
     * that bit was wrong - the driver accepted the synthetic text and refused the game's own bytes, so
     * the lift never ran and the boot froze again (FINDINGS 22.5).
     */
    @JavascriptInterface
    fun setShaderArrays(compiled: Boolean, detail: String) {
        diag.line(
            "shader arrays: " + detail.ifBlank {
                if (compiled) "the page's compiler accepts the game's declarations"
                else "the page's compiler rejects them -> lifting them"
            },
        )
    }

    /**
     * The shader self-test's summary line, sent by the page once it has compiled every case
     * (FINDINGS §22.11). The per-case verdicts arrive through [reportDiag]; this is the completion
     * signal, and what the owner shows the refreshed record on.
     */
    @JavascriptInterface
    fun shaderSelfTestDone(summary: String) {
        diag.line("shader self-test done: $summary")
        onShaderSelfTestDone?.invoke(summary)
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
        private const val TAG = "RfPort"
    }
}
