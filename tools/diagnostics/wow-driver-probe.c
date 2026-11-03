/* Runs inside Wine, through vulkan-1.dll and the selected Android wrapper. */
#define WIN32_LEAN_AND_MEAN
#define VK_NO_PROTOTYPES
#include <windows.h>
#include <stdio.h>
#include <vulkan/vulkan_core.h>

static void string(const char *value) {
    putchar('"');
    for (const unsigned char *p=(const unsigned char*)value;*p;p++) {
        if (*p=='"' || *p=='\\') {putchar('\\');putchar(*p);}
        else if (*p<32) printf("\\u%04x",*p);
        else putchar(*p);
    }
    putchar('"');
}
int WINAPI WinMain(HINSTANCE h,HINSTANCE previous,LPSTR args,int show) {
    HMODULE module=LoadLibraryW(L"vulkan-1.dll");
    if (!module) {fprintf(stderr,"driver_probe: vulkan-1.dll load failed: %lu\n",GetLastError());return 1;}
    PFN_vkGetInstanceProcAddr get=(PFN_vkGetInstanceProcAddr)GetProcAddress(module,"vkGetInstanceProcAddr");
    if (!get) {fprintf(stderr,"driver_probe: vkGetInstanceProcAddr missing\n");return 2;}
    PFN_vkCreateInstance create=(PFN_vkCreateInstance)get(VK_NULL_HANDLE,"vkCreateInstance");
    if (!create) return 3;
    VkApplicationInfo app={VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName="WoW Forever Wine driver probe";app.apiVersion=VK_API_VERSION_1_1;
    VkInstanceCreateInfo info={VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};info.pApplicationInfo=&app;
    VkInstance instance=VK_NULL_HANDLE;VkResult status=create(&info,NULL,&instance);
    if (status!=VK_SUCCESS) {fprintf(stderr,"driver_probe: vkCreateInstance result=%d\n",status);return 4;}
    PFN_vkEnumeratePhysicalDevices enumerate=(PFN_vkEnumeratePhysicalDevices)get(instance,"vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties2 properties=(PFN_vkGetPhysicalDeviceProperties2)get(instance,"vkGetPhysicalDeviceProperties2");
    PFN_vkDestroyInstance destroy=(PFN_vkDestroyInstance)get(instance,"vkDestroyInstance");
    VkPhysicalDevice devices[16];uint32_t count=16;
    if (!enumerate || !properties || !destroy) return 5;
    status=enumerate(instance,&count,devices);
    if (status!=VK_SUCCESS && status!=VK_INCOMPLETE) {destroy(instance,NULL);return 6;}
    for (uint32_t i=0;i<count;i++) {
        VkPhysicalDeviceDriverProperties driver={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
        VkPhysicalDeviceProperties2 p={VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};p.pNext=&driver;
        properties(devices[i],&p);
        printf("{\"probe\":\"wine-vulkan-wrapper\",\"deviceIndex\":%u,\"driverID\":%u,\"driverName\":",i,driver.driverID);
        string(driver.driverName);printf(",\"deviceName\":");string(p.properties.deviceName);
        printf(",\"driverInfo\":");string(driver.driverInfo);
        printf(",\"apiVersion\":%u,\"vendorID\":%u,\"deviceID\":%u}\n",p.properties.apiVersion,p.properties.vendorID,p.properties.deviceID);
    }
    fflush(stdout);destroy(instance,NULL);FreeLibrary(module);return 0;
}
