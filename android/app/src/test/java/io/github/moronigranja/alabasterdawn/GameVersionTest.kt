package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameVersionTest {

    /* The shape of the engine's own `class VersionManager { … }` in bundle.js, up to the first
     * `getVersionString`: four `this.<n> = <digits>;` lines, the suffix, and the `old*` fields that
     * must not be mistaken for the current version. */
    private val bundleBlock = """
        class VersionManager {
            constructor() {
                this.db = { key: "changelog", file: "changelog.json" };
                this.major = 0;
                this.minor = 1;
                this.patch = 0;
                this.hotfix = 10;
                this.suffix = "Early Access";
                this.saveVersion = 0;
                this.oldMajor = 0;
                this.oldMinor = 0;
                this.oldPatch = 0;
            }
            onReset() {
                this.loadedVersion = this.getVersionString();
            }
    """.trimIndent()

    @Test
    fun `the changelog's newest entry is the release version`() {
        assertEquals(
            "0.1.0",
            GameVersion.fromChangelog("""{"entries":[{"version":"0.1.0"},{"version":"0.0.4"}]}""")
        )
    }

    @Test
    fun `a changelog with nothing to read is null`() {
        assertNull(GameVersion.fromChangelog("""{"entries":[]}"""))
        assertNull(GameVersion.fromChangelog("{}"))
        assertNull(GameVersion.fromChangelog(null))
        assertNull(GameVersion.fromChangelog("not json"))
    }

    @Test
    fun `the bundle's own build string carries the hotfix and the suffix`() {
        assertEquals("0.1.0-10 Early Access", GameVersion.fromBundle(bundleBlock))
    }

    @Test
    fun `a zero hotfix and an empty suffix add nothing`() {
        val released = bundleBlock
            .replace("this.hotfix = 10;", "this.hotfix = 0;")
            .replace("""this.suffix = "Early Access";""", """this.suffix = "";""")
        assertEquals("0.1.0", GameVersion.fromBundle(released))
    }

    @Test
    fun `bundle text that is not the measured shape is null`() {
        assertNull(GameVersion.fromBundle("class SomethingElse { }"))
        assertNull(GameVersion.fromBundle(bundleBlock.replace("this.minor = 1;", "")))
    }

    @Test
    fun `a version block that runs past the bound is not parsed`() {
        val far = bundleBlock.replace("onReset() {", "onReset() { /* " + "x".repeat(5000) + " */")
        assertNull(GameVersion.fromBundle(far))
    }
}
