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
        const val REFRESH_MS = 1500L
        const val PREFS = "settings"
        const val PREF_DARK = "dark"
    }

    // Switchable palette; light values by default, see applyPalette().
    private var BG = 0xFFF5F5F7.toInt()            // window background
    private var CARD = 0xFFFFFFFF.toInt()          // card surface
    private var INK = 0xFF0F0F14.toInt()           // titles and values
    private var LABEL = 0xFF7A7A85.toInt()         // row labels
    private var DIVIDER = 0xFFEDEDF0.toInt()       // hairlines inside cards

    private var dark = false
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val liveRows = ArrayList<Pair<TextView, () -> String>>()
    private val liveCharts = ArrayList<Pair<LineChartView, () -> Float>>()
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val ticker = object : Runnable {
        override fun run() {
            for ((view, provider) in liveRows) view.text = provider()
            for ((chart, provider) in liveCharts) chart.addSample(provider())
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dark = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_DARK, false)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root)
        }
        setContentView(scroll)

        applyPalette()
        buildAll()
    }

    private fun applyPalette() {
        if (dark) {
            BG = 0xFF0E0E11.toInt()
            CARD = 0xFF1A1A1F.toInt()
            INK = 0xFFF2F2F5.toInt()
            LABEL = 0xFF8A8A93.toInt()
            DIVIDER = 0xFF2A2A30.toInt()
        } else {
            BG = 0xFFF5F5F7.toInt()
            CARD = 0xFFFFFFFF.toInt()
            INK = 0xFF0F0F14.toInt()
            LABEL = 0xFF7A7A85.toInt()
            DIVIDER = 0xFFEDEDF0.toInt()
        }
        window.statusBarColor = BG
        window.navigationBarColor = BG
        var flags = window.decorView.systemUiVisibility
        flags = if (dark) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                (if (Build.VERSION.SDK_INT >= 26)
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else -1)
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26)
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
        window.decorView.systemUiVisibility = flags
        root.setBackgroundColor(BG)
        scroll.setBackgroundColor(BG)
    }

    /** (Re)build every card; chart history survives the rebuild. */
    private fun buildAll() {
        val history = liveCharts.map { it.first.exportSamples() }
        liveRows.clear()
        liveCharts.clear()
        root.removeAllViews()

        buildHeader(root)
        buildSocCard(root)
        buildTemperatureCard(root)
        buildRamCard(root)
        buildGpuCard(root)
        buildStorageCard(root)
        buildDisplayCard(root)
        buildBatteryCard(root)
        buildSystemCard(root)
        buildSensorsCard(root)

        // Cards are built in a fixed order, so old buffers line up 1:1.
        for (i in liveCharts.indices) {
            if (i < history.size) liveCharts[i].first.importSamples(history[i])
        }
    }

    private fun toggleTheme() {
        dark = !dark
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_DARK, dark).apply()
        applyPalette()
        buildAll()
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
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titles.addView(TextView(this).apply {
            text = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), 0)
        })
        titles.addView(TextView(this).apply {
            text = "${Build.DEVICE} · ${Build.BOARD}"
            setTextColor(LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(4), dp(2), dp(4), dp(4))
        })
        headerRow.addView(titles, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        // Round theme-toggle button: moon in light mode, sun in dark mode.
        headerRow.addView(TextView(this).apply {
            text = if (dark) "☀" else "☾"
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CARD)
            }
            contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode"
            setOnClickListener { toggleTheme() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })

        parent.addView(headerRow)
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
        addHeroChart(
            card, "Average clock",
            valueProvider = {
                val ghz = SpecReader.avgCurFreqGhz()
                if (ghz.isNaN()) "n/a" else String.format(Locale.US, "%.2f GHz", ghz)
            },
            sampleProvider = { SpecReader.avgCurFreqGhz() }
        )
        for (core in 0 until SpecReader.coreCount) {
            addChartRow(
                card, "Core $core",
                valueProvider = { SpecReader.coreCurFreqText(core) },
                sampleProvider = { SpecReader.coreCurFreqGhz(core) }
            )
        }
    }

    private fun buildTemperatureCard(parent: LinearLayout) {
        val card = newCard(parent, "Temperatures")
        addHeroChart(
            card, "Battery",
            valueProvider = {
                val tenths = batteryExtra(BatteryManager.EXTRA_TEMPERATURE)
                if (tenths <= 0) "n/a"
                else String.format(Locale.US, "%.1f °C", tenths / 10.0)
            },
            sampleProvider = {
                val tenths = batteryExtra(BatteryManager.EXTRA_TEMPERATURE)
                if (tenths <= 0) Float.NaN else tenths / 10f
            }
        )

        // SoC thermal sensors, when the kernel lets apps read them.
        val zones = SpecReader.thermalZones()
        val interesting = zones.filter { (name, _) ->
            val n = name.lowercase(Locale.US)
            listOf("cpu", "soc", "gpu", "skin", "therm").any { n.contains(it) }
                && !n.contains("batt")
        }
        val shown = (interesting.ifEmpty { zones }).take(6)
        if (shown.isEmpty()) {
            addRow(card, "SoC sensors", "not readable on this device")
        } else {
            for ((name, path) in shown) {
                addChartRow(
                    card, name,
                    valueProvider = {
                        val t = SpecReader.zoneTempC(path)
                        if (t == null) "n/a" else String.format(Locale.US, "%.1f °C", t)
                    },
                    sampleProvider = { SpecReader.zoneTempC(path) ?: Float.NaN }
                )
            }
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
        addDividerIfNeeded(card)
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

    private fun addDividerIfNeeded(card: LinearLayout) {
        if (card.childCount > 0) {
            card.addView(View(this).apply { setBackgroundColor(DIVIDER) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
    }

    /** Big current value with a full-width chart underneath, hero style. */
    private fun addHeroChart(
        card: LinearLayout,
        label: String,
        valueProvider: () -> String,
        sampleProvider: () -> Float
    ) {
        addDividerIfNeeded(card)
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        block.addView(TextView(this).apply {
            text = label
            setTextColor(LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        val valueView = TextView(this).apply {
            text = valueProvider()
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(8))
        }
        block.addView(valueView)
        val chart = LineChartView(this).apply { lineColor = INK }
        block.addView(chart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(72)
        ))
        card.addView(block)
        liveRows.add(valueView to valueProvider)
        liveCharts.add(chart to sampleProvider)
        chart.addSample(sampleProvider())
    }

    /** Label left, sparkline in the middle, live value on the right. */
    private fun addChartRow(
        card: LinearLayout,
        label: String,
        valueProvider: () -> String,
        sampleProvider: () -> Float
    ) {
        addDividerIfNeeded(card)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.34f))
        val chart = LineChartView(this).apply { lineColor = INK }
        row.addView(chart, LinearLayout.LayoutParams(0, dp(28), 0.36f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        val valueView = TextView(this).apply {
            text = valueProvider()
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.END
            typeface = medium
            maxLines = 1
        }
        row.addView(valueView, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.30f
        ))
        card.addView(row)
        liveRows.add(valueView to valueProvider)
        liveCharts.add(chart to sampleProvider)
        chart.addSample(sampleProvider())
    }
}
