package com.ridesharefarecalc.overlay

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mirrors src/lib/fare.ts. This app intentionally keeps two implementations
 * of the fare formula (JS for pre-trip estimate/history, native for the
 * live overlay) rather than bridging every GPS tick through JS. If the rate
 * card logic changes, update both this file and src/lib/fare.ts.
 */
object FareMath {
    private const val EARTH_RADIUS_KM = 6371.0

    // Only used when there's no real routing data yet (before the Routes API
    // response lands, or if it fails) -- a rough guess so the pre-trip
    // estimate has *some* time charge instead of always 0, not meant to
    // reflect real traffic. Mirrors src/lib/fare.ts's ASSUMED_AVERAGE_SPEED_KMH.
    const val ASSUMED_AVERAGE_SPEED_KMH = 30.0

    fun haversineDistanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)

        val sinHalfDLat = sin(dLat / 2)
        val sinHalfDLon = sin(dLon / 2)
        val h = sinHalfDLat * sinHalfDLat +
            cos(radLat1) * cos(radLat2) * sinHalfDLon * sinHalfDLon

        return 2 * EARTH_RADIUS_KM * asin(sqrt(h))
    }

    // surgeMultiplier is ignored for rate cards that don't support surge
    // (e.g. TTRS) -- the caller doesn't need to know which cards support it.
    fun calculateFare(
        rateCard: RateCard,
        distanceKm: Double,
        minutes: Double,
        surgeMultiplier: Double = 1.0,
    ): Double {
        val hasTier = rateCard.longDistanceKmThreshold != null
        val standardKm = if (hasTier) min(distanceKm, rateCard.longDistanceKmThreshold!!) else distanceKm
        val excessKm = if (hasTier) max(distanceKm - rateCard.longDistanceKmThreshold!!, 0.0) else 0.0

        val perKmBeyond = rateCard.perKmRateBeyondThreshold ?: rateCard.perKmRate
        val distanceCharge = standardKm * rateCard.perKmRate + excessKm * perKmBeyond
        val timeCharge = minutes * rateCard.perMinuteRate

        val effectiveSurge = if (rateCard.supportsSurge) surgeMultiplier else 1.0
        val rawTotal = (rateCard.baseFare + distanceCharge + timeCharge) * effectiveSurge

        // The rate card's minimum fare is a floor on the whole trip, applied
        // after surge -- a short/cheap surged trip still can't undercut it.
        return max(rawTotal, rateCard.minimumFare)
    }
}
