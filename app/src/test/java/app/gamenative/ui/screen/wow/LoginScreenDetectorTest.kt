package app.gamenative.ui.screen.wow

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginScreenDetectorTest {
    // Real login-screen captures, scaled to 448x203, stored as gzipped raw RGB.
    private fun fixture(name: String): LoginFrame {
        val width = 448
        val height = 203
        val stream = javaClass.classLoader!!.getResourceAsStream("login/$name") ?: error("missing fixture $name")
        val rgb = GZIPInputStream(stream).use { it.readBytes() }
        check(rgb.size == width * height * 3) { "unexpected fixture size ${rgb.size}" }
        val pixels = IntArray(width * height) { i ->
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return LoginFrame(width, height, pixels)
    }

    private fun blank(width: Int, height: Int, color: Int) = LoginFrame(width, height, IntArray(width * height) { color })

    @Test fun realLoginScreensAreRecognised() {
        assertTrue(LoginScreenDetector.matches(fixture("login_screen_ref.rgb.gz")))
        assertTrue(LoginScreenDetector.matches(fixture("login_screen_3.rgb.gz")))
        assertEquals(LoginScreenDetector.regions.size, LoginScreenDetector.score(fixture("login_screen_ref.rgb.gz")))
    }

    @Test fun otherScreensAreNotRecognised() {
        assertFalse(LoginScreenDetector.matches(blank(448, 203, 0xFF000000.toInt())))
        assertFalse(LoginScreenDetector.matches(blank(448, 203, 0xFFFFFFFF.toInt())))
        assertFalse(LoginScreenDetector.matches(blank(448, 203, 0xFF5F0201.toInt())))
        assertFalse(LoginScreenDetector.matches(LoginFrame(0, 0, IntArray(0))))
        assertFalse(LoginScreenDetector.matches(LoginFrame(448, 203, IntArray(10))))
    }

    @Test fun aMirroredOrShiftedScreenIsNotRecognised() {
        val ref = fixture("login_screen_ref.rgb.gz")
        val mirrored = IntArray(ref.argb.size)
        for (y in 0 until ref.height) for (x in 0 until ref.width) mirrored[y * ref.width + x] = ref.argb[y * ref.width + (ref.width - 1 - x)]
        // The form is centred, so a mirror is almost the same; a vertical shift must break it.
        val shifted = IntArray(ref.argb.size)
        for (y in 0 until ref.height) for (x in 0 until ref.width) shifted[y * ref.width + x] = ref.argb[((y + 30) % ref.height) * ref.width + x]
        assertFalse(LoginScreenDetector.matches(LoginFrame(ref.width, ref.height, shifted)))
    }

    @Test fun missingLoginButtonBreaksTheMatch() {
        val ref = fixture("login_screen_ref.rgb.gz")
        val pixels = ref.argb.copyOf()
        // Paint the Log In button area dark (like the character-select or world screen).
        for (y in (0.76f * ref.height).toInt()..(0.81f * ref.height).toInt())
            for (x in (0.43f * ref.width).toInt()..(0.57f * ref.width).toInt()) pixels[y * ref.width + x] = 0xFF101010.toInt()
        val frame = LoginFrame(ref.width, ref.height, pixels)
        assertFalse(LoginScreenDetector.matches(frame))
        assertTrue(LoginScreenDetector.score(frame) < LoginScreenDetector.regions.size)
    }

    @Test fun rememberedEmailIsDetectedAndAnEmptyBoxIsNot() {
        val ref = fixture("login_screen_ref.rgb.gz")
        assertTrue(LoginScreenDetector.emailFieldHasText(ref))
        // An empty box is plain dark: wipe the text out of the e-mail box.
        val pixels = ref.argb.copyOf()
        for (y in (0.49f * ref.height).toInt()..(0.545f * ref.height).toInt())
            for (x in (0.41f * ref.width).toInt()..(0.59f * ref.width).toInt()) pixels[y * ref.width + x] = 0xFF282F33.toInt()
        val empty = LoginFrame(ref.width, ref.height, pixels)
        assertFalse(LoginScreenDetector.emailFieldHasText(empty))
        assertTrue(LoginScreenDetector.matches(empty))
        assertFalse(LoginScreenDetector.emailFieldHasText(LoginFrame(0, 0, IntArray(0))))
    }

    @Test fun passwordTypedIntoTheBoxStillCountsAsTheLoginScreen() {
        // A few bright dots inside the dark password box (typed characters) must not hide the screen.
        val ref = fixture("login_screen_ref.rgb.gz")
        val pixels = ref.argb.copyOf()
        val y = (0.648f * ref.height).toInt()
        for (i in 0 until 8) pixels[y * ref.width + (0.43f * ref.width).toInt() + i * 3] = 0xFFFFFFFF.toInt()
        assertTrue(LoginScreenDetector.matches(LoginFrame(ref.width, ref.height, pixels)))
    }

    @Test fun theKindBasedTestAlsoRecognisesTheRealLoginScreens() {
        assertTrue(LoginScreenDetector.looksLikeLogin(fixture("login_screen_ref.rgb.gz")))
        assertTrue(LoginScreenDetector.looksLikeLogin(fixture("login_screen_3.rgb.gz")))
    }

    @Test fun theKindBasedTestRejectsOtherScreens() {
        assertFalse(LoginScreenDetector.looksLikeLogin(blank(448, 203, 0xFF000000.toInt())))
        assertFalse(LoginScreenDetector.looksLikeLogin(blank(448, 203, 0xFFFFFFFF.toInt())))
        assertFalse(LoginScreenDetector.looksLikeLogin(blank(448, 203, 0xFF5F0201.toInt())))          // red everywhere: the dark box is missing
        assertFalse(LoginScreenDetector.looksLikeLogin(blank(448, 203, 0xFF9C9C78.toInt())))          // gold everywhere: no red button
        assertFalse(LoginScreenDetector.looksLikeLogin(blank(448, 203, 0xFF284028.toInt())))          // a green-ish dark scene
        assertFalse(LoginScreenDetector.looksLikeLogin(LoginFrame(0, 0, IntArray(0))))
        assertFalse(LoginScreenDetector.looksLikeLogin(LoginFrame(448, 203, IntArray(10))))
        val ref = fixture("login_screen_ref.rgb.gz")
        val shifted = IntArray(ref.argb.size)
        for (y in 0 until ref.height) for (x in 0 until ref.width) shifted[y * ref.width + x] = ref.argb[((y + 30) % ref.height) * ref.width + x]
        assertFalse(LoginScreenDetector.looksLikeLogin(LoginFrame(ref.width, ref.height, shifted)))
    }

    @Test fun theKindBasedTestNeedsEverySingleArea() {
        val ref = fixture("login_screen_ref.rgb.gz")
        for (region in LoginScreenDetector.regions) {
            val pixels = ref.argb.copyOf()
            val x0 = (region.x0 * ref.width).toInt(); val x1 = (region.x1 * ref.width).toInt() + 1
            val y0 = (region.y0 * ref.height).toInt(); val y1 = (region.y1 * ref.height).toInt() + 1
            for (y in y0..y1) for (x in x0..x1) pixels[y * ref.width + x] = 0xFF303030.toInt()       // wipe this one area to neutral grey
            val damaged = LoginFrame(ref.width, ref.height, pixels)
            // A neutral dark patch is what the password box should be, so that area passes by design; every other area must fail.
            val expected = region.name == "passwordBox"
            assertEquals("area ${region.name}", expected, LoginScreenDetector.looksLikeLogin(damaged))
        }
    }

    @Test fun dimmerTextAtAnotherResolutionStillCountsAsTheLoginScreen() {
        // Text drawn thinner at another game resolution lowers the average of the three text areas by 15-25%: the exact values then
        // drift out of tolerance (that is the failure this test stands for), the kinds do not.
        val ref = fixture("login_screen_ref.rgb.gz")
        val pixels = ref.argb.copyOf()
        for (region in LoginScreenDetector.regions.filter { it.name.endsWith("Label") || it.name == "rememberRow" }) {
            val x0 = (region.x0 * ref.width).toInt(); val x1 = (region.x1 * ref.width).toInt() + 1
            val y0 = (region.y0 * ref.height).toInt(); val y1 = (region.y1 * ref.height).toInt() + 1
            for (y in y0..y1) for (x in x0..x1) {
                val p = pixels[y * ref.width + x]
                val r = (((p shr 16) and 0xFF) * 0.8f).toInt(); val g = (((p shr 8) and 0xFF) * 0.8f).toInt(); val b = ((p and 0xFF) * 0.8f).toInt()
                pixels[y * ref.width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val dim = LoginFrame(ref.width, ref.height, pixels)
        assertFalse("the exact test no longer matches", LoginScreenDetector.matches(dim))
        assertTrue("the kind-based test still does", LoginScreenDetector.looksLikeLogin(dim))
    }
}
