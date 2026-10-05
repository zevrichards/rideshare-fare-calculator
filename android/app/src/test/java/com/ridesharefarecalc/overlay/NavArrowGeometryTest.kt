package com.ridesharefarecalc.overlay

import com.ridesharefarecalc.overlay.RideTriggerAccessibilityService.Companion.Box
import com.ridesharefarecalc.overlay.RideTriggerAccessibilityService.Companion.isNavArrowCandidate
import com.ridesharefarecalc.overlay.RideTriggerAccessibilityService.Companion.pickNavArrowIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Bounds measured from real 900x2000 ride-active screenshots of each app.
class NavArrowGeometryTest {
    private val screenWidth = 900

    private fun pick(boxes: List<Box>): Box? {
        val candidates = boxes.filter { isNavArrowCandidate(it, screenWidth) }
        return pickNavArrowIndex(candidates, screenWidth)?.let { candidates[it] }
    }

    @Test
    fun `Allridi ride-active screen picks the blue arrow, not the call or locate buttons`() {
        val arrow = Box(760, 1464, 868, 1572)
        val boxes = listOf(
            Box(20, 105, 90, 185), // hamburger menu
            Box(405, 100, 495, 190), // SOS
            Box(800, 100, 872, 190), // notification bell
            Box(753, 1308, 865, 1418), // locate-me
            Box(628, 1477, 732, 1559), // call
            arrow,
            Box(50, 1791, 190, 1878), // finish-the-trip slider handle
            Box(25, 1776, 875, 1895), // finish-the-trip bar
        )
        assertEquals(arrow, pick(boxes))
    }

    @Test
    fun `Allridi still picks the arrow when the call button sits a few pixels lower`() {
        val arrow = Box(760, 1464, 868, 1572)
        val lowerCall = Box(628, 1490, 732, 1572)
        assertEquals(arrow, pick(listOf(lowerCall, arrow)))
    }

    @Test
    fun `TTRS ride-active screen picks the white arrow, not the locate button`() {
        val arrow = Box(780, 1256, 878, 1354)
        val boxes = listOf(
            Box(22, 160, 120, 230), // HELP
            Box(780, 159, 878, 257), // locate-me
            arrow,
            Box(65, 1760, 835, 1862), // END TRIP
        )
        assertEquals(arrow, pick(boxes))
    }

    @Test
    fun `wide bars and left-side controls are never candidates`() {
        assertFalse(isNavArrowCandidate(Box(25, 1776, 875, 1895), screenWidth))
        assertFalse(isNavArrowCandidate(Box(50, 1791, 190, 1878), screenWidth))
        assertTrue(isNavArrowCandidate(Box(780, 1256, 878, 1354), screenWidth))
    }
}
