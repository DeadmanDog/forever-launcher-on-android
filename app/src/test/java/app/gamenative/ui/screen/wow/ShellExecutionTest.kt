package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ShellExecutionTest {
    private class Child : Process() {
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
    @Test fun stopBeforePublicationKillsLateChild() {
        val state = ShellExecution(); val ticket = state.reserve(); state.cancel()
        val child = Child(); assertFalse(state.publish(ticket, child)); assertTrue(child.destroyed)
        state.complete(ticket); assertFalse(state.cancelled(state.reserve()))
    }
    @Test fun duplicateRunIsRejectedAndOldCompletionDoesNotClearNewRun() {
        val state = ShellExecution(); val old = state.reserve()
        assertThrows(IllegalStateException::class.java) { state.reserve() }
        state.complete(old); val current = state.reserve(); state.complete(old)
        assertFalse(state.cancelled(current))
    }
    @Test fun stopAfterPublicationReturnsOwnedChild() {
        val state = ShellExecution(); val ticket = state.reserve(); val child = Child()
        assertTrue(state.publish(ticket, child)); assertSame(child, state.cancel()); assertTrue(state.cancelled(ticket))
    }
    @Test fun outputIsBoundedWithoutNewlines() {
        val output = ShellOutput(emptyList(), 100)
        repeat(10000) { output.append(CharArray(4096) { 'x' }, 4096) }
        assertEquals(100, output.text().length)
    }
    @Test fun redactionWorksAcrossChunksAndDisplayBoundary() {
        val output = ShellOutput(listOf("secret-token"), 20)
        listOf("123456789012345sec", "ret-token remainder").forEach { output.append(it.toCharArray(), it.length) }
        assertFalse(output.text().contains("sec")); assertTrue(output.text().contains("<reda"))
    }
}
