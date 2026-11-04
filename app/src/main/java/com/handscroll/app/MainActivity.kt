package com.handscroll.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(s: String, size: Float = 14f) = TextView(ctx).apply {
            text = s
            textSize = size
            setPadding(0, pad / 2, 0, pad / 4)
        }
        fun button(s: String, onClick: () -> Unit) = Button(ctx).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(label("HandScroll", 28f))
        root.addView(label(
            "Hold your open hand in front of the front camera.\n" +
                "Move it UP = next reel\nMove it DOWN = previous reel\n" +
                "Show a FIST for about half a second = pause / play"
        ))

        root.addView(label("Step 1: Permissions"))
        root.addView(button("Allow camera & notifications") {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS), 1
            )
        })

        root.addView(label("Step 2: Accessibility (lets the app swipe and tap for you)"))
        root.addView(button("Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(label(
            "Turn on HandScroll there. If the switch is greyed out: Settings > Apps > HandScroll > " +
                "three dots > Allow restricted settings."
        ))

        root.addView(label("Step 3: Start"))
        root.addView(button("Start tracking") {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(ctx, "Allow the camera first (Step 1)", Toast.LENGTH_LONG).show()
            } else {
                HandState.error = null
                ContextCompat.startForegroundService(ctx, Intent(ctx, HandTrackingService::class.java))
            }
        })
        root.addView(button("Stop tracking") {
            stopService(Intent(ctx, HandTrackingService::class.java))
        })

        root.addView(label("Tuning"))
        val thrLabel = label("")
        fun showThr(v: Float) {
            thrLabel.text = "Swipe distance needed: %.2f of the camera view (lower = more sensitive)".format(v)
        }
        showThr(Prefs.threshold(ctx))
        root.addView(thrLabel)
        root.addView(SeekBar(ctx).apply {
            max = 50
            progress = ((Prefs.threshold(ctx) * 100).toInt() - 10).coerceIn(0, 50)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = (p + 10) / 100f
                    Prefs.sp(ctx).edit().putFloat("threshold", v).apply()
                    showThr(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        root.addView(CheckBox(ctx).apply {
            text = "Only act inside Instagram"
            isChecked = Prefs.onlyInstagram(ctx)
            setOnCheckedChangeListener { _, c ->
                Prefs.sp(ctx).edit().putBoolean("only_ig", c).apply()
            }
        })

        root.addView(label("Live status"))
        status = TextView(ctx).apply { textSize = 15f }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val acc = ScrollAccessibilityService.instance != null
        status.text = buildString {
            appendLine("Accessibility: " + if (acc) "ON" else "OFF")
            appendLine("Tracking: " + if (HandState.running) "RUNNING" else "stopped")
            HandState.error?.let { appendLine("Error: $it") }
            appendLine("Hand: " + if (HandState.handFound) "found" else "not found")
            appendLine("Fist: " + if (HandState.fist) "yes" else "no")
            appendLine("Last action: ${HandState.lastAction}")
        }
    }
}
