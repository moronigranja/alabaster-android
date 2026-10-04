package io.github.moronigranja.alabasterdawn

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
 *
 * [pageRoot] is the selected [GameProfile]'s page root: the game-space prefix a page-relative path
 * resolves under. The tree is indexed before the game is known (its entry page is what says which
 * game it is), so the raw index carries `""` and [bind] returns the index the host uses.
 */
class GameIndex(private val entries: Map<String, GameEntry>, val pageRoot: String) {

    val size: Int get() = entries.size

    /** The same tree under a profile's page root. Called once, with the detected profile. */
    fun bind(pageRoot: String): GameIndex = GameIndex(entries, pageRoot)

    fun find(path: String): GameEntry? = entries[path]

    /**
     * Game-space fs paths are page-relative: the engines' own roots are empty strings, so a resource
     * asks for `data/...` (or a sound for `media/audio/sfx/x.ogg`) while the picked root holds it
     * under [pageRoot]. Try both spellings; the index is a map lookup, so the extra probe is free.
     */
    fun findGamePath(path: String): GameEntry? {
        val p = path.trimStart('/')
        entries[p]?.let { return it }
        return entries["$pageRoot/$p"]
    }

    /** Relative index key of the directory [path] names, or null when it is not a directory. */
    fun findGameDir(path: String): String? {
        /* The engines build directory paths with a trailing separator (`assets/extension/`). */
        val p = path.trim('/')
        if (p.isEmpty() || p == pageRoot) return pageRoot
        if (entries[p]?.isDir == true) return p
        if (entries["$pageRoot/$p"]?.isDir == true) return "$pageRoot/$p"
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

    const val TAG = "RfPort"

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
        /* Unbound: the caller detects the game from the entry page, then [GameIndex.bind]s it. */
        return GameIndex(entries, "")
    }

    /**
     * One indexed file's text, for the small data files this port reads for the record (the changelog
     * is 750 bytes). Returns null for a missing entry, a directory, an unknown/oversized size or a
     * failed read: the caller falls back to "unknown" rather than risking a big read on the UI thread.
     */
    fun readText(
        resolver: ContentResolver,
        treeUri: Uri,
        index: GameIndex,
        path: String,
        /* A game's changelog is whole-document JSON: CrossCode's is 78 KiB (Alabaster Dawn's is 750 B),
         * so a cap tuned to the smaller one silently loses the bigger game's version. */
        maxBytes: Long = 1024 * 1024,
    ): String? {
        val entry = index.find(path) ?: return null
        if (entry.isDir || entry.size <= 0 || entry.size > maxBytes) return null
        return try {
            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.docId))
                ?.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read $path", e)
            null
        }
    }

    /**
     * One file's bytes by its path under the picked root, **without indexing the tree**: one query per
     * segment. The entry screen draws each game's own art (see [GameProfile.artPath]) before any
     * game has been started, and walking a three-segment path is far cheaper than the full index.
     *
     * Returns null for a missing file, a directory, an oversized file or a failed read, so a caller
     * falls back to no image rather than half of one.
     */
    fun readByPath(
        resolver: ContentResolver,
        treeUri: Uri,
        path: String,
        maxBytes: Long = 2 * 1024 * 1024,
    ): ByteArray? {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        /* Every segment but the last is a directory we have to find by name. */
        for (segment in segments.dropLast(1)) {
            docId = findChild(resolver, treeUri, docId, segment) ?: return null
        }
        val fileId = findChild(resolver, treeUri, docId, segments.last()) ?: return null
        return try {
            resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, fileId))
                ?.use { stream -> stream.readBytes().takeIf { it.size <= maxBytes } }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read $path", e)
            null
        }
    }

    /**
     * Whether [path] still exists under the picked root, **without reading it**: the same one-query-
     * per-segment walk as [readByPath]. The entry screen uses this to tell a folder that is still
     * there from one that has been moved or deleted — the stored grant survives either way, so a URI
     * that resolves is not evidence that the folder does.
     */
    fun existsByPath(resolver: ContentResolver, treeUri: Uri, path: String): Boolean {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false
        var docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return false
        }
        for (segment in segments) {
            docId = findChild(resolver, treeUri, docId, segment) ?: return false
        }
        return true
    }

    /**
     * Whether the picked tree's own root document still exists. A deleted folder can leave its
     * persisted grant behind, and a [SafStore] on it then fails on every write instead of saying so.
     */
    fun treeResolves(resolver: ContentResolver, treeUri: Uri): Boolean = try {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId), PROJECTION, null, null, null
        )?.use { it.count > 0 } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "cannot resolve tree $treeUri", e)
        false
    }

    /** The document id of [name] under [parentDocId], or null when there is no such child. */
    private fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        name: String,
    ): String? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
            var found: String? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    found = cursor.getString(0)
                    break
                }
            }
            found
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot look up $name", e)
        null
    }

    /**
     * The picked folder's path under its volume, for the entry screen: the tree URI's document id is
     * `primary:Download/GameFolder`, so this is `Download/GameFolder`. The leaf alone cannot tell two
     * folders apart (two saves folders named `Saves`, say), and the path is what the user actually
     * sees in the picker.
     */
    fun pathOf(treeUri: Uri?): String? {
        if (treeUri == null) return null
        return try {
            val id = DocumentsContract.getTreeDocumentId(treeUri)
            val cut = id.indexOf(':')
            (if (cut == -1) id else id.substring(cut + 1)).ifEmpty { id }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read tree path from $treeUri", e)
            null
        }
    }

    /**
     * Last path segment of a picked tree URI's document id, for the pre-game screen. The document id
     * of a tree URI looks like `primary:Download/GameFolder`.
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
