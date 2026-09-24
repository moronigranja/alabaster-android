package io.github.moronigranja.alabasterdawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-screen pad layout's wire format and precedence, which decide what survives a restart and
 * whether a hand-edited file is read or ignored.
 */
class PadLayoutTest {

    @Test
    fun `a default layout is default and serialises to version only`() {
        val layout = PadLayout()
        assertTrue(layout.isDefault())
        assertEquals("""{"version":1}""", layout.toJson())
    }

    @Test
    fun `a moved and scaled control round-trips through the JSON`() {
        val layout = PadLayout().apply {
            global = 1.1f
            offsetX[OnScreenPadModel.LEFT_STICK] = 0.4f
            offsetY[OnScreenPadModel.LEFT_STICK] = -0.3f
            scale[OnScreenPadModel.LEFT_STICK] = 1.2f
            offsetY[OnScreenPadModel.DPAD_UP] = -0.5f
        }
        val parsed = PadLayout.fromJson(layout.toJson())
        assertNotNull(parsed)
        parsed!!
        assertEquals(1.1f, parsed.global, 0.0001f)
        for (control in 0 until OnScreenPadModel.CONTROLS) {
            assertEquals("offsetX $control", layout.offsetX[control], parsed.offsetX[control], 0.0001f)
            assertEquals("offsetY $control", layout.offsetY[control], parsed.offsetY[control], 0.0001f)
            assertEquals("scale $control", layout.scale[control], parsed.scale[control], 0.0001f)
        }
        assertEquals(1f, parsed.scale[OnScreenPadModel.DPAD_UP], 0.0001f)
    }

    @Test
    fun `setDefault restores every entry and a snapshot is independent`() {
        val layout = PadLayout().apply {
            global = 1.3f
            offsetX[OnScreenPadModel.FACE_A] = 1f
            scale[OnScreenPadModel.FACE_A] = 1.4f
        }
        val snapshot = layout.snapshot()
        layout.setDefault()
        assertTrue(layout.isDefault())
        assertEquals(1.3f, snapshot.global, 0.0001f)
        assertEquals(1f, snapshot.offsetX[OnScreenPadModel.FACE_A], 0.0001f)
        layout.copyFrom(snapshot)
        assertEquals(1.3f, layout.global, 0.0001f)
    }

    @Test
    fun `unknown names, unknown fields and a missing controls object are ignored`() {
        val layout = PadLayout.fromJson(
            """{"version":1,"nope":3,"controls":{"bogus":{"dx":9},"face_a":{"dx":0.5,"zzz":1}}}"""
        )
        assertNotNull(layout)
        assertEquals(0.5f, layout!!.offsetX[OnScreenPadModel.FACE_A], 0.0001f)
        assertEquals(0f, layout.offsetX[OnScreenPadModel.FACE_B], 0.0001f)

        val bare = PadLayout.fromJson("""{"version":1}""")
        assertNotNull(bare)
        assertTrue(bare!!.isDefault())

        val noControls = PadLayout.fromJson("""{"version":1,"global":2,"controls":5}""")
        assertNotNull(noControls)
        assertEquals(2f, noControls!!.global, 0.0001f)
    }

    @Test
    fun `a wrong version, malformed JSON or a non-object root yields null`() {
        assertNull(PadLayout.fromJson(null))
        assertNull(PadLayout.fromJson("{"))
        assertNull(PadLayout.fromJson("[1,2]"))
        assertNull(PadLayout.fromJson("""{"version":2}"""))
        assertNull(PadLayout.fromJson("""{"global":1.4}"""))
    }

    @Test
    fun `of prefers the file then the prefs then the default`() {
        val fromFile = PadLayout.of("""{"version":1,"global":1.4}""", """{"version":1,"global":1.1}""")
        assertEquals(1.4f, fromFile.global, 0.0001f)

        val fromPref = PadLayout.of("{", """{"version":1,"global":1.1}""")
        assertEquals(1.1f, fromPref.global, 0.0001f)

        val fromNeither = PadLayout.of(null, "not json")
        assertTrue(fromNeither.isDefault())
    }
}
