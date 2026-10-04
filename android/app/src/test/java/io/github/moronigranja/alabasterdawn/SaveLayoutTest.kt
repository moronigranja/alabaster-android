package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two shapes a picked saves folder can have: the game's own subfolder copied in whole, or that
 * subfolder's contents copied flat (the desktop folder's contents dropped straight in). The engine
 * asks for `Saves/…`/`Default/…` either way; [SaveLayout.detect] decides which folder answers it.
 */
class SaveLayoutTest {

    /** A store in memory with explicit directories, so `stat().isDir` means something. */
    private class FakeStore : SaveStore {
        val files = HashMap<String, String>()
        val dirs = HashSet<String>()

        override val label: String? get() = "Fake"

        override fun exists(rel: String): Boolean =
            rel.isEmpty() || files.containsKey(rel) || dirs.contains(rel)

        override fun mkdir(rel: String): Boolean { dirs.add(rel); return true }
        override fun list(rel: String): List<DirEntry> = emptyList()
        override fun stat(rel: String): StatInfo? = when {
            dirs.contains(rel) -> StatInfo(0L, 0L, isDir = true)
            files.containsKey(rel) -> StatInfo(0L, files.getValue(rel).length.toLong(), isDir = false)
            else -> null
        }
        override fun read(rel: String): String? = files[rel]
        override fun write(rel: String, data: String): Boolean = run { files[rel] = data; true }
        override fun rm(rel: String): Boolean = files.remove(rel) != null
        override fun rename(from: String, to: String): Boolean {
            val v = files.remove(from) ?: return false
            files[to] = v
            return true
        }
    }

    private fun adaFlat() = FakeStore().apply {
        dirs.add("Default"); dirs.add("Backups")
        files["Default/Save_ID_0000.save"] = "flat"
        files["pad-layout.json"] = "port"
    }

    private fun ccFlat() = FakeStore().apply {
        files["cc.save"] = "flat"; files["cc.save.backup"] = "old"
    }

    @Test
    fun `a folder with the game's own subfolder is served as it is`() {
        val store = FakeStore().apply {
            dirs.add("Saves"); files["Saves/Default/Save_ID_0000.save"] = "whole"
        }
        assertSame(store, SaveLayout.detect(store, GameProfile.ALABASTER_DAWN))
    }

    @Test
    fun `an empty or new folder keeps the standard shape`() {
        val store = FakeStore()
        assertSame(store, SaveLayout.detect(store, GameProfile.ALABASTER_DAWN))
        assertSame(store, SaveLayout.detect(store, GameProfile.CROSSCODE))
    }

    @Test
    fun `flat Alabaster Dawn saves answer the game's Saves prefix`() {
        val store = SaveLayout.detect(adaFlat(), GameProfile.ALABASTER_DAWN)
        assertEquals("flat", store.read("Saves/Default/Save_ID_0000.save"))
        assertTrue(store.exists("Saves/Default/Save_ID_0000.save"))
    }

    @Test
    fun `flat Alabaster Dawn writes land where the flat saves already are`() {
        val base = adaFlat()
        val store = SaveLayout.detect(base, GameProfile.ALABASTER_DAWN)
        store.write("Saves/Backups/Save_ID_0000.save", "rotated")
        /* The write reached the flat path, not a new `Saves/` tree. */
        assertEquals("rotated", base.files["Backups/Save_ID_0000.save"])
        assertNull(base.files["Saves/Backups/Save_ID_0000.save"])
        assertEquals("rotated", store.read("Saves/Backups/Save_ID_0000.save"))
    }

    @Test
    fun `flat CrossCode saves answer the game's Default prefix`() {
        val store = SaveLayout.detect(ccFlat(), GameProfile.CROSSCODE)
        assertEquals("flat", store.read("Default/cc.save"))
        assertEquals("old", store.read("Default/cc.save.backup"))
    }

    @Test
    fun `the port's own root files and deeper files are left alone`() {
        val store = SaveLayout.detect(adaFlat(), GameProfile.ALABASTER_DAWN)
        assertEquals("port", store.read("pad-layout.json"))
        assertNull(store.read("Savesx/Default/x"))
    }
}
