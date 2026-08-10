package com.ridesharefarecalc.overlay

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Small on-device ring buffer for RideTriggerAccessibilityService events,
 * readable from the app UI (see FareOverlayModule.getDiagnosticLog) without
 * adb/USB debugging -- the service typically fires while the driver is out
 * on the road, away from a connected computer, so `adb logcat` isn't a
 * realistic way to see what happened. Persisted to SharedPreferences (not
 * just in-memory) since the accessibility service can outlive/restart
 * independently of the app's own process.
 */
object DiagnosticLog {
    private const val PREFS_NAME = "fare_overlay_prefs"
    private const val KEY_LOG = "diagnostic_log"
    private const val MAX_ENTRIES = 100

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_LOG, "") ?: ""
        val entries = if (existing.isEmpty()) mutableListOf() else existing.split("\n").toMutableList()
        entries.add("${timeFormat.format(System.currentTimeMillis())}  $message")
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        prefs.edit().putString(KEY_LOG, entries.joinToString("\n")).apply()
    }

    fun getAll(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOG, "") ?: ""
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOG, "")
            .apply()
    }
}
