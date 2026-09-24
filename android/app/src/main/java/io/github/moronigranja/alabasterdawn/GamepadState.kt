package io.github.moronigranja.alabasterdawn

import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Controller state in the W3C "standard gamepad" layout the engine's `GAMEPAD_MAPPING_STANDARD`
 * expects: axes 0..3 = left X/Y, right X/Y (positive = right/down) and 17 buttons in the standard
 * order, D-pad at 12..15.
 *
 * Two sources feed it — the physical pad ([Gamepad] translates `KeyEvent`/`MotionEvent`) and the
 * on-screen pad ([overlayButton]/[overlayAxes], driven by [OnScreenPadView]) — and [publish] merges
 * them into one payload, so the engine always sees a single standard pad.
 *
 * Deliberately free of Android types, so the layout contract can be unit-tested on the JVM.
 *
 * [json] is called from the JavaScript bridge thread while input events arrive on the main thread.
 * The arrays are only touched by the input thread, which finishes building the payload before
 * publishing it through the volatile field the reader sees. The on-screen pad's setters run on the
 * main thread too, from the View's `onTouchEvent`, so that contract is unchanged.
 */
class GamepadState {

    private val axes = FloatArray(AXES)
    private val keyDown = BooleanArray(BUTTONS)
    private val hatDown = BooleanArray(DPAD_LAST - DPAD_FIRST + 1) // up, down, left, right
    private val trigger = FloatArray(BUTTONS)

    private val overlayKeyDown = BooleanArray(BUTTONS)
    private val overlayAxis = FloatArray(AXES)
    private var overlayEnabled = false

    @Volatile
    private var payload: String = ""

    @Volatile
    private var seen = false

    /**
     * `""` until a controller has been seen, so the shim can report no pads at all. An enabled
     * overlay is a pad in its own right: it must publish (all-neutral until a control is touched)
     * so the engine switches to its pad input device. Only hardware marks a controller as seen —
     * the overlay is a second source, not a second pad.
     */
    fun json(): String = if (seen || overlayEnabled) payload else ""

    fun press(button: Int) = setKey(button, true)

    fun release(button: Int) = setKey(button, false)

    fun setAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        axes[0] = leftX
        axes[1] = leftY
        axes[2] = rightX
        axes[3] = rightY
        seen = true
        publish()
    }

    fun setTrigger(button: Int, value: Float) {
        trigger[button] = value.coerceIn(0f, 1f)
        seen = true
        publish()
    }

    fun setHat(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        hatDown[0] = up
        hatDown[1] = down
        hatDown[2] = left
        hatDown[3] = right
        seen = true
        publish()
    }

    private fun setKey(button: Int, down: Boolean) {
        keyDown[button] = down
        seen = true
        publish()
    }

    /**
     * The on-screen pad's switch. Disabling clears its state before publishing, so a finger held
     * down when the pad disappears cannot leave a stuck button or a displaced stick behind.
     */
    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        if (!enabled) {
            overlayKeyDown.fill(false)
            overlayAxis.fill(0f)
        }
        publish()
    }

    /** A stray overlay event after the toggle cannot resurrect state: ignored while disabled. */
    fun overlayButton(button: Int, down: Boolean) {
        if (!overlayEnabled || button < 0 || button >= BUTTONS) return
        overlayKeyDown[button] = down
        publish()
    }

    fun overlayAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        if (!overlayEnabled) return
        overlayAxis[0] = leftX.coerceIn(-1f, 1f)
        overlayAxis[1] = leftY.coerceIn(-1f, 1f)
        overlayAxis[2] = rightX.coerceIn(-1f, 1f)
        overlayAxis[3] = rightY.coerceIn(-1f, 1f)
        publish()
    }

    private fun publish() {
        val axesJson = JSONArray()
        for (axis in 0 until AXES) {
            /* Whichever source is deflected further wins: a neutral overlay must not mask a held
             * physical stick, and a centred physical stick must not cancel an overlay one. */
            val physical = axes[axis]
            val overlay = overlayAxis[axis]
            axesJson.put((if (abs(overlay) > abs(physical)) overlay else physical).toDouble())
        }
        val buttonsJson = JSONArray()
        for (button in 0 until BUTTONS) {
            /* A digital press reads as {pressed: true, value: 1}; a trigger keeps its analogue value
             * and counts as pressed past halfway, which is what the engine's deadzones expect. */
            val digital = keyDown[button] || hatPressed(button) || overlayKeyDown[button]
            val analog = trigger[button]
            buttonsJson.put(
                JSONObject()
                    .put("pressed", digital || analog > 0.5f)
                    .put("value", (if (digital) 1f else analog).toDouble())
            )
        }
        payload = JSONObject().put("axes", axesJson).put("buttons", buttonsJson).toString()
    }

    /** Buttons 12..15 are the D-pad, fed both by key events and by the hat axis. */
    private fun hatPressed(button: Int): Boolean {
        if (button < DPAD_FIRST || button > DPAD_LAST) return false
        return hatDown[button - DPAD_FIRST]
    }

    companion object {
        const val BUTTONS = 17
        const val AXES = 4
        const val DPAD_FIRST = 12
        const val DPAD_LAST = 15
    }
}
