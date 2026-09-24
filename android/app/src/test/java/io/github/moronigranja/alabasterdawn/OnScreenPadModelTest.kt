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
            assertInside(model.pill(OnScreenPadModel.PILL_A), width, height, "${width}x$height toggle")
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

    /* --------------------------------------------------------------------------- pills ---- */

    @Test
    fun `the pad pill is hit only inside its pill`() {
        val model = model()
        val pill = model.pill(OnScreenPadModel.PILL_A)
        assertEquals(OnScreenPadModel.PILL_A, model.pillAt(pill.x, pill.y))
        assertEquals(
            OnScreenPadModel.PILL_NONE,
            model.pillAt(pill.x, pill.y + pill.halfHeight * 2f)
        )
        assertEquals(OnScreenPadModel.PILL_NONE, model.pillAt(pill.x, 1080f))
    }

    /* ------------------------------------------------------------------- layout editor ---- */

    @Test
    fun `opening the editor releases a held control`() {
        val model = model()
        val a = model.shape(OnScreenPadModel.FACE_A)
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        assertTrue(model.down(1, a.x, a.y))
        assertTrue(model.down(2, stick.x, stick.y))
        model.move(2, stick.x + stick.radius, stick.y)
        assertTrue(model.buttons[0])
        assertEquals(1.0, model.axes[0].toDouble(), 0.0001)
        model.releaseAll()
        assertFalse(model.buttons[0])
        assertEquals(0.0, model.axes[0].toDouble(), 0.0001)
    }

    @Test
    fun `a default layout reproduces the stock pad`() {
        val fresh = model()
        val model = model()
        model.layout = PadLayout().also {
            it.global = 1.3f
            it.offsetX[OnScreenPadModel.FACE_A] = 2f
            it.scale[OnScreenPadModel.FACE_A] = 1.5f
        }
        model.layout = PadLayout()
        for (control in 0 until OnScreenPadModel.CONTROLS) {
            val a = fresh.shape(control)
            val b = model.shape(control)
            assertEquals("control $control x", a.x, b.x, 0.0001f)
            assertEquals("control $control y", a.y, b.y, 0.0001f)
            assertEquals("control $control radius", a.radius, b.radius, 0.0001f)
            assertEquals("control $control halfWidth", a.halfWidth, b.halfWidth, 0.0001f)
            assertEquals("control $control halfHeight", a.halfHeight, b.halfHeight, 0.0001f)
        }
    }

    @Test
    fun `dragging moves a control by the finger delta and stores the placed offset`() {
        val model = model()
        val u = model.unit
        val start = model.shape(OnScreenPadModel.LEFT_STICK)
        val startX = start.x
        val startY = start.y
        model.beginDrag(OnScreenPadModel.LEFT_STICK, startX, startY)
        model.dragTo(OnScreenPadModel.LEFT_STICK, startX + 2f * u, startY - 1f * u)
        val moved = model.shape(OnScreenPadModel.LEFT_STICK)
        assertEquals(startX + 2f * u, moved.x, 0.01f)
        assertEquals(startY - 1f * u, moved.y, 0.01f)
        assertEquals(2f, model.layout.offsetX[OnScreenPadModel.LEFT_STICK], 0.01f)
        assertEquals(-1f, model.layout.offsetY[OnScreenPadModel.LEFT_STICK], 0.01f)
        /* The stored offset reproduces the placed shape in a fresh model. */
        val fresh = OnScreenPadModel().apply {
            resize(1920f, 1080f)
            layout = model.layout.snapshot()
        }
        assertEquals(moved.x, fresh.shape(OnScreenPadModel.LEFT_STICK).x, 0.001f)
        assertEquals(moved.y, fresh.shape(OnScreenPadModel.LEFT_STICK).y, 0.001f)
    }

    @Test
    fun `dragging past an edge stops with the control inside the viewport`() {
        val model = model()
        val u = model.unit
        val stick = model.shape(OnScreenPadModel.LEFT_STICK)
        val radius = stick.radius
        model.beginDrag(OnScreenPadModel.LEFT_STICK, stick.x, stick.y)
        model.dragTo(OnScreenPadModel.LEFT_STICK, stick.x + 20f * u, stick.y + 20f * u)
        val moved = model.shape(OnScreenPadModel.LEFT_STICK)
        assertEquals(1920f - radius - 0.2f * u, moved.x, 0.01f)
        assertEquals(1080f - radius - 0.2f * u, moved.y, 0.01f)
    }

    @Test
    fun `scaling the handle doubles the radius and clamps to the limits`() {
        val model = model()
        val up = model.shape(OnScreenPadModel.DPAD_UP)
        val grab = up.radius
        model.beginScale(OnScreenPadModel.DPAD_UP, up.x + grab, up.y)
        model.scaleTo(OnScreenPadModel.DPAD_UP, up.x + 2f * grab, up.y)
        assertEquals(2f * grab, model.shape(OnScreenPadModel.DPAD_UP).radius, 0.01f)
        model.scaleTo(OnScreenPadModel.DPAD_UP, model.shape(OnScreenPadModel.DPAD_UP).x + 100f * model.unit, model.shape(OnScreenPadModel.DPAD_UP).y)
        assertEquals(PadLayout.MAX_SCALE, model.layout.scale[OnScreenPadModel.DPAD_UP], 0.01f)
        model.scaleTo(OnScreenPadModel.DPAD_UP, model.shape(OnScreenPadModel.DPAD_UP).x, model.shape(OnScreenPadModel.DPAD_UP).y)
        assertEquals(PadLayout.MIN_SCALE, model.layout.scale[OnScreenPadModel.DPAD_UP], 0.01f)
    }

    @Test
    fun `growing the pad scales every control but not the pill row`() {
        val model = model()
        val u = model.unit
        val radius = model.shape(OnScreenPadModel.LEFT_STICK).radius
        val pill = model.pill(OnScreenPadModel.PILL_A)
        val pillX = pill.x
        val pillY = pill.y
        val pillWidth = pill.halfWidth
        model.addGlobal(0.5f)
        assertEquals(1.5f, model.layout.global, 0.001f)
        assertEquals(1.5f * radius, model.shape(OnScreenPadModel.LEFT_STICK).radius, 0.01f)
        assertEquals(pillX, model.pill(OnScreenPadModel.PILL_A).x, 0.001f)
        assertEquals(pillY, model.pill(OnScreenPadModel.PILL_A).y, 0.001f)
        assertEquals(pillWidth, model.pill(OnScreenPadModel.PILL_A).halfWidth, 0.001f)
        model.addGlobal(99f)
        assertEquals(PadLayout.MAX_GLOBAL, model.layout.global, 0.001f)
        assertEquals(u, model.unit, 0.0001f)
    }

    @Test
    fun `the handle sits at the selected control's corner and follows the selection`() {
        val model = model()
        assertFalse("nothing selected", model.handleHit(0f, 0f))
        val up = model.shape(OnScreenPadModel.DPAD_UP)
        model.beginDrag(OnScreenPadModel.DPAD_UP, up.x, up.y)
        val handle = model.handle
        assertEquals(up.x + up.radius, handle.x, 0.001f)
        assertEquals(up.y + up.radius, handle.y, 0.001f)
        assertTrue(model.handleHit(handle.x, handle.y))
        assertFalse(model.handleHit(handle.x + 2f * model.unit, handle.y))
        val a = model.shape(OnScreenPadModel.FACE_A)
        model.beginDrag(OnScreenPadModel.FACE_A, a.x, a.y)
        assertEquals(a.x + a.radius, model.handle.x, 0.001f)
        assertFalse(model.handleHit(up.x + up.radius, up.y + up.radius))
    }

    @Test
    fun `the pill row exposes the four slots in edit mode and two in play mode`() {
        val model = model()
        val u = model.unit
        val y = 0.8f * u + 0.35f * u
        assertEquals(960f - 1.45f * u, model.pill(OnScreenPadModel.PILL_A).x, 0.01f)
        assertEquals(960f + 1.45f * u, model.pill(OnScreenPadModel.PILL_B).x, 0.01f)
        assertEquals(y, model.pill(OnScreenPadModel.PILL_A).y, 0.01f)
        assertEquals(
            OnScreenPadModel.PILL_A,
            model.pillAt(model.pill(OnScreenPadModel.PILL_A).x, model.pill(OnScreenPadModel.PILL_A).y)
        )
        assertEquals(
            OnScreenPadModel.PILL_B,
            model.pillAt(model.pill(OnScreenPadModel.PILL_B).x, model.pill(OnScreenPadModel.PILL_B).y)
        )
        assertEquals(OnScreenPadModel.PILL_NONE, model.pillAt(960f, y))
        assertEquals(OnScreenPadModel.PILL_NONE, model.pillAt(960f, 300f))
        model.setEditing(true)
        assertEquals(960f - 3.15f * u, model.pill(OnScreenPadModel.PILL_A).x, 0.01f)
        assertEquals(960f - 0.85f * u, model.pill(OnScreenPadModel.PILL_MINUS).x, 0.01f)
        assertEquals(960f + 0.85f * u, model.pill(OnScreenPadModel.PILL_PLUS).x, 0.01f)
        assertEquals(960f + 3.15f * u, model.pill(OnScreenPadModel.PILL_B).x, 0.01f)
        assertEquals(OnScreenPadModel.PILL_MINUS, model.pillAt(960f - 0.85f * u, y))
        assertEquals(OnScreenPadModel.PILL_PLUS, model.pillAt(960f + 0.85f * u, y))
    }

    @Test
    fun `a custom layout stays inside a narrow window`() {
        val model = OnScreenPadModel().apply { resize(800f, 600f) }
        model.layout = PadLayout().also {
            it.global = 1.4f
            it.offsetX[OnScreenPadModel.FACE_A] = 5f
            it.offsetY[OnScreenPadModel.FACE_A] = 5f
            it.scale[OnScreenPadModel.LEFT_STICK] = 1.8f
        }
        for (control in 0 until OnScreenPadModel.CONTROLS) {
            assertInside(model.shape(control), 800f, 600f, "800x600 control $control")
        }
    }
}
