package app.gamenative.externaldisplay

import androidx.test.core.app.ApplicationProvider
import com.winlator.xconnector.ShadowRecordingXConnectorNative
import com.winlator.xserver.Atom
import com.winlator.xserver.harness.ShadowNoNativeDrawable
import com.winlator.xserver.harness.XTestServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [ShadowNoNativeDrawable::class, ShadowRecordingXConnectorNative::class])
class ImeControlPrivacyTest {
    @Test fun skippedControlsNeverExposeTypedCodepointsInLogs() {
        val x = XTestServer()
        val receiver = IMEInputReceiver(ApplicationProvider.getApplicationContext(), x.server)
        ShadowLog.clear()
        receiver.typeText(charArrayOf(1.toChar(), 2.toChar(), 127.toChar()).concatToString())
        assertTrue("Input-derived codepoints must not enter logs", ShadowLog.getLogsForTag("IMEInputReceiver").isEmpty())
    }

    @Test fun dedicatedTypingAlwaysBypassesClipboardRegardlessOfLength() {
        val x = XTestServer()
        val receiver = IMEInputReceiver(ApplicationProvider.getApplicationContext(), x.server)
        receiver.typeText("SyntheticNonCredentialText".repeat(10))
        assertNull(x.server.selectionManager.getSelection(Atom.internAtom("CLIPBOARD")).owner)
    }
}
