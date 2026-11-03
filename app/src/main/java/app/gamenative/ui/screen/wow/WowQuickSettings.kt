package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.ui.component.parseBooleanExtra
import app.gamenative.ui.component.parsePositiveFpsLimit
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.KeyValueSet

/** Logic behind the Settings "Quick" tab. Container I/O must run on IO. */
internal object WowQuickSettings {
    const val FPS_ENABLED_EXTRA = "fpsLimiterEnabled"
    const val FPS_TARGET_EXTRA = "fpsLimiterTarget"
    val fpsTargets = listOf(30, 45, 60, 90, 120, 144)

    data class FpsLimit(val enabled: Boolean, val target: Int)

    /** Same defaults as the in-game Quick Menu: enabled, 60 FPS. */
    fun fpsLimit(container: Container): FpsLimit = FpsLimit(
        parseBooleanExtra(container.getExtra(FPS_ENABLED_EXTRA)) ?: true,
        parsePositiveFpsLimit(container.getExtra(FPS_TARGET_EXTRA)) ?: 60,
    )

    fun saveFpsLimit(context: Context, limit: FpsLimit, active: () -> Boolean) {
        require(limit.target in 5..1000) { "Invalid FPS target." }
        check(!active()) { "Stop the game before changing the FPS limit." }
        val container = ContainerManager(context).getContainerById(WowContainerProfile.CONTAINER_ID)
            ?: error("WoW container has not been created yet")
        container.putExtra(FPS_ENABLED_EXTRA, limit.enabled)
        container.putExtra(FPS_TARGET_EXTRA, limit.target)
        container.saveData()
        check(fpsLimit(ContainerManager(context).getContainerById(container.id)!!) == limit) { "FPS limit was not saved." }
    }

    data class Status(val api: String, val runtime: String, val dxvk: String, val driver: String, val vkd3d: String,
        val gpuMode: String, val fps: String) {
        /** The graphics layer the next launch uses: VKD3D for DX12, DXVK for DX11. */
        val graphicsLayer: String get() = if (api == "DX12") vkd3d else dxvk
    }

    /** Human-readable snapshot of what the next Play will use: GPU and FPS with the launch's own precedence (saved variables win). */
    fun status(context: Context, container: Container): Status {
        val next = WowRuntimeTuning.preview(context, container)
        return Status(
            api = runCatching { WowVkd3dComponents.api(container) }.getOrDefault("?").uppercase(),
            runtime = shortRuntime(container.wineVersion),
            dxvk = shortDxvk(container.getDXWrapper()),
            driver = WowComponentNames.display(KeyValueSet(container.graphicsDriverConfig).get("version").ifBlank { container.graphicsDriverVersion }),
            vkd3d = shortVkd3d(container.getExtra(WowVkd3dComponents.PROFILE_KEY)).ifBlank { "VKD3D -" },
            gpuMode = next.gpuMode,
            fps = next.fps,
        )
    }

    /** Display names without internal revisions (WowComponentNames): "Proton 11.0 ARM64EC", "VKD3D 3.0.1", "DXVK 2.7.3". */
    fun shortRuntime(id: String): String = WowComponentNames.display(id)

    fun shortVkd3d(id: String): String = WowComponentNames.display(id)

    fun shortDxvk(wrapper: String): String = WowComponentNames.display(wrapper)
}
