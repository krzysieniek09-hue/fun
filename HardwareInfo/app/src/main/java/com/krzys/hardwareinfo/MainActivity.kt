package com.krzys.hardwareinfo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * A spec sheet for the phone it runs on, in a minimal fintech-style
 * look: light background, white rounded cards, gray labels on the left,
 * near-black values on the right, hairline dividers. The whole UI is
 * built in code; live values tick every 1.5 s. No libraries used.
 */
class MainActivity : Activity() {

    private companion object {
        const val BG = 0xFFF5F5F7.toInt()          // window background
        const val CARD = 0xFFFFFFFF.toInt()        // card surface
        const val INK = 0xFF0F0F14.toInt()         // titles and values
        const val LABEL = 0xFF7A7A85.toInt()       // row labels
        const val DIVIDER = 0xFFEDEDF0.toInt()     // hairlines inside cards
        const val REFRESH_MS = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val liveRows = ArrayList<Pair<TextView, () -> String>>()
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val ticker = object : Runnable {
        override fun run() {
            for ((view, provider) in liveRows) view.text = provider()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG
        var flags = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }

        buildHeader(root)
        buildSocCard(root)
        buildRamCard(root)
        buildGpuCard(root)
        buildStorageCard(root)
        buildDisplayCard(root)
        buildBatteryCard(root)
        buildSystemCard(root)
        buildSensorsCard(root)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------- sections ----------

    private fun buildHeader(parent: LinearLayout) {
        parent.addView(TextView(this).apply {
            text = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), 0)
        })
        parent.addView(TextView(this).apply {
            text = "${Build.DEVICE} · ${Build.BOARD}"
            setTextColor(LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(4), dp(2), dp(4), dp(4))
        })
    }

    private fun buildSocCard(parent: LinearLayout) {
        val card = newCard(parent, "Processor")
        val socName = SpecReader.socDescription() ?: SpecReader.cpuName()
        addRow(card, "SoC", socName)
        addRow(card, "Cores", SpecReader.coreCount.toString())
        addRow(card, "Clusters", SpecReader.clusterSummary())
        addRow(card, "Architecture", if (SpecReader.is64Bit()) "64-bit" else "32-bit")
        addRow(card, "ABIs", SpecReader.abis())
        addRow(card, "Governor", SpecReader.cpuGovernor())
        for (core in 0 until SpecReader.coreCount) {
            addLiveRow(card, "Core $core") { SpecReader.coreFreqLine(core) }
        }
    }

    private fun buildRamCard(parent: LinearLayout) {
        val card = newCard(parent, "Memory")
        val mem = SpecReader.memoryInfo(this)
        addRow(card, "Total", SpecReader.formatBytes(mem.totalMem))
        addLiveRow(card, "Available") {
            SpecReader.formatBytes(SpecReader.memoryInfo(this).availMem)
        }
        addLiveRow(card, "Used") {
            val m = SpecReader.memoryInfo(this)
            val used = m.totalMem - m.availMem
            val pct = if (m.totalMem > 0) used * 100 / m.totalMem else 0
            "${SpecReader.formatBytes(used)} ($pct%)"
        }
        addRow(card, "Low-RAM threshold", SpecReader.formatBytes(mem.threshold))
        val kernel = SpecReader.meminfo()
        kernel["SwapTotal"]?.let { total ->
            val free = kernel["SwapFree"] ?: 0
            addRow(
                card, "Swap / zram",
                if (total == 0L) "none"
                else "${SpecReader.formatBytes(total - free)} of ${SpecReader.formatBytes(total)}"
            )
        }
        kernel["Cached"]?.let { addRow(card, "Kernel cache", SpecReader.formatBytes(it)) }
        addRow(card, "Type / clock", "not exposed by Android")
    }

    private fun buildGpuCard(parent: LinearLayout) {
        val card = newCard(parent, "Graphics")
        val gpu = try {
            GpuProbe.query()
        } catch (e: Exception) {
            null
        }
        if (gpu != null) {
            addRow(card, "Renderer", gpu.renderer)
            addRow(card, "Vendor", gpu.vendor)
            addRow(card, "GL version", gpu.version)
        } else {
            addRow(card, "Renderer", "could not create GL context")
        }
    }

    private fun buildStorageCard(parent: LinearLayout) {
        val card = newCard(parent, "Storage")
        val volumes = SpecReader.storageVolumes(this)
        if (volumes.isEmpty()) {
            addRow(card, "Volumes", "not readable")
            return
        }
        for (v in volumes) {
            val used = v.total - v.free
            val pct = if (v.total > 0) used * 100 / v.total else 0
            addRow(card, v.label, SpecReader.formatBytes(v.total))
            addRow(card, "Used", "${SpecReader.formatBytes(used)} ($pct%)")
            addRow(card, "Free", SpecReader.formatBytes(v.free))
        }
    }

    private fun buildDisplayCard(parent: LinearLayout) {
        val card = newCard(parent, "Display")
        val display = windowManager.defaultDisplay
        val metrics = resources.displayMetrics

        val mode = display.mode
        addRow(card, "Resolution", "${mode.physicalWidth} × ${mode.physicalHeight}")
        addRow(card, "Density", "${metrics.densityDpi} dpi")
        addRow(card, "Refresh rate", String.format(Locale.US, "%.0f Hz", display.refreshRate))

        val rates = display.supportedModes
            .map { it.refreshRate }
            .distinctBy { it.toInt() }
            .sorted()
            .joinToString(", ") { String.format(Locale.US, "%.0f", it) }
        addRow(card, "Supported rates", "$rates Hz")

        val hdrTypes = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        addRow(
            card, "HDR",
            if (hdrTypes.isEmpty()) "not supported"
            else hdrTypes.joinToString(", ") { type ->
                when (type) {
                    1 -> "Dolby Vision"
                    2 -> "HDR10"
                    3 -> "HLG"
                    4 -> "HDR10+"
                    else -> "type $type"
                }
            }
        )
        addRow(
            card, "Wide color",
            if (Build.VERSION.SDK_INT >= 26 && display.isWideColorGamut) "yes" else "no"
        )
    }

    private fun buildBatteryCard(parent: LinearLayout) {
        val card = newCard(parent, "Battery")
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        addLiveRow(card, "Level") {
            "${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
        }
        addLiveRow(card, "Status") {
            SpecReader.batteryStatusName(batteryExtra(BatteryManager.EXTRA_STATUS))
        }
        addLiveRow(card, "Temperature") {
            val tenths = batteryExtra(BatteryManager.EXTRA_TEMPERATURE)
            if (tenths <= 0) "unknown"
            else String.format(Locale.US, "%.1f °C", tenths / 10.0)
        }
        addLiveRow(card, "Voltage") {
            val mv = batteryExtra(BatteryManager.EXTRA_VOLTAGE)
            if (mv <= 0) "unknown" else String.format(Locale.US, "%.3f V", mv / 1000.0)
        }
        addLiveRow(card, "Current") {
            val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (ua == Int.MIN_VALUE) "unknown"
            else String.format(Locale.US, "%.0f mA", ua / 1000.0)
        }
        val charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        addRow(
            card, "Charge counter",
            if (charge == Int.MIN_VALUE) "unknown"
            else String.format(Locale.US, "%.0f mAh", charge / 1000.0)
        )
        addRow(card, "Health", SpecReader.batteryHealthName(batteryExtra(BatteryManager.EXTRA_HEALTH)))
        addRow(card, "Technology", batteryStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown")
    }

    private fun buildSystemCard(parent: LinearLayout) {
        val card = newCard(parent, "System")
        addRow(card, "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        addRow(card, "Security patch", Build.VERSION.SECURITY_PATCH)
        addRow(card, "Kernel", SpecReader.kernelVersion())
        addRow(card, "Build ID", Build.DISPLAY)
        addRow(card, "Bootloader", Build.BOOTLOADER)
        addRow(card, "Hardware", Build.HARDWARE)
        addLiveRow(card, "Uptime") { SpecReader.uptime() }
    }

    private fun buildSensorsCard(parent: LinearLayout) {
        val card = newCard(parent, "Sensors")
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensors = sm.getSensorList(Sensor.TYPE_ALL)
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase(Locale.US) }
        if (sensors.isEmpty()) {
            addRow(card, "Sensors", "none reported")
            return
        }
        addRow(card, "Count", sensors.size.toString())
        for (s in sensors) addRow(card, s.name, s.vendor)
    }

    // ---------- battery helpers ----------

    private fun batteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun batteryExtra(key: String): Int =
        batteryIntent()?.getIntExtra(key, -1) ?: -1

    private fun batteryStringExtra(key: String): String? =
        batteryIntent()?.getStringExtra(key)

    // ---------- UI building blocks ----------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Section title above a flat white rounded card, grouped-list style. */
    private fun newCard(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(TextView(this).apply {
            text = title
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = medium
            setPadding(dp(4), dp(20), dp(4), dp(8))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        parent.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return card
    }

    private fun addRow(card: LinearLayout, label: String, value: String): TextView {
        if (card.childCount > 0) {
            card.addView(View(this).apply { setBackgroundColor(DIVIDER) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(11), 0, dp(11))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f))
        val valueView = TextView(this).apply {
            text = value
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.END
            typeface = medium
        }
        row.addView(valueView, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f
        ))
        card.addView(row)
        return valueView
    }

    private fun addLiveRow(card: LinearLayout, label: String, provider: () -> String) {
        val view = addRow(card, label, provider())
        liveRows.add(view to provider)
    }
}
