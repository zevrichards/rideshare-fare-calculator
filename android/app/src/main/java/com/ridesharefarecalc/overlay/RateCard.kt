package com.ridesharefarecalc.overlay

import org.json.JSONObject

/**
 * Mirrors src/lib/rateCards.ts's RateCard shape. longDistanceKmThreshold/
 * perKmRateBeyondThreshold are null together when a card has no tiered
 * long-distance rate (flat perKmRate for the whole trip -- e.g. Allridi).
 */
data class RateCard(
    val id: String,
    val name: String,
    val baseFare: Double,
    val perKmRate: Double,
    val longDistanceKmThreshold: Double?,
    val perKmRateBeyondThreshold: Double?,
    val perMinuteRate: Double,
    val minimumFare: Double,
    val supportsSurge: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseFare", baseFare)
        put("perKmRate", perKmRate)
        put("longDistanceKmThreshold", longDistanceKmThreshold ?: JSONObject.NULL)
        put("perKmRateBeyondThreshold", perKmRateBeyondThreshold ?: JSONObject.NULL)
        put("perMinuteRate", perMinuteRate)
        put("minimumFare", minimumFare)
        put("supportsSurge", supportsSurge)
    }

    companion object {
        val TTRS = RateCard(
            id = "ttrs",
            name = "TTRS",
            baseFare = 16.0,
            perKmRate = 1.75,
            longDistanceKmThreshold = 20.0,
            perKmRateBeyondThreshold = 3.0,
            perMinuteRate = 1.1,
            minimumFare = 28.0,
            supportsSurge = false,
        )

        val ALLRIDI = RateCard(
            id = "allridi",
            name = "Allridi",
            baseFare = 15.0,
            perKmRate = 1.55,
            longDistanceKmThreshold = null,
            perKmRateBeyondThreshold = null,
            perMinuteRate = 1.1,
            minimumFare = 22.0,
            supportsSurge = true,
        )

        val DEFAULTS = listOf(TTRS, ALLRIDI)

        fun fromJson(json: JSONObject): RateCard = RateCard(
            id = json.getString("id"),
            name = json.getString("name"),
            baseFare = json.getDouble("baseFare"),
            perKmRate = json.getDouble("perKmRate"),
            longDistanceKmThreshold = json.optDoubleOrNull("longDistanceKmThreshold"),
            perKmRateBeyondThreshold = json.optDoubleOrNull("perKmRateBeyondThreshold"),
            perMinuteRate = json.getDouble("perMinuteRate"),
            minimumFare = json.getDouble("minimumFare"),
            supportsSurge = json.getBoolean("supportsSurge"),
        )

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (isNull(key) || !has(key)) null else getDouble(key)
    }
}
