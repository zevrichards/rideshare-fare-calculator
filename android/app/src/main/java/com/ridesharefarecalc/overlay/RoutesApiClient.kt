package com.ridesharefarecalc.overlay

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal client for the Google Routes API computeRoutes endpoint. Used by
 * FareTrackingService to replace the haversine*1.3 fallback with real road
 * distance when GOOGLE_ROUTES_API_KEY is configured (see
 * android/local.properties). Deliberately stdlib-only (HttpURLConnection +
 * org.json) to avoid adding a network dependency for one call site. Blocking
 * -- always call from a background thread.
 */
object RoutesApiClient {
    private const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val TIMEOUT_MS = 8000

    fun fetchRoadDistanceKm(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        apiKey: String,
    ): Double? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", "routes.distanceMeters")
            }

            val body = JSONObject().apply {
                put("origin", JSONObject().put("location", JSONObject().put("latLng", latLng(originLat, originLng))))
                put("destination", JSONObject().put("location", JSONObject().put("latLng", latLng(destLat, destLng))))
                put("travelMode", "DRIVE")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val firstRoute = JSONObject(responseBody).optJSONArray("routes")?.optJSONObject(0)
                ?: return null
            if (!firstRoute.has("distanceMeters")) return null

            firstRoute.getInt("distanceMeters") / 1000.0
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun latLng(lat: Double, lng: Double) = JSONObject().apply {
        put("latitude", lat)
        put("longitude", lng)
    }
}
