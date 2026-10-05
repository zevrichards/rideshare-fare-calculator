package com.ridesharefarecalc.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurgeParsingTest {
    @Test
    fun `parses the standalone x-multiplier from an Allridi request screen`() {
        val texts = listOf("REJECT", "x1.1", "4.8", "Total Rides Taken By User:", "191", "9.53 km", "ALLRIDI", "Accept")
        assertEquals(1.1, RideTriggerAccessibilityService.parseSurgeMultiplier(texts)!!, 1e-9)
    }

    @Test
    fun `parses an integer multiplier`() {
        assertEquals(2.0, RideTriggerAccessibilityService.parseSurgeMultiplier(listOf("x2"))!!, 1e-9)
    }

    @Test
    fun `returns null when no multiplier text is present`() {
        assertNull(RideTriggerAccessibilityService.parseSurgeMultiplier(listOf("REJECT", "4.8", "9.53 km", "Accept")))
    }

    @Test
    fun `does not match text that merely contains an x-number`() {
        assertNull(RideTriggerAccessibilityService.parseSurgeMultiplier(listOf("Max 1.5 riders", "Express")))
    }
}
