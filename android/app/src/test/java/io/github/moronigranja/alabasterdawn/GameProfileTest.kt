package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The per-game seam: which profile a picked folder is, and the values the rest of the host derives
 * from it. All of it is pure (the profile is a table and the index is a map), so it is asserted here
 * instead of on a device.
 */
class GameProfileTest {

    private fun indexOf(vararg entries: Pair<String, Boolean>): GameIndex =
        GameIndex(entries.associate { (path, isDir) -> path to GameEntry("doc:$path", 1L, isDir) }, "")

    /** Alabaster Dawn's install root: the entry lives under `terra/`, game paths are page-relative. */
    private val alabaster = indexOf(
        "package.json" to false, "terra" to true, "terra/index.html" to false,
        "terra/dist/bundle.js" to false, "terra/data/database/changelog.json" to false,
        "terra/media/audio/sfx/x.ogg" to false,
    )

    /** CrossCode's install root: the entry lives under `assets/`, its fs paths already say `assets/`. */
    private val crosscode = indexOf(
        "package.json" to false, "assets" to true, "assets/node-webkit.html" to false,
        "assets/js/game.compiled.js" to false, "assets/data/changelog.json" to false,
        "assets/extension" to true,
    )

    @Test
    fun `the entry page picks the game`() {
        assertSame(GameProfile.ALABASTER_DAWN, GameProfile.detect(alabaster))
        assertSame(GameProfile.CROSSCODE, GameProfile.detect(crosscode))
    }

    @Test
    fun `a folder with no entry page is no game`() {
        assertNull(GameProfile.detect(indexOf("package.json" to false, "readme.txt" to false)))
        assertNull(GameProfile.detect(indexOf()))
    }

    @Test
    fun `each profile serves its own entry, root and shim`() {
        assertEquals("terra/index.html", GameProfile.ALABASTER_DAWN.indexHtml)
        assertEquals(
            "https://appassets.androidplatform.net/game/terra/index.html",
            GameProfile.ALABASTER_DAWN.entryUrl
        )
        assertEquals("terra/ada-shim.js", GameProfile.ALABASTER_DAWN.shimPath)
        assertEquals("ada-shim.js", GameProfile.ALABASTER_DAWN.shimAsset)

        assertEquals("assets/node-webkit.html", GameProfile.CROSSCODE.indexHtml)
        assertEquals(
            "https://appassets.androidplatform.net/game/assets/node-webkit.html",
            GameProfile.CROSSCODE.entryUrl
        )
        assertEquals("assets/cc-shim.js", GameProfile.CROSSCODE.shimPath)
        assertEquals("cc-shim.js", GameProfile.CROSSCODE.shimAsset)
    }

    @Test
    fun `the engine rewrites and the resolution reset are Alabaster Dawn's alone`() {
        assertEquals(true, GameProfile.ALABASTER_DAWN.rewrites)
        assertEquals(true, GameProfile.ALABASTER_DAWN.viewAlign)
        assertEquals("adaResetVideo", GameProfile.ALABASTER_DAWN.resetVideoParam)

        assertEquals(false, GameProfile.CROSSCODE.rewrites)
        assertEquals(false, GameProfile.CROSSCODE.viewAlign)
        assertNull("CrossCode has no stored resolution option to jam", GameProfile.CROSSCODE.resetVideoParam)
    }

    @Test
    fun `binding the index to a profile is what makes game paths resolve`() {
        /* The raw index is the one detection runs on; it resolves nothing game-relative. */
        assertEquals("", crosscode.pageRoot)

        val bound = crosscode.bind(GameProfile.CROSSCODE.pageRoot)
        assertEquals("assets", bound.pageRoot)
        /* Its own paths are root-relative and carry a trailing separator (the extension loader). */
        assertEquals("assets/extension", bound.findGameDir("assets/extension/"))
        /* Page-relative spellings still work: the probe costs one map lookup. */
        assertEquals("assets/extension", bound.findGameDir("extension/"))

        val ada = alabaster.bind(GameProfile.ALABASTER_DAWN.pageRoot)
        /* A page-relative path resolves to the file under the profile's root. */
        assertSame(
            ada.find("terra/media/audio/sfx/x.ogg"),
            ada.findGamePath("media/audio/sfx/x.ogg"),
        )
        assertEquals("terra", ada.findGameDir(""))
    }
}
