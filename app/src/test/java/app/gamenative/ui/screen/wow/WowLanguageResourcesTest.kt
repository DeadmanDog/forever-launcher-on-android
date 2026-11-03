package app.gamenative.ui.screen.wow

import android.app.Application
import app.gamenative.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Quick Menu reads Android resources: it must follow the in-app language, not the phone's. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en")
class WowLanguageResourcesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun quickMenuTextsFollowTheChosenLanguageOnAnEnglishPhone() {
        assertEquals("Quick Menu", context.getString(R.string.quick_menu_title))
        assertEquals("Gyorsmenü", localizedContext(context, "hu").getString(R.string.quick_menu_title))
        assertEquals("Képernyőeffektek", localizedContext(context, "hu").getString(R.string.screen_effects))
        assertEquals("3 fut, egy folyamatra koppintva bezárható", localizedContext(context, "hu").getString(R.string.tools_wine_processes_running_hint, 3))
        assertEquals("Quick Menu", localizedContext(context, "en").getString(R.string.quick_menu_title))
        // Untranslated entries (numbers, formats) fall back to the default texts.
        assertEquals("4", localizedContext(context, "hu").getString(R.string.performance_hud_preset_full))
    }

    @Config(qualifiers = "hu")
    @Test fun englishChosenOnAHungarianPhoneStaysEnglish() {
        assertEquals("Gyorsmenü", context.getString(R.string.quick_menu_title))
        assertEquals("Quick Menu", localizedContext(context, "en").getString(R.string.quick_menu_title))
    }

    @Test fun savingTheLanguageUpdatesTheAppWideState() {
        WowUiLanguage.save(context, WowLanguage.HU)
        assertEquals(WowLanguage.HU, WowUiLanguage.current)
        WowUiLanguage.save(context, WowLanguage.EN)
        WowUiLanguage.load(context)
        assertEquals(WowLanguage.EN, WowUiLanguage.current)
        WowUiLanguage.save(context, WowLanguage.SYSTEM)
    }
}
