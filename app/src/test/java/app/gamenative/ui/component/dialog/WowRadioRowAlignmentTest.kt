package app.gamenative.ui.component.dialog

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** 1.1.8, the user: "the radio dot at XServer is shifted". A radio's label sits level with its dot. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WowRadioRowAlignmentTest {
    private val rule = createComposeRule()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val component = ComponentName(context, ComponentActivity::class.java)
            val packageManager = Shadows.shadowOf(context.packageManager)
            packageManager.addActivityIfNotPresent(component)
            packageManager.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }).around(rule)

    @Test fun displayBackendLabelsSitLevelWithTheirDots() {
        rule.setContent {
            Box(Modifier.requiredSize(400.dp, 800.dp)) {
                WowLauncherTheme { WowDisplayBackendSection(blocked = false, onBusyChanged = {}, onSaved = {}) }
            }
        }
        rule.waitForIdle()
        val radios = rule.onAllNodes(SemanticsMatcher("radio") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton },
            useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("no radio buttons", radios.size >= 2)
        for (label in listOf("XServer", "Gamescope (experimental)")) {
            val text = rule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
            // The radio of this row: the nearest one.
            val radio = radios.map { it.boundsInRoot }.minBy { abs(it.center.y - text.center.y) }
            val offsetDp = abs(text.center.y - radio.center.y) / rule.density.density
            assertTrue("$label is $offsetDp dp off its dot", offsetDp < 2f)
        }
    }
}
