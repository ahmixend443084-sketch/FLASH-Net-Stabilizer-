
package com.flash.netstabilizer

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class FloatingPanelService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var expanded = false
    private var autoMatch = false
    private var statusView: TextView? = null

    private val green = Color.rgb(0, 255, 145)
    private val dark = Color.rgb(15, 20, 26)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (Build.VERSION.SDK_INT >= 23 &&
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        if (bubble == null && panel == null) {
            showBubble()
        }

        return START_NOT_STICKY
    }

    private fun params(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 250
        }
    }

    private fun background(color: Int, radius: Float) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(2, green)
        }

    private fun showBubble() {
        val view = TextView(this).apply {
            text = "⚡"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(green)
            background = background(dark, 60f)
            setPadding(18, 12, 18, 12)
            contentDescription = "Open FLASH controls"
        }

        val p = params()
        bubble = view
        bubbleParams = p

        view.setOnClickListener {
            showPanel()
        }

        enableDrag(view, p)

        windowManager.addView(view, p)
    }

    private fun showPanel() {
        if (expanded) return

        bubble?.let {
            windowManager.removeView(it)
            bubble = null
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = background(dark, 24f)
        }

        root.addView(TextView(this).apply {
            text = "⚡ FLASH"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
        })

        root.addView(TextView(this).apply {
            text = "NET STABILIZER"
            textSize = 13f
            setTextColor(Color.WHITE)
        })

        statusView = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 14, 0, 10)
        }
        root.addView(statusView)

        val matchButton = Button(this).apply {
            text = "AUTO MATCH: OFF"
            setOnClickListener {
                autoMatch = !autoMatch
                text = if (autoMatch) {
                    "AUTO MATCH: ON"
                } else {
                    "AUTO MATCH: OFF"
                }

                statusView?.text = if (autoMatch) {
                    "Monitoring requested; server matching engine not connected"
                } else {
                    "Auto mode stopped"
                }
            }
        }
        root.addView(matchButton)

        root.addView(Button(this).apply {
            text = "HIDE TO BUBBLE"
            setOnClickListener {
                showBubble()
            }
        })

        root.addView(Button(this).apply {
            text = "CLOSE FLASH"
            setOnClickListener {
                stopSelf()
            }
        })

        val p = params().apply {
            width = (resources.displayMetrics.widthPixels * 0.78f)
                .toInt()
            gravity = Gravity.CENTER
        }

        panel = root
        panelParams = p
        expanded = true

        windowManager.addView(root, p)
    }

    private fun enableDrag(
        view: View,
        p: WindowManager.LayoutParams
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchX = event.rawX
                    touchY = event.rawY
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (event.rawX - touchX).toInt()
                    p.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, p)
                    true
                }

                else -> false
            }
        }
    }

    override fun onDestroy() {
        bubble?.let {
            runCatching { windowManager.removeView(it) }
        }
        panel?.let {
            runCatching { windowManager.removeView(it) }
        }

        bubble = null
        panel = null
        expanded = false

        super.onDestroy()
    }
}
