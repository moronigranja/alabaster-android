package io.github.moronigranja.alabasterdawn

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList

/**
 * The port's own look, in one place: the console-style dark panel and warm off-white text the side
 * menu, the diagnostics dialog and the entry screen share.
 *
 * The palette is the port's own. No game art is drawn from here — the entry screen's hero image is
 * read out of the *user's* copy of the game at runtime (see [GameProfile.logoPath] and `NOTICE.md`).
 */
object PortStyle {

    /** Body text and icons on the port's panels. */
    const val TEXT = 0xFFE8DCC8.toInt()

    /** Secondary text: status lines, folder names, section headers. */
    const val DIM = 0xB3E8DCC8.toInt()

    /** The panel's own colour; [SCRIM] is what dims the game behind the side menu. */
    const val PANEL = 0xF0101820.toInt()
    const val SCRIM = 0x99000000.toInt()

    /** The ripple and the filled pill that marks the current choice in a list. */
    const val RIPPLE = 0x33E8DCC8
    const val PILL = 0x1FE8DCC8

    /** The hero card's own colour: the games' art is drawn for a bright title screen, not for a dark
     *  panel, so the card is a light tile the art sits on. */
    const val CARD = 0xFFF3F0E9.toInt()

    /** The hairline between rows. */
    const val HAIRLINE = 0x33E8DCC8.toInt()

    /** A game that is ready to start (the status dot on the entry screen's rows). */
    const val ACCENT = 0xFF4FB3A6.toInt()

    /** Text on an [ACCENT] fill. */
    const val INK = 0xFF0E1A22.toInt()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** A rounded panel, the shape every screen of the port is built out of. */
    fun panel(context: Context, radiusDp: Int = 20, color: Int = PANEL): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(color)
        }

    /** The round action badge that sits on the hero card. */
    fun badge(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ACCENT)
    }

    /**
     * A row's background: a rounded ripple over the whole row, filled with a dim pill when the row is
     * the current choice. The mask has a colour on purpose — a mask is read by alpha, and an unfilled
     * GradientDrawable is transparent.
     */
    fun row(context: Context, filled: Boolean = false, radiusDp: Int = 24): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(android.graphics.Color.WHITE)
        }
        val content = if (filled) {
            GradientDrawable().apply {
                cornerRadius = dp(context, radiusDp).toFloat()
                setColor(PILL)
            }
        } else {
            null
        }
        return RippleDrawable(ColorStateList.valueOf(RIPPLE), content, mask)
    }

    /**
     * The port's button look. A platform `Button` is grey, all-caps and elevated, which reads as a
     * different app next to these panels; [primary] is the one filled action a screen has.
     */
    fun dress(button: Button, primary: Boolean = false) {
        val context = button.context
        button.background = if (primary) {
            GradientDrawable().apply {
                cornerRadius = dp(context, 12).toFloat()
                setColor(ACCENT)
            }
        } else {
            GradientDrawable().apply {
                cornerRadius = dp(context, 12).toFloat()
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(dp(context, 1), HAIRLINE)
            }
        }
        button.isAllCaps = false
        button.setTextColor(if (primary) INK else TEXT)
        button.textSize = 15f
        button.stateListAnimator = null
        button.minHeight = dp(context, 44)
    }

    /** The entry screen's little "this game is ready" dot. */
    fun dot(context: Context, ready: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (ready) ACCENT else RIPPLE)
    }
}
