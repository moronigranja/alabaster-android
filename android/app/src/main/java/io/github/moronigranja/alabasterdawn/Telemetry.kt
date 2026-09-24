package io.github.moronigranja.alabasterdawn

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * The phone's own numbers for the shim's readout.
 *
 * The sticky `ACTION_BATTERY_CHANGED` intent is the only permission-free source of a temperature: it
 * carries `EXTRA_TEMPERATURE` (tenths of °C) and `EXTRA_LEVEL`/`EXTRA_SCALE`. The SoC zones the
 * system caches (AP/PA) are not readable by an unprivileged app, so the throttling signal comes from
 * `PowerManager.getCurrentThermalStatus()` instead. Sampled at most every [SAMPLE_MS] and only while
 * the readout is on (the shim asks when it paints), so a backgrounded game costs nothing.
 */
class Telemetry(private val context: Context) {

    private var sampledAt = 0L
    private var cached = EMPTY

    /** `{"level":65,"temp":388,"thermal":"critical"}`; a field is null when it is unavailable. */
    fun json(): String {
        val now = SystemClock.elapsedRealtime()
        if (now - sampledAt >= SAMPLE_MS) {
            sampledAt = now
            cached = sample()
        }
        return cached
    }

    private fun sample(): String {
        var level: Int? = null
        var temp: Int? = null
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                if (raw >= 0 && scale > 0) level = raw * 100 / scale
                val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tenths != Int.MIN_VALUE) temp = tenths
            }
        } catch (e: Exception) {
            /* Both stay null; the readout still shows the frame rate and the resolution. */
            Log.w(TAG, "battery state unreadable", e)
        }
        return "{\"level\":" + (level?.toString() ?: "null") +
            ",\"temp\":" + (temp?.toString() ?: "null") +
            ",\"thermal\":" + thermal() + "}"
    }

    /** Null below API 29, where the API does not exist, and for a status this build does not know. */
    private fun thermal(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "null"
        val name = when (context.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> return "null"
        }
        return "\"" + name + "\""
    }

    companion object {
        private const val TAG = "AdaPort"
        private const val SAMPLE_MS = 5_000L
        private const val EMPTY = "{\"level\":null,\"temp\":null,\"thermal\":null}"
    }
}
