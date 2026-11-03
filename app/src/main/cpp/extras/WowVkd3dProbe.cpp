#include <jni.h>
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <sstream>
#include <string>
#include <vector>
#include <cstring>
#include "adrenotools/driver.h"

namespace {
std::string quote(const char* text) {
    std::string out = "\"";
    for (const unsigned char* p = (const unsigned char*)text; *p; ++p) {
        if (*p == '\\' || *p == '"') out += '\\';
        if (*p >= 32) out += char(*p);
    }
    return out + "\"";
}
struct Utf {
    JNIEnv* env; jstring value; const char* text;
    Utf(JNIEnv* e, jstring v):env(e),value(v),text(e->GetStringUTFChars(v,nullptr)) {}
    ~Utf(){if(text)env->ReleaseStringUTFChars(value,text);}
};
struct Driver {
    void* handle=nullptr; VkInstance instance=VK_NULL_HANDLE; VkDevice device=VK_NULL_HANDLE;
    PFN_vkDestroyInstance destroyInstance=nullptr; PFN_vkDestroyDevice destroyDevice=nullptr;
    ~Driver(){if(device&&destroyDevice)destroyDevice(device,nullptr);if(instance&&destroyInstance)destroyInstance(instance,nullptr);if(handle)dlclose(handle);}
};
std::string probe(const char* native,const char* directory,const char* name,const char* temp) {
    Driver d;
    d.handle=adrenotools_open_libvulkan(RTLD_NOW|RTLD_LOCAL,ADRENOTOOLS_DRIVER_CUSTOM,temp,native,directory,name,nullptr,nullptr);
    if(!d.handle)return "{\"error\":\"Selected custom Vulkan driver could not be loaded\"}";
    auto gpa=(PFN_vkGetInstanceProcAddr)dlsym(d.handle,"vkGetInstanceProcAddr");
    if(!gpa)return "{\"error\":\"Vulkan dispatch is missing\"}";
    auto create=(PFN_vkCreateInstance)gpa(nullptr,"vkCreateInstance");
    if(!create)return "{\"error\":\"vkCreateInstance is missing\"}";
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.pApplicationName="FrontMage DX12 prerequisites";app.apiVersion=VK_API_VERSION_1_3;
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ci.pApplicationInfo=&app;
    auto result=create(&ci,nullptr,&d.instance);
    if(result!=VK_SUCCESS)return "{\"error\":\"Vulkan 1.3 instance creation failed\",\"result\":"+std::to_string(result)+"}";
    d.destroyInstance=(PFN_vkDestroyInstance)gpa(d.instance,"vkDestroyInstance");
    auto enumerate=(PFN_vkEnumeratePhysicalDevices)gpa(d.instance,"vkEnumeratePhysicalDevices");
    auto getFeatures=(PFN_vkGetPhysicalDeviceFeatures2)gpa(d.instance,"vkGetPhysicalDeviceFeatures2");
    auto getProperties=(PFN_vkGetPhysicalDeviceProperties2)gpa(d.instance,"vkGetPhysicalDeviceProperties2");
    auto getExtensions=(PFN_vkEnumerateDeviceExtensionProperties)gpa(d.instance,"vkEnumerateDeviceExtensionProperties");
    auto getQueues=(PFN_vkGetPhysicalDeviceQueueFamilyProperties)gpa(d.instance,"vkGetPhysicalDeviceQueueFamilyProperties");
    auto getMemory=(PFN_vkGetPhysicalDeviceMemoryProperties)gpa(d.instance,"vkGetPhysicalDeviceMemoryProperties");
    auto createDevice=(PFN_vkCreateDevice)gpa(d.instance,"vkCreateDevice");
    auto gdpa=(PFN_vkGetDeviceProcAddr)gpa(d.instance,"vkGetDeviceProcAddr");
    d.destroyDevice=(PFN_vkDestroyDevice)gpa(d.instance,"vkDestroyDevice");
    if(!d.destroyInstance||!enumerate||!getFeatures||!getProperties||!getExtensions||!getQueues||!getMemory||!createDevice||!gdpa||!d.destroyDevice)return "{\"error\":\"Required Vulkan 1.3 entry points are missing\"}";
    uint32_t count=0;
    if(enumerate(d.instance,&count,nullptr)!=VK_SUCCESS||count==0||count>32)return "{\"error\":\"No Vulkan physical device\"}";
    std::vector<VkPhysicalDevice> devices(count);
    if(enumerate(d.instance,&count,devices.data())!=VK_SUCCESS)return "{\"error\":\"Physical device enumeration failed\"}";
    VkPhysicalDevice pd=devices[0];
    uint32_t ec=0;
    if(getExtensions(pd,nullptr,&ec,nullptr)!=VK_SUCCESS||ec>4096)return "{\"error\":\"Extension enumeration failed\"}";
    std::vector<VkExtensionProperties> exts(ec);
    if(getExtensions(pd,nullptr,&ec,exts.data())!=VK_SUCCESS)return "{\"error\":\"Extension enumeration failed\"}";
    bool robustness=false,push=false;
    for(uint32_t i=0;i<ec;i++){robustness|=!strcmp(exts[i].extensionName,"VK_EXT_robustness2");push|=!strcmp(exts[i].extensionName,"VK_KHR_push_descriptor");}
    VkPhysicalDeviceRobustness2FeaturesEXT robust{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ROBUSTNESS_2_FEATURES_EXT};
    VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};f13.pNext=robustness?&robust:nullptr;
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};f12.pNext=&f13;
    VkPhysicalDeviceVulkan11Features f11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};f11.pNext=&f12;
    VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};features.pNext=&f11;
    VkPhysicalDeviceVulkan12Properties p12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_PROPERTIES};
    VkPhysicalDeviceProperties2 properties{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};properties.pNext=&p12;
    getProperties(pd,&properties);
    if(properties.properties.apiVersion<VK_API_VERSION_1_3)return "{\"error\":\"Physical device is below Vulkan 1.3\",\"apiVersion\":"+std::to_string(properties.properties.apiVersion)+"}";
    getFeatures(pd,&features);
    VkPhysicalDeviceMemoryProperties memory{};getMemory(pd,&memory);
    uint32_t qc=0;getQueues(pd,&qc,nullptr);if(qc==0||qc>256)return "{\"error\":\"No graphics queue\"}";
    std::vector<VkQueueFamilyProperties> queues(qc);getQueues(pd,&qc,queues.data());
    uint32_t family=qc;for(uint32_t i=0;i<qc;i++)if(queues[i].queueCount&&(queues[i].queueFlags&VK_QUEUE_GRAPHICS_BIT)){family=i;break;}
    VkResult createResult=VK_ERROR_FEATURE_NOT_PRESENT;
    if(family<qc&&robustness&&push){
        const char* required[]={"VK_EXT_robustness2","VK_KHR_push_descriptor"};float priority=1;
        VkDeviceQueueCreateInfo queue{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};queue.queueFamilyIndex=family;queue.queueCount=1;queue.pQueuePriorities=&priority;
        VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dc.pNext=&features;dc.queueCreateInfoCount=1;dc.pQueueCreateInfos=&queue;dc.enabledExtensionCount=2;dc.ppEnabledExtensionNames=required;
        createResult=createDevice(pd,&dc,nullptr,&d.device);
        if(d.device){auto destroy=(PFN_vkDestroyDevice)gdpa(d.device,"vkDestroyDevice");if(destroy)d.destroyDevice=destroy;}
    }
    std::ostringstream out;out<<"{\"deviceName\":"<<quote(properties.properties.deviceName)<<",\"apiVersion\":"<<properties.properties.apiVersion<<",\"driverVersion\":"<<properties.properties.driverVersion<<",\"createDeviceResult\":"<<createResult<<",\"extensions\":[";
    for(uint32_t i=0;i<ec;i++){if(i)out<<',';out<<quote(exts[i].extensionName);}out<<"],\"features\":{";
    bool first=true;
    auto feature=[&](const char* name,VkBool32 enabled){if(!first)out<<',';first=false;out<<quote(name)<<":"<<(enabled?"true":"false");};
    feature("shaderDrawParameters",f11.shaderDrawParameters);
#define F12(name) feature(#name,f12.name)
    F12(samplerMirrorClampToEdge);F12(descriptorIndexing);F12(shaderSampledImageArrayNonUniformIndexing);F12(shaderStorageBufferArrayNonUniformIndexing);F12(shaderStorageImageArrayNonUniformIndexing);F12(shaderUniformTexelBufferArrayNonUniformIndexing);F12(shaderStorageTexelBufferArrayNonUniformIndexing);F12(descriptorBindingSampledImageUpdateAfterBind);F12(descriptorBindingStorageImageUpdateAfterBind);F12(descriptorBindingStorageBufferUpdateAfterBind);F12(descriptorBindingUniformTexelBufferUpdateAfterBind);F12(descriptorBindingStorageTexelBufferUpdateAfterBind);F12(descriptorBindingPartiallyBound);F12(descriptorBindingVariableDescriptorCount);F12(runtimeDescriptorArray);F12(bufferDeviceAddress);F12(timelineSemaphore);
#undef F12
    feature("robustBufferAccessUpdateAfterBind",p12.robustBufferAccessUpdateAfterBind);
    feature("synchronization2",f13.synchronization2);feature("dynamicRendering",f13.dynamicRendering);feature("robustBufferAccess2",robust.robustBufferAccess2);feature("robustImageAccess2",robust.robustImageAccess2);feature("nullDescriptor",robust.nullDescriptor);
    out<<"},\"limits\":{";first=true;
    auto limit=[&](const char* name,uint32_t value){if(!first)out<<',';first=false;out<<quote(name)<<":"<<value;};
#define P12(name) limit(#name,p12.name)
    P12(maxPerStageDescriptorUpdateAfterBindSampledImages);P12(maxPerStageDescriptorUpdateAfterBindStorageImages);P12(maxPerStageDescriptorUpdateAfterBindStorageBuffers);P12(maxPerStageDescriptorUpdateAfterBindSamplers);P12(maxDescriptorSetUpdateAfterBindSampledImages);P12(maxDescriptorSetUpdateAfterBindStorageImages);P12(maxDescriptorSetUpdateAfterBindStorageBuffers);P12(maxDescriptorSetUpdateAfterBindSamplers);P12(maxUpdateAfterBindDescriptorsInAllPools);
#undef P12
    out<<"},\"memoryHeaps\":[";
    for(uint32_t i=0;i<memory.memoryHeapCount;i++){if(i)out<<',';out<<"{\"size\":"<<memory.memoryHeaps[i].size<<",\"flags\":"<<memory.memoryHeaps[i].flags<<"}";}
    out<<"],\"memoryTypes\":[";
    for(uint32_t i=0;i<memory.memoryTypeCount;i++){if(i)out<<',';out<<"{\"heapIndex\":"<<memory.memoryTypes[i].heapIndex<<",\"propertyFlags\":"<<memory.memoryTypes[i].propertyFlags<<"}";}
    out<<"]}";return out.str();
}
}
extern "C" JNIEXPORT jstring JNICALL Java_app_gamenative_ui_screen_wow_WowVkd3dCapabilities_nativeProbe(JNIEnv* env,jobject,jstring native,jstring directory,jstring library,jstring temporary){
    Utf n(env,native),d(env,directory),l(env,library),t(env,temporary);
    if(!n.text||!d.text||!l.text||!t.text)return nullptr;
    try {auto result=probe(n.text,d.text,l.text,t.text);return env->NewStringUTF(result.c_str());}
    catch(...){return env->NewStringUTF("{\"error\":\"Vulkan probe failed\"}");}
}
