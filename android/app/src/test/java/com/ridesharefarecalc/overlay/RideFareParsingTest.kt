package com.ridesharefarecalc.overlay

import com.ridesharefarecalc.overlay.RideTriggerAccessibilityService.Companion.parseRideFare
import com.ridesharefarecalc.overlay.RideTriggerAccessibilityService.Companion.parseRideId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val TTRS = "production.ttrides.driver"
private const val ALLRIDI = "product.allridi.driver"

// Screen texts copied from real device logs of both apps.
class RideFareParsingTest {
    private val ttrsComplete = listOf(
        "Ride Complete", "(ID:: #733320530)", "Mon, Oct 05, 2026 06:36 PM", "Distance Travelled",
        "6.65 km", "Ride Time", "10 min", "TT$39", "Please rate your experience of this ride", "DONE",
        "To check your ride summary visit earnings screen",
    )
    private val allridiPayment = listOf(
        "Payment", "Form of payment", "Trip fare", "TT$77", "Promotion", "-TT$0", "Extra fee",
        "Trip fare", "TT$77", "Close",
    )

    @Test
    fun `reads the fare and ride id from a TTRS ride-complete screen`() {
        assertEquals(39.0, parseRideFare(TTRS, ttrsComplete)!!, 1e-9)
        assertEquals("733320530", parseRideId(ttrsComplete))
    }

    @Test
    fun `reads the final trip fare from an Allridi payment screen`() {
        assertEquals(77.0, parseRideFare(ALLRIDI, allridiPayment)!!, 1e-9)
    }

    @Test
    fun `Allridi uses the last trip fare when it differs from the first`() {
        val withExtra = listOf("Payment", "Form of payment", "Trip fare", "TT$50", "Extra fee", "Trip fare", "TT$55")
        assertEquals(55.0, parseRideFare(ALLRIDI, withExtra)!!, 1e-9)
    }

    @Test
    fun `no fare while the amount has not appeared yet`() {
        assertNull(parseRideFare(ALLRIDI, listOf("Payment", "Form of payment", "Trip fare")))
        assertNull(parseRideFare(TTRS, listOf("Ride Complete", "Distance Travelled", "6.65 km")))
    }

    @Test
    fun `a zero fare is parsed as zero`() {
        val zero = listOf("Ride Complete", "(ID:: #733313771)", "Ride Time", "6 min", "P", "TT$0", "DONE")
        assertEquals(0.0, parseRideFare(TTRS, zero)!!, 1e-9)
    }

    @Test
    fun `commission matches what the apps' own earnings pages showed`() {
        // TTRS page went TT$72 -> TT$103 after a TT$39 ride; Allridi showed TT$45 for a TT$53 ride.
        assertEquals(31.2, DailyEarnings.netOfCommission(TTRS, 39.0), 1e-9)
        assertEquals(45.05, DailyEarnings.netOfCommission(ALLRIDI, 53.0), 1e-9)
    }
}
