
package com.flash.netstabilizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
import kotlin.math.abs

class FloatingPanelService : Service() {

    private lateinit var windowManager: WindowManager

    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var expanded = false
    private var autoMatch = false
    private var statusView: TextView? = null

    private val green = Color.rgb(0, 255, 145)
    private val dark = Color.rgb(15, 20, 26)

    companion object {
        private const val CHANNEL_ID = "flash_stabilizer_channel"
        private const val NOTIFICATION_ID = 101
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startFlashForeground()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (!Settings.canDrawOverlays(this)) {
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FLASH Net Stabilizer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the FLASH floating panel running"
            }

            val manager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun startFlashForeground() {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("FLASH Net Stabilizer")
            .setContentText("Floating controls are running")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createParams(): WindowManager.LayoutParams {
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

    private fun makeBackground(
        color: Int,
        radius: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(2, green)
        }
    }

    private fun showBubble() {
        if (bubble != null) return

        val view = TextView(this).apply {
            text = "⚡"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(green)
            background = makeBackground(dark, 60f)
            setPadding(18, 12, 18, 12)
            contentDescription = "Open FLASH controls"
        }

        val params = createParams()
        bubble = view

        enableBubbleTouch(view, params)
        windowManager.addView(view, params)
    }

    private fun showPanel() {
        if (expanded) return

        bubble?.let { oldBubble ->
            runCatching { windowManager.removeView(oldBubble) }
            bubble = null
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = makeBackground(dark, 24f)
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

        root.addView(Button(this).apply {
            text = if (autoMatch) "AUTO MATCH: ON" else "AUTO MATCH: OFF"

            setOnClickListener {
                autoMatch = !autoMatch

                text = if (autoMatch) {
                    "AUTO MATCH: ON"
                } else {
                    "AUTO MATCH: OFF"
                }

                statusView?.text = if (autoMatch) {
                    "Auto mode ON. Server matching is not implemented yet."
                } else {
                    "Auto mode OFF"
                }
            }
        })

        root.addView(Button(this).apply {
            text = "HIDE TO BUBBLE"

            setOnClickListener {
                hidePanelToBubble()
            }
        })

        root.addView(Button(this).apply {
            text = "CLOSE FLASH"

            setOnClickListener {
                stopSelf()
            }
        })

        val params = createParams().apply {
            width = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        panel = root
        expanded = true

        windowManager.addView(root, params)
    }

    private fun hidePanelToBubble() {
        panel?.let { oldPanel ->
            runCatching { windowManager.removeView(oldPanel) }
        }

        panel = null
        statusView = null
        expanded = false

        showBubble()
    }

    private fun enableBubbleTouch(
        view: View,
        params: WindowManager.LayoutParams
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()

                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                    }

                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy

                        runCatching {
                            windowManager.updateViewLayout(view, params)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        showPanel()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
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
        statusView = null
        expanded = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
