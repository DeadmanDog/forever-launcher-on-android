package app.gamenative.ui.screen.wow

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto sign-in signs in on the login screen by the rules that worked since 1.3.21 (exact colours, or colour kinds with the form
 * shape, or colour kinds alone given more time), once per start, and stops for good once the login screen has gone.
 */
class AutoSignInGateTest {
    private fun fixture(name: String): LoginFrame {
        val width = 448
        val height = 203
        val stream = javaClass.classLoader!!.getResourceAsStream("login/$name") ?: error("missing fixture $name")
        val rgb = GZIPInputStream(stream).use { it.readBytes() }
        return LoginFrame(width, height, IntArray(width * height) { i ->
            (0xFF shl 24) or ((rgb[i * 3].toInt() and 0xFF) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[i * 3 + 2].toInt() and 0xFF)
        })
    }

    private val exact = LoginScreenDetector.Look(6, exact = true, kinds = false, shape = true)
    private val exactNoShape = LoginScreenDetector.Look(6, exact = true, kinds = false, shape = false)
    private val formLike = LoginScreenDetector.Look(4, exact = false, kinds = true, shape = true)
    private val coloursOnly = LoginScreenDetector.Look(4, exact = false, kinds = true, shape = false)
    private val other = LoginScreenDetector.Look(1, exact = false, kinds = false, shape = false)
    private val unreadable = LoginScreenDetector.Look(-1, exact = false, kinds = false, shape = false)

    private fun AutoSignInGate.feed(look: LoginScreenDetector.Look, times: Int, startMs: Long, stepMs: Long): AutoSignInGate.Decision {
        // The first decision that is not WAIT (later looks only repeat STOP).
        repeat(times) { val d = observe(look, startMs + it * stepMs); if (d != AutoSignInGate.Decision.WAIT) return d }
        return AutoSignInGate.Decision.WAIT
    }

    @Test fun theRealLoginScreenSignsIn() {
        for (name in listOf("login_screen_ref.rgb.gz", "login_screen_3.rgb.gz")) {
            val look = LoginScreenDetector.look(fixture(name))
            assertTrue(name, look.exact)
            assertEquals(name, AutoSignInGate.Decision.SIGN_IN, AutoSignInGate().feed(look, 3, 0, 200))
        }
    }

    @Test fun threeExactLooksSignInEvenWithoutTheFormShape() {
        // 1.2.0's first build demanded the shape here too and then never signed in on the current login screen.
        for (look in listOf(exact, exactNoShape)) {
            val gate = AutoSignInGate()
            assertEquals(AutoSignInGate.Decision.WAIT, gate.observe(look, 0))
            assertEquals(AutoSignInGate.Decision.WAIT, gate.observe(look, 200))
            assertEquals(AutoSignInGate.Decision.SIGN_IN, gate.observe(look, 400))
            assertEquals("acts once only", AutoSignInGate.Decision.STOP, gate.observe(look, 600))
        }
    }

    @Test fun kindsWithTheFormShapeNeedFourLooksOverHalfASecond() {
        val gate = AutoSignInGate()
        assertEquals(AutoSignInGate.Decision.WAIT, gate.feed(formLike, 4, 0, 100))   // 4 looks within 300 ms: not yet
        assertEquals(AutoSignInGate.Decision.SIGN_IN, gate.observe(formLike, 500))
        assertTrue(gate.closed)
    }

    @Test fun kindsAloneNeedSixLooksOverTwoSeconds() {
        val gate = AutoSignInGate()
        assertEquals(AutoSignInGate.Decision.WAIT, gate.feed(coloursOnly, 8, 0, 200))   // 8 looks within 1.4 s: not yet
        assertEquals(AutoSignInGate.Decision.SIGN_IN, gate.observe(coloursOnly, 2000))
    }

    @Test fun aDimmedRealScreenStillSignsIn() {
        val ref = fixture("login_screen_ref.rgb.gz")
        val pixels = ref.argb.copyOf()
        for (r in LoginScreenDetector.regions.filter { it.name.endsWith("Label") || it.name == "rememberRow" }) {
            for (y in (r.y0 * ref.height).toInt()..(r.y1 * ref.height).toInt()) for (x in (r.x0 * ref.width).toInt()..(r.x1 * ref.width).toInt()) {
                val p = pixels[y * ref.width + x]
                pixels[y * ref.width + x] = (0xFF shl 24) or ((((p shr 16) and 0xFF) * 0.8f).toInt() shl 16) or
                    ((((p shr 8) and 0xFF) * 0.8f).toInt() shl 8) or ((p and 0xFF) * 0.8f).toInt()
            }
        }
        val look = LoginScreenDetector.look(LoginFrame(ref.width, ref.height, pixels))
        assertFalse(look.exact)
        assertTrue(look.kindOfLogin)
        assertEquals(AutoSignInGate.Decision.SIGN_IN, AutoSignInGate().feed(look, 11, 0, 200))
    }

    @Test fun otherScreensNeverSignIn() {
        assertEquals(AutoSignInGate.Decision.WAIT, AutoSignInGate().feed(other, 500, 0, 700))
    }

    @Test fun aBrokenStreakStartsOver() {
        val gate = AutoSignInGate()
        gate.observe(exact, 0); gate.observe(exact, 200)
        assertEquals(AutoSignInGate.Decision.WAIT, gate.observe(unreadable, 400))
        assertEquals(AutoSignInGate.Decision.WAIT, gate.observe(exact, 600))
        assertEquals(AutoSignInGate.Decision.WAIT, gate.observe(exact, 800))
        assertEquals(AutoSignInGate.Decision.SIGN_IN, gate.observe(exact, 1000))
    }

    @Test fun onceTheLoginScreenWasSeenAndIsGoneForLongTheGateClosesForGood() {
        val gate = AutoSignInGate()
        // Seen clearly (3 looks), but the kind-based path has not signed in yet; then the player signs in by hand.
        assertEquals(AutoSignInGate.Decision.WAIT, gate.feed(coloursOnly, 3, 0, 200))
        assertEquals(AutoSignInGate.Decision.WAIT, gate.feed(other, AutoSignInGate.GONE_POLLS - 1, 1000, 700))
        assertEquals(AutoSignInGate.Decision.STOP, gate.observe(other, 30_000))
        assertEquals("the login screen coming back later does nothing", AutoSignInGate.Decision.STOP, gate.feed(exact, 5, 40_000, 200))
    }

    @Test fun aShortInterruptionOfTheLoginScreenDoesNotEndIt() {
        val gate = AutoSignInGate()
        gate.feed(coloursOnly, 3, 0, 200)
        assertEquals(AutoSignInGate.Decision.WAIT, gate.feed(other, 5, 1000, 700))       // a dialog for a few seconds
        assertEquals(AutoSignInGate.Decision.SIGN_IN, gate.feed(exact, 3, 10_000, 200))
    }

    @Test fun loadingScreensBeforeTheLoginScreenDoNotCloseTheGate() {
        val gate = AutoSignInGate()
        assertEquals(AutoSignInGate.Decision.WAIT, gate.feed(other, 100, 0, 700))
        assertEquals(AutoSignInGate.Decision.WAIT, gate.feed(coloursOnly, 2, 70_000, 200))    // a fade: not "clearly seen"
        assertEquals(AutoSignInGate.Decision.WAIT, gate.feed(other, 100, 71_000, 700))
        assertEquals(AutoSignInGate.Decision.SIGN_IN, gate.feed(exact, 3, 200_000, 200))
    }

    @Test fun unreadableLooksNeverCountAsGone() {
        val gate = AutoSignInGate()
        gate.feed(exact, 2, 0, 200)
        assertEquals(AutoSignInGate.Decision.WAIT, gate.feed(unreadable, 200, 400, 200))
        assertFalse(gate.closed)
    }

    @Test fun aSignInFromTheMenuClosesTheGate() {
        val gate = AutoSignInGate()
        gate.stop()
        assertEquals(AutoSignInGate.Decision.STOP, gate.feed(exact, 3, 0, 200))
    }

    @Test fun resetClearsTheStreaks() {
        val gate = AutoSignInGate()
        gate.feed(exact, 2, 0, 200)
        gate.reset()
        assertEquals(AutoSignInGate.Decision.WAIT, gate.observe(exact, 400))
    }
}
