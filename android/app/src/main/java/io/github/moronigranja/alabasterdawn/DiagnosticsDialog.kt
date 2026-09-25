package io.github.moronigranja.alabasterdawn

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The diagnostics record, on screen: read-only, monospace, scrollable and selectable, with a Share
 * button and Close.
 *
 * It exists because the failures worth reporting happen on hardware the maintainer does not have,
 * where there is no adb and often no Google account either — the user's only channel was a photo of
 * the screen. The record is rendered here (so a screenshot is already a complete report), and Share
 * hands the same text to any installed app as plain text, no storage permission and no provider.
 *
 * The dialog takes window focus, which blurs the page and pauses the engine (see
 * PortActivity.setPageFocus); the owner re-dispatches focus when the dialog is dismissed.
 */
class DiagnosticsDialog(
    context: Context,
    private val record: String,
    /** Called on Share; returns the line shown under the buttons (where the copy was written). */
    private val onShare: () -> String,
) : Dialog(context) {

    private val status = TextView(context)

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0101820.toInt())
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

        root.addView(
            TextView(context).apply {
                this.text = "Diagnostics — Back opens this while the game is running"
                textSize = 16f
                setTextColor(COLOR_TEXT)
            }
        )

        root.addView(
            ScrollView(context).apply {
                addView(
                    TextView(context).apply {
                        this.text = record
                        typeface = Typeface.MONOSPACE
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(COLOR_TEXT)
                        setTextIsSelectable(true)
                    }
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        status.apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(status)

        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply {
                    this.text = "Share"
                    setOnClickListener { status.text = onShare() }
                })
                addView(Button(context).apply {
                    this.text = "Close"
                    setOnClickListener { dismiss() }
                })
            }
        )

        setContentView(root)
        /* The log lines are long; the record is worth the whole screen. */
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_TEXT = 0xFFE8DCC8.toInt()
        private const val COLOR_DIM = 0xB3E8DCC8.toInt()
    }
}
