#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <vulkan/vulkan.h>

/*
 * Highest Vulkan API version of the first physical device, or 0 when Vulkan cannot be queried.
 * Devices above 1.2 report 1.2.0 unless their feature set is the known-good one below.
 */
static const VkBool32 kFullFeatureSet[55] = {
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1,
    1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1
};

JNIEXPORT jint JNICALL
Java_com_winlator_core_GPUHelper_vkGetApiVersion(JNIEnv *env, jclass clazz) {
    jint version = 0;
    VkInstance instance = VK_NULL_HANDLE;

    void *lib = dlopen("/system/lib64/libvulkan.so", RTLD_NOW);
    if (!lib) return 0;

    PFN_vkCreateInstance createInstance = (PFN_vkCreateInstance) dlsym(lib, "vkCreateInstance");
    PFN_vkDestroyInstance destroyInstance = (PFN_vkDestroyInstance) dlsym(lib, "vkDestroyInstance");
    PFN_vkEnumeratePhysicalDevices enumerateDevices = (PFN_vkEnumeratePhysicalDevices) dlsym(lib, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties getProperties = (PFN_vkGetPhysicalDeviceProperties) dlsym(lib, "vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceFeatures getFeatures = (PFN_vkGetPhysicalDeviceFeatures) dlsym(lib, "vkGetPhysicalDeviceFeatures");
    if (!createInstance || !destroyInstance || !enumerateDevices || !getProperties || !getFeatures) {
        dlclose(lib);
        return 0;
    }

    VkInstanceCreateInfo createInfo;
    memset(&createInfo, 0, sizeof(createInfo));
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;

    if (createInstance(&createInfo, NULL, &instance) == VK_SUCCESS) {
        uint32_t count = 1;
        VkPhysicalDevice device = VK_NULL_HANDLE;
        VkResult result = enumerateDevices(instance, &count, &device);
        if (result == VK_SUCCESS || result == VK_INCOMPLETE) {
            VkPhysicalDeviceProperties properties;
            memset(&properties, 0, sizeof(properties));
            getProperties(device, &properties);
            version = (jint) properties.apiVersion;

            if (properties.apiVersion > VK_MAKE_API_VERSION(0, 1, 2, 4095)) {
                VkPhysicalDeviceFeatures features;
                memset(&features, 0, sizeof(features));
                getFeatures(device, &features);
                if (memcmp(&features, kFullFeatureSet, sizeof(features)) != 0) {
                    version = (jint) VK_MAKE_API_VERSION(0, 1, 2, 0);
                }
            }
        }
        destroyInstance(instance, NULL);
    }

    dlclose(lib);
    return version;
}
