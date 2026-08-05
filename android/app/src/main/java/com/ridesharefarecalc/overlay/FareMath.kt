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
 * constants change, update both this file and src/lib/fare.ts.
 */
object FareMath {
    const val BASE_FARE = 16.0
    const val RATE_PER_KM = 1.75
    const val LONG_DISTANCE_KM_THRESHOLD = 20.0
    const val RATE_PER_KM_BEYOND_THRESHOLD = 3.0
    const val RATE_PER_MINUTE = 1.1

    private const val EARTH_RADIUS_KM = 6371.0

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

    fun calculateFare(distanceKm: Double, minutes: Double): Double {
        val standardKm = min(distanceKm, LONG_DISTANCE_KM_THRESHOLD)
        val excessKm = max(distanceKm - LONG_DISTANCE_KM_THRESHOLD, 0.0)
        val distanceCharge = standardKm * RATE_PER_KM + excessKm * RATE_PER_KM_BEYOND_THRESHOLD
        val timeCharge = minutes * RATE_PER_MINUTE
        return BASE_FARE + distanceCharge + timeCharge
    }
}
