package app.gamenative.display

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** DroidDeck's preset values for CAS and color. Off leaves the native composite path alone. */
internal enum class GamescopePictureLook(
    val id: String,
    val label: String,
    private val cas: Float,
    private val brightness: Float = 0f,
    private val contrast: Float = 1f,
    private val gamma: Float = 1f,
    private val saturation: Float = 1f,
) {
    OFF("off", "Off", 0f),
    ADAPTIVE("adaptive", "Adaptive Sharpen", 0.85f),
    CLARITY("clarity", "Game Clarity", 0.55f, 0.02f, 1.12f, 1f, 1.08f),
    VIVID("vivid", "Vivid", 0.30f, 0.03f, 1.10f, 0.98f, 1.45f);

    fun shaderDefines() = """
        #define WOW_CAS $cas
        #define WOW_BRIGHTNESS $brightness
        #define WOW_CONTRAST $contrast
        #define WOW_GAMMA $gamma
        #define WOW_SATURATION $saturation
        #define WOW_HAS_COLOR ${if (this == CLARITY || this == VIVID) 1 else 0}
    """.trimIndent()

    fun stage(context: Context, home: File): String? {
        if (this == OFF) return null
        val directory = File(home, ".local/share/gamescope/reshade/Shaders")
        check(directory.isDirectory || directory.mkdirs()) { "Unable to prepare the Gamescope shader directory" }
        val name = "WowForeverDroidDeck-$id.fx"
        val file = AtomicFile(File(directory, name))
        val shader = context.assets.open("gamescope-effects/DroidDeckPicture.fx").bufferedReader().use { it.readText() }
        val bytes = (shaderDefines() + "\n" + shader).toByteArray()
        if (file.baseFile.isFile && file.baseFile.readBytes().contentEquals(bytes)) return name
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        return name
    }

}
