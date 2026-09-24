package dev.moroni.alabasterdawn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout contract the engine reads (its `GAMEPAD_MAPPING_STANDARD` table): axes 0..3 are the
 * two sticks with positive Y down, buttons are the 17 W3C indices with the D-pad at 12..15.
 *
 * A real controller crashed the app here (a D-pad index read out of bounds in the payload builder),
 * so the whole index range is exercised, one button at a time.
 */
class GamepadStateTest {

    private fun state() = GamepadState()

    private fun buttonsOf(json: String) = JSONObject(json).getJSONArray("buttons")

    private fun axesOf(json: String) = JSONObject(json).getJSONArray("axes")

    @Test
    fun `no controller seen yet means no pads`() {
        assertEquals("", state().json())
    }

    @Test
    fun `a pressed face button is pressed with value one`() {
        val state = state()
        state.press(0)
        val first = buttonsOf(state.json()).getJSONObject(0)
        assertTrue(first.getBoolean("pressed"))
        assertEquals(1.0, first.getDouble("value"), 0.0001)
    }

    @Test
    fun `releasing clears the press`() {
        val state = state()
        state.press(0)
        state.release(0)
        assertFalse(buttonsOf(state.json()).getJSONObject(0).getBoolean("pressed"))
    }

    @Test
    fun `every button index publishes, including the d-pad and mode buttons`() {
        val state = state()
        for (button in 0 until GamepadState.BUTTONS) {
            state.press(button)
            assertTrue("button $button", buttonsOf(state.json()).getJSONObject(button).getBoolean("pressed"))
            state.release(button)
            assertFalse("button $button", buttonsOf(state.json()).getJSONObject(button).getBoolean("pressed"))
        }
    }

    @Test
    fun `the hat drives the d-pad buttons`() {
        val state = state()
        state.setHat(up = true, down = false, left = true, right = false)
        val buttons = buttonsOf(state.json())
        assertTrue(buttons.getJSONObject(12).getBoolean("pressed")) // up
        assertFalse(buttons.getJSONObject(13).getBoolean("pressed")) // down
        assertTrue(buttons.getJSONObject(14).getBoolean("pressed")) // left
        assertFalse(buttons.getJSONObject(15).getBoolean("pressed")) // right
    }

    @Test
    fun `a trigger keeps its analogue value and presses past halfway`() {
        val state = state()
        state.setTrigger(6, 0.4f)
        val half = buttonsOf(state.json()).getJSONObject(6)
        assertFalse(half.getBoolean("pressed"))
        assertEquals(0.4, half.getDouble("value"), 0.0001)
        state.setTrigger(6, 1.4f)
        val full = buttonsOf(state.json()).getJSONObject(6)
        assertTrue(full.getBoolean("pressed"))
        assertEquals(1.0, full.getDouble("value"), 0.0001)
    }

    @Test
    fun `axes are published in standard order`() {
        val state = state()
        state.setAxes(leftX = 0.5f, leftY = -0.5f, rightX = 0.25f, rightY = -0.25f)
        val axes = axesOf(state.json())
        assertEquals(4, axes.length())
        assertEquals(0.5, axes.getDouble(0), 0.0001)
        assertEquals(-0.5, axes.getDouble(1), 0.0001)
        assertEquals(0.25, axes.getDouble(2), 0.0001)
        assertEquals(-0.25, axes.getDouble(3), 0.0001)
    }
}
