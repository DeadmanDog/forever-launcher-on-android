package app.gamenative.ui.components

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.gamenative.ui.screen.wow.LocalWowHungarian
import app.gamenative.ui.screen.wow.tx
import app.gamenative.ui.theme.WowTheme
import app.gamenative.ui.theme.WowThemeChoice
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w960dp-h434dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BootingSplashAppearanceTest {
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

    @Test fun landscapeBackgroundFollowsThemeWhileProgressStaysGold() = checkThemes()

    @Test
    @Config(qualifiers = "w960dp-h434dp-land-xxhdpi")
    fun highDensityLandscapeKeepsTheEntireLogoAndGoldProgress() = checkThemes()

    private fun checkThemes() {
        val previous = WowTheme.choice
        var view: android.view.View? = null
        rule.mainClock.autoAdvance = false
        val density = org.robolectric.RuntimeEnvironment.getApplication().resources.displayMetrics.density
        try {
            WowTheme.choice = WowThemeChoice.RED
            rule.setContent {
                view = LocalView.current
                CompositionLocalProvider(LocalWowHungarian provides true) {
                    Box(Modifier.fillMaxSize()) { BootingSplash(text = tx("Loading...", "Betöltés..."), progress = .6f) }
                }
            }
            rule.waitForIdle()
            rule.onNodeWithText("Betöltés...").assertIsDisplayed()
            rule.onAllNodesWithText("Játék indítása...").assertCountEquals(0)
            rule.onAllNodesWithText("Nem hivatalos indító", substring = true).assertCountEquals(0)
            val bounds = rule.onNodeWithTag("splashProgress").fetchSemanticsNode().boundsInRoot
            assertEquals(260f * density, bounds.width, 1f)
            assertEquals(3f * density, bounds.height, 1f)
            fun capture(name: String): Bitmap {
                lateinit var bitmap: Bitmap
                rule.runOnIdle {
                    val v = view!!
                    bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                    v.draw(Canvas(bitmap))
                    val output = File("build/splash-render-proof").apply { mkdirs() }
                    File(output, "$name-$density.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                return bitmap
            }
            val red = capture("red-theme")
            rule.runOnIdle { WowTheme.choice = WowThemeChoice.BLUE }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val blue = capture("blue-theme")
            assertFalse("Background follows the theme", red.sameAs(blue))
            val background = red.getPixel(4, red.height / 2)
            assertTrue("Red theme has a red background", android.graphics.Color.red(background) > android.graphics.Color.blue(background))
            val blueBackground = blue.getPixel(4, blue.height / 2)
            assertTrue("Blue theme has a blue background", android.graphics.Color.blue(blueBackground) > android.graphics.Color.red(blueBackground))
            val x = (bounds.left + bounds.width * .15f).toInt()
            val y = bounds.center.y.toInt()
            assertEquals("Progress color is independent of app theme", red.getPixel(x, y), blue.getPixel(x, y))
            val gold = red.getPixel(x, y)
            assertTrue("Progress remains gold", android.graphics.Color.red(gold) > android.graphics.Color.green(gold) && android.graphics.Color.green(gold) > android.graphics.Color.blue(gold))
            red.recycle(); blue.recycle()
        } finally { WowTheme.choice = previous }
    }
}
