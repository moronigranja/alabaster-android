package io.github.moronigranja.alabasterdawn

import org.json.JSONException
import org.json.JSONObject

/**
 * The user's override of the on-screen pad's stock geometry, as a delta on top of it: one global
 * multiplier for the touch unit and, per control, an offset in raw `u` units plus a scale on the
 * control's size. A default layout is exactly the stock pad.
 *
 * Deliberately free of Android types (the wire format uses `org.json`, the same library
 * [GamepadState] serialises with), so the format and the precedence rule are unit-testable on the
 * JVM. Offsets are stored in raw `u` units and scales are dimensionless, so one layout works for
 * every window size and orientation.
 */
class PadLayout {

    /** Multiplies the touch unit `u`: the whole pad grows or shrinks about its own layout. */
    var global = 1f

    /** Per-control offset from the stock position, in raw `u` units. */
    val offsetX = FloatArray(OnScreenPadModel.CONTROLS)
    val offsetY = FloatArray(OnScreenPadModel.CONTROLS)

    /** Per-control multiplier on the control's extent (radius, or pill half extents). */
    val scale = FloatArray(OnScreenPadModel.CONTROLS) { 1f }

    fun isDefault(): Boolean {
        if (global != 1f) return false
        for (control in 0 until OnScreenPadModel.CONTROLS) {
            if (offsetX[control] != 0f || offsetY[control] != 0f || scale[control] != 1f) return false
        }
        return true
    }

    fun setDefault() {
        global = 1f
        offsetX.fill(0f)
        offsetY.fill(0f)
        scale.fill(1f)
    }

    /** A deep copy (fresh arrays), for the editor's undo snapshot. */
    fun snapshot(): PadLayout = PadLayout().also { it.copyFrom(this) }

    /** In-place copy from [other], so the View can restore a snapshot without reallocating. */
    fun copyFrom(other: PadLayout) {
        global = other.global
        other.offsetX.copyInto(offsetX)
        other.offsetY.copyInto(offsetY)
        other.scale.copyInto(scale)
    }

    /**
     * Only non-default entries are emitted: `global` when it is not 1.0, then a `controls` object
     * holding the controls with a non-zero offset or a non-unit scale. A default layout is exactly
     * `{"version":1}`.
     */
    fun toJson(): String {
        val root = JSONObject()
        root.put("version", VERSION)
        if (global != 1f) root.put("global", global.toDouble())
        val controls = JSONObject()
        for (control in 0 until OnScreenPadModel.CONTROLS) {
            val dx = offsetX[control]
            val dy = offsetY[control]
            val s = scale[control]
            if (dx == 0f && dy == 0f && s == 1f) continue
            val entry = JSONObject()
            if (dx != 0f) entry.put("dx", dx.toDouble())
            if (dy != 0f) entry.put("dy", dy.toDouble())
            if (s != 1f) entry.put("scale", s.toDouble())
            controls.put(nameOf(control), entry)
        }
        if (controls.length() > 0) root.put("controls", controls)
        return root.toString()
    }

    companion object {
        const val VERSION = 1

        /** File kept at the root of the picked saves folder, so the layout travels with the saves. */
        const val FILE = "pad-layout.json"

        const val MIN_GLOBAL = 0.5f
        const val MAX_GLOBAL = 1.5f
        const val GLOBAL_STEP = 0.1f
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.5f

        /** Control names, indexed by control id ([OnScreenPadModel.LEFT_STICK] … `HOME`). */
        private val NAMES = arrayOf(
            "left_stick", "dpad_up", "dpad_down", "dpad_left", "dpad_right", "right_stick",
            "face_a", "face_b", "face_x", "face_y", "l1", "l2", "r1", "r2", "select", "start", "home",
        )

        fun nameOf(control: Int): String = NAMES.getOrElse(control) { "" }

        /** -1 when [name] is not one of the known controls (a hand-edited file may carry anything). */
        fun controlOf(name: String): Int = NAMES.indexOf(name)

        /**
         * Parses [text]; null when it cannot be trusted — malformed JSON, a non-object root, or a
         * version other than [VERSION] (a newer format must not be misread as this one). Unknown
         * control names, unknown fields and missing fields are ignored, never fatal.
         */
        fun fromJson(text: String?): PadLayout? {
            if (text == null) return null
            val root = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return null
            }
            if (root.optInt("version", -1) != VERSION) return null
            val layout = PadLayout()
            layout.global = root.optDouble("global", 1.0).toFloat()
            val controls = root.optJSONObject("controls") ?: return layout
            val names = controls.keys()
            while (names.hasNext()) {
                val name = names.next()
                val control = controlOf(name)
                if (control == -1) continue
                val entry = controls.optJSONObject(name) ?: continue
                layout.offsetX[control] = entry.optDouble("dx", 0.0).toFloat()
                layout.offsetY[control] = entry.optDouble("dy", 0.0).toFloat()
                layout.scale[control] = entry.optDouble("scale", 1.0).toFloat()
            }
            return layout
        }

        /** The saves-folder file wins over the app prefs; a missing/invalid copy falls through. */
        fun of(fileText: String?, prefText: String?): PadLayout =
            fromJson(fileText) ?: fromJson(prefText) ?: PadLayout()
    }
}
