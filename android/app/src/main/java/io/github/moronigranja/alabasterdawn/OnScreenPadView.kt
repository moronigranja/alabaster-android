package io.github.moronigranja.alabasterdawn

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The on-screen pad: draws [OnScreenPadModel] and feeds what it holds into [Gamepad], which merges
 * it with the physical pad's state into the one standard pad the engine polls.
 *
 * Drawing and hit rules live in the model; this class only turns Android's `MotionEvent` stream into
 * model calls and the model's state into canvas primitives. Its own state is just the two switches
 * — the user's `padEnabled` preference and `controllerInUse` — and their one combined effect,
 * [applyOverlay].
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

    /**
     * The user's choice, set from the saved preference by the Activity and flipped by a tap on the
     * toggle. Assigning it re-applies the overlay contribution, so a pad started hidden never
     * publishes state.
     */
    var padEnabled = true
        set(value) {
            if (field == value) return
            field = value
            applyOverlay()
            invalidate()
        }

    /** True while a physical controller is being used: the controls hide until it goes quiet. */
    var controllerInUse = false

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
        if (!controllerInUse) {
            controllerInUse = true
            applyOverlay()
            invalidate()
        }
        removeCallbacks(showAgain)
        postDelayed(showAgain, CONTROLLER_IDLE_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(showAgain)
        super.onDetachedFromWindow()
    }

    private fun controlsDrawn(): Boolean = padEnabled && !controllerInUse

    /**
     * The one place the overlay contributes to the gamepad. Disabling clears what the overlay last
     * reported, so a finger held down while the pad disappears leaves no stuck button behind; and
     * re-enabling republishes instead of diffing, because that clearing happened on Gamepad's side.
     */
    private fun applyOverlay() {
        val drawn = controlsDrawn()
        Gamepad.setOverlayEnabled(drawn)
        if (drawn) pushState(force = true)
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
                if (model.toggleHit(x, y)) {
                    padEnabled = !padEnabled
                    onToggle?.invoke(padEnabled)
                    consumed = true
                } else if (controlsDrawn()) {
                    /* While the controls are drawn the pad consumes the whole gesture: the page has
                     * no touch UI, and the engine has no touch input device to fall back on. */
                    model.down(id, x, y)
                    if (pushState()) invalidate()
                    consumed = true
                }
                /* A consumed DOWN never reaches the WebView's own touch listener. */
                if (consumed && event.actionMasked == MotionEvent.ACTION_DOWN) onFirstTouch?.invoke()
            }

            MotionEvent.ACTION_MOVE -> {
                if (controlsDrawn()) {
                    var tracked = false
                    for (p in 0 until event.pointerCount) {
                        tracked = model.move(event.getPointerId(p), event.getX(p), event.getY(p)) || tracked
                    }
                    if (tracked && pushState()) invalidate()
                    consumed = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (model.up(id) && pushState()) invalidate()
                consumed = controlsDrawn()
            }

            MotionEvent.ACTION_CANCEL -> {
                for (p in 0 until event.pointerCount) model.up(event.getPointerId(p))
                if (pushState()) invalidate()
                consumed = true
            }
        }
        return consumed
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
        val u = model.unit
        stroke.strokeWidth = u * STROKE_UNITS
        label.textSize = u * TEXT_UNITS
        val drawn = controlsDrawn()
        if (drawn) {
            drawStick(canvas, OnScreenPadModel.LEFT_STICK, 0)
            drawDpad(canvas)
            drawStick(canvas, OnScreenPadModel.RIGHT_STICK, 2)
            drawDisc(canvas, OnScreenPadModel.FACE_Y, "Y")
            drawDisc(canvas, OnScreenPadModel.FACE_A, "A")
            drawDisc(canvas, OnScreenPadModel.FACE_X, "X")
            drawDisc(canvas, OnScreenPadModel.FACE_B, "B")
            drawPill(canvas, OnScreenPadModel.L1, "L1")
            drawPill(canvas, OnScreenPadModel.L2, "L2")
            drawPill(canvas, OnScreenPadModel.R1, "R1")
            drawPill(canvas, OnScreenPadModel.R2, "R2")
            drawPill(canvas, OnScreenPadModel.SELECT, "SEL")
            drawPill(canvas, OnScreenPadModel.START, "START")
            drawPill(canvas, OnScreenPadModel.HOME, "HOME")
        }
        /* The toggle is drawn last and always, so the pad can be brought back after hiding it. */
        drawPill(canvas, model.toggle, false, if (drawn) "HIDE" else "PAD")
    }

    private fun drawStick(canvas: Canvas, control: Int, axis: Int) {
        val shape = model.shape(control)
        stroke.color = COLOR_STROKE
        canvas.drawCircle(shape.x, shape.y, shape.radius, stroke)
        val knob = model.unit * OnScreenPadModel.STICK_KNOB
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
        canvas.drawText(text, shape.x, centreLine(shape.y), label)
    }

    private fun drawPill(canvas: Canvas, control: Int, text: String) {
        val shape = model.shape(control)
        drawPill(canvas, shape, model.buttons[model.buttonIndex(control)], text)
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

        /* The launcher icon's palette. Plain vals: the alpha-lit hex values exceed Int.MAX_VALUE, so
         * Kotlin would infer Long for them. */
        private val COLOR_IDLE = 0x66101820
        private val COLOR_STROKE = 0x99E8DCC8.toInt()
        private val COLOR_PRESSED = 0xCC2F9E9B.toInt()
        private val COLOR_LABEL = 0xFFE8DCC8.toInt()
    }
}
