package dev.moroni.alabasterdawn

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log

/** One document of the picked game tree, keyed by its path relative to the picked root. */
class GameEntry(val docId: String, val size: Long, val isDir: Boolean)

/**
 * Immutable in-memory index of the picked game folder.
 *
 * Keys are paths relative to the picked root (e.g. `terra/media/gui/loading.png`), which is what
 * both the asset handler (URLs under `/game/`) and the fs bridge (paths relative to the page root)
 * need. Built once per launch by [GameFiles.indexTree]; never mutated afterwards, so the
 * background [GameAssetHandler] threads can read it without locking.
 */
class GameIndex(private val entries: Map<String, GameEntry>) {

    val size: Int get() = entries.size

    fun find(path: String): GameEntry? = entries[path]

    /**
     * Game-space fs paths are page-relative: the bundle's ENGINE_CONF roots are empty strings, so a
     * sound resource asks for `media/audio/sfx/x.ogg` while the picked root holds exactly that file
     * under `terra/`. Try both spellings; the index is a map lookup, so the extra probe is free.
     */
    fun findGamePath(path: String): GameEntry? {
        val p = path.trimStart('/')
        entries[p]?.let { return it }
        return entries["terra/$p"]
    }

    /** Relative index key of the directory [path] names, or null when it is not a directory. */
    fun findGameDir(path: String): String? {
        val p = path.trimStart('/')
        if (p.isEmpty() || p == "terra") return "terra"
        if (entries[p]?.isDir == true) return p
        if (entries["terra/$p"]?.isDir == true) return "terra/$p"
        return null
    }

    /** Immediate children of an index-key directory. Only the map editor browses, so O(n) is fine. */
    fun children(dir: String): List<DirEntry> {
        val out = ArrayList<DirEntry>()
        val head = if (dir.isEmpty()) "" else "$dir/"
        for ((key, entry) in entries) {
            if (!key.startsWith(head)) continue
            val rest = key.substring(head.length)
            if (rest.isEmpty() || rest.indexOf('/') != -1) continue
            out.add(DirEntry(rest, entry.isDir))
        }
        return out
    }

    /** Every index key, sorted: used for diagnostics only. */
    fun paths(): List<String> = entries.keys.sorted()
}

object GameFiles {

    const val TAG = "AdaPort"

    private val PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
    )

    /**
     * Walks the picked tree with a single `query` per directory (286 directories / 2652 files for
     * the shipped game) and returns the full index.
     */
    fun indexTree(resolver: ContentResolver, treeUri: Uri): GameIndex {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val entries = HashMap<String, GameEntry>(4096)
        val dirs = ArrayList<Pair<String, String>>() // docId to relative path
        dirs.add(rootId to "")
        var files = 0
        while (dirs.isNotEmpty()) {
            val (docId, prefix) = dirs.removeAt(dirs.size - 1)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            try {
                resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        val mime = cursor.getString(2) ?: ""
                        val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                        if (mime == Document.MIME_TYPE_DIR) {
                            entries[rel] = GameEntry(id, 0L, true)
                            dirs.add(id to rel)
                        } else {
                            val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                            entries[rel] = GameEntry(id, size, false)
                            files++
                        }
                    }
                }
            } catch (e: Exception) {
                val where = if (prefix.isEmpty()) "<root>" else prefix
                Log.e(TAG, "indexing failed at $where", e)
            }
        }
        Log.i(TAG, "indexed ${entries.size - files} directories / $files files")
        return GameIndex(entries)
    }

    /**
     * Last path segment of a picked tree URI's document id, for the pre-game screen. The document id
     * of a tree URI looks like `primary:Download/AlabasterDawn`.
     */
    fun displayNameOf(treeUri: Uri?): String? {
        if (treeUri == null) return null
        return try {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            val cut = id.lastIndexOf('/')
            val cut2 = id.lastIndexOf(':')
            val start = maxOf(cut, cut2)
            val name = if (start == -1) id else id.substring(start + 1)
            name.ifEmpty { id }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read tree name from $treeUri", e)
            null
        }
    }
}
