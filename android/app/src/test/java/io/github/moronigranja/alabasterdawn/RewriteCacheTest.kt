package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The byte budget and the hit counting behind "; rewrite cached=… hits=…". */
class RewriteCacheTest {

    @Test
    fun `a repeated key is a hit and returns the stored array`() {
        val cache = RewriteCache()
        val body = "rewritten".toByteArray()
        assertTrue(cache.put("terra/dist/bundle.js", body))
        assertNull("a key never stored is absent", cache.get("terra/data/x.frag"))
        assertSame(body, cache.get("terra/dist/bundle.js"))
        assertSame(body, cache.get("terra/dist/bundle.js"))
        assertEquals("both lookups were served from the cache", 2, cache.hits())
        assertEquals(1, cache.size())
    }

    @Test
    fun `an entry larger than the budget is refused and stores nothing`() {
        val cache = RewriteCache(maxBytes = 4L)
        assertFalse(cache.put("big", ByteArray(5)))
        assertNull(cache.get("big"))
        assertEquals(0, cache.size())
        assertEquals(0L, cache.bytes())
    }

    @Test
    fun `the budget counts bytes and stops accepting past it`() {
        val cache = RewriteCache(maxBytes = 4L)
        assertTrue(cache.put("a", ByteArray(3)))
        assertFalse("2 more bytes would be 5 of 4", cache.put("b", ByteArray(2)))
        assertEquals(3L, cache.bytes())
        assertEquals(1, cache.size())
    }

    @Test
    fun `the summary reads raw counts`() {
        val cache = RewriteCache(maxBytes = 4L)
        cache.put("a", ByteArray(3))
        cache.get("a")
        cache.get("a")
        assertEquals("cached=1 hits=2 3/4 bytes", cache.summary())
    }

    @Test
    fun `overwriting a key does not double-count its bytes`() {
        val cache = RewriteCache(maxBytes = 4L)
        cache.put("a", ByteArray(3))
        assertTrue(cache.put("a", ByteArray(4)))
        assertEquals(4L, cache.bytes())
        assertEquals(1, cache.size())
    }
}
