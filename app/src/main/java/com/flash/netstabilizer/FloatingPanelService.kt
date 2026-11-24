
package com.flash.netstabilizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
    private lateinit var monitor: NetworkMonitor

    private val handler = Handler(Looper.getMainLooper())

    private var bubble: TextView? = null
    private var panel: ScrollView? = null
    private var monitoring = false
    private var expanded = false
    private var destroyed = false

    private var connectionText: TextView? = null
    private var latencyText: TextView? = null
    private var averageText: TextView? = null
    private var jitterText: TextView? = null
    private var failureText: TextView? = null
    private var statusText: TextView? = null
    private var toggleButton: Button? = null

    private val monitorTask = object : Runnable {
        override fun run() {
            if (!monitoring || destroyed) return

            monitor.checkConnection { status ->
                handler.post {
                    if (!destroyed && monitoring) {
                        updatePanel(status)
                    }
                }
            }

            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()

        windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        monitor = NetworkMonitor(this)

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

        if (bubble == null && panel == null) {
            showBubble()
        }

        return START_STICKY
    }

    private fun startFlashForeground() {
        val channelId = "flash_net_monitor"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "FLASH Network Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FLASH Net Stabilizer")
            .setContentText("Floating network monitor is running")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                101,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(101, notification)
        }
    }

    private fun createBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun makeText(
        text: String,
        size: Float,
        color: Int = Color.WHITE
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(12, 8, 12, 8)
        }
    }

    private fun makeButton(
        text: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun layoutParams(
        width: Int,
        height: Int,
        gravity: Int
    ): WindowManager.LayoutParams {
        val windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        return WindowManager.LayoutParams(
            width,
            height,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }
    }

    private fun showBubble() {
        if (destroyed || bubble != null || expanded) return
        if (!Settings.canDrawOverlays(this)) return

        val view = TextView(this).apply {
            text = "⚡"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = createBackground(
                Color.rgb(20, 145, 255),
                60f
            )
            elevation = 12f
        }

        val params = layoutParams(
            58,
            58,
            Gravity.TOP or Gravity.START
        ).apply {
            x = 0
            y = 220
        }

        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()

                        if (kotlin.math.abs(dx) > 8 ||
                            kotlin.math.abs(dy) > 8
                        ) {
                            moved = true
                        }

                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)

                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            openPanel()
                        }
                        return true
                    }
                }
                return true
            }
        })

        try {
            windowManager.addView(view, params)
            bubble = view
        } catch (_: Exception) {
            bubble = null
        }
    }

    private fun openPanel() {
        if (destroyed || expanded) return

        removeBubble()
        showPanel()
    }

    private fun showPanel() {
        if (destroyed || expanded) return
        if (!Settings.canDrawOverlays(this)) return

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            background = createBackground(
                Color.rgb(24, 29, 40),
                22f
            )
        }

        content.addView(
            makeText(
                "FLASH NET STABILIZER",
                19f
            ).apply {
                typeface = Typeface.DEFAULT_BOLD
            }
        )

        content.addView(
            makeText(
                "Network diagnostics",
                13f,
                Color.LTGRAY
            )
        )

        connectionText = makeText("Connection: checking...", 14f)
        latencyText = makeText("Probe time: --", 14f)
        averageText = makeText("Average probe time: --", 14f)
        jitterText = makeText("Jitter estimate: --", 14f)
        failureText = makeText("Failed probes: --", 14f)
        statusText = makeText("Monitoring is OFF", 13f, Color.YELLOW)

        content.addView(connectionText)
        content.addView(latencyText)
        content.addView(averageText)
        content.addView(jitterText)
        content.addView(failureText)
        content.addView(statusText)

        toggleButton = makeButton("START MONITORING") {
            toggleMonitoring()
        }

        content.addView(toggleButton)

        content.addView(
            makeButton("HIDE TO BUBBLE") {
                hidePanelToBubble()
            }
        )

        content.addView(
            makeButton("CLOSE FLASH") {
                stopSelf()
            }
        )

        val scroll = ScrollView(this).apply {
            addView(content)
            background = createBackground(
                Color.rgb(24, 29, 40),
                22f
            )
        }

        val params = layoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            width = minOf(width, 360)
        }

        try {
            windowManager.addView(scroll, params)
            panel = scroll
            expanded = true
        } catch (_: Exception) {
            panel = null
            expanded = false
            showBubble()
        }
    }

    private fun toggleMonitoring() {
        if (monitoring) {
            monitoring = false
            handler.removeCallbacks(monitorTask)
            toggleButton?.text = "START MONITORING"
            statusText?.text = "Monitoring is OFF"
        } else {
            monitoring = true
            toggleButton?.text = "STOP MONITORING"
            statusText?.text = "Checking network..."
            handler.removeCallbacks(monitorTask)
            handler.post(monitorTask)
        }
    }

    private fun updatePanel(status: NetworkStatus) {
        connectionText?.text =
            "Connection: ${status.transport} | " +
            if (status.internetValidated) {
                "Internet OK"
            } else {
                "Internet unverified"
            }

        latencyText?.text =
            "Latest probe: ${status.latencyMs?.let { "$it ms" } ?: "Failed"}"

        averageText?.text =
            "Average probe: ${status.averageLatencyMs?.let { "$it ms" } ?: "--"}"

        jitterText?.text =
            "Jitter estimate: ${status.jitterMs?.let {
                String.format(java.util.Locale.US, "%.1f ms", it)
            } ?: "--"}"

        failureText?.text =
            "Failed probes: ${status.failedProbes}/${status.totalProbes} " +
            "(${String.format(java.util.Locale.US, "%.1f", status.failurePercent)}%)"

        statusText?.text = status.message
    }

    private fun hidePanelToBubble() {
        panel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        panel = null
        expanded = false

        connectionText = null
        latencyText = null
        averageText = null
        jitterText = null
        failureText = null
        statusText = null
        toggleButton = null

        showBubble()
    }

    private fun removeBubble() {
        bubble?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubble = null
    }

    override fun onDestroy() {
        destroyed = true
        monitoring = false
        handler.removeCallbacksAndMessages(null)

        monitor.close()

        panel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null

        removeBubble()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
