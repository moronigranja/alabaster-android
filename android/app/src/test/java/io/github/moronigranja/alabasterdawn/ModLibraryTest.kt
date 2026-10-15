package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The mod library: the distribution checksum gate, the archive-shape rules (a single mod, a raw
 * CCLoader build, a distribution, a wrapper directory), the on/off/remove moves, the listing, and the
 * path rule the served view applies. All of it runs on a plain [File] root.
 */
class ModLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        val file = File(tmp.root, "archive-${System.nanoTime()}.zip")
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun root(): File = tmp.newFolder("cc")

    private fun json(id: String, title: String = id, version: String = "1.0.0"): ByteArray =
        "{\"id\":\"$id\",\"title\":\"$title\",\"version\":\"$version\"}".toByteArray()

    @Test
    fun `a distribution checksum mismatch leaves nothing behind`() {
        val root = root()
        val bytes = File(tmp.root, "d.zip").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertThrows(IOException::class.java) {
            ModLibrary.installDistributionFrom(root, bytes.inputStream(), "0".repeat(64))
        }
        assertFalse(File(root, "ccloader/main.html").exists())
        assertFalse(File(root, "assets/mods/x/ccmod.json").exists())
    }

    @Test
    fun `the distribution extracts only the loader and the mods`() {
        val root = root()
        val bytes = zip(
            "ccloader/main.html" to "<html></html>".toByteArray(),
            "ccloader/dist/main.js" to "//loader".toByteArray(),
            "package.json" to "{}".toByteArray(),
            "assets/mods/m/ccmod.json" to json("m"),
            "assets/mods/m/icon.xcf" to ByteArray(0),
            "../evil.js" to "nope".toByteArray(),
        )
        ModLibrary.installDistributionFrom(root, bytes.inputStream(), sha256(bytes.readBytes()))
        assertTrue(File(root, "ccloader/main.html").isFile)
        assertTrue(File(root, "assets/mods/m/ccmod.json").isFile)
        assertFalse("top-level package.json is not part of the library", File(root, "package.json").exists())
        assertFalse("no .xcf", File(root, "assets/mods/m/icon.xcf").exists())
        assertFalse("no escape", File(tmp.root, "evil.js").exists())
        assertTrue(ModLibrary.loaderInstalled(root))
    }

    @Test
    fun `a single mod archive is filed under its own id`() {
        val root = root()
        val archive = zip(
            "ccmod.json" to json("my-mod", "My Mod"),
            "prestart.js" to "//x".toByteArray(),
            "assets/media/thing.png" to ByteArray(3),
        )
        val installed = ModLibrary.installArchiveFrom(root, archive, "MyMod.ccmod")
        assertEquals(listOf("my-mod"), installed.mods)
        assertFalse(installed.loader)
        assertTrue(File(root, "assets/mods/my-mod/ccmod.json").isFile)
        assertTrue(File(root, "assets/mods/my-mod/assets/media/thing.png").isFile)
    }

    @Test
    fun `a manifest id with spaces or plus becomes a safe directory name`() {
        val root = root()
        val archive = zip("ccmod.json" to json("New game++"))
        val installed = ModLibrary.installArchiveFrom(root, archive, "ngpp.zip")
        assertEquals(listOf("New_game__"), installed.mods)
        assertTrue(File(root, "assets/mods/New_game__/ccmod.json").isFile)
    }

    @Test
    fun `a wrapper directory is stripped before the shape is decided`() {
        val root = root()
        val archive = zip(
            "Foo-1.2.3/ccmod.json" to json("foo"),
            "Foo-1.2.3/js/x.js" to "//x".toByteArray(),
        )
        val installed = ModLibrary.installArchiveFrom(root, archive, "foo.zip")
        assertEquals(listOf("foo"), installed.mods)
        assertTrue(File(root, "assets/mods/foo/ccmod.json").isFile)
        assertTrue(File(root, "assets/mods/foo/js/x.js").isFile)
    }

    @Test
    fun `a bare CCLoader build becomes the loader package`() {
        val root = root()
        val archive = zip(
            "main.html" to "<html></html>".toByteArray(),
            "dist/main.js" to "//loader".toByteArray(),
            "metadata.json" to "{\"version\":\"3.0.0-alpha\"}".toByteArray(),
        )
        val installed = ModLibrary.installArchiveFrom(root, archive, "ccloader3.zip")
        assertTrue(installed.loader)
        assertTrue(File(root, "ccloader/main.html").isFile)
        assertTrue(File(root, "ccloader/dist/main.js").isFile)
        assertEquals("3.0.0-alpha", ModLibrary.loaderVersion(root))
    }

    @Test
    fun `an archive that is none of the shapes is refused`() {
        val root = root()
        val archive = zip("readme.md" to "hello".toByteArray())
        assertThrows(IOException::class.java) {
            ModLibrary.installArchiveFrom(root, archive, "readme.zip")
        }
    }

    @Test
    fun `the library lists, disables and removes mods, and reads the locale mod`() {
        val root = root()
        val archive = zip(
            "ccloader/main.html" to "<html></html>".toByteArray(),
            "assets/mods/CrossCode-Esp/ccmod.json" to json("CrossCode-Esp", "Español"),
            "assets/mods/other/ccmod.json" to json("other", "Other", "2.0"),
        )
        ModLibrary.installArchiveFrom(root, archive, "dist.zip")

        assertTrue(ModLibrary.loaderInstalled(root))
        assertEquals(ModLibrary.LOCALE, ModLibrary.locale(root))
        assertEquals(listOf("CrossCode-Esp", "other"), ModLibrary.list(root).map { it.id })
        assertTrue(ModLibrary.list(root).all { it.enabled })

        assertTrue(ModLibrary.setEnabled(root, "other", false))
        assertTrue(File(root, "disabled/other/ccmod.json").isFile)
        assertFalse(File(root, "assets/mods/other").exists())
        val disabled = ModLibrary.list(root).first { it.id == "other" }
        assertFalse(disabled.enabled)
        assertEquals("Other", disabled.title)

        assertTrue(ModLibrary.remove(root, "other"))
        assertFalse(File(root, "disabled/other").exists())
        assertEquals(listOf("CrossCode-Esp"), ModLibrary.list(root).map { it.id })

        /* Removing the distribution takes its mods and the loader, turning the locale back off. */
        ModLibrary.removeDistribution(root)
        assertFalse(ModLibrary.loaderInstalled(root))
        assertNull(ModLibrary.locale(root))
        assertTrue(ModLibrary.list(root).isEmpty())
    }

    @Test
    fun `the served view reads the loader and the mods, and never escapes the root`() {
        val root = root()
        val archive = zip(
            "ccloader/main.html" to "<html></html>".toByteArray(),
            "assets/mods/m/ccmod.json" to json("m"),
        )
        ModLibrary.installArchiveFrom(root, archive, "dist.zip")
        val view = ModLibrary.open(root)!!
        assertEquals("<html></html>", String(view.read("ccloader/main.html")!!))
        assertTrue(view.exists("assets/mods/m/ccmod.json"))
        assertEquals(listOf("m"), view.list("assets/mods"))
        assertEquals(false, view.stat("ccloader/main.html")?.isDir)

        assertNull(view.read("assets/data/database.json"))
        assertFalse(view.exists("assets/data/database.json"))
        assertNull(view.read("../ccmod.json"))
        assertNull(view.read("ccloader/../ccmod.json"))
        assertNull(ModLibrary.open(File(tmp.root, "nothing")))
    }
}
