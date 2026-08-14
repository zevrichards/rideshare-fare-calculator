package com.ridesharefarecalc.overlay

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-app "today's earnings" figure, captured passively whenever
 * RideTriggerAccessibilityService happens to see the driver open that app's
 * own Earnings screen -- there's no way to proactively fetch it, only to
 * notice it when the driver looks themselves. Stored with the date it was
 * captured so a stale figure from a previous day is never shown as today's.
 */
object DailyEarnings {
    private const val PREFS_NAME = "fare_overlay_prefs"
    private const val KEY_PREFIX = "daily_earnings_"

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun record(context: Context, packageName: String, amount: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + packageName, "$amount|${todayKey()}")
            .apply()
    }

    // Null if nothing was ever captured for this package, or if the stored
    // figure is from a previous day.
    fun getToday(context: Context, packageName: String): Double? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + packageName, null) ?: return null
        val parts = raw.split("|")
        if (parts.size != 2 || parts[1] != todayKey()) return null
        return parts[0].toDoubleOrNull()
    }
}
