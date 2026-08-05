package com.ridesharefarecalc.overlay

import com.ridesharefarecalc.BuildConfig
import com.ridesharefarecalc.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

// A point-in-time read of the running trip, exposed to JS via
// FareOverlayModule.getActiveTrip()/onTripTick so TripScreen can drive its
// breakdown UI from this service instead of its own separate GPS watch --
// including adopting a trip that was started by NavigationInterceptActivity,
// which JS never otherwise learns about. Ships the pre-trip estimate's
// distance/time inputs rather than a pre-computed total so JS can run its
// own calculateFareBreakdown and get a real Base/Distance/Time breakdown,
// not just a dollar figure with no components -- both null together if no
// destination was available to estimate from.
data class TripSnapshot(
    val distanceKm: Double,
    val elapsedMinutes: Double,
    val rateCardId: String,
    val surgeMultiplier: Double,
    val estimatedDistanceKm: Double?,
    val estimatedMinutes: Double?,
)

class FareTrackingService : Service() {

    companion object {
        const val ACTION_STOP = "com.ridesharefarecalc.overlay.action.STOP"
        const val EXTRA_DEST_LAT = "destLat"
        const val EXTRA_DEST_LNG = "destLng"

        private const val CHANNEL_ID = "fare_tracking"
        private const val NOTIFICATION_ID = 4201
        private const val MIN_USABLE_ACCURACY_METERS = 50f

        // Set by FareOverlayModule while the RN app is alive so it can relay the
        // final trip summary back to JS. The service still runs fine without a
        // listener attached (e.g. if the app process was backgrounded/killed).
        @Volatile
        var tripCompletedListener: ((distanceKm: Double, minutes: Double, total: Double) -> Unit)? = null

        // null means no trip is currently running -- read by
        // FareOverlayModule.getActiveTrip() for a synchronous snapshot, and
        // updated every tick alongside tripTickListener for live updates.
        @Volatile
        var activeSnapshot: TripSnapshot? = null

        @Volatile
        var tripTickListener: ((TripSnapshot) -> Unit)? = null
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var overlayView: FareOverlayView? = null
    private val tickHandler = Handler(Looper.getMainLooper())

    private var estimatedTotal: Double? = null
    private var estimatedDistanceKm: Double? = null
    private var estimatedMinutes: Double? = null
    private var lastLocation: Location? = null
    private var distanceKm = 0.0
    private var startTimeMs = 0L
    private var activeRateCard: RateCard = RateCard.TTRS
    private var surgeMultiplier: Double = 1.0

    // Guards against the background routing lookup in resolveEstimate()
    // landing after the trip has already been stopped and re-showing a
    // notification that startTracking's caller believes is gone.
    @Volatile
    private var isTracking = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            onNewLocation(result.lastLocation ?: return)
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateOverlay()
            tickHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
        } else {
            startTracking(intent)
        }
        return START_NOT_STICKY
    }

    private fun startTracking(intent: Intent?) {
        distanceKm = 0.0
        lastLocation = null
        estimatedTotal = null
        estimatedDistanceKm = null
        estimatedMinutes = null
        startTimeMs = System.currentTimeMillis()
        isTracking = true
        // Read fresh each trip -- the driver may switch rate card or adjust
        // surge between trips without restarting the app.
        activeRateCard = RateCardPreference.getSelectedRateCard(this)
        surgeMultiplier = RateCardPreference.getSurgeMultiplier(this)

        // Seeded synchronously (not just inside the first tick) so a
        // getActiveTrip() call landing in the gap before the first tick
        // still correctly reports a running trip instead of null.
        activeSnapshot = TripSnapshot(0.0, 0.0, activeRateCard.id, surgeMultiplier, null, null)

        startForeground(NOTIFICATION_ID, buildNotification(0.0))
        showOverlayIfPermitted()
        requestInitialEstimate(intent)

        val locationRequest = LocationRequest.Builder(3000L)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

        tickHandler.post(tickRunnable)
    }

    private fun showOverlayIfPermitted() {
        if (!canDrawOverlay()) return
        try {
            overlayView = FareOverlayView(
                context = this,
                onTap = { bringAppToForeground() },
                onStop = { stopTracking() },
            ).also { it.show() }
        } catch (_: SecurityException) {
            overlayView = null
        }
    }

    private fun bringAppToForeground() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestInitialEstimate(intent: Intent?) {
        val destLat = intent?.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN)?.takeIf { !it.isNaN() }
        val destLng = intent?.getDoubleExtra(EXTRA_DEST_LNG, Double.NaN)?.takeIf { !it.isNaN() }
        if (destLat == null || destLng == null) return

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { origin ->
                if (origin == null) return@addOnSuccessListener
                resolveEstimate(origin.latitude, origin.longitude, destLat, destLng)
            }
    }

    // Straight-line*1.3 distance with an assumed-average-speed time guess is
    // the immediate, guaranteed-available estimate; if a routes API key is
    // configured we then refine it with real, traffic-aware road
    // distance/duration on a background thread and update the overlay again
    // once it lands.
    private fun resolveEstimate(originLat: Double, originLng: Double, destLat: Double, destLng: Double) {
        val straightLineKm = FareMath.haversineDistanceKm(originLat, originLng, destLat, destLng)
        val roadDistanceEstimateKm = straightLineKm * 1.3
        val assumedMinutes = (roadDistanceEstimateKm / FareMath.ASSUMED_AVERAGE_SPEED_KMH) * 60.0
        applyEstimate(roadDistanceEstimateKm, assumedMinutes)
        updateOverlay()

        val apiKey = BuildConfig.GOOGLE_ROUTES_API_KEY
        if (apiKey.isEmpty()) return

        Thread {
            val route = RoutesApiClient.fetchRouteEstimate(originLat, originLng, destLat, destLng, apiKey)
            if (route != null && isTracking) {
                applyEstimate(route.distanceKm, route.durationMinutes)
                tickHandler.post { if (isTracking) updateOverlay() }
            }
        }.start()
    }

    private fun applyEstimate(distanceKm: Double, minutes: Double) {
        estimatedDistanceKm = distanceKm
        estimatedMinutes = minutes
        estimatedTotal = FareMath.calculateFare(activeRateCard, distanceKm, minutes, surgeMultiplier)
    }

    private fun onNewLocation(location: Location) {
        val previous = lastLocation
        val isNoisyFix = previous != null && location.accuracy > MIN_USABLE_ACCURACY_METERS
        if (!isNoisyFix) {
            if (previous != null) {
                distanceKm += FareMath.haversineDistanceKm(
                    previous.latitude, previous.longitude, location.latitude, location.longitude,
                )
            }
            lastLocation = location
        }
        updateOverlay()
    }

    private fun updateOverlay() {
        val elapsedMinutes = (System.currentTimeMillis() - startTimeMs) / 60000.0
        val runningTotal = FareMath.calculateFare(activeRateCard, distanceKm, elapsedMinutes, surgeMultiplier)
        try {
            overlayView?.update(estimatedTotal, runningTotal, distanceKm, elapsedMinutes)
        } catch (_: SecurityException) {
            overlayView = null
        }
        updateNotification(runningTotal)

        val snapshot = TripSnapshot(
            distanceKm, elapsedMinutes, activeRateCard.id, surgeMultiplier, estimatedDistanceKm, estimatedMinutes,
        )
        activeSnapshot = snapshot
        tripTickListener?.invoke(snapshot)
    }

    private fun stopTracking() {
        val elapsedMinutes = (System.currentTimeMillis() - startTimeMs) / 60000.0
        val finalTotal = FareMath.calculateFare(activeRateCard, distanceKm, elapsedMinutes, surgeMultiplier)

        teardown()
        tripCompletedListener?.invoke(distanceKm, elapsedMinutes, finalTotal)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun teardown() {
        isTracking = false
        activeSnapshot = null
        tickHandler.removeCallbacks(tickRunnable)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        try {
            overlayView?.remove()
        } catch (_: SecurityException) {
            // Overlay permission may have been revoked mid-trip; nothing to clean up.
        }
        overlayView = null
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fare Tracking",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(runningTotal: Double): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Trip in progress")
            .setContentText("Running total: \$${"%.2f".format(runningTotal)}")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun updateNotification(runningTotal: Double) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(runningTotal))
    }
}
