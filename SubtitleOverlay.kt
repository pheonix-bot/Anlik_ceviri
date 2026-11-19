package com.krunit.screentranslate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class SubtitleOverlay(
    private val context: Context,
    private val onStopRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootContainer: LinearLayout? = null
    private var subtitleView: TextView? = null
    private var isShowing = false
    private var lastDisplayed = ""

    @SuppressLint("ClickableViewAccessibility")
    fun show(initialText: String = "Canlı altyazı hazır — ekranda İngilizce metin bekleniyor...") {
        if (isShowing) return

        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#D90F172A"))
            cornerRadius = 28f
            setStroke(2, Color.parseColor("#38BDF8"))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(32, 18, 24, 18)
        }

        val textView = TextView(context).apply {
            text = initialText
            setTextColor(Color.WHITE)
            textSize = 15.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = TextView(context).apply {
            text = "  ✕"
            setTextColor(Color.parseColor("#FACC15"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(16, 4, 8, 4)
            setOnClickListener { onStopRequested() }
        }

        container.addView(textView)
        container.addView(closeBtn)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val screenWidth = context.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            (screenWidth * 0.90).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        var initialY = 0
        var initialTouchY = 0f
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (initialTouchY - event.rawY).toInt()
                    params.y = (initialY + deltaY).coerceAtLeast(20)
                    windowManager.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(container, params)
        rootContainer = container
        subtitleView = textView
        isShowing = true
    }

    fun getOverlayView(): View? = rootContainer

    fun updateSubtitle(translatedText: String) {
        val clean = translatedText.trim()
        if (clean.isEmpty() || clean == lastDisplayed) return
        lastDisplayed = clean
        subtitleView?.post {
            subtitleView?.text = clean
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            rootContainer?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        rootContainer = null
        subtitleView = null
        isShowing = false
        lastDisplayed = ""
    }
}
