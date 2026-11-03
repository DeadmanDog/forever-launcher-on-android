package app.gamenative.ui.screen.wow

import android.app.Application
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.winlator.contents.ContentsManager
import org.robolectric.RuntimeEnvironment
import android.net.Uri

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class DeveloperShellResourceTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun completedShellDrainsOutputRedactsSecretsAndCanRunAgain() {
        assumeTrue(File("/bin/sh").isFile)
        val shell = DeveloperShell()
        val request = DeveloperShell.Request(listOf("/bin/sh", "-c", "printf '%s' \"\$TOKEN\""), temp.newFolder(), mapOf("TOKEN" to "private-token"))
        val output = shell.run(request)
        assertTrue(output.contains("<redacted>")); assertFalse(output.contains("private-token"))
        assertTrue(shell.run(request).contains("Exit: 0"))
    }
    @Test fun cancelledReservationDoesNotExecuteCommand() {
        assumeTrue(File("/bin/sh").isFile)
        val root = temp.newFolder(); val shell = DeveloperShell(); val ticket = shell.prepareRun(); shell.stop()
        val request = DeveloperShell.Request(listOf("/bin/sh", "-c", "touch marker"), root, emptyMap())
        assertEquals("Command cancelled.", shell.run(request, ticket = ticket)); assertFalse(File(root, "marker").exists())
    }
    @Test fun dxvkAndRuntimeImportsRespectTheSharedStagingLock() {
        val context = RuntimeEnvironment.getApplication()
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (runtime in listOf(false, true)) {
                val entered = CountDownLatch(1)
                val pending = synchronized(ContentsManager.getLocalInstallLock()) {
                    val task = pool.submit {
                        entered.countDown()
                        runCatching {
                            val uri = Uri.fromFile(File(temp.root, "missing.wcp"))
                            if (runtime) WowDeveloperComponents.importRuntime(context, uri)
                            else WowDxvkImporter.importWcp(context, uri) { false }
                        }
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    assertThrows(TimeoutException::class.java) { task.get(100, TimeUnit.MILLISECONDS) }
                    task
                }
                pending.get(5, TimeUnit.SECONDS)
            }
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
