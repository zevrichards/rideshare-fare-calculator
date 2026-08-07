package com.ridesharefarecalc.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.ridesharefarecalc.BuildConfig

/**
 * Opt-in (user must enable via Settings > Accessibility -- see
 * FareOverlayModule.hasAccessibilityServiceEnabled/requestAccessibilityServiceEnable)
 * detector for TTRS/Allridi's "Start Ride" button: the moment a driver
 * confirms pickup and the paid portion of a trip begins. Scoped via
 * res/xml/accessibility_service_config.xml to just these two packages;
 * observe-only, no gesture/action-injection capability requested.
 *
 * Both apps are built from the same white-label codebase (confirmed by
 * decompiling both real driver APKs) and share identical view IDs:
 *   - driverStartRideBtn: the trigger, a real View with a standard
 *     setOnClickListener/performClick() despite its swipe-to-confirm visual,
 *     so a plain TYPE_VIEW_CLICKED event is expected here.
 *   - textViewCustomerDropAddress: a sibling on the same screen, holding the
 *     drop-off address as plain text.
 *
 * Existing manual/intercept/destination-optional flows are untouched by any
 * of this -- this service only ever calls the same public entry points
 * (FareTrackingService's own Intent actions) those flows already use.
 */
class RideTriggerAccessibilityService : AccessibilityService() {

    companion object {
        private const val START_RIDE_BTN_ID = "driverStartRideBtn"
        private const val DROP_ADDRESS_ID = "textViewCustomerDropAddress"

        private val RATE_CARD_ID_BY_PACKAGE = mapOf(
            "production.ttrides.driver" to "ttrs",
            "product.allridi.driver" to "allridi",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val packageName = event.packageName?.toString() ?: return
        val rateCardId = RATE_CARD_ID_BY_PACKAGE[packageName] ?: return

        val source = event.source ?: return
        val isStartRideClick = source.viewIdResourceName == "$packageName:id/$START_RIDE_BTN_ID"
        source.recycle()
        if (!isStartRideClick) return

        // Guards against double-starting if the event somehow fires twice.
        if (FareTrackingService.activeSnapshot != null) return

        val dropAddress = findDropAddress(packageName)

        // Set before starting so the very first tick already uses the right
        // card -- matches which app the click came from, not whatever was
        // last selected in-app.
        RateCardPreference.setSelectedRateCardId(this, rateCardId)

        // Starts the fare clock immediately, with no destination -- the
        // same destination-optional path TripScreen's own Start Trip uses.
        // Don't block trip start on the geocoding network call below.
        ContextCompat.startForegroundService(this, Intent(this, FareTrackingService::class.java))

        if (!dropAddress.isNullOrBlank()) {
            geocodeAndAttach(dropAddress)
        }
    }

    private fun findDropAddress(packageName: String): String? {
        val root = rootInActiveWindow ?: return null
        try {
            val nodes = root.findAccessibilityNodeInfosByViewId("$packageName:id/$DROP_ADDRESS_ID")
            val text = nodes?.firstOrNull()?.text?.toString()
            nodes?.forEach { it.recycle() }
            return text
        } finally {
            root.recycle()
        }
    }

    // Geocodes off the main thread (same pattern as
    // FareTrackingService.resolveEstimate's background Routes API lookup),
    // then attaches the result to the already-running trip via
    // ACTION_SET_DESTINATION rather than waiting to start tracking at all.
    private fun geocodeAndAttach(address: String) {
        val apiKey = BuildConfig.GOOGLE_ROUTES_API_KEY
        if (apiKey.isEmpty()) return

        Thread {
            val location = PlacesApiClient.searchText(address, apiKey)
            if (location != null) {
                val intent = Intent(this, FareTrackingService::class.java).apply {
                    action = FareTrackingService.ACTION_SET_DESTINATION
                    putExtra(FareTrackingService.EXTRA_DEST_LAT, location.latitude)
                    putExtra(FareTrackingService.EXTRA_DEST_LNG, location.longitude)
                }
                startService(intent)
            }
        }.start()
    }

    override fun onInterrupt() {}
}
