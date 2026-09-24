package io.github.moronigranja.alabasterdawn

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Geometry, hit rules and pointer bookkeeping for the on-screen pad. Deliberately free of Android
 * types, so the layout and the hit rules can be unit-tested on the JVM — the same reason
 * [GamepadState] has none.
 *
 * Coordinates are view pixels with `y` down. Every size derives from one touch unit `u`, computed by
 * [resize] as `min(width / 14, min(width, height) * 0.115)`: the height term sizes the pad on a
 * normal landscape window, the width term keeps the two clusters apart when the window is too
 * narrow for the full layout.
 *
 * A pointer claims a control only on DOWN inside it, one pointer per control, and keeps it until it
 * lifts, so sliding off a control towards another one can never fire that other control. A
 * button/pill additionally clears while its pointer is outside its hit area (a drag off a button
 * must not leave it held); a stick keeps tracking, because a flick overshoots its ring by nature,
 * and clamps the deflection to 1.
 */
class OnScreenPadModel {

    /** One control's shape: circles read [radius], pills their half extents. */
    class Shape {
        var x = 0f
        var y = 0f
        var radius = 0f
        var halfWidth = 0f
        var halfHeight = 0f
        var circular = true
    }

    /** The 4 axes and 17 buttons [GamepadState] publishes, in the same order. */
    val axes = FloatArray(GamepadState.AXES)
    val buttons = BooleanArray(GamepadState.BUTTONS)

    /** Touch unit of the current layout: stroke widths and text sizes are multiples of it. */
    var unit = 0f
        private set

    private val shapes = Array(CONTROLS) { Shape() }
    private val pointerOf = IntArray(CONTROLS) { NO_POINTER }
    private val controlOf = HashMap<Int, Int>()

    /** The pad toggle, drawn and hit-tested separately from the controls (no pointer claims it). */
    val toggle = Shape()

    fun shape(control: Int): Shape = shapes[control]

    fun resize(width: Float, height: Float) {
        val u = min(width / WIDTH_DIVISOR, min(width, height) * HEIGHT_FRACTION)
        unit = u
        val pad = u * EDGE_PAD

        /* The left stick sits one radius plus the edge pad from the corner: any closer and its ring
         * is clipped by the screen edge, leaving full deflection towards that corner unreachable. */
        val corner = u * (STICK_RADIUS + EDGE_PAD)
        circle(LEFT_STICK, corner, height - corner, u * STICK_RADIUS)
        circle(RIGHT_STICK, width - u * 5.2f - pad, height - u * 1.7f - pad, u * STICK_RADIUS)

        val dpadX = u * 4.8f + pad
        val dpadY = height - u * 1.6f - pad
        circle(DPAD_UP, dpadX, dpadY - u * CROSS_OFFSET, u * CROSS_RADIUS)
        circle(DPAD_DOWN, dpadX, dpadY + u * CROSS_OFFSET, u * CROSS_RADIUS)
        circle(DPAD_LEFT, dpadX - u * CROSS_OFFSET, dpadY, u * CROSS_RADIUS)
        circle(DPAD_RIGHT, dpadX + u * CROSS_OFFSET, dpadY, u * CROSS_RADIUS)

        /* Diamond geometry, the same face ordering the engine registers: Y top, A bottom, X left,
         * B right. */
        val faceX = width - u * 1.9f - pad
        val faceY = height - u * 1.9f - pad
        circle(FACE_Y, faceX, faceY - u * CROSS_OFFSET, u * CROSS_RADIUS)
        circle(FACE_A, faceX, faceY + u * CROSS_OFFSET, u * CROSS_RADIUS)
        circle(FACE_X, faceX - u * CROSS_OFFSET, faceY, u * CROSS_RADIUS)
        circle(FACE_B, faceX + u * CROSS_OFFSET, faceY, u * CROSS_RADIUS)

        pill(L1, dpadX, height - u * 3.6f - pad, u * SHOULDER_HALF_WIDTH, u * SHOULDER_HALF_HEIGHT)
        pill(L2, dpadX, height - u * 4.5f - pad, u * SHOULDER_HALF_WIDTH, u * SHOULDER_HALF_HEIGHT)
        /* A row higher than the D-pad's, so the pill cannot sit on the Y key it points at. */
        pill(R1, faceX, height - u * 3.85f - pad, u * SHOULDER_HALF_WIDTH, u * SHOULDER_HALF_HEIGHT)
        pill(R2, faceX, height - u * 4.75f - pad, u * SHOULDER_HALF_WIDTH, u * SHOULDER_HALF_HEIGHT)

        pill(SELECT, width / 2f - u, height - u * 0.9f - pad, u * SMALL_HALF_WIDTH, u * SMALL_HALF_HEIGHT)
        pill(START, width / 2f + u, height - u * 0.9f - pad, u * SMALL_HALF_WIDTH, u * SMALL_HALF_HEIGHT)
        pill(HOME, width / 2f, height - u * 1.6f - pad, u * SMALL_HALF_WIDTH, u * SMALL_HALF_HEIGHT)

        toggle.x = width / 2f
        toggle.y = u * 0.8f + pad
        toggle.halfWidth = u * TOGGLE_HALF_WIDTH
        toggle.halfHeight = u * TOGGLE_HALF_HEIGHT
        toggle.circular = false
    }

    /**
     * A DOWN inside a control claims it. False when the point is on no control, or when that
     * control already belongs to another pointer (two fingers must never fight over one axis).
     */
    fun down(id: Int, x: Float, y: Float): Boolean {
        if (controlOf.containsKey(id)) return false
        val control = controlAt(x, y)
        if (control == NO_CONTROL || pointerOf[control] != NO_POINTER) return false
        pointerOf[control] = id
        controlOf[id] = control
        apply(control, x, y)
        return true
    }

    /** False for an unknown pointer, so a stray event changes nothing. */
    fun move(id: Int, x: Float, y: Float): Boolean {
        val control = controlOf[id] ?: return false
        apply(control, x, y)
        return true
    }

    fun up(id: Int): Boolean {
        val control = controlOf.remove(id) ?: return false
        pointerOf[control] = NO_POINTER
        clear(control)
        return true
    }

    fun toggleHit(x: Float, y: Float): Boolean =
        abs(x - toggle.x) <= toggle.halfWidth && abs(y - toggle.y) <= toggle.halfHeight

    /** The W3C index a button/pill control drives, for the View's pressed state; -1 for the sticks. */
    fun buttonIndex(control: Int): Int = BUTTON_INDEX[control]

    /** Sticks write their axes (dead zone rescaled, so full deflection is still 1); buttons their index. */
    private fun apply(control: Int, x: Float, y: Float) {
        if (control == LEFT_STICK || control == RIGHT_STICK) {
            val shape = shapes[control]
            val dx = x - shape.x
            val dy = y - shape.y
            val r = hypot(dx, dy)
            val dead = shape.radius * STICK_DEADZONE
            val magnitude = if (r <= dead) 0f else min((r - dead) / (shape.radius - dead), 1f)
            val base = if (control == LEFT_STICK) 0 else 2
            axes[base] = if (r == 0f) 0f else magnitude * dx / r
            axes[base + 1] = if (r == 0f) 0f else magnitude * dy / r
            return
        }
        buttons[buttonIndex(control)] = inside(control, x, y)
    }

    private fun clear(control: Int) {
        when (control) {
            LEFT_STICK -> {
                axes[0] = 0f
                axes[1] = 0f
            }
            RIGHT_STICK -> {
                axes[2] = 0f
                axes[3] = 0f
            }
            else -> buttons[buttonIndex(control)] = false
        }
    }

    /** First match wins, hence the control ids are ordered from the precise controls to the wide ones. */
    private fun controlAt(x: Float, y: Float): Int {
        for (control in 0 until CONTROLS) if (inside(control, x, y)) return control
        return NO_CONTROL
    }

    private fun inside(control: Int, x: Float, y: Float): Boolean {
        val shape = shapes[control]
        if (shape.circular) return hypot(x - shape.x, y - shape.y) <= shape.radius
        return abs(x - shape.x) <= shape.halfWidth && abs(y - shape.y) <= shape.halfHeight
    }

    private fun circle(control: Int, x: Float, y: Float, radius: Float) {
        val shape = shapes[control]
        shape.x = x
        shape.y = y
        shape.radius = radius
        shape.circular = true
    }

    private fun pill(control: Int, x: Float, y: Float, halfWidth: Float, halfHeight: Float) {
        val shape = shapes[control]
        shape.x = x
        shape.y = y
        shape.halfWidth = halfWidth
        shape.halfHeight = halfHeight
        shape.circular = false
    }

    companion object {
        const val NO_CONTROL = -1
        private const val NO_POINTER = -1

        /** Control ids, ordered so the precise controls are hit-tested before the wide ones. */
        const val LEFT_STICK = 0
        const val DPAD_UP = 1
        const val DPAD_DOWN = 2
        const val DPAD_LEFT = 3
        const val DPAD_RIGHT = 4
        const val RIGHT_STICK = 5
        const val FACE_A = 6
        const val FACE_B = 7
        const val FACE_X = 8
        const val FACE_Y = 9
        const val L1 = 10
        const val L2 = 11
        const val R1 = 12
        const val R2 = 13
        const val SELECT = 14
        const val START = 15
        const val HOME = 16
        const val CONTROLS = 17

        /** W3C standard indices these controls drive; -1 for the sticks, which drive axes. */
        private val BUTTON_INDEX = intArrayOf(
            -1, // left stick
            GamepadState.DPAD_FIRST, // D-pad up
            GamepadState.DPAD_FIRST + 1, // down
            GamepadState.DPAD_FIRST + 2, // left
            GamepadState.DPAD_FIRST + 3, // right
            -1, // right stick
            0, // A
            1, // B
            2, // X
            3, // Y
            4, // L1
            6, // L2
            5, // R1
            7, // R2
            8, // select
            9, // start
            16, // home
        )

        /**
         * Touch unit = `min(width / WIDTH_DIVISOR, min(width, height) * HEIGHT_FRACTION)`. The width
         * term is the tightest of the layout's horizontal requirements — the widest is that the
         * right stick's left edge (`width - 7.05u`) stays clear of Start's right edge
         * (`width / 2 + 1.6u`), i.e. `width >= 17.3u` — plus a little margin.
         */
        private const val WIDTH_DIVISOR = 18f
        private const val HEIGHT_FRACTION = 0.115f

        /** Radius of the left stick's knob, in touch units — drawn by the View at the deflection. */
        const val STICK_KNOB = 0.62f

        private const val EDGE_PAD = 0.35f
        private const val STICK_RADIUS = 1.5f
        private const val STICK_DEADZONE = 0.12f

        /**
         * Both crosses (D-pad and face diamond) are 4 keys of [CROSS_RADIUS] at [CROSS_OFFSET]: the
         * offset exceeds the radius by more than the diagonal spacing needs, so no two keys of a
         * cluster overlap, and a hit test can never be ambiguous between them.
         */
        private const val CROSS_OFFSET = 0.9f
        private const val CROSS_RADIUS = 0.6f

        /** Shoulder and trigger pills (1.5u x 0.7u) and the smaller Select/Start/HOME ones (1.2u x 0.62u). */
        private const val SHOULDER_HALF_WIDTH = 0.75f
        private const val SHOULDER_HALF_HEIGHT = 0.35f
        private const val SMALL_HALF_WIDTH = 0.6f
        private const val SMALL_HALF_HEIGHT = 0.31f
        private const val TOGGLE_HALF_WIDTH = 1.3f
        private const val TOGGLE_HALF_HEIGHT = 0.35f
    }
}
