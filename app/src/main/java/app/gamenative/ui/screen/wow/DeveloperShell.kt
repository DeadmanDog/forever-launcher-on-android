package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.contents.ContentsManager
import com.winlator.container.ContainerOverlay
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit command execution only; opening the screen never starts a process. */
internal class DeveloperShell {
    companion object {
        fun isSensitiveName(key: String) = com.winlator.core.envvars.EnvVarRedaction.isRedacted(key)
        fun redactedEnvironment(values: String): String {
            val env = EnvVars(values)
            return env.joinToString("\n") { key ->
                val secret = isSensitiveName(key)
                "$key=${if (secret) "<redacted>" else env.get(key)}"
            }
        }
    }
    data class Request(val command: List<String>, val directory: File, val environment: Map<String, String>, val cleanupCommand: List<String>? = null)
    private val execution = ShellExecution()
    fun prepareRun() = execution.reserve()
    fun finishRun(ticket: ShellExecution.Ticket) = execution.complete(ticket)

    // The saved variables come in through mergeUserOverrides at the end (launcher-owned names kept, search lists appended).
    fun androidRequest(container: Container, command: String): Request {
        val env = EnvVars()
        env.put("HOME", container.rootDir.path)
        env.put("WINEPREFIX", File(container.rootDir, ".wine").path)
        WowLaunchEnvironment.mergeUserOverrides(container, env)
        return Request(listOf("/system/bin/sh", "-c", command), container.rootDir, env.associateWith { env.get(it) })
    }

    fun wineRequest(context: Context, container: Container, command: String): Request {
        val p = WowDeveloperComponents.runtimeProfiles(context).singleOrNull { WowDeveloperComponents.runtimeId(it) == container.wineVersion }
            ?: error("Selected ARM64EC/Bionic runtime is unavailable. No fallback was applied.")
        val runtime = ContentsManager.getInstallDir(context, p)
        val image = ImageFs.find(context)
        val env = EnvVars()
        env.put("HOME", container.rootDir.path)
        env.put("WINEPREFIX", File(container.rootDir, ".wine").path)
        env.put("PATH", "${runtime.path}/bin:${image.rootDir.path}/usr/bin:/system/bin")
        env.put("LD_LIBRARY_PATH", "${image.rootDir.path}/usr/lib:/system/lib64:${runtime.path}/lib")
        WowRuntimeTuning.execShim(context.applicationInfo.nativeLibraryDir).let { path ->
            check(File(path).isFile) { "The exec shim (libwowexec.so) is missing." }
            env.put("LD_PRELOAD", ContainerOverlay.appendPreload(env.get("LD_PRELOAD"), path, ":"))
        }
        env.put("DISPLAY", ":0")
        ContainerOverlay.applyBionicLaunchEnv(context, container, env)
        check(File(container.rootDir, ".wine/system.reg").isFile) { "Existing prefix registry missing; shell will not create a prefix." }
        WowLaunchEnvironment.mergeUserOverrides(container, env)
        return Request(listOf("/system/bin/linker64", File(runtime, "bin/wine").path, "cmd", "/d", "/c", command),
            container.rootDir, env.associateWith { env.get(it) }, listOf("/system/bin/linker64", File(runtime, "bin/wineserver").path, "-k"))
    }

    fun run(request: Request, timeoutSeconds: Long = 30, ticket: ShellExecution.Ticket = prepareRun()): String {
        if (execution.cancelled(ticket)) { finishRun(ticket); return "Command cancelled." }
        check(!app.gamenative.PluviaApp.keepAlive) { "Shell is unavailable during an active game session." }
        val builder = ProcessBuilder(request.command).directory(request.directory).redirectErrorStream(true)
        builder.environment().putAll(request.environment)
        val child = try { builder.start() } catch (e: Exception) { finishRun(ticket); throw e }
        if (!execution.publish(ticket, child)) {
            closeStreams(child); finishRun(ticket); return "Command cancelled."
        }
        val secrets = request.environment.filter { isSensitiveName(it.key) && it.value.isNotEmpty() }.values.toList()
        // Drain the pipe even after the display limit, without readLine's unbounded line allocation.
        val output = ShellOutput(secrets)
        val reader = Thread {
            runCatching { child.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.append(buffer, count)
                }
            } }
        }.apply { isDaemon = true; start() }
        try {
            val completed = child.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) stop()
            reader.join(1000)
            return output.text() + if (completed) "\nExit: ${child.exitValue()}" else "\nCommand timed out."
        } finally {
            terminate(child)
            closeStreams(child)
            reader.join(1000)
            finishRun(ticket)
            request.cleanupCommand?.let { cleanup ->
                if (!app.gamenative.PluviaApp.keepAlive) runCatching {
                    val builder = ProcessBuilder(cleanup).directory(request.directory).redirectErrorStream(true)
                    builder.environment().putAll(request.environment)
                    val server = builder.redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
                    try { if (!server.waitFor(3, TimeUnit.SECONDS)) server.destroyForcibly() }
                    finally { closeStreams(server) }
                }
            }
        }
    }

    fun stop() {
        val child = execution.cancel() ?: return
        // /proc traversal belongs off the UI thread; cancellation is published synchronously.
        Thread { terminate(child) }.apply { isDaemon = true; name = "WoW-shell-stop"; start() }
    }

    private fun closeStreams(child: Process) {
        runCatching { child.inputStream.close() }
        runCatching { child.errorStream.close() }
        runCatching { child.outputStream.close() }
    }

    private fun terminate(child: Process) {
        runCatching {
            val field = child.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            val root = field.getInt(child)
            val processes = com.winlator.core.ProcessHelper.listSubProcesses()
            val owned = mutableSetOf(root)
            var changed: Boolean
            do { changed = false; processes.forEach { if (it.ppid in owned && owned.add(it.pid)) changed = true } } while (changed)
            owned.filter { it != root }.reversed().forEach { com.winlator.core.ProcessHelper.killProcess(it) }
        }
        child.destroyForcibly()
    }
}
