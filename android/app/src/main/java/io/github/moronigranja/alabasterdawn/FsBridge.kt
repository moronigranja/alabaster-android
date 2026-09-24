package io.github.moronigranja.alabasterdawn

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class StatInfo(val mtimeMillis: Long, val size: Long, val isDir: Boolean)

class DirEntry(val name: String, val isDir: Boolean)

/**
 * The save root, in one of two shapes. Both preserve the Steam layout exactly
 * (`Saves/Default/Save_ID_0000.save`, `Saves/Default/System.save`, `Saves/Backups`, `Saves/Backups2`),
 * so a desktop `Saves/` folder can be copied in, and the files can be copied back out.
 *
 * Paths are relative to the save root and never contain `..` (rejected by [FsBridge]).
 */
interface SaveStore {
    /** Human readable location for the pre-game screen; null when the store is app-private. */
    val label: String?

    fun exists(rel: String): Boolean
    fun mkdir(rel: String): Boolean
    fun list(rel: String): List<DirEntry>?
    fun stat(rel: String): StatInfo?
    fun read(rel: String): String?
    fun write(rel: String, data: String): Boolean

    /** True only when a document was actually deleted (`StorageTools.deleteFile` counts successes). */
    fun rm(rel: String): Boolean
    fun rename(from: String, to: String): Boolean
}

/** Saves in the folder the user picked with SAF. */
class SafStore(private val resolver: ContentResolver, val treeUri: Uri) : SaveStore {

    private val rootDocId: String = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: Exception) {
        Log.e(TAG, "not a document tree: $treeUri", e)
        throw e
    }

    /** Relative path to document id. Only ever grown on create and pruned on delete/rename. */
    private val ids = ConcurrentHashMap<String, String>()

    init {
        ids[""] = rootDocId
    }

    override val label: String? get() = GameFiles.displayNameOf(treeUri)

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun childrenUri(docId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

    override fun exists(rel: String): Boolean = resolve(rel) != null

    override fun mkdir(rel: String): Boolean = ensureDir(rel) != null

    override fun list(rel: String): List<DirEntry>? {
        val docId = resolve(rel) ?: return null
        val out = ArrayList<DirEntry>()
        try {
            resolver.query(childrenUri(docId), PROJ_NAME_TYPE, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    out.add(DirEntry(name, cursor.getString(1) == Document.MIME_TYPE_DIR))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "list $rel failed", e)
            return null
        }
        return out
    }

    override fun stat(rel: String): StatInfo? {
        val docId = resolve(rel) ?: return null
        return try {
            resolver.query(docUri(docId), PROJ_STAT, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val mime = cursor.getString(0)
                val size = if (cursor.isNull(1)) 0L else cursor.getLong(1)
                val mtime = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                StatInfo(mtime, size, mime == Document.MIME_TYPE_DIR)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stat $rel failed", e)
            null
        }
    }

    override fun read(rel: String): String? {
        val docId = resolve(rel) ?: return null
        return try {
            resolver.openInputStream(docUri(docId))?.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.e(TAG, "read $rel failed", e)
            null
        }
    }

    override fun write(rel: String, data: String): Boolean =
        writeInto(rel, data.toByteArray(Charsets.UTF_8))

    override fun rm(rel: String): Boolean {
        val docId = resolve(rel) ?: return false
        val ok = try {
            DocumentsContract.deleteDocument(resolver, docUri(docId))
        } catch (e: Exception) {
            Log.e(TAG, "delete $rel failed", e)
            false
        }
        if (ok) forget(rel)
        return ok
    }

    /**
     * SAF's `renameDocument` cannot move between parents, but the game's save rotation does exactly
     * that (`Default/x -> Backups/x -> Backups2/x`). Cross-directory moves are therefore a copy
     * followed by a delete; `renameDocument` is only an optimisation inside one directory.
     */
    override fun rename(from: String, to: String): Boolean {
        val srcDocId = resolve(from) ?: return false
        val srcParent = parentOf(from)
        if (srcParent == parentOf(to)) {
            try {
                val renamed = DocumentsContract.renameDocument(resolver, docUri(srcDocId), nameOf(to))
                if (renamed != null) {
                    forget(from)
                    ids[to] = DocumentsContract.getDocumentId(renamed)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "renameDocument $from failed, falling back to copy+delete", e)
            }
        }
        val bytes = try {
            resolver.openInputStream(docUri(srcDocId))?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "rename $from -> $to: cannot read source", e)
            null
        } ?: return false
        if (!writeInto(to, bytes)) return false
        return rm(from)
    }

    /**
     * Create-or-overwrite by display name. `DocumentsContract.createDocument` de-duplicates a
     * colliding name (`Save_ID_0000 (1).save`), which would break both the naming scheme and the
     * slot rotation, so an existing document is always reused instead of created.
     */
    private fun writeInto(rel: String, bytes: ByteArray): Boolean {
        val name = nameOf(rel)
        if (name.isEmpty()) return false
        val parentRel = parentOf(rel)
        val parentDocId = ensureDir(parentRel) ?: return false
        val target = findChild(parentDocId, name)
        return try {
            val out = if (target != null) {
                ids[rel] = target
                resolver.openOutputStream(docUri(target), "wt")
            } else {
                ids.remove(rel)
                val created = DocumentsContract.createDocument(
                    resolver, docUri(parentDocId), FILE_MIME, name
                ) ?: return false
                ids[rel] = DocumentsContract.getDocumentId(created)
                resolver.openOutputStream(created, "wt")
            } ?: return false
            out.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "write $rel failed", e)
            ids.remove(rel)
            false
        }
    }

    private fun resolve(rel: String): String? {
        if (rel.isEmpty()) return rootDocId
        ids[rel]?.let { return it }
        val parent = resolve(parentOf(rel)) ?: return null
        val child = findChild(parent, nameOf(rel)) ?: return null
        ids[rel] = child
        return child
    }

    private fun ensureDir(rel: String): String? {
        if (rel.isEmpty()) return rootDocId
        ids[rel]?.let { return it }
        val parent = ensureDir(parentOf(rel)) ?: return null
        val name = nameOf(rel)
        findChild(parent, name)?.let { ids[rel] = it; return it }
        return try {
            val created = DocumentsContract.createDocument(
                resolver, docUri(parent), Document.MIME_TYPE_DIR, name
            ) ?: return null
            val docId = DocumentsContract.getDocumentId(created)
            ids[rel] = docId
            docId
        } catch (e: Exception) {
            Log.e(TAG, "mkdir $rel failed", e)
            null
        }
    }

    /** One query over the parent's children - the source of truth for names, cache or not. */
    private fun findChild(parentDocId: String, name: String): String? = try {
        resolver.query(childrenUri(parentDocId), PROJ_NAME_TYPE_ID, null, null, null)?.use { cursor ->
            var found: String? = null
            while (cursor.moveToNext()) {
                if (name == cursor.getString(0)) {
                    found = cursor.getString(1)
                    break
                }
            }
            found
        }
    } catch (e: Exception) {
        Log.e(TAG, "cannot list children of $parentDocId", e)
        null
    }

    private fun forget(rel: String) {
        ids.remove(rel)
        val prefix = "$rel/"
        ids.keys.removeAll { it.startsWith(prefix) }
    }

    companion object {
        private const val TAG = "AdaPort"

        /** Deliberate: `MimeTypeMap.getExtensionFromMimeType` returns null for it, so providers keep
         *  the game's own `....save` name instead of appending an extension. */
        private const val FILE_MIME = "application/octet-stream"
        private val PROJ_NAME_TYPE = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        private val PROJ_NAME_TYPE_ID =
            arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID)
        private val PROJ_STAT = arrayOf(
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
    }
}

/** Directory part of a relative path; the empty string is the root. */
private fun parentOf(rel: String): String {
    val cut = rel.lastIndexOf('/')
    return if (cut == -1) "" else rel.substring(0, cut)
}

/** Last segment of a relative path. */
private fun nameOf(rel: String): String = rel.substringAfterLast('/')

/** Saves in app-private storage, used only when the user has not granted a saves folder. */
class FileStore(private val base: File) : SaveStore {

    override val label: String? get() = null

    private fun file(rel: String): File = File(base, rel)

    override fun exists(rel: String): Boolean = rel.isEmpty() || file(rel).exists()

    override fun mkdir(rel: String): Boolean = rel.isEmpty() || file(rel).mkdirs() || file(rel).isDirectory

    override fun list(rel: String): List<DirEntry>? {
        val dir = file(rel)
        val names = dir.list() ?: return null
        return names.map { DirEntry(it, File(dir, it).isDirectory) }
    }

    override fun stat(rel: String): StatInfo? {
        val f = file(rel)
        if (!f.exists()) return null
        return StatInfo(f.lastModified(), f.length(), f.isDirectory)
    }

    override fun read(rel: String): String? = try {
        file(rel).readText(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    override fun write(rel: String, data: String): Boolean = try {
        val f = file(rel)
        f.parentFile?.mkdirs()
        f.writeText(data, Charsets.UTF_8)
        true
    } catch (e: Exception) {
        Log.e("AdaPort", "write $rel failed", e)
        false
    }

    override fun rm(rel: String): Boolean {
        val f = file(rel)
        return f.exists() && f.deleteRecursively()
    }

    override fun rename(from: String, to: String): Boolean {
        if (!file(from).exists()) return false
        val target = file(to)
        target.parentFile?.mkdirs()
        return file(from).renameTo(target)
    }

    companion object {
        fun appPrivate(context: Context): FileStore = FileStore(File(context.filesDir, "saves"))
    }
}

/**
 * Backs the shim's `fs` object. Two namespaces:
 *
 * | path | backend | access |
 * |---|---|---|
 * | `/saves/...` | the picked saves tree | read-write |
 * | anything else (`media/gui/x.png`) | the picked game tree, from [GameIndex] | read-only |
 *
 * `nw.App.dataPath` is the literal `/saves`; the game concatenates `/Saves/...` onto it, so the
 * whole Steam layout lands inside the picked folder. Nothing here ever throws across the JNI
 * boundary: failures come back as `false` / `null`.
 */
class FsBridge(
    private val resolver: ContentResolver,
    private val gameTreeUri: Uri,
    private val gameIndex: GameIndex,
    private val store: SaveStore,
) {

    fun exists(path: String): Boolean {
        if (isSavePath(path)) {
            val rel = saveRel(path) ?: return false
            return store.exists(rel)
        }
        return gameIndex.findGamePath(path) != null
    }

    fun mkdir(path: String): Boolean {
        val rel = saveRel(path) ?: return false
        return store.mkdir(rel)
    }

    fun list(path: String): List<DirEntry>? {
        if (isSavePath(path)) {
            val rel = saveRel(path) ?: return null
            return store.list(rel)
        }
        val dir = gameIndex.findGameDir(path) ?: return null
        return gameIndex.children(dir)
    }

    fun stat(path: String): StatInfo? {
        if (isSavePath(path)) {
            val rel = saveRel(path) ?: return null
            return store.stat(rel)
        }
        val entry = gameIndex.findGamePath(path) ?: return null
        return StatInfo(0L, entry.size, entry.isDir)
    }

    fun read(path: String): String? {
        if (isSavePath(path)) {
            val rel = saveRel(path) ?: return null
            return store.read(rel)
        }
        val entry = gameIndex.findGamePath(path) ?: return null
        if (entry.isDir) return null
        return try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(gameTreeUri, entry.docId)
            resolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.e(TAG, "cannot read game file $path", e)
            null
        }
    }

    fun write(path: String, data: String): Boolean {
        if (!isSavePath(path)) {
            Log.w(TAG, "ignored write outside the saves folder: $path")
            return false
        }
        val rel = saveRel(path) ?: return false
        return store.write(rel, data)
    }

    fun rm(path: String): Boolean {
        if (!isSavePath(path)) {
            Log.w(TAG, "ignored delete outside the saves folder: $path")
            return false
        }
        val rel = saveRel(path) ?: return false
        return store.rm(rel)
    }

    fun rename(from: String, to: String): Boolean {
        if (!isSavePath(from) || !isSavePath(to)) {
            Log.w(TAG, "ignored rename outside the saves folder: $from -> $to")
            return false
        }
        val src = saveRel(from) ?: return false
        val dst = saveRel(to) ?: return false
        return store.rename(src, dst)
    }

    fun copy(from: String, to: String): Boolean {
        if (!isSavePath(from) || !isSavePath(to)) {
            Log.w(TAG, "ignored copy outside the saves folder: $from -> $to")
            return false
        }
        val src = saveRel(from) ?: return false
        val dst = saveRel(to) ?: return false
        val data = store.read(src) ?: return false
        return store.write(dst, data)
    }

    val storeLabel: String? get() = store.label

    private fun isSavePath(path: String): Boolean =
        path == SAVES_ROOT || path.startsWith("$SAVES_ROOT/")

    private fun saveRel(path: String): String? = normalize(path.removePrefix(SAVES_ROOT))

    /** Splits, drops empty and `.` segments, rejects `..`. Backslashes stay literal on purpose, so
     *  the Windows-style paths the game probes (`/Default\Saves\Default\...`) simply miss. */
    private fun normalize(path: String): String? {
        val segments = ArrayList<String>(4)
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> return null
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    companion object {
        private const val TAG = "AdaPort"
        const val SAVES_ROOT = "/saves"
    }
}
