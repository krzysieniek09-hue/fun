package com.krzys.hardwareinfo

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.util.Locale

/**
 * All hardware probing lives here. Everything is read straight from the
 * Android framework or the kernel's /proc and /sys interfaces - no
 * libraries, no borrowed code.
 */
object SpecReader {

    // ---------- small helpers ----------

    private fun readFile(path: String): String? =
        try {
            val f = File(path)
            if (f.canRead()) f.readText().trim().ifEmpty { null } else null
        } catch (e: Exception) {
            null
        }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "unknown"
        val kb = 1024.0
        return when {
            bytes >= kb * kb * kb -> String.format(Locale.US, "%.2f GB", bytes / (kb * kb * kb))
            bytes >= kb * kb -> String.format(Locale.US, "%.1f MB", bytes / (kb * kb))
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun khzToString(khz: Long): String =
        if (khz >= 1000000) String.format(Locale.US, "%.2f GHz", khz / 1000000.0)
        else String.format(Locale.US, "%d MHz", khz / 1000)

    // ---------- CPU ----------

    val coreCount: Int get() = Runtime.getRuntime().availableProcessors()

    /** "Hardware" or "model name" line from /proc/cpuinfo, if the kernel exposes one. */
    fun cpuName(): String {
        val text = readFile("/proc/cpuinfo") ?: return "unknown"
        for (line in text.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase(Locale.US)
            if (key == "hardware" || key == "model name") {
                return line.substring(idx + 1).trim()
            }
        }
        return "unknown"
    }

    /** SoC vendor/model straight from the framework on Android 12+. */
    fun socDescription(): String? =
        if (Build.VERSION.SDK_INT >= 31) {
            val man = Build.SOC_MANUFACTURER
            val model = Build.SOC_MODEL
            listOf(man, model)
                .filter { it.isNotBlank() && it != Build.UNKNOWN }
                .joinToString(" ")
                .ifBlank { null }
        } else null

    fun coreMinFreq(core: Int): Long =
        readFile("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_min_freq")?.toLongOrNull() ?: -1

    fun coreMaxFreq(core: Int): Long =
        readFile("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")?.toLongOrNull() ?: -1

    fun coreCurFreq(core: Int): Long =
        readFile("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")?.toLongOrNull() ?: -1

    fun coreFreqLine(core: Int): String {
        val cur = coreCurFreq(core)
        val max = coreMaxFreq(core)
        return when {
            cur > 0 && max > 0 -> "${khzToString(cur)} / ${khzToString(max)}"
            max > 0 -> "max ${khzToString(max)}"
            else -> "not readable"
        }
    }

    /** Distinct max frequencies across cores, e.g. "4x 1.80 GHz + 4x 2.40 GHz". */
    fun clusterSummary(): String {
        val maxes = (0 until coreCount).map { coreMaxFreq(it) }.filter { it > 0 }
        if (maxes.isEmpty()) return "frequencies not readable"
        return maxes.groupingBy { it }.eachCount()
            .toSortedMap()
            .entries
            .joinToString(" + ") { (freq, n) -> "${n}x ${khzToString(freq)}" }
    }

    fun cpuGovernor(): String =
        readFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") ?: "unknown"

    /** Current frequency of one core in GHz, or NaN if not readable. */
    fun coreCurFreqGhz(core: Int): Float {
        val khz = coreCurFreq(core)
        return if (khz > 0) khz / 1_000_000f else Float.NaN
    }

    fun coreCurFreqText(core: Int): String {
        val ghz = coreCurFreqGhz(core)
        return if (ghz.isNaN()) "n/a" else String.format(Locale.US, "%.2f GHz", ghz)
    }

    /** Mean of all readable per-core current frequencies in GHz, or NaN. */
    fun avgCurFreqGhz(): Float {
        var sum = 0L
        var n = 0
        for (core in 0 until coreCount) {
            val khz = coreCurFreq(core)
            if (khz > 0) {
                sum += khz
                n++
            }
        }
        return if (n > 0) sum / n / 1_000_000f else Float.NaN
    }

    // ---------- Temperatures ----------

    /**
     * Thermal zones the kernel lets us read, as (name, temp-file path).
     * Many devices block these for apps; callers must cope with an
     * empty list.
     */
    fun thermalZones(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val dirs = File("/sys/class/thermal")
            .listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name } ?: return out
        for (d in dirs) {
            val tempPath = "${d.path}/temp"
            val t = zoneTempC(tempPath) ?: continue
            if (t <= 0f || t > 150f) continue   // dead or nonsense sensor
            val name = readFile("${d.path}/type") ?: d.name
            out.add(name to tempPath)
        }
        return out
    }

    /**
     * The zones worth charting: CPU/SoC/GPU/skin sensors first, up to
     * six, so the activity and the background recorder agree on keys.
     */
    fun interestingZones(): List<Pair<String, String>> {
        val zones = thermalZones()
        val interesting = zones.filter { (name, _) ->
            val n = name.lowercase(Locale.US)
            listOf("cpu", "soc", "gpu", "skin", "therm").any { n.contains(it) }
                && !n.contains("batt")
        }
        return (interesting.ifEmpty { zones }).take(6)
    }

    /** Zone temperature in Celsius; kernels report m°C, d°C or °C. */
    fun zoneTempC(path: String): Float? {
        val raw = readFile(path)?.toFloatOrNull() ?: return null
        return when {
            raw > 1000f -> raw / 1000f
            raw > 200f -> raw / 10f
            else -> raw
        }
    }

    fun abis(): String = Build.SUPPORTED_ABIS.joinToString(", ")

    fun is64Bit(): Boolean = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    // ---------- Memory ----------

    fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info
    }

    /** Selected fields from /proc/meminfo in bytes, keyed by their kernel names. */
    fun meminfo(): Map<String, Long> {
        val wanted = setOf(
            "MemTotal", "MemAvailable", "MemFree", "Cached",
            "SwapTotal", "SwapFree", "Buffers"
        )
        val out = LinkedHashMap<String, Long>()
        val text = readFile("/proc/meminfo") ?: return out
        for (line in text.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            if (key !in wanted) continue
            val kb = line.substring(idx + 1).trim()
                .removeSuffix("kB").trim().toLongOrNull() ?: continue
            out[key] = kb * 1024
        }
        return out
    }

    // ---------- Storage ----------

    data class Volume(val label: String, val total: Long, val free: Long)

    fun storageVolumes(context: Context): List<Volume> {
        val volumes = ArrayList<Volume>()
        try {
            val data = StatFs(Environment.getDataDirectory().path)
            volumes.add(Volume("Internal (data)", data.totalBytes, data.availableBytes))
        } catch (e: Exception) { /* partition not statable */ }
        try {
            val dirs = context.getExternalFilesDirs(null)
            // First entry is emulated internal storage (already counted);
            // anything after it is a real SD card or USB volume.
            for (i in 1 until dirs.size) {
                val d = dirs[i] ?: continue
                val st = StatFs(d.path)
                volumes.add(Volume("Removable #$i", st.totalBytes, st.availableBytes))
            }
        } catch (e: Exception) { /* no removable media */ }
        return volumes
    }

    // ---------- Battery ----------

    fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        else -> "unknown"
    }

    fun batteryHealthName(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        else -> "unknown"
    }

    // ---------- System ----------

    fun kernelVersion(): String = System.getProperty("os.version") ?: "unknown"

    fun uptime(): String {
        var s = SystemClock.elapsedRealtime() / 1000
        val d = s / 86400; s %= 86400
        val h = s / 3600; s %= 3600
        val m = s / 60; s %= 60
        return if (d > 0) String.format(Locale.US, "%dd %02d:%02d:%02d", d, h, m, s)
        else String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
