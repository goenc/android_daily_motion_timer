package com.goenc.dailymotiontimer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class WalkingPhaseOverlay(private val context: Context) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var overlayView: View? = null
    private var phaseTextView: TextView? = null
    private var remainingTextView: TextView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    fun showOrUpdate(state: TimerUiState) {
        if (!Settings.canDrawOverlays(context)) {
            hide()
            return
        }

        val view = overlayView ?: createOverlayView().also { newView ->
            runCatching {
                val layoutParams = createLayoutParams()
                overlayLayoutParams = layoutParams
                windowManager.addView(newView, layoutParams)
                overlayView = newView
            }.onFailure {
                hide()
                return
            }
        }
        updateText(state)
        view.visibility = View.VISIBLE
    }

    fun hide() {
        val view = overlayView ?: return
        runCatching {
            windowManager.removeView(view)
        }
        overlayView = null
        phaseTextView = null
        remainingTextView = null
        overlayLayoutParams = null
    }

    private fun createOverlayView(): View {
        return FrameLayout(context).apply {
            setPadding(18.dp, 12.dp, 10.dp, 14.dp)
            background = GradientDrawable().apply {
                cornerRadius = 18.dp.toFloat()
                setColor(Color.argb(230, 24, 32, 40))
                setStroke(1.dp, Color.argb(180, 255, 255, 255))
            }
            elevation = 8.dp.toFloat()
            setOnClickListener {
                openMainActivity()
            }
            setOnTouchListener(DragTouchListener())

            val contentLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(6.dp, 0, 6.dp, 0)
            }
            val fixedPhaseLabelWidth: Int
            phaseTextView = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val width = slowLabelWidthPx(paint)
                fixedPhaseLabelWidth = width
                minWidth = width
                maxWidth = width
            }
            contentLayout.minimumWidth = fixedPhaseLabelWidth + CONTENT_HORIZONTAL_EXTRA_WIDTH_PX
            remainingTextView = TextView(context).apply {
                setTextColor(Color.argb(235, 255, 255, 255))
                textSize = 16f
                gravity = Gravity.CENTER
            }
            contentLayout.addView(remainingTextView)
            contentLayout.addView(phaseTextView)
            addView(contentLayout)
        }
    }

    private fun updateText(state: TimerUiState) {
        phaseTextView?.let { textView ->
            when (state.currentPhase) {
                WalkingPhase.Slow -> {
                    textView.text = "ゆっくり"
                    textView.setPadding(
                        SLOW_LABEL_HORIZONTAL_PADDING_PIXELS,
                        0,
                        SLOW_LABEL_HORIZONTAL_PADDING_PIXELS,
                        0,
                    )
                }
                WalkingPhase.Fast -> {
                    textView.text = "はやく"
                    textView.setPadding(
                        SLOW_LABEL_HORIZONTAL_PADDING_PIXELS,
                        0,
                        SLOW_LABEL_HORIZONTAL_PADDING_PIXELS,
                        0,
                    )
                }
            }
        }
        remainingTextView?.text = state.formattedRemainingTime
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(POSITION_X_KEY, defaultOverlayX())
            y = preferences.getInt(POSITION_Y_KEY, defaultOverlayY())
        }
    }

    private fun openMainActivity() {
        hide()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isDragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val layoutParams = overlayLayoutParams ?: return false
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && kotlin.math.abs(dx) < DRAG_START_THRESHOLD_PIXELS &&
                        kotlin.math.abs(dy) < DRAG_START_THRESHOLD_PIXELS
                    ) {
                        return true
                    }
                    isDragging = true
                    layoutParams.x = (initialX + dx).coerceAtLeast(0)
                    layoutParams.y = (initialY + dy).coerceAtLeast(0)
                    runCatching {
                        windowManager.updateViewLayout(view, layoutParams)
                        persistOverlayPosition(layoutParams.x, layoutParams.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    private fun slowLabelWidthPx(paint: android.graphics.Paint): Int {
        val textWidth = paint.measureText("ゆっくり").toInt()
        return textWidth + (SLOW_LABEL_HORIZONTAL_PADDING_PIXELS * 2)
    }

    private fun defaultOverlayX(): Int {
        return (context.resources.displayMetrics.widthPixels - 220.dp).coerceAtLeast(16.dp)
    }

    private fun defaultOverlayY(): Int {
        return 80.dp
    }

    private fun persistOverlayPosition(x: Int, y: Int) {
        preferences.edit()
            .putInt(POSITION_X_KEY, x)
            .putInt(POSITION_Y_KEY, y)
            .apply()
    }

    private companion object {
        const val DRAG_START_THRESHOLD_PIXELS = 8
        const val SLOW_LABEL_HORIZONTAL_PADDING_PIXELS = 10
        const val CONTENT_HORIZONTAL_EXTRA_WIDTH_PX = 24
        const val PREFERENCES_NAME = "walking_phase_overlay"
        const val POSITION_X_KEY = "position_x"
        const val POSITION_Y_KEY = "position_y"
    }
}
