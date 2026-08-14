package com.ridesharefarecalc.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.ridesharefarecalc.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opt-in (user must enable via Settings > Accessibility -- see
 * FareOverlayModule.hasAccessibilityServiceEnabled/requestAccessibilityServiceEnable)
 * detector for the moment a driver confirms pickup and the paid portion of a
 * TTRS/Allridi trip begins. Scoped via res/xml/accessibility_service_config.xml
 * to just these two packages; observe-only, no gesture/action-injection
 * capability requested.
 *
 * Detection is screen-content-based, not button-based. Static APK
 * decompilation suggested a specific "Start Ride" button
 * (driverStartRideBtn/textViewCustomerDropAddress), but real-device testing
 * (v0.1.1 through v0.1.6-beta) showed that theory was wrong on two fronts:
 * the actual flow doesn't go through that button at all (it's tucked behind
 * an "arrived?" confirmation + a separate start tap), and even that real tap
 * can't be inspected reliably -- it triggers its own screen transition,
 * which invalidates the clicked AccessibilityNodeInfo before onClickEvent's
 * handler runs (confirmed: both a fresh rootInActiveWindow query and
 * climbing the node's own parent chain came back empty on a real device).
 *
 * The fix (v0.1.7-beta on): watch TYPE_WINDOW_STATE_CHANGED instead, which
 * fires once a new screen has already settled -- it doesn't race the
 * transition it's reporting on. A real TTRS ride, logged in full, showed the
 * screen right after the trip actually starts is uniquely identifiable by
 * its own visible text ("Distance Covered" / "Ride Time" only ever appear
 * once a trip is in progress). Matching on that instead of any specific
 * button sidesteps the whole staleness problem.
 *
 * Existing manual/intercept/destination-optional flows are untouched by any
 * of this -- this service only ever calls the same public entry points
 * (FareTrackingService's own Intent actions) those flows already use.
 */
class RideTriggerAccessibilityService : AccessibilityService() {

    companion object {
        // Diagnostic logging, kept even after the fix -- the service fires
        // in the field, not somewhere adb can reach, so the on-device log
        // (see DiagnosticLog) remains the only practical way to confirm it's
        // still working as TTRS/Allridi's own UI changes over time.
        private const val TAG = "RideTrigger"

        // Legacy id-based match, kept as a harmless fallback in case some
        // app variant does route through a real, id-bearing button -- but
        // the primary trigger below no longer depends on this.
        private const val START_RIDE_BTN_ID = "driverStartRideBtn"
        private const val DROP_ADDRESS_ID = "textViewCustomerDropAddress"

        // Confirmed present, from real rides' screen text, only once the
        // paid trip has actually started (never during accept/navigate/
        // arrived). Despite sharing a codebase, TTRS and Allridi use
        // different wording here ("Distance Covered" vs "Distance
        // Driven") -- confirmed independently from two separate real rides.
        private val RIDE_ACTIVE_MARKERS = listOf("Distance Covered", "Distance Driven")

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
        val packageName = event.packageName?.toString() ?: return
        if (RATE_CARD_ID_BY_PACKAGE[packageName] == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event, packageName)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event, packageName)
        }
    }

    // Legacy id-based match -- see START_RIDE_BTN_ID's comment. Kept as a
    // harmless fallback; the primary trigger is handleWindowStateChanged.
    private fun handleClick(event: AccessibilityEvent, packageName: String) {
        val source = event.source
        val viewId = source?.viewIdResourceName
        val text = source?.text
        val contentDesc = source?.contentDescription
        logBoth("click pkg=$packageName viewId=$viewId text=$text desc=$contentDesc class=${event.className}")
        val isStartRideClick = viewId == "$packageName:id/$START_RIDE_BTN_ID"
        source?.recycle()

        if (!isStartRideClick) return

        logBoth("Start Ride click matched (legacy id path), package=$packageName")
        startTripIfNeeded(packageName)
    }

    // Fires once the new screen/window has already settled -- unlike a
    // click, this doesn't race a transition the event itself is causing, so
    // rootInActiveWindow is reliable here. Primary trigger: if the settled
    // screen's own text shows the trip is now in progress, start tracking.
    private fun handleWindowStateChanged(event: AccessibilityEvent, packageName: String) {
        val eventText = event.text?.joinToString(" | ")
        logBoth("window changed pkg=$packageName class=${event.className} text=$eventText")
        val texts = collectScreenTexts()
        logBoth("  screen context: ${describeScreen(texts)}")

        if (texts.any { text -> RIDE_ACTIVE_MARKERS.any { marker -> text.contains(marker) } }) {
            logBoth("Ride-active screen detected, package=$packageName")
            startTripIfNeeded(packageName, extractDropAddress(texts))
        }

        extractDailyEarnings(packageName, texts)?.let { amount ->
            logBoth("Daily earnings detected pkg=$packageName amount=$amount")
            DailyEarnings.record(this, packageName, amount)
        }
    }

    private fun startTripIfNeeded(packageName: String, screenDropAddress: String? = null) {
        // Guards against double-starting -- both handleClick and
        // handleWindowStateChanged can call this, and the ride-active
        // screen re-fires window-changed repeatedly as its timer ticks.
        if (FareTrackingService.activeSnapshot != null) {
            logBoth("Ignoring: a trip is already active")
            return
        }

        // textViewCustomerDropAddress (see DROP_ADDRESS_ID) turned out not
        // to hold the address in the real UI -- confirmed via a real ride's
        // log, findDropAddress() returned null. The window-state path
        // extracts the address straight from the ride-active screen's own
        // visible text instead (see extractDropAddress); the id-based
        // lookup remains only as a fallback for the legacy click path.
        val dropAddress = screenDropAddress ?: findDropAddress(packageName)
        logBoth("dropAddress=$dropAddress")

        // Set before starting so the very first tick already uses the right
        // card -- matches which app the trigger came from, not whatever was
        // last selected in-app.
        RateCardPreference.setSelectedRateCardId(this, RATE_CARD_ID_BY_PACKAGE.getValue(packageName))

        // Starts the fare clock immediately, with no destination -- the
        // same destination-optional path TripScreen's own Start Trip uses.
        // Don't block trip start on the geocoding network call below.
        ContextCompat.startForegroundService(this, Intent(this, FareTrackingService::class.java))

        if (!dropAddress.isNullOrBlank()) {
            geocodeAndAttach(dropAddress)
        }
    }

    // Walks the current window's node tree collecting visible text, capped
    // to keep results readable/bounded. Diagnostic-only in itself, but also
    // the source for extractDropAddress below.
    private fun collectScreenTexts(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        try {
            collectText(root, texts)
        } finally {
            root.recycle()
        }
        return texts
    }

    private fun describeScreen(texts: List<String>): String =
        if (texts.isEmpty()) "(no visible text found)" else texts.take(12).joinToString(" | ")

    // Both apps put the drop-off address on the ride-active screen, but
    // structured differently (confirmed from separate real rides' logs):
    //   - Allridi has an explicit "Drop off at" label immediately followed
    //     by the address itself -- use that when present, it's unambiguous.
    //   - TTRS has no such label; there, the address consistently appears
    //     as the item right before a standalone "<number> km" entry (e.g.
    //     "Regular | Park Avenue Park Avenue San Juan | 0 km | Distance
    //     Covered | ..."). Only tried as a fallback, since on Allridi's
    //     screen the item before "0 km" is "Distance Driven", not the
    //     address -- this pattern alone would be wrong there.
    // Both extraction attempts are scoped to only run on the already-
    // confirmed ride-active screen (see handleWindowStateChanged), so
    // neither should false-match similar-looking text elsewhere.
    private val kmEntryPattern = Regex("""^\d+(\.\d+)?\s*km$""")

    private fun extractDropAddress(texts: List<String>): String? {
        val dropOffLabelIndex = texts.indexOfFirst { it.equals("Drop off at", ignoreCase = true) }
        if (dropOffLabelIndex >= 0 && dropOffLabelIndex + 1 < texts.size) {
            return texts[dropOffLabelIndex + 1]
        }

        val kmIndex = texts.indexOfFirst { kmEntryPattern.matches(it.trim()) }
        if (kmIndex <= 0) return null
        return texts[kmIndex - 1]
    }

    // Passively captured whenever the driver happens to open their own
    // app's Earnings screen -- there's no way to navigate there ourselves,
    // only to notice the figure when it's already on screen. Each app
    // structures this screen differently, so parsing is per-package.
    private fun extractDailyEarnings(packageName: String, texts: List<String>): Double? =
        when (packageName) {
            "product.allridi.driver" -> extractAllridiDailyEarnings(texts)
            "production.ttrides.driver" -> extractTtrsDailyEarnings(texts)
            else -> null
        }

    // Confirmed from a real Allridi Earnings screen: a "Daily Earnings"
    // section lists "<Day>, <DD/MM>" entries each immediately followed by
    // that day's amount (e.g. "TUE, 11/08 | TT$22 | MON, 10/08 | TT$93 |
    // ..."). Matching today's actual date string, rather than assuming the
    // list's first entry is always today, avoids depending on sort order.
    private fun extractAllridiDailyEarnings(texts: List<String>): Double? {
        val todayLabel = SimpleDateFormat("EEE, dd/MM", Locale.US).format(Date())
        val labelIndex = texts.indexOfFirst { it.equals(todayLabel, ignoreCase = true) }
        if (labelIndex < 0 || labelIndex + 1 >= texts.size) return null
        return parseCurrency(texts[labelIndex + 1])
    }

    // Best-effort, not yet confirmed against a real TTRS Earnings screen --
    // based on the driver's description of a value sitting directly above a
    // "today's earning(s)" heading. Diagnostic logging (see logBoth above)
    // will show whether this actually matches once tested on a real screen.
    private fun extractTtrsDailyEarnings(texts: List<String>): Double? {
        val headingIndex = texts.indexOfFirst {
            it.contains("today", ignoreCase = true) && it.contains("earning", ignoreCase = true)
        }
        if (headingIndex <= 0) return null
        return parseCurrency(texts[headingIndex - 1])
    }

    private fun parseCurrency(text: String): Double? =
        Regex("""[\d,]+(\.\d+)?""").find(text)?.value?.replace(",", "")?.toDoubleOrNull()

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
