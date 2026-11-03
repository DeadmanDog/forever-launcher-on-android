package app.gamenative.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

object StorageUtils {
    fun hasStoragePermission(context: Context, path: String): Boolean {
        val isOutsideSandbox = !path.contains("/Android/data/${context.packageName}") &&
            !path.contains(context.dataDir.path)

        if (!isOutsideSandbox) return true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestManageExternalStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return true
                } catch (_: Exception) {
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        return true
                    } catch (_: Exception) {
                        return false
                    }
                }
            }
        } else {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                return false
            }
        }
    }

    fun getPathFromTreeUri(context: Context, uri: android.net.Uri?): String? {
        if (uri == null) return null
        val path = treeUriPath(context, uri) ?: return null
        return existingPath(path, storageRoots(context)) ?: path
    }

    /** Every mounted storage volume root (internal storage first). */
    fun storageRoots(context: Context): List<String> {
        val roots = mutableListOf(Environment.getExternalStorageDirectory().path)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes?.mapNotNullTo(roots) { it.directory?.absolutePath }
        }
        return roots.distinct()
    }

    /**
     * [path] as it exists on this phone. A folder picker can hand back a volume id that is not mounted under /storage (seen
     * 2026-11-01 on a RedMagic: "53FC-3FF3:WoWF" for a folder on internal storage); then the same relative folder is looked up on
     * each mounted volume. Returns null when no candidate exists (for example before All files access is granted).
     */
    fun existingPath(path: String, roots: List<String>): String? {
        if (File(path).exists()) return path
        val relative = when {
            roots.any { path == it || path.startsWith("$it/") } -> return null
            path.startsWith("/storage/") -> path.removePrefix("/storage/").substringAfter('/', "")
            else -> return null
        }
        if (relative.isEmpty()) return null
        return roots.map { "$it/$relative" }.firstOrNull { File(it).exists() }
    }

    private fun treeUriPath(context: Context, uri: android.net.Uri): String? {
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val path = docId.substringAfter(":")
                val external = Environment.getExternalStorageDirectory().path
                if (path.isEmpty()) external else "$external/$path"
            } else if (docId.contains(":")) {
                val parts = docId.split(":", limit = 2)
                val volumeRoot = resolveVolumeRoot(context, parts[0])
                if (parts[1].isEmpty()) volumeRoot else "$volumeRoot/${parts[1]}"
            } else {
                docId
            }
        } catch (_: Exception) {
            uri.path?.removePrefix("/tree/")?.let {
                if (it.startsWith("primary:")) {
                    val p = it.substringAfter(":")
                    val external = Environment.getExternalStorageDirectory().path
                    if (p.isEmpty()) external else "$external/$p"
                } else it
            }
        }
    }

    private fun resolveVolumeRoot(context: Context, volumeId: String): String {
        val defaultRoot = "/storage/$volumeId"
        if (File(defaultRoot).exists()) return defaultRoot
        val sm = context.getSystemService(StorageManager::class.java)
        val volume = sm?.storageVolumes?.firstOrNull { it.uuid?.equals(volumeId, ignoreCase = true) == true }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume?.directory?.absolutePath ?: defaultRoot
        } else {
            defaultRoot
        }
    }
}
