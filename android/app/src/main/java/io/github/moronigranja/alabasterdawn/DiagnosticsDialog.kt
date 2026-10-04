package io.github.moronigranja.alabasterdawn

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
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
    /**
     * Called on Reset resolution: the owner drops the game's stored Resolution option (FINDINGS §19).
     * It is here rather than in the game's own Options because at a Resolution this device cannot
     * drive the game is too slow to reach those; the dialog is the port's own and is always usable.
     */
    private val onResetResolution: () -> Unit,
    /**
     * Called on "Test shader spellings": the owner asks the page to compile every spelling of the
     * game's array declarations on this device's own GL front end and report which it takes
     * (FINDINGS §22.11). The point is that the port stops guessing: the phone answers.
     */
    private val onShaderSelfTest: () -> Unit,
    /** Called on "OpenGL driver": the owner opens whatever screen sets it (ANGLE Preferences). */
    private val onDriverSettings: () -> Unit,
    /**
     * Whether this game has the port's shader machinery at all. False for an engine without the GLSL
     * the tools exist for (CrossCode), where the Reset-resolution row, the spelling test and the Mali
     * hint would all be about files the game does not have.
     */
    private val shaderTools: Boolean,
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
                setTextColor(PortStyle.TEXT)
            }
        )

        root.addView(
            ScrollView(context).apply {
                addView(
                    TextView(context).apply {
                        this.text = record
                        typeface = Typeface.MONOSPACE
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTextColor(PortStyle.TEXT)
                        setTextIsSelectable(true)
                    }
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        status.apply {
            textSize = 12f
            setTextColor(PortStyle.DIM)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(status)

        if (shaderTools) {
            root.addView(
                TextView(context).apply {
                    /* The one instruction that has fixed a Mali phone so far, and the one thing an app
                     * cannot do for itself: the driver choice is a privileged `Settings.Global` entry. */
                    text = "Does the game stop on its loading bar? On a Mali device that is the OpenGL " +
                        "driver: open \"OpenGL driver\" below and set this app to ANGLE. If it starts, " +
                        "the panel's spelling test says which shaders still disagree."
                    textSize = 12f
                    setTextColor(PortStyle.DIM)
                    setPadding(0, dp(8), 0, 0)
                }
            )
        }

        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply {
                    this.text = "Share"
                    setOnClickListener { status.text = onShare() }
                    PortStyle.dress(this)
                })
                if (shaderTools) {
                    addView(Button(context).apply {
                        this.text = "Reset resolution"
                        setOnClickListener {
                            onResetResolution()
                            dismiss()
                        }
                        PortStyle.dress(this)
                    })
                }
                addView(Button(context).apply {
                    this.text = "Close"
                    setOnClickListener { dismiss() }
                    PortStyle.dress(this)
                })
            }
        )

        root.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply {
                    this.text = "Test shader spellings"
                    setOnClickListener {
                        onShaderSelfTest()
                        dismiss()
                    }
                    PortStyle.dress(this)
                }.also { it.visibility = if (shaderTools) View.VISIBLE else View.GONE })
                addView(Button(context).apply {
                    this.text = "OpenGL driver"
                    setOnClickListener { onDriverSettings() }
                    PortStyle.dress(this)
                })
            }
        )

        setContentView(root)
        /* The log lines are long; the record is worth the whole screen. */
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

}
