package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WowComponentSelectionTest {
    @Test fun `Turnip config version maps current before legacy version`() {
        assertEquals("custom-turnip", WowComponentSelection.driverCurrent("version=custom-turnip,adrenotoolsTurnip=1", "old"))
        assertEquals("legacy", WowComponentSelection.driverCurrent("", "legacy"))
    }
    @Test fun `Turnip current and selected are separate and unchanged selection cannot apply`() {
        val catalog = setOf("default-driver", "custom-driver")
        val current = WowComponentSelection("", null).refresh("default-driver", catalog)
        assertEquals("default-driver", current.selected)
        assertFalse(current.canApply(catalog))
        val selected = current.select("custom-driver")
        assertEquals("default-driver", selected.current)
        assertTrue(selected.canApply(catalog))
        assertEquals(selected, selected.refresh("default-driver", catalog))
        assertFalse(selected.refresh("custom-driver", catalog).canApply(catalog))
    }
    @Test fun `Proton current and selection survive catalog refresh independently`() {
        val catalog = setOf("proton-a-arm64ec-1", "wine-b-arm64ec-2")
        val state = WowComponentSelection("", null).refresh("proton-a-arm64ec-1", catalog).select("wine-b-arm64ec-2")
        assertEquals("proton-a-arm64ec-1", state.current)
        assertTrue(state.canApply(catalog))
        assertEquals(state, state.refresh("proton-a-arm64ec-1", catalog))
        assertFalse(state.refresh("wine-b-arm64ec-2", catalog).canApply(catalog))
    }
    @Test fun `reopen initializes selection from saved current for both selectors`() {
        for (saved in listOf("Turnip-custom", "proton-custom-arm64ec-9")) {
            val state = WowComponentSelection("", null).refresh(saved, setOf(saved, "other"))
            assertEquals(saved, state.current)
            assertEquals(saved, state.selected)
            assertFalse(state.canApply(setOf(saved, "other")))
        }
    }
    @Test fun `removed selection cannot apply and missing current is never replaced by fallback`() {
        val state = WowComponentSelection("missing-current", "removed").refresh("missing-current", setOf("other"))
        assertEquals("missing-current", state.current)
        assertNull(state.selected)
        assertFalse(state.canApply(setOf("other")))
    }
}
