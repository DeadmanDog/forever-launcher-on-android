#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wmissing-field-initializers"
#include "IdlePollPacer.h"
#include "VulkanRendererContext.h"
#include "AcquiredFrameGuard.h"
#include "PerformanceHintCycle.h"
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>
#include <chrono>
#include <stdexcept>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <cmath>
#include <inttypes.h>
#include <dlfcn.h>
#include "window_vert.h"
#include "window_frag.h"

VulkanRendererContext::VulkanRendererContext(ANativeWindow* win, int cW, int cH, void* aHandle)
    : window(win), surfaceWidth(cW), surfaceHeight(cH), containerWidth(cW), containerHeight(cH),
      adrenotoolsHandle(aHandle)
{
    try {
        createInstance(); createSurface(); pickPhysicalDevice(); createLogicalDevice();
        createSwapchain(); createRenderPass(); createDSLayout();
        createPipeline(true, pipeline);
        createFramebuffers(); createCmdPool(); createSampler();
        createWinTexPool(); createCursorDS(); createCmdBufs(); createSyncObjects();
        isRunning = true;
        renderThread = std::thread(&VulkanRendererContext::renderLoop, this);
    } catch (...) {
        // The destructor does not run for a constructor that throws: free what the earlier steps created.
        // The caller (JNI) still owns the window and the driver handle and releases them itself.
        isRunning = false;
        teardown(false);
        throw;
    }
}

VulkanRendererContext::~VulkanRendererContext() { teardown(true); }

void VulkanRendererContext::teardown(bool releaseExternal) {
    isRunning = false; renderWake.notify();
    if (renderThread.joinable()) renderThread.join();
    // The scanout layers go first (their last buffer is handed back once the layer is gone), then the hand-back thread stops and
    // returns whatever still waits. Neither needs the device or renderMutex.
    destroyScanout();
    if (scanoutReleaser) scanoutReleaser->stop();
    std::lock_guard<std::mutex> lk(renderMutex);
    if (device != VK_NULL_HANDLE) {
        if (vk_.DeviceWaitIdle) vk_.DeviceWaitIdle(device);
        for (auto& [id, wt] : texMap) destroyWinTex(wt);
        texMap.clear();
        // Imported AHB textures are only owned by the cache; without this their images,
        // memory and buffer references outlive the renderer in a long-lived process.
        for (auto& [ahb, wt] : ahbImportCache) {
            WinTex deferred = wt;
            deferred.isAHB = false;
            deleteQueue.push_back(deferred);
            AHardwareBuffer_release(ahb);
        }
        ahbImportCache.clear();
        RLOG_W("AHB imports: peak %zu in one window, peak %zu in total, %zu retired by the limits", ahbPeakPerWindow, ahbPeakTotal, ahbEvictions);

        freeRetiredTextures(deleteQueue);
        freeRetiredTextures(pendingRetirements);
        for (auto& batch : submittedRetirements) freeRetiredTextures(batch);
        cleanupSwapchain(); cleanupCursorTex();

        // Destroying a handle that was never created (VK_NULL_HANDLE) is a no-op, so each step is safe on its own.
        vk_.DestroySampler(device, sampler, nullptr);
        vk_.DestroyDescriptorPool(device, winTexPool, nullptr);
        vk_.DestroyPipeline(device, pipeline, nullptr);
        vk_.DestroyPipelineLayout(device, pipeLayout, nullptr);
        vk_.DestroyDescriptorSetLayout(device, dsLayout, nullptr);
        for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            if (i < imgAvailSems.size() && imgAvailSems[i] != VK_NULL_HANDLE) {
                vk_.DestroySemaphore(device, imgAvailSems[i], nullptr);
                imgAvailSems[i] = VK_NULL_HANDLE;
            }
            if (i < inFlightFences.size() && inFlightFences[i] != VK_NULL_HANDLE) {
                vk_.DestroyFence(device, inFlightFences[i], nullptr);
                inFlightFences[i] = VK_NULL_HANDLE;
            }
        }
        vk_.DestroyCommandPool(device, cmdPool, nullptr);
        vk_.DestroyRenderPass(device, renderPass, nullptr);
        vk_.DestroyDevice(device, nullptr);
        device = VK_NULL_HANDLE;
    }
    if (instance != VK_NULL_HANDLE) {
        if (surface != VK_NULL_HANDLE) { vk_.DestroySurfaceKHR(instance, surface, nullptr); surface = VK_NULL_HANDLE; }
        if (releaseExternal && window) { ANativeWindow_release(window); window = nullptr; }
        vk_.DestroyInstance(instance, nullptr);
        instance = VK_NULL_HANDLE;
    } else if (releaseExternal && window) {
        ANativeWindow_release(window); window = nullptr;
    }
    if (releaseExternal && adrenotoolsHandle) { dlclose(adrenotoolsHandle); adrenotoolsHandle = nullptr; }
}

void VulkanRendererContext::loadInstanceDispatch() {
    auto i = [&](const char* name) { return gipa ? gipa(instance, name) : nullptr; };
#define LOAD_I2(fn) vk_.fn = (PFN_vk##fn)i("vk"#fn)
    LOAD_I2(DestroyInstance);
    LOAD_I2(EnumeratePhysicalDevices);
    LOAD_I2(GetPhysicalDeviceProperties);
    LOAD_I2(GetPhysicalDeviceMemoryProperties);
    LOAD_I2(GetPhysicalDeviceFormatProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    LOAD_I2(GetPhysicalDeviceSurfaceFormatsKHR);
    LOAD_I2(GetPhysicalDeviceSurfacePresentModesKHR);
    LOAD_I2(GetPhysicalDeviceQueueFamilyProperties);
    LOAD_I2(GetPhysicalDeviceSurfaceSupportKHR);
    LOAD_I2(CreateDevice);
    LOAD_I2(DestroySurfaceKHR);
    LOAD_I2(CreateAndroidSurfaceKHR);
    LOAD_I2(GetDeviceProcAddr);
}

void VulkanRendererContext::loadDeviceDispatch() {
    auto d = [&](const char* name) -> PFN_vkVoidFunction {
        return vk_.GetDeviceProcAddr ? vk_.GetDeviceProcAddr(device, name) : nullptr;
    };
#define LOAD_D2(fn) vk_.fn = (PFN_vk##fn)d("vk"#fn)
    LOAD_D2(DestroyDevice);
    LOAD_D2(GetDeviceQueue);
    LOAD_D2(DeviceWaitIdle);
    LOAD_D2(CreateSwapchainKHR);
    LOAD_D2(DestroySwapchainKHR);
    LOAD_D2(GetSwapchainImagesKHR);
    LOAD_D2(AcquireNextImageKHR);
    LOAD_D2(QueuePresentKHR);
    LOAD_D2(GetRefreshCycleDurationGOOGLE);
    LOAD_D2(GetPastPresentationTimingGOOGLE);
    LOAD_D2(QueueSubmit);
    LOAD_D2(CreateRenderPass);
    LOAD_D2(DestroyRenderPass);
    LOAD_D2(CreateFramebuffer);
    LOAD_D2(DestroyFramebuffer);
    LOAD_D2(CreateImageView);
    LOAD_D2(DestroyImageView);
    LOAD_D2(CreateImage);
    LOAD_D2(DestroyImage);
    LOAD_D2(CreateBuffer);
    LOAD_D2(DestroyBuffer);
    LOAD_D2(AllocateMemory);
    LOAD_D2(FreeMemory);
    LOAD_D2(MapMemory);
    LOAD_D2(FlushMappedMemoryRanges);
    LOAD_D2(BindBufferMemory);
    LOAD_D2(BindImageMemory);
    LOAD_D2(GetBufferMemoryRequirements);
    LOAD_D2(GetImageMemoryRequirements);
    LOAD_D2(CreateDescriptorSetLayout);
    LOAD_D2(DestroyDescriptorSetLayout);
    LOAD_D2(CreateDescriptorPool);
    LOAD_D2(DestroyDescriptorPool);
    LOAD_D2(AllocateDescriptorSets);
    LOAD_D2(FreeDescriptorSets);
    LOAD_D2(UpdateDescriptorSets);
    LOAD_D2(CreatePipelineLayout);
    LOAD_D2(DestroyPipelineLayout);
    LOAD_D2(CreateShaderModule);
    LOAD_D2(DestroyShaderModule);
    LOAD_D2(CreateGraphicsPipelines);
    LOAD_D2(DestroyPipeline);
    LOAD_D2(CreateCommandPool);
    LOAD_D2(DestroyCommandPool);
    LOAD_D2(AllocateCommandBuffers);
    LOAD_D2(FreeCommandBuffers);
    LOAD_D2(BeginCommandBuffer);
    LOAD_D2(EndCommandBuffer);
    LOAD_D2(ResetCommandBuffer);
    LOAD_D2(CmdBeginRenderPass);
    LOAD_D2(CmdEndRenderPass);
    LOAD_D2(CmdBindPipeline);
    LOAD_D2(CmdBindDescriptorSets);
    LOAD_D2(CmdDraw);
    LOAD_D2(CmdPushConstants);
    LOAD_D2(CmdSetViewport);
    LOAD_D2(CmdSetScissor);
    LOAD_D2(CmdPipelineBarrier);
    LOAD_D2(CmdCopyImage);
    LOAD_D2(CmdBlitImage);
    LOAD_D2(CmdCopyBufferToImage);
    LOAD_D2(CreateSampler);
    LOAD_D2(DestroySampler);
    LOAD_D2(CreateSemaphore);
    LOAD_D2(DestroySemaphore);
    LOAD_D2(CreateFence);
    LOAD_D2(DestroyFence);
    LOAD_D2(WaitForFences);
    LOAD_D2(ResetFences);
    LOAD_D2(GetFenceStatus);

    vk_.GetAndroidHardwareBufferPropertiesANDROID =
        (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)d("vkGetAndroidHardwareBufferPropertiesANDROID");
}

void VulkanRendererContext::createInstance() {
    RLOG("createInstance: adrenotoolsHandle=%p (custom driver %s)",
        adrenotoolsHandle, adrenotoolsHandle?"ACTIVE":"NOT SET - using stock driver");

    if (adrenotoolsHandle) {
        gipa = (PFN_vkGetInstanceProcAddr)dlsym(adrenotoolsHandle, "vkGetInstanceProcAddr");
    }
    if (!gipa) {
        void* loaderLib = dlopen("libvulkan.so", RTLD_NOW | RTLD_GLOBAL);
        if (loaderLib)
            gipa = (PFN_vkGetInstanceProcAddr)dlsym(loaderLib, "vkGetInstanceProcAddr");
    }

    // No loader and no driver entry point: a C++ exception (the caller returns 0), not a call through a null pointer.
    if (!gipa) throw std::runtime_error("no vkGetInstanceProcAddr");
    vk_.CreateInstance = (PFN_vkCreateInstance)gipa(nullptr, "vkCreateInstance");
    if (!vk_.CreateInstance) throw std::runtime_error("no vkCreateInstance");
    VkApplicationInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;
    ai.pApplicationName="Winlator"; ai.apiVersion=VK_API_VERSION_1_3;
    const char* ext[]={"VK_KHR_surface","VK_KHR_android_surface"};
    VkInstanceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo=&ai; ci.enabledExtensionCount=2; ci.ppEnabledExtensionNames=ext;
    if (vk_.CreateInstance(&ci,nullptr,&instance)!=VK_SUCCESS) throw std::runtime_error("instance");

    loadInstanceDispatch();
}

void VulkanRendererContext::createSurface() {
    VkAndroidSurfaceCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window=window;
    if (vk_.CreateAndroidSurfaceKHR(instance,&ci,nullptr,&surface)!=VK_SUCCESS) throw std::runtime_error("surface");
}

void VulkanRendererContext::pickPhysicalDevice() {
    uint32_t n=0; vk_.EnumeratePhysicalDevices(instance,&n,nullptr);
    std::vector<VkPhysicalDevice> devs(n); vk_.EnumeratePhysicalDevices(instance,&n,devs.data());
    physicalDevice = VK_NULL_HANDLE;
    graphicsQueueFamilyIndex = 0;
    for (auto d : devs) {
        uint32_t qCount = 0;
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vk_.GetPhysicalDeviceQueueFamilyProperties(d, &qCount, qProps.data());
        for (uint32_t i = 0; i < qCount; i++) {
            VkBool32 present = VK_FALSE;
            vk_.GetPhysicalDeviceSurfaceSupportKHR(d, i, surface, &present);
            if ((qProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && present) {
                physicalDevice = d;
                graphicsQueueFamilyIndex = i;
                return;
            }
        }
    }
    if (n > 0) physicalDevice = devs[0];
}

void VulkanRendererContext::createLogicalDevice() {
    float p=1.f;
    VkDeviceQueueCreateInfo qi{}; qi.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qi.queueFamilyIndex=graphicsQueueFamilyIndex; qi.queueCount=1; qi.pQueuePriorities=&p;

    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice, &props);
    maxAnisotropy = props.limits.maxSamplerAnisotropy;

    PFN_vkEnumerateDeviceExtensionProperties enumDevExts =
        (PFN_vkEnumerateDeviceExtensionProperties)gipa(instance, "vkEnumerateDeviceExtensionProperties");
    { uint32_t n=0; if(enumDevExts) enumDevExts(physicalDevice,nullptr,&n,nullptr);
      std::vector<VkExtensionProperties> av(n);
      if(enumDevExts) enumDevExts(physicalDevice,nullptr,&n,av.data());
      for (auto& e:av) {
          if (strcmp(e.extensionName,"VK_EXT_filter_cubic")==0) cubicExtName="VK_EXT_filter_cubic";
          else if (strcmp(e.extensionName,"VK_IMG_filter_cubic")==0 && !cubicExtName) cubicExtName="VK_IMG_filter_cubic";
          if (strcmp(e.extensionName,VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME)==0) displayTimingSupported=true;
      } }
    // Cubic filtering also needs the format feature on the formats it samples (the CPU window textures and the cursor are
    // B8G8R8A8, the game's buffers usually R8G8B8A8); without it the filter falls back to linear. The extension is enabled only
    // under the name the driver really offers (enabling VK_EXT_filter_cubic on a VK_IMG-only driver failed device creation).
    cubicSupported = false;
    if (cubicExtName && vk_.GetPhysicalDeviceFormatProperties) {
        VkFormatProperties bgra{}, rgba{};
        vk_.GetPhysicalDeviceFormatProperties(physicalDevice, VK_FORMAT_B8G8R8A8_UNORM, &bgra);
        vk_.GetPhysicalDeviceFormatProperties(physicalDevice, VK_FORMAT_R8G8B8A8_UNORM, &rgba);
        const VkFormatFeatureFlags cubicBit = VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_CUBIC_BIT_EXT;
        cubicSupported = (bgra.optimalTilingFeatures & cubicBit) && (rgba.optimalTilingFeatures & cubicBit);
    }
    std::vector<const char*> extList = {
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
    };
    if (cubicSupported) extList.push_back(cubicExtName);
    if (displayTimingSupported) extList.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
    VkDeviceCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    ci.pQueueCreateInfos=&qi; ci.queueCreateInfoCount=1;
    ci.enabledExtensionCount=(uint32_t)extList.size(); ci.ppEnabledExtensionNames=extList.data();

    if (vk_.CreateDevice(physicalDevice,&ci,nullptr,&device)!=VK_SUCCESS) throw std::runtime_error("device");
    vk_.GetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)gipa(instance, "vkGetDeviceProcAddr");
    loadDeviceDispatch();
    if (!vk_.GetRefreshCycleDurationGOOGLE || !vk_.GetPastPresentationTimingGOOGLE) displayTimingSupported=false;
    vk_.GetDeviceQueue(device,graphicsQueueFamilyIndex,0,&graphicsQueue);

    vk_.GetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);
}

struct SwapchainZeroExtent : std::runtime_error { SwapchainZeroExtent() : std::runtime_error("swapchain extent 0") {} };

void VulkanRendererContext::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice,surface,&caps);
    VkExtent2D logical=(caps.currentExtent.width!=0xFFFFFFFF)?caps.currentExtent:VkExtent2D{(uint32_t)surfaceWidth,(uint32_t)surfaceHeight};
    // A window of no size (being resized, minimised) cannot have a swapchain (a 0 extent is invalid usage): a transient state.
    if (logical.width==0 || logical.height==0) throw SwapchainZeroExtent();
    uint32_t fmtN=0; vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(fmtN); vk_.GetPhysicalDeviceSurfaceFormatsKHR(physicalDevice,surface,&fmtN,fmts.data());
    swapchainFmt = VK_FORMAT_R8G8B8A8_UNORM;
    uint32_t imgCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imgCount > caps.maxImageCount) imgCount = caps.maxImageCount;

    uint32_t pmCount=0;
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,nullptr);
    availablePresentModes.resize(pmCount);
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice,surface,&pmCount,availablePresentModes.data());
    VkPresentModeKHR presentMode=VK_PRESENT_MODE_FIFO_KHR;
    for (auto pm:availablePresentModes) if(pm==requestedPresentMode){presentMode=pm;break;}
    if(verboseLog){
        std::string pmList;
        for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
        RLOG("createSwapchain: %dx%d fmt=%d supportedPresentModes=[%s] chosen=%d req=%d",
            swapchainExt.width,swapchainExt.height,(int)swapchainFmt,pmList.c_str(),(int)presentMode,(int)requestedPresentMode);
    }

    // Pre-rotation: render in the panel's own orientation, so the compositor does not have to
    // rotate every frame (with IDENTITY on a rotated display every present was SUBOPTIMAL).
    // The quad vertices are rotated in the vertex shader; logical sizes stay landscape.
    uint32_t rotation=0;
    VkSurfaceTransformFlagBitsKHR pre=choosePreTransform(caps, rotation);
    swapchainPreTransform=pre;
    swapchainRotation=rotation;
    swapchainLogicalExt=logical;
    swapchainExt=(rotation&1)?VkExtent2D{logical.height,logical.width}:logical;

    VkCompositeAlphaFlagBitsKHR compositeAlpha=
        (caps.supportedCompositeAlpha&VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)?
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR:VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;

    VkSwapchainKHR oldSwapchain=swapchain;
    VkSwapchainCreateInfoKHR ci{}; ci.sType=VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    ci.surface=surface; ci.minImageCount=imgCount; ci.imageFormat=swapchainFmt;
    ci.imageColorSpace=VK_COLOR_SPACE_SRGB_NONLINEAR_KHR; ci.imageExtent=swapchainExt;
    ci.imageArrayLayers=1;
    ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode=VK_SHARING_MODE_EXCLUSIVE; ci.preTransform=pre;
    ci.compositeAlpha=compositeAlpha; ci.presentMode=presentMode; ci.clipped=VK_TRUE;
    ci.oldSwapchain=oldSwapchain;
    if (vk_.CreateSwapchainKHR(device,&ci,nullptr,&swapchain)!=VK_SUCCESS) throw std::runtime_error("swapchain");
    activePresentMode.store(presentMode, std::memory_order_relaxed);
    // Timings of the old swapchain are gone with it: no gap to count, a new pacing chain and a fresh refresh query.
    lastTimingId=nextPresentId-1; lastShownGameNs=0; refreshQueriedNs=0;
    latencyCount=0; latencyFloorNs=0; highWindows=0; drainHoldWindows=0; drainOne=false; latencyWindowDirty=false; latencyWindowAppLate=false; drainReachedNs=0; lastShownContentNs=0;
    refillWatchWindows=0; refilled=false; cleanWindows=kSettleWindows; drainedContentGen=0;
    phaseSumCos=phaseSumSin=0; phaseFrames=0;
    RLOG("swapchain created: %dx%d format=%d presentMode=%d compositeAlpha=%d imgCount=%u preTransform=%d rotation=%u",
        swapchainExt.width,swapchainExt.height,(int)swapchainFmt,(int)presentMode,(int)compositeAlpha,imgCount,(int)pre,rotation);
    if (oldSwapchain!=VK_NULL_HANDLE) vk_.DestroySwapchainKHR(device,oldSwapchain,nullptr);
    vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,nullptr);
    swapchainImages.resize(imgCount); vk_.GetSwapchainImagesKHR(device,swapchain,&imgCount,swapchainImages.data());
    swapchainViews.resize(imgCount);
    for (size_t i=0;i<imgCount;i++) {
        VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image=swapchainImages[i]; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=swapchainFmt;
        vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        VkComponentMapping mapping{};
        mapping.r = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.g = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.b = VK_COMPONENT_SWIZZLE_IDENTITY;
        mapping.a = VK_COMPONENT_SWIZZLE_IDENTITY;
        vi.components = mapping;
        if (vk_.CreateImageView(device,&vi,nullptr,&swapchainViews[i])!=VK_SUCCESS) throw std::runtime_error("imgview");
    }

    // QueuePresent waits are indexed by acquired image, not frame fence.
    {
        for (auto s : swapchainRenderFinished) {
            if (s != VK_NULL_HANDLE) vk_.DestroySemaphore(device, s, nullptr);
        }
        swapchainRenderFinished.clear();
        swapchainRenderFinished.resize(imgCount, VK_NULL_HANDLE);
        VkSemaphoreCreateInfo sci{}; sci.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        for (size_t i = 0; i < imgCount; i++) {
            if (vk_.CreateSemaphore(device, &sci, nullptr, &swapchainRenderFinished[i]) != VK_SUCCESS)
                throw std::runtime_error("swapchain sem");
        }
    }
}

void VulkanRendererContext::createRenderPass() {
    VkAttachmentDescription att{}; att.format=swapchainFmt; att.samples=VK_SAMPLE_COUNT_1_BIT;
    att.loadOp=VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp=VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp=VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp=VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout=VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{0,VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{}; sub.pipelineBindPoint=VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount=1; sub.pColorAttachments=&ref;
    VkSubpassDependency dep{}; dep.srcSubpass=VK_SUBPASS_EXTERNAL; dep.dstSubpass=0;
    dep.srcStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT; dep.srcAccessMask=0;
    dep.dstStageMask=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask=VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    ci.attachmentCount=1; ci.pAttachments=&att; ci.subpassCount=1; ci.pSubpasses=&sub;
    ci.dependencyCount=1; ci.pDependencies=&dep;
    if (vk_.CreateRenderPass(device,&ci,nullptr,&renderPass)!=VK_SUCCESS) throw std::runtime_error("renderpass");
}

void VulkanRendererContext::createDSLayout() {
    VkDescriptorSetLayoutBinding b{}; b.binding=0; b.descriptorCount=1;
    b.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; b.stageFlags=VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    ci.bindingCount=1; ci.pBindings=&b;
    if (vk_.CreateDescriptorSetLayout(device,&ci,nullptr,&dsLayout)!=VK_SUCCESS) throw std::runtime_error("dslayout");
}
 

VkShaderModule VulkanRendererContext::makeShader(const uint32_t* code, size_t sz) {
    VkShaderModuleCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize=sz; ci.pCode=code; VkShaderModule m;
    if (vk_.CreateShaderModule(device,&ci,nullptr,&m)!=VK_SUCCESS) throw std::runtime_error("shader");
    return m;
}

void VulkanRendererContext::createPipeline(bool blend, VkPipeline& out) {
    if (pipeLayout==VK_NULL_HANDLE) {
        VkPushConstantRange pc{}; pc.stageFlags=VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT;
        pc.size=sizeof(WindowPushConstants);
        VkPipelineLayoutCreateInfo li{}; li.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        li.setLayoutCount=1; li.pSetLayouts=&dsLayout; li.pushConstantRangeCount=1; li.pPushConstantRanges=&pc;
        if (vk_.CreatePipelineLayout(device,&li,nullptr,&pipeLayout)!=VK_SUCCESS) throw std::runtime_error("pipelayout");
    }
    auto vert=makeShader(window_vert_code,sizeof(window_vert_code));
    auto frag=makeShader(window_frag_code,sizeof(window_frag_code));
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[0].stage=VK_SHADER_STAGE_VERTEX_BIT; stages[0].module=vert; stages[0].pName="main";
    stages[1].sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; stages[1].stage=VK_SHADER_STAGE_FRAGMENT_BIT; stages[1].module=frag; stages[1].pName="main";
    VkPipelineVertexInputStateCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    VkPipelineInputAssemblyStateCreateInfo ia{}; ia.sType=VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO; ia.topology=VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkDynamicState dyn[]={VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{}; ds.sType=VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO; ds.dynamicStateCount=2; ds.pDynamicStates=dyn;
    VkPipelineViewportStateCreateInfo vp{}; vp.sType=VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO; vp.viewportCount=1; vp.scissorCount=1;
    VkPipelineRasterizationStateCreateInfo rast{}; rast.sType=VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO; rast.polygonMode=VK_POLYGON_MODE_FILL; rast.lineWidth=1.f; rast.cullMode=VK_CULL_MODE_NONE; rast.frontFace=VK_FRONT_FACE_COUNTER_CLOCKWISE;
    VkPipelineMultisampleStateCreateInfo ms{}; ms.sType=VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO; ms.rasterizationSamples=VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask=0xF; ba.blendEnable=blend?VK_TRUE:VK_FALSE;
    if (blend){ba.srcColorBlendFactor=VK_BLEND_FACTOR_SRC_ALPHA;ba.dstColorBlendFactor=VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;ba.colorBlendOp=VK_BLEND_OP_ADD;ba.srcAlphaBlendFactor=VK_BLEND_FACTOR_ONE;ba.dstAlphaBlendFactor=VK_BLEND_FACTOR_ZERO;ba.alphaBlendOp=VK_BLEND_OP_ADD;}
    VkPipelineColorBlendStateCreateInfo cb{}; cb.sType=VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO; cb.attachmentCount=1; cb.pAttachments=&ba;
    VkGraphicsPipelineCreateInfo pi{}; pi.sType=VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pi.stageCount=2; pi.pStages=stages; pi.pVertexInputState=&vi; pi.pInputAssemblyState=&ia;
    pi.pViewportState=&vp; pi.pRasterizationState=&rast; pi.pMultisampleState=&ms;
    pi.pColorBlendState=&cb; pi.pDynamicState=&ds; pi.layout=pipeLayout; pi.renderPass=renderPass; pi.subpass=0;
    if (vk_.CreateGraphicsPipelines(device,VK_NULL_HANDLE,1,&pi,nullptr,&out)!=VK_SUCCESS) throw std::runtime_error("pipeline");
    vk_.DestroyShaderModule(device,frag,nullptr); vk_.DestroyShaderModule(device,vert,nullptr);
}


void VulkanRendererContext::createFramebuffers() {
    swapchainFBs.resize(swapchainViews.size());
    for (size_t i=0;i<swapchainViews.size();i++) {
        VkImageView att[]={swapchainViews[i]};
        VkFramebufferCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass=renderPass; fi.attachmentCount=1; fi.pAttachments=att;
        fi.width=swapchainExt.width; fi.height=swapchainExt.height; fi.layers=1;
        if (vk_.CreateFramebuffer(device,&fi,nullptr,&swapchainFBs[i])!=VK_SUCCESS) throw std::runtime_error("fb");
    }
}

void VulkanRendererContext::createCmdPool() {
    VkCommandPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    ci.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; ci.queueFamilyIndex=graphicsQueueFamilyIndex;
    if (vk_.CreateCommandPool(device,&ci,nullptr,&cmdPool)!=VK_SUCCESS) throw std::runtime_error("cmdpool");
}

void VulkanRendererContext::createSampler() {
    sampler = makeSampler(filterMode);
}

VkSampler VulkanRendererContext::makeSampler(int filterMode) {
    bool useCubic = (filterMode == 2) && cubicSupported;
    VkFilter filter = (filterMode == 1) ? VK_FILTER_NEAREST
                    : (useCubic)         ? VK_FILTER_CUBIC_EXT
                    :                      VK_FILTER_LINEAR;
    RLOG("createSampler: filter=%s (filterMode=%d, cubicSupported=%d)",
        filterMode==2?(cubicSupported?"CUBIC":"LINEAR_FALLBACK"):filterMode==1?"NEAREST":"LINEAR",
        filterMode, (int)cubicSupported);
    VkSamplerCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    ci.magFilter=filter; ci.minFilter=filter;
    ci.addressModeU=ci.addressModeV=ci.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.mipmapMode=VK_SAMPLER_MIPMAP_MODE_NEAREST;
    ci.minLod=0.f; ci.maxLod=0.f;
    VkSampler made = VK_NULL_HANDLE;
    if (vk_.CreateSampler(device,&ci,nullptr,&made)!=VK_SUCCESS) throw std::runtime_error("sampler");
    return made;
}

void VulkanRendererContext::createWinTexPool() {

    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 129};
    VkDescriptorPoolCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.flags=VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    ci.poolSizeCount=1; ci.pPoolSizes=&ps; ci.maxSets=129;
    if (vk_.CreateDescriptorPool(device,&ci,nullptr,&winTexPool)!=VK_SUCCESS) throw std::runtime_error("wintexpool");
}


void VulkanRendererContext::createCursorDS() {
    VkDescriptorSetAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool=winTexPool; ai.descriptorSetCount=1; ai.pSetLayouts=&dsLayout;
    vk_.AllocateDescriptorSets(device,&ai,&cursorDS);
}

void VulkanRendererContext::createCmdBufs() {
    cmdBufs.resize(MAX_FRAMES_IN_FLIGHT);
    VkCommandBufferAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool=cmdPool; ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount=MAX_FRAMES_IN_FLIGHT;
    if (vk_.AllocateCommandBuffers(device,&ai,cmdBufs.data())!=VK_SUCCESS) throw std::runtime_error("cmdbuf");
}

void VulkanRendererContext::createSyncObjects() {
    imgAvailSems.resize(MAX_FRAMES_IN_FLIGHT); inFlightFences.resize(MAX_FRAMES_IN_FLIGHT);
    VkSemaphoreCreateInfo si{}; si.sType=VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkFenceCreateInfo fi{}; fi.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;
    for (uint32_t i=0;i<MAX_FRAMES_IN_FLIGHT;i++) {
        if (vk_.CreateSemaphore(device,&si,nullptr,&imgAvailSems[i])!=VK_SUCCESS||
            vk_.CreateFence(device,&fi,nullptr,&inFlightFences[i])!=VK_SUCCESS) throw std::runtime_error("sync");
    }
}

void VulkanRendererContext::cleanupSwapchain() {
    for (auto s : swapchainRenderFinished) {
        if (s != VK_NULL_HANDLE) vk_.DestroySemaphore(device, s, nullptr);
    }
    swapchainRenderFinished.clear();
    for (auto fb:swapchainFBs) vk_.DestroyFramebuffer(device,fb,nullptr); swapchainFBs.clear();
    for (auto iv:swapchainViews) vk_.DestroyImageView(device,iv,nullptr); swapchainViews.clear();
    if (!cmdBufs.empty()){vk_.FreeCommandBuffers(device,cmdPool,(uint32_t)cmdBufs.size(),cmdBufs.data());cmdBufs.clear();}
    if (swapchain!=VK_NULL_HANDLE) { vk_.DestroySwapchainKHR(device,swapchain,nullptr); swapchain=VK_NULL_HANDLE; }
}

uint32_t VulkanRendererContext::findMemType(uint32_t filter, VkMemoryPropertyFlags props) {
    for (uint32_t i=0;i<memProperties.memoryTypeCount;i++)
        if ((filter&(1u<<i))&&(memProperties.memoryTypes[i].propertyFlags&props)==props) return i;
    throw std::runtime_error("memtype");
}

void VulkanRendererContext::createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
    VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem)
{
    // Publish only a fully bound buffer. Failures also leave caller handles empty.
    VkBuffer candidateBuffer = VK_NULL_HANDLE;
    VkDeviceMemory candidateMemory = VK_NULL_HANDLE;
    buf = VK_NULL_HANDLE; mem = VK_NULL_HANDLE;
    try {
        VkBufferCreateInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.size=sz; bi.usage=usage; bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        if (vk_.CreateBuffer(device,&bi,nullptr,&candidateBuffer)!=VK_SUCCESS)
            throw std::runtime_error("buffer");
        VkMemoryRequirements req{};
        vk_.GetBufferMemoryRequirements(device,candidateBuffer,&req);
        VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,props);
        if (vk_.AllocateMemory(device,&ai,nullptr,&candidateMemory)!=VK_SUCCESS)
            throw std::runtime_error("bufmem");
        if (vk_.BindBufferMemory(device,candidateBuffer,candidateMemory,0)!=VK_SUCCESS)
            throw std::runtime_error("buffer bind");
        buf=candidateBuffer; mem=candidateMemory;
    } catch (...) {
        if (candidateBuffer!=VK_NULL_HANDLE) vk_.DestroyBuffer(device,candidateBuffer,nullptr);
        if (candidateMemory!=VK_NULL_HANDLE) vk_.FreeMemory(device,candidateMemory,nullptr);
        throw;
    }
}

bool VulkanRendererContext::createWinTexResources(WinTex& wt, int w, int h) {
    // wt is a private candidate, never a resource referenced by an in-flight draw.
    auto fail = [&]() {
        if (wt.ds!=VK_NULL_HANDLE) vk_.FreeDescriptorSets(device,winTexPool,1,&wt.ds);
        if (wt.view!=VK_NULL_HANDLE) vk_.DestroyImageView(device,wt.view,nullptr);
        if (wt.img!=VK_NULL_HANDLE) vk_.DestroyImage(device,wt.img,nullptr);
        if (wt.mem!=VK_NULL_HANDLE) vk_.FreeMemory(device,wt.mem,nullptr);
        wt={};
        return false;
    };
    try {
        VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ii.imageType=VK_IMAGE_TYPE_2D; ii.extent={(uint32_t)w,(uint32_t)h,1};
        ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_B8G8R8A8_UNORM;
        ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
        ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT;
        ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS) return fail();
        VkMemoryRequirements req{}; vk_.GetImageMemoryRequirements(device,wt.img,&req);
        VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize=req.size;
        ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vk_.AllocateMemory(device,&ai,nullptr,&wt.mem)!=VK_SUCCESS ||
            vk_.BindImageMemory(device,wt.img,wt.mem,0)!=VK_SUCCESS) return fail();
        VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image=wt.img; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=ii.format;
        vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        vi.components={swapRB?VK_COMPONENT_SWIZZLE_B:VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,swapRB?VK_COMPONENT_SWIZZLE_R:VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY};
        if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS) return fail();
        VkDescriptorSetAllocateInfo aiDS{}; aiDS.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        aiDS.descriptorPool=winTexPool; aiDS.descriptorSetCount=1; aiDS.pSetLayouts=&dsLayout;
        if (vk_.AllocateDescriptorSets(device,&aiDS,&wt.ds)!=VK_SUCCESS) return fail();
        VkDescriptorImageInfo info{}; info.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        info.imageView=wt.view; info.sampler=sampler;
        VkWriteDescriptorSet write{}; write.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet=wt.ds; write.dstBinding=0; write.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.descriptorCount=1; write.pImageInfo=&info;
        vk_.UpdateDescriptorSets(device,1,&write,0,nullptr);
        wt.w=w; wt.h=h; wt.needsTransition=true;
        return true;
    } catch (...) { return fail(); }
}

bool VulkanRendererContext::importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb) {
    if (!vk_.GetAndroidHardwareBufferPropertiesANDROID || !ahb) return false;
    VkAndroidHardwareBufferFormatPropertiesANDROID fmtP{};
    fmtP.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID props{};
    props.sType=VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    props.pNext=&fmtP;
    if (vk_.GetAndroidHardwareBufferPropertiesANDROID(device,ahb,&props)!=VK_SUCCESS ||
        fmtP.format==VK_FORMAT_UNDEFINED) return false;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb,&desc);
    if (!desc.width || !desc.height) return false;

    // These resources have never been submitted. A rejected import must free
    // them now, even if no later frame runs to drain the retirement queue.
    auto fail = [&]() {
        if (wt.ds!=VK_NULL_HANDLE) vk_.FreeDescriptorSets(device,winTexPool,1,&wt.ds);
        if (wt.view!=VK_NULL_HANDLE) vk_.DestroyImageView(device,wt.view,nullptr);
        if (wt.img!=VK_NULL_HANDLE) vk_.DestroyImage(device,wt.img,nullptr);
        if (wt.mem!=VK_NULL_HANDLE) vk_.FreeMemory(device,wt.mem,nullptr);
        wt={};
        return false;
    };
    try {
        VkExternalMemoryImageCreateInfo emi{};
        emi.sType=VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
        emi.handleTypes=VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
        VkImageCreateInfo ii{};
        ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ii.pNext=&emi; ii.imageType=VK_IMAGE_TYPE_2D;
        // Typed AHBs use the format reported by the driver. VkFormat enum values
        // are not VkExternalFormatANDROID identifiers. R/B swapping is a view swizzle.
        ii.format=fmtP.format; ii.extent={desc.width,desc.height,1};
        ii.mipLevels=1; ii.arrayLayers=1; ii.samples=VK_SAMPLE_COUNT_1_BIT;
        ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.usage=VK_IMAGE_USAGE_SAMPLED_BIT;
        ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
        if (vk_.CreateImage(device,&ii,nullptr,&wt.img)!=VK_SUCCESS) return fail();
        VkImportAndroidHardwareBufferInfoANDROID imp{};
        imp.sType=VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID; imp.buffer=ahb;
        VkMemoryDedicatedAllocateInfo ded{};
        ded.sType=VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO; ded.pNext=&imp; ded.image=wt.img;
        VkMemoryAllocateInfo mai{};
        mai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.pNext=&ded; mai.allocationSize=props.allocationSize;
        mai.memoryTypeIndex=findMemType(props.memoryTypeBits,0);
        if (vk_.AllocateMemory(device,&mai,nullptr,&wt.mem)!=VK_SUCCESS ||
            vk_.BindImageMemory(device,wt.img,wt.mem,0)!=VK_SUCCESS) return fail();
        VkImageViewCreateInfo vi{};
        vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image=wt.img; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=fmtP.format;
        vi.components={swapRB?VK_COMPONENT_SWIZZLE_B:VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,swapRB?VK_COMPONENT_SWIZZLE_R:VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY};
        vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        if (vk_.CreateImageView(device,&vi,nullptr,&wt.view)!=VK_SUCCESS) return fail();
        VkDescriptorSetAllocateInfo dsai{};
        dsai.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        dsai.descriptorPool=winTexPool; dsai.descriptorSetCount=1; dsai.pSetLayouts=&dsLayout;
        if (vk_.AllocateDescriptorSets(device,&dsai,&wt.ds)!=VK_SUCCESS) return fail();
        VkDescriptorImageInfo dii{};
        dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=wt.view; dii.sampler=sampler;
        VkWriteDescriptorSet wr{};
        wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=wt.ds; wr.dstBinding=0;
        wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
        vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
        wt.needsTransition=true; wt.isAHB=true; wt.w=(int)desc.width; wt.h=(int)desc.height;
        return true;
    } catch (...) {
        return fail();
    }
}

void VulkanRendererContext::destroyWinTex(WinTex& wt) {
    if (wt.isAHB) {


        wt = {};
        return;
    }
    if (wt.img!=VK_NULL_HANDLE || wt.stg!=VK_NULL_HANDLE) {
        
        WinTex deferred = wt;
        deferred.isAHB = false;
        deleteQueue.push_back(deferred);
    }
    wt={};
}

void VulkanRendererContext::freeRetiredTextures(std::vector<WinTex>& textures) {
    for (auto& wt : textures) {
        if (wt.idleToken != 0) {
            // The frames that may still read that Present buffer are done: the client may have it back. If remembering fails the
            // Java side's own time limit releases it, so a client is never left waiting.
            try { idleReleased.push_back(wt.idleToken); } catch (...) {}
        }
        for (auto& upload : wt.uploads) {
            if (upload.buffer!=VK_NULL_HANDLE) vk_.DestroyBuffer(device,upload.buffer,nullptr);
            if (upload.memory!=VK_NULL_HANDLE) vk_.FreeMemory(device,upload.memory,nullptr);
        }
        if (wt.ds != VK_NULL_HANDLE) vk_.FreeDescriptorSets(device, winTexPool, 1, &wt.ds);
        if (wt.view != VK_NULL_HANDLE) vk_.DestroyImageView(device, wt.view, nullptr);
        if (wt.img != VK_NULL_HANDLE) vk_.DestroyImage(device, wt.img, nullptr);
        if (wt.mem != VK_NULL_HANDLE) vk_.FreeMemory(device, wt.mem, nullptr);
        if (wt.stg != VK_NULL_HANDLE) {
            vk_.DestroyBuffer(device, wt.stg, nullptr);
            vk_.FreeMemory(device, wt.stgMem, nullptr);
        }
    }
    textures.clear();
}

void VulkanRendererContext::commitTextureRetirements() {
    // Called only after QueueSubmit succeeds. An aborted recording keeps its
    // pending resources alive until a later successful submission or shutdown.
    pendingRetirements.swap(submittedRetirements[currentFrame]);
}


bool VulkanRendererContext::ensureCursorTex(short w, short h) {
    if (cursorImg!=VK_NULL_HANDLE && cursorTexW==w && cursorTexH==h) return true;
    // The frame lock excludes CPU recording, but submitted draws may still
    // sample the old image/descriptor. Retire them only on a size change.
    if (cursorImg != VK_NULL_HANDLE) {
        for (auto fence : inFlightFences) if (!waitForFrameFence(fence)) return false;
    }
    cleanupCursorTex();
    if (cursorDS == VK_NULL_HANDLE) return false;
    try {
        VkImageCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO; ii.imageType=VK_IMAGE_TYPE_2D;
        ii.extent={(uint32_t)w,(uint32_t)h,1}; ii.mipLevels=1; ii.arrayLayers=1; ii.format=VK_FORMAT_B8G8R8A8_UNORM;
        ii.tiling=VK_IMAGE_TILING_OPTIMAL; ii.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;
        ii.usage=VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_SAMPLED_BIT; ii.samples=VK_SAMPLE_COUNT_1_BIT; ii.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
        if (vk_.CreateImage(device,&ii,nullptr,&cursorImg)!=VK_SUCCESS) throw std::runtime_error("cursor image");
        VkMemoryRequirements req; vk_.GetImageMemoryRequirements(device,cursorImg,&req);
        VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO; ai.allocationSize=req.size; ai.memoryTypeIndex=findMemType(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (vk_.AllocateMemory(device,&ai,nullptr,&cursorMem)!=VK_SUCCESS ||
            vk_.BindImageMemory(device,cursorImg,cursorMem,0)!=VK_SUCCESS) throw std::runtime_error("cursor memory");
        VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO; vi.image=cursorImg; vi.viewType=VK_IMAGE_VIEW_TYPE_2D; vi.format=VK_FORMAT_B8G8R8A8_UNORM; vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        if (vk_.CreateImageView(device,&vi,nullptr,&cursorView)!=VK_SUCCESS) throw std::runtime_error("cursor view");
        VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL; dii.imageView=cursorView; dii.sampler=sampler;
        VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr.dstSet=cursorDS; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.descriptorCount=1; wr.pImageInfo=&dii;
        vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    } catch (...) {
        cleanupCursorTex();
        return false;
    }
    cursorTexW=w; cursorTexH=h;
    cursorNeedsTransition=true;
    return true;
}

void VulkanRendererContext::cleanupCursorTex() {
    if (cursorView!=VK_NULL_HANDLE){vk_.DestroyImageView(device,cursorView,nullptr);cursorView=VK_NULL_HANDLE;}
    if (cursorImg!=VK_NULL_HANDLE){vk_.DestroyImage(device,cursorImg,nullptr);cursorImg=VK_NULL_HANDLE;}
    if (cursorMem!=VK_NULL_HANDLE){vk_.FreeMemory(device,cursorMem,nullptr);cursorMem=VK_NULL_HANDLE;}
    for (auto& staging : cursorStaging) {
        if (staging.buffer != VK_NULL_HANDLE) vk_.DestroyBuffer(device, staging.buffer, nullptr);
        if (staging.memory != VK_NULL_HANDLE) vk_.FreeMemory(device, staging.memory, nullptr);
        staging = {};
    }
    cursorTexW=0; cursorTexH=0; cursorW=0; cursorH=0;
    cursorNeedsTransition=false;
}

void VulkanRendererContext::ensureCursorStaging(VkDeviceSize sz) {
    // currentFrame's fence has completed before this slot is written again.
    auto& staging = cursorStaging[currentFrame];
    if (staging.capacity >= sz) return;
    if (staging.buffer != VK_NULL_HANDLE) vk_.DestroyBuffer(device, staging.buffer, nullptr);
    if (staging.memory != VK_NULL_HANDLE) vk_.FreeMemory(device, staging.memory, nullptr);
    staging = {};
    try {
        createBuffer(sz,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,staging.buffer,staging.memory);
        if (vk_.MapMemory(device,staging.memory,0,sz,0,&staging.mapped)!=VK_SUCCESS)
            throw std::runtime_error("cursor staging map");
        staging.capacity=sz;
    } catch (...) {
        if (staging.buffer != VK_NULL_HANDLE) vk_.DestroyBuffer(device, staging.buffer, nullptr);
        if (staging.memory != VK_NULL_HANDLE) vk_.FreeMemory(device, staging.memory, nullptr);
        staging = {};
        throw;
    }
}

void VulkanRendererContext::commitCursorUpload() {
    // Staging a cursor image (generation + dirty flag) happens under renderMutex, so the check and the clear below must too:
    // without it an image staged between them would have its dirty flag cleared and never be uploaded.
    std::lock_guard<std::mutex> lk(renderMutex);
    // A newer image may have been staged after this frame took its snapshot: it still has to be uploaded.
    if (cursorStageGen.load() == cursorSnapshotGen) isCursorImageDirty.store(false);
    cursorNeedsTransition=false;
}

void VulkanRendererContext::recordCmdBuf(VkCommandBuffer cb, VkFramebuffer targetFb, VkRenderPass targetRp,
    const std::vector<DrawEntry>& draws,
    std::vector<VkImageMemoryBarrier>& ahbTransitions,
    std::vector<VkImageMemoryBarrier>& preUpload,
    std::vector<VkImageMemoryBarrier>& postUpload,
    VkBuffer cursorUpload, bool hasCursorUpload,
    float ox, float oy, float sx, float sy, float cw, float ch,
    short ptrX, short ptrY, short curHotX, short curHotY,
    short curW, short curH, short curUpW, short curUpH, short curTexW, short curTexH, bool curVis)
{
    VkCommandBufferBeginInfo bi{}; bi.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vk_.BeginCommandBuffer(cb,&bi)!=VK_SUCCESS) throw std::runtime_error("begin cb");

    ahbTransitions.clear(); preUpload.clear(); postUpload.clear();

    for (auto& d : draws) {
        if (d.img==VK_NULL_HANDLE) continue;
        if (d.isAHB && d.needsTransition) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=0; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            if (std::none_of(ahbTransitions.begin(), ahbTransitions.end(),
                    [&d](const auto& barrier) { return barrier.image == d.img; })) {
                ahbTransitions.push_back(b);
            }
        } else if (!d.isAHB && (d.needsTransition || d.upload!=VK_NULL_HANDLE)) {
            VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.oldLayout=d.needsTransition ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
            b.image=d.img; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
            b.srcAccessMask=d.needsTransition ? 0 : VK_ACCESS_SHADER_READ_BIT;
            b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
            preUpload.push_back(b);
            b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
            postUpload.push_back(b);
        }
    }

    if (!ahbTransitions.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)ahbTransitions.size(), ahbTransitions.data());
    if (!preUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)preUpload.size(), preUpload.data());

    for (auto& d : draws) {
        if (d.isAHB || d.upload==VK_NULL_HANDLE || d.img==VK_NULL_HANDLE) continue;
        VkBufferImageCopy r{}; r.bufferOffset=0; r.bufferRowLength=0; r.bufferImageHeight=0;
        r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)d.w,(uint32_t)d.h,1};
        vk_.CmdCopyBufferToImage(cb, d.upload, d.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
    }

    bool cursorDrawn = curVis && cursorImg!=VK_NULL_HANDLE && cursorDS!=VK_NULL_HANDLE;
    bool hasCursorCopy = hasCursorUpload && cursorImg!=VK_NULL_HANDLE && cursorUpload!=VK_NULL_HANDLE;
    if (hasCursorCopy) {
        VkImageMemoryBarrier b{}; b.sType=VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout=cursorNeedsTransition ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.newLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        b.image=cursorImg; b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        b.srcAccessMask=cursorNeedsTransition ? 0 : VK_ACCESS_SHADER_READ_BIT;
        b.dstAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;
        vk_.CmdPipelineBarrier(cb, cursorNeedsTransition ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &b);
        VkBufferImageCopy r{}; r.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
        r.imageExtent={(uint32_t)curUpW,(uint32_t)curUpH,1};
        vk_.CmdCopyBufferToImage(cb, cursorUpload, cursorImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
        b.oldLayout=VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        postUpload.push_back(b);
    }

    if (!postUpload.empty())
        vk_.CmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t)postUpload.size(), postUpload.data());

    VkExtent2D tgtExt = swapchainExt;
    // Effects work in the logical (unrotated) size; the swapchain may be pre-rotated.
    VkExtent2D outExt = swapchainLogicalExt;
    const int rotation = (int)swapchainRotation;
    VkRenderPassBeginInfo rpi{}; rpi.sType=VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = targetRp;
    rpi.framebuffer = targetFb;
    rpi.renderArea={{0,0},tgtExt};
    VkClearValue clr={{{0.f,0.f,0.f,1.f}}}; rpi.clearValueCount=1; rpi.pClearValues=&clr;

    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0,0,(float)tgtExt.width,(float)tgtExt.height,0,1};
    vk_.CmdSetViewport(cb, 0, 1, &vp);
    VkRect2D sc{{0,0},tgtExt}; vk_.CmdSetScissor(cb, 0, 1, &sc);

    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    for (auto& d : draws) {
        if (d.ds==VK_NULL_HANDLE) continue;
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout, 0, 1, &d.ds, 0, nullptr);
        WindowPushConstants pc{};
        pc.ndcX0=(ox+(float)d.x*sx)/cw*2.f-1.f;
        pc.ndcY0=(oy+(float)d.y*sy)/ch*2.f-1.f;
        pc.ndcX1=(ox+(float)(d.x+d.w)*sx)/cw*2.f-1.f;
        pc.ndcY1=(oy+(float)(d.y+d.h)*sy)/ch*2.f-1.f;
        pc.useTexAlpha = 0;
        pc.effectId = activeEffectId;
        pc.sharpness = activeSharpness;
        // The effects see the whole texture as their input; a cut-out quad only shows part of it (same numbers when it is all).
        const int texW = d.tw > 0 ? d.tw : d.w, texH = d.th > 0 ? d.th : d.h;
        pc.resW = (float)std::max(1, texW);
        pc.resH = (float)std::max(1, texH);
        pc.uvX0 = d.u0; pc.uvY0 = d.v0; pc.uvX1 = d.u1; pc.uvY1 = d.v1;
        pc.effectMask = activeEffectMask;
        pc.brightness = activeBrightness;
        pc.contrast = activeContrast;
        pc.gamma = activeGamma;
        pc.outW = std::max(1.0f, (float)d.w * sx / cw * (float)outExt.width * ((float)std::max(1, texW) / (float)std::max(1, d.w)));
        pc.outH = std::max(1.0f, (float)d.h * sy / ch * (float)outExt.height * ((float)std::max(1, texH) / (float)std::max(1, d.h)));
        pc.rotation = rotation;
        vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }

    if (cursorDrawn) {
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout, 0, 1, &cursorDS, 0, nullptr);
        // The image starts at pointer minus hotspot, which is negative next to the left/top edge: the scissor (the whole
        // framebuffer) cuts what lies outside. Clamping the origin instead slid the whole image inwards, so the hotspot was no
        // longer under the pointer there.
        float cx=(float)((int)ptrX-curHotX), cy=(float)((int)ptrY-curHotY);
        WindowPushConstants cpc{};
        cpc.ndcX0=(ox+cx*sx)/cw*2.f-1.f; cpc.ndcY0=(oy+cy*sy)/ch*2.f-1.f;
        cpc.ndcX1=(ox+(cx+curW)*sx)/cw*2.f-1.f; cpc.ndcY1=(oy+(cy+curH)*sy)/ch*2.f-1.f;
        cpc.uvX0 = 0.f; cpc.uvY0 = 0.f;
        cpc.uvX1 = (float)curW / (float)std::max(1, (int)curTexW); cpc.uvY1 = (float)curH / (float)std::max(1, (int)curTexH);
        cpc.useTexAlpha = 1;
        cpc.effectId = 0;
        cpc.sharpness = 0.f;
        cpc.resW = (float)std::max(1, (int)curW);
        cpc.resH = (float)std::max(1, (int)curH);
        cpc.effectMask = 0;
        cpc.brightness = 0.0f;
        cpc.contrast = 0.0f;
        cpc.gamma = 1.0f;
        cpc.outW = (float)std::max(1, (int)curW);
        cpc.outH = (float)std::max(1, (int)curH);
        cpc.rotation = rotation;
        vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(cpc), &cpc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }
    vk_.CmdEndRenderPass(cb);
}


static int64_t monotonicNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

static int64_t threadCpuNs() {
    timespec ts{};
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) != 0) return 0;
    return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void VulkanRendererContext::renderLoop() {
    bool priorityApplied = false;
    int originalPriority = 0;
    void* androidLibrary = nullptr;
    PerformanceHintCycle::Api hintApi{};
    std::unique_ptr<PerformanceHintCycle> hint;
    // Hand-back ("presentidle", on by default): a finished frame that carries tokens can complete while nothing asks for another
    // frame (a client with all its buffers still held cannot present). Only then the loop wakes by itself, at the time
    // IdlePollPacer aims at (the measured finish of such frames), looks at the fences and gives up after ~3.5 s without a real
    // wake-up; with the switch off no token exists and the thread sleeps until it is woken, as always.
    IdlePollPacer idlePacer;
    bool idleStretch = false;
    while (isRunning) {
        auto wakeReason = [this] {
            return !isRunning || (!surfaceDetached.load() &&
                (needsRender.load() || fbResized.load() || cursorMoved.load()));
        };
        // With the hand-back off (the default) nothing below touches renderMutex or a retirement list: the thread sleeps until woken.
        if (idleSinkInstalled.load(std::memory_order_acquire) && !idlePacer.givenUp() && hasIdleTokensInFlight()) {
            if (!idleStretch) { idlePacer.begin(); idleStretch = true; }
            if (!renderWake.waitFor(std::chrono::nanoseconds(idlePacer.nextWaitNs()), wakeReason)) {
                pollIdleTokens();
                idlePacer.polled(deliverReleasedIdle());
                continue;
            }
        } else {
            renderWake.wait(wakeReason);
        }
        idleStretch = false;
        idlePacer.woken();
        if (!isRunning) break;
        if (drainedContentGen && contentGen.load(std::memory_order_relaxed) == drainedContentGen) {
            // A skip is waiting for the next game frame: other wake-ups sleep until it comes or the hold ends.
            const int64_t now = monotonicNs();
            if (now < drainHoldUntilNs)
                renderWake.waitFor(std::chrono::nanoseconds(drainHoldUntilNs - now), [this] {
                    return !isRunning || surfaceDetached.load() || fbResized.load() ||
                        contentGen.load(std::memory_order_relaxed) != drainedContentGen;
                });
            if (!isRunning) break;
        }
        if (activePresentMode.load(std::memory_order_relaxed) == VK_PRESENT_MODE_FIFO_KHR && lastGamePresentNs && !fbResized.load() &&
            contentGen.load(std::memory_order_relaxed) == presentedContentGen) {
            // FIFO while the game presents every refresh: the panel takes exactly one frame per refresh, so every extra
            // (cursor or scene-only) present stays in the queue for good and adds a refresh of latency. Such a change waits
            // for the next game frame and goes out with it; if the game stops, after two refreshes it is presented alone.
            const int64_t refresh = refreshPeriodNs.load(std::memory_order_relaxed);
            const int64_t until = lastGamePresentNs + 2 * (refresh > 0 ? refresh : 16666667);
            const int64_t now = monotonicNs();
            if (now < until)
                renderWake.waitFor(std::chrono::nanoseconds(until - now), [this] {
                    return !isRunning || surfaceDetached.load() || fbResized.load() ||
                        contentGen.load(std::memory_order_relaxed) != presentedContentGen;
                });
            if (!isRunning) break;
        }
        if (activePresentMode.load(std::memory_order_relaxed) == VK_PRESENT_MODE_FIFO_KHR && !fbResized.load()) {
            // FIFO: presents at least 3/4 of a refresh apart (about one per refresh), game frames included. Frames that come in a burst (the game catching up
            // after a hitch, e.g. entering the world) would otherwise each queue up, and with the game at exactly the panel rate
            // the queue never empties again: two extra frames measured as 63 instead of 25 ms to the screen for the rest of the
            // session. The newest content goes out once the refresh has passed.
            const int64_t refresh = refreshPeriodNs.load(std::memory_order_relaxed);
            const int64_t spacing = (refresh > 0 ? refresh : 16666667) * 3 / 4;
            const int64_t since = monotonicNs() - lastPresentNs.load(std::memory_order_relaxed);
            if (since >= 0 && since < spacing)
                renderWake.waitFor(std::chrono::nanoseconds(spacing - since), [this] {
                    return !isRunning || surfaceDetached.load() || fbResized.load();
                });
            if (!isRunning) break;
        } else if (cursorMoved.load() && !needsRender.load() && !fbResized.load()) {
            // Only the pointer moved: at most one such frame per refresh (touch and mouse report far faster), and a scene
            // change arriving meanwhile takes the pointer along in the same frame.
            const int64_t refresh = refreshPeriodNs.load(std::memory_order_relaxed);
            const int64_t spacing = refresh > 0 ? refresh : 8333333;
            const int64_t since = monotonicNs() - lastPresentNs.load(std::memory_order_relaxed);
            if (since >= 0 && since < spacing)
                renderWake.waitFor(std::chrono::nanoseconds(spacing - since), [this] {
                    return !isRunning || surfaceDetached.load() || needsRender.load() || fbResized.load();
                });
            if (!isRunning) break;
        }

        bool boost = displayPriorityHint.load();
        if (boost != priorityApplied) {
            if (boost) originalPriority = getpriority(PRIO_PROCESS, 0);
            setpriority(PRIO_PROCESS, 0, boost ? -4 : originalPriority);
            priorityApplied = boost;
        }
        if (adpfHint.load() && !hint) {
            androidLibrary = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
            if (androidLibrary) {
                hintApi.manager = reinterpret_cast<decltype(hintApi.manager)>(dlsym(androidLibrary, "APerformanceHint_getManager"));
                hintApi.create = reinterpret_cast<decltype(hintApi.create)>(dlsym(androidLibrary, "APerformanceHint_createSession"));
                hintApi.update = reinterpret_cast<decltype(hintApi.update)>(dlsym(androidLibrary, "APerformanceHint_updateTargetWorkDuration"));
                hintApi.report = reinterpret_cast<decltype(hintApi.report)>(dlsym(androidLibrary, "APerformanceHint_reportActualWorkDuration"));
                hintApi.close = reinterpret_cast<decltype(hintApi.close)>(dlsym(androidLibrary, "APerformanceHint_closeSession"));
            }
            hint = std::make_unique<PerformanceHintCycle>(hintApi);
        }
        if (hint) hint->configure(adpfHint.load() && !scanoutActive.load(),
            gettid(), hintTargetNs.load());
        // Report this thread's CPU time, not wall time: fence, acquire and present
        // waits inside renderFrame are idle time and must not look like overload.
        const int64_t workStart = (hint && hint->active()) ? threadCpuNs() : 0;
        try { renderFrame();
            if (hint && hint->active()) hint->report(threadCpuNs() - workStart);
        }
        catch (const std::exception& error) {
            RLOG_E("renderFrame failed: %s", error.what());
            if (!fbResized.load()) isRunning = false;
        }
        catch (...) { isRunning = false; RLOG_E("renderFrame failed with an unknown exception"); }
        if (recreateBackoff) {
            // The swapchain could not be rebuilt yet: fbResized stays set (so every wake-up would retry at once); wait a little
            // first. A detach or the end of the renderer cuts the wait short.
            recreateBackoff = false;
            renderWake.waitFor(std::chrono::milliseconds(100), [this] { return !isRunning || surfaceDetached.load(); });
        }
    }
    hint.reset();
    if (androidLibrary) dlclose(androidLibrary);
}

void VulkanRendererContext::commitAhbTransitions() {
    if (std::none_of(frameDraws.begin(), frameDraws.end(),
            [](const auto& draw) { return draw.isAHB && draw.needsTransition; })) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    for (const auto& draw : frameDraws) {
        if (!draw.isAHB || !draw.needsTransition) continue;
        auto cached = ahbImportCache.find(draw.ahb);
        if (cached != ahbImportCache.end() && cached->second.img == draw.img) {
            cached->second.needsTransition = false;
        }
    }
}

bool VulkanRendererContext::waitForFrameFence(VkFence fence) {
    if (fence == VK_NULL_HANDLE) return true;
    VkResult status = vk_.WaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX);
    if (status == VK_SUCCESS) return true;
    RLOG_E("Frame fence wait failed: %d; stopping renderer", (int)status);
    isRunning = false;
    renderWake.notify();
    return false;
}

bool VulkanRendererContext::recoverAcquiredFrame(const VkSemaphore* acquired, uint32_t count) noexcept {
    // This path runs only after an aborted frame. Consume successful acquires
    // before reusing their semaphores or retiring their swapchain.
    VkResult status = VK_SUCCESS;
    if (count) {
        VkPipelineStageFlags stages[1] = {VK_PIPELINE_STAGE_ALL_COMMANDS_BIT};
        if (count > 1) count = 1;
        VkSubmitInfo drain{}; drain.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        drain.waitSemaphoreCount = count; drain.pWaitSemaphores = acquired;
        drain.pWaitDstStageMask = stages;
        status = vk_.QueueSubmit(graphicsQueue, 1, &drain, VK_NULL_HANDLE);
    }
    if (status == VK_SUCCESS) status = vk_.DeviceWaitIdle(device);
    if (status != VK_SUCCESS) {
        RLOG_E("Acquired frame recovery failed: %d; stopping renderer", (int)status);
        isRunning = false;
        renderWake.notify();
        return false;
    }
    VkFence stale = inFlightFences[currentFrame];
    for (auto& fence : imgInFlight) if (fence == stale) fence = VK_NULL_HANDLE;
    if (stale != VK_NULL_HANDLE) vk_.DestroyFence(device, stale, nullptr);
    inFlightFences[currentFrame] = VK_NULL_HANDLE;
    VkFenceCreateInfo info{}; info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (vk_.CreateFence(device, &info, nullptr, &inFlightFences[currentFrame]) != VK_SUCCESS) {
        RLOG_E("Cannot recreate frame fence; stopping renderer");
        isRunning = false;
        renderWake.notify();
        return false;
    }
    fbResized.store(true);
    markSceneDirty();
    renderWake.notify();
    return true;
}

void VulkanRendererContext::renderFrame() {
    std::shared_lock<std::shared_mutex> frameLock(frameMutex);

    needsRender.store(false,std::memory_order_relaxed);
    cursorMoved.store(false,std::memory_order_relaxed);

    if (surfaceDetached.load(std::memory_order_acquire)) return;
    // Surface retirement/recreation holds frameMutex exclusively. Check the
    // resource state under our shared lock, after excluding a detached surface.
    if (!fbResized.load() && (swapchain == VK_NULL_HANDLE || cmdBufs.empty())) {
        isRunning = false;
        return;
    }
    if (scanoutActive.load()) {
        applyScanoutBuffer();

        if (!scanoutBlackFrameDone.load()) {
            scanoutBlackFrameDone.store(true);

            std::lock_guard<std::mutex> lk(renderMutex);
            renderList.clear();
            // 1.2.3: the buffers last shown through the compositor are no longer drawn: hand their Present tokens back after this
            // black frame's fence (they used to wait out the 2 s stall limit, one swapchain image short, each time scanout began).
            for (auto& entry : windowIdleTokens) retireIdleTokens(entry.second);
            windowIdleTokens.clear();
            sceneGen.fetch_add(1, std::memory_order_acq_rel);
        } else if (!fbResized.load()) {
            // Nothing is drawn while the game is on the scanout layer. Holds retired meanwhile (or before a black frame that
            // never got presented) are handed back here once every submitted frame is done; they used to wait for the 2 s limit.
            if (idleSinkInstalled.load(std::memory_order_acquire)) {
                pollIdleTokens();
                deliverReleasedIdle();
            }
            return;
        }
        // else: a resize or present-mode change while scanout is on: the swapchain is rebuilt below (the flag used to stay set
        // and the loop spun on it at 100% CPU), then one black frame goes into the new swapchain.
    } else {
        scanoutBlackFrameDone.store(false);
    }
    if (surfaceWidth==0||surfaceHeight==0) {
        if (fbResized.load()) recreateBackoff = true;   // never spin on a flag that cannot be served yet
        return;
    }

    if (fbResized.load()) {
        for (auto& f:inFlightFences) {
            if (!waitForFrameFence(f)) return;
        }
        // Presentation waits can outlive the graphics frame fences.
        // Quiesce only when retiring swapchain resources, never per draw.
        if (vk_.DeviceWaitIdle(device) != VK_SUCCESS) {
            isRunning = false;
            RLOG_E("Cannot retire swapchain after device failure");
            return;
        }
        cleanupSwapchain();
        bool ok=false, zeroExtent=false;
        try{createSwapchain();createFramebuffers();createCmdBufs();imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
ok=true;}catch(const SwapchainZeroExtent&){zeroExtent=true;}catch(...){}
        if (ok) {
            fbResized.store(false);
            recreateFailures = 0;
            // Under scanout the new swapchain has shown nothing yet: one black frame goes into it.
            if (scanoutActive.load()) scanoutBlackFrameDone.store(false);
        } else if (zeroExtent || ++recreateFailures < kRecreateFailureLimit) {
            // Transient (a 0x0 window) or not yet given up: keep fbResized and try again after a short wait.
            if (recreateFailures == 1 || recreateFailures % 10 == 0)
                RLOG_W("Swapchain recreation failed (%s, %d in a row); retrying", zeroExtent ? "0x0 window" : "error", recreateFailures);
            recreateBackoff = true;
        } else {
            isRunning = false;
            RLOG_E("Swapchain recreation failed %d times; stopping renderer", recreateFailures);
        }
        presentedGen = UINT64_MAX;
        needsRender.store(ok);
        return;
    }

    if (currentFrame >= cmdBufs.size() || cmdBufs[currentFrame] == VK_NULL_HANDLE) return;
    if (frameUnchanged() || drainQueuedFrame() || holdDrainedContent()) {
        // Woken for something the last presented frame already shows (a change picked up by that frame's snapshot after
        // the flags were cleared, or a pointer that moved back), a game frame skipped to empty the FIFO queue by one, or a
        // wake-up that would present that skipped content after all: no acquire, no present.
        drainFinishedOtherSlots();
        deliverReleasedIdle();
        return;
    }
    bool currentFenceWaited = false;
    if (inFlightFences[currentFrame] != VK_NULL_HANDLE) {
        const VkResult fenceStatus = vk_.GetFenceStatus
            ? vk_.GetFenceStatus(device, inFlightFences[currentFrame]) : VK_NOT_READY;
        if (fenceStatus != VK_SUCCESS) {
            if (!waitForFrameFence(inFlightFences[currentFrame])) return;
            currentFenceWaited = true;
        }
    }
    // This slot's last successful submission, including all preceding queue
    // work, has completed. No wait on the other in-flight slot is necessary.
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        freeRetiredTextures(submittedRetirements[currentFrame]);
    }
    drainFinishedOtherSlots();
    deliverReleasedIdle();

    VkSemaphore acquiredSems[1]{};
    uint32_t acquiredCount = 0;
    auto recovery = AcquiredFrameGuard([&]() noexcept {
        recoverAcquiredFrame(acquiredSems, acquiredCount);
    });
    uint32_t imgIdx = 0;
    VkResult res = VK_SUCCESS;
    // A bounded wait: this thread holds frameMutex shared, and detachSurface (UI thread) waits for it exclusively. An endless
    // acquire on a stuck presentation engine could block the UI thread into an ANR.
    res=vk_.AcquireNextImageKHR(device,swapchain,kAcquireTimeoutNs,imgAvailSems[currentFrame],VK_NULL_HANDLE,&imgIdx);
    if (res==VK_TIMEOUT||res==VK_NOT_READY) {
        // Nothing was acquired and the semaphore is untouched: try again on the next pass of the loop.
        needsRender.store(true, std::memory_order_relaxed);
        return;
    }
    if (res==VK_ERROR_OUT_OF_DATE_KHR||res==VK_ERROR_SURFACE_LOST_KHR){fbResized.store(true);return;}
    if (res!=VK_SUCCESS&&res!=VK_SUBOPTIMAL_KHR) {
        RLOG_E("Swapchain acquire failed: %d", (int)res);
        if (res == VK_ERROR_DEVICE_LOST) isRunning = false;
        return;
    }
    acquiredSems[acquiredCount++] = imgAvailSems[currentFrame];
    recovery.arm();
    if (imgIdx >= swapchainFBs.size() || imgIdx >= swapchainImages.size() || imgIdx >= swapchainRenderFinished.size()) {
        RLOG_E("renderFrame: invalid acquired image index=%u (fb=%zu images=%zu)",
            imgIdx, swapchainFBs.size(), swapchainImages.size());
        return;
    }

    if (imgInFlight.size()!=swapchainImages.size()) imgInFlight.assign(swapchainImages.size(),VK_NULL_HANDLE);
    if (imgInFlight[imgIdx]!=VK_NULL_HANDLE &&
        (!currentFenceWaited || imgInFlight[imgIdx] != inFlightFences[currentFrame])) {
        if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, imgInFlight[imgIdx]) == VK_NOT_READY) {
            if (!waitForFrameFence(imgInFlight[imgIdx])) return;
        }
    }
    imgInFlight[imgIdx]=inFlightFences[currentFrame];

    if (vk_.ResetCommandBuffer(cmdBufs[currentFrame],0) != VK_SUCCESS) {
        RLOG_E("Cannot reset frame command buffer");
        return;
    }

    float ox,oy,sx,sy,cw,ch;
    short ptrX,ptrY,curHotX,curHotY,curW,curH,curUpW=0,curUpH=0,curTexW,curTexH; bool curVis;
    VkBuffer curUpload=VK_NULL_HANDLE; bool hasCurUpload=false;
    uint64_t frameGen=0, frameContentGen=0;
    int64_t frameContentNs=0;

    {
        std::lock_guard<std::mutex> lk(renderMutex);

        // Capture only resources retired before this frame's draw snapshot.
        // A successful submission on graphicsQueue covers every older draw.
        if (pendingRetirements.empty()) {
            pendingRetirements.swap(deleteQueue);
        } else {
            pendingRetirements.insert(pendingRetirements.end(), deleteQueue.begin(), deleteQueue.end());
            deleteQueue.clear();
        }

        ox=sceneOffsetX; oy=sceneOffsetY; sx=sceneScaleX; sy=sceneScaleY;
        frameGen=sceneGen.load(std::memory_order_acquire);
        frameContentGen=contentGen.load(std::memory_order_relaxed);
        frameContentNs=lastContentNs.load(std::memory_order_relaxed);
        cw=(float)containerWidth; ch=(float)containerHeight;
        ptrX=(short)pointerX.load(); ptrY=(short)pointerY.load();
        curHotX=cursorHotX; curHotY=cursorHotY; curW=cursorW; curH=cursorH; curTexW=cursorTexW; curTexH=cursorTexH;
        curVis=cursorVisible.load();

        frameDraws.clear();
        for (const auto& entry:renderList) appendWindowDraw(entry);

        if (isCursorImageDirty.load() && cursorImg!=VK_NULL_HANDLE && !cursorPixels.empty()) {
            // The image plus a repeated right/bottom edge (what clamp-to-edge sampled when the texture had the image's size), so
            // linear/cubic filtering at the edge of the used part never reads a previous, larger cursor.
            curUpW=(short)std::min<int>(cursorW+kCursorEdgePad, cursorTexW);
            curUpH=(short)std::min<int>(cursorH+kCursorEdgePad, cursorTexH);
            VkDeviceSize csz=(VkDeviceSize)curUpW*curUpH*4;
            try {
                ensureCursorStaging(csz);
                curUpload=cursorStaging[currentFrame].buffer;
                auto* dst=static_cast<uint32_t*>(cursorStaging[currentFrame].mapped);
                for (int y=0; y<curUpH; y++) {
                    const uint32_t* src=cursorPixels.data()+(size_t)std::min<int>(y, cursorH-1)*cursorW;
                    uint32_t* row=dst+(size_t)y*curUpW;
                    memcpy(row, src, (size_t)cursorW*4);
                    for (int x=cursorW; x<curUpW; x++) row[x]=src[cursorW-1];
                }
                cursorSnapshotGen = cursorStageGen.load();
                hasCurUpload=true;
            } catch (...) {
                // A transient cursor-staging allocation failure must not stop
                // WoW presentation or sample an image that was never uploaded.
                curVis=false;
                RLOG_E("Cursor staging unavailable; retaining pending upload");
            }
        }
    }

    bool effectiveCurVis = curVis && !scanoutActive.load();

    recordCmdBuf(cmdBufs[currentFrame], swapchainFBs[imgIdx], renderPass, frameDraws,
        frameAhbTransitions, framePreUpload, framePostUpload,
        curUpload, hasCurUpload,
        ox, oy, sx, sy, cw, ch, ptrX, ptrY, curHotX, curHotY, curW, curH, curUpW, curUpH, curTexW, curTexH, effectiveCurVis);

    VkResult endStatus = vk_.EndCommandBuffer(cmdBufs[currentFrame]);
    if (endStatus != VK_SUCCESS) {
        RLOG_E("EndCommandBuffer failed: %d (draws=%zu imgIdx=%u)", (int)endStatus, frameDraws.size(), imgIdx);
        return; // recovery guard consumes acquires and rebuilds the swapchain
    }

    {
        VkSemaphore wSem[]={imgAvailSems[currentFrame]}, sSem[]={swapchainRenderFinished[imgIdx]};
        VkPipelineStageFlags wStage[]={VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
        VkSubmitInfo si{}; si.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.waitSemaphoreCount=1; si.pWaitSemaphores=wSem; si.pWaitDstStageMask=wStage;
        si.signalSemaphoreCount=1; si.pSignalSemaphores=sSem;
        si.commandBufferCount=1; si.pCommandBuffers=&cmdBufs[currentFrame];

        recovery.arm();
        if (vk_.ResetFences(device,1,&inFlightFences[currentFrame]) != VK_SUCCESS) return;
        VkResult submitStatus = vk_.QueueSubmit(graphicsQueue,1,&si,inFlightFences[currentFrame]);
        if (submitStatus != VK_SUCCESS) {
            RLOG_E("QueueSubmit failed: %d", (int)submitStatus);
            return;
        }
        commitTextureRetirements();
        commitAhbTransitions();
        commitCpuUploads();
        if (hasCurUpload) commitCursorUpload();
        recovery.submitted();
        VkSwapchainKHR scs[]={swapchain};
        VkPresentInfoKHR pi{}; pi.sType=VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        pi.waitSemaphoreCount=1; pi.pWaitSemaphores=sSem; pi.swapchainCount=1; pi.pSwapchains=scs; pi.pImageIndices=&imgIdx;
        const int64_t presentNs = monotonicNs();
        const bool gameFrame = frameContentGen != presentedContentGen;
        VkPresentTimeGOOGLE when{};
        VkPresentTimesInfoGOOGLE times{};
        if (displayTimingSupported) {
            refreshCycleDuration(presentNs);
            // No desiredPresentTime: Android keeps the last one given for every later frame (it never returns to automatic
            // timestamps), and SurfaceFlinger then drops queued frames even in FIFO. The ID alone is enough to read back timings.
            when.presentID = nextPresentId;
            presentWasGame[nextPresentId & 255] = gameFrame ? 1 : 0;
            presentQueuedNs[nextPresentId & 255] = presentNs;
            presentMetadataId[nextPresentId & 255] = nextPresentId;
            // The content time and the updates of this frame's snapshot (a later update belongs to the next frame).
            presentContentNs[nextPresentId & 255] = frameContentNs;
            presentContentGenSlot[nextPresentId & 255] = frameContentGen;
            presentContentUpdates[nextPresentId & 255] = gameFrame ? (int)std::min<uint64_t>(frameContentGen - presentedContentGen, 1000000) : 0;
            times.sType = VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE;
            times.swapchainCount = 1; times.pTimes = &when;
            pi.pNext = &times;
        }
        res=vk_.QueuePresentKHR(graphicsQueue,&pi);
        if (res == VK_SUCCESS || res == VK_SUBOPTIMAL_KHR) {
            presentedGen = frameGen;
            if (gameFrame) lastGamePresentNs = presentNs;
            presentedContentGen = frameContentGen;
            presentedPtrX = ptrX; presentedPtrY = ptrY;
            lastPresentNs.store(presentNs, std::memory_order_relaxed);
            if (displayTimingSupported) { ++nextPresentId; collectPresentTimings(); }
        }
        if (res != VK_SUCCESS && res != VK_SUBOPTIMAL_KHR) {
            RLOG_E("QueuePresent failed: %d", (int)res);
            if (res == VK_ERROR_DEVICE_LOST) isRunning = false;
            else fbResized.store(true);
        } else if (res == VK_SUBOPTIMAL_KHR && swapchainNeedsRotationUpdate()) {
            // The display was turned (e.g. landscape flipped 180 degrees): pre-rotate again.
            fbResized.store(true);
        }
        currentFrame=(currentFrame+1)%MAX_FRAMES_IN_FLIGHT;
    }
}

bool VulkanRendererContext::frameUnchanged() {
    if (presentedGen != sceneGen.load(std::memory_order_acquire) || isCursorImageDirty.load()) return false;
    if (cursorVisible.load() && (pointerX.load() != presentedPtrX || pointerY.load() != presentedPtrY)) return false;
    std::lock_guard<std::mutex> lk(renderMutex);
    return deleteQueue.empty();
}

void VulkanRendererContext::refreshCycleDuration(int64_t nowNs) {
    // The panel can switch rate (60/120/144) without a new swapchain: asked again once a second.
    if (refreshQueriedNs && nowNs - refreshQueriedNs < 1000000000LL) return;
    refreshQueriedNs = nowNs;
    VkRefreshCycleDurationGOOGLE cycle{};
    if (vk_.GetRefreshCycleDurationGOOGLE(device, swapchain, &cycle) == VK_SUCCESS && cycle.refreshDuration > 0)
        refreshPeriodNs.store((int64_t)cycle.refreshDuration, std::memory_order_relaxed);
}

void VulkanRendererContext::collectPresentTimings() {
    VkPastPresentationTimingGOOGLE timings[16];
    uint32_t count = 16;
    VkResult status = vk_.GetPastPresentationTimingGOOGLE(device, swapchain, &count, timings);
    if ((status != VK_SUCCESS && status != VK_INCOMPLETE) || !count) return;
    std::sort(timings, timings + count, [](const auto& a, const auto& b) { return a.presentID < b.presentID; });
    const int64_t refresh = refreshPeriodNs.load(std::memory_order_relaxed);
    const int64_t interval = frameIntervalNs.load(std::memory_order_relaxed);
    for (uint32_t i = 0; i < count; ++i) {
        const auto& timing = timings[i];
        if (timing.presentID <= lastTimingId) continue;
        // Presents the swapchain never reported were replaced before they reached the panel (Mailbox). A slot already reused
        // by a newer present says nothing about the old one.
        if (timing.presentID - lastTimingId < 256)
            for (uint32_t id = lastTimingId + 1; id < timing.presentID; ++id)
                if (presentMetadataId[id & 255] == id && presentWasGame[id & 255]) {
                    statMissing.fetch_add(1, std::memory_order_relaxed);
                    latencyWindowDirty = true;
                    logFrame(id, 3, id & 255, 0, 0);
                }
        lastTimingId = timing.presentID;
        const uint32_t slot = timing.presentID & 255;
        if (presentMetadataId[slot] != timing.presentID) {
            statMissing.fetch_add(1, std::memory_order_relaxed);
            latencyWindowDirty = true;
            continue;
        }
        if (!presentWasGame[slot]) continue;
        const uint64_t shownAt = timing.actualPresentTime;
        if (shownAt == 0 || shownAt == UINT64_MAX || shownAt <= lastShownGameNs) {
            statDropped.fetch_add(1, std::memory_order_relaxed);
            latencyWindowDirty = true;
            logFrame(timing.presentID, 2, slot, 0, 0);
            continue;
        }
        statShown.fetch_add(1, std::memory_order_relaxed);
        logFrame(timing.presentID, 1, slot, (int64_t)shownAt, (int64_t)timing.presentMargin);
        // The interval to the previous shown game frame first: a late frame marks the latency window it closes, not the next.
        if (lastShownGameNs) {
            const int64_t gap = (int64_t)(shownAt - lastShownGameNs);
            if (gap < 1000000000LL) {
                if (gap > statMaxIntervalNs.load(std::memory_order_relaxed)) statMaxIntervalNs.store(gap, std::memory_order_relaxed);
                // Late: on screen at least one refresh longer than the limit allows (without a limit: than the recent average).
                // 1.2.3: never shorter than a refresh: the panel cannot show frames faster than its rate (a limit of 90 on 60 Hz).
                const int64_t expected = interval > 0 ? (refresh > interval ? refresh : interval) : shownGapAvgNs;
                if (expected > 0 && refresh > 0 && gap > expected + refresh * 3 / 4) {
                    statLate.fetch_add(1, std::memory_order_relaxed);
                    latencyWindowDirty = true;
                    // The game's content came on time (within half a refresh of the expected spacing) yet was shown late: the
                    // compositor missed. Otherwise the game itself was late (its hitch, not the queue's).
                    const int64_t contentGap = presentContentNs[slot] - lastShownContentNs;
                    if (lastShownContentNs > 0 && presentContentNs[slot] > 0 && contentGap > 0 && contentGap <= expected + refresh / 2)
                        latencyWindowAppLate = true;
                }
                shownGapAvgNs = shownGapAvgNs ? shownGapAvgNs + (gap - shownGapAvgNs) / 16 : gap;
            }
        }
        lastShownGameNs = shownAt;
        if (presentContentNs[slot] > 0) lastShownContentNs = presentContentNs[slot];
        const int64_t queuedAt = presentQueuedNs[slot];
        if (queuedAt > 0 && (int64_t)shownAt > queuedAt && (int64_t)shownAt - queuedAt < 1000000000LL) {
            const int64_t latency = (int64_t)shownAt - queuedAt;
            statLatencyPacked.fetch_add((int64_t(1) << kLatencyCountShift) + latency / 1000, std::memory_order_relaxed);
            if (latencyCount == 0) latencyWindowStartNs = (int64_t)shownAt;
            latencySamples[latencyCount++] = latency;
            if (latencyCount == kLatencyWindow) {
                std::nth_element(latencySamples, latencySamples + kLatencyWindow / 2, latencySamples + kLatencyWindow);
                // Under 90% of the frame rate across the window (loading, a run of hitches): nothing to learn from it.
                // 1.2.3: never shorter than a refresh (a limit above the panel rate made every window "slow": the drain never ran).
                const int64_t expected = interval > refresh ? interval : refresh;
                const bool slow = expected > 0 &&
                    (int64_t)shownAt - latencyWindowStartNs > (int64_t)(kLatencyWindow - 1) * expected * 10 / 9;
                latencyWindowDone(latencySamples[kLatencyWindow / 2], refresh, latencyWindowDirty, slow);
                latencyWindowDirty = false;
                latencyWindowAppLate = false;
                latencyCount = 0;
            }
        }
        if (refresh > 0 && timing.presentMargin > 0) {
            const double angle = 2.0 * M_PI * (double)(timing.presentMargin % (uint64_t)refresh) / (double)refresh;
            phaseSumCos += std::cos(angle);
            phaseSumSin += std::sin(angle);
            if (++phaseFrames >= kPhaseWindow) {
                double mean = std::atan2(phaseSumSin, phaseSumCos);
                if (mean < 0) mean += 2.0 * M_PI;
                phaseSequence.fetch_add(1, std::memory_order_acq_rel);
                phaseMeanNs.store((int64_t)(mean / (2.0 * M_PI) * (double)refresh), std::memory_order_relaxed);
                phaseConcentration.store((int64_t)(std::hypot(phaseSumSin, phaseSumCos) / phaseFrames * 1000.0), std::memory_order_relaxed);
                phaseRefreshNs.store(refresh, std::memory_order_relaxed);
                phaseWindow.fetch_add(1, std::memory_order_relaxed);
                phaseSequence.fetch_add(1, std::memory_order_release);
                phaseSumCos = phaseSumSin = 0;
                phaseFrames = 0;
            }
        }
    }
}

void VulkanRendererContext::latencyWindowDone(int64_t medianNs, int64_t refreshNs, bool dirty, bool slow) {
    exportWindowNs.store(medianNs, std::memory_order_relaxed);
    auto state = [this](int value) {
        exportWindowState.store(value, std::memory_order_relaxed);
        exportFloorNs.store(latencyFloorNs, std::memory_order_relaxed);
    };
    const int mode = fifoDrainMode.load(std::memory_order_relaxed);
    if (activePresentMode.load(std::memory_order_relaxed) != VK_PRESENT_MODE_FIFO_KHR || refreshNs <= 0 || mode == kDrainOff) {
        highWindows = 0;
        drainOne = false;
        state(kWindowNone);
        return;
    }
    if (slow) {
        // Loading or a run of hitches: neither a floor nor a refill is learned from it. 1.1.8: a skip's pending check and refill
        // watch end here too; a window after loading has nothing to do with the skip before it (1.1.6 DX12 diag: a check left
        // over from before a 16 s loading screen took the post-loading 40.9 ms as the floor, one frame above the real one).
        highWindows = 0;
        cleanWindows = 0;
        drainHoldWindows = 0;
        refillWatchWindows = 0;
        refilled = false;
        state(kWindowSlow);
        return;
    }
    if (drainHoldWindows > 0 && (!drainReachedNs || medianNs < drainReachedNs)) drainReachedNs = medianNs;
    if (dirty) {
        // In the world WoW hitches every 1-2 s, so 40-70% of the windows have a drop or a late frame. When only clean windows
        // could act, the drain almost never ran in live play (0-4 skips a session, median latency 31 vs 32 ms off). A hitch
        // window's median still shows how deep the queue is: it may count towards and trigger a skip and it advances a hold,
        // but it never teaches the floor and never gives the hold's verdict (1.3.36: a check decided inside a hitch took 47 ms
        // as the floor) - those stay with clean windows. It does not restart the settle count either.
        state(kWindowDirty);
        // Only a compositor miss refills the emptier queue; the game's own late frames leave a frame the next skip takes out.
        if (refillWatchWindows > 0 && latencyWindowAppLate) { refilled = true; highWindows = 0; return; }
        if (drainHoldWindows > 0) { if (drainHoldWindows > 1) --drainHoldWindows; return; }
        if (!latencyFloorNs || cleanWindows < kSettleWindows) { highWindows = 0; return; }
        highWindows = medianNs > latencyFloorNs + refreshNs * 3 / 4 ? highWindows + 1 : 0;
        if (highWindows >= 2) {
            drainOne = true;
            highWindows = 0;
            drainHoldWindows = 3;
            latencyBeforeDrainNs = medianNs;
            drainReachedNs = 0;
        }
        return;
    }
    ++cleanWindows;
    if (refilled) {
        refilled = false;
        refillWatchWindows = 0;
        latencyFloorNs = medianNs;
        state(kWindowRefilled);
        return;
    }
    if (refillWatchWindows > 0) --refillWatchWindows;
    if (drainHoldWindows > 0) {
        if (--drainHoldWindows == 0) {
            // One step: the level a working skip reached is where it stops. Probe: keep the old floor and try again lower.
            // 1.1.8: judged by the lowest window since the skip, hitch windows included: a hitch right after a working skip
            // refills the queue, and the clean check window alone called the skip useless (1.1.6 DX12 diag: 39.7 -> 24.2 -> 40.9).
            const int64_t reached = drainReachedNs ? std::min(drainReachedNs, medianNs) : medianNs;
            if (latencyBeforeDrainNs - reached < refreshNs / 2) latencyFloorNs = medianNs;
            else if (mode == kDrainOneStep) latencyFloorNs = reached;
            else refillWatchWindows = kRefillWatchWindows;
        }
        state(kWindowHold);
        return;
    }
    if (cleanWindows < kSettleWindows) { state(kWindowSettle); return; }
    if (!latencyFloorNs || medianNs < latencyFloorNs) latencyFloorNs = medianNs;
    highWindows = medianNs > latencyFloorNs + refreshNs * 3 / 4 ? highWindows + 1 : 0;
    state(kWindowClean);
    if (highWindows >= 2) {
        drainOne = true;
        highWindows = 0;
        drainHoldWindows = 3;
        latencyBeforeDrainNs = medianNs;
        drainReachedNs = 0;
    }
}

bool VulkanRendererContext::drainQueuedFrame() {
    if (!drainOne) return false;
    if (activePresentMode.load(std::memory_order_relaxed) != VK_PRESENT_MODE_FIFO_KHR ||
        fifoDrainMode.load(std::memory_order_relaxed) == kDrainOff) {
        drainOne = false;
        return false;
    }
    // Only a new game frame is skipped (the next one shows its content); cursor or scene-only wake-ups present as usual.
    const uint64_t content = contentGen.load(std::memory_order_relaxed);
    if (content == presentedContentGen || isCursorImageDirty.load()) return false;
    drainOne = false;
    // The skipped content stays in the window textures: until the next game frame no other wake-up may present it, or the
    // skip would take nothing out of the queue.
    drainedContentGen = content;
    const int64_t refresh = refreshPeriodNs.load(std::memory_order_relaxed);
    drainHoldUntilNs = monotonicNs() + 2 * (refresh > 0 ? refresh : 16666667);
    // The loop must come back to wait for the hold to end even if nothing else wakes it (OPT12-05): no new game frame, no cursor.
    needsRender.store(true, std::memory_order_relaxed);
    statDrains.fetch_add(1, std::memory_order_relaxed);
    if (frameTraceEnabled.load(std::memory_order_relaxed)) {
        std::lock_guard<std::mutex> lk(frameLogMutex);
        int64_t* row = frameLog[(frameLogHead + frameLogCount) % kFrameLogRows];
        row[0] = 0; row[1] = 4; row[2] = (int64_t)content;
        row[3] = (int64_t)std::min<uint64_t>(content - presentedContentGen, 1000000);
        row[4] = lastContentNs.load(std::memory_order_relaxed); row[5] = monotonicNs(); row[6] = 0; row[7] = 0;
        if (frameLogCount < kFrameLogRows) ++frameLogCount; else frameLogHead = (frameLogHead + 1) % kFrameLogRows;
    }
    return true;
}

bool VulkanRendererContext::holdDrainedContent() {
    if (!drainedContentGen) return false;
    if (contentGen.load(std::memory_order_relaxed) != drainedContentGen) {
        drainedContentGen = 0;
        return false;
    }
    if (monotonicNs() >= drainHoldUntilNs) {
        // No game frame came in time: the skipped content goes out after all, so this skip took nothing out of the queue.
        drainedContentGen = 0;
        statDrainReplays.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    needsRender.store(true, std::memory_order_relaxed);   // presented once the hold ends, if nothing newer came
    return true;
}

void VulkanRendererContext::phaseStats(int64_t out[4]) {
    // The fields of one window only: taken while the sequence is even and unchanged across the reads.
    for (int attempt = 0; attempt < 8; ++attempt) {
        const uint64_t before = phaseSequence.load(std::memory_order_acquire);
        if (before & 1) continue;
        out[0] = phaseWindow.load(std::memory_order_relaxed);
        out[1] = phaseMeanNs.load(std::memory_order_relaxed);
        out[2] = phaseConcentration.load(std::memory_order_relaxed);
        out[3] = phaseRefreshNs.load(std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_acquire);
        if (phaseSequence.load(std::memory_order_relaxed) == before) return;
    }
    // Still being written: a zero concentration makes the pacer ignore this sample (no mixed window is ever used).
    out[2] = 0;
}

void VulkanRendererContext::takeDisplayStats(int64_t out[kDisplayStats]) {
    out[0] = displayTimingSupported ? 1 : 0;
    out[1] = statShown.exchange(0, std::memory_order_relaxed);
    out[2] = statDropped.exchange(0, std::memory_order_relaxed);
    out[3] = statLate.exchange(0, std::memory_order_relaxed);
    out[4] = statMaxIntervalNs.exchange(0, std::memory_order_relaxed) / 1000;
    out[5] = refreshPeriodNs.load(std::memory_order_relaxed) / 1000;
    const int64_t packed = statLatencyPacked.exchange(0, std::memory_order_relaxed);
    const int64_t frames = packed >> kLatencyCountShift;
    const int64_t totalUs = packed & ((int64_t(1) << kLatencyCountShift) - 1);
    out[6] = frames > 0 ? totalUs / frames : 0;
    out[7] = statDrains.exchange(0, std::memory_order_relaxed);
    out[8] = statMissing.exchange(0, std::memory_order_relaxed);
    out[9] = statDrainReplays.exchange(0, std::memory_order_relaxed);
    out[10] = exportFloorNs.load(std::memory_order_relaxed) / 1000;
    out[11] = exportWindowNs.load(std::memory_order_relaxed) / 1000;
    out[12] = exportWindowState.load(std::memory_order_relaxed);
}

void VulkanRendererContext::logFrame(int64_t id, int64_t kind, uint32_t slot, int64_t displayNs, int64_t marginNs) {
    if (!frameTraceEnabled.load(std::memory_order_relaxed)) return;
    std::lock_guard<std::mutex> lk(frameLogMutex);
    int64_t* row = frameLog[(frameLogHead + frameLogCount) % kFrameLogRows];
    row[0] = id; row[1] = kind; row[2] = (int64_t)presentContentGenSlot[slot]; row[3] = presentContentUpdates[slot];
    row[4] = presentContentNs[slot]; row[5] = presentQueuedNs[slot]; row[6] = displayNs; row[7] = marginNs;
    if (frameLogCount < kFrameLogRows) ++frameLogCount; else frameLogHead = (frameLogHead + 1) % kFrameLogRows;
}

int VulkanRendererContext::takeFrameLog(int64_t* out, int maxRows) {
    std::lock_guard<std::mutex> lk(frameLogMutex);
    const int rows = std::min(frameLogCount, maxRows);
    for (int i = 0; i < rows; ++i)
        memcpy(out + (size_t)i * kFrameLogFields, frameLog[(frameLogHead + i) % kFrameLogRows], sizeof(int64_t) * kFrameLogFields);
    frameLogHead = (frameLogHead + rows) % kFrameLogRows;
    frameLogCount -= rows;
    return rows;
}

VkSurfaceTransformFlagBitsKHR VulkanRendererContext::choosePreTransform(const VkSurfaceCapabilitiesKHR& caps, uint32_t& rotation) {
    rotation=0;
    switch (caps.currentTransform) {
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:  rotation=1; break;
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: rotation=2; break;
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: rotation=3; break;
        default: break;
    }
    if (rotation && (caps.supportedTransforms&caps.currentTransform)) return caps.currentTransform;
    rotation=0;
    // Mirrored or unsupported transforms keep the previous behaviour (the compositor transforms).
    return (caps.supportedTransforms&VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)?
        VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:caps.currentTransform;
}

bool VulkanRendererContext::swapchainNeedsRotationUpdate() {
    VkSurfaceCapabilitiesKHR caps;
    if (vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice,surface,&caps)!=VK_SUCCESS) return false;
    uint32_t rotation=0;
    const bool rotated=choosePreTransform(caps, rotation)!=swapchainPreTransform;
    if (rotated) RLOG("present SUBOPTIMAL: surface transform %d, swapchain %d; recreating",
        (int)caps.currentTransform,(int)swapchainPreTransform);
    return rotated;
}

void VulkanRendererContext::onSurfaceResized(int w, int h) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    if (w<=0||h<=0 || (surfaceWidth==w && surfaceHeight==h)) return;
    surfaceWidth=w; surfaceHeight=h; fbResized.store(true); renderWake.notify();
}

void VulkanRendererContext::detachSurface() {
    surfaceDetached.store(true, std::memory_order_release);
    renderWake.notify();

    // Keep readers excluded until all old surface resources have been retired.
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);

    vk_.DeviceWaitIdle(device);
    cleanupSwapchain();
    if (surface != VK_NULL_HANDLE) {
        vk_.DestroySurfaceKHR(instance, surface, nullptr);
        surface = VK_NULL_HANDLE;
    }
    if (window) {
        ANativeWindow_release(window);
        window = nullptr;
    }
}

bool VulkanRendererContext::reattachSurface(ANativeWindow* newWindow) {
    if (!isRunning.load()) {
        // The render thread has stopped for good: a surface would never be drawn to. False makes Java build a new renderer.
        if (newWindow) ANativeWindow_release(newWindow);
        __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: render thread not running");
        return false;
    }
    if (window) { ANativeWindow_release(window); window = nullptr; }
    window = newWindow;
    VkAndroidSurfaceCreateInfoKHR ci{};
    ci.sType  = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    ci.window = window;
    if (vk_.CreateAndroidSurfaceKHR(instance, &ci, nullptr, &surface) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: CreateAndroidSurface failed");
        ANativeWindow_release(window); window = nullptr;
        return false;
    }
    {
        std::unique_lock<std::shared_mutex> frameLock(frameMutex);
        try {
            createSwapchain();
            createFramebuffers();
            createCmdBufs();
            imgInFlight.assign(swapchainImages.size(), VK_NULL_HANDLE);
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, "Winlator_Renderer", "reattachSurface: swapchain recreate failed");
            return false;
        }
        surfaceDetached.store(false, std::memory_order_release);
    }
    markSceneDirty();
    renderWake.notify();
    __android_log_print(ANDROID_LOG_DEBUG, "Winlator_Renderer", "reattachSurface: OK");
    return true;
}

void VulkanRendererContext::setTransform(float ox, float oy, float sx, float sy) {
    std::lock_guard<std::mutex> lk(renderMutex);
    if (sceneOffsetX==ox && sceneOffsetY==oy && sceneScaleX==sx && sceneScaleY==sy) return;
    sceneOffsetX=ox; sceneOffsetY=oy; sceneScaleX=sx; sceneScaleY=sy;
    markSceneDirty(); renderWake.notify();
}

void VulkanRendererContext::updatePointerPosition(short x, short y) {
    const int previousX = pointerX.exchange(x);
    const int previousY = pointerY.exchange(y);
    if ((previousX != x || previousY != y) && cursorVisible.load()) {
        cursorMoved.store(true);
        renderWake.notify();
    }
}

void VulkanRendererContext::setCursorVisible(bool v) {
    if (cursorVisible.exchange(v)==v) return;
    markSceneDirty(); renderWake.notify();
}

void VulkanRendererContext::updateCursorImage(void* px, short w, short h, short hotX, short hotY) {
    if (!px||w<=0||h<=0) return;
    const size_t bytes=(size_t)w*h*4;
    const auto started=std::chrono::steady_clock::now();
    {
        // Any cursor that fits the reserved texture: only CPU state changes (the render thread uploads from its snapshot), so
        // this callback, which runs under the X server window/input locks, never waits for a frame in acquire/present.
        std::lock_guard<std::mutex> lk(renderMutex);
        if (cursorImg!=VK_NULL_HANDLE && w<=cursorTexW && h<=cursorTexH) {
            if (cursorW==w && cursorH==h && cursorPixels.size()==(size_t)w*h && memcmp(cursorPixels.data(),px,bytes)==0) {
                if (cursorHotX!=hotX || cursorHotY!=hotY) {
                    cursorHotX=hotX; cursorHotY=hotY;
                    markSceneDirty(); renderWake.notify();
                }
            } else {
                cursorPixels.resize((size_t)w*h);
                memcpy(cursorPixels.data(),px,bytes);
                cursorW=w; cursorH=h;
                cursorHotX=hotX; cursorHotY=hotY;
                cursorStageGen.fetch_add(1);
                isCursorImageDirty.store(true); markSceneDirty(); renderWake.notify();
            }
            noteSlowCursorUpdate(started, w, h, false);
            return;
        }
    }
    // The first cursor, or one larger than the reserved texture: the image has to be (re)created, which only the exclusive
    // frame lock allows.
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    if (!ensureCursorTex(std::max<short>(w,kCursorCapacity), std::max<short>(h,kCursorCapacity))) return;
    cursorPixels.resize((size_t)w*h); memcpy(cursorPixels.data(),px,bytes);
    cursorW=w; cursorH=h;
    cursorHotX=hotX; cursorHotY=hotY;
    cursorStageGen.fetch_add(1);
    isCursorImageDirty.store(true); markSceneDirty(); renderWake.notify();
    noteSlowCursorUpdate(started, w, h, true);
}

void VulkanRendererContext::noteSlowCursorUpdate(std::chrono::steady_clock::time_point started, short w, short h, bool resources) {
    const auto us=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-started).count();
    if (us>=4000) RLOG_W("Slow cursor update: %dx%d took %lld us (%s)", (int)w, (int)h, (long long)us,
        resources ? "texture created" : "pixels only");
}

VkBuffer VulkanRendererContext::prepareCpuUpload(WinTex& wt) {
    auto& slot=wt.uploads[currentFrame];
    const VkDeviceSize size=(VkDeviceSize)wt.w*wt.h*4;
    if (slot.capacity<size) {
        CpuUpload candidate{};
        try {
            createBuffer(size,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                candidate.buffer,candidate.memory);
            if (vk_.MapMemory(device,candidate.memory,0,size,0,&candidate.mapped)!=VK_SUCCESS || !candidate.mapped)
                throw std::runtime_error("CPU upload map");
            candidate.capacity=size;
        } catch (...) {
            if (candidate.buffer!=VK_NULL_HANDLE) vk_.DestroyBuffer(device,candidate.buffer,nullptr);
            if (candidate.memory!=VK_NULL_HANDLE) vk_.FreeMemory(device,candidate.memory,nullptr);
            throw;
        }
        // renderFrame has completed this slot's fence before taking its snapshot.
        if (slot.buffer!=VK_NULL_HANDLE) vk_.DestroyBuffer(device,slot.buffer,nullptr);
        if (slot.memory!=VK_NULL_HANDLE) vk_.FreeMemory(device,slot.memory,nullptr);
        slot=candidate;
    }
    memcpy(slot.mapped,wt.pixels->data(),(size_t)size);
    return slot.buffer;
}

void VulkanRendererContext::appendWindowDraw(const RenderEntry& entry) {
    auto it=texMap.find(entry.id);
    if (it==texMap.end()) return;
    WinTex& wt=it->second;
    if (wt.ds==VK_NULL_HANDLE) return;
    DrawEntry draw{wt.img,wt.ds,VK_NULL_HANDLE,entry.x,entry.y,wt.w,wt.h};
    draw.id=entry.id; draw.revision=wt.revision; draw.isAHB=wt.isAHB; draw.ahb=wt.ahb;
    draw.tw=wt.w; draw.th=wt.h;
    if (wt.winW>0 && wt.winH>0 && wt.w>0 && wt.h>0 &&
        (wt.presentX!=0 || wt.presentY!=0 || wt.w!=wt.winW || wt.h!=wt.winH)) {
        // Present's xOff/yOff place the picture's top-left corner inside the window, and only the part inside the window is
        // visible: the quad is that part, showing the matching part of the texture. (A zero offset and a picture of the
        // window's size - the normal full-screen case - skips all of this.)
        const int64_t vx0=std::max<int64_t>(0,wt.presentX), vy0=std::max<int64_t>(0,wt.presentY);
        const int64_t vx1=std::min<int64_t>(wt.winW,(int64_t)wt.presentX+wt.w), vy1=std::min<int64_t>(wt.winH,(int64_t)wt.presentY+wt.h);
        if (vx1<=vx0 || vy1<=vy0) return;   // nothing of the picture lies inside the window
        draw.x=entry.x+(int)vx0; draw.y=entry.y+(int)vy0; draw.w=(int)(vx1-vx0); draw.h=(int)(vy1-vy0);
        draw.u0=(float)(vx0-wt.presentX)/(float)wt.w; draw.v0=(float)(vy0-wt.presentY)/(float)wt.h;
        draw.u1=(float)(vx1-wt.presentX)/(float)wt.w; draw.v1=(float)(vy1-wt.presentY)/(float)wt.h;
    }
    if (wt.isAHB) {
        auto cached=ahbImportCache.find(wt.ahb);
        draw.needsTransition=cached!=ahbImportCache.end() && cached->second.needsTransition;
    } else {
        draw.needsTransition=wt.needsTransition;
        if (wt.dirty) {
            try { draw.upload=prepareCpuUpload(wt); }
            catch (...) {
                RLOG_E("CPU staging unavailable; retaining pending update");
                if (wt.needsTransition) return; // An uninitialized image must not be sampled.
            }
        }
    }
    frameDraws.push_back(draw);
}

void VulkanRendererContext::commitCpuUploads() {
    if (std::none_of(frameDraws.begin(),frameDraws.end(),
            [](const auto& draw) { return !draw.isAHB && draw.upload!=VK_NULL_HANDLE; })) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    for (const auto& draw:frameDraws) {
        if (draw.isAHB || draw.upload==VK_NULL_HANDLE) continue;
        auto it=texMap.find(draw.id);
        if (it==texMap.end() || it->second.img!=draw.img) continue;
        it->second.needsTransition=false;
        // An update received after this frame's snapshot must remain pending.
        if (it->second.revision==draw.revision) it->second.dirty=false;
    }
}

void VulkanRendererContext::updateWindowContent(int64_t id, void* px, short w, short h, short stride, int offX, int offY, int winW, int winH) {
    if (!px || w<=0 || h<=0 || (stride>0 && stride<w)) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    bool housekeeping=false;   // retired tokens or a replaced texture: the loop must run to clean up, visible or not
    try {
        {   // a window that now shows CPU pixels no longer shows a Present buffer
            auto tokens=windowIdleTokens.find(id);
            if (tokens!=windowIdleTokens.end()) { retireIdleTokens(tokens->second); windowIdleTokens.erase(tokens); housekeeping=true; }
        }
        auto it=texMap.find(id);
        if (it==texMap.end() || it->second.isAHB || it->second.img==VK_NULL_HANDLE ||
            it->second.w!=w || it->second.h!=h) {
            // The buffers this window imported while it showed Present content are no longer its picture: they are given up below
            // (they used to stay imported until the window went away). Room for their retirement is made first, so that step
            // cannot fail half-way.
            auto ahbs=windowAhbs.find(id);
            if (ahbs!=windowAhbs.end()) deleteQueue.reserve(deleteQueue.size()+ahbs->second.size()+1);
            WinTex candidate{};
            candidate.pixels=std::make_shared<std::vector<uint8_t>>((size_t)w*h*4);
            if (!createWinTexResources(candidate,w,h)) {
                // The retired tokens still have to reach their client: wake the loop for them.
                if (housekeeping) { markSceneDirty(); renderWake.notify(); }
                return;
            }
            // Destination allocation may fail after the private image was built.
            // Unwind its handles directly; cleanup must not allocate memory.
            if (it==texMap.end()) {
                try { it=texMap.emplace(id,WinTex{}).first; }
                catch (...) {
                    vk_.FreeDescriptorSets(device,winTexPool,1,&candidate.ds);
                    vk_.DestroyImageView(device,candidate.view,nullptr);
                    vk_.DestroyImage(device,candidate.img,nullptr);
                    vk_.FreeMemory(device,candidate.mem,nullptr);
                    throw;
                }
            }
            try { destroyWinTex(it->second); }
            catch (...) {
                // Retirement queue growth failed; the old image is still owned.
                // The unpublished replacement must be released without allocation.
                vk_.FreeDescriptorSets(device,winTexPool,1,&candidate.ds);
                vk_.DestroyImageView(device,candidate.view,nullptr);
                vk_.DestroyImage(device,candidate.img,nullptr);
                vk_.FreeMemory(device,candidate.mem,nullptr);
                throw;
            }
            it->second=std::move(candidate);
            housekeeping=true;
            if (ahbs!=windowAhbs.end()) {
                for (AHardwareBuffer* ahb : ahbs->second) releaseAhbOwnership(ahb);
                windowAhbs.erase(ahbs);
            }
        }
        WinTex& wt=it->second;
        const size_t dstPitch=(size_t)w*4;
        const size_t srcPitch=(size_t)(stride>0?stride:w)*4;
        auto* source=static_cast<const uint8_t*>(px);
        for (int row=0;row<h;++row)
            memcpy(wt.pixels->data()+(size_t)row*dstPitch,source+(size_t)row*srcPitch,dstPitch);
        ++wt.revision; wt.dirty=true;
        wt.presentX=offX; wt.presentY=offY; wt.winW=winW; wt.winH=winH;
        // Codex OPT13-07: a same-size update of a window that is not drawn (fully covered, so left out of the render list) only
        // stores its pixels; it is no game content and wakes nothing. Uncovering it changes the render list, which redraws with
        // these fresh pixels. Housekeeping and direct scanout (it empties the render list itself) keep the full signal.
        const bool drawn=std::any_of(renderList.begin(),renderList.end(),[id](const RenderEntry& e){ return e.id==id; });
        if (!drawn && !housekeeping && !scanoutActive.load()) return;
        contentGen.fetch_add(1, std::memory_order_relaxed);
        lastContentNs.store(monotonicNs(), std::memory_order_relaxed);
        markSceneDirty(); renderWake.notify();
    } catch (...) {
        RLOG_E("CPU window update unavailable; preserving previous content");
        if (housekeeping) { markSceneDirty(); renderWake.notify(); }
    }
}

void VulkanRendererContext::updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short w, short h, int offX, int offY, int winW, int winH, int64_t idleToken) {
    std::lock_guard<std::mutex> lk(renderMutex);
    bool tokenTaken = false;   // the token now belongs to the window's list (or was released through it)
    try { updateWindowContentAHBLocked(id, ahb, w, h, offX, offY, winW, winH, idleToken, tokenTaken); }
    catch (...) {
        // Every early way out hands the hold back: a Present nobody keeps waited out the 2 s stall limit, once per frame.
        // The exception still reaches the caller (the JNI entry logs it), the token is settled here.
        if (!tokenTaken) handBackIdleTokenLocked(idleToken);
        markSceneDirty(); renderWake.notify();
        throw;
    }
}

void VulkanRendererContext::updateWindowContentAHBLocked(int64_t id, AHardwareBuffer* ahb, short, short, int offX, int offY, int winW, int winH,
                                                         int64_t idleToken, bool& tokenTaken) {
    if (!ahb) { handBackIdleTokenLocked(idleToken); tokenTaken = true; return; }
    const size_t pendingRetirements = deleteQueue.size();
    const size_t pendingIdleReleases = idleReleased.size();




    // A fresh import is an image, its memory, a view and a descriptor that no container owns yet. Should the bookkeeping below
    // run out of memory, they are freed right here (the GPU has never seen them): the slot for that is reserved before the import.
    std::vector<WinTex> rollback;
    bool publishedNow = false;
    auto cit = ahbImportCache.find(ahb);
    if (cit == ahbImportCache.end()) {
        rollback.reserve(1);
        WinTex tmp{};
        if (!importAHBToWinTex(tmp, ahb)) {
            RLOG_E("updateWindowContentAHB: import failed for id=%" PRId64, id);
            handBackIdleTokenLocked(idleToken); tokenTaken = true;
            return;
        }
        try {
            cit = ahbImportCache.emplace(ahb, tmp).first;
        } catch (...) {
            tmp.isAHB = false;
            rollback.push_back(tmp);
            freeRetiredTextures(rollback);
            RLOG_E("updateWindowContentAHB: out of memory publishing the import for id=%" PRId64, id);
            handBackIdleTokenLocked(idleToken); tokenTaken = true;
            return;
        }
        AHardwareBuffer_acquire(ahb);   // only once the cache owns the import: nothing can fail between the two
        publishedNow = true;
        RLOG("updateWindowContentAHB: imported new AHB %p for id=%" PRId64 " (%dx%d)",
            (void*)ahb, id, tmp.w, tmp.h);
    }

    try {
        auto& recorded = windowAhbs[id];
        if (std::find(recorded.begin(), recorded.end(), ahb) == recorded.end()) {
            // Everything here that can run out of memory happens BEFORE anything is changed: a failure must leave the window's
            // list and the owner counter as they were, because the two always describe the same owners (a list entry without
            // a counted owner would later let the first window to go retire a buffer another window still shows).
            recorded.reserve(recorded.size() + 1);
            auto counter = ahbOwnerCounts.try_emplace(ahb, 0u).first;
            recorded.push_back(ahb);   // capacity reserved above: cannot throw
            ++counter->second;
        }
    } catch (...) {
        // No owner could be recorded for a buffer imported just now: take the import back out of the cache again.
        if (publishedNow && ahbOwnerCounts.find(ahb) == ahbOwnerCounts.end()) {
            WinTex undone = cit->second;
            undone.isAHB = false;
            rollback.push_back(undone);
            ahbImportCache.erase(cit);
            AHardwareBuffer_release(ahb);
            freeRetiredTextures(rollback);
        }
        RLOG_E("updateWindowContentAHB: out of memory recording the owner for id=%" PRId64, id);
        handBackIdleTokenLocked(idleToken); tokenTaken = true;
        return;
    }

    auto& buffers = windowAhbs[id];   // the entry exists now: this lookup cannot allocate

    WinTex& src = cit->second;
    WinTex& wt  = texMap[id];
    AHardwareBuffer* previousAhb = wt.isAHB ? wt.ahb : nullptr;
    if (!wt.isAHB) destroyWinTex(wt);
    wt = src;
    wt.ahb = ahb;
    wt.presentX = offX; wt.presentY = offY; wt.winW = winW; wt.winH = winH;   // this window's own placement of the shared buffer
    if (idleToken != 0) {
        try {
            auto& tokens = windowIdleTokens[id];
            // A different buffer replaces the one shown so far: the older Presents are free once the frames that may read
            // their buffer are done. The same buffer presented again keeps all its tokens until it is replaced.
            if (previousAhb != ahb) retireIdleTokens(tokens);
            tokens.push_back(idleToken);
            tokenTaken = true;
            // The same buffer presented over and over without ever being handed back is not how a swapchain behaves; bound the
            // list anyway: the oldest Presents are released (fail-open, as the Java side's time limit would do much later).
            constexpr size_t kMaxTokensPerBuffer = 64;
            if (tokens.size() > kMaxTokensPerBuffer) {
                const size_t drop = tokens.size() - kMaxTokensPerBuffer;
                for (size_t i = 0; i < drop; ++i) { try { idleReleased.push_back(tokens[i]); } catch (...) {} }
                tokens.erase(tokens.begin(), tokens.begin() + drop);
            }
        } catch (...) {
            // Not recorded: hand it back now rather than after the Java side's 2 s limit (what retireIdleTokens does too).
            if (!tokenTaken) { handBackIdleTokenLocked(idleToken); tokenTaken = true; }
        }
    }
    // A window that keeps getting new swapchain buffers must not keep every one it ever used (each holds an image, its
    // memory and a descriptor of the small pool): beyond a generous per-window limit the oldest import is retired. It is
    // re-imported on a cache miss if it ever comes back, and the retirement waits for the frames still using it.
    constexpr size_t kMaxAhbPerWindow = 16;
    // All windows together: the descriptor pool has 129 sets, shared with the CPU window textures, the cursor and the retired
    // images that still wait for their frames. Imports beyond this total would fail to get a descriptor, so the window holding
    // the most of them gives up its oldest one first.
    constexpr size_t kMaxAhbTotal = 96;
    ahbPeakPerWindow = std::max(ahbPeakPerWindow, buffers.size());
    ahbPeakTotal = std::max(ahbPeakTotal, ahbImportCache.size());
    auto noteEviction = [&](int64_t window, size_t inWindow) {
        ++ahbEvictions;
        if (ahbEvictions <= 32 || (ahbEvictions & (ahbEvictions - 1)) == 0)
            RLOG_W("AHB import limit: window %" PRId64 " retired its oldest import (%zu in the window, %zu in total, %zu retired so far)",
                   window, inWindow, ahbImportCache.size(), ahbEvictions);
    };
    while (buffers.size() > kMaxAhbPerWindow) {
        auto victim = std::find_if(buffers.begin(), buffers.end(), [ahb](AHardwareBuffer* b) { return b != ahb; });
        if (victim == buffers.end()) break;
        AHardwareBuffer* old = *victim;
        buffers.erase(victim);
        noteEviction(id, buffers.size() + 1);
        releaseAhbOwnership(old);
    }
    while (ahbImportCache.size() > kMaxAhbTotal) {
        int64_t fattestId = 0;
        std::vector<AHardwareBuffer*>* fattest = nullptr;
        for (auto& [windowId, list] : windowAhbs)
            if (list.size() > 1 && (!fattest || list.size() > fattest->size())) { fattest = &list; fattestId = windowId; }
        if (!fattest) break;
        // Never the buffer the window shows right now (a window keeps its texture view and descriptor from it).
        auto shown = texMap.find(fattestId);
        AHardwareBuffer* keep = shown != texMap.end() ? shown->second.ahb : nullptr;
        auto victim = std::find_if(fattest->begin(), fattest->end(), [keep](AHardwareBuffer* b) { return b != keep; });
        if (victim == fattest->end()) break;
        AHardwareBuffer* old = *victim;
        fattest->erase(victim);
        noteEviction(fattestId, fattest->size() + 1);
        releaseAhbOwnership(old);
    }
    // Keep the latest buffer even for a covered window. The render list wakes
    // the compositor if the window is revealed.
    const bool drawn = scanoutActive.load() || std::any_of(renderList.begin(), renderList.end(),
        [id](const RenderEntry& entry) { return entry.id == id; });
    if (drawn) {
        contentGen.fetch_add(1, std::memory_order_relaxed);
        lastContentNs.store(monotonicNs(), std::memory_order_relaxed);
    } else if (deleteQueue.size() == pendingRetirements && idleReleased.size() == pendingIdleReleases) {
        return;
    }
    markSceneDirty(); renderWake.notify();
}

void VulkanRendererContext::handBackIdleToken(int64_t token) {
    if (token == 0) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    handBackIdleTokenLocked(token);
}

void VulkanRendererContext::handBackIdleTokenLocked(int64_t token) {
    if (token == 0) return;
    // Its buffer was never imported or drawn: free at once. Delivered by the render thread (the sink must not run on the caller's
    // thread, which holds X server locks); a frame with nothing new only hands tokens back, it presents nothing.
    try { idleReleased.push_back(token); } catch (...) { return; }   // out of memory: the Java side's time limit releases it
    needsRender.store(true, std::memory_order_release);
    renderWake.notify();
}

void VulkanRendererContext::setIdleSink(std::function<void(const std::vector<int64_t>&)> sink) {
    std::lock_guard<std::mutex> lk(idleSinkMutex);
    idleSinkInstalled.store(static_cast<bool>(sink), std::memory_order_release);
    idleSink = std::move(sink);
}

void VulkanRendererContext::retireIdleTokens(std::vector<int64_t>& tokens) {
    for (int64_t token : tokens) {
        WinTex carrier{};
        carrier.idleToken = token;
        try { deleteQueue.push_back(carrier); }
        catch (...) {
            // No room to queue it behind the frames: hand it back at once (what the client got before this feature existed).
            try { idleReleased.push_back(token); } catch (...) {}
        }
    }
    tokens.clear();
}

void VulkanRendererContext::drainFinishedOtherSlots() {
    std::lock_guard<std::mutex> lk(renderMutex);
    for (unsigned slot = 0; slot < MAX_FRAMES_IN_FLIGHT; ++slot) {
        if (slot == currentFrame || submittedRetirements[slot].empty()) continue;
        bool carriesToken = false;
        for (const auto& wt : submittedRetirements[slot]) if (wt.idleToken != 0) { carriesToken = true; break; }
        if (!carriesToken) continue;   // ordinary resources wait for their slot's normal turn
        // The other slot's last submission has finished: nothing recorded before it can still be read by the GPU.
        if (!vk_.GetFenceStatus || vk_.GetFenceStatus(device, inFlightFences[slot]) != VK_SUCCESS) continue;
        freeRetiredTextures(submittedRetirements[slot]);
    }
}

bool VulkanRendererContext::hasIdleTokensInFlight() {
    std::lock_guard<std::mutex> lk(renderMutex);
    if (!idleReleased.empty()) return true;
    for (unsigned slot = 0; slot < MAX_FRAMES_IN_FLIGHT; ++slot)
        for (const auto& wt : submittedRetirements[slot]) if (wt.idleToken != 0) return true;
    // Retired but not yet taken by a frame (under scanout no frame comes to take them): pollIdleTokens frees them too.
    for (const auto& wt : deleteQueue) if (wt.idleToken != 0) return true;
    for (const auto& wt : pendingRetirements) if (wt.idleToken != 0) return true;
    return false;
}

void VulkanRendererContext::pollIdleTokens() {
    std::lock_guard<std::mutex> lk(renderMutex);
    for (unsigned slot = 0; slot < MAX_FRAMES_IN_FLIGHT; ++slot) {
        if (submittedRetirements[slot].empty()) continue;
        bool carriesToken = false;
        for (const auto& wt : submittedRetirements[slot]) if (wt.idleToken != 0) { carriesToken = true; break; }
        if (!carriesToken) continue;
        // This slot's last submission has finished: nothing recorded before it can still be read by the GPU. (The render thread is
        // the only one that resets or submits with these fences, and it is here, between two frames.)
        if (!vk_.GetFenceStatus || inFlightFences[slot] == VK_NULL_HANDLE ||
            vk_.GetFenceStatus(device, inFlightFences[slot]) != VK_SUCCESS) continue;
        freeRetiredTextures(submittedRetirements[slot]);
    }
    // Tokens retired since the last frame's snapshot: no frame recorded later can read their buffer (the window shows something
    // else now), so once every submitted frame has finished the client may have it back. The render thread is the only recorder
    // and it is here, between frames. Only the tokens leave; GPU resources keep their normal turn with the next frame.
    bool queued = false;
    for (const auto& wt : deleteQueue) if (wt.idleToken != 0) { queued = true; break; }
    if (!queued) for (const auto& wt : pendingRetirements) if (wt.idleToken != 0) { queued = true; break; }
    if (!queued || !vk_.GetFenceStatus) return;
    for (unsigned slot = 0; slot < MAX_FRAMES_IN_FLIGHT; ++slot)
        if (inFlightFences[slot] == VK_NULL_HANDLE || vk_.GetFenceStatus(device, inFlightFences[slot]) != VK_SUCCESS) return;
    for (auto* list : {&deleteQueue, &pendingRetirements})
        for (auto& wt : *list)
            if (wt.idleToken != 0) {
                try { idleReleased.push_back(wt.idleToken); } catch (...) { continue; }   // kept: a later pass or frame retries
                wt.idleToken = 0;
            }
}

bool VulkanRendererContext::deliverReleasedIdle() {
    std::vector<int64_t> done;
    {
        std::lock_guard<std::mutex> lk(renderMutex);
        if (idleReleased.empty()) return false;
        done.swap(idleReleased);
    }
    std::function<void(const std::vector<int64_t>&)> sink;
    { std::lock_guard<std::mutex> lk(idleSinkMutex); sink = idleSink; }
    if (sink) sink(done);
    return true;
}

void VulkanRendererContext::setRenderList(const int64_t* ids, const int* xs, const int* ys, int count) {
    if (count<0 || (count>0 && (!ids || !xs || !ys))) return;
    std::lock_guard<std::mutex> lk(renderMutex);
    bool same=renderList.size()==(size_t)count;
    for (int i=0;same && i<count;++i)
        same=renderList[i].id==ids[i] && renderList[i].x==xs[i] && renderList[i].y==ys[i];
    if (same) return;
    renderList.resize(count);
    for (int i=0;i<count;++i) renderList[i]={ids[i],xs[i],ys[i]};
    markSceneDirty(); renderWake.notify();
}

void VulkanRendererContext::releaseAhbOwnership(AHardwareBuffer* ahb) {
    auto owners = ahbOwnerCounts.find(ahb);
    if (owners != ahbOwnerCounts.end()) {
        if (--owners->second != 0) return;
        ahbOwnerCounts.erase(owners);
    }
    auto cit = ahbImportCache.find(ahb);
    if (cit != ahbImportCache.end()) {
        WinTex deferred = cit->second;
        deferred.isAHB  = false;
        deleteQueue.push_back(deferred);
        AHardwareBuffer_release(ahb);
        ahbImportCache.erase(cit);
    }
}

void VulkanRendererContext::removeWindow(int64_t id) {
    std::lock_guard<std::mutex> lk(renderMutex);



    auto it = texMap.find(id);
    if (it != texMap.end()) {
        if (!it->second.isAHB) destroyWinTex(it->second);
        else it->second = {};
        texMap.erase(it);
    }


    auto wit = windowAhbs.find(id);
    if (wit != windowAhbs.end()) {
        for (AHardwareBuffer* ahb : wit->second) releaseAhbOwnership(ahb);
        windowAhbs.erase(wit);
    }
    {
        auto tokens = windowIdleTokens.find(id);
        if (tokens != windowIdleTokens.end()) { retireIdleTokens(tokens->second); windowIdleTokens.erase(tokens); }
    }

    renderList.erase(std::remove_if(renderList.begin(),renderList.end(),
        [id](const RenderEntry& e){return e.id==id;}),renderList.end());
    markSceneDirty(); renderWake.notify();
}

void VulkanRendererContext::dumpRendererInfo() {
    VkPhysicalDeviceProperties props{};
    vk_.GetPhysicalDeviceProperties(physicalDevice,&props);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "=== RENDERER INFO ===");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "GPU: %s vendorID=0x%x driverVersion=0x%x apiVersion=%d.%d.%d",
        props.deviceName,props.vendorID,props.driverVersion,
        VK_VERSION_MAJOR(props.apiVersion),VK_VERSION_MINOR(props.apiVersion),VK_VERSION_PATCH(props.apiVersion));
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Swapchain: %dx%d fmt=%d",swapchainExt.width,swapchainExt.height,(int)swapchainFmt);
    std::string pmList;
    for(auto pm:availablePresentModes) pmList+=std::to_string((int)pm)+" ";
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "SupportedPresentModes: [%s] current=%d",pmList.c_str(),(int)requestedPresentMode);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Filter: mode=%d (%s)", filterMode, filterMode==2?(cubicSupported?"CUBIC":"LINEAR"):filterMode==1?"NEAREST":"LINEAR");
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Scanout: active=%d gameFrameDelivered=%d",
        (int)scanoutActive.load(),(int)gameFrameDelivered.load());   // the surface handles belong to the scanout lifetime lock: not read here
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,
        "Surface: %dx%d container: %dx%d",
        surfaceWidth,surfaceHeight,containerWidth,containerHeight);
    __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,"=== END RENDERER INFO ===");
}

void VulkanRendererContext::setFilterMode(int mode) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    RLOG("setFilterMode: %d -> %d (%s->%s)", filterMode, mode,
        filterMode==2?(cubicSupported?"CUBIC":"LINEAR"):filterMode==1?"NEAREST":"LINEAR", mode==2?(cubicSupported?"CUBIC":"LINEAR"):mode==1?"NEAREST":"LINEAR");
    if (filterMode==mode) { RLOG("setFilterMode: already set, skipping"); return; }
    vk_.DeviceWaitIdle(device);
    // The new sampler first: if it cannot be made, every descriptor keeps the old one (destroying first left them pointing at a
    // destroyed sampler, and the exception crossed JNI).
    VkSampler fresh = VK_NULL_HANDLE;
    try { fresh = makeSampler(mode); }
    catch (...) { RLOG_E("setFilterMode: no sampler for mode %d; keeping mode %d", mode, filterMode); return; }
    const VkSampler old = sampler;
    sampler = fresh;
    filterMode = mode;
    auto updateDS=[&](VkDescriptorSet ds, VkImageView view){
        if(ds==VK_NULL_HANDLE||view==VK_NULL_HANDLE) return;
        VkDescriptorImageInfo dii{}; dii.imageLayout=VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        dii.imageView=view; dii.sampler=sampler;
        VkWriteDescriptorSet wr{}; wr.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        wr.dstSet=ds; wr.dstBinding=0; wr.descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        wr.descriptorCount=1; wr.pImageInfo=&dii;
        vk_.UpdateDescriptorSets(device,1,&wr,0,nullptr);
    };
    
    for (auto& [id,wt]:texMap) updateDS(wt.ds, wt.view);

    for (auto& [ahb,wt]:ahbImportCache) updateDS(wt.ds, wt.view);
    if (cursorDS!=VK_NULL_HANDLE&&cursorView!=VK_NULL_HANDLE) updateDS(cursorDS, cursorView);
    if (old!=VK_NULL_HANDLE) vk_.DestroySampler(device,old,nullptr);   // no descriptor and no frame (device idle) uses it now
    markSceneDirty(); renderWake.notify();
}

void VulkanRendererContext::setSwapRB(bool enabled) {
    std::lock_guard<std::mutex> lk(renderMutex);
    if (swapRB == enabled) return;
    swapRB = enabled;
    RLOG("setSwapRB: %d", (int)swapRB);


}

void VulkanRendererContext::setEffect(int effectId, float sharpness, int effectMask, float brightness, float contrast, float gamma) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    activeEffectId = effectId;
    activeSharpness = std::max(0.0f, std::min(1.0f, sharpness));
    activeEffectMask = effectMask;
    activeBrightness = std::max(-1.0f, std::min(1.0f, brightness));
    activeContrast = std::max(-1.0f, std::min(1.0f, contrast));
    activeGamma = std::max(0.1f, std::min(4.0f, gamma));
    RLOG("setEffect: id=%d sharpness=%.3f mask=%d brightness=%.3f contrast=%.3f gamma=%.3f",
        activeEffectId, activeSharpness, activeEffectMask, activeBrightness, activeContrast, activeGamma);
    markSceneDirty();
    renderWake.notify();
}

void VulkanRendererContext::setPresentMode(VkPresentModeKHR mode) {
    std::unique_lock<std::shared_mutex> frameLock(frameMutex);
    bool supported = false;
    for (auto pm : availablePresentModes) if (pm == mode) { supported = true; break; }
    VkPresentModeKHR target = supported ? mode : VK_PRESENT_MODE_FIFO_KHR;
    RLOG("setPresentMode: requested=%d supported=%d -> applying=%d",
        (int)mode, (int)supported, (int)target);
    if (requestedPresentMode==target) { RLOG("setPresentMode: already set, skipping"); return; }
    requestedPresentMode=target;
    fbResized.store(true); renderWake.notify();
}

#pragma GCC diagnostic pop
