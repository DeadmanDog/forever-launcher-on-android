package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.InsetDrawable
import androidx.core.content.ContextCompat
import app.gamenative.R
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForeverAdaptiveIconTest {
    @Test fun adaptiveEmblemFitsAtLauncherAndCacheSizesAcrossDensities() = renderAndCheck(false)

    @Test fun foregroundBitmapRetainsSafeMarginWhenWrappersAreRemoved() = renderAndCheck(true)

    @Test fun foregroundHasTheSame108DpIntrinsicSizeAsTheOriginalVector() {
        val app = RuntimeEnvironment.getApplication()
        for (density in listOf(160, 320, 480, 640)) {
            val config = Configuration(app.resources.configuration).apply { densityDpi = density }
            val context = app.createConfigurationContext(config)
            val foreground = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)!!
            val expected = (108 * context.resources.displayMetrics.density + .5f).toInt()
            org.junit.Assert.assertEquals("density=$density width", expected, foreground.intrinsicWidth)
            org.junit.Assert.assertEquals("density=$density height", expected, foreground.intrinsicHeight)
        }
    }

    private fun renderAndCheck(unwrap: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val xml = File(res, "drawable/ic_launcher_foreground.xml").readText()
        val mode = (if (xml.contains("forever_icon_layer_042")) "baked" else if (xml.contains("%")) "fraction" else "absolute") + if (unwrap) "-unwrapped" else ""
        val output = File("build/icon-render-proof/$mode").apply { mkdirs() }
        val failures = mutableListOf<String>()
        for (density in listOf(160, 320, 480)) for (size in listOf(48, 128, 512)) {
            val config = Configuration(app.resources.configuration).apply { densityDpi = density }
            val context = app.createConfigurationContext(config)
            val original = ContextCompat.getDrawable(context, R.mipmap.ic_launcher_042)!!.mutate() as AdaptiveIconDrawable
            var foreground = original.foreground
            if (unwrap) while (foreground is InsetDrawable) foreground = foreground.drawable!!
            val drawable = if (unwrap) AdaptiveIconDrawable(original.background, foreground) else original
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            var minX = size; var minY = size; var maxX = -1; var maxY = -1
            for (y in 0 until size) for (x in 0 until size) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 128 && Color.red(pixel) > 100 && Color.green(pixel) > 65 &&
                    Color.red(pixel) > Color.green(pixel) * 1.1 && Color.green(pixel) > Color.blue(pixel) * 1.25) {
                    minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                }
            }
            val fits = minX >= size * .05 && minY >= size * .05 && maxX < size * .95 && maxY < size * .95 && maxX > minX
            val result = "density=$density size=$size gold=[$minX,$minY,$maxX,$maxY] fits=$fits"
            println(result)
            if (!fits) failures += result
            File(output, "$density-$size.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
