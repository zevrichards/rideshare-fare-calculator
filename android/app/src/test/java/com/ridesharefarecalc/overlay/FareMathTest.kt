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
    fun `charges only the base fare for zero distance and zero time`() {
        assertEquals(16.0, FareMath.calculateFare(0.0, 0.0), epsilon)
    }

    @Test
    fun `charges the standard per-km rate below the 20km threshold`() {
        // 16 + 10*1.75 + 15*1.1
        assertEquals(50.0, FareMath.calculateFare(10.0, 15.0), epsilon)
    }

    @Test
    fun `charges the standard rate for the full trip exactly at the threshold`() {
        // 16 + 20*1.75
        assertEquals(51.0, FareMath.calculateFare(20.0, 0.0), epsilon)
    }

    @Test
    fun `charges the higher rate only for distance beyond the threshold`() {
        // 16 + 20*1.75 + 5*3
        assertEquals(66.0, FareMath.calculateFare(25.0, 0.0), epsilon)
    }

    @Test
    fun `combines the tiered distance charge with the time charge`() {
        // 16 + 20*1.75 + 10*3 + 10*1.1
        assertEquals(92.0, FareMath.calculateFare(30.0, 10.0), epsilon)
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
