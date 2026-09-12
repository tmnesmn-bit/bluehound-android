package com.bluehound.guard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0b1220")
    private val PANEL = Color.parseColor("#141d31")
    private val TEXT = Color.parseColor("#e8eefc")
    private val DIM = Color.parseColor("#8fa0c4")
    private val BLUE = Color.parseColor("#4da3ff")
    private val GREEN = Color.parseColor("#3ddc84")
    private val AMBER = Color.parseColor("#ffb347")
    private val RED = Color.parseColor("#ff5c6c")

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var listBox: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(40), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "📡 BlueHound Guard"
            textSize = 24f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Always-on lookout for Bluetooth trackers"
            textSize = 13f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(14))
        })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(DIM)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(PANEL)
        }
        root.addView(status)

        toggle = Button(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onToggle() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10); bottomMargin = dp(10) })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))

        root.addView(TextView(this).apply {
            text = "A quiet list is not a guarantee — trackers broadcast on and off, some go quiet near their owner, " +
                "and some change their radio address, which restarts the 15-minute clock. " +
                "Also keep your phone's built-in unknown-tracker alerts on. " +
                "Your own gear can be flagged too; that's normal. " +
                "If you find a tracker that isn't yours and you're worried: don't destroy it (it can be evidence) — contact local police."
            textSize = 11f
            setTextColor(DIM)
            setPadding(0, dp(10), 0, 0)
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun onToggle() {
        if (ScanService.running) {
            stopService(Intent(this, ScanService::class.java))
        } else {
            val missing = neededPermissions().filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) startGuard()
            else requestPermissions(missing.toTypedArray(), 1)
        }
        ui.postDelayed({ refresh() }, 400)
    }

    private fun neededPermissions(): List<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_SCAN
            p += Manifest.permission.BLUETOOTH_CONNECT
        }
        p += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
        return p
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        val essential = permissions.indices.filter {
            permissions[it] != Manifest.permission.POST_NOTIFICATIONS
        }
        if (essential.all { grantResults[it] == PackageManager.PERMISSION_GRANTED }) {
            startGuard()
        } else {
            status.text = "Bluetooth and location permissions are required to scan. Tap Start to try again."
        }
    }

    private fun startGuard() {
        startForegroundService(Intent(this, ScanService::class.java))
        ui.postDelayed({ refresh() }, 600)
    }

    private fun refresh() {
        val running = ScanService.running
        toggle.text = if (running) "Stop guard" else "Start guard"
        toggle.setBackgroundColor(if (running) RED else BLUE)
        toggle.setTextColor(if (running) Color.WHITE else Color.parseColor("#04101f"))

        val now = System.currentTimeMillis()
        val rows = ScanService.devices.values
            .filter { now - it.lastSeen < 90_000 }
            .sortedByDescending { it.rssi }

        status.text = when {
            !running -> "Guard is off. Tap Start — scanning keeps running with the screen off."
            rows.isEmpty() -> "Guard running… listening (devices can take a minute to appear)"
            else -> "Guard running — ${rows.size} device${if (rows.size == 1) "" else "s"} nearby"
        }

        listBox.removeAllViews()
        for (d in rows.take(60)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(PANEL)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val title = if (d.name.isNotBlank()) d.name
                else "${d.icon ?: "❓"} ${d.type ?: "Unknown Bluetooth device"}"
            row.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            val sub = buildList {
                if (d.name.isNotBlank() && d.type != null) add("${d.icon ?: ""} ${d.type}".trim())
                d.maker?.let { add(it) }
                add("${d.rssi} dBm")
                add(Decoder.distanceLabel(d.rssi))
            }.joinToString(" · ")
            row.addView(TextView(this).apply {
                text = sub
                textSize = 12f
                setTextColor(sigColor(d.rssi))
            })
            if (d.isTracker) {
                row.addView(TextView(this).apply {
                    text = "⚠️ possible tracker · ${d.trackerLabel ?: "unknown type"}" +
                        if (d.alerted) " · ALERTED" else ""
                    textSize = 12f
                    setTextColor(RED)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(3), 0, 0)
                })
            }
            listBox.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun sigColor(rssi: Int): Int = when {
        rssi >= -60 -> GREEN
        rssi >= -80 -> AMBER
        else -> DIM
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
