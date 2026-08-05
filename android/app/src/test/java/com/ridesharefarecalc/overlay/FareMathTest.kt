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
        assertEquals(22.0, FareMath.calculateFare(RateCard.ALLRIDI, 0.0, 0.0), epsilon)
    }

    @Test
    fun `Allridi charges a flat per-km rate with no long-distance tier`() {
        // raw = 15 + 30*1.55 + 10*1.1 = 72.5
        assertEquals(72.5, FareMath.calculateFare(RateCard.ALLRIDI, 30.0, 10.0), epsilon)
    }

    @Test
    fun `Allridi applies the surge multiplier to the whole calculated fare`() {
        // raw = (15 + 30*1.55 + 10*1.1) * 1.2 = 72.5 * 1.2 = 87
        assertEquals(87.0, FareMath.calculateFare(RateCard.ALLRIDI, 30.0, 10.0, 1.2), epsilon)
    }

    @Test
    fun `Allridi still applies the minimum fare floor when the surged fare is below it`() {
        // raw = 15 * 1.05 = 15.75, below the $22 minimum
        assertEquals(22.0, FareMath.calculateFare(RateCard.ALLRIDI, 0.0, 0.0, 1.05), epsilon)
    }

    @Test
    fun `Allridi does not floor a surged fare that already exceeds the minimum`() {
        // raw = (15 + 1*1.55) * 1.5 = 24.825, above the $22 minimum
        assertEquals(24.825, FareMath.calculateFare(RateCard.ALLRIDI, 1.0, 0.0, 1.5), epsilon)
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
