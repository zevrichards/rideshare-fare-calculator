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

    // Both apps' Earnings screens already show take-home (e.g. a TT$39 TTRS ride moved the
    // page from TT$72 to TT$103; a TT$53 Allridi ride showed as TT$45), whereas the fare on
    // an end-of-ride screen is what the rider paid, before the platform's cut.
    private val COMMISSION_RATES = mapOf(
        "production.ttrides.driver" to 0.20,
        "product.allridi.driver" to 0.15,
    )

    fun netOfCommission(packageName: String, grossFare: Double): Double =
        grossFare * (1.0 - (COMMISSION_RATES[packageName] ?: 0.0))

    // Adds a finished ride (after commission) to today's figure. The next time the driver
    // opens that app's Earnings screen, record() replaces this with the app's own number.
    fun addRide(context: Context, packageName: String, grossFare: Double): Double {
        val total = (getToday(context, packageName) ?: 0.0) + netOfCommission(packageName, grossFare)
        record(context, packageName, total)
        return total
    }

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
