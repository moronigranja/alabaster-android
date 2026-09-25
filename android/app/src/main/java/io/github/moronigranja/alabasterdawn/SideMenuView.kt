package io.github.moronigranja.alabasterdawn

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

/**
 * The port's side panel, opened by Back: a scrim over the whole window plus a panel pinned to the
 * right edge carrying the overlay switch, the frame-readout switch, the keep-a-log-file switch, the
 * picture position, three read-only status lines and Exit.
 *
 * Two things decide the details. The switches and the position are stored and owned by the
 * Activity, so this view is only told what to show ([setHideWithController], [setStatsEnabled],
 * [setLogToSaves], [setAlign]) and reports taps through callbacks. And nothing inside it may take
 * view focus: the engine stops its loop and suspends audio on the page's `blur` (see
 * PortActivity.setPageFocus), so the descendants are made unfocusable and the WebView keeps focus
 * while the panel is open.
 */
class SideMenuView(context: Context) : FrameLayout(context) {

    /** A tap on the overlay switch (never fired by [setHideWithController]). */
    var onHideWithController: ((Boolean) -> Unit)? = null

    /** A tap on the frame-readout switch. */
    var onStatsEnabled: ((Boolean) -> Unit)? = null

    /** A tap on the keep-a-log-file switch. */
    var onLogToSaves: ((Boolean) -> Unit)? = null

    /** A tap on one of the three position radios. */
    var onAlign: ((ViewAlign) -> Unit)? = null

    /** A tap on Exit. */
    var onExit: (() -> Unit)? = null

    /** A tap on Diagnostics: the owner shows the record, which it owns. */
    var onDiagnostics: (() -> Unit)? = null

    /** A tap on the scrim; the owner closes the panel, because it owns the WebView re-focus. */
    var onScrimTap: (() -> Unit)? = null

    private val hideToggle = Switch(context)
    private val statsToggle = Switch(context)
    private val logToggle = Switch(context)
    private val alignGroup = RadioGroup(context)
    private val radios = LinkedHashMap<ViewAlign, RadioButton>()
    private val controllerStatus = TextView(context)
    private val savesStatus = TextView(context)
    private val engineStatus = TextView(context)

    /** True while [sync] assigns the widget state, so a programmatic set fires no callback. */
    private var syncing = false

    private var hideWithController = true
    private var statsEnabled = false
    private var logToSaves = true
    private var align = ViewAlign.DEFAULT

    val isOpen: Boolean get() = visibility == VISIBLE

    fun open() { visibility = VISIBLE }

    fun close() { visibility = GONE }

    fun setHideWithController(value: Boolean) {
        hideWithController = value
        sync()
    }

    fun setStatsEnabled(value: Boolean) {
        statsEnabled = value
        sync()
    }

    fun setLogToSaves(value: Boolean) {
        logToSaves = value
        sync()
    }

    fun setAlign(value: ViewAlign) {
        align = value
        sync()
    }

    /** Refreshed by the owner just before [open]: what the port is reading right now. */
    fun setStatus(controllerActive: Boolean, saves: String) {
        controllerStatus.text = "Controller input: " + (if (controllerActive) "active" else "idle")
        savesStatus.text = "Saves: " + saves
    }

    /**
     * The last thing the injected shim reported — in particular a boot that stopped advancing, which
     * is exactly what a user with a frozen loading bar needs to be told (and to report). Long reports
     * are cut here and read in full in the diagnostics panel.
     */
    fun setLastEngineReport(report: String) {
        val cut = if (report.length > 160) report.take(160) + "…" else report
        engineStatus.text = "Engine: $cut\n(see Diagnostics)"
    }

    init {
        visibility = GONE
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        addView(
            View(context).apply {
                setBackgroundColor(0x99000000.toInt())
                setOnClickListener { onScrimTap?.invoke() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        addView(buildPanel(), LayoutParams(dp(300), LayoutParams.MATCH_PARENT, Gravity.END))
        sync()
    }

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            /* Its blank area must not fall through to the scrim, which would close the menu. */
            isClickable = true
            setBackgroundColor(0xF0101820.toInt())
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

        panel.addView(TextView(context).apply {
            text = "Menu"
            textSize = 18f
            setTextColor(COLOR_TEXT)
        })

        hideToggle.apply {
            text = "Hide controls with a controller"
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) onHideWithController?.invoke(checked)
            }
        }
        panel.addView(hideToggle)

        statsToggle.apply {
            text = "Show FPS / battery / temperature"
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) onStatsEnabled?.invoke(checked)
            }
        }
        panel.addView(statsToggle)

        logToggle.apply {
            text = "Keep a log file with the saves"
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) onLogToSaves?.invoke(checked)
            }
        }
        panel.addView(logToggle)

        panel.addView(TextView(context).apply {
            text = "Game position"
            textSize = 14f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(16), 0, 0)
        })

        alignGroup.apply { orientation = LinearLayout.VERTICAL }
        addRadio(ViewAlign.TOP, "Top")
        addRadio(ViewAlign.CENTER, "Center")
        addRadio(ViewAlign.BOTTOM, "Bottom")
        panel.addView(alignGroup)

        /* Pushes the status lines and Exit to the bottom of the panel. */
        panel.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        controllerStatus.apply {
            textSize = 13f
            setTextColor(COLOR_DIM)
        }
        panel.addView(controllerStatus)

        savesStatus.apply {
            textSize = 13f
            setTextColor(COLOR_DIM)
        }
        panel.addView(savesStatus)

        engineStatus.apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(4), 0, dp(8))
        }
        panel.addView(engineStatus)

        panel.addView(Button(context).apply {
            text = "Diagnostics"
            setOnClickListener { onDiagnostics?.invoke() }
        })

        panel.addView(Button(context).apply {
            text = "Exit"
            setOnClickListener { onExit?.invoke() }
        })

        return panel
    }

    private fun addRadio(value: ViewAlign, label: String) {
        alignGroup.addView(RadioButton(context).apply {
            text = label
            textSize = 15f
            setTextColor(COLOR_TEXT)
            setOnCheckedChangeListener { _, checked ->
                if (checked && !syncing) onAlign?.invoke(value)
            }
        })
        radios[value] = alignGroup.getChildAt(alignGroup.childCount - 1) as RadioButton
    }

    /** The only writer of the widget state; the listeners are muted across the assignment. */
    private fun sync() {
        syncing = true
        hideToggle.isChecked = hideWithController
        statsToggle.isChecked = statsEnabled
        logToggle.isChecked = logToSaves
        for ((value, radio) in radios) radio.isChecked = value == align
        syncing = false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_TEXT = 0xFFE8DCC8.toInt()
        private const val COLOR_DIM = 0xB3E8DCC8.toInt()
    }
}
