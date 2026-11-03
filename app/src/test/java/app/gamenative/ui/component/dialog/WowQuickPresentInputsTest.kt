package app.gamenative.ui.component.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WowQuickPresentInputsTest {
    @Test
    fun `a saved present mode override wins over the Quick tab FIFO choice`() {
        val inputs = WowQuickPresentInputs("MESA_VK_WSI_PRESENT_MODE=mailbox", "", "")

        assertEquals("mailbox", inputs.effective("fifo"))
        assertTrue(inputs.savedOverride)
    }

    @Test
    fun `a saved FIFO override keeps the latency choice usable under a Mailbox menu choice`() {
        val inputs = WowQuickPresentInputs("MESA_VK_WSI_PRESENT_MODE=fifo", "", "")

        assertEquals("fifo", inputs.effective("mailbox"))
    }

    @Test
    fun `the default menu choice follows the driver and then the renderer present mode`() {
        assertEquals("mailbox", WowQuickPresentInputs("", "mailbox", "fifo").effective(""))
        assertEquals("immediate", WowQuickPresentInputs("", "", "immediate").effective(""))
        assertEquals("fifo", WowQuickPresentInputs().effective(""))
        assertFalse(WowQuickPresentInputs("DXVK_HUD=fps", "", "").savedOverride)
    }
}
