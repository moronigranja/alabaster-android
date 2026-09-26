package io.github.moronigranja.alabasterdawn

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * The on-screen pad: draws [OnScreenPadModel] and feeds what it holds into [Gamepad], which merges
 * it with the physical pad's state into the one standard pad the engine polls.
 *
 * Drawing and hit rules live in the model; this class only turns Android's `MotionEvent` stream into
 * model calls and the model's state into canvas primitives. Its own state is the two switches — the
 * user's `padEnabled` preference and `controllerInUse` — and their one combined effect,
 * [applyOverlay], plus the layout editor: the [editing] mode, the [layout] being edited, and the
 * pointer bookkeeping for the drags.
 *
 * This View is a control surface, not a clickable widget, hence the accessibility lint suppression.
 */
class OnScreenPadView(context: Context) : View(context) {

    private val model = OnScreenPadModel()
    private val lastAxes = FloatArray(GamepadState.AXES)
    private val lastButtons = BooleanArray(GamepadState.BUTTONS)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = COLOR_LABEL
    }
    private val triangle = Path()

    private val showAgain = Runnable {
        controllerInUse = false
        applyOverlay()
        invalidate()
    }

    /** The layout being edited and drawn; assigning it re-places every control. */
    var layout: PadLayout
        get() = model.layout
        set(value) {
            model.layout = value
            invalidate()
        }

    /** Fired on every commit (DONE, detach, background), so the Activity can persist the layout. */
    var onLayoutChanged: ((PadLayout) -> Unit)? = null

    /** True while the layout editor is open. */
    val editing: Boolean get() = model.editing

    /** The RESET snapshot: what UNDO restores. Cleared on commit and on entering the editor. */
    private var undo: PadLayout? = null

    /** Pointer id → control being moved / resized, so a MOVE can route each finger. */
    private val dragPointer = HashMap<Int, Int>()
    private val scalePointer = HashMap<Int, Int>()

    /**
     * The user's choice, set from the saved preference by the Activity and flipped by a tap on the
     * PAD/HIDE pill. Assigning it re-applies the overlay contribution, so a pad started hidden never
     * publishes state.
     */
    var padEnabled = true
        set(value) {
            if (field == value) return
            field = value
            model.padHidden = !value
            applyOverlay()
            invalidate()
        }

    /** True while a physical controller is being used: the controls hide until it goes quiet. */
    var controllerInUse = false
        private set

    /**
     * Whether a controller in use hides the overlay at all; the side menu's switch. Off leaves the
     * controls and the pill row drawn — and the overlay publishing — while a pad is being used.
     */
    var hideWithController = true
        set(value) {
            if (field == value) return
            field = value
            applyOverlay()
            invalidate()
        }

    var onToggle: ((Boolean) -> Unit)? = null

    /** Fired on a DOWN the pad consumes, so the Activity can nudge a suspended `AudioContext`. */
    var onFirstTouch: (() -> Unit)? = null

    init {
        /* The WebView must keep focus: the engine gates its input on document.hasFocus(). */
        isFocusable = false
        isFocusableInTouchMode = false
        applyOverlay()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        model.resize(w.toFloat(), h.toFloat())
    }

    /** A controller event: hide the controls now, and show them again once it has gone quiet. */
    fun noteControllerActivity() {
        if (model.editing) return
        if (!controllerInUse) {
            controllerInUse = true
            applyOverlay()
            invalidate()
        }
        removeCallbacks(showAgain)
        postDelayed(showAgain, CONTROLLER_IDLE_MS)
    }

    override fun onDetachedFromWindow() {
        /* The WebView is rebuilt on a renderer crash; save an edit in progress first. */
        commitIfEditing()
        removeCallbacks(showAgain)
        super.onDetachedFromWindow()
    }

    /**
     * Saves the edited layout and leaves the editor. False when not editing. Fires
     * [onLayoutChanged] even when nothing changed: the write is idempotent, and it re-syncs the
     * prefs copy after a hand-edited `pad-layout.json`.
     */
    fun commitIfEditing(): Boolean {
        if (!model.editing) return false
        undo = null
        model.setEditing(false)
        dragPointer.clear()
        scalePointer.clear()
        onLayoutChanged?.invoke(layout)
        applyOverlay()
        invalidate()
        return true
    }

    private fun setEditing(editing: Boolean) {
        undo = null
        /* A control held when the editor opens must not stay held when it closes. */
        if (editing) model.releaseAll()
        model.setEditing(editing)
        applyOverlay()
        invalidate()
    }

    /** Whether the controller is hiding the overlay right now. */
    private fun controllerHides(): Boolean = hideWithController && controllerInUse

    private fun controlsDrawn(): Boolean = padEnabled && !controllerHides()

    /**
     * Whether the pill row is drawn and can be hit. It hides with the controls while a physical
     * controller is being used, so a controller player sees no touch chrome at all, and [showAgain]
     * brings it back with them once the controller has gone quiet. [noteControllerActivity] ignores
     * controller events while the editor is open, so editing never hides the row that closes it —
     * RESET/DONE stay reachable for as long as they are needed. With the switch off the pill row
     * stays while a controller is used.
     */
    private fun pillsDrawn(): Boolean = !controllerHides()

    /** Whether the overlay is a pad right now: the controls are drawn and no editor is open. */
    private fun overlayActive(): Boolean = controlsDrawn() && !model.editing

    /**
     * The one place the overlay contributes to the gamepad. Disabling clears what the overlay last
     * reported, so a finger held down while the pad disappears leaves no stuck button behind; and
     * re-enabling republishes instead of diffing, because that clearing happened on Gamepad's side.
     */
    private fun applyOverlay() {
        val active = overlayActive()
        Gamepad.setOverlayEnabled(active)
        if (active) pushState(force = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)
        var consumed = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pill = if (pillsDrawn()) model.pillAt(x, y) else OnScreenPadModel.PILL_NONE
                if (pill != OnScreenPadModel.PILL_NONE) {
                    onPill(pill)
                } else if (model.editing && model.handleHit(x, y) && model.selected != OnScreenPadModel.NO_CONTROL) {
                    model.beginScale(model.selected, x, y)
                    scalePointer[id] = model.selected
                } else if (model.editing && model.controlAt(x, y) != OnScreenPadModel.NO_CONTROL) {
                    val control = model.controlAt(x, y)
                    model.beginDrag(control, x, y)
                    dragPointer[id] = control
                } else if (controlsDrawn()) {
                    /* While the controls are drawn the pad consumes the whole gesture: the page has
                     * no touch UI, and the engine has no touch input device to fall back on. */
                    model.down(id, x, y)
                    if (pushState()) invalidate()
                    consumed = true
                }
                consumed = consumed || model.editing || controlsDrawn() || pill != OnScreenPadModel.PILL_NONE
                if (model.editing || pill != OnScreenPadModel.PILL_NONE) invalidate()
                /* A consumed DOWN never reaches the WebView's own touch listener. */
                if (consumed && event.actionMasked == MotionEvent.ACTION_DOWN) onFirstTouch?.invoke()
            }

            MotionEvent.ACTION_MOVE -> {
                if (model.editing) {
                    var changed = false
                    for (p in 0 until event.pointerCount) {
                        val pid = event.getPointerId(p)
                        val px = event.getX(p)
                        val py = event.getY(p)
                        dragPointer[pid]?.let { model.dragTo(it, px, py); changed = true }
                        scalePointer[pid]?.let { model.scaleTo(it, px, py); changed = true }
                    }
                    if (changed) invalidate()
                    consumed = true
                } else if (controlsDrawn()) {
                    var tracked = false
                    for (p in 0 until event.pointerCount) {
                        tracked = model.move(event.getPointerId(p), event.getX(p), event.getY(p)) || tracked
                    }
                    if (tracked && pushState()) invalidate()
                    consumed = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (model.editing) {
                    dragPointer.remove(id)
                    scalePointer.remove(id)
                    consumed = true
                } else {
                    if (model.up(id) && pushState()) invalidate()
                    consumed = controlsDrawn()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (model.editing) {
                    dragPointer.clear()
                    scalePointer.clear()
                } else {
                    for (p in 0 until event.pointerCount) model.up(event.getPointerId(p))
                    if (pushState()) invalidate()
                }
                consumed = true
            }
        }
        return consumed
    }

    /** A tap on one of the top-row pills; the meaning of [PILL_A]/[PILL_B] depends on the mode. */
    private fun onPill(pill: Int) {
        when (pill) {
            OnScreenPadModel.PILL_A -> if (model.editing) {
                /* RESET takes a snapshot only when there is something to undo, which is exactly what
                 * makes the label flip to UNDO right after a press that changed something. */
                val saved = undo
                if (saved != null) {
                    layout.copyFrom(saved)
                    undo = null
                    model.layout = layout
                } else if (!layout.isDefault()) {
                    undo = layout.snapshot()
                    layout.setDefault()
                    model.layout = layout
                }
                invalidate()
            } else {
                padEnabled = !padEnabled
                onToggle?.invoke(padEnabled)
            }

            OnScreenPadModel.PILL_MINUS ->
                if (model.editing) model.addGlobal(-PadLayout.GLOBAL_STEP)

            OnScreenPadModel.PILL_PLUS ->
                if (model.editing) model.addGlobal(PadLayout.GLOBAL_STEP)

            OnScreenPadModel.PILL_B ->
                if (model.editing) commitIfEditing() else setEditing(true)
        }
    }

    /** Publishes the model's state to [Gamepad], one call per value that actually changed. */
    private fun pushState(force: Boolean = false): Boolean {
        var touched = force
        for (axis in lastAxes.indices) {
            if (force || abs(model.axes[axis] - lastAxes[axis]) > AXIS_EPSILON) {
                lastAxes[axis] = model.axes[axis]
                touched = true
            }
        }
        if (touched) {
            Gamepad.overlayAxes(lastAxes[0], lastAxes[1], lastAxes[2], lastAxes[3])
        }
        for (button in lastButtons.indices) {
            if (force || model.buttons[button] != lastButtons[button]) {
                lastButtons[button] = model.buttons[button]
                Gamepad.overlayButton(button, lastButtons[button])
                touched = true
            }
        }
        return touched
    }

    /* ------------------------------------------------------------------------ drawing ---- */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (model.unit <= 0f) return
        stroke.strokeWidth = model.layoutUnit * STROKE_UNITS
        val drawn = controlsDrawn()
        if (drawn || model.editing) {
            drawStick(canvas, OnScreenPadModel.LEFT_STICK, 0)
            drawDpad(canvas)
            drawStick(canvas, OnScreenPadModel.RIGHT_STICK, 2)
            drawDisc(canvas, OnScreenPadModel.FACE_Y, "Y")
            drawDisc(canvas, OnScreenPadModel.FACE_A, "A")
            drawDisc(canvas, OnScreenPadModel.FACE_X, "X")
            drawDisc(canvas, OnScreenPadModel.FACE_B, "B")
            drawControlPill(canvas, OnScreenPadModel.L1, "L1")
            drawControlPill(canvas, OnScreenPadModel.L2, "L2")
            drawControlPill(canvas, OnScreenPadModel.R1, "R1")
            drawControlPill(canvas, OnScreenPadModel.R2, "R2")
            drawControlPill(canvas, OnScreenPadModel.SELECT, "SEL")
            drawControlPill(canvas, OnScreenPadModel.START, "START")
            drawControlPill(canvas, OnScreenPadModel.HOME, "HOME")
        }
        if (model.editing) drawEditorChrome(canvas)
        /* The pill row is drawn last so it sits above the pad, and stays drawn whenever the pad is
         * on screen in some form — hidden or not, it is the only way back. */
        if (pillsDrawn()) {
            stroke.strokeWidth = model.unit * STROKE_UNITS
            label.textSize = model.unit * TEXT_UNITS
            if (model.padHidden && !model.editing) {
                drawRowPill(canvas, OnScreenPadModel.PILL_A, "")
                drawChevron(canvas, model.pill(OnScreenPadModel.PILL_A))
            } else {
                drawRowPill(canvas, OnScreenPadModel.PILL_A, pillLabelA(drawn))
                drawRowPill(canvas, OnScreenPadModel.PILL_MINUS, "-")
                drawRowPill(canvas, OnScreenPadModel.PILL_PLUS, "+")
                drawRowPill(canvas, OnScreenPadModel.PILL_B, if (model.editing) "DONE" else "EDIT")
            }
        }
    }

    private fun pillLabelA(drawn: Boolean): String = when {
        model.editing -> if (undo != null) "UNDO" else "RESET"
        else -> "HIDE"
    }

    /** The way back when the pad is hidden: a downward chevron in the small centred pill. */
    private fun drawChevron(canvas: Canvas, pill: OnScreenPadModel.Shape) {
        val w = pill.halfWidth * 0.55f
        val h = pill.halfHeight * 0.5f
        stroke.strokeWidth = model.layoutUnit * STROKE_UNITS
        stroke.color = COLOR_LABEL
        canvas.drawLine(pill.x - w, pill.y - h, pill.x, pill.y + h, stroke)
        canvas.drawLine(pill.x, pill.y + h, pill.x + w, pill.y - h, stroke)
    }

    /** The selected control's outline, its corner handle, and the hint line. */
    private fun drawEditorChrome(canvas: Canvas) {
        val selected = model.selected
        if (selected != OnScreenPadModel.NO_CONTROL) {
            val shape = model.shape(selected)
            stroke.color = COLOR_PRESSED
            if (shape.circular) {
                canvas.drawCircle(shape.x, shape.y, shape.radius, stroke)
            } else {
                canvas.drawRoundRect(
                    shape.x - shape.halfWidth,
                    shape.y - shape.halfHeight,
                    shape.x + shape.halfWidth,
                    shape.y + shape.halfHeight,
                    shape.halfHeight,
                    shape.halfHeight,
                    stroke
                )
            }
        }
        val handle = model.handle
        if (handle.x != OnScreenPadModel.OFF_SCREEN) {
            fill.color = COLOR_PRESSED
            canvas.drawRoundRect(
                handle.x - handle.halfWidth,
                handle.y - handle.halfHeight,
                handle.x + handle.halfWidth,
                handle.y + handle.halfHeight,
                handle.halfWidth,
                handle.halfWidth,
                fill
            )
            stroke.color = COLOR_LABEL
            canvas.drawRoundRect(
                handle.x - handle.halfWidth,
                handle.y - handle.halfHeight,
                handle.x + handle.halfWidth,
                handle.y + handle.halfHeight,
                handle.halfWidth,
                handle.halfWidth,
                stroke
            )
        }
        label.textSize = model.unit * HINT_UNITS
        canvas.drawText(
            "drag to move · corner to resize · size %.2fx".format(layout.global),
            width / 2f,
            centreLine(model.unit * HINT_Y),
            label
        )
    }

    private fun drawStick(canvas: Canvas, control: Int, axis: Int) {
        val shape = model.shape(control)
        stroke.color = COLOR_STROKE
        canvas.drawCircle(shape.x, shape.y, shape.radius, stroke)
        val knob = model.knobRadius(control)
        val travel = shape.radius - knob
        val x = shape.x + model.axes[axis] * travel
        val y = shape.y + model.axes[axis + 1] * travel
        if (model.axes[axis] != 0f || model.axes[axis + 1] != 0f) {
            fill.color = COLOR_PRESSED
            canvas.drawCircle(x, y, knob, fill)
        }
        drawOutline(canvas, x, y, knob)
    }

    private fun drawDpad(canvas: Canvas) {
        dpadKey(canvas, OnScreenPadModel.DPAD_UP, 0f, -1f)
        dpadKey(canvas, OnScreenPadModel.DPAD_DOWN, 0f, 1f)
        dpadKey(canvas, OnScreenPadModel.DPAD_LEFT, -1f, 0f)
        dpadKey(canvas, OnScreenPadModel.DPAD_RIGHT, 1f, 0f)
    }

    private fun dpadKey(canvas: Canvas, control: Int, dx: Float, dy: Float) {
        val shape = model.shape(control)
        val held = model.buttons[model.buttonIndex(control)]
        fill.color = if (held) COLOR_PRESSED else COLOR_IDLE
        canvas.drawCircle(shape.x, shape.y, shape.radius, fill)
        stroke.color = COLOR_STROKE
        canvas.drawCircle(shape.x, shape.y, shape.radius, stroke)
        /* A small wedge pointing the way this key drives. */
        val reach = shape.radius * 0.45f
        val baseX = shape.x - dx * reach * 0.5f
        val baseY = shape.y - dy * reach * 0.5f
        triangle.rewind()
        triangle.moveTo(shape.x + dx * reach, shape.y + dy * reach)
        triangle.lineTo(baseX - dy * reach * 0.8f, baseY + dx * reach * 0.8f)
        triangle.lineTo(baseX + dy * reach * 0.8f, baseY - dx * reach * 0.8f)
        triangle.close()
        fill.color = if (held) COLOR_LABEL else COLOR_STROKE
        canvas.drawPath(triangle, fill)
    }

    private fun drawDisc(canvas: Canvas, control: Int, text: String) {
        val shape = model.shape(control)
        val held = model.buttons[model.buttonIndex(control)]
        fill.color = if (held) COLOR_PRESSED else COLOR_IDLE
        canvas.drawCircle(shape.x, shape.y, shape.radius, fill)
        stroke.color = COLOR_STROKE
        canvas.drawCircle(shape.x, shape.y, shape.radius, stroke)
        label.textSize = min(model.layoutUnit * TEXT_UNITS, 0.9f * shape.radius)
        canvas.drawText(text, shape.x, centreLine(shape.y), label)
    }

    private fun drawControlPill(canvas: Canvas, control: Int, text: String) {
        val shape = model.shape(control)
        val held = model.buttons[model.buttonIndex(control)]
        label.textSize = min(model.layoutUnit * TEXT_UNITS, 0.9f * shape.halfHeight)
        drawPill(canvas, shape, held, text)
    }

    private fun drawRowPill(canvas: Canvas, which: Int, text: String) {
        drawPill(canvas, model.pill(which), false, text)
    }

    private fun drawPill(canvas: Canvas, shape: OnScreenPadModel.Shape, held: Boolean, text: String) {
        fill.color = if (held) COLOR_PRESSED else COLOR_IDLE
        canvas.drawRoundRect(
            shape.x - shape.halfWidth,
            shape.y - shape.halfHeight,
            shape.x + shape.halfWidth,
            shape.y + shape.halfHeight,
            shape.halfHeight,
            shape.halfHeight,
            fill
        )
        stroke.color = COLOR_STROKE
        canvas.drawRoundRect(
            shape.x - shape.halfWidth,
            shape.y - shape.halfHeight,
            shape.x + shape.halfWidth,
            shape.y + shape.halfHeight,
            shape.halfHeight,
            shape.halfHeight,
            stroke
        )
        canvas.drawText(text, shape.x, centreLine(shape.y), label)
    }

    private fun drawOutline(canvas: Canvas, x: Float, y: Float, radius: Float) {
        stroke.color = COLOR_STROKE
        canvas.drawCircle(x, y, radius, stroke)
    }

    /** `drawText`'s y is the baseline; centre the glyphs on [y] instead. */
    private fun centreLine(y: Float): Float = y - (label.ascent() + label.descent()) / 2f

    companion object {
        /** How long a physical controller has to stay quiet before the controls come back. */
        const val CONTROLLER_IDLE_MS = 60_000L

        private const val AXIS_EPSILON = 1e-4f
        private const val STROKE_UNITS = 0.06f
        private const val TEXT_UNITS = 0.42f
        private const val HINT_UNITS = 0.34f

        /** The hint line's centre height, in raw touch units. */
        private const val HINT_Y = 2.1f

        /* The launcher icon's palette. Plain vals: the alpha-lit hex values exceed Int.MAX_VALUE, so
         * Kotlin would infer Long for them. */
        private val COLOR_IDLE = 0x66101820
        private val COLOR_STROKE = 0x99E8DCC8.toInt()
        private val COLOR_PRESSED = 0xCC2F9E9B.toInt()
        private val COLOR_LABEL = 0xFFE8DCC8.toInt()
    }
}
