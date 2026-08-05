package com.ridesharefarecalc.overlay

import android.content.Context
import org.json.JSONObject

/**
 * SharedPreferences-backed rate card + surge storage, read/write natively
 * (same pattern as NavPreference). Needed because FareTrackingService and
 * NavigationInterceptActivity never touch ReactHost, so a purely
 * intercepted trip has no way to ask JS which rate card/surge is active --
 * JS writes here (via FareOverlayModule) whenever the user picks/edits a
 * card or moves the surge slider, and this is the only source of truth
 * FareTrackingService reads from.
 */
object RateCardPreference {
    private const val PREFS_NAME = "fare_overlay_prefs"
    private const val KEY_RATE_CARD_PREFIX = "rate_card_"
    private const val KEY_SELECTED_RATE_CARD_ID = "selected_rate_card_id"
    private const val KEY_SURGE_MULTIPLIER = "surge_multiplier"

    fun getRateCard(context: Context, id: String): RateCard {
        val default = RateCard.DEFAULTS.firstOrNull { it.id == id } ?: RateCard.TTRS
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RATE_CARD_PREFIX + id, null) ?: return default
        return try {
            RateCard.fromJson(JSONObject(json))
        } catch (_: Exception) {
            default
        }
    }

    fun setRateCard(context: Context, card: RateCard) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RATE_CARD_PREFIX + card.id, card.toJson().toString())
            .apply()
    }

    fun getSelectedRateCardId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SELECTED_RATE_CARD_ID, RateCard.TTRS.id) ?: RateCard.TTRS.id
    }

    fun setSelectedRateCardId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_RATE_CARD_ID, id)
            .apply()
    }

    fun getSelectedRateCard(context: Context): RateCard =
        getRateCard(context, getSelectedRateCardId(context))

    fun getSurgeMultiplier(context: Context): Double {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_SURGE_MULTIPLIER, 1.0f).toDouble()
    }

    fun setSurgeMultiplier(context: Context, value: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SURGE_MULTIPLIER, value.toFloat())
            .apply()
    }
}
