package app.gamenative

import android.content.Context
import com.winlator.core.RuntimeLog
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** Optional bounded Java-crash capture, always delegates to Android's existing handler. */
object CrashHandler {
    private var installed: Thread.UncaughtExceptionHandler? = null
    private var previous: Thread.UncaughtExceptionHandler? = null
    @Synchronized fun configure(context: Context, enabled: Boolean) {
        if (!enabled) {
            if (Thread.getDefaultUncaughtExceptionHandler() === installed) Thread.setDefaultUncaughtExceptionHandler(previous)
            installed=null;previous=null
            return
        }
        if (installed!=null) return
        previous=Thread.getDefaultUncaughtExceptionHandler()
        val delegate=previous
        val file=File(context.filesDir,"diagnostics/wow/java-crash.txt")
        installed=Thread.UncaughtExceptionHandler { thread, error ->
            try { file.parentFile?.mkdirs();writeCrash(file,thread.name,error) } catch (_:Throwable) { }
            finally {
                if(delegate!=null) delegate.uncaughtException(thread,error)
                else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(installed)
    }
    internal fun writeCrash(file: File, thread: String, error: Throwable) {
        RuntimeLog(file,64L*1024).use { log ->
            fun line(s:String) { val b=(s.take(4096)+"\n").toByteArray();log.append(b,b.size) }
            line("timestamp=${System.currentTimeMillis()} thread=$thread")
            val seen=Collections.newSetFromMap(IdentityHashMap<Throwable,Boolean>())
            var current:Throwable?=error;var depth=0
            while(current!=null && depth++<8 && seen.add(current)) {
                line(current.javaClass.name+": "+current.message)
                current.stackTrace.take(100).forEach { line(" at $it") };current=current.cause
            }
        }
    }
}
