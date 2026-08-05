package com.ridesharefarecalc.overlay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * Registered for geo:/google.navigation: intents. Only fires if the sending
 * app used an implicit intent with no explicit package/component target --
 * Android resolves explicit intents directly to their target and never
 * consults this activity's intent-filter at all. No UI: captures the
 * destination, starts the tracking service, hands off to the real nav app,
 * and finishes immediately.
 */
class NavigationInterceptActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        val destination = uri?.let(DestinationParser::parse)

        // Track even when the destination couldn't be parsed -- the running
        // total (distance/time based) doesn't need one, only the pre-trip
        // "Estimated Total" does, and the overlay already renders fine
        // without it (see FareOverlayView.update's null handling).
        startTrackingService(destination)

        if (uri != null) {
            relaunchRealNavigationApp(uri)
        }

        finish()
    }

    private fun startTrackingService(destination: Destination?) {
        val serviceIntent = Intent(this, FareTrackingService::class.java).apply {
            if (destination != null) {
                putExtra(FareTrackingService.EXTRA_DEST_LAT, destination.latitude)
                putExtra(FareTrackingService.EXTRA_DEST_LNG, destination.longitude)
            }
        }
        try {
            startForegroundService(serviceIntent)
        } catch (_: IllegalStateException) {
            // Most likely ForegroundServiceStartNotAllowedException: location
            // permission wasn't granted before this background-triggered
            // launch. Skip tracking for this trip; the hand-off to the real
            // nav app below still happens so the driver isn't blocked.
        }
    }

    private fun relaunchRealNavigationApp(uri: Uri) {
        for (packageName in NavPreference.candidatePackages(this)) {
            if (!isPackageInstalled(packageName)) continue

            val navIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(navIntent)
                return
            } catch (_: ActivityNotFoundException) {
                // That app doesn't actually handle this URI; try the next one.
            }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
