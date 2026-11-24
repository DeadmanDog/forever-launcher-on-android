package app.gamenative.ui.components

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Review 2026-11-15: a real finger tap on the splash's back arrow (down, a small move, up) must abort; the 1.2.0 shield consumed
 * the move and Compose cancelled the tap. Touches still do not reach what is under the splash. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w960dp-h434dp-land-mdpi")
class BootingSplashBackTapTest {
    private val rule = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val component = ComponentName(context, ComponentActivity::class.java)
            val pm = Shadows.shadowOf(context.packageManager)
            pm.addActivityIfNotPresent(component)
            pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }
    }).around(rule)

    @Test fun aFingerTapWithASmallMoveOnTheBackArrowAborts() {
        var underneath = 0
        var aborted = 0
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().testTag("underneath").clickable { underneath++ })
                BootingSplash(visible = true, onAbort = { aborted++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(ApplicationProvider.getApplicationContext<Application>().getString(app.gamenative.R.string.back))
            .performTouchInput { down(center); moveBy(Offset(3f, 2f)); up() }
        rule.waitForIdle()
        assertEquals("the back arrow aborts on a real tap", 1, aborted)
        rule.onNodeWithTag("bootingSplashTouchShield").performTouchInput { down(center); moveBy(Offset(3f, 2f)); up() }
        rule.waitForIdle()
        assertEquals("the screen under the splash got no touch", 0, underneath)
    }
}
