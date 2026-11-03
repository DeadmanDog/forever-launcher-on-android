package app.gamenative.utils

import android.content.res.AssetManager
import com.winlator.core.TarCompressorUtils
import java.io.File

object AssetUtils {

    fun extractComponentsWithVersionCheck(
        extractionPairs: List<Pair<String, File>>,
        assetManager: AssetManager,
        extractType: TarCompressorUtils.Type
    ) {
        for ((assetFile, targetDir) in extractionPairs) {
            if (targetDir.exists() && (targetDir.list()?.isNotEmpty() == true)) continue

            val tempDir = File(targetDir.parentFile, "${targetDir.name}.tmp")
            if (tempDir.exists()) tempDir.deleteRecursively()
            tempDir.mkdirs()

            val success = TarCompressorUtils.extract(
                extractType,
                assetManager,
                assetFile,
                tempDir
            )

            if (success) {
                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!tempDir.renameTo(targetDir)) {

                    tempDir.deleteRecursively()
                    continue
                }

            } else {
                tempDir.deleteRecursively()

            }
        }
    }
}
