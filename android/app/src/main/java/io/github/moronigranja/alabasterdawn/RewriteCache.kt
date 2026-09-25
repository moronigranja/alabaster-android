package io.github.moronigranja.alabasterdawn

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The rewritten bytes of the few assets [GameAssetHandler] does not serve verbatim: `bundle.js` is
 * 12.7 MB of SAF read plus a string replace on every request, and every `.frag` otherwise costs a
 * second read of its paired `.vert`. The picked game tree is read-only, so the result is the same
 * for the whole process lifetime: re-reading and re-rewriting is pure provider latency.
 *
 * Bounded by total bytes rather than entry count (the tree holds 2 652 files, of which only a
 * handful are rewritten and worth holding). Concurrent, because [GameAssetHandler.handle] runs on
 * the WebView's background threads. Never throws.
 */
class RewriteCache(private val maxBytes: Long = 32L * 1024 * 1024) {

    private val cached = ConcurrentHashMap<String, ByteArray>()
    private val bytes = AtomicLong()
    private val hits = AtomicInteger()

    /** The cached bytes for [key], or null. A present entry counts as a hit. */
    fun get(key: String): ByteArray? = cached[key]?.also { hits.incrementAndGet() }

    /** Stores [body] unless that would push the total past [maxBytes]; false means nothing changed. */
    fun put(key: String, body: ByteArray): Boolean {
        val size = body.size.toLong()
        if (size > maxBytes) return false
        synchronized(this) {
            val total = bytes.get() - (cached[key]?.size?.toLong() ?: 0L) + size
            if (total > maxBytes) return false
            cached[key] = body
            bytes.set(total)
        }
        return true
    }

    fun size(): Int = cached.size

    fun hits(): Int = hits.get()

    fun bytes(): Long = bytes.get()

    /** Raw counts, no rounding, so the panel and the tests read the same characters. */
    fun summary(): String = "cached=${size()} hits=${hits()} ${bytes()}/$maxBytes bytes"
}
