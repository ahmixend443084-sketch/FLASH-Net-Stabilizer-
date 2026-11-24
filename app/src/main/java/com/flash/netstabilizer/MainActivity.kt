package com.flash.netstabilizer
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val green = Color.rgb(0, 255, 145)
    private val dark = Color.rgb(12, 17, 22)

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized &&
            Settings.canDrawOverlays(this)
        ) {
            status.text = "Floating permission is enabled"
        }
    }

    private fun showScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(dark)
        }

        val title = TextView(this).apply {
            text = "⚡ FLASH"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(green)
        }

        val subtitle = TextView(this).apply {
            text = "NET STABILIZER"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        status = TextView(this).apply {
            text = "Floating panel permission required"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }

        val permissionButton = Button(this).apply {
            text = "ALLOW FLOATING PANEL"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    startFloatingPanel()
                }
            }
        }

        val startButton = Button(this).apply {
            text = "START FLASH"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startFloatingPanel()
                } else {
                    status.text = "Please allow floating permission first"
                }
            }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(permissionButton)
        root.addView(startButton)

        setContentView(root)
    }

    private fun startFloatingPanel() {
        val serviceIntent = Intent(
            this,
            FloatingPanelService::class.java
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        status.text = "Starting floating panel..."
    }
}
