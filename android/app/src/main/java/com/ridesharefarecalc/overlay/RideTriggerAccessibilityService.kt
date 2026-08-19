package com.ridesharefarecalc.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
 * TTRS/Allridi trip begins, and for when that trip ends. Scoped via
 * res/xml/accessibility_service_config.xml to just these two packages;
 * observe-only for its core purpose -- no gesture/action-injection
 * capability requested beyond the online/offline toggle described below.
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
 * button sidesteps the whole staleness problem. Trip-end detection (see
 * RIDE_ENDED_MARKERS_BY_PACKAGE) uses the same approach.
 *
 * Existing manual/intercept/destination-optional flows are untouched by any
 * of this -- this service only ever calls the same public entry points
 * (FareTrackingService's own Intent actions) those flows already use.
 *
 * Also taps the other app's online/offline toggle once a trip genuinely
 * starts on one (to avoid a double-booking), and taps it back online once
 * that trip ends -- see triggerToggle/performPendingToggle. Unlike the
 * ride-start/end detection itself, this does briefly foreground the other
 * app and perform an action on it -- deliberately scoped to a status toggle
 * only, never to responding to a Request, which is a materially different
 * category of automation from an auto-accept ("sniping") tool.
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

        // Confirmed from real rides' screen text, only once the trip has
        // actually ended: TTRS shows a "Ride Complete" summary/rating
        // screen; Allridi shows a payment/receipt screen. Allridi's two
        // markers must both be present (a lone "Trip fare" is a bit generic
        // to trust alone); TTRS's single marker is distinctive enough on
        // its own. Not yet confirmed live -- diagnostic logging will show
        // what's actually there if this needs adjusting.
        private val RIDE_ENDED_MARKERS_BY_PACKAGE = mapOf(
            "production.ttrides.driver" to listOf("Ride Complete"),
            "product.allridi.driver" to listOf("Form of payment", "Trip fare"),
        )

        private val RATE_CARD_ID_BY_PACKAGE = mapOf(
            "production.ttrides.driver" to "ttrs",
            "product.allridi.driver" to "allridi",
        )

        // Cap on how many settled screens of the target app we'll inspect
        // looking for its online/offline toggle before giving up -- without
        // this, a toggle that's never found would leave every future visit
        // to that app (for unrelated reasons) mistakenly treated as still
        // pending a toggle.
        private const val MAX_TOGGLE_ATTEMPTS = 4
    }

    // Describes an in-progress attempt to tap the other app's online/offline
    // toggle -- set by triggerToggle, consumed by performPendingToggle once
    // targetPackage's window settles, cleared on success or after
    // MAX_TOGGLE_ATTEMPTS.
    private data class PendingToggle(
        val targetPackage: String,
        // The exact text currently shown on the toggle we're about to tap
        // ("ON" to switch it off, "OFF" to switch it back on).
        val searchText: String,
        val returnToPackage: String?,
        var attempts: Int = 0,
    )

    private var pendingToggle: PendingToggle? = null

    // Which app (if any) this service itself put offline, so a trip ending
    // on the other one knows which app to bring back online. Cleared once
    // that toggle-back-on actually succeeds, not just attempted -- so a
    // failed/retrying attempt doesn't lose track of which app is owed one.
    private var offlinedPackage: String? = null

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
    // rootInActiveWindow is reliable here. Primary trigger for both starting
    // and stopping the fare clock: if the settled screen's own text shows
    // the trip is now in progress (or has ended), act accordingly.
    private fun handleWindowStateChanged(event: AccessibilityEvent, packageName: String) {
        val eventText = event.text?.joinToString(" | ")
        logBoth("window changed pkg=$packageName class=${event.className} text=$eventText")
        val texts = collectScreenTexts()
        logBoth("  screen context: ${describeScreen(texts)}")

        if (packageName == pendingToggle?.targetPackage) {
            performPendingToggle(packageName)
        }

        if (texts.any { text -> RIDE_ACTIVE_MARKERS.any { marker -> text.contains(marker) } }) {
            logBoth("Ride-active screen detected, package=$packageName")
            startTripIfNeeded(packageName, extractDropAddress(texts))
        }

        if (isRideEndedScreen(packageName, texts)) {
            logBoth("Ride-ended screen detected, package=$packageName")
            stopTripIfNeeded(packageName)
        }

        extractDailyEarnings(packageName, texts)?.let { amount ->
            logBoth("Daily earnings detected pkg=$packageName amount=$amount")
            DailyEarnings.record(this, packageName, amount)
        }
    }

    private fun isRideEndedScreen(packageName: String, texts: List<String>): Boolean {
        val required = RIDE_ENDED_MARKERS_BY_PACKAGE[packageName] ?: return false
        return required.all { marker -> texts.any { it.contains(marker) } }
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

        val otherPkg = otherPackage(packageName)
        if (otherPkg != null) {
            triggerToggle(target = otherPkg, searchText = "ON", returnTo = packageName)
        }
    }

    // Closes the overlay (via FareTrackingService's own stop path -- same
    // one TripScreen's Stop Trip button and the intercept flow already use)
    // and, if this service put the other app offline for this trip, brings
    // it back online.
    private fun stopTripIfNeeded(justEndedPackage: String) {
        if (FareTrackingService.activeSnapshot == null) {
            // Nothing to stop -- avoids re-triggering the online toggle on
            // every subsequent window event this same ended screen fires.
            return
        }

        logBoth("Stopping trip (ride-ended screen detected on $justEndedPackage)")
        val stopIntent = Intent(this, FareTrackingService::class.java).apply {
            action = FareTrackingService.ACTION_STOP
        }
        startService(stopIntent)

        val toBringOnline = offlinedPackage
        if (toBringOnline != null) {
            triggerToggle(target = toBringOnline, searchText = "OFF", returnTo = justEndedPackage)
        }
    }

    // Foregrounds `target` and, once its window settles, taps whatever node
    // shows exactly `searchText` ("ON" or "OFF") to flip its status.
    private fun triggerToggle(target: String, searchText: String, returnTo: String?) {
        logBoth("Foregrounding $target to tap its '$searchText' toggle")
        pendingToggle = PendingToggle(targetPackage = target, searchText = searchText, returnToPackage = returnTo)
        launchApp(target)
    }

    private fun otherPackage(packageName: String): String? = when (packageName) {
        "production.ttrides.driver" -> "product.allridi.driver"
        "product.allridi.driver" -> "production.ttrides.driver"
        else -> null
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            logBoth("launchApp: no launch intent for $packageName")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        logBoth("launchApp: launched $packageName")
    }

    // Best-effort, not yet confirmed against a real toggle in either app --
    // the toggle has no stable id (same story as driverStartRideBtn
    // earlier), so this searches for a node whose own text is exactly
    // pending.searchText and climbs to its nearest clickable ancestor
    // (matching the pattern of custom touch wrappers seen elsewhere in
    // these apps). Retries across up to MAX_TOGGLE_ATTEMPTS settled
    // screens, since the target app may still be mid-launch (splash/
    // loading) the first time this fires. Diagnostic logging will show
    // what's actually there if this doesn't match on a real ride.
    private fun performPendingToggle(packageName: String) {
        val pending = pendingToggle ?: return
        pending.attempts++

        val root = rootInActiveWindow
        val toggleNode = root?.let { findToggleNode(it, pending.searchText) }
        root?.recycle()

        if (toggleNode != null) {
            val result = toggleNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            toggleNode.recycle()
            logBoth(
                "toggle: tapped '${pending.searchText}' for $packageName (attempt ${pending.attempts}), result=$result",
            )
            pendingToggle = null
            offlinedPackage = if (pending.searchText == "ON") packageName else null

            // Give the tap a moment to register before switching away, so we
            // don't interrupt the app's own transition out of its old state.
            val returnTo = pending.returnToPackage
            if (returnTo != null) {
                Handler(Looper.getMainLooper()).postDelayed({ launchApp(returnTo) }, 1500)
            }
            return
        }

        logBoth("toggle: '${pending.searchText}' not found for $packageName (attempt ${pending.attempts})")
        if (pending.attempts >= MAX_TOGGLE_ATTEMPTS) {
            logBoth("toggle: giving up for $packageName after ${pending.attempts} attempts")
            pendingToggle = null
        }
    }

    // Searches for a node whose own text is exactly `text` (not a substring
    // match, e.g. "ON" won't match "ONLINE"), then climbs its parent chain
    // looking for the nearest clickable ancestor -- the actual toggle is
    // very likely a custom touch container wrapping a plain label, the same
    // pattern seen with driverStartRideBtn. Returns an un-recycled node the
    // caller owns; all other nodes visited are recycled internally.
    private fun findToggleNode(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(text) ?: return null
        for (match in matches) {
            val isExactMatch = match.text?.toString()?.trim()?.equals(text, ignoreCase = true) == true
            if (!isExactMatch) {
                match.recycle()
                continue
            }

            var current: AccessibilityNodeInfo? = match
            var depth = 0
            while (current != null && depth < 6) {
                if (current.isClickable) {
                    return current
                }
                val parent = current.parent
                if (current !== match) current.recycle()
                current = parent
                depth++
            }
            match.recycle()
        }
        return null
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
