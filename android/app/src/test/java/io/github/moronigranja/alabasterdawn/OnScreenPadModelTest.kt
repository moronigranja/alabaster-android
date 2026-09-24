package io.github.moronigranja.alabasterdawn

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-screen pad's layout contract and hit rules, which are the parts that decide whether a
 * finger reaches the control it aimed at.
 *
 * The overlap test is the guard that matters: the whole pad is a function of one touch unit, so a
 * single constant moved one row too far silently makes two controls fight over a pointer.
 */
class OnScreenPadModelTest {

    private fun model(width: Float = 1920f, height: Float = 1080f) =
        OnScreenPadModel().apply { resize(width, height) }

    /* ------------------------------------------------------------------------- layout ---- */

    @Test
    fun `every control fits on screen and no two controls overlap`() {
        for ((width, height) in listOf(1920f to 1080f, 2520f to 1080f, 800f to 600f)) {
            val model = model(width, height)
            for (control in 0 until OnScreenPadModel.CONTROLS) {
                assertInside(model.shape(control), width, height, "${width}x$height control $control")
            }
            assertInside(model.toggle, width, height, "${width}x$height toggle")
            for (a in 0 until OnScreenPadModel.CONTROLS) {
                for (b in a + 1 until OnScreenPadModel.CONTROLS) {
                    assertFalse(
                        "controls $a and $b overlap at ${width}x$height",
                        overlaps(model.shape(a), model.shape(b))
                    )
                }
            }
        }
    }

    @Test
    fun `the left stick is tangent to the screen edge, so its ring is fully reachable`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        assertTrue("left edge", stick.x - stick.radius > 0f)
        assertTrue("bottom edge", stick.y + stick.radius < 1080f)
    }

    private fun assertInside(shape: OnScreenPadModel.Shape, width: Float, height: Float, what: String) {
        val halfWidth = if (shape.circular) shape.radius else shape.halfWidth
        val halfHeight = if (shape.circular) shape.radius else shape.halfHeight
        assertTrue("$what left", shape.x - halfWidth >= 0f)
        assertTrue("$what top", shape.y - halfHeight >= 0f)
        assertTrue("$what right", shape.x + halfWidth <= width)
        assertTrue("$what bottom", shape.y + halfHeight <= height)
    }

    private fun overlaps(a: OnScreenPadModel.Shape, b: OnScreenPadModel.Shape): Boolean = when {
        a.circular && b.circular -> hypot(a.x - b.x, a.y - b.y) < a.radius + b.radius
        !a.circular && !b.circular ->
            abs(a.x - b.x) < a.halfWidth + b.halfWidth && abs(a.y - b.y) < a.halfHeight + b.halfHeight
        else -> {
            val circle = if (a.circular) a else b
            val pill = if (a.circular) b else a
            val nearestX = circle.x.coerceIn(pill.x - pill.halfWidth, pill.x + pill.halfWidth)
            val nearestY = circle.y.coerceIn(pill.y - pill.halfHeight, pill.y + pill.halfHeight)
            hypot(circle.x - nearestX, circle.y - nearestY) < circle.radius
        }
    }

    /* -------------------------------------------------------------------------- sticks ---- */

    @Test
    fun `the stick centre is neutral`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        assertTrue(model.down(1, stick.x, stick.y))
        for (axis in 0 until GamepadState.AXES) assertEquals(0.0, model.axes[axis].toDouble(), 0.0001)
    }

    @Test
    fun `a full deflection reads one on its axis and zero on the other`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        model.down(1, stick.x, stick.y)
        model.move(1, stick.x + stick.radius, stick.y)
        assertEquals(1.0, model.axes[0].toDouble(), 0.0001)
        assertEquals(0.0, model.axes[1].toDouble(), 0.0001)
        model.move(1, stick.x, stick.y - stick.radius)
        assertEquals(0.0, model.axes[0].toDouble(), 0.0001)
        assertEquals(-1.0, model.axes[1].toDouble(), 0.0001)
    }

    @Test
    fun `a diagonal deflection is normalised, not saturated`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        val d = stick.radius / 1.41421356f
        model.down(1, stick.x + d, stick.y + d)
        assertEquals(0.7071, model.axes[0].toDouble(), 0.001)
        assertEquals(0.7071, model.axes[1].toDouble(), 0.001)
    }

    @Test
    fun `a deflection inside the dead zone is neutral`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        model.down(1, stick.x, stick.y)
        model.move(1, stick.x + stick.radius * 0.1f, stick.y)
        assertEquals(0.0, model.axes[0].toDouble(), 0.0001)
    }

    @Test
    fun `a thumb past the ring keeps tracking, clamped to one`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        model.down(1, stick.x, stick.y)
        model.move(1, stick.x + stick.radius * 10f, stick.y)
        assertEquals(1.0, model.axes[0].toDouble(), 0.0001)
        assertEquals(0.0, model.axes[1].toDouble(), 0.0001)
    }

    @Test
    fun `lifting the thumb centres the stick`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        model.down(1, stick.x, stick.y)
        model.move(1, stick.x + stick.radius, stick.y)
        assertTrue(model.up(1))
        assertEquals(0.0, model.axes[0].toDouble(), 0.0001)
    }

    @Test
    fun `the right stick drives its own axes`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.RIGHT_STICK)
        model.down(1, stick.x, stick.y)
        model.move(1, stick.x, stick.y + stick.radius)
        assertEquals(0.0, model.axes[0].toDouble(), 0.0001)
        assertEquals(1.0, model.axes[3].toDouble(), 0.0001)
    }

    /* ------------------------------------------------------------------------- buttons ---- */

    @Test
    fun `every control drives exactly its W3C index`() {
        val expected = mapOf(
            OnScreenPadModel.DPAD_UP to GamepadState.DPAD_FIRST,
            OnScreenPadModel.DPAD_DOWN to GamepadState.DPAD_FIRST + 1,
            OnScreenPadModel.DPAD_LEFT to GamepadState.DPAD_FIRST + 2,
            OnScreenPadModel.DPAD_RIGHT to GamepadState.DPAD_FIRST + 3,
            OnScreenPadModel.FACE_A to 0,
            OnScreenPadModel.FACE_B to 1,
            OnScreenPadModel.FACE_X to 2,
            OnScreenPadModel.FACE_Y to 3,
            OnScreenPadModel.L1 to 4,
            OnScreenPadModel.L2 to 6,
            OnScreenPadModel.R1 to 5,
            OnScreenPadModel.R2 to 7,
            OnScreenPadModel.SELECT to 8,
            OnScreenPadModel.START to 9,
            OnScreenPadModel.HOME to 16,
        )
        for ((control, index) in expected) {
            val model = model()
            val shape = model.shape(control)
            assertTrue("control $control claims its centre", model.down(1, shape.x, shape.y))
            for (button in 0 until GamepadState.BUTTONS) {
                assertEquals(
                    "control $control -> button $button",
                    button == index,
                    model.buttons[button]
                )
            }
        }
    }

    @Test
    fun `a pressed button is released by lifting and by leaving its hit area`() {
        val model = model()
        val a = model.shape(OnScreenPadModel.FACE_A)
        assertTrue(model.down(1, a.x, a.y))
        assertTrue(model.buttons[0])
        model.move(1, a.x, a.y - a.radius * 3f)
        assertFalse("a drag off the key must not leave it held", model.buttons[0])
        model.move(1, a.x, a.y)
        assertTrue("coming back onto the key presses it again", model.buttons[0])
        assertTrue(model.up(1))
        assertFalse(model.buttons[0])
    }

    @Test
    fun `sliding from the stick onto a button does not press that button`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        val a = model.shape(OnScreenPadModel.FACE_A)
        assertTrue(model.down(1, stick.x, stick.y))
        assertTrue(model.move(1, a.x, a.y))
        assertFalse(model.buttons[0])
    }

    @Test
    fun `a control holds one pointer`() {
        val model = model()
        val a = model.shape(OnScreenPadModel.FACE_A)
        assertTrue(model.down(1, a.x, a.y))
        assertFalse(model.down(2, a.x, a.y))
        assertTrue(model.buttons[0])
        assertTrue(model.up(1))
        assertTrue("the freed control can be claimed again", model.down(3, a.x, a.y))
    }

    @Test
    fun `two pointers are independent`() {
        val model = model()
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        val a = model.shape(OnScreenPadModel.FACE_A)
        assertTrue(model.down(1, stick.x, stick.y))
        model.move(1, stick.x + stick.radius, stick.y)
        assertTrue(model.down(2, a.x, a.y))
        assertEquals(1.0, model.axes[0].toDouble(), 0.0001)
        assertTrue(model.buttons[0])
        assertTrue(model.up(1))
        assertEquals(0.0, model.axes[0].toDouble(), 0.0001)
        assertTrue("the other finger keeps its button", model.buttons[0])
    }

    @Test
    fun `stray pointers change nothing`() {
        val model = model()
        assertFalse(model.move(9, 10f, 10f))
        assertFalse(model.up(9))
        assertFalse(model.down(9, -5f, -5f))
        for (button in 0 until GamepadState.BUTTONS) assertFalse(model.buttons[button])
    }

    /* -------------------------------------------------------------------------- toggle ---- */

    @Test
    fun `the toggle is hit only inside its pill`() {
        val model = model()
        assertTrue(model.toggleHit(model.toggle.x, model.toggle.y))
        assertFalse(model.toggleHit(model.toggle.x, model.toggle.y + model.toggle.halfHeight * 2f))
        assertFalse(model.toggleHit(model.toggle.x, 1080f))
    }
}
