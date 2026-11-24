package app.gamenative.externaldisplay

import android.app.Activity
import android.os.Looper
import android.view.View
import com.winlator.xconnector.ShadowRecordingXConnectorNative
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PasteWorkerCancellationTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup()
    private val x = XTestServer()
    private val receiver = IMEInputReceiver(activity.get(), x.server).also {
        activity.get().setContentView(it)
        it.showKeyboard()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun startWorker(): Thread {
        val before = Thread.getAllStackTraces().keys.toSet()
        receiver.pasteViaClipboard("Synthetic guide text; never a credential.")
        return Thread.getAllStackTraces().keys.single { it.name == "clipboard-paste" && it !in before }
    }

    @After fun cleanup() {
        Thread.getAllStackTraces().keys.filter { it.name == "clipboard-paste" }.forEach { it.interrupt() }
        activity.pause().stop().destroy()
    }

    /** Claude 1.2.17: closing the keyboard keeps the paste that waits for Wine (it is delivered, not dropped). */
    @Test fun hiddenKeyboardKeepsThePasteWaitingForWine() {
        val thread = startWorker()
        receiver.hideKeyboard()
        shadowOf(Looper.getMainLooper()).idle()
        thread.join(400)
        assertTrue("A paste made just before closing the keyboard must still be delivered", thread.isAlive)
    }

    @Test fun detachedReceiverInterruptsWorkerWaitingForWine() {
        val thread = startWorker()
        activity.get().setContentView(View(activity.get()))
        thread.join(400)
        assertFalse("Detached receiver must not retain clipboard delivery", thread.isAlive)
    }

    @Test fun secondPasteCancelsFirstDeliveryRatherThanPastingTwice() {
        val first = startWorker()
        val second = startWorker()
        first.join(400)
        assertFalse("Superseded paste worker must stop", first.isAlive)
        assertTrue("Latest paste still waits for Wine", second.isAlive)
    }
}
