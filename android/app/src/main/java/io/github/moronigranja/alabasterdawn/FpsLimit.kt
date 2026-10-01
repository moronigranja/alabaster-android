package io.github.moronigranja.alabasterdawn

import kotlin.math.abs

/**
 * The side menu's frame-rate cap: the rates its slider offers and the one the app remembers.
 *
 * The cap exists because the port is GPU-bound (README "Performance"): the engine drives everything it
 * draws from `requestAnimationFrame`, so the shim serves one frame per interval and the game logic keeps
 * advancing on its own 60 Hz step — only the presents drop (FINDINGS §20.2). A display can only present
 * on a vsync, so a rate that is not a whole division of the panel's refresh lands on the next vsync up:
 * on a 60 Hz panel **40 and 45 both land on 30**, on a 120 Hz one on 40; 20, 30 and 60 are exact on both.
 * (40 is the rung a 120 Hz surface can hold exactly, and 45 the one that lands there.)
 *
 * Five rates, in the order the menu shows them, with 30 as the default — the rate the switch stood for
 * before it had a slider, so a stored choice from an older build keeps its meaning.
 */
object FpsLimit {

    /**
     * What the bridge sends when the switch is off, and what the shim's gate reads as "no cap": its
     * `requestAnimationFrame` wrapper passes every call straight through.
     */
    const val OFF = 0

    /** The rates the slider offers, low to high. */
    val CHOICES = listOf(20, 30, 40, 45, 60)

    /** The rate the switch stands for when nothing has been chosen yet. */
    const val DEFAULT = 30

    /** The rate at slider position [index]; the ends clamp, so a stale index cannot break the menu. */
    fun at(index: Int): Int = CHOICES[index.coerceIn(0, CHOICES.size - 1)]

    /** The slider position of a stored rate: an exact choice, or the nearest one. */
    fun indexOf(fps: Int): Int {
        var best = 0
        var bestGap = Int.MAX_VALUE
        for ((index, choice) in CHOICES.withIndex()) {
            val gap = abs(choice - fps)
            if (gap < bestGap) {
                best = index
                bestGap = gap
            }
        }
        return best
    }

    /**
     * A stored rate as one this slider can show: a value another build wrote (or a hand-edited
     * preference) snaps to the nearest choice rather than leaving the thumb between two positions, and
     * a tie goes to the lower rate. [OFF] is not a rate - it means the switch is off - so it reads as
     * "nothing chosen", the default.
     */
    fun normalize(stored: Int): Int = if (stored == OFF) DEFAULT else at(indexOf(stored))
}
