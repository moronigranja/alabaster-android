package io.github.moronigranja.alabasterdawn

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * The port's side panel, opened by Back: a scrim over the whole window plus a panel pinned to the
 * right edge carrying the overlay switch, the frame-readout switch, the frame-rate switch (with the
 * rate slider it reveals underneath), the keep-a-log-file switch, the picture position, three
 * read-only status lines and Exit. Every entry is the same full-width, icon-led row, after the Eden /
 * Sudachi / Azahar side menus: an icon in a fixed left gutter, the label beside it on a single line,
 * the control at the right edge, and the whole row is the target.
 *
 * Two things decide the details. The switches, the rate and the position are stored and owned by the
 * Activity, so this view is only told what to show ([setHideWithExternalInput], [setStatsEnabled],
 * [setLimitFps], [setFpsLimit], [setLogToSaves], [setAlign]) and reports taps through callbacks. And
 * nothing inside it may take view focus: the engine stops its loop and suspends audio on the page's
 * `blur` (see PortActivity.setPageFocus), so the descendants are made unfocusable and the WebView
 * keeps focus while the panel is open — including the slider, which is dragged, not focused.
 */
class SideMenuView(context: Context) : FrameLayout(context) {

    /** A tap on the overlay switch (never fired by [setHideWithExternalInput]). */
    var onHideWithExternalInput: ((Boolean) -> Unit)? = null

    /** A tap on the frame-readout switch. */
    var onStatsEnabled: ((Boolean) -> Unit)? = null

    /** A tap on the frame-rate switch. */
    var onLimitFps: ((Boolean) -> Unit)? = null

    /** A move of the rate slider the switch reveals, in frames per second (see [FpsLimit]). */
    var onFpsLimit: ((Int) -> Unit)? = null

    /** A tap on the keep-a-log-file switch. */
    var onLogToSaves: ((Boolean) -> Unit)? = null

    /** A tap on one of the three position radios. */
    var onAlign: ((ViewAlign) -> Unit)? = null

    /** A tap on Exit. */
    var onExit: (() -> Unit)? = null

    /** A tap on Troubleshoot: the owner shows the record, which it owns. */
    var onDiagnostics: (() -> Unit)? = null

    /** A tap on the scrim; the owner closes the panel, because it owns the WebView re-focus. */
    var onScrimTap: (() -> Unit)? = null

    private val hideToggle = Switch(context)
    private val statsToggle = Switch(context)
    private val fpsToggle = Switch(context)
    private val logToggle = Switch(context)
    private val radios = LinkedHashMap<ViewAlign, RadioButton>()

    private val fpsSlider = SeekBar(context)
    private val fpsValue = TextView(context)
    private val fpsTicks = FpsTicks(context)

    /** The slider and its tick labels: under the switch row, visible only while the switch is on. */
    private val fpsSliderGroup = LinearLayout(context)

    /**
     * The row that owns each radio. A `RadioGroup` cannot host a child that has another parent (the
     * row owns the button), so mutual exclusion is done explicitly instead of by a group.
     */
    private val radioRows = LinkedHashMap<ViewAlign, LinearLayout>()
    private val versionLine = TextView(context)
    private val externalInputStatus = TextView(context)
    private val savesStatus = TextView(context)
    private val engineStatus = TextView(context)

    /** True while [sync] assigns the widget state, so a programmatic set fires no callback. */
    private var syncing = false

    /** The icon and control colour on a row, and the ripple the whole row shows. */
    private val rippleColor = ColorStateList.valueOf(0x33E8DCC8)
    private val pillColor = 0x1FE8DCC8.toInt()

    private var hideWithExternalInput = true
    private var statsEnabled = false
    private var limitFps = false
    private var fpsLimit = FpsLimit.DEFAULT
    private var logToSaves = true
    private var align = ViewAlign.DEFAULT

    val isOpen: Boolean get() = visibility == VISIBLE

    /** Opening always starts at the top: the panel can have been scrolled to reach Exit. */
    fun open() {
        visibility = VISIBLE
        scroller.scrollTo(0, 0)
    }

    fun close() { visibility = GONE }

    fun setHideWithExternalInput(value: Boolean) {
        hideWithExternalInput = value
        sync()
    }

    fun setStatsEnabled(value: Boolean) {
        statsEnabled = value
        sync()
    }

    fun setLimitFps(value: Boolean) {
        limitFps = value
        sync()
    }

    /** The rate the switch caps at, one of [FpsLimit.CHOICES]. */
    fun setFpsLimit(value: Int) {
        fpsLimit = value
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
    fun setStatus(externalInputActive: Boolean, saves: String) {
        externalInputStatus.text = "External input: " + (if (externalInputActive) "active" else "idle")
        savesStatus.text = "Saves: " + saves
    }

    /** Which build the port is; shown with the rest of the status block. */
    fun setVersion(text: String) {
        versionLine.text = text
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

    /** The panel's scroller; kept so [open] can send it back to the top. */
    private lateinit var scroller: ScrollView

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

        /* The panel can be taller than the screen - a landscape phone has ~410 dp of height and this
         * menu needs ~500 - and it used to be clipped with nothing to scroll, so Exit (the last view)
         * was unreachable. `fillViewport` keeps the blank-space filler that pushes the status block to
         * the bottom when the whole menu does fit, and scrolls when it does not. */
        scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(buildPanel(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        /* 340 dp so a 14 sp label keeps one line beside the 24 dp icon, its 16 dp gutter and the
         * switch, inside the panel's own 16 dp of padding - Eden's single-line layout. */
        addView(scroller, LayoutParams(dp(340), LayoutParams.MATCH_PARENT, Gravity.END))
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

        hideToggle.setOnCheckedChangeListener { _, checked ->
            if (!syncing) onHideWithExternalInput?.invoke(checked)
        }
        addRow(panel, R.drawable.ic_menu_pad, "Hide pad with external input", hideToggle) {
            hideToggle.isChecked = !hideToggle.isChecked
        }

        statsToggle.setOnCheckedChangeListener { _, checked ->
            if (!syncing) onStatsEnabled?.invoke(checked)
        }
        addRow(panel, R.drawable.ic_menu_stats, "Show FPS / battery / temp", statsToggle) {
            statsToggle.isChecked = !statsToggle.isChecked
        }

        fpsToggle.setOnCheckedChangeListener { _, checked ->
            if (!syncing) onLimitFps?.invoke(checked)
        }
        addRow(panel, R.drawable.ic_menu_fps, "Limit the frame rate (battery)", fpsToggle) {
            fpsToggle.isChecked = !fpsToggle.isChecked
        }
        /* The rate lives under the switch, and only while it is on: the cap is the switch's job, the
         * slider only says how much. */
        buildFpsSlider(panel)

        logToggle.setOnCheckedChangeListener { _, checked ->
            if (!syncing) onLogToSaves?.invoke(checked)
        }
        addRow(panel, R.drawable.ic_menu_log, "Keep a log with the saves", logToggle) {
            logToggle.isChecked = !logToggle.isChecked
        }

        /* not a row: a section header keeps its small dim style */
        panel.addView(TextView(context).apply {
            text = "Game position"
            textSize = 14f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(16), 0, 0)
        })

        addRadio(panel, ViewAlign.TOP, "Top", R.drawable.ic_menu_align_top, last = false)
        addRadio(panel, ViewAlign.CENTER, "Center", R.drawable.ic_menu_align_center, last = false)
        addRadio(panel, ViewAlign.BOTTOM, "Bottom", R.drawable.ic_menu_align_bottom, last = true)

        /* Pushes the status lines and Exit to the bottom of the panel. */
        panel.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        versionLine.apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
        }
        panel.addView(versionLine)

        externalInputStatus.apply {
            textSize = 13f
            setTextColor(COLOR_DIM)
        }
        panel.addView(externalInputStatus)

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

        addRow(panel, R.drawable.ic_menu_diagnostics, "Troubleshoot", null) {
            onDiagnostics?.invoke()
        }
        addRow(panel, R.drawable.ic_menu_exit, "Exit", null, last = true) {
            onExit?.invoke()
        }

        return panel
    }

    /**
     * The rate slider, under the frame-rate switch: five positions ([FpsLimit.CHOICES]), their labels
     * on the ticks below, and the chosen rate as a readout beside the bar so the value is readable
     * while the thumb is dragged.
     *
     * The labels are aligned to the thumb's own travel, not to fractions of the bar's width: a
     * `SeekBar`'s thumb centre runs from its left padding to its right padding, and the tick strip is
     * given those same two insets - plus the readout's width on the right, because the readout is the
     * bar's neighbour in the row. The bar's padding is set to half a thumb so the first and last
     * positions sit inside the panel instead of half off its edge; both use the same number, so they
     * cannot drift apart.
     */
    private fun buildFpsSlider(panel: LinearLayout) {
        val halfThumb = (fpsSlider.thumb?.intrinsicWidth ?: 0) / 2
        val readout = dp(56)
        fpsSlider.max = FpsLimit.CHOICES.size - 1
        fpsSlider.isFocusable = false
        fpsSlider.setPadding(halfThumb, 0, halfThumb, 0)
        fpsSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val fps = FpsLimit.at(progress)
                fpsValue.text = "$fps fps"
                if (fromUser && !syncing) onFpsLimit?.invoke(fps)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        fpsValue.apply {
            textSize = 13f
            setTextColor(COLOR_TEXT)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                fpsSlider,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                fpsValue,
                LinearLayout.LayoutParams(readout, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        fpsTicks.setPadding(halfThumb, 0, halfThumb + readout, 0)
        fpsSliderGroup.apply {
            orientation = LinearLayout.VERTICAL
            /* Under the switch row's label: that row's own 12 dp padding, the 24 dp icon and its
             * 16 dp gutter, so the bar starts where every other row's content does. */
            setPadding(dp(52), 0, dp(12), dp(6))
            visibility = GONE
            addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(fpsTicks, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(
            fpsSliderGroup,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        panel.addView(
            View(context).apply { setBackgroundColor(COLOR_DIVIDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        )
    }

    /**
     * The rate labels under the slider: one per choice, centred on the position the thumb reaches with
     * that choice selected. The strip is given the bar's two insets (see [buildFpsSlider]), so the same
     * arithmetic in both places is what keeps a label over its thumb.
     */
    private inner class FpsTicks(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_DIM
            textSize = dp(11).toFloat()
            textAlign = Paint.Align.CENTER
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val metrics = paint.fontMetrics
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                (metrics.descent - metrics.ascent).toInt() + dp(6),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val last = FpsLimit.CHOICES.size - 1
            val travel = (width - paddingLeft - paddingRight).toFloat()
            val baseline = -paint.fontMetrics.ascent
            for ((index, fps) in FpsLimit.CHOICES.withIndex()) {
                canvas.drawText(fps.toString(), paddingLeft + travel * index / last, baseline, paint)
            }
        }
    }

    /**
     * The row background: a rounded ripple over the whole row, filled with a dim pill when the row is
     * the current choice (the reference side menus mark the active entry this way). The mask has a
     * colour on purpose - a mask is read by alpha, and an unfilled GradientDrawable is transparent.
     */
    private fun rowBackground(filled: Boolean): Drawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(Color.WHITE)
        }
        val content = if (filled) {
            GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(pillColor)
            }
        } else {
            null
        }
        return RippleDrawable(rippleColor, content, mask)
    }

    /**
     * The panel's only row shape, after the emulator side menus: a leading icon in a fixed gutter, the
     * label beside it on a single line, the control (if any) pinned to the right edge, and the whole
     * row - icon, label and empty space - is the hit target. The platform Button already spanned the
     * panel, which is why everything else had to catch up. Returns the row so a caller can restyle it
     * (the picture-position group fills the current one).
     */
    private fun addRow(
        panel: LinearLayout, iconRes: Int, label: String, control: View?,
        last: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = rowBackground(false)
            addView(
                ImageView(context).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(COLOR_TEXT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) }
            )
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 14f
                    setTextColor(COLOR_TEXT)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (control != null) {
                /* The row is the hit target, so the control must not swallow the touch. */
                control.isClickable = false
                control.isFocusable = false
                addView(
                    control,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            if (onClick != null) {
                isClickable = true
                setOnClickListener { onClick() }
            }
        }
        panel.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        if (!last) {
            panel.addView(
                View(context).apply { setBackgroundColor(COLOR_DIVIDER) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            )
        }
        return row
    }

    private fun addRadio(
        panel: LinearLayout, value: ViewAlign, label: String, iconRes: Int, last: Boolean,
    ) {
        val radio = RadioButton(context).apply {
            setOnCheckedChangeListener { _, checked -> if (checked && !syncing) onAlign?.invoke(value) }
        }
        radios[value] = radio
        radioRows[value] = addRow(panel, iconRes, label, radio, last) {
            /* The row, not the RadioButton, is the hit target: check the three explicitly, since
             * there is no RadioGroup to uncheck the siblings. */
            for ((other, r) in radios) r.isChecked = other == value
            for ((other, r) in radioRows) r.background = rowBackground(other == value)
            onAlign?.invoke(value)
        }
    }

    /** The only writer of the widget state; the listeners are muted across the assignment. */
    private fun sync() {
        syncing = true
        hideToggle.isChecked = hideWithExternalInput
        statsToggle.isChecked = statsEnabled
        fpsToggle.isChecked = limitFps
        fpsSlider.progress = FpsLimit.indexOf(fpsLimit)
        fpsValue.text = "$fpsLimit fps"
        fpsSliderGroup.visibility = if (limitFps) VISIBLE else GONE
        logToggle.isChecked = logToSaves
        for ((value, radio) in radios) radio.isChecked = value == align
        for ((value, row) in radioRows) row.background = rowBackground(value == align)
        syncing = false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLOR_TEXT = 0xFFE8DCC8.toInt()
        private const val COLOR_DIM = 0xB3E8DCC8.toInt()
        private const val COLOR_DIVIDER = 0x33E8DCC8.toInt()
    }
}
