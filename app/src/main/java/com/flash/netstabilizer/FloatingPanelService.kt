
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
import java.util.Locale
import kotlin.math.abs

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

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("FLASH Net Stabilizer")
            .setContentText("Network monitoring service")
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

    private fun background(
        color: Int,
        radius: Float
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun makeText(
        value: String,
        size: Float,
        color: Int = Color.WHITE
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(8, 4, 8, 4)
    }

    private fun makeButton(
        value: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = value
        textSize = 12f
        isAllCaps = false
        minHeight = 38
        minimumHeight = 38
        setPadding(8, 0, 8, 0)
        setOnClickListener { action() }
    }

    private fun createParams(
        width: Int,
        height: Int,
        gravity: Int
    ): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        return WindowManager.LayoutParams(
            width,
            height,
            type,
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
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = background(
                Color.rgb(15, 135, 245),
                50f
            )
            elevation = 8f
        }

        val params = createParams(
            48,
            48,
            Gravity.TOP or Gravity.START
        ).apply {
            x = 0
            y = 220
        }

        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var initialX = 0
            private var initialY = 0
            private var moved = false

            override fun onTouch(
                v: View,
                event: MotionEvent
            ): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        initialX = params.x
                        initialY = params.y
                        moved = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()

                        if (abs(dx) > 7 || abs(dy) > 7) {
                            moved = true
                        }

                        params.x = (initialX + dx).coerceAtLeast(0)
                        params.y = (initialY + dy).coerceAtLeast(0)

                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!moved) openPanel()
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
            setPadding(10, 8, 10, 8)
            background = background(
                Color.rgb(23, 29, 41),
                16f
            )
        }

        val heading = TextView(this).apply {
            text = "⚡ FLASH NET"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 3, 8, 5)
        }

        content.addView(heading)

        connectionText = makeText("Network: checking...", 12f)
        latencyText = makeText("Latency: --", 12f)
        jitterText = makeText("Jitter: --", 12f)
        failureText = makeText("Failed probes: --", 12f)
        statusText = makeText("Monitoring OFF", 11f, Color.YELLOW)

        content.addView(connectionText)
        content.addView(latencyText)
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
            isFillViewport = true
            addView(content)
            background = background(
                Color.rgb(23, 29, 41),
                16f
            )
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val panelWidth = minOf(
            (screenWidth * 0.76f).toInt(),
            dp(280)
        )

        val params = createParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )

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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun toggleMonitoring() {
        if (monitoring) {
            monitoring = false
            handler.removeCallbacks(monitorTask)
            toggleButton?.text = "START MONITORING"
            statusText?.text = "Monitoring OFF"
            statusText?.setTextColor(Color.YELLOW)
        } else {
            monitoring = true
            toggleButton?.text = "STOP MONITORING"
            statusText?.text = "Checking connection..."
            statusText?.setTextColor(Color.CYAN)

            handler.removeCallbacks(monitorTask)
            handler.post(monitorTask)
        }
    }

    private fun updatePanel(status: NetworkStatus) {
        connectionText?.text =
            "Network: ${status.transport}" +
                if (status.internetValidated) " • Online"
                else " • Unverified"

        latencyText?.text =
            "Latency: ${
                status.latencyMs?.let { "$it ms" } ?: "--"
            }"

        jitterText?.text =
            "Jitter: ${
                status.jitterMs?.let {
                    String.format(Locale.US, "%.1f ms", it)
                } ?: "--"
            }"

        failureText?.text =
            "Failed: ${status.failedProbes}/${status.totalProbes} " +
                "(${String.format(Locale.US, "%.0f", status.failurePercent)}%)"

        statusText?.text = status.message
        statusText?.setTextColor(
            if (status.latencyMs != null &&
                status.latencyMs < 100 &&
                status.failurePercent == 0.0
            ) {
                Color.GREEN
            } else {
                Color.YELLOW
            }
        )
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

        if (::monitor.isInitialized) {
            monitor.close()
        }

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
