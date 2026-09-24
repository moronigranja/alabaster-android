package io.github.moronigranja.alabasterdawn

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Translates Android controller input into the W3C standard layout the engine reads.
 *
 * PortActivity owns the dispatch: events whose source is GAMEPAD or JOYSTICK are fed here (and
 * consumed, so the WebView never sees them as stray key presses), everything else passes through.
 */
object Gamepad {

    /** Android stick axes are already screen-oriented (positive Y = down), same as the W3C layout. */
    private const val Y_SIGN = 1f

    private const val LEFT_TRIGGER = 6
    private const val RIGHT_TRIGGER = 7

    private val state = GamepadState()

    /** JSON for the shim's `getGamepadJson()`; `""` until a controller has been seen. */
    fun json(): String = state.json()

    /* The on-screen pad's contribution, merged with the physical pad by GamepadState.publish(). The
     * overlay is owned by the View (it knows when to be visible); this is only the plumbing. */

    fun setOverlayEnabled(enabled: Boolean) = state.setOverlayEnabled(enabled)

    fun overlayButton(index: Int, down: Boolean) = state.overlayButton(index, down)

    fun overlayAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float) =
        state.overlayAxes(leftX, leftY, rightX, rightY)

    fun onKey(event: KeyEvent) {
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return
        val button = keyIndex(event.keyCode) ?: return
        if (action == KeyEvent.ACTION_DOWN) state.press(button) else state.release(button)
    }

    fun onMotion(event: MotionEvent) {
        val device = event.device
        state.setAxes(
            axisValue(event, device, MotionEvent.AXIS_X),
            axisValue(event, device, MotionEvent.AXIS_Y) * Y_SIGN,
            /* Right stick: the 8BitDo/Xbox layout reports Z/RZ, the Switch Pro Controller reports
             * RX/RY (measured with `dumpsys input`: ABS_X/ABS_Y/ABS_RX/ABS_RY). */
            axisValue(event, device, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX),
            axisValue(event, device, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY) * Y_SIGN
        )
        state.setTrigger(
            LEFT_TRIGGER, triggerAxis(event, device, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
        )
        state.setTrigger(
            RIGHT_TRIGGER, triggerAxis(event, device, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
        )
        if (hasAxis(device, MotionEvent.AXIS_HAT_X) || hasAxis(device, MotionEvent.AXIS_HAT_Y)) {
            val hatX = if (hasAxis(device, MotionEvent.AXIS_HAT_X)) {
                event.getAxisValue(MotionEvent.AXIS_HAT_X)
            } else {
                0f
            }
            val hatY = if (hasAxis(device, MotionEvent.AXIS_HAT_Y)) {
                event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            } else {
                0f
            }
            state.setHat(hatY < -0.5f, hatY > 0.5f, hatX < -0.5f, hatX > 0.5f)
        }
    }

    /** Value of the first axis the device actually reports, or 0 when it reports none of them. */
    private fun axisValue(event: MotionEvent, device: InputDevice?, vararg candidates: Int): Float {
        for (axis in candidates) {
            if (hasAxis(device, axis)) return event.getAxisValue(axis)
        }
        return 0f
    }

    /** Analog triggers: a pad may report either the trigger axis or the brake/gas pair. */
    private fun triggerAxis(event: MotionEvent, device: InputDevice?, vararg axisIds: Int): Float {
        var value = 0f
        for (axis in axisIds) {
            if (!hasAxis(device, axis)) continue
            val candidate = event.getAxisValue(axis)
            if (candidate > value) value = candidate
        }
        return value.coerceIn(0f, 1f)
    }

    private fun hasAxis(device: InputDevice?, axis: Int): Boolean {
        if (device == null) return false
        for (range in device.motionRanges) if (range.axis == axis) return true
        return false
    }

    /** Android key codes for gamepad buttons -> W3C indices. */
    private fun keyIndex(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> 0
        KeyEvent.KEYCODE_BUTTON_B -> 1
        KeyEvent.KEYCODE_BUTTON_X -> 2
        KeyEvent.KEYCODE_BUTTON_Y -> 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 4
        KeyEvent.KEYCODE_BUTTON_R1 -> 5
        /* Pads with digital triggers (the Switch Pro's ZL/ZR) land on the standard trigger indices. */
        KeyEvent.KEYCODE_BUTTON_L2 -> LEFT_TRIGGER
        KeyEvent.KEYCODE_BUTTON_R2 -> RIGHT_TRIGGER
        KeyEvent.KEYCODE_BUTTON_SELECT -> 8
        KeyEvent.KEYCODE_BUTTON_START -> 9
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 10
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 11
        KeyEvent.KEYCODE_DPAD_UP -> 12
        KeyEvent.KEYCODE_DPAD_DOWN -> 13
        KeyEvent.KEYCODE_DPAD_LEFT -> 14
        KeyEvent.KEYCODE_DPAD_RIGHT -> 15
        KeyEvent.KEYCODE_BUTTON_MODE -> 16
        else -> null
    }
}
