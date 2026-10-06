package com.ridesharefarecalc.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

        // TTRS's splash screen alone can take ~4s after launchApp, so budget by time, not attempts.
        private const val TOGGLE_TIMEOUT_MS = 15_000L
        private const val NAV_BUTTON_TIMEOUT_MS = 8_000L
        private const val POST_CLOSE_WATCH_MS = 120_000L

        // The icon-only navigation arrow on each app's ride-active screen (ids taken from
        // the clickable-node dump logged at ride start).
        private val NAV_BUTTON_ID_BY_PACKAGE = mapOf(
            "production.ttrides.driver" to "buttonDriverNavigation",
            "product.allridi.driver" to "buttonDriverNavigationEndRide",
        )

        private val surgeTextPattern = Regex("""^[xX×]\s*(\d+(\.\d+)?)$""")

        // Allridi's incoming-request screen shows the surge as a standalone "x1.1" text.
        fun parseSurgeMultiplier(texts: List<String>): Double? =
            texts.firstNotNullOfOrNull { surgeTextPattern.find(it.trim())?.groupValues?.get(1)?.toDoubleOrNull() }

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
        val startedAt: Long = SystemClock.elapsedRealtime(),
        var retryScheduled: Boolean = false,
    )

    private var pendingToggle: PendingToggle? = null
    private var offlinedPackage: String? = null
    private var lastAlertedRequestKey: String? = null

    // Set once we've returned to the ride-active app after toggling the other app offline;
    // triggers a one-shot attempt to tap the "start navigation" icon button.
    private var pendingNavButtonPackage: String? = null
    private var navButtonAttempts = 0
    private var navButtonStartedAt = 0L
    private var navRetryScheduled = false

    // Surge from the most recent Allridi request screen, applied when that trip starts
    // (the surge text isn't shown on the ride-active screen).
    private var lastSeenAllridiSurge: Double? = null

    // Allridi shows a payment summary (Close) and then a rate-the-rider screen (Close again).
    private var allridiCloseClicks = 0
    private var postCloseWatchRunning = false

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
                    } else {
                        startPostCloseWatch(packageName)
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
        if (pendingPaymentDismissalPackage == packageName && isBackAtMap(packageName, texts)) {
            logBoth("Main map screen detected after payment on $packageName. Restoring other app online.")
            triggerPendingPostPaymentOnlineToggle(packageName)
        }

        // After closing Allridi's payment screen, the driver is already in the other app:
        // they want it back online, so don't wait for an Allridi screen that may never come.
        val dismissPending = pendingPaymentDismissalPackage
        if (dismissPending == "product.allridi.driver" &&
            allridiCloseClicks >= 1 &&
            packageName == otherPackage(dismissPending) &&
            texts.isNotEmpty()
        ) {
            logBoth("Driver moved to $packageName after closing the payment screen. Restoring it online.")
            triggerPendingPostPaymentOnlineToggle(dismissPending)
        }

        extractDailyEarnings(packageName, texts)?.let { amount ->
            logBoth("Daily earnings detected pkg=$packageName amount=$amount")
            DailyEarnings.record(this, packageName, amount)
        }

        handleIncomingRequest(packageName, texts)
    }

    // Allridi's online home screen shows just "ON" (offline: "OFF"), none of the markers.
    private fun isBackAtMap(packageName: String, texts: List<String>): Boolean {
        val atMap = texts.any { text -> HOME_MAP_MARKERS.any { marker -> text.contains(marker, ignoreCase = true) } } ||
            (packageName == "product.allridi.driver" && texts.any { it == "ON" || it == "OFF" })
        return atMap && !isRideEndedScreen(packageName, texts)
    }

    // The close taps and the home screen don't always produce events (a ride's log went
    // quiet for 13s after the first Close), so also poll the screen after that Close.
    private fun startPostCloseWatch(packageName: String) {
        if (postCloseWatchRunning) return
        postCloseWatchRunning = true
        val deadline = SystemClock.elapsedRealtime() + POST_CLOSE_WATCH_MS
        var lastContext: String? = null
        var dumpedClickables = false
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (pendingPaymentDismissalPackage != packageName || SystemClock.elapsedRealtime() > deadline) {
                    postCloseWatchRunning = false
                    return
                }
                val texts = collectScreenTexts(packageName)
                val context = describeScreen(texts)
                if (texts.isNotEmpty() && context != lastContext) {
                    lastContext = context
                    logBoth("post-close screen: $context")
                    if (!dumpedClickables) {
                        dumpedClickables = true
                        logClickableNodes("post-close $packageName")
                    }
                }
                if (isBackAtMap(packageName, texts)) {
                    postCloseWatchRunning = false
                    logBoth("Post-close watch: back at the map on $packageName. Restoring other app online.")
                    triggerPendingPostPaymentOnlineToggle(packageName)
                    return
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(tick, 1000)
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
            retryOrGiveUp(pending)
            return
        }

        // When TTRS is offline its home screen has a big GO ONLINE button -- simpler and
        // more reliable than the drawer toggle, so try it first.
        if (packageName == "production.ttrides.driver") {
            val goOnlineBtn = findClickableActionButton(root, "GO ONLINE")
            if (goOnlineBtn != null) {
                if (pending.searchText == "OFF") {
                    val result = goOnlineBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    logBoth("toggle: tapped 'GO ONLINE' for $packageName (attempt ${pending.attempts}), result=$result")
                } else {
                    logBoth("toggle: $packageName already offline (GO ONLINE showing), nothing to tap (attempt ${pending.attempts})")
                }
                goOnlineBtn.recycle()
                root.recycle()
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
        // The label shows the app's current state, so the opposite label means it's already
        // where we want it (e.g. the driver took it offline by hand first).
        val oppositeText = if (pending.searchText == "ON") "OFF" else "ON"
        val alreadyThereNode = if (toggleNode == null) findToggleNode(root, oppositeText, packageName) else null
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

        if (alreadyThereNode != null) {
            alreadyThereNode.recycle()
            logBoth("toggle: $packageName already shows '$oppositeText', nothing to tap (attempt ${pending.attempts})")
            finishToggle(pending, packageName)
            return
        }

        logBoth("toggle: '${pending.searchText}' not found for $packageName (attempt ${pending.attempts})")
        retryOrGiveUp(pending)
    }

    private fun finishToggle(pending: PendingToggle, packageName: String) {
        pendingToggle = null
        offlinedPackage = if (pending.searchText == "ON") packageName else null
        returnToOriginalApp(pending)
    }

    private fun returnToOriginalApp(pending: PendingToggle) {
        val returnTo = pending.returnToPackage ?: return
        val pressNavOnReturn = pending.pressNavOnReturn
        Handler(Looper.getMainLooper()).postDelayed({
            launchApp(returnTo)
            if (pressNavOnReturn) {
                pendingNavButtonPackage = returnTo
                navButtonAttempts = 0
                navButtonStartedAt = SystemClock.elapsedRealtime()
                navRetryScheduled = false
            }
        }, 1500)
    }

    private fun retryOrGiveUp(pending: PendingToggle) {
        if (SystemClock.elapsedRealtime() - pending.startedAt >= TOGGLE_TIMEOUT_MS) {
            logBoth("toggle: giving up for ${pending.targetPackage} after ${pending.attempts} attempts")
            pendingToggle = null
            returnToOriginalApp(pending)
            return
        }
        if (pending.retryScheduled) return
        pending.retryScheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            pending.retryScheduled = false
            if (pendingToggle === pending) {
                performPendingToggle(pending.targetPackage)
            }
        }, 500)
    }

    /**
     * One-shot (with retries) attempt to tap the icon-only "start navigation" button on the
     * ride-active screen, after we've returned from toggling the other app offline.
     */
    private fun attemptPressNavigationButton(packageName: String) {
        navButtonAttempts++
        val root = rootInActiveWindow
        val buttonId = NAV_BUTTON_ID_BY_PACKAGE[packageName]

        if (root != null && buttonId != null && root.packageName?.toString() == packageName) {
            val matches = root.findAccessibilityNodeInfosByViewId("$packageName:id/$buttonId")
            val navBtn = matches?.firstOrNull { it.isClickable }
            matches?.filter { it !== navBtn }?.forEach { it.recycle() }
            if (navBtn != null) {
                val result = navBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                navBtn.recycle()
                root.recycle()
                logBoth("navBtn: tapped navigation button for $packageName (attempt $navButtonAttempts), result=$result")
                pendingNavButtonPackage = null
                return
            }
        }

        if (SystemClock.elapsedRealtime() - navButtonStartedAt >= NAV_BUTTON_TIMEOUT_MS) {
            val descriptors = mutableListOf<String>()
            if (root != null) collectClickableDescriptors(root, descriptors)
            logBoth("navBtn: giving up for $packageName after $navButtonAttempts attempts. Clickables: ${descriptors.joinToString(" | ")}")
            root?.recycle()
            pendingNavButtonPackage = null
            return
        }

        root?.recycle()
        if (navRetryScheduled) return
        navRetryScheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            navRetryScheduled = false
            if (pendingNavButtonPackage == packageName) {
                attemptPressNavigationButton(packageName)
            }
        }, 500)
    }

    private fun logClickableNodes(tag: String) {
        val root = rootInActiveWindow ?: return
        val descriptors = mutableListOf<String>()
        collectClickableDescriptors(root, descriptors)
        root.recycle()
        logBoth("clickables ($tag): ${descriptors.joinToString(" | ")}")
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
        // With no destination set the preceding text is a label, not an address.
        val candidate = texts[kmIndex - 1]
        if (RIDE_ACTIVE_MARKERS.any { candidate.contains(it) } || candidate.equals("Enter Destination", ignoreCase = true)) {
            return null
        }
        return candidate
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