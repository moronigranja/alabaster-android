package io.github.moronigranja.alabasterdawn

/**
 * The port's own log, kept in memory so a device-only failure can be reported without adb: the
 * diagnostics panel (pre-game screen and side menu) renders [snapshot] on screen, and it can be
 * written to a file and shared.
 *
 * Two kinds of line end up here. The app's own boot milestones (`indexed 2652 files in 8103ms`,
 * grant accepted, WebView facts) come from [line]; the engine's own reports come from the shim
 * through `AdaBridge.reportDiag` and land in [event]. A line that repeats immediately — which is
 * what a failure the engine throws once per frame looks like — is collapsed to `(xN)` in place, so
 * the ring keeps the context around a fault instead of only its last few seconds. Nothing here
 * throws, nothing here blocks, and the buffer is bounded, so it is safe to log from the asset
 * handler's background threads too.
 *
 * Pure Kotlin (the clock and the logcat sink are injected) so the trimming/ordering rules are
 * unit-tested on the JVM.
 */
class Diag(
    private val capacity: Int = 400,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private val lines = ArrayDeque<String>()
    private val startedAt = clock()
    private var sink: ((String) -> Unit)? = null

    /* The previous message, so a line the engine throws once per frame while it is stuck collapses
     * to "(xN)" instead of pushing the lines that name the cause out of the ring. The comparison is
     * on the message, not the stamped text, so identical lines at different times still collapse. */
    private var lastMessage: String? = null
    private var lastRepeats: Int = 0

    /* The engine polls getGamepadJson() exactly once per frame (see ada-shim.js), which makes it the
     * port's frame clock: if it goes quiet, the page's JS thread is blocked or dead, not merely
     * waiting for a resource. */
    @Volatile
    private var lastFrameAt = clock()

    /* The bridge call in flight, so a JS thread stuck inside a synchronous call is named. */
    @Volatile
    private var lastCall: String = "-"

    fun attachSink(sink: (String) -> Unit) {
        this.sink = sink
    }

    /** Safe to call from the asset handler's background threads as well as from the main thread. */
    @Synchronized
    fun line(message: String) {
        val text = "+${clock() - startedAt}ms $message"
        if (message == lastMessage) {
            /* Rewrite the previous line in place with the newer stamp and the running count, so a
             * message that repeats once per frame costs one ring slot, not the whole ring. */
            lastRepeats++
            if (lines.isNotEmpty()) lines.removeLast()
            lines.addLast("+${clock() - startedAt}ms $message (x$lastRepeats)")
        } else {
            lastMessage = message
            lastRepeats = 1
            lines.addLast(text)
        }
        while (lines.size > capacity) lines.removeFirst()
        /* Every raw occurrence reaches the sink, so logcat stays at full fidelity while only the
         * ring collapses. This runs inside the lock: the sink must not call back into Diag. */
        sink?.invoke(text)
    }

    /** A report from the injected shim (boot progress, a stalled boot, the GL/audio facts). */
    fun event(kind: String, payload: String) = line("ENGINE $kind: $payload")

    /** Called from every `getGamepadJson()`: one engine frame. */
    fun noteFrame() {
        lastFrameAt = clock()
    }

    fun silentMs(): Long = clock() - lastFrameAt

    /** The bridge call the JS thread is inside right now (`fsExists`, `fsReadFile`, …). */
    fun noteCall(description: String) {
        lastCall = description
    }

    fun lastBridgeCall(): String = lastCall

    fun size(): Int = lines.size

    /** The ring's bound, so a caller can carry over at most this many lines from a previous record. */
    fun maxLines(): Int = capacity

    /**
     * Carries the previous session's *pre-formatted* lines (see `LogFile.previousLines`) into this
     * ring, oldest first. A user who freezes and restarts expects the panel to still show what
     * happened, and the file is written from this ring — so carrying the lines over both shows them
     * and keeps them in the record that reaches disk.
     *
     * The collapse state is reset: those lines are already stamped and counted, so the next [line]
     * must start a fresh group rather than rewrite the last of them.
     */
    @Synchronized
    fun carryOver(previous: List<String>) {
        for (line in previous) lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
        lastMessage = null
        lastRepeats = 0
    }

    /** The whole record, newest last, under [header] lines supplied by the owner (device, WebView, …). */
    fun snapshot(header: List<String>): String = buildString {
        for (line in header) appendLine(line)
        appendLine()
        appendLine("--- log (${lines.size} lines, oldest first) ---")
        for (line in lines) appendLine(line)
    }
}
