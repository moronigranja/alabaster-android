package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carry from app storage into a folder the user picked: what a save that only exists in one
 * place does, and what happens when both have a copy.
 *
 * This is the rule that decides whether a player's progress survives picking a saves folder, so it is
 * pinned here rather than only on a device (where app storage cannot be read back without root).
 */
class SaveCarryTest {

    /** A store in memory: path -> (text, mtime). Directories are implied by the paths under them. */
    private class FakeStore : SaveStore {
        val files = HashMap<String, Pair<String, Long>>()
        /** Paths whose [read] returns null (a document the provider refuses), to test the skip. */
        val unreadable = HashSet<String>()

        override val label: String? get() = null

        private fun children(dir: String): List<DirEntry> {
            val head = if (dir.isEmpty()) "" else "$dir/"
            val seen = LinkedHashSet<String>()
            val out = ArrayList<DirEntry>()
            for (key in files.keys) {
                if (!key.startsWith(head)) continue
                val rest = key.substring(head.length)
                val cut = rest.indexOf('/')
                if (cut == -1) {
                    if (seen.add(rest)) out.add(DirEntry(rest, isDir = false))
                } else {
                    val name = rest.substring(0, cut)
                    if (seen.add(name)) out.add(DirEntry(name, isDir = true))
                }
            }
            return out
        }

        override fun exists(rel: String): Boolean = files.containsKey(rel)

        override fun mkdir(rel: String): Boolean = true

        override fun list(rel: String): List<DirEntry> = children(rel)

        override fun stat(rel: String): StatInfo? =
            files[rel]?.let { StatInfo(it.second, it.first.length.toLong(), isDir = false) }

        override fun read(rel: String): String? =
            if (unreadable.contains(rel)) null else files[rel]?.first

        override fun write(rel: String, data: String): Boolean {
            files[rel] = data to 0L
            return true
        }

        override fun rm(rel: String): Boolean = files.remove(rel) != null

        override fun rename(from: String, to: String): Boolean {
            val value = files.remove(from) ?: return false
            files[to] = value
            return true
        }

        fun put(rel: String, text: String, mtime: Long) {
            files[rel] = text to mtime
        }
    }

    @Test
    fun `a save that only app storage has is carried into the folder`() {
        val app = FakeStore().apply { put("Saves/Default/System.save", "progress", 100) }
        val folder = FakeStore()

        assertEquals(listOf("Saves/Default/System.save"), SaveCarry.copyNewer(app, folder))
        assertEquals("progress", folder.files["Saves/Default/System.save"]?.first)
    }

    @Test
    fun `the folder's own newer save is kept, the older app-storage one is not copied over it`() {
        val app = FakeStore().apply { put("Saves/Default/System.save", "old", 100) }
        val folder = FakeStore().apply { put("Saves/Default/System.save", "new", 200) }

        assertTrue(SaveCarry.copyNewer(app, folder).isEmpty())
        assertEquals("new", folder.files["Saves/Default/System.save"]?.first)
    }

    @Test
    fun `a newer app-storage save replaces the folder's older copy`() {
        val app = FakeStore().apply { put("Saves/Default/System.save", "newer", 300) }
        val folder = FakeStore().apply { put("Saves/Default/System.save", "older", 200) }

        assertEquals(listOf("Saves/Default/System.save"), SaveCarry.copyNewer(app, folder))
        assertEquals("newer", folder.files["Saves/Default/System.save"]?.first)
    }

    @Test
    fun `the carry never deletes from the source`() {
        val app = FakeStore().apply { put("Saves/Default/System.save", "progress", 100) }
        val folder = FakeStore().apply { put("Saves/Default/System.save", "same", 100) }

        SaveCarry.copyNewer(app, folder)

        assertTrue("app storage keeps its copy", app.files.containsKey("Saves/Default/System.save"))
    }

    @Test
    fun `an empty app storage carries nothing and writes nothing`() {
        val app = FakeStore()
        val folder = FakeStore().apply { put("Saves/Default/System.save", "mine", 100) }

        assertTrue(SaveCarry.copyNewer(app, folder).isEmpty())
        assertEquals("mine", folder.files["Saves/Default/System.save"]?.first)
        assertEquals(1, folder.files.size)
    }

    @Test
    fun `every file under a nested directory is carried, at any depth`() {
        val app = FakeStore().apply {
            put("Saves/Default/System.save", "a", 10)
            put("Saves/Backups/Save_ID_auto.save", "b", 10)
            put("pad-layout.json", "c", 10)
        }
        val folder = FakeStore()

        val copied = SaveCarry.copyNewer(app, folder)

        assertEquals(3, copied.size)
        assertEquals("a", folder.files["Saves/Default/System.save"]?.first)
        assertEquals("b", folder.files["Saves/Backups/Save_ID_auto.save"]?.first)
        assertEquals("c", folder.files["pad-layout.json"]?.first)
    }

    @Test
    fun `a file the provider refuses to read is skipped, not written as empty`() {
        val app = FakeStore().apply {
            put("Saves/Default/System.save", "progress", 10)
            put("Saves/Default/Broken.save", "gone", 10)
            unreadable.add("Saves/Default/Broken.save")
        }
        val folder = FakeStore()

        assertEquals(listOf("Saves/Default/System.save"), SaveCarry.copyNewer(app, folder))
        assertFalse("the unreadable one is left alone", folder.files.containsKey("Saves/Default/Broken.save"))
    }

    @Test
    fun `a blank save is carried like any other`() {
        val app = FakeStore().apply { put("Saves/Default/System.save", "", 10) }
        val folder = FakeStore()

        assertEquals(listOf("Saves/Default/System.save"), SaveCarry.copyNewer(app, folder))
        assertTrue(folder.files.containsKey("Saves/Default/System.save"))
    }
}
