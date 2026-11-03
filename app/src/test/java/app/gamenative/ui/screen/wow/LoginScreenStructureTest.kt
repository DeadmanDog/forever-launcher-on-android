package app.gamenative.ui.screen.wow

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The form-shape test that keeps a frame of the right colours but the wrong shape from being taken for the login screen. */
class LoginScreenStructureTest {
    private fun fixture(name: String): LoginFrame {
        val width = 448
        val height = 203
        val stream = javaClass.classLoader!!.getResourceAsStream("login/$name") ?: error("missing fixture $name")
        val rgb = GZIPInputStream(stream).use { it.readBytes() }
        check(rgb.size == width * height * 3)
        return LoginFrame(width, height, IntArray(width * height) { i ->
            (0xFF shl 24) or ((rgb[i * 3].toInt() and 0xFF) shl 16) or ((rgb[i * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[i * 3 + 2].toInt() and 0xFF)
        })
    }

    private fun fill(f: LoginFrame, pixels: IntArray, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int) {
        for (y in (y0 * f.height).toInt() until (y1 * f.height).toInt() + 1) for (x in (x0 * f.width).toInt() until (x1 * f.width).toInt() + 1)
            pixels[y * f.width + x] = argb
    }

    /** What the review's counter-example looks like: a plain background with six flat patches in the form's colours and places. */
    private fun sixFlatPatches(): LoginFrame {
        val f = LoginFrame(448, 203, IntArray(448 * 203) { 0xFF1E5A1E.toInt() })
        val colours = listOf(0xFFB40F0A, 0xFFB40F0A, 0xFF464646, 0xFFC8B932, 0xFFB4A014, 0xFF8C5A14)
        for ((i, r) in LoginScreenDetector.regions.withIndex()) fill(f, f.argb, r.x0, r.y0, r.x1, r.y1, colours[i].toInt())
        return f
    }

    @Test fun theRealCapturesHaveTheShapeOfTheForm() {
        assertTrue(LoginScreenDetector.hasFormStructure(fixture("login_screen_ref.rgb.gz")))
        assertTrue(LoginScreenDetector.hasFormStructure(fixture("login_screen_3.rgb.gz")))
    }

    @Test fun sixFlatPatchesOfTheRightColoursPassTheColourTestButNotTheShapeTest() {
        val fake = sixFlatPatches()
        assertFalse(LoginScreenDetector.matches(fake))
        // The colour test alone accepts it (that is what the review used as counter-example) ...
        assertTrue(LoginScreenDetector.looksLikeLogin(fake))
        // ... the shape test does not: no text in the labels, no button between its halves.
        assertFalse(LoginScreenDetector.hasFormStructure(fake))
        assertFalse(LoginScreenDetector.looksLikeLoginForm(fake))
        val look = LoginScreenDetector.look(fake)
        assertTrue(look.kindOfLogin)
        assertFalse("the quick path is closed to it", look.formLike)
    }

    @Test fun flatteningTheTextOfTheLabelsBreaksTheShape() {
        val ref = fixture("login_screen_ref.rgb.gz")
        for (name in listOf("emailLabel", "passwordLabel", "rememberRow")) {
            val r = LoginScreenDetector.regions.first { it.name == name }
            val pixels = ref.argb.copyOf()
            val avg = LoginScreenDetector.average(ref, r)!!
            fill(ref, pixels, r.x0, r.y0, r.x1, r.y1, (0xFF shl 24) or (avg[0] shl 16) or (avg[1] shl 8) or avg[2])
            val flat = LoginFrame(ref.width, ref.height, pixels)
            assertEquals("$name keeps its exact colour", true, LoginScreenDetector.score(flat) >= 5)
            assertFalse("$name without text", LoginScreenDetector.looksLikeLoginForm(flat))
        }
    }

    @Test fun aButtonThatDoesNotRunThroughTheTextBreaksTheShape() {
        val ref = fixture("login_screen_ref.rgb.gz")
        val pixels = ref.argb.copyOf()
        // The gap between the two measured halves of the button is background instead of button.
        fill(ref, pixels, 0.4675f, 0.762f, 0.53f, 0.804f, 0xFF203020.toInt())
        val gap = LoginFrame(ref.width, ref.height, pixels)
        assertTrue("both halves still look like a button", LoginScreenDetector.score(gap) >= 4)
        assertFalse(LoginScreenDetector.hasFormStructure(gap))
        assertFalse(LoginScreenDetector.looksLikeLoginForm(gap))
    }

    @Test fun anotherResolutionWithThinnerTextStillHasTheShape() {
        val ref = fixture("login_screen_ref.rgb.gz")
        val pixels = ref.argb.copyOf()
        // Thinner text: blend every label pixel 40% towards the area's average (the deviation shrinks to 60%).
        for (name in listOf("emailLabel", "passwordLabel", "rememberRow")) {
            val r = LoginScreenDetector.regions.first { it.name == name }
            val avg = LoginScreenDetector.average(ref, r)!!
            for (y in (r.y0 * ref.height).toInt()..(r.y1 * ref.height).toInt()) for (x in (r.x0 * ref.width).toInt()..(r.x1 * ref.width).toInt()) {
                val p = pixels[y * ref.width + x]
                fun mix(c: Int, a: Int) = (c * 0.6f + a * 0.4f).toInt()
                pixels[y * ref.width + x] = (0xFF shl 24) or (mix((p shr 16) and 0xFF, avg[0]) shl 16) or (mix((p shr 8) and 0xFF, avg[1]) shl 8) or mix(p and 0xFF, avg[2])
            }
        }
        assertTrue(LoginScreenDetector.hasFormStructure(LoginFrame(ref.width, ref.height, pixels)))
    }

    @Test fun lookReportsOnePassResults() {
        val ref = fixture("login_screen_ref.rgb.gz")
        val exact = LoginScreenDetector.look(ref)
        assertEquals(LoginScreenDetector.regions.size, exact.score)
        assertTrue(exact.exact)
        assertFalse("an exact match needs no kind-based verdict", exact.kindOfLogin)
        assertEquals(-1, LoginScreenDetector.look(LoginFrame(0, 0, IntArray(0))).score)
        assertEquals(-1, LoginScreenDetector.look(LoginFrame(448, 203, IntArray(10))).score)
        assertTrue(LoginScreenDetector.details(ref).contains("emailLabel="))
    }

    @Test fun lookAcceptsADimmedRealScreenByKindAndShape() {
        val ref = fixture("login_screen_ref.rgb.gz")
        val pixels = ref.argb.copyOf()
        for (r in LoginScreenDetector.regions.filter { it.name.endsWith("Label") || it.name == "rememberRow" }) {
            for (y in (r.y0 * ref.height).toInt()..(r.y1 * ref.height).toInt()) for (x in (r.x0 * ref.width).toInt()..(r.x1 * ref.width).toInt()) {
                val p = pixels[y * ref.width + x]
                pixels[y * ref.width + x] = (0xFF shl 24) or ((((p shr 16) and 0xFF) * 0.8f).toInt() shl 16) or ((((p shr 8) and 0xFF) * 0.8f).toInt() shl 8) or ((p and 0xFF) * 0.8f).toInt()
            }
        }
        val look = LoginScreenDetector.look(LoginFrame(ref.width, ref.height, pixels))
        assertFalse(look.exact)
        assertTrue(look.kindOfLogin)
        assertTrue("a dimmed real screen still has the shape: the quick path", look.formLike)
    }

    @Test fun theShapeNumbersAreLoggedForCalibration() {
        val text = LoginScreenDetector.shapeDetails(fixture("login_screen_ref.rgb.gz"))
        assertTrue(text, text.contains("buttonMiddle=97,21,1") && text.contains("devEmailLabel=") && text.contains("devPasswordBox="))
        assertEquals("shape=?", LoginScreenDetector.shapeDetails(LoginFrame(0, 0, IntArray(0))))
    }
}
