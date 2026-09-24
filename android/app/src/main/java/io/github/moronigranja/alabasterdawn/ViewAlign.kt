package io.github.moronigranja.alabasterdawn

/**
 * Where the side menu puts the game picture inside the window. [wire] is both the pref value and
 * the string the page reads from `AdaBridge.getViewAlign()`, so the two sides cannot drift.
 */
enum class ViewAlign(val wire: String) {
    TOP("top"),
    CENTER("center"),
    BOTTOM("bottom");

    companion object {
        /** The default, and the fallback for a pref written by an older or hand-edited build. */
        val DEFAULT = CENTER

        fun fromWire(raw: String?): ViewAlign = entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}
