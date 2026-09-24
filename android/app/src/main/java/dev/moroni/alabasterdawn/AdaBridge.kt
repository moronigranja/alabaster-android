package dev.moroni.alabasterdawn

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * The `window.AdaBridge` object the injected shim talks to.
 *
 * Every method here is synchronous on purpose: the shim mirrors Node's synchronous fs, so the app
 * must answer inline. Nothing throws across the boundary - failures come back as `false` / `null`.
 */
class AdaBridge(private val fs: FsBridge) {

    @JavascriptInterface
    fun getGamepadJson(): String = Gamepad.json()

    @JavascriptInterface
    fun fsExists(path: String): Boolean = fs.exists(path)

    @JavascriptInterface
    fun fsMkdir(path: String): Boolean = fs.mkdir(path)

    /** JSON `[{"n":name,"d":isDirectory}, ...]`, or `""` when the directory cannot be listed. */
    @JavascriptInterface
    fun fsReaddir(path: String): String {
        val entries = fs.list(path) ?: return ""
        val json = StringBuilder(entries.size * 24 + 2).append('[')
        for ((i, entry) in entries.withIndex()) {
            if (i > 0) json.append(',')
            json.append("{\"n\":").append(quote(entry.name))
                .append(",\"d\":").append(entry.isDir).append('}')
        }
        return json.append(']').toString()
    }

    /** JSON `{"mtime":epochMillis,"size":bytes,"dir":bool}`, or `null` when the path is absent. */
    @JavascriptInterface
    fun fsStatSync(path: String): String? {
        val stat = fs.stat(path) ?: return null
        return "{\"mtime\":" + stat.mtimeMillis + ",\"size\":" + stat.size + ",\"dir\":" + stat.isDir + "}"
    }

    @JavascriptInterface
    fun fsReadFile(path: String): String? = fs.read(path)

    @JavascriptInterface
    fun fsWriteFile(path: String, data: String): Boolean = fs.write(path, data)

    @JavascriptInterface
    fun fsRename(from: String, to: String): Boolean = fs.rename(from, to)

    @JavascriptInterface
    fun fsRm(path: String): Boolean = fs.rm(path)

    @JavascriptInterface
    fun fsCopyFile(from: String, to: String): Boolean = fs.copy(from, to)

    /** Device-only failures would otherwise be a black screen with nothing in logcat. */
    @JavascriptInterface
    fun reportJsError(message: String) {
        Log.e(TAG, "js: $message")
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
