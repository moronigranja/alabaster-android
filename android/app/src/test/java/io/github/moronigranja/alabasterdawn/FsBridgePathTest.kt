package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The save/game namespace rule and the save-relative path mapping. Both engine generations reach it:
 * the released one with `/Saves/Default/`, the demo-era one with `\Saves\Default\` appended to
 * `nw.App.dataPath` (which is the literal `/saves`).
 */
class FsBridgePathTest {

    @Test
    fun `only the save root and what is under it is a save path`() {
        assertTrue(FsBridge.isSavePath("/saves"))
        assertTrue(FsBridge.isSavePath("/saves/"))
        assertTrue(FsBridge.isSavePath("/saves/Saves/Default/x.save"))
        /* The demo-era engine: dataPath + "\Saves\Default\". */
        assertTrue(FsBridge.isSavePath("/saves\\Saves\\Default\\"))
    }

    @Test
    fun `the game tree and lookalikes stay out of the save namespace`() {
        assertFalse(FsBridge.isSavePath("/savesx"))
        assertFalse(FsBridge.isSavePath("media/gui/x.png"))
        /* What the game's own storage fix probes: it must miss, not resolve. */
        assertFalse(FsBridge.isSavePath("/Default\\Saves\\Default\\"))
        assertFalse(FsBridge.isSavePath("/User Data/Default/Saves/Default/"))
    }

    @Test
    fun `both separators map onto one save-relative path`() {
        assertEquals("Saves/Default", FsBridge.saveRel("/saves/Saves/Default/"))
        assertEquals("Saves/Default", FsBridge.saveRel("/saves\\Saves\\Default\\"))
        assertEquals("Saves", FsBridge.saveRel("/saves\\Saves\\"))
        assertEquals("Saves/Backups2/Save_ID_0000.save",
            FsBridge.saveRel("/saves\\Saves\\Backups2\\Save_ID_0000.save"))
    }

    @Test
    fun `the root maps to the empty path`() {
        assertEquals("", FsBridge.saveRel("/saves"))
        assertEquals("", FsBridge.saveRel("/saves/"))
    }

    @Test
    fun `empty, dot and duplicate segments collapse`() {
        assertEquals("a/b/c", FsBridge.saveRel("/saves//a/./b//c"))
        assertEquals("a/b", FsBridge.saveRel("/saves\\a\\b"))
    }

    @Test
    fun `a path that climbs out of the save root is refused`() {
        assertNull(FsBridge.saveRel("/saves/../etc"))
        assertNull(FsBridge.saveRel("/saves/Saves/../../x"))
        assertNull(FsBridge.saveRel("/saves\\..\\x"))
    }
}
