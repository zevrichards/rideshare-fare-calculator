package com.ridesharefarecalc.overlay

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Tests the pure request-building/response-parsing logic directly rather
// than going through fetchRoadDistanceKm over a real socket -- this
// environment's sandboxed shell doesn't reliably support loopback test
// servers (a MockWebServer-based version of this test hung indefinitely).
// Robolectric (not plain JUnit) because org.json.JSONObject is also stubbed
// to throw "not mocked" on the default unit-test classpath.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutesApiClientTest {

    @Test
    fun `builds a request body with travel mode DRIVE and the given coordinates`() {
        val body = RoutesApiClient.buildRequestBody(
            originLat = 1.0, originLng = 2.0, destLat = 3.0, destLng = 4.0,
        )

        assertEquals("DRIVE", body.getString("travelMode"))

        val origin = body.getJSONObject("origin").getJSONObject("location").getJSONObject("latLng")
        assertEquals(1.0, origin.getDouble("latitude"), 1e-9)
        assertEquals(2.0, origin.getDouble("longitude"), 1e-9)

        val destination = body.getJSONObject("destination").getJSONObject("location").getJSONObject("latLng")
        assertEquals(3.0, destination.getDouble("latitude"), 1e-9)
        assertEquals(4.0, destination.getDouble("longitude"), 1e-9)
    }

    @Test
    fun `parses road distance in km from a successful response`() {
        val result = RoutesApiClient.parseDistanceKm("""{"routes":[{"distanceMeters":12000}]}""")
        assertEquals(12.0, result!!, 1e-9)
    }

    @Test
    fun `returns null when the response has no routes`() {
        assertNull(RoutesApiClient.parseDistanceKm("""{"routes":[]}"""))
    }

    @Test
    fun `returns null when a route has no distanceMeters`() {
        assertNull(RoutesApiClient.parseDistanceKm("""{"routes":[{}]}"""))
    }

    @Test
    fun `throws on invalid JSON so the caller's catch-all can fall back`() {
        assertThrows(JSONException::class.java) {
            RoutesApiClient.parseDistanceKm("not json")
        }
    }
}
