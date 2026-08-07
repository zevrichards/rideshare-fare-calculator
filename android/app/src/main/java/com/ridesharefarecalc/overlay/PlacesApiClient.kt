package com.ridesharefarecalc.overlay

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class PlaceLocation(val latitude: Double, val longitude: Double)

/**
 * Minimal client for the Places API (New) Text Search endpoint -- the native
 * counterpart to src/lib/geocoding.ts's searchDestination, used by
 * RideTriggerAccessibilityService to turn a plain address string read off
 * TTRS/Allridi's own screen into coordinates. Same Google Cloud
 * project/API key/endpoint as the JS version (GOOGLE_ROUTES_API_KEY --
 * Places API (New) must be enabled on that project, see README). Only asks
 * for the location field, not the display name, since the JS side isn't
 * involved here. Deliberately stdlib-only (HttpURLConnection + org.json),
 * matching RoutesApiClient. Blocking -- always call from a background thread.
 */
object PlacesApiClient {
    const val DEFAULT_ENDPOINT = "https://places.googleapis.com/v1/places:searchText"
    private const val TIMEOUT_MS = 8000

    fun searchText(
        query: String,
        apiKey: String,
        endpoint: String = DEFAULT_ENDPOINT,
    ): PlaceLocation? {
        if (query.isBlank()) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", "places.location")
            }

            val body = JSONObject().put("textQuery", query)
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            parseFirstLocation(responseBody)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parseFirstLocation(responseBody: String): PlaceLocation? {
        val location = JSONObject(responseBody)
            .optJSONArray("places")
            ?.optJSONObject(0)
            ?.optJSONObject("location")
            ?: return null
        if (!location.has("latitude") || !location.has("longitude")) return null
        return PlaceLocation(location.getDouble("latitude"), location.getDouble("longitude"))
    }
}
