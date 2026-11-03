package app.gamenative.ui.component.dialog

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.ui.screen.wow.LocalWowHungarian
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The portrait Settings tabs at a narrow width and a large font: every label fits and every tab has the same height. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WowTabLabelLayoutTest {
    private val rule = createComposeRule()

    // The release variant has no ui-test-manifest, so the host activity of the compose rule is registered by hand.
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val component = ComponentName(context, ComponentActivity::class.java)
            val packageManager = Shadows.shadowOf(context.packageManager)
            packageManager.addActivityIfNotPresent(component)
            packageManager.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }).around(rule)

    private data class Case(val width: Int, val hungarian: Boolean, val fontScale: Float)

    private val english = listOf("Quick", "Game", "Components", "Advanced")
    private val hungarian = listOf("Gyors", "Játék", "Komponensek", "Haladó")

    @Test
    fun `portrait tab labels each stay on one line and are never cut`() {
        // A phone showed "Komponen-sek" and "Diagnoszti-ka" broken over two lines with five tabs; the row now shrinks the shared
        // font size instead.
        var case by mutableStateOf(Case(320, false, 1f))
        rule.setContent {
            val base = LocalDensity.current
            Box(Modifier.requiredSize(case.width.dp, 400.dp)) {
                CompositionLocalProvider(LocalDensity provides Density(base.density * 0.85f, case.fontScale), LocalWowHungarian provides case.hungarian) {
                    WowLauncherTheme {
                        Box(Modifier.fillMaxSize().padding(20.dp)) {
                            Box(Modifier.testTag("row")) {
                                WowTabRow(if (case.hungarian) hungarian else english, selected = 3, enabled = true, maxSize = 15.sp, onSelect = {})
                            }
                        }
                    }
                }
            }
        }
        val broken = mutableListOf<String>()
        for (width in listOf(320, 360, 412)) for (hu in listOf(false, true)) for (scale in listOf(1f, 1.3f)) {
            rule.runOnIdle { case = Case(width, hu, scale) }
            rule.waitForIdle()
            val sizes = mutableSetOf<Float>()
            for (label in if (hu) hungarian else english) {
                val results = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val layout = results.single()
                if (layout.hasVisualOverflow || layout.lineCount != 1) broken += "$width dp, ${if (hu) "hu" else "en"}, x$scale: $label"
                sizes += layout.layoutInput.style.fontSize.value
            }
            assertEquals("one shared font size at $width dp, ${if (hu) "hu" else "en"}, x$scale", 1, sizes.size)
            // Four tabs leave room: on a normal phone width the labels are not shrunk below the old 12 sp.
            if (width >= 412 && scale == 1f) assertTrue("font ${sizes.single()} sp at $width dp", sizes.single() >= 12f)
            // The row is one line of tabs high, not the whole page: a phone showed the tabs stretched to the bottom of Settings.
            val rowHeightDp = rule.onNodeWithTag("row").fetchSemanticsNode().size.height / (rule.density.density)
            assertTrue("tab row height ${rowHeightDp} dp at $width dp, x$scale", rowHeightDp < 60f)
        }
        assertEquals(emptyList<String>(), broken)
    }
}
