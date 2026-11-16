package com.ridesharefarecalc.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

// Fixtures mirror src/lib/__tests__/fare.test.ts -- the two fare
// implementations (JS and this file) must agree on every case, since
// FareMath.kt exists specifically to avoid bridging every GPS tick through
// JS (see the comment on FareMath.kt).
class FareMathTest {

    private val epsilon = 1e-9

    @Test
    fun `TTRS floors at the minimum fare for zero distance and zero time`() {
        assertEquals(28.0, FareMath.calculateFare(RateCard.TTRS, 0.0, 0.0), epsilon)
    }

    @Test
    fun `TTRS charges the standard per-km rate below the 20km threshold`() {
        // raw = 16 + 10*1.75 + 15*1.1 = 50, above the $28 minimum
        assertEquals(50.0, FareMath.calculateFare(RateCard.TTRS, 10.0, 15.0), epsilon)
    }

    @Test
    fun `TTRS charges the standard rate for the full trip exactly at the threshold`() {
        // raw = 16 + 20*1.75 = 51
        assertEquals(51.0, FareMath.calculateFare(RateCard.TTRS, 20.0, 0.0), epsilon)
    }

    @Test
    fun `TTRS charges the higher rate only for distance beyond the threshold`() {
        // raw = 16 + 20*1.75 + 5*3 = 66
        assertEquals(66.0, FareMath.calculateFare(RateCard.TTRS, 25.0, 0.0), epsilon)
    }

    @Test
    fun `TTRS combines the tiered distance charge with the time charge`() {
        // raw = 16 + 20*1.75 + 10*3 + 10*1.1 = 92
        assertEquals(92.0, FareMath.calculateFare(RateCard.TTRS, 30.0, 10.0), epsilon)
    }

    @Test
    fun `TTRS ignores a surge multiplier since it does not support surge`() {
        assertEquals(92.0, FareMath.calculateFare(RateCard.TTRS, 30.0, 10.0, 1.5), epsilon)
    }

    @Test
    fun `Allridi floors at the minimum fare for zero distance and zero time`() {
        assertEquals(26.0, FareMath.calculateFare(RateCard.ALLRIDI, 0.0, 0.0), epsilon)
    }

    @Test
    fun `Allridi charges 3 per km beyond 20km`() {
        // raw = 15 + 20*1.55 + 10*3 + 10*1.1 = 87
        assertEquals(87.0, FareMath.calculateFare(RateCard.ALLRIDI, 30.0, 10.0), epsilon)
    }

    @Test
    fun `Allridi applies the surge multiplier to the whole calculated fare`() {
        // raw = (15 + 20*1.55 + 10*3 + 10*1.1) * 1.2 = 87 * 1.2 = 104.4
        assertEquals(104.4, FareMath.calculateFare(RateCard.ALLRIDI, 30.0, 10.0, 1.2), epsilon)
    }

    @Test
    fun `Allridi still applies the minimum fare floor when the surged fare is below it`() {
        // raw = 15 * 1.05 = 15.75, below the $26 minimum
        assertEquals(26.0, FareMath.calculateFare(RateCard.ALLRIDI, 0.0, 0.0, 1.05), epsilon)
    }

    @Test
    fun `Allridi does not floor a surged fare that already exceeds the minimum`() {
        // raw = (15 + 3*1.55) * 1.5 = 29.475, above the $26 minimum
        assertEquals(29.475, FareMath.calculateFare(RateCard.ALLRIDI, 3.0, 0.0, 1.5), epsilon)
    }

    @Test
    fun `a saved Allridi card from before the tier and 26 minimum is corrected`() {
        val old = RateCard.ALLRIDI.copy(
            longDistanceKmThreshold = null,
            perKmRateBeyondThreshold = null,
            minimumFare = 22.0,
            baseFare = 17.0,
        )
        val corrected = RateCard.withCorrections(old)
        assertEquals(20.0, corrected.longDistanceKmThreshold!!, epsilon)
        assertEquals(3.0, corrected.perKmRateBeyondThreshold!!, epsilon)
        assertEquals(26.0, corrected.minimumFare, epsilon)
        assertEquals(17.0, corrected.baseFare, epsilon)
    }

    @Test
    fun `corrections leave TTRS and a custom Allridi minimum alone`() {
        assertEquals(RateCard.TTRS, RateCard.withCorrections(RateCard.TTRS))
        val custom = RateCard.ALLRIDI.copy(minimumFare = 30.0)
        assertEquals(30.0, RateCard.withCorrections(custom).minimumFare, epsilon)
    }

    @Test
    fun `haversine returns zero for identical points`() {
        assertEquals(0.0, FareMath.haversineDistanceKm(15.3, -61.38, 15.3, -61.38), epsilon)
    }

    @Test
    fun `haversine returns approximately 111km for one degree of longitude at the equator`() {
        assertEquals(111.19, FareMath.haversineDistanceKm(0.0, 0.0, 0.0, 1.0), 0.1)
    }
}
