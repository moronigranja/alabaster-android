package dev.moroni.alabasterdawn

import org.json.JSONArray
import org.json.JSONObject

/**
 * Controller state in the W3C "standard gamepad" layout the engine's `GAMEPAD_MAPPING_STANDARD`
 * expects: axes 0..3 = left X/Y, right X/Y (positive = right/down) and 17 buttons in the standard
 * order, D-pad at 12..15.
 *
 * Deliberately free of Android types: [Gamepad] translates `KeyEvent`/`MotionEvent` into these
 * calls, so the layout contract can be unit-tested on the JVM.
 *
 * [json] is called from the JavaScript bridge thread while input events arrive on the main thread.
 * The arrays are only touched by the input thread, which finishes building the payload before
 * publishing it through the volatile field the reader sees.
 */
class GamepadState {

    private val axes = FloatArray(AXES)
    private val keyDown = BooleanArray(BUTTONS)
    private val hatDown = BooleanArray(DPAD_LAST - DPAD_FIRST + 1) // up, down, left, right
    private val trigger = FloatArray(BUTTONS)

    @Volatile
    private var payload: String = ""

    @Volatile
    private var seen = false

    /** `""` until a controller has been seen, so the shim can report no pads at all. */
    fun json(): String = if (seen) payload else ""

    fun press(button: Int) = setKey(button, true)

    fun release(button: Int) = setKey(button, false)

    fun setAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        axes[0] = leftX
        axes[1] = leftY
        axes[2] = rightX
        axes[3] = rightY
        publish()
    }

    fun setTrigger(button: Int, value: Float) {
        trigger[button] = value.coerceIn(0f, 1f)
        publish()
    }

    fun setHat(up: Boolean, down: Boolean, left: Boolean, right: Boolean) {
        hatDown[0] = up
        hatDown[1] = down
        hatDown[2] = left
        hatDown[3] = right
        publish()
    }

    private fun setKey(button: Int, down: Boolean) {
        keyDown[button] = down
        publish()
    }

    private fun publish() {
        val axesJson = JSONArray()
        for (axis in axes) axesJson.put(axis.toDouble())
        val buttonsJson = JSONArray()
        for (button in 0 until BUTTONS) {
            /* A digital press reads as {pressed: true, value: 1}; a trigger keeps its analogue value
             * and counts as pressed past halfway, which is what the engine's deadzones expect. */
            val digital = keyDown[button] || hatPressed(button)
            val analog = trigger[button]
            buttonsJson.put(
                JSONObject()
                    .put("pressed", digital || analog > 0.5f)
                    .put("value", (if (digital) 1f else analog).toDouble())
            )
        }
        payload = JSONObject().put("axes", axesJson).put("buttons", buttonsJson).toString()
        seen = true
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
