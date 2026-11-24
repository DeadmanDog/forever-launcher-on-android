package app.gamenative.externaldisplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.2.16: a keyboard that cuts a very long paste short must not shorten the guide; Android's clipboard wins when it holds more. */
class PasteTextChoiceTest {
    private val guide = buildString { var i = 0; while (length < 120_000) append("Step ${i++}: ő ű á é\r\n") }

    @Test fun aTruncatedCommitIsReplacedByTheWholeClipboard() =
        assertEquals(guide, IMEInputReceiver.fullClipboardText(guide.substring(0, 4096), guide))

    @Test fun lineBreakDifferencesDoNotHideTheMatch() =
        assertEquals(guide, IMEInputReceiver.fullClipboardText(guide.substring(0, 5000).replace("\r\n", "\n"), guide))

    @Test fun anUnrelatedClipboardIsNotPasted() =
        assertEquals("my own long text ".repeat(10), IMEInputReceiver.fullClipboardText("my own long text ".repeat(10), guide))

    @Test fun noClipboardOrAShorterOneKeepsTheCommit() {
        assertEquals(guide, IMEInputReceiver.fullClipboardText(guide, null))
        assertEquals(guide, IMEInputReceiver.fullClipboardText(guide, guide.substring(0, 100)))
    }
}
