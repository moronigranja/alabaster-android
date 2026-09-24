package io.github.moronigranja.alabasterdawn

import android.content.SharedPreferences
import android.util.Log

/**
 * The two places a [PadLayout] lives: the app prefs (always) and, when the user has picked a saves
 * folder, `pad-layout.json` at its root. The file travels with the saves folder and wins on load.
 *
 * Called from the main thread on commit only — a few hundred bytes per commit, the same order of
 * cost as the prefs write it pairs with.
 */
class PadLayoutStore(private val prefs: SharedPreferences, private val saves: SaveStore) {

    fun load(): PadLayout {
        val fileText = if (saves.exists(PadLayout.FILE)) saves.read(PadLayout.FILE) else null
        val prefText = prefs.getString(PREF_KEY, null)
        val layout = PadLayout.of(fileText, prefText)
        val source = when {
            fileText != null && PadLayout.fromJson(fileText) != null -> PadLayout.FILE
            prefText != null && PadLayout.fromJson(prefText) != null -> PREF_KEY
            else -> "default"
        }
        Log.i(TAG, "pad layout from $source (${if (layout.isDefault()) "default" else "custom"})")
        return layout
    }

    /** Always writes the prefs copy; the saves-folder file too when there is one. A default layout is
     * written rather than deleted — an explicit "default" file is clearer than a missing one. */
    fun save(layout: PadLayout) {
        val text = layout.toJson()
        prefs.edit().putString(PREF_KEY, text).apply()
        if (!saves.write(PadLayout.FILE, text)) {
            Log.w(TAG, "could not write ${PadLayout.FILE} to the saves folder")
        }
    }

    companion object {
        const val PREF_KEY = "pad_layout_json"
        private const val TAG = "AdaPort"
    }
}
