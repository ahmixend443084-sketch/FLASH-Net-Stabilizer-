
package com.flash.netstabilizer

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val green = Color.rgb(0, 255, 145)
    private val dark = Color.rgb(12, 17, 22)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var latency: TextView
    private lateinit var button: Button

    @Volatile
    private var running = false

    @Volatile
    private var testing = false

    private val monitor = object : Runnable {
        override fun run() {
            if (!running || testing) return

            testing = true
            status.text = "Testing connection..."

            executor.execute {
                val result = testConnection()

                handler.post {
                    testing = false

                    if (running) {
                        if (result >= 0) {
                            latency.text = "$result ms"
                            status.text = "Internet reachable"
                        } else {
                            latency.text = "--"
                            status.text = "Test failed; retrying"
                        }

                        handler.postDelayed(this, 5000)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(dark)
        }

        fun makeText(
            value: String,
            size: Float,
            color: Int
        ) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
        }

        root.addView(makeText("⚡ FLASH", 34f, green))
        root.addView(makeText("NET STABILIZER", 18f, Color.WHITE))
        status = makeText("Ready to start", 16f, Color.LTGRAY)
        root.addView(status)
        latency = makeText("--", 42f, green)
        root.addView(latency)
        root.addView(
            makeText("HTTPS RESPONSE TIME", 13f, Color.LTGRAY)
        )

        button = Button(this).apply {
            text = "START AUTO MONITOR"
            setTextColor(Color.BLACK)
            setBackgroundColor(green)
            setOnClickListener {
                if (running) stopMonitor() else startMonitor()
            }
        }
        root.addView(button)

        root.addView(
            makeText(
                "Connection test only. Yalla server matching is not implemented yet.",
                12f,
                Color.LTGRAY
            )
        )

        setContentView(root)
    }

    private fun startMonitor() {
        running = true
        button.text = "STOP MONITORING"
        handler.removeCallbacks(monitor)
        handler.post(monitor)
    }

    private fun stopMonitor() {
        running = false
        handler.removeCallbacks(monitor)
        button.text = "START AUTO MONITOR"
        status.text = "Monitoring stopped"
    }

    private fun testConnection(): Long {
        var connection: HttpURLConnection? = null

        return try {
            val start = System.nanoTime()

            connection = URL(
                "https://www.google.com/generate_204"
            ).openConnection() as HttpURLConnection

            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = "GET"

            val code = connection.responseCode
            val elapsed =
                (System.nanoTime() - start) / 1_000_000

            if (code in 200..299) elapsed else -1L
        } catch (_: Exception) {
            -1L
        } finally {
            connection?.disconnect()
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }
}
