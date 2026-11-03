package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.container.ContainerManager

object ContainerUtils {
    fun getOrCreateContainer(context: Context, appId: String): Container =
        getOrCreateContainer(context, appId, ContainerManager(context))

    fun getOrCreateContainer(context: Context, appId: String, containerManager: ContainerManager): Container {
        // The WoW container is always created by WoWForeverScreen (WowContainerProfile) before launch.
        check(containerManager.hasContainer(appId)) { "Container $appId does not exist" }
        return containerManager.getContainerById(appId)
    }

    fun isAbsoluteWindowsPath(path: String): Boolean =
        Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path)
}
