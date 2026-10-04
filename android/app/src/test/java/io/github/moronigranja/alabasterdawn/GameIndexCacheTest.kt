package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameIndexCacheTest {

    /** A small index with a directory, a file and a nested file. */
    private fun index(): GameIndex = GameIndex(
        mapOf(
            "terra" to GameEntry("root/terra", 0L, true),
            "terra/index.html" to GameEntry("root/terra/index.html", 1234L, false),
            "terra/data/changelog.json" to GameEntry("root/terra/data/changelog.json", 42L, false),
        ),
        "",
    )

    /** Every entry as (path, docId, size, isDir), so two indexes compare without GameEntry equality. */
    private fun shape(i: GameIndex): List<List<Any>> =
        i.paths().map { p ->
            val e = i.find(p)!!
            listOf(p, e.docId, e.size, e.isDir)
        }

    @Test
    fun `a round trip yields the same entries unbound`() {
        val original = index()
        val decoded = GameIndexCache.decode(
            GameIndexCache.encode(original, "primary:Download/Game", "1.4.2"),
            "primary:Download/Game",
            "1.4.2",
        )
        assertEquals(shape(original), shape(decoded!!))
        assertEquals("", decoded.pageRoot)
    }

    @Test
    fun `a different tree rejects the cache`() {
        val text = GameIndexCache.encode(index(), "primary:Download/Game", "1.4.2")
        assertNull(GameIndexCache.decode(text, "primary:Download/Other", "1.4.2"))
    }

    @Test
    fun `a different version rejects the cache`() {
        val text = GameIndexCache.encode(index(), "primary:Download/Game", "1.4.2")
        assertNull(GameIndexCache.decode(text, "primary:Download/Game", "1.5.0"))
    }

    @Test
    fun `a null version on both sides is accepted`() {
        val text = GameIndexCache.encode(index(), "primary:Download/Game", null)
        val decoded = GameIndexCache.decode(text, "primary:Download/Game", null)
        assertEquals(shape(index()), shape(decoded!!))
    }

    @Test
    fun `a truncated last line rejects the cache`() {
        val text = GameIndexCache.encode(index(), "primary:Download/Game", "1.4.2")
        /* A write cut short leaves the last line without its final (docId) field. */
        val cut = text.substring(0, text.lastIndexOf('\t'))
        assertNull(GameIndexCache.decode(cut, "primary:Download/Game", "1.4.2"))
    }

    @Test
    fun `a line with the wrong field count rejects the cache`() {
        val text = GameIndexCache.encode(index(), "primary:Download/Game", "1.4.2")
        assertNull(
            GameIndexCache.decode(text + "E\t0\t5\n", "primary:Download/Game", "1.4.2")
        )
    }

    @Test
    fun `a file with the wrong header rejects the cache`() {
        val text = GameIndexCache.encode(index(), "primary:Download/Game", "1.4.2")
            .replaceFirst("#ada-index 1", "#ada-index 2")
        assertNull(GameIndexCache.decode(text, "primary:Download/Game", "1.4.2"))
    }
}
