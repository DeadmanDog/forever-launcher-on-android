package app.gamenative.ui.screen.wow

import android.os.SystemClock
import android.os.Process
import com.winlator.core.OneShotTasks
import com.winlator.core.RuntimeLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Created only for an enabled session; no worker, sysfs reads or files while off. */
internal class DiagnosticSampler(val directory: File, detailed: Boolean) : AutoCloseable {
    val frames = DiagnosticFrames()
    val display = WowDisplayGeometry(directory)
    // New columns are appended so existing CSV readers keep their column positions.
    private val performanceHeader = "elapsed_ms,frame_source,frame_intervals,mean_interval_ms,max_interval_ms,over33ms,cpu0_cap_khz,cpu0_cur_khz,cpu6_cap_khz,cpu6_cur_khz,app_rss_kb,gpu_busy,scanout_frames,compositor_frames,scanout_state,frame_window,frame_windows," +
        com.winlator.renderer.VulkanRenderer.DISPLAY_STATS_HEADER + "," + com.winlator.xserver.extensions.PresentPhase.RELEASE_STATS_HEADER +
        ",power_mw,gpu_mhz,battery_status"
    // Both CSVs rotate (the newest part plus a .previous one) and repeat their header: a log that stopped at its size limit lost
    // the end of a long session, which is where the player saw the problem (1.1.1: frames.csv ended ~32 min into a 58 min game).
    private val writer = RuntimeLog(File(directory,"performance.csv"), 1024L*1024, true, (performanceHeader + "\n").toByteArray())
    // One row per game frame (see VulkanRenderer.takeFrameLog): when its content arrived, went to the panel and was shown.
    private val frameWriter = RuntimeLog(File(directory,"frames.csv"), 8L*1024*1024, true,
        "present_id,kind,content_gen,content_updates,content_ns,queue_ns,display_ns,margin_us\n".toByteArray())
    private val logcatWriter=RuntimeLog(File(directory,"app-logcat.log"),RuntimeLog.MAX_BYTES,true)
    private val logcat = try {
        ProcessBuilder("/system/bin/logcat","--uid=${Process.myUid()}","-v","threadtime","-T","1",if(detailed) "*:D" else "*:W")
            .redirectErrorStream(true).start()
    } catch (e:Exception) {
        val b=("Own-UID logcat unavailable: ${e.javaClass.simpleName}\n").toByteArray();logcatWriter.append(b,b.size)
        null
    }
    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"wow-diagnostic-sampler") }
    init {
        // A header line, so an empty capture (no warnings from this app) can be told from a capture that never ran.
        if (logcat != null) {
            val b = ("# own-UID logcat, ${if (detailed) "*:D" else "*:W (warnings and errors only)"}, started ${java.util.Date()}\n").toByteArray()
            logcatWriter.append(b, b.size)
        }
        logcat?.let { child -> OneShotTasks.execute {
            try { child.inputStream.use { input -> val buffer=ByteArray(8192);while(true) { val n=input.read(buffer);if(n<0)break;logcatWriter.append(buffer,n) } } }
            catch (_:Exception) { }
            finally { logcatWriter.close() }
        } }
        com.winlator.renderer.ScanoutStats.reset()
        app.gamenative.display.GamescopeFrameLog.reset()
        com.winlator.renderer.FrameTrace.set(true)
        executor.scheduleWithFixedDelay({
            try {
                val f=frames.take()
                val values=listOf(SystemClock.elapsedRealtime(),f.source,f.intervals,f.meanMs,f.maxMs,f.over33Ms,
                    read("/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"),read("/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"),
                    read("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"),read("/sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq"),
                    try { File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim() ?: "N/A" } } catch (_:Exception) { "N/A" },
                    read("/sys/class/kgsl/kgsl-3d0/gpubusy"))
                line(values.joinToString(",") { it.toString().replace(',',' ').replace('\n',' ') } + "," +
                    com.winlator.renderer.ScanoutStats.take() + ",${f.windowId},${f.windows}," +
                    com.winlator.renderer.VulkanRenderer.takeDisplayStats() + "," + com.winlator.xserver.extensions.PresentPhase.takeReleaseStats() +
                    "," + powerMilliwatts() + "," + read("/sys/class/kgsl/kgsl-3d0/clock_mhz") + "," + read("/sys/class/power_supply/battery/status").replace(',',' '))
                writeFrameLog()
            } catch (_:Exception) { }
        },1,1,TimeUnit.SECONDS)
    }
    /**
     * Whole-phone draw from the battery (|current| x voltage), like the HUD's PWR; "N/A" when unreadable. Only meaningful while
     * not charging (battery_status), so power A/B tests (XServer vs Gamescope, settings) can be compared from the logs.
     */
    private fun powerMilliwatts(): String {
        val ua = read("/sys/class/power_supply/battery/current_now").toLongOrNull() ?: return "N/A"
        val uv = read("/sys/class/power_supply/battery/voltage_now").toLongOrNull() ?: return "N/A"
        return (kotlin.math.abs(ua) * uv / 1_000_000_000L).toString()
    }
    private fun read(path:String)=try { File(path).readText().trim().take(128) } catch (_:Exception) { "N/A" }
    @Synchronized private fun line(value:String) { val b=(value+"\n").toByteArray();writer.append(b,b.size) }
    @Synchronized private fun frameLine(value:String) { val b=(value+"\n").toByteArray();frameWriter.append(b,b.size) }
    private fun writeFrameLog() {
        writeGamescopeFrameLog()
        val rows = com.winlator.renderer.VulkanRenderer.takeFrameLog()
        if (rows.isEmpty()) return
        val text = StringBuilder(rows.size * 12)
        var i = 0
        while (i + 7 < rows.size) {
            val kind = when (rows[i + 1].toInt()) { 1 -> "shown"; 2 -> "dropped"; 3 -> "missing"; 4 -> "skipped"; else -> "?" }
            text.append(rows[i]).append(',').append(kind).append(',').append(rows[i + 2]).append(',').append(rows[i + 3]).append(',')
                .append(rows[i + 4]).append(',').append(rows[i + 5]).append(',').append(rows[i + 6]).append(',').append(rows[i + 7] / 1000).append('\n')
            i += 8
        }
        val bytes = text.toString().toByteArray()
        synchronized(this) { frameWriter.append(bytes, bytes.size) }
    }
    /** Gamescope sessions: one "produced" row per game frame (content_ns = when it was produced); the display columns stay 0
     *  because the panel latch happens in the bridge's own presenter. */
    private fun writeGamescopeFrameLog() {
        val rows = app.gamenative.display.GamescopeFrameLog.take()
        if (rows.isEmpty()) return
        val text = StringBuilder(rows.size * 20)
        var i = 0
        while (i + 1 < rows.size) {
            text.append(rows[i]).append(",produced,0,0,").append(rows[i + 1]).append(",0,0,0\n")
            i += 2
        }
        val bytes = text.toString().toByteArray()
        synchronized(this) { frameWriter.append(bytes, bytes.size) }
    }
    @Synchronized override fun close() { com.winlator.renderer.FrameTrace.set(false);executor.shutdownNow();logcat?.destroy();logcatWriter.close();writer.close();frameWriter.close() }
}
