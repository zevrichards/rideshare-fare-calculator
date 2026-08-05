package com.ridesharefarecalc.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A small draggable "chat head"-style card rendered via WindowManager, on
 * top of whatever app is currently in the foreground. The stop button is a
 * distinct child view so it receives its own taps even while the card body
 * handles drag gestures (Android delivers touches to children before the
 * parent's OnTouchListener sees them). Tapping the card body itself (not the
 * stop button, and not while dragging) invokes onTap, e.g. to bring the app
 * to the foreground.
 */
class FareOverlayView(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onStop: () -> Unit,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val estimatedText: TextView
    private val runningText: TextView
    private val metaText: TextView
    private val rootView: LinearLayout

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 200
    }

    private var isAttached = false

    init {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        estimatedText = TextView(context).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 11f
        }
        runningText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        metaText = TextView(context).apply {
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 10f
        }
        val stopButton = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            setOnClickListener { onStop() }
        }

        val textStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(estimatedText)
            addView(runningText)
            addView(metaText)
        }

        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E6202124"))
            }
            addView(textStack)
            addView(stopButton)
        }

        attachDragHandling()
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandling() {
        var downRawX = 0f
        var downRawY = 0f
        var downParamX = 0
        var downParamY = 0
        var dragged = false
        val dragThresholdPx = context.resources.displayMetrics.density * 8

        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamX = layoutParams.x
                    downParamY = layoutParams.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > dragThresholdPx || abs(dy) > dragThresholdPx) {
                        dragged = true
                        layoutParams.x = downParamX + dx.toInt()
                        layoutParams.y = downParamY + dy.toInt()
                        if (isAttached) {
                            windowManager.updateViewLayout(rootView, layoutParams)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        onTap()
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun show() {
        if (isAttached) return
        windowManager.addView(rootView, layoutParams)
        isAttached = true
    }

    fun update(estimatedTotal: Double?, runningTotal: Double, distanceKm: Double, elapsedMinutes: Double) {
        estimatedText.text = if (estimatedTotal != null) {
            "Est \$${"%.2f".format(estimatedTotal)}"
        } else {
            ""
        }
        runningText.text = "\$${"%.2f".format(runningTotal)}"
        metaText.text = "${"%.1f".format(distanceKm)}km · ${"%.0f".format(elapsedMinutes)}min"
    }

    fun remove() {
        if (!isAttached) return
        windowManager.removeView(rootView)
        isAttached = false
    }
}
