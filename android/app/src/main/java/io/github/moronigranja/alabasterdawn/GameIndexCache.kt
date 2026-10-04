package io.github.moronigranja.alabasterdawn

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * The picked folder's index, kept between launches.
 *
 * Walking the tree costs a query per directory: 3 348 entries (≈7 s) for Alabaster Dawn and 5 178
 * (≈10 s) for CrossCode on the phone, which with "Start last game directly" is the whole startup
 * wait. The index is written after a walk and read back on the next launch.
 *
 * It is used only while it still describes the same folder and the same game build: the tree's
 * document id must match, and the game's own version file (GameProfile.versionPath, read by name so
 * it needs no index) must be identical to the one the index was built from. Anything else — a moved
 * or deleted folder, a game update, a re-picked folder, a truncated file — falls back to a full walk,
 * which is also what rewrites the cache.
 *
 * Document ids for local storage are derived from the path, so they stay valid across launches; a
 * provider with opaque ids simply never hits, because the file's own tree id will not match.
 */
object GameIndexCache {

    private const val TAG = "RfPort"
    private const val HEADER = "#ada-index 1"
    /** Field separator. A tab in a file or folder name is not supported: such a line is rejected. */
    private const val SEP = '\t'

    /** The cache file for [treeUri], under the app's own storage (never in the game's folder). */
    private fun file(context: Context, treeUri: Uri): File {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        val key = id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return File(File(context.filesDir, "index"), "index-$key.txt")
    }

    /** The index text for [index], stamped with the folder and the game build it was built from. */
    fun encode(index: GameIndex, treeDocId: String, version: String?): String = buildString {
        append(HEADER).append('\n')
        append("TREE ").append(treeDocId).append('\n')
        append("VERSION ").append(version.orEmpty()).append('\n')
        for ((rel, entry) in index.snapshot()) {
            append('E').append(SEP).append(if (entry.isDir) 1 else 0).append(SEP)
                .append(entry.size).append(SEP).append(rel).append(SEP).append(entry.docId).append('\n')
        }
    }

    /**
     * The index [text] describes, or null when it is not a readable cache for this folder and build.
     * Any malformed line rejects the whole file: a half-written cache must not become a half index.
     */
    fun decode(text: String, treeDocId: String, version: String?): GameIndex? {
        val lines = text.split('\n')
        if (lines.size < 4 || lines[0] != HEADER) return null
        if (lines[1] != "TREE $treeDocId") return null
        if (lines[2] != "VERSION " + version.orEmpty()) return null
        val entries = HashMap<String, GameEntry>(lines.size * 2)
        for (line in lines.drop(3)) {
            if (line.isEmpty()) continue
            val parts = line.split(SEP)
            if (parts.size != 5 || parts[0] != "E") return null
            val isDir = parts[1] == "1"
            val size = parts[2].toLongOrNull() ?: return null
            val name = parts[3]
            if (name.isEmpty()) return null
            entries[name] = GameEntry(parts[4], size, isDir)
        }
        return GameIndex(entries, "")
    }

    /** The cached index for [treeUri], or null when there is none or it no longer describes it. */
    fun load(context: Context, treeUri: Uri, version: String?): GameIndex? {
        val cache = file(context, treeUri)
        val index =
            if (!cache.isFile || !GameFiles.treeResolves(context.contentResolver, treeUri)) null
            else try {
                decode(cache.readText(), DocumentsContract.getTreeDocumentId(treeUri), version)
            } catch (e: Exception) {
                Log.w(TAG, "cannot read the index cache", e)
                null
            }
        Log.i(TAG, "index cache " + (if (index != null) "hit (${index.size} entries)" else "miss"))
        return index
    }

    /** Writes [index] for [treeUri]; a failure is logged and ignored (the cache is an optimisation). */
    fun save(context: Context, treeUri: Uri, version: String?, index: GameIndex) {
        try {
            val cache = file(context, treeUri)
            cache.parentFile?.mkdirs()
            cache.writeText(encode(index, DocumentsContract.getTreeDocumentId(treeUri), version))
        } catch (e: Exception) {
            Log.w(TAG, "cannot write the index cache", e)
        }
    }
}
