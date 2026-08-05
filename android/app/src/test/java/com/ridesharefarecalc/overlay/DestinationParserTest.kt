package com.ridesharefarecalc.overlay

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Needs a real android.net.Uri implementation (not the "not mocked" JVM
// stub), hence Robolectric rather than a plain JUnit test. Pinned to 34
// (rather than this project's compileSdk 36) since that's the SDK level
// actually installed/verified here, and 36 may be too new for this
// Robolectric version's shadows.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestinationParserTest {

    @Test
    fun `parses a hierarchical geo URI with a q parameter`() {
        val uri = Uri.parse("geo:0,0?q=15.3,-61.38(My Destination)")
        assertEquals(Destination(15.3, -61.38), DestinationParser.parse(uri))
    }

    @Test
    fun `parses an opaque google_navigation URI with a q parameter`() {
        val uri = Uri.parse("google.navigation:q=15.3,-61.38")
        assertEquals(Destination(15.3, -61.38), DestinationParser.parse(uri))
    }

    @Test
    fun `parses lat,lng directly from the path when there is no q parameter`() {
        val uri = Uri.parse("geo:15.3,-61.38")
        assertEquals(Destination(15.3, -61.38), DestinationParser.parse(uri))
    }

    @Test
    fun `returns null for a geo URI with no real destination`() {
        assertNull(DestinationParser.parse(Uri.parse("geo:0,0")))
    }

    @Test
    fun `returns null when the q parameter has no parseable coordinates`() {
        assertNull(DestinationParser.parse(Uri.parse("geo:0,0?q=some+place+name")))
    }
}
