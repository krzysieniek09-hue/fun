package com.krzys.hardwareinfo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Optional background recorder for capability testing: while enabled it
 * samples clock speeds and temperatures every 10 s and stores averaged
 * values in the same SQLite history the charts read. Runs as a
 * foreground service (persistent notification, as Android requires),
 * takes no wakelock, and stops the moment the user toggles it off.
 */
class RecorderService : Service() {

    companion object {
        @Volatile
        var running = false
            private set

        const val CHANNEL_ID = "monitor"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_MS = 10_000L
        const val FLUSH_EVERY = 6          // one DB batch per minute
    }

    private lateinit var store: SampleStore
    private lateinit var zones: List<Pair<String, String>>
    private val handler = Handler(Looper.getMainLooper())
    private val sums = HashMap<String, Float>()
    private val counts = HashMap<String, Int>()
    private var ticks = 0

    private val ticker = object : Runnable {
        override fun run() {
            sample()
            if (++ticks >= FLUSH_EVERY) {
                ticks = 0
                flush()
            }
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SampleStore(this)
        zones = SpecReader.interestingZones()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) {
            running = true
            handler.post(ticker)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(ticker)
        flush()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- sampling ----------

    private fun record(key: String, value: Float) {
        if (!value.isFinite()) return
        sums[key] = (sums[key] ?: 0f) + value
        counts[key] = (counts[key] ?: 0) + 1
    }

    private fun sample() {
        record("avg_clock", SpecReader.avgCurFreqGhz())
        for (core in 0 until SpecReader.coreCount) {
            record("core$core", SpecReader.coreCurFreqGhz(core))
        }
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tenths > 0) record("temp_batt", tenths / 10f)
        for ((name, path) in zones) {
            SpecReader.zoneTempC(path)?.let { record("zone:$name", it) }
        }
    }

    private fun flush() {
        if (sums.isEmpty()) return
        val averages = HashMap<String, Float>(sums.size)
        for ((key, sum) in sums) {
            val n = counts[key] ?: continue
            if (n > 0) averages[key] = sum / n
        }
        sums.clear()
        counts.clear()
        val ts = System.currentTimeMillis()
        Thread { store.insertBatch(ts, averages) }.start()
    }

    // ---------- notification ----------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Background recording",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)
        return builder
            .setContentTitle("Hardware Info")
            .setContentText("Recording clocks and temperatures")
            .setSmallIcon(R.drawable.ic_chip)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }
}
