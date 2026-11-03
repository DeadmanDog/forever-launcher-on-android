package app.gamenative.ui.screen.wow

/** A captured game frame (ARGB pixels). */
class LoginFrame(val width: Int, val height: Int, val argb: IntArray)

/**
 * Recognises WoW's Battle.net login screen from the average colour of a few fixed areas of the form
 * (the red Log In button, the dark password box and the gold labels). Auto sign-in types the e-mail and
 * password blindly, so it must only ever run when this matches: typed into the world those characters
 * would be game hotkeys. Areas are fractions of the whole frame; the reference colours were measured on
 * the real login screen (RedMagic 11, then 1792x810; the regions are fractions of the frame, so the half-size 1344x608 of the same aspect uses them unchanged) and were identical across animation frames.
 */
object LoginScreenDetector {
    class Region(
        val name: String,
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val r: Int, val g: Int, val b: Int,
        val tolerance: Int = 28,
    )

    val regions = listOf(
        Region("loginButtonLeft", 0.436f, 0.762f, 0.4675f, 0.804f, 95, 2, 1),
        Region("loginButtonRight", 0.530f, 0.762f, 0.5625f, 0.804f, 90, 2, 1),
        Region("passwordBox", 0.425f, 0.632f, 0.575f, 0.663f, 40, 42, 42),
        Region("emailLabel", 0.4475f, 0.455f, 0.5525f, 0.488f, 155, 154, 116),
        Region("passwordLabel", 0.4675f, 0.588f, 0.5325f, 0.621f, 119, 116, 70),
        Region("rememberRow", 0.44f, 0.707f, 0.5775f, 0.738f, 104, 84, 42),
    )

    /**
     * True when the e-mail box already holds text (WoW's "Remember Account" fills it in). Typed characters
     * are bright on the dark box; an empty box has none. Measured on the 448x203 capture: 29% of the box
     * pixels are brighter than 90 with a remembered address, 0% in an empty box.
     */
    fun emailFieldHasText(frame: LoginFrame): Boolean {
        if (frame.width <= 0 || frame.height <= 0 || frame.argb.size < frame.width * frame.height) return false
        val x0 = (0.42f * frame.width).toInt().coerceIn(0, frame.width - 1)
        val x1 = (0.5825f * frame.width).toInt().coerceIn(x0 + 1, frame.width)
        val y0 = (0.4995f * frame.height).toInt().coerceIn(0, frame.height - 1)
        val y1 = (0.5348f * frame.height).toInt().coerceIn(y0 + 1, frame.height)
        var bright = 0
        var total = 0
        for (y in y0 until y1) {
            val row = y * frame.width
            for (x in x0 until x1) {
                val p = frame.argb[row + x]
                if (minOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) > 90) bright++
                total++
            }
        }
        return total > 0 && bright.toFloat() / total >= 0.05f
    }

    /** How many of the areas match; all of them must for [matches]. */
    fun score(frame: LoginFrame): Int = scoreOf(averages(frame))

    fun matches(frame: LoginFrame): Boolean = validFrame(frame) && score(frame) == regions.size

    private fun validFrame(frame: LoginFrame) =
        frame.width > 0 && frame.height > 0 && frame.argb.size >= frame.width * frame.height

    private fun averages(frame: LoginFrame): Array<IntArray?> = Array(regions.size) { average(frame, regions[it]) }

    private fun scoreOf(c: Array<IntArray?>): Int = regions.indices.count { c[it] != null && colourMatches(c[it]!!, regions[it]) }

    /**
     * What one look at a frame found, from a single pass over the six areas. [kinds] = the colours are of the right KINDS (the test
     * 1.3.19 used); [shape] = the frame also has the shape of the form (only computed when the kinds match).
     */
    class Look(val score: Int, val exact: Boolean, val kinds: Boolean, val shape: Boolean) {
        /** Colours of the right kinds AND the form's shape: the quick path of the auto sign-in. */
        val formLike: Boolean get() = !exact && kinds && shape
        /** Not the exact values but the kinds match, with or without the shape: the slower, cautious path. */
        val kindOfLogin: Boolean get() = !exact && kinds
    }

    /**
     * One pass for everything the auto sign-in needs. A frame that cannot be read is score -1.
     */
    fun look(frame: LoginFrame): Look {
        if (!validFrame(frame)) return Look(-1, false, false, false)
        val c = averages(frame)
        val score = scoreOf(c)
        val exact = score == regions.size
        val kinds = !exact && kindsMatch(c)
        return Look(score, exact, kinds, kinds && hasFormStructure(frame))
    }

    /** The numbers behind [hasFormStructure], for the diagnostics log: calibrates the limits on a real device. */
    fun shapeDetails(frame: LoginFrame): String {
        if (!validFrame(frame)) return "shape=?"
        val m = average(frame, buttonMiddle)
        fun dev(i: Int) = deviation(frame, regions[i])?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "?"
        return "buttonMiddle=" + (m?.joinToString(",") ?: "?") + " devEmailLabel=" + dev(3) + " devPasswordLabel=" + dev(4) +
            " devRememberRow=" + dev(5) + " devPasswordBox=" + dev(2)
    }

    /** A short per-area description (region averages) of a frame that only partly matched, for the diagnostics log. */
    fun details(frame: LoginFrame): String = regions.joinToString(" ") { r ->
        average(frame, r)?.joinToString(",", r.name + "=") ?: (r.name + "=?") }

    /** Average colour {r, g, b} of an area, or null when the area holds no pixel. */
    fun average(frame: LoginFrame, region: Region): IntArray? {
        val x0 = (region.x0 * frame.width).toInt().coerceIn(0, frame.width - 1)
        val x1 = (region.x1 * frame.width).toInt().coerceIn(x0 + 1, frame.width)
        val y0 = (region.y0 * frame.height).toInt().coerceIn(0, frame.height - 1)
        val y1 = (region.y1 * frame.height).toInt().coerceIn(y0 + 1, frame.height)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in y0 until y1) {
            val row = y * frame.width
            for (x in x0 until x1) {
                val p = frame.argb[row + x]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0) return null
        return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun colourMatches(c: IntArray, region: Region): Boolean =
        Math.abs(c[0] - region.r) <= region.tolerance &&
            Math.abs(c[1] - region.g) <= region.tolerance &&
            Math.abs(c[2] - region.b) <= region.tolerance

    /**
     * The same six areas judged by KIND of colour instead of exact value: a strong red button (both halves), a dark neutral password box
     * and three warm gold text areas. The exact values were measured at one resolution; a different game resolution, UI scale or a newer
     * build rasterises the label text differently and can push the averages out of the exact tolerance, while these kinds stay the same.
     * Colours alone would also accept six flat patches of the right colours; [looksLikeLoginForm] adds the SHAPE of the form
     * ([hasFormStructure]). The caller asks for either to hold for several looks in a row before it types anything (see BattleNetSignIn).
     */
    fun looksLikeLogin(frame: LoginFrame): Boolean {
        if (!validFrame(frame)) return false
        return kindsMatch(averages(frame))
    }

    /** [looksLikeLogin] and the shape of the form ([hasFormStructure]). */
    fun looksLikeLoginForm(frame: LoginFrame): Boolean = looksLikeLogin(frame) && hasFormStructure(frame)

    private fun kindsMatch(c: Array<IntArray?>): Boolean {
        if (c.any { it == null }) return false
        val v = Array(c.size) { c[it]!! }
        return red(v[0]) && red(v[1]) && darkNeutral(v[2]) && brightGold(v[3]) && midGold(v[4]) && warm(v[5])
    }

    private fun red(v: IntArray) = v[0] >= 60 && v[1] <= 45 && v[2] <= 45 && v[0] - maxOf(v[1], v[2]) >= 40
    private fun darkNeutral(v: IntArray) = maxOf(v[0], v[1], v[2]) <= 85 && maxOf(v[0], v[1], v[2]) - minOf(v[0], v[1], v[2]) <= 16
    private fun brightGold(v: IntArray) = v[0] >= 100 && Math.abs(v[0] - v[1]) <= 25 && v[0] - v[2] >= 20
    private fun midGold(v: IntArray) = v[0] >= 75 && v[1] >= 60 && Math.abs(v[0] - v[1]) <= 25 && v[0] - v[2] >= 30
    private fun warm(v: IntArray) = v[0] >= 65 && v[0] - v[1] >= 6 && v[1] - v[2] >= 15

    /** The part of the Log In button between its two measured halves (it holds the "Log In" text on the same red). */
    private val buttonMiddle = Region("loginButtonMiddle", 0.4675f, 0.762f, 0.53f, 0.804f, 98, 22, 2)

    /**
     * Whether the frame has the shape of the login form, not just its colours: the button runs red THROUGH the text between its
     * halves, the three labels hold text (their brightness varies), the password box is a flat dark field. Measured on the two real
     * captures (448x203): button middle (98,22,2); deviation of brightness 65 / 39 / 25 in the e-mail label / password label /
     * remember row and 3.5 in the box; the limits below are well under those. Six flat colour patches, or a game scene that happens to
     * average out to the right colours, do not have this shape.
     */
    fun hasFormStructure(frame: LoginFrame): Boolean {
        if (!validFrame(frame)) return false
        val middle = average(frame, buttonMiddle) ?: return false
        if (!(middle[0] >= 60 && middle[1] <= 70 && middle[2] <= 60 && middle[0] - maxOf(middle[1], middle[2]) >= 30)) return false
        return (deviation(frame, regions[3]) ?: return false) >= 25.0 &&
            (deviation(frame, regions[4]) ?: return false) >= 15.0 &&
            (deviation(frame, regions[5]) ?: return false) >= 8.0 &&
            (deviation(frame, regions[2]) ?: return false) <= 20.0
    }

    /** Standard deviation of the brightness inside an area (text and edges raise it, a flat colour has none), or null for an empty area. */
    private fun deviation(frame: LoginFrame, region: Region): Double? {
        val x0 = (region.x0 * frame.width).toInt().coerceIn(0, frame.width - 1)
        val x1 = (region.x1 * frame.width).toInt().coerceIn(x0 + 1, frame.width)
        val y0 = (region.y0 * frame.height).toInt().coerceIn(0, frame.height - 1)
        val y1 = (region.y1 * frame.height).toInt().coerceIn(y0 + 1, frame.height)
        var sum = 0L
        var sumSquares = 0L
        var n = 0
        for (y in y0 until y1) {
            val row = y * frame.width
            for (x in x0 until x1) {
                val p = frame.argb[row + x]
                val luma = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                sum += luma
                sumSquares += luma.toLong() * luma
                n++
            }
        }
        if (n == 0) return null
        val mean = sum.toDouble() / n
        return Math.sqrt((sumSquares.toDouble() / n - mean * mean).coerceAtLeast(0.0))
    }
}
