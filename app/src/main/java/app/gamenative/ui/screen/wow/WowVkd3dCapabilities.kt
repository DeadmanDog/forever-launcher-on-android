package app.gamenative.ui.screen.wow

import android.content.Context
import com.winlator.container.Container
import com.winlator.contents.AdrenotoolsManager
import com.winlator.core.AppUtils
import org.json.JSONObject
import java.io.File

internal object WowVkd3dCapabilities {
    data class Report(val json: JSONObject, val failures: List<String>) {
        val compatible get() = failures.isEmpty()
        fun summary() = if (compatible) "Vulkan/D3D12 prerequisites passed (${json.optString("deviceName")}). Device WoW validation is still required."
            else "DX12 unavailable: ${failures.joinToString("; ")}"
    }
    private object Native { init { System.loadLibrary("wow_vkd3d_probe") } }
    private external fun nativeProbe(nativeDir: String, driverDir: String, library: String, temporary: String): String
    fun probe(context: Context, container: Container): Report = synchronized(this) {
        require(container.graphicsDriver.equals("Wrapper", true) && container.displayRenderer == "vulkan") { "DX12 requires the existing Wrapper/Vulkan renderer." }
        val driver = com.winlator.core.KeyValueSet(container.graphicsDriverConfig).get("version")
        val directory = File(context.filesDir, "contents/adrenotools/$driver").canonicalFile
        require(AdrenotoolsManager.validateDriverDirectory(directory) == driver) { "Selected Vulkan driver is not installed." }
        Native
        val raw = nativeProbe(AppUtils.getNativeLibDir(context), directory.path + "/",
            AdrenotoolsManager(context).getLibraryName(driver), File(context.cacheDir, "vkd3d-probe").apply { mkdirs() }.path)
        evaluate(JSONObject(raw))
    }
    fun evaluate(json: JSONObject): Report {
        val failures = mutableListOf<String>()
        if (json.has("error")) failures += json.getString("error")
        if (json.optInt("apiVersion") < (1 shl 22) + (3 shl 12)) failures += "Vulkan 1.3 is required"
        val features = json.optJSONObject("features") ?: JSONObject()
        for (name in listOf("shaderDrawParameters", "samplerMirrorClampToEdge", "descriptorIndexing",
            "shaderSampledImageArrayNonUniformIndexing", "shaderStorageBufferArrayNonUniformIndexing",
            "shaderStorageImageArrayNonUniformIndexing", "shaderUniformTexelBufferArrayNonUniformIndexing",
            "shaderStorageTexelBufferArrayNonUniformIndexing", "descriptorBindingSampledImageUpdateAfterBind",
            "descriptorBindingStorageImageUpdateAfterBind", "descriptorBindingStorageBufferUpdateAfterBind",
            "descriptorBindingUniformTexelBufferUpdateAfterBind", "descriptorBindingStorageTexelBufferUpdateAfterBind",
            "descriptorBindingPartiallyBound", "descriptorBindingVariableDescriptorCount", "runtimeDescriptorArray",
            "bufferDeviceAddress", "timelineSemaphore", "synchronization2", "dynamicRendering",
            "robustBufferAccess2", "robustImageAccess2", "nullDescriptor", "robustBufferAccessUpdateAfterBind")) {
            if (!features.optBoolean(name)) failures += "Missing $name"
        }
        val extensions = json.optJSONArray("extensions")
        val names = (0 until (extensions?.length() ?: 0)).map { extensions!!.getString(it) }.toSet()
        for (name in listOf("VK_EXT_robustness2", "VK_KHR_push_descriptor")) if (name !in names) failures += "Missing $name"
        val limits = json.optJSONObject("limits") ?: JSONObject()
        for (name in listOf("maxPerStageDescriptorUpdateAfterBindSampledImages", "maxPerStageDescriptorUpdateAfterBindStorageImages",
            "maxPerStageDescriptorUpdateAfterBindStorageBuffers", "maxDescriptorSetUpdateAfterBindSampledImages",
            "maxDescriptorSetUpdateAfterBindStorageImages", "maxDescriptorSetUpdateAfterBindStorageBuffers", "maxUpdateAfterBindDescriptorsInAllPools")) {
            if (limits.optLong(name) < 1000000) failures += "$name < 1000000"
        }
        for (name in listOf("maxPerStageDescriptorUpdateAfterBindSamplers", "maxDescriptorSetUpdateAfterBindSamplers")) {
            if (limits.optLong(name) < 2048) failures += "$name < 2048"
        }
        if (json.optInt("createDeviceResult", -1) != 0) failures += "Vulkan device creation did not pass"
        return Report(json, failures)
    }
}
