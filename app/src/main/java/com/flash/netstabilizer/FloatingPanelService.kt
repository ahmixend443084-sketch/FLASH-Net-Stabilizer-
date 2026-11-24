
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class FloatingPanelService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var mainHandler: Handler

    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var statusView: TextView? = null
    private var connectionView: TextView? = null
    private var latencyView: TextView? = null
    private var monitorButton: Button? = null

    private var expanded = false
    private var monitoring = false
    private var monitor: NetworkMonitor? = null

    private val green = Color.rgb(0, 255, 145)
    private val dark = Color.rgb(15, 20, 26)

    private val monitorTask = object : Runnable {
        override fun run() {
            if (!monitoring) return

            monitor?.checkConnection { result ->
                mainHandler.post {
                    if (!monitoring) return@post

                    connectionView?.text =
                        "Connection: ${result.transport}"

                    latencyView?.text =
                        "Internet check: " +
                        (if (result.internetValidated) "Available" else "Unconfirmed") +
                        "\nProbe time: " +
                        (result.latencyMs?.let { "$it ms" } ?: "Unavailable") +
                        "\n${result.message}"
                }
            }

            mainHandler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        startFlashForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        monitor = NetworkMonitor(this)
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

        if (bubble == null && panel == null) {
            showBubble()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "flash_net_channel",
                "FLASH Net Stabilizer",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startFlashForeground() {
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "flash_net_channel")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("FLASH Net Stabilizer")
            .setContentText("Floating network monitor is running")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                101,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(101, notification)
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
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(2, green)
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

        var downX = 0f
        var downY = 0f
        var originalX = 0
        var originalY = 0
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originalX = params.x
                    originalY = params.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()

                    if (kotlin.math.abs(dx) > 8 ||
                        kotlin.math.abs(dy) > 8
                    ) dragged = true

                    if (dragged) {
                        params.x = originalX + dx
                        params.y = originalY + dy
                        runCatching {
                            windowManager.updateViewLayout(view, params)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) showPanel()
                    true
                }

                else -> true
            }
        }

        windowManager.addView(view, params)
    }

    private fun showPanel() {
        if (expanded) return

        bubble?.let {
            runCatching { windowManager.removeView(it) }
        }
        bubble = null

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

        connectionView = TextView(this).apply {
            text = "Connection: Not checked"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 4)
        }
        root.addView(connectionView)

        latencyView = TextView(this).apply {
            text = "Internet check: Not started"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 10)
        }
        root.addView(latencyView)

        statusView = TextView(this).apply {
            text = "Monitoring is OFF"
            textSize = 13f
            setTextColor(green)
        }
        root.addView(statusView)

        monitorButton = Button(this).apply {
            text = "MONITORING: OFF"
            setOnClickListener {
                monitoring = !monitoring

                text = if (monitoring) {
                    "MONITORING: ON"
                } else {
                    "MONITORING: OFF"
                }

                if (monitoring) {
                    statusView?.text = "Checking connection..."
                    mainHandler.removeCallbacks(monitorTask)
                    monitorTask.run()
                } else {
                    mainHandler.removeCallbacks(monitorTask)
                    statusView?.text = "Monitoring stopped"
                }
            }
        }
        root.addView(monitorButton)

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

        val scroll = ScrollView(this).apply {
            addView(root)
        }

        val params = createParams().apply {
            width = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        panel = root
        expanded = true
        windowManager.addView(scroll, params)
    }

    private fun hidePanelToBubble() {
        // Remove the actual view attached to WindowManager.
        panel?.let { root ->
            val parent = root.parent
            if (parent is View) {
                runCatching { windowManager.removeView(parent) }
            } else {
                runCatching { windowManager.removeView(root) }
            }
        }

        panel = null
        statusView = null
        connectionView = null
        latencyView = null
        monitorButton = null
        expanded = false

        showBubble()
    }

    override fun onDestroy() {
        monitoring = false
        mainHandler.removeCallbacks(monitorTask)
        monitor?.close()
        monitor = null

        bubble?.let {
            runCatching { windowManager.removeView(it) }
        }

        panel?.let { root ->
            val parent = root.parent
            if (parent is View) {
                runCatching { windowManager.removeView(parent) }
            } else {
                runCatching { windowManager.removeView(root) }
            }
        }

        bubble = null
        panel = null
        expanded = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
