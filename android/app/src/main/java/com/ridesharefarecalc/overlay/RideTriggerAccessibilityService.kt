package com.ridesharefarecalc.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        // Temporary diagnostic logging (v0.1.1) -- the service reportedly
        // didn't fire on either app on a real ride. This TAG lets us confirm,
        // via `adb logcat -s RideTrigger`, whether the service is even
        // receiving click events at all inside these apps (any button, not
        // just Start Ride, since packageNames scopes the whole app) before
        // chasing narrower theories.
        private const val TAG = "RideTrigger"

        private const val START_RIDE_BTN_ID = "driverStartRideBtn"
        private const val DROP_ADDRESS_ID = "textViewCustomerDropAddress"

        private val RATE_CARD_ID_BY_PACKAGE = mapOf(
            "production.ttrides.driver" to "ttrs",
            "product.allridi.driver" to "allridi",
        )
    }

    // Logs to both logcat (for a connected computer) and the on-device ring
    // buffer (see DiagnosticLog) -- the service typically fires while the
    // driver is out on the road with no computer around, so the ring buffer,
    // readable from within the app itself, is the one that actually matters
    // in practice.
    private fun logBoth(message: String) {
        Log.d(TAG, message)
        DiagnosticLog.log(this, message)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        logBoth("onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val packageName = event.packageName?.toString() ?: return
        val rateCardId = RATE_CARD_ID_BY_PACKAGE[packageName] ?: return

        val source = event.source
        val viewId = source?.viewIdResourceName
        val text = source?.text
        val contentDesc = source?.contentDescription
        logBoth("click pkg=$packageName viewId=$viewId text=$text desc=$contentDesc class=${event.className}")
        val isStartRideClick = viewId == "$packageName:id/$START_RIDE_BTN_ID"

        // The clicked view itself carries no identifying info (no id, no
        // text, no description) -- likely a custom touch/gesture wrapper
        // (e.g. a swipe-to-confirm container). Climb the node's own parent
        // chain instead of re-querying rootInActiveWindow -- a fresh window
        // query can race with a screen transition the click itself triggers
        // and come back null (confirmed: "(no root)" seen on a real device
        // right as this exact kind of click fired), whereas walking up from
        // the node reference we already have doesn't depend on that query.
        if (viewId == null && text == null && contentDesc == null) {
            logBoth("  ancestors: ${describeAncestors(source)}")
            logBoth("  screen context: ${describeScreen()}")
        }

        source?.recycle()

        if (!isStartRideClick) return

        logBoth("Start Ride click matched, package=$packageName")

        // Guards against double-starting if the event somehow fires twice.
        if (FareTrackingService.activeSnapshot != null) {
            logBoth("Ignoring: a trip is already active")
            return
        }

        val dropAddress = findDropAddress(packageName)
        logBoth("dropAddress=$dropAddress")

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

    // Climbs from the clicked node's own parent reference (not a fresh
    // window query) collecting each ancestor's id/text/desc/class. More
    // reliable than describeScreen() right at the moment of a click that
    // triggers its own screen transition.
    private fun describeAncestors(start: AccessibilityNodeInfo?): String {
        val parts = mutableListOf<String>()
        var current = start?.parent
        var depth = 0
        while (current != null && depth < 8) {
            parts.add(
                "[${current.className} id=${current.viewIdResourceName} " +
                    "text=${current.text} desc=${current.contentDescription}]",
            )
            val next = current.parent
            current.recycle()
            current = next
            depth++
        }
        return if (parts.isEmpty()) "(no parent)" else parts.joinToString(" < ")
    }

    // Walks the current window's node tree collecting visible text, capped
    // to keep a single log line readable. Diagnostic-only -- gives screen
    // context for clicks whose own view has no id/text/description.
    private fun describeScreen(): String {
        val root = rootInActiveWindow ?: return "(no root)"
        val texts = mutableListOf<String>()
        try {
            collectText(root, texts)
        } finally {
            root.recycle()
        }
        return if (texts.isEmpty()) "(no visible text found)" else texts.take(12).joinToString(" | ")
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (out.size >= 12) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(text)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
            child.recycle()
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
        if (apiKey.isEmpty()) {
            logBoth("geocodeAndAttach: no API key configured, skipping")
            return
        }

        Thread {
            val location = PlacesApiClient.searchText(address, apiKey)
            logBoth("geocodeAndAttach: resolved=$location")
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
