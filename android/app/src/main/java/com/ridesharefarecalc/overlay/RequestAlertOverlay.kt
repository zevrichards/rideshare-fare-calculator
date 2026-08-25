package com.ridesharefarecalc.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * A brief, full-screen colored flash signaling an incoming ride request's
 * distance at a glance -- red for far (skip it), green for close (grab it).
 * Purely a visual cue: FLAG_NOT_TOUCHABLE means it never intercepts taps, so
 * the driver can still tap Accept/Reject straight through it. Doesn't touch
 * the request itself, unlike an auto-accept/reject tool would.
 *
 * Reuses the same SYSTEM_ALERT_WINDOW permission already granted for the
 * fare overlay -- no new permission needed.
 */
object RequestAlertOverlay {
    enum class AlertColor { RED, GREEN }

    private const val FLASH_DURATION_MS = 1800L

    fun flash(context: Context, color: AlertColor) {
        if (!Settings.canDrawOverlays(context)) return

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val view = View(context).apply {
            setBackgroundColor(
                when (color) {
                    AlertColor.RED -> Color.argb(140, 220, 30, 30)
                    AlertColor.GREEN -> Color.argb(140, 30, 180, 60)
                },
            )
        }

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // Already removed, or the window's gone -- nothing to clean up.
            }
        }, FLASH_DURATION_MS)
    }
}
