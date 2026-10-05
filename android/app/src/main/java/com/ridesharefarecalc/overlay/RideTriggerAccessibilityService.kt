package com.ridesharefarecalc.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.ridesharefarecalc.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opt-in detector for driver actions:
 * - Confirms pickup & tracks active trip lifecycle.
 * - Auto-accepts or auto-rejects incoming trip requests by pickup distance thresholds.
 * - Manages cross-app online/offline toggling upon trip start and after payment dismissal.
 *
 * Scoped via res/xml/accessibility_service_config.xml:
 * - TTRS Driver: production.ttrides.driver
 * - AllRiDi Driver: product.allridi.driver
 */
class RideTriggerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RideTrigger"

        // View IDs for UI node matching
        private const val START_RIDE_BTN_ID = "driverStartRideBtn"
        private const val DROP_ADDRESS_ID = "textViewCustomerDropAddress"
        private const val TTRS_MENU_BTN_ID = "menuBtn"

        // Screen text markers indicating an active ride is underway
        private val RIDE_ACTIVE_MARKERS = listOf("Distance Covered", "Distance Driven")

        // Screen text markers indicating a completed trip payment summary
        private val RIDE_ENDED_MARKERS_BY_PACKAGE = mapOf(
            "production.ttrides.driver" to listOf("Ride Complete"),
            "product.allridi.driver" to listOf("Form of payment", "Trip fare"),
        )

        // Text labels on buttons that dismiss the payment/summary screen
        private val PAYMENT_DISMISS_BUTTON_TEXTS = listOf(
            "Done", "Close", "OK", "Submit", "Collect Payment", "Complete"
        )

        // Text markers indicating driver has returned to main home/map screen
        private val HOME_MAP_MARKERS = listOf(
            "Online", "OFFLINE", "Go Offline", "Go Online", "Searching for rides"
        )

        // Internal rate card mapping
        private val RATE_CARD_ID_BY_PACKAGE = mapOf(
            "production.ttrides.driver" to "ttrs",
            "product.allridi.driver" to "allridi",
        )

        private const val MAX_TOGGLE_ATTEMPTS = 6
        private const val MAX_NAV_BUTTON_ATTEMPTS = 6

        private val surgeTextPattern = Regex("""^[xX×]\s*(\d+(\.\d+)?)$""")

        // Allridi's incoming-request screen shows the surge as a standalone "x1.1" text.
        fun parseSurgeMultiplier(texts: List<String>): Double? =
            texts.firstNotNullOfOrNull { surgeTextPattern.find(it.trim())?.groupValues?.get(1)?.toDoubleOrNull() }

        data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
            val width get() = right - left
            val height get() = bottom - top
            val centerX get() = (left + right) / 2
            val centerY get() = (top + bottom) / 2
        }

        // Small square-ish control (5-20% of screen width) on the right side of the screen.
        fun isNavArrowCandidate(box: Box, screenWidth: Int): Boolean {
            val min = screenWidth * 5 / 100
            val max = screenWidth * 20 / 100
            return box.width in min..max && box.height in min..max && box.centerX > screenWidth * 70 / 100
        }

        // Lowest candidate wins, then the rightmost among those at about the same height
        // (3% of screen width tolerance): picks the arrow over the locate-me button above it
        // and over Allridi's call button beside it.
        fun pickNavArrowIndex(boxes: List<Box>, screenWidth: Int): Int? {
            if (boxes.isEmpty()) return null
            val lowest = boxes.maxOf { it.centerY }
            val tolerance = screenWidth * 3 / 100
            return boxes.indices
                .filter { boxes[it].centerY >= lowest - tolerance }
                .maxByOrNull { boxes[it].centerX }
        }

        // Best-guess contentDescription substrings for the icon-only "start navigation"
        // button on the ride-active screen. Unconfirmed -- if this misses, the diagnostic
        // log dump of clickable icon descriptors will show the real one to hardcode.
        private val NAV_BUTTON_DESC_CANDIDATES = listOf(
            "navigate", "navigation", "start navigation", "directions", "waze", "google maps", "maps"
        )
        private val NAV_BUTTON_ID_CANDIDATES = listOf("navigat", "direction")
    }

    /**
     * Tracks pending app toggling state.
     */
    private data class PendingToggle(
        val targetPackage: String,
        val searchText: String,
        val returnToPackage: String?,
        var attempts: Int = 0,
        var openedDrawer: Boolean = false,
        val pressNavOnReturn: Boolean = false,
    )

    private var pendingToggle: PendingToggle? = null
    private var offlinedPackage: String? = null
    private var lastAlertedRequestKey: String? = null

    // Set once we've returned to the ride-active app after toggling the other app offline;
    // triggers a one-shot attempt to tap the "start navigation" icon button.
    private var pendingNavButtonPackage: String? = null
    private var navButtonAttempts = 0

    // Surge from the most recent Allridi request screen, applied when that trip starts
    // (the surge text isn't shown on the ride-active screen).
    private var lastSeenAllridiSurge: Double? = null

    // Allridi shows a payment summary (Close) and then a rate-the-rider screen (Close again).
    private var allridiCloseClicks = 0

    private var lastLoggedWindowKey: String? = null
    
    // Flag to wait for payment screen dismissal before restoring the other app online
    private var pendingPaymentDismissalPackage: String? = null

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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleWindowStateChanged(event, packageName)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event, packageName)
        }
    }

    private fun handleClick(event: AccessibilityEvent, packageName: String) {
        val source = event.source
        val viewId = source?.viewIdResourceName
        val text = source?.text?.toString()?.trim()
        val contentDesc = source?.contentDescription?.toString()?.trim()
        logBoth("click pkg=$packageName viewId=$viewId text=$text desc=$contentDesc class=${event.className}")

        val isStartRideClick = viewId == "$packageName:id/$START_RIDE_BTN_ID"
        source?.recycle()

        if (isStartRideClick) {
            logBoth("Start Ride click matched (legacy id path), package=$packageName")
            startTripIfNeeded(packageName)
            return
        }

        // Check if the user clicked a Done/Close button on the payment screen.
        // Allridi has a second "rate the rider" screen (with its own Close button) after
        // the payment summary's Close, so only the second dismiss click counts there.
        if (pendingPaymentDismissalPackage == packageName) {
            val clickedText = text ?: contentDesc ?: ""
            val isAllridi = packageName == "product.allridi.driver"
            val isDismissClick = PAYMENT_DISMISS_BUTTON_TEXTS.any { it.equals(clickedText, ignoreCase = true) } ||
                (isAllridi && contentDesc?.contains("close", ignoreCase = true) == true)
            if (isDismissClick) {
                if (isAllridi) {
                    allridiCloseClicks++
                    logBoth("Allridi dismiss click #$allridiCloseClicks ('$clickedText')")
                    if (allridiCloseClicks >= 2) {
                        triggerPendingPostPaymentOnlineToggle(packageName)
                    }
                } else {
                    logBoth("Driver clicked '$clickedText' to dismiss payment screen on $packageName")
                    triggerPendingPostPaymentOnlineToggle(packageName)
                }
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent, packageName: String) {
        val eventText = event.text?.joinToString(" | ")
        val texts = collectScreenTexts(packageName)
        val windowKey = "$packageName|${event.className}|$eventText|${describeScreen(texts)}"
        if (windowKey != lastLoggedWindowKey) {
            lastLoggedWindowKey = windowKey
            logBoth("window changed pkg=$packageName class=${event.className} text=$eventText")
            logBoth("  screen context: ${describeScreen(texts)}")
        }

        if (packageName == pendingToggle?.targetPackage) {
            performPendingToggle(packageName)
        }

        if (packageName == pendingNavButtonPackage) {
            attemptPressNavigationButton(packageName)
        }

        if (texts.any { text -> RIDE_ACTIVE_MARKERS.any { marker -> text.contains(marker) } }) {
            logBoth("Ride-active screen detected, package=$packageName")
            startTripIfNeeded(packageName, extractDropAddress(texts))
        }

        if (isRideEndedScreen(packageName, texts)) {
            logBoth("Ride-ended screen detected, package=$packageName")
            stopTripIfNeeded(packageName)
        }

        // If waiting for payment dismissal, check if driver has returned to the main map screen
        if (pendingPaymentDismissalPackage == packageName) {
            // Allridi's online home screen shows just "ON" (offline: "OFF"), none of the markers.
            val returnedToMap = texts.any { text -> HOME_MAP_MARKERS.any { marker -> text.contains(marker, ignoreCase = true) } } ||
                (packageName == "product.allridi.driver" && texts.any { it == "ON" || it == "OFF" })
            val isPaymentScreenStillVisible = isRideEndedScreen(packageName, texts)
            
            if (returnedToMap && !isPaymentScreenStillVisible) {
                logBoth("Main map screen detected after payment on $packageName. Restoring other app online.")
                triggerPendingPostPaymentOnlineToggle(packageName)
            }
        }

        extractDailyEarnings(packageName, texts)?.let { amount ->
            logBoth("Daily earnings detected pkg=$packageName amount=$amount")
            DailyEarnings.record(this, packageName, amount)
        }

        handleIncomingRequest(packageName, texts)
    }

    private fun isRideEndedScreen(packageName: String, texts: List<String>): Boolean {
        val required = RIDE_ENDED_MARKERS_BY_PACKAGE[packageName] ?: return false
        return required.all { marker -> texts.any { it.contains(marker) } }
    }

    private fun handleIncomingRequest(packageName: String, texts: List<String>) {
        val distanceKm = detectIncomingRequestDistanceKm(packageName, texts) ?: run {
            lastAlertedRequestKey = null
            return
        }

        if (packageName == "product.allridi.driver") {
            lastSeenAllridiSurge = parseSurgeMultiplier(texts) ?: 1.0
        }

        val key ="$packageName:$distanceKm"
        if (key == lastAlertedRequestKey) return
        lastAlertedRequestKey = key

        val farThresholdKm = RateCardPreference.getFarRequestThresholdKm(this)
        val nearThresholdKm = RateCardPreference.getNearRequestThresholdKm(this)

        val actionText = when {
            distanceKm < nearThresholdKm -> "Accept"
            distanceKm > farThresholdKm -> if (packageName == "production.ttrides.driver") "Cancel" else "REJECT"
            else -> null
        } ?: return

        val root = rootInActiveWindow ?: return
        val actionBtn = findClickableActionButton(root, actionText)
        root.recycle()

        if (actionBtn != null) {
            val result = actionBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            actionBtn.recycle()
            // logBoth("Tapped '$actionText' for $packageName (dist: ${distanceKm}km), result=$result")
        } else {
            // logBoth("Could not immediately find '$actionText' on $packageName. Scheduling retry...")
            
            Handler(Looper.getMainLooper()).postDelayed({
                val retryRoot = rootInActiveWindow ?: return@postDelayed
                val retryBtn = findClickableActionButton(retryRoot, actionText)
                retryRoot.recycle()
                if (retryBtn != null) {
                    val res = retryBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    retryBtn.recycle()
                    // logBoth("Retry tapped '$actionText' for $packageName, result=$res")
                } else {
                    // logBoth("Retry failed to find '$actionText' on $packageName")
                }
            }, 300)
        }
    }

    private fun detectIncomingRequestDistanceKm(packageName: String, texts: List<String>): Double? =
        when (packageName) {
            "production.ttrides.driver" -> {
                val hasAccept = texts.any { it.equals("Accept", ignoreCase = true) }
                val awayText = texts.firstOrNull { kmAwayPattern.containsMatchIn(it) }
                if (hasAccept && awayText != null) {
                    kmAwayPattern.find(awayText)?.groupValues?.get(1)?.toDoubleOrNull()
                } else {
                    null
                }
            }
            "product.allridi.driver" -> {
                val hasAccept = texts.any { it.equals("Accept", ignoreCase = true) }
                val hasReject = texts.any { it.equals("REJECT", ignoreCase = true) }
                val kmText = texts.firstOrNull { kmEntryPattern.matches(it.trim()) }
                if (hasAccept && hasReject && kmText != null) {
                    parseCurrency(kmText)
                } else {
                    null
                }
            }
            else -> null
        }

    private fun startTripIfNeeded(packageName: String, screenDropAddress: String? = null) {
        if (FareTrackingService.activeSnapshot != null) {
            logBoth("Ignoring: a trip is already active")
            return
        }

        val dropAddress = screenDropAddress ?: findDropAddress(packageName)
        logBoth("dropAddress=$dropAddress")
        logClickableNodes("ride start $packageName")

        RateCardPreference.setSelectedRateCardId(this, RATE_CARD_ID_BY_PACKAGE.getValue(packageName))

        if (packageName == "product.allridi.driver") {
            lastSeenAllridiSurge?.let { surge ->
                RateCardPreference.setSurgeMultiplier(this, surge)
                logBoth("Applied Allridi surge x$surge from the request screen")
            }
            lastSeenAllridiSurge = null
        }

        ContextCompat.startForegroundService(this, Intent(this, FareTrackingService::class.java))

        if (!dropAddress.isNullOrBlank()) {
            geocodeAndAttach(dropAddress)
        }

        val otherPkg = otherPackage(packageName)
        if (otherPkg != null) {
            triggerToggle(target = otherPkg, searchText = "ON", returnTo = packageName, pressNavOnReturn = true)
        }
    }

    /**
     * Stops active trip fare recording immediately, but defers toggling the competing app 
     * back online until the driver taps 'Done/Close' or returns to the main map screen.
     */
    private fun stopTripIfNeeded(justEndedPackage: String) {
        if (FareTrackingService.activeSnapshot == null) {
            return
        }

        logBoth("Stopping trip calculation (payment summary detected on $justEndedPackage)")
        val stopIntent = Intent(this, FareTrackingService::class.java).apply {
            action = FareTrackingService.ACTION_STOP
        }
        startService(stopIntent)

        // Set pending dismissal flag so we wait for driver to close the summary screen
        pendingPaymentDismissalPackage = justEndedPackage
        allridiCloseClicks = 0
    }

    /**
     * Restores the secondary app online once payment screen dismissal is confirmed.
     */
    private fun triggerPendingPostPaymentOnlineToggle(justEndedPackage: String) {
        pendingPaymentDismissalPackage = null
        val toBringOnline = offlinedPackage ?: otherPackage(justEndedPackage)
        if (toBringOnline != null) {
            logBoth("Restoring $toBringOnline online post-payment dismissal")
            triggerToggle(target = toBringOnline, searchText = "OFF", returnTo = justEndedPackage)
        }
    }

    private fun triggerToggle(target: String, searchText: String, returnTo: String?, pressNavOnReturn: Boolean = false) {
        logBoth("Foregrounding $target to tap its '$searchText' toggle")
        pendingToggle = PendingToggle(
            targetPackage = target,
            searchText = searchText,
            returnToPackage = returnTo,
            pressNavOnReturn = pressNavOnReturn,
        )
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

    /**
     * Executes online/offline toggles with robust delays for navigation drawer animations.
     */
    private fun performPendingToggle(packageName: String) {
        val pending = pendingToggle ?: return
        pending.attempts++

        val root = rootInActiveWindow
        if (root == null) {
            logBoth("toggle: no root for $packageName (attempt ${pending.attempts})")
            scheduleNextToggleRetry(packageName)
            giveUpIfExhausted(pending)
            return
        }

        // When TTRS is offline its home screen has a big GO ONLINE button -- simpler and
        // more reliable than the drawer toggle, so try it first.
        if (packageName == "production.ttrides.driver" && pending.searchText == "OFF") {
            val goOnlineBtn = findClickableActionButton(root, "GO ONLINE")
            if (goOnlineBtn != null) {
                val result = goOnlineBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                goOnlineBtn.recycle()
                root.recycle()
                logBoth("toggle: tapped 'GO ONLINE' for $packageName (attempt ${pending.attempts}), result=$result")
                finishToggle(pending, packageName)
                return
            }
        }

        // Handle opening TTRS side navigation drawer
        if (packageName == "production.ttrides.driver" && !pending.openedDrawer) {
            val menuBtn = root.findAccessibilityNodeInfosByViewId("$packageName:id/$TTRS_MENU_BTN_ID")?.firstOrNull()
            if (menuBtn != null) {
                menuBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                menuBtn.recycle()
                pending.openedDrawer = true
                logBoth("toggle: opened TTRS drawer (attempt ${pending.attempts}), awaiting menu animation...")
                root.recycle()
                
                // Allow 400ms for drawer animation before scanning for toggle
                Handler(Looper.getMainLooper()).postDelayed({
                    performPendingToggle(packageName)
                }, 400)
                return
            } else {
                logBoth("toggle: TTRS menuBtn not found yet (attempt ${pending.attempts})")
            }
        }

        val toggleNode = findToggleNode(root, pending.searchText, packageName)
        root.recycle()

        if (toggleNode != null) {
            val result = toggleNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            toggleNode.recycle()
            logBoth(
                "toggle: tapped '${pending.searchText}' for $packageName (attempt ${pending.attempts}), result=$result",
            )
            finishToggle(pending, packageName)
            return
        }

        logBoth("toggle: '${pending.searchText}' not found for $packageName (attempt ${pending.attempts})")
        scheduleNextToggleRetry(packageName)
        giveUpIfExhausted(pending)
    }

    private fun finishToggle(pending: PendingToggle, packageName: String) {
        pendingToggle = null
        offlinedPackage = if (pending.searchText == "ON") packageName else null

        val returnTo = pending.returnToPackage
        if (returnTo != null) {
            val pressNavOnReturn = pending.pressNavOnReturn
            Handler(Looper.getMainLooper()).postDelayed({
                launchApp(returnTo)
                if (pressNavOnReturn) {
                    pendingNavButtonPackage = returnTo
                    navButtonAttempts = 0
                }
            }, 1500)
        }
    }

    private fun scheduleNextToggleRetry(packageName: String) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (pendingToggle?.targetPackage == packageName) {
                performPendingToggle(packageName)
            }
        }, 500)
    }

    private fun giveUpIfExhausted(pending: PendingToggle) {
        if (pending.attempts >= MAX_TOGGLE_ATTEMPTS) {
            logBoth("toggle: giving up for ${pending.targetPackage} after ${pending.attempts} attempts")
            pendingToggle = null
        }
    }

    /**
     * One-shot (with retries) attempt to tap the icon-only "start navigation" button on the
     * ride-active screen, after we've returned from toggling the other app offline.
     */
    private fun attemptPressNavigationButton(packageName: String) {
        navButtonAttempts++
        val root = rootInActiveWindow
        if (root == null) {
            logBoth("navBtn: no root for $packageName (attempt $navButtonAttempts)")
            scheduleNavButtonRetryOrGiveUp(packageName)
            return
        }

        // Neither app gives the arrow an id or description (logs show ImageButton, id=null,
        // desc=null), so fall back to "small unlabeled clickable at the lower right", but only
        // while the ride-active screen is actually showing.
        val onRideActiveScreen = collectScreenTexts(packageName)
            .any { text -> RIDE_ACTIVE_MARKERS.any { marker -> text.contains(marker) } }
        val navBtn = findNavigationButton(root)
            ?: if (onRideActiveScreen) findNavigationButtonByGeometry(root) else null
        if (navBtn != null) {
            val result = navBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            navBtn.recycle()
            root.recycle()
            logBoth("navBtn: tapped navigation button for $packageName (attempt $navButtonAttempts), result=$result")
            pendingNavButtonPackage = null
            return
        }

        // Diagnostic dump so the real contentDescription can be identified if the guess misses.
        val descriptors = mutableListOf<String>()
        collectClickableDescriptors(root, descriptors)
        root.recycle()
        logBoth("navBtn: no match on attempt $navButtonAttempts for $packageName. Clickable icons: ${descriptors.joinToString(" | ")}")

        scheduleNavButtonRetryOrGiveUp(packageName)
    }

    private fun logClickableNodes(tag: String) {
        val root = rootInActiveWindow ?: return
        val descriptors = mutableListOf<String>()
        collectClickableDescriptors(root, descriptors)
        root.recycle()
        logBoth("clickables ($tag): ${descriptors.joinToString(" | ")}")
    }

    private fun scheduleNavButtonRetryOrGiveUp(packageName: String) {
        if (navButtonAttempts >= MAX_NAV_BUTTON_ATTEMPTS) {
            logBoth("navBtn: giving up for $packageName after $navButtonAttempts attempts")
            pendingNavButtonPackage = null
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (pendingNavButtonPackage == packageName) {
                attemptPressNavigationButton(packageName)
            }
        }, 500)
    }

    private fun findNavigationButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.US)
        val viewId = node.viewIdResourceName?.lowercase(Locale.US)
        val descMatches = desc != null && NAV_BUTTON_DESC_CANDIDATES.any { desc.contains(it) }
        val idMatches = viewId != null && NAV_BUTTON_ID_CANDIDATES.any { viewId.contains(it) }
        if (descMatches || idMatches) {
            var current: AccessibilityNodeInfo? = node
            var depth = 0
            while (current != null && depth < 4) {
                if (current.isClickable) {
                    return current
                }
                current = current.parent
                depth++
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNavigationButton(child)
            if (result != null) {
                if (result !== child) child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    private fun findNavigationButtonByGeometry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screen = android.graphics.Rect()
        root.getBoundsInScreen(screen)
        val screenWidth = screen.width()
        if (screenWidth <= 0) return null

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectSmallUnlabeledClickables(root, screenWidth, candidates)

        val bestIndex = pickNavArrowIndex(candidates.map { toBox(boundsOf(it)) }, screenWidth)
        val best = bestIndex?.let { candidates[it] }
        candidates.filter { it !== best }.forEach { it.recycle() }
        if (best != null) {
            logBoth("navBtn: geometry fallback chose bounds=${boundsOf(best).flattenToString()}")
        }
        return best
    }

    private fun toBox(rect: android.graphics.Rect) = Box(rect.left, rect.top, rect.right, rect.bottom)

    private fun boundsOf(node: AccessibilityNodeInfo): android.graphics.Rect =
        android.graphics.Rect().also { node.getBoundsInScreen(it) }

    private fun collectSmallUnlabeledClickables(
        node: AccessibilityNodeInfo,
        screenWidth: Int,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val matches = child.isClickable &&
                child.text.isNullOrEmpty() &&
                child.contentDescription.isNullOrEmpty() &&
                isNavArrowCandidate(toBox(boundsOf(child)), screenWidth)
            collectSmallUnlabeledClickables(child, screenWidth, out)
            if (matches) out.add(child) else child.recycle()
        }
    }

    private fun collectClickableDescriptors(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (out.size >= 30) return
        if (node.isClickable) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val cls = node.className?.toString()?.substringAfterLast('.')
            out.add(
                "class=$cls id=${node.viewIdResourceName} desc=${node.contentDescription} " +
                    "text=${node.text} bounds=${bounds.flattenToString()} children=${node.childCount}",
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableDescriptors(child, out)
            child.recycle()
        }
    }

    private fun findClickableActionButton(root: AccessibilityNodeInfo, buttonText: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(buttonText) ?: return null
        for (match in matches) {
            val ownText = match.text?.toString()?.trim()
            if (ownText.equals(buttonText, ignoreCase = true)) {
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
            } else {
                match.recycle()
            }
        }
        return null
    }

    private fun findToggleNode(root: AccessibilityNodeInfo, text: String, packageName: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(text) ?: return null
        for (match in matches) {
            val ownText = match.text?.toString()?.trim()
            val isMatch = if (packageName == "production.ttrides.driver") {
                ownText?.endsWith(" $text", ignoreCase = true) == true || ownText.equals(text, ignoreCase = true)
            } else {
                ownText.equals(text, ignoreCase = true)
            }
            if (!isMatch) {
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

    // Events from a background app (TTRS fires constantly) arrive while the other app is on
    // screen; reading the active window then would attribute that screen to the wrong app.
    private fun collectScreenTexts(packageName: String): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        try {
            if (root.packageName?.toString() != packageName) return emptyList()
            collectText(root, texts)
        } finally {
            root.recycle()
        }
        return texts
    }

    private fun describeScreen(texts: List<String>): String =
        if (texts.isEmpty()) "(no visible text found)" else texts.take(12).joinToString(" | ")

    private val kmEntryPattern = Regex("""^\d+(\.\d+)?\s*km$""")
    private val kmAwayPattern = Regex("""(\d+(\.\d+)?)\s*km\s*away""", RegexOption.IGNORE_CASE)

    private fun extractDropAddress(texts: List<String>): String? {
        val dropOffLabelIndex = texts.indexOfFirst { it.equals("Drop off at", ignoreCase = true) }
        if (dropOffLabelIndex >= 0 && dropOffLabelIndex + 1 < texts.size) {
            return texts[dropOffLabelIndex + 1]
        }

        val kmIndex = texts.indexOfFirst { kmEntryPattern.matches(it.trim()) }
        if (kmIndex <= 0) return null
        return texts[kmIndex - 1]
    }

    private fun extractDailyEarnings(packageName: String, texts: List<String>): Double? =
        when (packageName) {
            "product.allridi.driver" -> extractAllridiDailyEarnings(texts)
            "production.ttrides.driver" -> extractTtrsDailyEarnings(texts)
            else -> null
        }

    private fun extractAllridiDailyEarnings(texts: List<String>): Double? {
        val todayLabel = SimpleDateFormat("EEE, dd/MM", Locale.US).format(Date())
        val labelIndex = texts.indexOfFirst { it.equals(todayLabel, ignoreCase = true) }
        if (labelIndex < 0 || labelIndex + 1 >= texts.size) return null
        return parseCurrency(texts[labelIndex + 1])
    }

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