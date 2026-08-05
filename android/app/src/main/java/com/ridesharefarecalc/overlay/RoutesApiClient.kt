package com.ridesharefarecalc.overlay

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class RouteEstimate(val distanceKm: Double, val durationMinutes: Double)

/**
 * Minimal client for the Google Routes API computeRoutes endpoint. Used by
 * FareTrackingService to replace the haversine*1.3/no-time fallback with
 * real, traffic-aware road distance and duration when GOOGLE_ROUTES_API_KEY
 * is configured (see android/local.properties). Deliberately stdlib-only
 * (HttpURLConnection + org.json) to avoid adding a network dependency for
 * one call site. Blocking -- always call from a background thread.
 */
object RoutesApiClient {
    const val DEFAULT_ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val TIMEOUT_MS = 8000
    private val DURATION_SECONDS_REGEX = Regex("""^(\d+(?:\.\d+)?)s$""")

    fun fetchRouteEstimate(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        apiKey: String,
        endpoint: String = DEFAULT_ENDPOINT,
    ): RouteEstimate? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", "routes.distanceMeters,routes.duration")
            }

            val body = buildRequestBody(originLat, originLng, destLat, destLng)
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            parseRouteEstimate(responseBody)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // Split out from fetchRouteEstimate so request-shaping and
    // response-parsing can be unit tested without a real socket -- this
    // environment's sandbox doesn't play well with loopback test servers.
    internal fun buildRequestBody(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
    ): JSONObject = JSONObject().apply {
        put("origin", JSONObject().put("location", JSONObject().put("latLng", latLng(originLat, originLng))))
        put("destination", JSONObject().put("location", JSONObject().put("latLng", latLng(destLat, destLng))))
        put("travelMode", "DRIVE")
        // Duration reflects live/predictive traffic conditions. This bills at
        // the Routes API's Pro SKU ($10/1,000, 5,000 free/month) rather than
        // Basic ($5/1,000, 10,000 free/month) -- see README.
        put("routingPreference", "TRAFFIC_AWARE")
    }

    internal fun parseRouteEstimate(responseBody: String): RouteEstimate? {
        val firstRoute = JSONObject(responseBody).optJSONArray("routes")?.optJSONObject(0)
            ?: return null
        if (!firstRoute.has("distanceMeters")) return null
        val durationSeconds = parseDurationSeconds(firstRoute.optString("duration", "")) ?: return null
        val distanceKm = firstRoute.getInt("distanceMeters") / 1000.0
        return RouteEstimate(distanceKm, durationSeconds / 60.0)
    }

    // Routes API returns duration as a protobuf Duration string, e.g. "929s".
    private fun parseDurationSeconds(duration: String): Double? =
        DURATION_SECONDS_REGEX.find(duration)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun latLng(lat: Double, lng: Double) = JSONObject().apply {
        put("latitude", lat)
        put("longitude", lng)
    }
}
