package com.bluehound.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

class Dev(
    val addr: String,
    @Volatile var name: String,
    @Volatile var rssi: Int,
    @Volatile var icon: String?,
    @Volatile var type: String?,
    @Volatile var maker: String?,
    @Volatile var trackerLabel: String?,
    @Volatile var isTracker: Boolean,
    val firstSeen: Long,
    @Volatile var lastSeen: Long,
    @Volatile var alerted: Boolean = false
)

class ScanService : Service() {

    companion object {
        val devices = ConcurrentHashMap<String, Dev>()
        @Volatile var running = false

        // a tracker first seen this long ago and still nearby triggers an alert
        const val FOLLOW_MS = 15 * 60_000L
        const val RECENT_MS = 3 * 60_000L
    }

    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address ?: return
            val name = try { result.device.name } catch (e: SecurityException) { null }
                ?: result.scanRecord?.deviceName ?: ""
            val d = Decoder.decode(result.scanRecord, name)
            val now = System.currentTimeMillis()
            val prev = devices[addr]
            if (prev == null) {
                devices[addr] = Dev(
                    addr, name, result.rssi, d.icon, d.type, d.maker,
                    d.trackerLabel, d.isTracker, now, now
                )
            } else {
                prev.rssi = result.rssi
                prev.lastSeen = now
                if (name.isNotBlank()) prev.name = name
                if (d.icon != null) prev.icon = d.icon
                if (d.type != null) prev.type = d.type
                if (d.maker != null) prev.maker = d.maker
                if (d.isTracker) { prev.isTracker = true; prev.trackerLabel = d.trackerLabel }
            }
        }
    }

    private val trackerCheck = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (d in devices.values) {
                if (d.isTracker && !d.alerted &&
                    now - d.firstSeen > FOLLOW_MS &&
                    now - d.lastSeen < RECENT_MS
                ) {
                    d.alerted = true
                    alert(d)
                }
            }
            // forget devices not heard for a day
            devices.values.removeIf { now - it.lastSeen > 24 * 3600_000L }
            handler.postDelayed(this, 60_000)
        }
    }

    // Android downgrades unfiltered scans that run >30 min, so restart periodically
    private val scanRestart = object : Runnable {
        override fun run() {
            try {
                scanner?.stopScan(scanCb)
                startScan()
            } catch (e: SecurityException) { /* permission revoked mid-run */ }
            handler.postDelayed(this, 20 * 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        makeChannels()
        startForeground(1, guardNotification())
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        scanner = bt.adapter?.bluetoothLeScanner
        try {
            startScan()
            running = true
        } catch (e: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.postDelayed(trackerCheck, 60_000)
        handler.postDelayed(scanRestart, 20 * 60_000)
        return START_STICKY
    }

    private fun startScan() {
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        scanner?.startScan(null, settings, scanCb)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { scanner?.stopScan(scanCb) } catch (e: SecurityException) { }
        super.onDestroy()
    }

    private fun makeChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("guard", "Guard running", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel("alerts", "Tracker alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun guardNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "guard")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BlueHound Guard is watching")
            .setContentText("Scanning for Bluetooth trackers in the background")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun alert(d: Dev) {
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val label = d.trackerLabel ?: "Unknown tracker"
        val n = Notification.Builder(this, "alerts")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Possible tracker traveling with you")
            .setContentText("$label keeps showing up near you (first seen over 15 minutes ago).")
            .setStyle(Notification.BigTextStyle().bigText(
                "$label keeps showing up near you — first seen over 15 minutes ago, still nearby " +
                "(signal ${d.rssi} dBm, ${Decoder.distanceLabel(d.rssi)}).\n\n" +
                "It could be yours or belong to someone you're with. " +
                "If it isn't and you're worried: don't destroy it — it can be evidence — and contact local police."
            ))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(d.addr.hashCode(), n)
    }
}
