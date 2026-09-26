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
 * The stock layout lives in `base[]`, computed from the *effective* unit [layoutUnit] (`u` times the
 * layout's global scale). Each control's live [shape] is its base plus the [PadLayout] delta — an
 * offset in raw `u` units and a scale on the extent — clamped so it stays in the viewport. A default
 * layout therefore reproduces the stock pad exactly, and one layout works for every window size.
 *
 * A pointer claims a control only on DOWN inside it, one pointer per control, and keeps it until it
 * lifts, so sliding off a control towards another one can never fire that other control. A
 * button/pill additionally clears while its pointer is outside its hit area (a drag off a button
 * must not leave it held); a stick keeps tracking, because a flick overshoots its ring by nature,
 * and clamps the deflection to 1.
 *
 * The editor ([editing]/[selected]) reuses the same shapes: its drags go through
 * [beginDrag]/[dragTo]/[beginScale]/[scaleTo] instead of the play path, so editing and play input
 * cannot interfere.
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

    /** Raw touch unit of the current window: stroke widths and text sizes are multiples of it. */
    var unit = 0f
        private set

    /** The user's override set; assigning it re-places every control. */
    var layout = PadLayout()
        set(value) {
            field = value
            placeAll()
        }

    /** True while the layout editor is open: the pill row changes and no pad state is published. */
    var editing = false
        private set

    /**
     * True while the user has the pad hidden. The pill row is then just the way back - one small
     * centred pill drawn as a downward chevron, and no EDIT pill, because there is no layout on screen
     * to edit. Set by the View from its `padEnabled` preference; the pill geometry follows it.
     */
    var padHidden = false
        set(value) {
            if (field == value) return
            field = value
            placePills()
        }

    /** The control the editor has selected, or [NO_CONTROL]. */
    var selected = NO_CONTROL
        private set

    private val shapes = Array(CONTROLS) { Shape() }
    private val base = Array(CONTROLS) { Shape() }
    private val pointerOf = IntArray(CONTROLS) { NO_POINTER }
    private val controlOf = HashMap<Int, Int>()
    private val grabX = FloatArray(CONTROLS)
    private val grabY = FloatArray(CONTROLS)

    private var viewWidth = 0f
    private var viewHeight = 0f

    private var scaleGrabDist = 0f
    private var scaleGrabScale = 1f

    /** The four pills of the top row, placed by [placePills] for the current mode. */
    private val pills = Array(4) { Shape() }

    /** The selected control's resize handle; off-screen when nothing is selected. */
    val handle = Shape()

    /** The unit the pad is drawn and sized with: [unit] times the layout's global scale. */
    val layoutUnit: Float get() = unit * layout.global

    fun shape(control: Int): Shape = shapes[control]

    /** The top-row pill [which] ([PILL_A] … [PILL_B]); off-screen pills have `x == OFF_SCREEN`. */
    fun pill(which: Int): Shape = pills[which]

    fun resize(width: Float, height: Float) {
        viewWidth = width
        viewHeight = height
        unit = min(width / WIDTH_DIVISOR, min(width, height) * HEIGHT_FRACTION)
        placeAll()
    }

    /* ------------------------------------------------------------------ layout editor ---- */

    fun setEditing(editing: Boolean) {
        this.editing = editing
        placePills()
    }

    /** The control a point falls on, or [NO_CONTROL]. First match wins (see [CONTROLS] ordering). */
    fun controlAt(x: Float, y: Float): Int {
        for (control in 0 until CONTROLS) if (inside(control, x, y)) return control
        return NO_CONTROL
    }

    fun beginDrag(control: Int, x: Float, y: Float) {
        selected = control
        grabX[control] = x
        grabY[control] = y
        placeHandle()
    }

    /** Moves the grabbed control by the finger's delta, clamped to the viewport, and stores the result. */
    fun dragTo(control: Int, x: Float, y: Float) {
        if (unit <= 0f) return
        val dx = x - grabX[control]
        val dy = y - grabY[control]
        grabX[control] = x
        grabY[control] = y
        layout.offsetX[control] += dx / unit
        layout.offsetY[control] += dy / unit
        place(control)
        storeOffsets(control)
        placeHandle()
    }

    fun beginScale(control: Int, x: Float, y: Float) {
        selected = control
        val shape = shapes[control]
        scaleGrabDist = hypot(x - shape.x, y - shape.y)
        scaleGrabScale = layout.scale[control]
    }

    /** Scales the grabbed control by the change in distance from its centre, then stores the result. */
    fun scaleTo(control: Int, x: Float, y: Float) {
        if (unit <= 0f) return
        val shape = shapes[control]
        val dist = hypot(x - shape.x, y - shape.y)
        val factor = if (scaleGrabDist <= 0f) 1f else dist / scaleGrabDist
        layout.scale[control] =
            (scaleGrabScale * factor).coerceIn(PadLayout.MIN_SCALE, PadLayout.MAX_SCALE)
        place(control)
        storeScale(control)
        placeHandle()
    }

    /** Grows or shrinks the whole pad about its own layout; the stored offsets are left alone. */
    fun addGlobal(delta: Float) {
        layout.global =
            (layout.global + delta).coerceIn(PadLayout.MIN_GLOBAL, PadLayout.MAX_GLOBAL)
        placeAll()
    }

    /** True inside the resize handle's grab radius (and only when a control is selected). */
    fun handleHit(x: Float, y: Float): Boolean =
        selected != NO_CONTROL && hypot(x - handle.x, y - handle.y) <= HANDLE_GRAB * unit

    /** Radius of a stick's drawn knob, following the control's scale. */
    fun knobRadius(control: Int): Float =
        shapes[control].radius * (STICK_KNOB / STICK_RADIUS)

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

    /** Releases every claimed pointer, so opening the editor cannot leave a control stuck held. */
    fun releaseAll() {
        if (controlOf.isEmpty()) return
        for (id in controlOf.keys.toList()) up(id)
    }

    /** Which of the four pills a point falls on, or [PILL_NONE] when it is on none. */
    fun pillAt(x: Float, y: Float): Int {
        for (which in 0 until 4) {
            val pill = pills[which]
            if (pill.x == OFF_SCREEN) continue
            if (abs(x - pill.x) <= pill.halfWidth && abs(y - pill.y) <= pill.halfHeight) return which
        }
        return PILL_NONE
    }

    /** The W3C index a button/pill control drives, for the View's pressed state; -1 for the sticks. */
    fun buttonIndex(control: Int): Int = BUTTON_INDEX[control]

    /* ------------------------------------------------------------------------ geometry ---- */

    private fun placeAll() {
        if (unit <= 0f) return
        computeBase()
        for (control in 0 until CONTROLS) place(control)
        placeHandle()
        placePills()
    }

    /** The stock layout, at the effective unit [layoutUnit]. */
    private fun computeBase() {
        val u = layoutUnit
        val w = viewWidth
        val h = viewHeight
        val pad = u * EDGE_PAD

        /* The left stick sits one radius plus the edge pad from the corner: any closer and its ring
         * is clipped by the screen edge, leaving full deflection towards that corner unreachable. */
        val corner = u * (STICK_RADIUS + EDGE_PAD)
        circle(base[LEFT_STICK], corner, h - corner, u * STICK_RADIUS)
        circle(base[RIGHT_STICK], w - u * 5.2f - pad, h - u * 1.7f - pad, u * STICK_RADIUS)

        val dpadX = u * 4.8f + pad
        val dpadY = h - u * 1.6f - pad
        circle(base[DPAD_UP], dpadX, dpadY - u * CROSS_OFFSET, u * CROSS_RADIUS)
        circle(base[DPAD_DOWN], dpadX, dpadY + u * CROSS_OFFSET, u * CROSS_RADIUS)
        circle(base[DPAD_LEFT], dpadX - u * CROSS_OFFSET, dpadY, u * CROSS_RADIUS)
        circle(base[DPAD_RIGHT], dpadX + u * CROSS_OFFSET, dpadY, u * CROSS_RADIUS)

        /* Diamond geometry, the same face ordering the engine registers: Y top, A bottom, X left,
         * B right. */
        val faceX = w - u * 1.9f - pad
        val faceY = h - u * 1.9f - pad
        circle(base[FACE_Y], faceX, faceY - u * CROSS_OFFSET, u * CROSS_RADIUS)
        circle(base[FACE_A], faceX, faceY + u * CROSS_OFFSET, u * CROSS_RADIUS)
        circle(base[FACE_X], faceX - u * CROSS_OFFSET, faceY, u * CROSS_RADIUS)
        circle(base[FACE_B], faceX + u * CROSS_OFFSET, faceY, u * CROSS_RADIUS)

        pillShape(base[L1], dpadX, h - u * 3.6f - pad, u * SHOULDER_HALF_WIDTH, u * SHOULDER_HALF_HEIGHT)
        pillShape(base[L2], dpadX, h - u * 4.5f - pad, u * SHOULDER_HALF_WIDTH, u * SHOULDER_HALF_HEIGHT)
        /* A row higher than the D-pad's, so the pill cannot sit on the Y key it points at. */
        pillShape(base[R1], faceX, h - u * 3.85f - pad, u * SHOULDER_HALF_WIDTH, u * SHOULDER_HALF_HEIGHT)
        pillShape(base[R2], faceX, h - u * 4.75f - pad, u * SHOULDER_HALF_WIDTH, u * SHOULDER_HALF_HEIGHT)

        pillShape(base[SELECT], w / 2f - u, h - u * 0.9f - pad, u * SMALL_HALF_WIDTH, u * SMALL_HALF_HEIGHT)
        pillShape(base[START], w / 2f + u, h - u * 0.9f - pad, u * SMALL_HALF_WIDTH, u * SMALL_HALF_HEIGHT)
        pillShape(base[HOME], w / 2f, h - u * 1.6f - pad, u * SMALL_HALF_WIDTH, u * SMALL_HALF_HEIGHT)
    }

    /** One control's live shape: its base plus the layout's offset and scale, clamped inside the view. */
    private fun place(control: Int) {
        val b = base[control]
        val s = shapes[control]
        s.circular = b.circular
        s.x = b.x + layout.offsetX[control] * unit
        s.y = b.y + layout.offsetY[control] * unit
        s.radius = b.radius * layout.scale[control]
        s.halfWidth = b.halfWidth * layout.scale[control]
        s.halfHeight = b.halfHeight * layout.scale[control]
        clamp(control)
    }

    /** Keeps [control]'s centre at least [CLAMP_MARGIN]u away from every edge, whatever its size. */
    private fun clamp(control: Int) {
        val s = shapes[control]
        val m = CLAMP_MARGIN * unit
        val ex = if (s.circular) s.radius else s.halfWidth
        val ey = if (s.circular) s.radius else s.halfHeight
        s.x = s.x.coerceIn(ex + m, maxOf(ex + m, viewWidth - ex - m))
        s.y = s.y.coerceIn(ey + m, maxOf(ey + m, viewHeight - ey - m))
    }

    /** Writes the placed position back into the layout, so the stored value equals what is drawn. */
    private fun storeOffsets(control: Int) {
        layout.offsetX[control] = (shapes[control].x - base[control].x) / unit
        layout.offsetY[control] = (shapes[control].y - base[control].y) / unit
    }

    private fun storeScale(control: Int) {
        val b = base[control]
        val s = shapes[control]
        layout.scale[control] = if (b.circular) {
            if (b.radius == 0f) 1f else s.radius / b.radius
        } else {
            if (b.halfWidth == 0f) 1f else s.halfWidth / b.halfWidth
        }
    }

    private fun placeHandle() {
        val s = if (selected == NO_CONTROL) null else shapes[selected]
        if (s == null) {
            handle.x = OFF_SCREEN
            handle.y = OFF_SCREEN
            return
        }
        val ex = if (s.circular) s.radius else s.halfWidth
        val ey = if (s.circular) s.radius else s.halfHeight
        val h = HANDLE_HALF * unit
        handle.x = s.x + ex
        handle.y = s.y + ey
        handle.halfWidth = h
        handle.halfHeight = h
        handle.circular = false
    }

    /** The top row for the current mode; pills not in that mode go off-screen so they cannot be hit. */
    private fun placePills() {
        val u = unit
        val pad = u * EDGE_PAD
        val rowY = u * 0.8f + pad
        for (which in 0 until 4) {
            pills[which].apply {
                circular = false
                halfHeight = u * PILL_HALF_HEIGHT
                halfWidth = 0f
                x = OFF_SCREEN
                y = OFF_SCREEN
            }
        }
        val centre = viewWidth / 2f
        if (editing) {
            setPill(PILL_A, centre - u * PILL_EDIT_OFFSET, rowY, u * PILL_WIDE, u * PILL_HALF_HEIGHT)
            setPill(PILL_MINUS, centre - u * PILL_MINI_OFFSET, rowY, u * PILL_NARROW, u * PILL_HALF_HEIGHT)
            setPill(PILL_PLUS, centre + u * PILL_MINI_OFFSET, rowY, u * PILL_NARROW, u * PILL_HALF_HEIGHT)
            setPill(PILL_B, centre + u * PILL_EDIT_OFFSET, rowY, u * PILL_WIDE, u * PILL_HALF_HEIGHT)
        } else if (padHidden) {
            setPill(PILL_A, centre, rowY, u * PILL_CHEVRON_HALF_WIDTH, u * PILL_CHEVRON_HALF_HEIGHT)
        } else {
            setPill(PILL_A, centre - u * PILL_PLAY_OFFSET, rowY, u * PILL_WIDE, u * PILL_HALF_HEIGHT)
            setPill(PILL_B, centre + u * PILL_PLAY_OFFSET, rowY, u * PILL_WIDE, u * PILL_HALF_HEIGHT)
        }
    }

    private fun setPill(which: Int, x: Float, y: Float, halfWidth: Float, halfHeight: Float) {
        pills[which].apply {
            circular = false
            this.x = x
            this.y = y
            this.halfWidth = halfWidth
            this.halfHeight = halfHeight
        }
    }

    /* -------------------------------------------------------------------------- input ---- */

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

    private fun inside(control: Int, x: Float, y: Float): Boolean {
        val shape = shapes[control]
        if (shape.circular) return hypot(x - shape.x, y - shape.y) <= shape.radius
        return abs(x - shape.x) <= shape.halfWidth && abs(y - shape.y) <= shape.halfHeight
    }

    private fun circle(shape: Shape, x: Float, y: Float, radius: Float) {
        shape.x = x
        shape.y = y
        shape.radius = radius
        shape.circular = true
    }

    private fun pillShape(shape: Shape, x: Float, y: Float, halfWidth: Float, halfHeight: Float) {
        shape.x = x
        shape.y = y
        shape.halfWidth = halfWidth
        shape.halfHeight = halfHeight
        shape.circular = false
    }

    companion object {
        const val NO_CONTROL = -1
        private const val NO_POINTER = -1

        /** The four pills of the top row, in draw/hit order. */
        const val PILL_A = 0
        const val PILL_MINUS = 1
        const val PILL_PLUS = 2
        const val PILL_B = 3
        const val PILL_NONE = -1

        /** Off-screen x for a pill or handle that must not be drawn or hit. */
        const val OFF_SCREEN = -10f

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
        private const val STICK_KNOB = 0.62f

        private const val EDGE_PAD = 0.35f
        private const val STICK_RADIUS = 1.5f
        private const val STICK_DEADZONE = 0.12f

        /** How far a control's centre must stay from an edge, in touch units. */
        private const val CLAMP_MARGIN = 0.2f

        /** The editor's resize handle: a 0.25u-half square, grabbed within 0.4u. */
        private const val HANDLE_HALF = 0.25f
        private const val HANDLE_GRAB = 0.4f

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

        /** Top row: half height, wide/narrow half widths, and the two rows' offsets from centre. */
        private const val PILL_HALF_HEIGHT = 0.35f
        private const val PILL_WIDE = 1.3f
        private const val PILL_NARROW = 0.7f
        /* The way back when the pad is hidden: narrower than a wide pill and a little shorter, since
         * the chevron it carries needs no room for a word. */
        private const val PILL_CHEVRON_HALF_WIDTH = 0.75f
        private const val PILL_CHEVRON_HALF_HEIGHT = 0.28f
        private const val PILL_PLAY_OFFSET = 1.45f
        private const val PILL_EDIT_OFFSET = 3.15f
        private const val PILL_MINI_OFFSET = 0.85f
    }
}
