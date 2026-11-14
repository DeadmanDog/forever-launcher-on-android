package app.gamenative.ui.widget

import android.os.SystemClock
import java.io.File
import java.util.Locale

enum class CpuUsageSource {
    PROC_STAT,
    PROCESS_TIME,
    CPU_FREQUENCY,
    UNAVAILABLE,
}

data class CpuUsageReading(val percent: Int, val source: CpuUsageSource) {
    /**
     * The figure that may be shown as CPU load, or null. The last-resort source only compares the cores' clock with their maximum,
     * which is how fast they run, not how busy they are (idle cores at a high governor setting read 100%): it is never a load.
     */
    val loadPercent: Int? get() = if (source == CpuUsageSource.CPU_FREQUENCY) null else percent
}

data class GpuUsageReading(val percent: Int, val source: String)

object SystemMetricsSources {
    @Volatile
    private var gpuUsagePathsCache: List<String>? = null

    @Volatile
    private var thermalZonesCache: List<Pair<String, String>>? = null

    @Volatile
    private var cpuTempPathsCache: List<String>? = null

    @Volatile
    private var gpuTempPathsCache: List<String>? = null

    private val fixedGpuTempPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/temp",
        "/sys/class/kgsl/kgsl-3d0/devfreq/temp",
        "/sys/class/misc/mali0/device/temp",
        "/sys/kernel/gpu/temp",
    )

    @Synchronized
    fun gpuUsagePaths(): List<String> {
        gpuUsagePathsCache?.let { return it }

        val candidates = linkedSetOf<String>()
        fun add(path: String) {
            if (File(path).canRead()) {
                candidates += path
            }
        }

        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/gpuinfo",
            "/sys/devices/platform/mali/utilization",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/pvrsrvkm/device/utilisation",
            "/sys/class/devfreq/gpu/load",
        ).forEach(::add)

        listOf(
            File("/sys/class/devfreq"),
            File("/sys/devices/virtual/devfreq"),
        ).forEach { devfreqRoot ->
            if (!devfreqRoot.isDirectory) return@forEach
            val nodeDirs = devfreqRoot.listFiles { file -> file.isDirectory } ?: emptyArray<File>()
            for (nodeDir in nodeDirs) {
                val nodePath = nodeDir.path.lowercase(Locale.US)
                val looksLikeGpuNode = listOf("gpu", "mali", "g3d", "kgsl").any { token ->
                    nodePath.contains(token)
                }
                val usageFiles = listOf(
                    "gpu_busy_percentage",
                    "gpu_load",
                    "utilisation",
                    "utilization",
                    "load",
                    "gpuinfo",
                )
                for (fileName in usageFiles) {
                    val file = File(nodeDir, fileName)
                    if (!file.canRead()) continue
                    if (looksLikeGpuNode || fileName == "gpu_busy_percentage" || fileName == "gpuinfo") {
                        candidates += file.path
                    }
                }
            }
        }

        val paths = candidates.toList()
        gpuUsagePathsCache = paths
        return paths
    }

    @Synchronized
    fun cpuTempPaths(): List<String> {
        cpuTempPathsCache?.let { return it }

        val paths = prioritizePaths(allThermalZones()) { type ->
            when {
                type.contains("cpu-silicon") -> 0
                type.contains("cpu-0") -> 1
                type.contains("cpu") && !type.contains("gpu") -> 2
                type.contains("soc") -> 3
                type.contains("s5p-tmu") -> 4
                type.contains("cputop") -> 5
                type.contains("tsens") -> 6
                type.contains("cluster") -> 7
                type.contains("big") || type.contains("little") -> 8
                else -> null
            }
        }

        cpuTempPathsCache = paths
        return paths
    }

    @Synchronized
    fun gpuTempPaths(): List<String> {
        gpuTempPathsCache?.let { return it }

        val zonePaths = prioritizePaths(allThermalZones()) { type ->
            when {
                type.contains("gpu-silicon") -> 0
                type.contains("gpu") -> 1
                type.contains("g3d") -> 2
                type.contains("kgsl") -> 3
                type.contains("mali") -> 4
                else -> null
            }
        }

        val paths = (fixedGpuTempPaths + zonePaths).distinct()
        gpuTempPathsCache = paths
        return paths
    }

    @Synchronized
    private fun allThermalZones(): List<Pair<String, String>> {
        thermalZonesCache?.let { return it }

        val zones = listOf(
            File("/sys/class/thermal"),
            File("/sys/devices/virtual/thermal"),
        ).flatMap { thermalDir ->
            val zoneDirs = thermalDir.listFiles { file ->
                file.isDirectory && file.name.startsWith("thermal_zone")
            } ?: return@flatMap emptyList<Pair<String, String>>()

            zoneDirs.mapNotNull { zone ->
                val type = readFirstLine(File(zone, "type").path)
                    ?.trim()
                    ?.lowercase(Locale.US)
                    ?: return@mapNotNull null
                Pair(type, File(zone, "temp").path)
            }
        }.distinctBy { it.second }

        thermalZonesCache = zones
        return zones
    }

    private fun prioritizePaths(
        zones: List<Pair<String, String>>,
        ranker: (String) -> Int?,
    ): List<String> {
        return zones
            .mapNotNull { (type, path) ->
                ranker(type)?.let { rank -> Triple(type, path, rank) }
            }
            .sortedWith(compareBy({ it.third }, { it.second }))
            .map { it.second }
    }

    fun readTemperatureC(paths: List<String>): Int? {
        for (path in paths.distinct()) {
            val raw = readFirstLine(path)?.trim()?.toIntOrNull() ?: continue
            val celsius = if (raw > 1000) (raw + 500) / 1000 else raw
            if (celsius in 1..150) {
                return celsius
            }
        }
        return null
    }

    // 2026-11-12 review: the HUD reads dozens of one-line sysfs and /proc files every second; a BufferedReader per read cost
    // about 16 KB of buffers each time. One small buffer per thread is reused instead.
    private val lineBuffer = ThreadLocal.withInitial { ByteArray(1024) }

    /** The first line of a small file (without its line end), or null when it is empty or unreadable. */
    fun readFirstLine(path: String): String? {
        return try {
            java.io.FileInputStream(path).use { input ->
                val buffer = lineBuffer.get()!!
                var length = 0
                while (length < buffer.size) {
                    val read = input.read(buffer, length, buffer.size - length)
                    if (read <= 0) break
                    val end = firstLineEnd(buffer, length, length + read)
                    length += read
                    if (end >= 0) return@use String(buffer, 0, end, Charsets.UTF_8)
                }
                if (length == 0) null
                else if (length < buffer.size) String(buffer, 0, length, Charsets.UTF_8)
                // A first line longer than the buffer: the plain way.
                else File(path).bufferedReader().use { it.readLine() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun firstLineEnd(buffer: ByteArray, from: Int, to: Int): Int {
        for (i in from until to) if (buffer[i] == '\n'.code.toByte() || buffer[i] == '\r'.code.toByte()) return i
        return -1
    }

    fun readNthLine(path: String, lineIndex: Int): String? {
        return try {
            File(path).bufferedReader().useLines { lines ->
                lines.drop(lineIndex).firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readPercentFromLine(path: String): Int? {
        val raw = readFirstLine(path)?.trim() ?: return null
        val token = raw.split(Regex("\\s+"))
            .map { it.replace(Regex("[^0-9]"), "") }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return token.toIntOrNull()?.coerceIn(0, 100)
    }

    fun readLongFromLine(path: String): Long? {
        return readFirstLine(path)?.trim()?.toLongOrNull()
    }

    /**
     * CPU time (utime + stime, in USER_HZ ticks) of every process the app may see. Android hides other users' processes
     * from /proc, so this is the app together with the Wine/game processes it started.
     */
    fun readProcessCpuTicks(): Map<Int, Long>? {
        val entries = File("/proc").listFiles { file -> file.name.all(Char::isDigit) } ?: return null
        val ticks = HashMap<Int, Long>()
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val line = readFirstLine("/proc/$pid/stat") ?: continue
            // "pid (comm) state ..." where comm may hold spaces or parentheses: parse after the last ')'.
            val rest = line.substring(line.lastIndexOf(')') + 1).trim().split(' ')
            val used = (rest.getOrNull(11)?.toLongOrNull() ?: continue) + (rest.getOrNull(12)?.toLongOrNull() ?: continue)
            ticks[pid] = used
        }
        return ticks.takeIf { it.isNotEmpty() }
    }
}

class CpuUsageSampler(
    private val readLine: (String) -> String? = SystemMetricsSources::readFirstLine,
    processorCount: Int = Runtime.getRuntime().availableProcessors(),
    /** Per-process CPU ticks, used when /proc/stat is unreadable; null disables this source. */
    private val processTicks: (() -> Map<Int, Long>?)? = null,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val cores = processorCount.coerceIn(1, 128)
    private val maximumFrequencies = arrayOfNulls<Long>(cores)
    private var lastTotal: Long? = null
    private var lastIdle: Long? = null
    private var lastProcessTicks: Map<Int, Long>? = null
    private var lastProcessTimeMs = 0L
    // /proc/stat is denied to apps on current Android: after a few failed reads it is not tried (and failed) every second.
    private var procStatMisses = 0
    private companion object { const val PROC_STAT_MAX_MISSES = 3 }

    fun reset() {
        lastTotal = null
        lastIdle = null
        lastProcessTicks = null
    }

    /** Share of all cores' capacity used by the app's processes since the previous sample (USER_HZ = 100 ticks/s). */
    private fun readFromProcesses(): Int? {
        val read = processTicks ?: return null
        val now = clockMs()
        val current = read() ?: return null
        val previous = lastProcessTicks
        val previousTime = lastProcessTimeMs
        lastProcessTicks = current
        lastProcessTimeMs = now
        if (previous == null || now <= previousTime) return null
        var usedTicks = 0L
        for ((pid, ticks) in current) {
            // A process that appeared since the last sample started inside the interval: all its ticks count.
            usedTicks += (ticks - (previous[pid] ?: 0L)).coerceAtLeast(0L)
        }
        return ((usedTicks * 10L * 100L) / ((now - previousTime) * cores)).toInt().coerceIn(0, 100)
    }

    fun sample(): CpuUsageReading? {
        val parts = if (procStatMisses >= PROC_STAT_MAX_MISSES) null else readLine("/proc/stat")
            .also { if (it == null) procStatMisses++ else procStatMisses = 0 }
            ?.trim()
            ?.split(Regex("\\s+"))

        if (parts != null && parts.size >= 5 && parts.firstOrNull() == "cpu") {
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size >= 4) {
                val idle = values.getOrElse(3) { 0L }
                val iowait = values.getOrElse(4) { 0L }
                val total = values.sum()
                val idleTotal = idle + iowait

                val previousTotal = lastTotal
                val previousIdle = lastIdle
                lastTotal = total
                lastIdle = idleTotal

                if (previousTotal != null && previousIdle != null) {
                    val totalDiff = total - previousTotal
                    val idleDiff = idleTotal - previousIdle
                    if (totalDiff > 0) {
                        val percent = (((totalDiff - idleDiff).coerceAtLeast(0L)) * 100L / totalDiff)
                            .toInt()
                            .coerceIn(0, 100)
                        return CpuUsageReading(percent, CpuUsageSource.PROC_STAT)
                    }
                }
            }
        }

        readFromProcesses()?.let { return CpuUsageReading(it, CpuUsageSource.PROCESS_TIME) }

        val fallback = readFromFrequency() ?: return null
        return CpuUsageReading(fallback, CpuUsageSource.CPU_FREQUENCY)
    }

    private fun readFromFrequency(): Int? {
        var currentTotal = 0L
        var maxTotal = 0L

        maximumFrequencies.indices.forEach { cpuIndex ->
            val current = readLine("/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/scaling_cur_freq")
                ?.trim()?.toLongOrNull()
            if (current != null && current > 0L) {
                val maximum = maximumFrequencies[cpuIndex]
                    ?: readLine("/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/cpuinfo_max_freq")
                        ?.trim()?.toLongOrNull()?.takeIf { it > 0L }
                        ?.also { maximumFrequencies[cpuIndex] = it }
                if (maximum != null) {
                    currentTotal += current.coerceAtMost(maximum)
                    maxTotal += maximum
                }
            }
        }

        if (maxTotal <= 0L) {
            return null
        }

        return ((currentTotal * 100L) / maxTotal).toInt().coerceIn(0, 100)
    }
}

class GpuBusyDelta {
    private var lastBusy = -1L
    private var lastTotal = -1L
    private var decreaseStreak = 0

    var perReadCounters: Boolean = false
        private set

    fun reset() {
        lastBusy = -1L
        lastTotal = -1L
        decreaseStreak = 0
    }

    fun update(busy: Long, total: Long): Int? {
        if (busy < 0L || total <= 0L) return null

        val previousBusy = lastBusy
        val previousTotal = lastTotal
        lastBusy = busy
        lastTotal = total

        if (perReadCounters) {
            return percent(busy, total)
        }
        if (previousBusy < 0L || previousTotal < 0L) return null

        val busyDelta = busy - previousBusy
        val totalDelta = total - previousTotal
        if (busyDelta < 0L || totalDelta <= 0L) {
            decreaseStreak++
            if (decreaseStreak >= 2) {
                perReadCounters = true
            }
            return null
        }

        decreaseStreak = 0
        return percent(busyDelta, totalDelta)
    }

    private fun percent(busy: Long, total: Long): Int? {
        if (total <= 0L) return null
        return ((busy * 100L) / total).toInt().coerceIn(0, 100)
    }
}

class GpuUsageSampler {
    private val busyDelta = GpuBusyDelta()
    private var lastGpuInfoMs: Long? = null
    private var lastGpuInfoWallMs: Long = 0L

    fun reset() {
        busyDelta.reset()
        lastGpuInfoMs = null
        lastGpuInfoWallMs = 0L
    }

    fun sample(): GpuUsageReading? {
        return SystemMetricsSources.gpuUsagePaths()
            .asSequence()
            .mapNotNull { readSample(it) }
            .firstOrNull()
    }

    private fun readSample(path: String): GpuUsageReading? {
        return when (path.substringAfterLast("/")) {
            "gpubusy" -> {
                val raw = SystemMetricsSources.readFirstLine(path)?.trim() ?: return null
                val parts = raw.split(Regex("\\s+"))
                if (parts.size < 2) return null
                val busy = parts[0].toLongOrNull() ?: return null
                val total = parts[1].toLongOrNull() ?: return null
                busyDelta.update(busy, total)?.let { GpuUsageReading(it, path) }
            }
            "gpuinfo" -> {
                val line = SystemMetricsSources.readNthLine(path, 1)?.trim() ?: return null
                val gpuMs = line.split(Regex("\\s+")).lastOrNull()?.toLongOrNull() ?: return null
                val now = SystemClock.elapsedRealtime()
                val previousMs = lastGpuInfoMs
                val previousWall = lastGpuInfoWallMs
                lastGpuInfoMs = gpuMs
                lastGpuInfoWallMs = now
                if (previousMs == null || previousWall <= 0L) return null
                val wallDelta = now - previousWall
                if (wallDelta <= 0L) return null
                val gpuDelta = (gpuMs - previousMs).coerceAtLeast(0L)
                GpuUsageReading(((gpuDelta * 100L) / wallDelta).toInt().coerceIn(0, 100), path)
            }
            else -> SystemMetricsSources.readPercentFromLine(path)?.let { percent ->
                GpuUsageReading(percent, path)
            }
        }
    }
}
