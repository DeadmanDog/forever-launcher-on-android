package app.gamenative.externaldisplay

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.Atom
import com.winlator.xserver.harness.ShadowNoNativeDrawable
import com.winlator.xserver.harness.XTestServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PasteIsolationTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup()
    private val x = XTestServer()
    private val receiver = IMEInputReceiver(activity.get(), x.server)
    private val sample = "Synthetic guide line; not a credential. ".repeat(10)

    private fun stageCommit() {
        activity.get().setContentView(receiver)
        receiver.showKeyboard()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(receiver.hasFocus())
        receiver.onCreateInputConnection(EditorInfo()).commitText(sample, 1)
    }

    private fun noServerClipboard() = assertNull(x.server.selectionManager.getSelection(Atom.internAtom("CLIPBOARD")).owner)

    @After fun cleanup() {
        Thread.getAllStackTraces().keys.filter { it.name == "clipboard-paste" }.forEach { it.interrupt() }
        activity.pause().stop().destroy()
    }

    /** Claude 1.2.17: Back right after pasting a guide closes the keyboard; the paste must still arrive (Codex's version dropped it). */
    @Test fun hidingKeyboardSendsTheGatheredPasteAtOnce() {
        stageCommit()
        receiver.hideKeyboard()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(x.server.selectionManager.getSelection(Atom.internAtom("CLIPBOARD")).owner)
    }

    @Test fun removingReceiverCancelsGatheredPasteBeforeItReachesClipboard() {
        stageCommit()
        activity.get().setContentView(View(activity.get()))
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        noServerClipboard()
    }

    @Test fun aMiddleSubstringNeverExpandsIntoUncommittedClipboardText() {
        val committed = "Synthetic guide section ".repeat(4)
        val clipboard = "Uncommitted prefix\n$committed\nUncommitted suffix"
        assertEquals(committed, IMEInputReceiver.fullClipboardText(committed, clipboard))
    }

    @Test fun emptyAfterNewlineNormalizationDoesNotSelectClipboard() {
        assertEquals("\r".repeat(64), IMEInputReceiver.fullClipboardText("\r".repeat(64), "x".repeat(128)))
    }

    @Test fun anActualTruncatedPrefixStillRecoversTheFullGuide() {
        assertEquals(sample, IMEInputReceiver.fullClipboardText(sample.take(100), sample))
    }
}
