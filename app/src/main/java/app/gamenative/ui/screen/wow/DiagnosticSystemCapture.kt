package app.gamenative.ui.screen.wow

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.winlator.core.RuntimeLog
import java.io.File

internal object DiagnosticSystemCapture {
    fun capture(context: Context, directory: File) {
        directory.mkdirs()
        RuntimeLog(File(directory,"previous-exits.txt"),64L*1024).use { log ->
            fun write(s:String) { val b=(s+"\n").toByteArray();log.append(b,b.size) }
            write("Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}; historical records, not all from this session.")
            if (Build.VERSION.SDK_INT < 30) { write("ApplicationExitInfo unavailable below Android 11.");return }
            try {
                val am=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.getHistoricalProcessExitReasons(context.packageName,0,3).forEachIndexed { index, info ->
                    write("timestamp=${info.timestamp} pid=${info.pid} reason=${info.reason} status=${info.status} rss_kb=${info.rss} pss_kb=${info.pss} process=${info.processName}")
                    write("description=${info.description?.take(2000)}")
                    try {
                        val stream=info.traceInputStream
                        if (stream==null) write("trace[$index]: unavailable")
                        else stream.use { input ->
                            RuntimeLog(File(directory,"exit-trace-$index.bin"),1024L*1024).use { out ->
                                val buffer=ByteArray(8192); var left=1024*1024
                                while(left>0) { val n=input.read(buffer,0,minOf(buffer.size,left));if(n<0)break;out.append(buffer,n);left-=n }
                            }
                        }
                    } catch (e:Exception) { write("trace[$index]: ${e.javaClass.simpleName}") }
                }
            } catch (e:Exception) { write("Historical exit data unavailable: ${e.javaClass.simpleName}") }
        }
    }
}
