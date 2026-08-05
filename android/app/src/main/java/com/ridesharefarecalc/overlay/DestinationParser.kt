package com.ridesharefarecalc.overlay

import android.net.Uri

data class Destination(val latitude: Double, val longitude: Double)

/**
 * Parses destination coordinates out of the two intent URI shapes driver
 * apps use to hand off to a navigation app. Both are opaque (no "//"
 * authority): Uri.getQueryParameter() throws UnsupportedOperationException
 * on both rather than just returning null, hence queryParam() below.
 *   geo:0,0?q=15.3,-61.38(My Destination)
 *   google.navigation:q=15.3,-61.38
 */
object DestinationParser {
    private val LAT_LNG_REGEX = Regex("""^(-?[0-9]+\.?[0-9]*),(-?[0-9]+\.?[0-9]*)""")

    fun parse(uri: Uri): Destination? {
        val qParam = queryParam(uri, "q") ?: extractOpaqueParam(uri.schemeSpecificPart, "q")
        qParam?.let(::extractLatLng)?.let { return it }

        val beforeQuery = uri.schemeSpecificPart?.substringBefore('?')
        val fromPath = beforeQuery?.let(::extractLatLng)
        if (fromPath != null && (fromPath.latitude != 0.0 || fromPath.longitude != 0.0)) {
            return fromPath
        }

        return null
    }

    // geo: and google.navigation: URIs have no "//" authority, so Android's
    // real Uri classifies them as opaque -- getQueryParameter() throws
    // UnsupportedOperationException on those rather than returning null.
    private fun queryParam(uri: Uri, key: String): String? =
        try {
            uri.getQueryParameter(key)
        } catch (_: UnsupportedOperationException) {
            null
        }

    private fun extractOpaqueParam(raw: String?, key: String): String? {
        if (raw == null) return null
        val queryPart = raw.substringAfter('?', missingDelimiterValue = raw)
        return queryPart.split('&')
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.let(Uri::decode)
    }

    private fun extractLatLng(value: String): Destination? {
        val match = LAT_LNG_REGEX.find(value.trim()) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        return Destination(lat, lng)
    }
}
