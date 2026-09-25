package io.github.moronigranja.alabasterdawn

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the asset path has been asked for, and what it is still doing.
 *
 * The interesting number is not the throughput but the *unfinished* requests: every asset the game
 * draws is a synchronous read out of the picked SAF tree, so a request that never returns freezes
 * the boot exactly like a dead one would, and on hardware that cannot be debugged the only way to
 * tell "the tree's provider is hung" from "the page's JS thread is blocked" is to know which reads
 * are outstanding and for how long.
 *
 * Called from the WebView's background handler threads, so the state is concurrent. Pure Kotlin (the
 * clock is injected) so the pairing rules and the stuck threshold are unit-tested on the JVM.
 */
class AssetTracker(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {

    private val inFlight = ConcurrentHashMap<String, Long>()
    private val served = AtomicInteger()
    private val missed = AtomicInteger()

    @Volatile
    private var slowestMs = 0L

    @Volatile
    private var slowestPath = "-"

    fun started(path: String) {
        inFlight[path] = clock()
    }

    /** Completes a request and returns how long it took, in ms (0 when it was never started). */
    fun finished(path: String): Long {
        val startedAt = inFlight.remove(path) ?: return 0L
        served.incrementAndGet()
        val took = clock() - startedAt
        if (took > slowestMs) {
            slowestMs = took
            slowestPath = path
        }
        return took
    }

    /** Answers with no body (a missing asset): counted as missed, never as served. */
    fun missed(path: String) {
        inFlight.remove(path)
        missed.incrementAndGet()
    }

    /** Requests started more than [thresholdMs] ago: a path here is a read that never came back. */
    fun stuck(thresholdMs: Long): List<String> {
        val at = clock()
        return inFlight.entries
            .filter { at - it.value >= thresholdMs }
            .map { "${it.key} (${(at - it.value) / 1000}s)" }
    }

    fun counters(): String =
        "assets served=$served missed=$missed inFlight=${inFlight.size} " +
            "slowest=${slowestMs}ms $slowestPath"
}
