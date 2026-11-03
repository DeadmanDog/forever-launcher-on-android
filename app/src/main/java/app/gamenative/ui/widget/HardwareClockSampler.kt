package app.gamenative.ui.widget

import java.io.File

/** Read-only sysfs sampling. CPU cpufreq exports kHz; Linux devfreq exports Hz. */
internal class HardwareClockSampler(
    private val cpuRoot: File = File("/sys/devices/system/cpu"),
    private val gpuRoots: List<File> = listOf(File("/sys/class/devfreq"), File("/sys/devices/virtual/devfreq")),
    private val fixedGpuHz: List<File> = listOf(
        File("/sys/class/kgsl/kgsl-3d0/gpuclk"),
        File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"),
        File("/sys/class/devfreq/gpu/cur_freq"),
    ),
    /** Qualcomm also exports the clock in MHz; these are often readable when the Hz nodes are not. */
    private val fixedGpuMHz: List<File> = listOf(
        File("/sys/class/kgsl/kgsl-3d0/clock_mhz"),
        File("/sys/kernel/gpu/gpu_clock"),
    ),
) {
    // Discover once per HUD instance, including the unavailable case. Never scan on every tick.
    private val cpuPaths by lazy {
        val policies = File(cpuRoot, "cpufreq").listFiles().orEmpty()
            .filter { it.name.matches(Regex("policy[0-9]+")) }
        val cores = cpuRoot.listFiles().orEmpty().filter { it.name.matches(Regex("cpu[0-9]+")) }
            .map { File(it, "cpufreq") }
        // Some firmware permits reading a known file but denies directory enumeration.
        val knownCores = (0 until Runtime.getRuntime().availableProcessors().coerceIn(1, 128))
            .map { File(cpuRoot, "cpu$it/cpufreq") }
        (policies + cores + knownCores).distinctBy { it.path }.map { directory ->
            listOf(File(directory, "cpuinfo_cur_freq"), File(directory, "scaling_cur_freq"))
        }
    }
    private val gpuPaths by lazy {
        (fixedGpuHz + gpuRoots.flatMap { root ->
            root.listFiles().orEmpty().filter {
                val name = it.name.lowercase()
                listOf("gpu", "kgsl", "mali", "g3d").any(name::contains)
            }.map { File(it, "cur_freq") }
        }).distinctBy { it.path }
    }

    fun cpuMHz(): Int? = cpuPaths.mapNotNull { alternatives ->
        alternatives.firstNotNullOfOrNull { readMHz(it, 1_000L) }
    }.maxOrNull()
    fun gpuMHz(): Int? = gpuPaths.firstNotNullOfOrNull { readMHz(it, 1_000_000L) }
        ?: fixedGpuMHz.firstNotNullOfOrNull { readMHz(it, 1L) }

    private fun readMHz(file: File, unitsPerMHz: Long): Int? =
        mhz(SystemMetricsSources.readLongFromLine(file.path), unitsPerMHz)

    companion object {
        /** GPU clock/busy sources an app might read; Android may deny some of them (SELinux). */
        val gpuProbePaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/clock_mhz", "/sys/kernel/gpu/gpu_clock", "/sys/class/devfreq/gpu/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpubusy", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/kernel/gpu/gpu_busy",
            // Upper limits: what caps the clock (thermal level, user/vendor max, frequency table).
            "/sys/class/kgsl/kgsl-3d0/max_clock_mhz", "/sys/class/kgsl/kgsl-3d0/min_clock_mhz",
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk", "/sys/class/kgsl/kgsl-3d0/thermal_pwrlevel",
            "/sys/class/kgsl/kgsl-3d0/max_pwrlevel", "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
            "/sys/class/kgsl/kgsl-3d0/num_pwrlevels", "/sys/class/kgsl/kgsl-3d0/freq_table_mhz",
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
            "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq", "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq",
            "/sys/class/kgsl/kgsl-3d0/devfreq/governor",
            "/sys/kernel/gpu/gpu_max_clock", "/sys/kernel/gpu/gpu_min_clock", "/sys/kernel/gpu/gpu_freq_table",
            "/sys/kernel/gpu/gpu_governor", "/sys/kernel/gpu/gpu_model",
        )

        /** "path=value" per source, or missing/denied: tells from a diagnostic log which ones this phone exposes. */
        fun probe(paths: List<String> = gpuProbePaths): String = paths.joinToString("; ") { path ->
            val file = File(path)
            path + "=" + when {
                !file.exists() -> "missing"
                !file.canRead() -> "denied"
                else -> runCatching { file.readText().trim().replace('\n', ' ').take(160) }.getOrElse { "error" }
            }
        }

        internal fun mhz(raw: Long?, unitsPerMHz: Long): Int? {
            if (raw == null || raw <= 0 || unitsPerMHz <= 0) return null
            return (raw / unitsPerMHz).takeIf { it in 1..10_000 }?.toInt()
        }
    }
}
