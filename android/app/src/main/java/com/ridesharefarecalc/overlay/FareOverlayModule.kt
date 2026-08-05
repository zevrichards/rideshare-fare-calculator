package com.ridesharefarecalc.overlay

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import org.json.JSONObject

private fun TripSnapshot.toWritableMap(): WritableMap = Arguments.createMap().apply {
    putDouble("distanceKm", distanceKm)
    putDouble("elapsedMinutes", elapsedMinutes)
    putString("rateCardId", rateCardId)
    putDouble("surgeMultiplier", surgeMultiplier)
    if (estimatedDistanceKm != null) putDouble("estimatedDistanceKm", estimatedDistanceKm) else putNull("estimatedDistanceKm")
    if (estimatedMinutes != null) putDouble("estimatedMinutes", estimatedMinutes) else putNull("estimatedMinutes")
}

class FareOverlayModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        // Only fires if RN has actually booted in this process (e.g. the
        // user opened the app UI at some point) -- FareTrackingService and
        // NavigationInterceptActivity are plain Android classes that never
        // touch ReactHost, so a purely intercepted trip (app never opened)
        // won't have a listener registered here at all. That's fine: this
        // event only drives trip-history persistence, which isn't needed
        // (the actual rideshare app tracks trip/fare history already).
        FareTrackingService.tripCompletedListener = { distanceKm, minutes, total ->
            val params = Arguments.createMap().apply {
                putDouble("distanceKm", distanceKm)
                putDouble("minutes", minutes)
                putDouble("total", total)
            }
            reactApplicationContext.emitDeviceEvent("onTripCompleted", params)
        }

        // Live updates for an in-progress trip, whether it was started from
        // this app's own Start Trip button or from a nav-intercept that JS
        // never otherwise learns about (see getActiveTrip below for the
        // one-time snapshot used when TripScreen first mounts/resumes).
        FareTrackingService.tripTickListener = { snapshot ->
            reactApplicationContext.emitDeviceEvent("onTripTick", snapshot.toWritableMap())
        }
    }

    override fun getName(): String = "FareOverlay"

    @ReactMethod
    fun hasOverlayPermission(promise: Promise) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(reactApplicationContext)
        promise.resolve(granted)
    }

    @ReactMethod
    fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${reactApplicationContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            reactApplicationContext.startActivity(intent)
        }
    }

    @ReactMethod
    fun hasLocationPermission(promise: Promise) {
        promise.resolve(hasFineLocationPermission())
    }

    @ReactMethod
    fun startTrip(destLat: Double, destLng: Double, promise: Promise) {
        if (!hasFineLocationPermission()) {
            promise.reject("NO_LOCATION_PERMISSION", "ACCESS_FINE_LOCATION not granted")
            return
        }

        val intent = Intent(reactApplicationContext, FareTrackingService::class.java).apply {
            putExtra(FareTrackingService.EXTRA_DEST_LAT, destLat)
            putExtra(FareTrackingService.EXTRA_DEST_LNG, destLng)
        }
        ContextCompat.startForegroundService(reactApplicationContext, intent)
        promise.resolve(true)
    }

    @ReactMethod
    fun stopTrip() {
        val intent = Intent(reactApplicationContext, FareTrackingService::class.java).apply {
            action = FareTrackingService.ACTION_STOP
        }
        reactApplicationContext.startService(intent)
    }

    // One-time snapshot for TripScreen to adopt a trip already in progress
    // when it mounts/resumes (e.g. the overlay was tapped for a trip that
    // started via nav-intercept, which never otherwise touches JS). Ongoing
    // updates after that come from the onTripTick event instead.
    @ReactMethod
    fun getActiveTrip(promise: Promise) {
        val snapshot = FareTrackingService.activeSnapshot
        promise.resolve(snapshot?.toWritableMap())
    }

    // Mirrors JS's rate card selection/edits/surge into native
    // SharedPreferences (see RateCardPreference) so FareTrackingService can
    // read them even when a trip is only ever driven by an intercepted nav
    // intent and RN never boots. JS's AsyncStorage copy (src/lib/rateCards.ts)
    // remains the source of truth for the app's own UI; these calls are a
    // write-through, not a two-way sync.
    @ReactMethod
    fun setSelectedRateCard(id: String) {
        RateCardPreference.setSelectedRateCardId(reactApplicationContext, id)
    }

    @ReactMethod
    fun setRateCard(cardJson: String) {
        try {
            RateCardPreference.setRateCard(reactApplicationContext, RateCard.fromJson(JSONObject(cardJson)))
        } catch (_: Exception) {
            // Malformed payload from JS -- leave the previously stored card as-is.
        }
    }

    @ReactMethod
    fun setSurgeMultiplier(value: Double) {
        RateCardPreference.setSurgeMultiplier(reactApplicationContext, value)
    }

    @ReactMethod
    fun getPreferredNavApp(promise: Promise) {
        promise.resolve(NavPreference.getPreferredPackage(reactApplicationContext))
    }

    @ReactMethod
    fun setPreferredNavApp(packageName: String) {
        NavPreference.setPreferredPackage(reactApplicationContext, packageName)
    }

    @ReactMethod
    fun openDefaultAppSettings() {
        val packageUri = Uri.parse("package:${reactApplicationContext.packageName}")
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        val intent = Intent(action, packageUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactApplicationContext.startActivity(intent)
    }

    // Required boilerplate for NativeEventEmitter on the JS side. Events are
    // pushed directly via FareTrackingService.tripCompletedListener above,
    // not through a JS-driven subscribe/unsubscribe count.
    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            reactApplicationContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}
