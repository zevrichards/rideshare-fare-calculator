package com.ridesharefarecalc.overlay

import com.ridesharefarecalc.overlay.RideTriggerAccessibilityService.Companion.isPickupPhaseScreen
import com.ridesharefarecalc.overlay.RideTriggerAccessibilityService.Companion.parseWazeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TTRS = "production.ttrides.driver"
private const val ALLRIDI = "product.allridi.driver"

// Screen texts copied from real device logs of both apps.
class ScreenDetectionTest {
    @Test
    fun `TTRS pickup screen is detected, before and after Arrived`() {
        val arriving = listOf("HELP", "Pickup", "Luke Assing", "8 URIAH BUTLER HIGHWAY", "U.W.I. Pool", "CANCEL REQUEST", "ARRIVED")
        val startRide = listOf("HELP", "Pickup", "Simone", "Mario's Pizza", "LP#28 CORNER", "CANCEL REQUEST", "START RIDE")
        assertTrue(isPickupPhaseScreen(TTRS, arriving))
        assertTrue(isPickupPhaseScreen(TTRS, startRide))
    }

    @Test
    fun `TTRS request list and ride-active screens are not pickup screens`() {
        val requestList = listOf("ONLINE", "Last update: 1 minute ago", "REGULAR", "2.09 km away", "Pickup time: 04:40 PM", "Accept", "Cancel")
        val rideActive = listOf("HELP", "Regular", "13 MT D'OR RD", "0 km", "Distance Covered", "00m 00s", "Ride Time", "END TRIP")
        assertFalse(isPickupPhaseScreen(TTRS, requestList))
        assertFalse(isPickupPhaseScreen(TTRS, rideActive))
    }

    @Test
    fun `Allridi pickup screen is detected in each phase`() {
        val arrived = listOf("Safiah H", "4.8", "MJ43+VJ Saint Augustine", "Arrived", "Drop off at", "3rd Avenue", "Cancel")
        val startTrip = listOf("Rebecca D", "4.8", "MH29+H5V", "Start Trip", "Drop off at", "3rd Avenue")
        val afterArrived = listOf("Rebecca D", "4.8", "MH29+H5V", "Drop off at", "Cancel")
        assertTrue(isPickupPhaseScreen(ALLRIDI, arrived))
        assertTrue(isPickupPhaseScreen(ALLRIDI, startTrip))
        assertTrue(isPickupPhaseScreen(ALLRIDI, afterArrived))
    }

    @Test
    fun `Allridi request and ride-active screens are not pickup screens`() {
        val request = listOf("REJECT", "4.8", "Total Rides Taken By User:", "411", "1.63 km", "ALLRIDI", "Royal Castle", "Akal Rd", "Accept")
        val rideActive = listOf("3rd Avenue", "Ride Duration", "00:00:00", "Distance Driven", "0 km", "Finish the trip", "Drop off at", "3rd Avenue")
        val home = listOf("ON")
        assertFalse(isPickupPhaseScreen(ALLRIDI, request))
        assertFalse(isPickupPhaseScreen(ALLRIDI, rideActive))
        assertFalse(isPickupPhaseScreen(ALLRIDI, home))
    }

    @Test
    fun `parses Waze minutes and kilometres`() {
        val metrics = parseWazeMetrics(listOf("Arrive 6:45", "12 min · 5.4 km", "Report"))
        assertNotNull(metrics)
        assertEquals(12.0, metrics!!.minutes, 1e-9)
        assertEquals(5.4, metrics.distanceKm, 1e-9)
    }

    @Test
    fun `parses Waze hours and converts miles`() {
        val hours = parseWazeMetrics(listOf("1 hr 5 min · 40 km"))!!
        assertEquals(65.0, hours.minutes, 1e-9)
        assertEquals(40.0, hours.distanceKm, 1e-9)

        val miles = parseWazeMetrics(listOf("10 min · 3 mi"))!!
        assertEquals(3 * 1.609344, miles.distanceKm, 1e-6)
    }

    @Test
    fun `parses Waze metrics split across separate text nodes`() {
        val metrics = parseWazeMetrics(listOf("12 min", "5.4 km"))
        assertNotNull(metrics)
        assertEquals(12.0, metrics!!.minutes, 1e-9)
        assertEquals(5.4, metrics.distanceKm, 1e-9)
    }

    @Test
    fun `no Waze metrics when the screen has none`() {
        assertNull(parseWazeMetrics(listOf("Search Waze", "Home", "Work")))
    }
}
