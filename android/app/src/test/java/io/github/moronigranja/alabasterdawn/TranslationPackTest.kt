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
 * The pack install and the read view: the checksum gate, which entries are extracted, and the
 * game-root-relative path rule. All of it runs on a plain [File] root, so no device is needed.
 */
class TranslationPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a checksum mismatch leaves nothing behind`() {
        val root = File(tmp.root, "cc-es")
        val bytes = zip("ccloader/x.js" to "x".toByteArray())
        assertThrows(IOException::class.java) {
            TranslationPack.installFrom(root, ByteArrayInputStream(bytes), "0".repeat(64))
        }
        assertFalse("no marker", File(root, "pack.json").exists())
        assertFalse("no extracted file", File(root, "ccloader/x.js").exists())
    }

    @Test
    fun `only the pack's trees are extracted, with no directory entries, xcf sources or escapes`() {
        val root = File(tmp.root, "cc-es")
        /* `../evil.js` would land outside `root` if the entry were trusted; the pack's own three
         * `.xcf` sources and its top-level `package.json` are deliberately not part of the install. */
        val bytes = zip(
            "ccloader/" to ByteArray(0),
            "package.json" to "{}".toByteArray(),
            "ccloader/x.js" to "//x".toByteArray(),
            "assets/mods/m/ccmod.json" to "{}".toByteArray(),
            "assets/mods/m/icon.xcf" to ByteArray(0),
            "../evil.js" to "nope".toByteArray(),
        )
        TranslationPack.installFrom(root, ByteArrayInputStream(bytes), sha256(bytes))
        assertTrue(File(root, "ccloader/x.js").isFile)
        assertTrue(File(root, "assets/mods/m/ccmod.json").isFile)
        assertTrue("marker written last", File(root, "pack.json").isFile)
        assertFalse(File(root, "package.json").exists())
        assertFalse(File(root, "assets/mods/m/icon.xcf").exists())
        assertFalse("no escape", File(tmp.root, "evil.js").exists())
    }

    @Test
    fun `the pack view reads only the pack and never escapes it`() {
        val root = File(tmp.root, "cc-es")
        val bytes = zip(
            "ccloader/x.js" to "//x".toByteArray(),
            "assets/mods/m/ccmod.json" to "{}".toByteArray(),
        )
        TranslationPack.installFrom(root, ByteArrayInputStream(bytes), sha256(bytes))
        val pack = TranslationPack.Pack(root)

        assertEquals("//x", String(pack.read("ccloader/x.js")!!))
        assertTrue(pack.exists("ccloader/x.js"))
        assertTrue(pack.exists("/ccloader/x.js"))
        assertEquals(listOf("x.js"), pack.list("ccloader"))
        assertEquals(true, pack.stat("ccloader/x.js")?.isDir?.not())

        /* The game's own files are not the pack's: a path only the game tree carries reads nothing. */
        assertNull(pack.read("assets/data/database.json"))
        assertFalse(pack.exists("assets/data/database.json"))

        /* The escape rule, same as FsBridge's. */
        assertNull(pack.read("../pack.json"))
        assertNull(pack.read("ccloader/../pack.json"))
        assertFalse(pack.exists("ccloader/../pack.json"))
        assertNull(pack.stat("../pack.json"))
    }
}
