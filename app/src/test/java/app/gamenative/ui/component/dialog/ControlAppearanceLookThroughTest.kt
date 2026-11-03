package app.gamenative.ui.component.dialog

import com.winlator.inputcontrols.ControlElement
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAppearanceLookThroughTest {
    @Test
    fun `copying an appearance keeps an unset look-through unset`() {
        val source = ControlElement(null).apply { lookThroughSetting = null }
        val target = ControlElement(null).apply { lookThroughSetting = true }

        ControlAppearance.capture(source).applyTo(target)

        assertNull(target.lookThroughSetting)
    }

    @Test
    fun `copying an appearance carries an enabled look-through`() {
        val source = ControlElement(null).apply { lookThroughSetting = true }
        val target = ControlElement(null).apply { lookThroughSetting = false }

        ControlAppearance.capture(source).applyTo(target)

        assertTrue(target.isLookThrough)
    }
}
