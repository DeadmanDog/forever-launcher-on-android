#pragma once
#include "RenderWakeSignal.h"
#include <vulkan/vulkan.h>
#include <list>
#include <memory>
#include <vulkan/vulkan_android.h>
struct VkTable {

    PFN_vkCreateInstance CreateInstance;

    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;

    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkCreateRenderPass CreateRenderPass;
    PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer;
    PFN_vkDestroyFramebuffer DestroyFramebuffer;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkFlushMappedMemoryRanges FlushMappedMemoryRanges;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdDraw CmdDraw;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport;
    PFN_vkCmdSetScissor CmdSetScissor;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdBlitImage CmdBlitImage;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateSemaphore CreateSemaphore;
    PFN_vkDestroySemaphore DestroySemaphore;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    PFN_vkGetFenceStatus GetFenceStatus;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
    PFN_vkGetRefreshCycleDurationGOOGLE GetRefreshCycleDurationGOOGLE;
    PFN_vkGetPastPresentationTimingGOOGLE GetPastPresentationTimingGOOGLE;
};

#include <android/log.h>
#include "../logging_disabled.h"
#include <string>
#define WLOG_TAG "Winlator_Renderer"
#define RLOG(...) if(verboseLog) __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,__VA_ARGS__)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,WLOG_TAG,__VA_ARGS__)
#define RLOG_W(...) __android_log_print(ANDROID_LOG_WARN,WLOG_TAG,__VA_ARGS__)
#define SCANOUT_LOG(...) __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Scanout",__VA_ARGS__)

#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vector>
#include <unordered_map>
#include <thread>
#include <atomic>
#include <chrono>
#include <mutex>
#include <shared_mutex>
#include <condition_variable>
#include <functional>

static constexpr uint32_t MAX_FRAMES_IN_FLIGHT = 2;


struct WindowPushConstants {
    float ndcX0, ndcY0, ndcX1, ndcY1;
    int   useTexAlpha;
    int   effectId;
    float sharpness;
    float resW;
    float resH;
    int   effectMask;
    float brightness;
    float contrast;
    float gamma;
    float outW;   // on-screen quad width  in pixels (for FSR/EASU upscale ratio)
    float outH;   // on-screen quad height in pixels
    int   rotation; // clockwise quarter turns of the pre-rotated swapchain (vertex shader only)
    float uvX0, uvY0, uvX1, uvY1; // the part of the texture the quad shows (vertex shader only); 0,0,1,1 = all of it
};

class VulkanRendererContext {
public:
    VulkanRendererContext(ANativeWindow* window, int cWidth, int cHeight, void* adrenotoolsHandle = nullptr);
    ~VulkanRendererContext();

    void onSurfaceResized(int width, int height);
    void setTransform(float ox, float oy, float sx, float sy);
    void updatePointerPosition(short x, short y);
    void updateWindowContent(int64_t id, void* pixels, short w, short h, short stride, int offX, int offY, int winW = 0, int winH = 0);
    void updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short w, short h, int offX, int offY, int winW = 0, int winH = 0, int64_t idleToken = 0);
    /** The JNI layer's way to hand a Present buffer back to its client (called from the render thread, outside renderMutex). */
    void setIdleSink(std::function<void(const std::vector<int64_t>&)> sink);
    void updateCursorImage(void* pixels, short w, short h, short hotX, short hotY);
    void setCursorVisible(bool visible);
    void setRenderList(const int64_t* ids, const int* xs, const int* ys, int count);
    void removeWindow(int64_t id);
    void clearBackbuffer() {}
    void beginBatch() {}
    void endBatch() {}
    void initScanout();
    void destroyScanout();
    void applyScanoutBuffer();
    void initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin);
    void scanoutSetDst(int x, int y, int w, int h);
    void scanoutSetBuffer(AHardwareBuffer* ahb, int x, int y, int w, int h, int fenceFd = -1);
    void scanoutSetCursorImage(void* pixels, short w, short h, short stride);
    void scanoutSetCursorPos(short x, short y, short hotX, short hotY);
    std::atomic<bool> scanoutActive{false};
    std::atomic<bool> displayPriorityHint{false};
    std::atomic<bool> adpfHint{false};
    std::atomic<int64_t> hintTargetNs{16666667};
    // The frame limit's interval (0 = no limit): a shown game frame that stays on screen longer than it is counted as late.
    std::atomic<int64_t> frameIntervalNs{0};
    // The FIFO latency drain (below) is a player choice, off by default: off keeps the queue as it fills (about 60 ms at 60 Hz,
    // never a skipped frame). kDrainOneStep takes queued frames out after a hitch and stops at the first level that worked;
    // kDrainProbe also tries one step lower each time (the lower level is kept if it holds, else learned as the floor).
    static constexpr int kDrainOff = 0, kDrainOneStep = 1, kDrainProbe = 2;
    std::atomic<int> fifoDrainMode{kDrainOff};
    // The frame log is written only while the diagnostics read it (set from Java per session); the functional timing does not depend on it.
    std::atomic<bool> frameTraceEnabled{false};
    // What the panel really showed since the previous call (VK_GOOGLE_display_timing; all zero without it):
    // {timing available, game frames shown, game frames never shown, late game frames, longest shown game-frame interval in us,
    //  refresh period in us, average present-to-screen time of game frames in us, queued frames skipped (FIFO),
    //  game frames the swapchain never reported (missing or stale history: not counted as dropped), skips presented after all
    //  (the hold ended without a new game frame), drain floor in us, last latency window median in us, last window state}.
    static constexpr int kDisplayStats = 13;
    enum WindowState { kWindowNone = 0, kWindowClean, kWindowDirty, kWindowSlow, kWindowHold, kWindowSettle, kWindowRefilled };
    // Per game frame, oldest first: {present id, kind, content generation, content updates since the previous present,
    // last content update ns, queue-present ns, actual display ns (0 if never shown), present margin ns}, all CLOCK_MONOTONIC.
    // kind: 1 shown, 2 dropped (invalid time), 3 missing (never reported), 4 skipped by the drain.
    static constexpr int kFrameLogFields = 8;
    static constexpr int kFrameLogRows = 2048;
    int takeFrameLog(int64_t* out, int maxRows);
    void takeDisplayStats(int64_t out[kDisplayStats]);
    // Where game frames finish inside a refresh: per window of kPhaseWindow shown game frames, the circular mean of the time
    // from the compositor frame being ready to the panel taking it ({window number, mean ns, concentration 0..1000, refresh ns}).
    // The vsync pacer shifts its releases with it so frames land mid-refresh instead of on the edge.
    static constexpr int kPhaseWindow = 60;
    void phaseStats(int64_t out[4]);
    std::atomic<bool> gameFrameDelivered{false};
    std::atomic<bool> surfaceDetached{false};

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);

    bool verboseLog = true;
    void setVerboseLog(bool v) { verboseLog = v; }
    void dumpRendererInfo();

    std::string adrenoDriverPath;
    std::string adrenoDriverName;
    std::string adrenoNativeLibDir;
    void* vulkanHandle = nullptr;
    std::atomic<bool> scanoutBlackFrameDone{false};
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkTable vk_ = {};
    void loadCustomDriver();
    void loadInstanceDispatch();
    void loadDeviceDispatch();

    void setFilterMode(int mode);
    void setSwapRB(bool enabled);
    void setEffect(int effectId, float sharpness, int effectMask, float brightness, float contrast, float gamma);
    void setPresentMode(VkPresentModeKHR mode);

private:
    struct CpuUpload {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        void* mapped = nullptr;
        VkDeviceSize capacity = 0;
    };

    struct WinTex {
        VkImage              img            = VK_NULL_HANDLE;
        VkDeviceMemory       mem            = VK_NULL_HANDLE;
        VkImageView          view           = VK_NULL_HANDLE;
        VkDescriptorSet      ds             = VK_NULL_HANDLE;
        VkBuffer             stg            = VK_NULL_HANDLE;
        VkDeviceMemory       stgMem         = VK_NULL_HANDLE;
        void*                mapped         = nullptr;
        VkDeviceSize         cap            = 0;
        int                  w              = 0;
        int                  h              = 0;
        bool                 dirty          = false;
        bool                 isAHB          = false;
        bool                 needsTransition = false;
        AHardwareBuffer*     ahb            = nullptr;
        std::shared_ptr<std::vector<uint8_t>> pixels;
        CpuUpload uploads[MAX_FRAMES_IN_FLIGHT]{};
        uint64_t revision = 0;
        // Where the picture sits inside THIS window (Present xOff/yOff) and how big the window is. Per window, never per buffer:
        // two windows can show the same buffer differently. winW/winH == 0: unknown, the picture fills the window origin.
        int presentX = 0, presentY = 0, winW = 0, winH = 0;
        // A retirement entry that carries no GPU resource, only the promise "tell the client this Present buffer is free" (0: none).
        int64_t idleToken = 0;
    };

    struct RenderEntry { int64_t id; int x, y; };
    struct DrawEntry {
        VkImage         img            = VK_NULL_HANDLE;
        VkDescriptorSet ds             = VK_NULL_HANDLE;
        VkBuffer        upload         = VK_NULL_HANDLE;
        int             x=0, y=0, w=0, h=0;
        bool            needsTransition = false;
        bool            isAHB          = false;
        AHardwareBuffer* ahb           = nullptr;
        int64_t id = 0;
        uint64_t revision = 0;
        int tw = 0, th = 0;                         // size of the whole texture (x,y,w,h are the visible part of it)
        float u0 = 0.f, v0 = 0.f, u1 = 1.f, v1 = 1.f;
    };

    ANativeWindow* window;
    int surfaceWidth, surfaceHeight, containerWidth, containerHeight;
    void* adrenotoolsHandle = nullptr;
    int filterMode = 0;
    bool swapRB = false;
    int activeEffectId = 0;
    float activeSharpness = 1.0f;
    int activeEffectMask = 0;
    float activeBrightness = 0.0f;
    float activeContrast = 0.0f;
    float activeGamma = 1.0f;
    float maxAnisotropy           = 1.0f;
    bool  cubicSupported          = false;
    VkPhysicalDeviceMemoryProperties memProperties{};
    VkPresentModeKHR requestedPresentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t graphicsQueueFamilyIndex = 0;
    std::vector<VkPresentModeKHR> availablePresentModes;

    std::unordered_map<int64_t, WinTex>         texMap;

    std::unordered_map<AHardwareBuffer*, WinTex>              ahbImportCache;
    std::unordered_map<int64_t, std::vector<AHardwareBuffer*>> windowAhbs;
    std::unordered_map<AHardwareBuffer*, uint32_t> ahbOwnerCounts;
    // How close the imports came to their limits (renderMutex): logged when the renderer ends, so a play session shows the real numbers.
    size_t ahbPeakPerWindow = 0, ahbPeakTotal = 0, ahbEvictions = 0;

    std::vector<WinTex>    deleteQueue;

    // ---- Present buffer hand-back (experimental): a client is told a buffer is free only after the frames that may read it are done.
    // A token (made by the Java side) rides the same retirement queues as retired images: it is released when the frame that
    // was recorded after the buffer was superseded - and so every earlier frame - has completed on the GPU.
    std::unordered_map<int64_t, std::vector<int64_t>> windowIdleTokens;  // window id -> tokens of the picture it shows (renderMutex)
    std::vector<int64_t> idleReleased;                                    // tokens whose frames are done, not yet delivered (renderMutex)
    std::function<void(const std::vector<int64_t>&)> idleSink;           // set by the JNI layer; called outside renderMutex
    std::mutex idleSinkMutex;
    std::atomic<bool> idleSinkInstalled{false};                           // lock-free "is the hand-back on": the render loop skips every token check while it is off
    void retireIdleTokens(std::vector<int64_t>& tokens);                 // renderMutex held; empties [tokens]
    void drainFinishedOtherSlots();                                       // frees what a finished other slot holds (cheap check)
    void pollIdleTokens();                                                // like the above for ALL slots; used when nothing else wakes the render thread
    bool hasIdleTokensInFlight();                                         // a submitted frame still carries hand-back tokens, or some wait to be delivered
    void deliverReleasedIdle();                                           // renderMutex NOT held
    std::vector<WinTex>    pendingRetirements;
    std::vector<WinTex>    submittedRetirements[MAX_FRAMES_IN_FLIGHT];
    std::vector<RenderEntry> renderList;

    std::vector<DrawEntry>             frameDraws;
    std::vector<VkImageMemoryBarrier>  frameAhbTransitions;
    std::vector<VkImageMemoryBarrier>  framePreUpload;
    std::vector<VkImageMemoryBarrier>  framePostUpload;

    void*  scanoutGameSC      = nullptr;
    void*  scanoutCursorSC    = nullptr;
    void*  scanoutCursorBuf   = nullptr;
    int32_t scanoutCursorBufW = 0;
    int32_t scanoutCursorBufH = 0;

    void*  scanoutTx          = nullptr;
    void*  scanoutGameTx      = nullptr;

    ARect  scanoutLastSrc{}, scanoutLastDst{};
    bool   scanoutGeoDirty    = true;
    bool   scanoutVisShown    = false;
    bool   scanoutApiLoaded   = false;
    void*  fnSCCreateFromWin  = nullptr;
    void*  fnSCRelease        = nullptr;
    void*  fnSTCreate         = nullptr;
    void*  fnSTDelete         = nullptr;
    void*  fnSTApply          = nullptr;
    void*  fnSTSetBuffer      = nullptr;
    void*  fnSTSetZOrder      = nullptr;
    void*  fnSTSetVisibility  = nullptr;
    void*  fnSTSetGeometry    = nullptr;
    void*  fnSTSetBackPressure = nullptr;
    bool   loadScanoutApi();

    int32_t scanoutDstX=0, scanoutDstY=0, scanoutDstW=0, scanoutDstH=0;

    int32_t lastDstX=0, lastDstY=0, lastDstW=0, lastDstH=0;
    bool    gameScVisible      = false;

    struct ScanoutPending { AHardwareBuffer* ahb=nullptr; int x=0,y=0,w=0,h=0; int fenceFd=-1; };
    std::mutex        scanoutMutex;
    std::recursive_mutex scanoutLifeMutex; // guards SurfaceControl/transaction lifetime vs. destroyScanout
    ScanoutPending    scanoutPending{};
    std::atomic<bool> scanoutPendingDirty{false};

    short  pendingCursorX=0, pendingCursorY=0, pendingCursorHotX=0, pendingCursorHotY=0;
    bool   cursorPosDirty=false;
    bool   cursorImageDirty=false;

    std::atomic<int>  pointerX{0}, pointerY{0};
    float sceneOffsetX=0.f, sceneOffsetY=0.f, sceneScaleX=1.f, sceneScaleY=1.f;

    std::atomic<bool> cursorVisible{false};
    short  cursorHotX=0, cursorHotY=0, cursorTexW=0, cursorTexH=0;
    // The cursor image itself (cursorTexW/H is the texture, reserved at least kCursorCapacity so a size change needs no new
    // GPU resources and never waits for in-flight frames under the X server locks).
    short  cursorW=0, cursorH=0;
    static constexpr short kCursorCapacity = 256;
    static constexpr short kCursorEdgePad = 2;
    std::vector<uint32_t>  cursorPixels;
    std::atomic<bool> isCursorImageDirty{false};
    // Counts staged cursor images: an image staged while a frame was uploading the previous one must stay dirty.
    std::atomic<uint64_t> cursorStageGen{0};
    uint64_t cursorSnapshotGen = 0;
    std::atomic<bool> cursorMoved{false};

    VkImage         cursorImg   = VK_NULL_HANDLE;
    VkDeviceMemory  cursorMem   = VK_NULL_HANDLE;
    VkImageView     cursorView  = VK_NULL_HANDLE;
    VkDescriptorSet  cursorDS   = VK_NULL_HANDLE;
    struct CursorStaging {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        void* mapped = nullptr;
        VkDeviceSize capacity = 0;
    };
    CursorStaging cursorStaging[MAX_FRAMES_IN_FLIGHT]{};
    bool cursorNeedsTransition = false;

    VkInstance       instance       = VK_NULL_HANDLE;
    VkSurfaceKHR     surface        = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice         device         = VK_NULL_HANDLE;
    VkQueue          graphicsQueue  = VK_NULL_HANDLE;
    VkSwapchainKHR   swapchain   = VK_NULL_HANDLE;
    VkFormat         swapchainFmt;
    VkExtent2D       swapchainExt;          // image size, in the panel's own orientation
    VkExtent2D       swapchainLogicalExt{}; // the same surface as the app sees it (unrotated)
    VkSurfaceTransformFlagBitsKHR swapchainPreTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    uint32_t         swapchainRotation = 0;

    std::vector<VkImage>       swapchainImages;
    std::vector<VkImageView>   swapchainViews;
    std::vector<VkFramebuffer> swapchainFBs;


    VkRenderPass          renderPass  = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsLayout    = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout  = VK_NULL_HANDLE;

    VkPipeline            pipeline    = VK_NULL_HANDLE;

    VkCommandPool                cmdPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> cmdBufs;

    std::vector<VkSemaphore> imgAvailSems;
    std::vector<VkFence>     inFlightFences;
    std::vector<VkFence>     imgInFlight;
    uint32_t                 currentFrame = 0;

    std::vector<VkSemaphore> swapchainRenderFinished;

    VkSampler        sampler    = VK_NULL_HANDLE;
    VkDescriptorPool winTexPool = VK_NULL_HANDLE;

    std::atomic<bool> needsRender{false};
    // Present on change: every state change a frame must show bumps sceneGen (game window content also bumps contentGen). A
    // wake-up that finds the presented generation, pointer and cursor image unchanged acquires and presents nothing.
    std::atomic<uint64_t> sceneGen{1};
    std::atomic<uint64_t> contentGen{0};
    void markSceneDirty() { sceneGen.fetch_add(1, std::memory_order_acq_rel); needsRender.store(true, std::memory_order_release); }
    uint64_t presentedGen = UINT64_MAX;          // render thread
    uint64_t presentedContentGen = 0;            // render thread
    int presentedPtrX = INT32_MIN, presentedPtrY = INT32_MIN;
    std::atomic<int64_t> lastPresentNs{0};       // CLOCK_MONOTONIC of the last present
    int64_t lastGamePresentNs = 0;              // render thread: the last present that carried new game content
    bool frameUnchanged();

    bool displayTimingSupported = false;
    // Written on swapchain creation (render thread, or reattach under frameMutex), read by the render loop before it takes frameMutex.
    std::atomic<VkPresentModeKHR> activePresentMode{VK_PRESENT_MODE_FIFO_KHR};
    std::atomic<int64_t> refreshPeriodNs{0};
    int64_t refreshQueriedNs = 0;
    uint32_t nextPresentId = 1;
    uint32_t lastTimingId = 0;
    uint64_t lastShownGameNs = 0;
    int64_t shownGapAvgNs = 0;
    uint8_t presentWasGame[256] = {};
    std::atomic<int64_t> statShown{0}, statDropped{0}, statLate{0}, statMaxIntervalNs{0};
    std::atomic<int64_t> statMissing{0}, statDrainReplays{0};
    std::atomic<int64_t> exportFloorNs{0}, exportWindowNs{0}, exportWindowState{0};
    // Content arrival, for the frame log: the last window content update and how many came since the previous present.
    std::atomic<int64_t> lastContentNs{0};
    int64_t presentContentNs[256] = {};
    uint64_t presentContentGenSlot[256] = {};
    int presentContentUpdates[256] = {};
    std::mutex frameLogMutex;
    int64_t frameLog[kFrameLogRows][kFrameLogFields] = {};
    int frameLogHead = 0, frameLogCount = 0;
    void logFrame(int64_t id, int64_t kind, uint32_t slot, int64_t displayNs, int64_t marginNs);
    // Present-to-screen time of game frames: count in bits 48-62, sum in microseconds in bits 0-47, so one exchange reads a
    // matching pair.
    static constexpr int kLatencyCountShift = 48;
    std::atomic<int64_t> statLatencyPacked{0};
    std::atomic<int64_t> statDrains{0};
    // FIFO with the game at exactly the panel rate: every repeated refresh (a hitch) leaves one more frame in the queue for good,
    // each adding a refresh to the time to the screen; only a frame that is not presented takes one out. Per window of
    // kLatencyWindow shown game frames the median present-to-screen time is compared with the lowest window seen (the queue
    // empty). Two windows in a row at least 3/4 refresh above it skip one new game frame; three windows later the result is
    // checked: if the time did not drop by half a refresh the queue was empty after all, and that level becomes the floor, so a
    // wrong floor costs one skipped frame once. A window with a hitch in it (a game frame never shown or shown late) counts for
    // nothing: a hitch empties and refills the queue by itself and hid the effect of a skip (1.3.36 measurement). A hitch within
    // kRefillWatchWindows after a skip that worked means the emptier queue does not hold in this scene (frames finish too close
    // to the deadline, 1.3.38 measurement: a repeated frame every 10-12 s at 30 ms, none at 47): the level it refills to becomes
    // the floor, so the queue keeps the one frame that makes the picture smooth.
    static constexpr int kLatencyWindow = 60;
    int64_t latencySamples[kLatencyWindow] = {};
    int latencyCount = 0;
    int64_t latencyFloorNs = 0;
    int highWindows = 0;
    int drainHoldWindows = 0;
    int64_t latencyBeforeDrainNs = 0;
    bool drainOne = false;
    bool latencyWindowDirty = false;
    int64_t latencyWindowStartNs = 0;
    // After a hitch or a slow (loading) stretch, this many clean windows pass before the floor or a skip is decided.
    static constexpr int kSettleWindows = 2;
    int cleanWindows = kSettleWindows;
    // The content a skip left out: until new game content arrives (or two refreshes pass) no other wake-up presents it.
    uint64_t drainedContentGen = 0;
    int64_t drainHoldUntilNs = 0;
    static constexpr int kRefillWatchWindows = 20;
    int refillWatchWindows = 0;
    bool refilled = false;
    void latencyWindowDone(int64_t medianNs, int64_t refreshNs, bool dirty, bool slow);
    bool drainQueuedFrame();
    bool holdDrainedContent();
    int64_t presentQueuedNs[256] = {};
    uint32_t presentMetadataId[256] = {};   // the present a slot's metadata belongs to (slots are reused every 256 presents)
    double phaseSumCos = 0, phaseSumSin = 0;
    int phaseFrames = 0;
    std::atomic<int64_t> phaseWindow{0}, phaseMeanNs{0}, phaseConcentration{0}, phaseRefreshNs{0};
    // Odd while a window's fields are being written: a reader takes a snapshot only from one even, unchanged value.
    std::atomic<uint64_t> phaseSequence{0};
    void refreshCycleDuration(int64_t nowNs);
    void collectPresentTimings();
    std::thread       renderThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> fbResized{false};
    std::mutex        renderMutex;
    RenderWakeSignal renderWake;
    mutable std::shared_mutex frameMutex;

    void createInstance();
    bool recoverAcquiredFrame(const VkSemaphore* acquired, uint32_t count) noexcept;
    void commitAhbTransitions();
    bool waitForFrameFence(VkFence fence);
    void createSurface();
    void pickPhysicalDevice();
    void createLogicalDevice();
    void createSwapchain();
    void createRenderPass();
    void createDSLayout();
    void createPipeline(bool blend, VkPipeline& out);
    void createFramebuffers();
    void createCmdPool();
    void createSampler();
    void createWinTexPool();
    void createCursorDS();
    void createCmdBufs();
    void createSyncObjects();
    void cleanupSwapchain();

    bool  createWinTexResources(WinTex& wt, int w, int h);
    VkBuffer prepareCpuUpload(WinTex& wt);
    void appendWindowDraw(const RenderEntry& entry);
    void commitCpuUploads();
    bool  importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb);
    void  destroyWinTex(WinTex& wt);
    void freeRetiredTextures(std::vector<WinTex>& textures);
    void commitTextureRetirements();
    bool  ensureCursorTex(short w, short h);
    void  cleanupCursorTex();
    void  noteSlowCursorUpdate(std::chrono::steady_clock::time_point started, short w, short h, bool resources);
    void  ensureCursorStaging(VkDeviceSize sz);
    void  commitCursorUpload();
    // Drops one window's claim on an imported buffer; the import is retired (deferred) when no window owns it any more.
    void  releaseAhbOwnership(AHardwareBuffer* ahb);
    // Releases whatever exists, in destruction order; safe on a half-constructed object. The window and the driver handle
    // belong to the JNI caller until construction has succeeded, so only the destructor passes releaseExternal.
    void  teardown(bool releaseExternal);

    static VkSurfaceTransformFlagBitsKHR choosePreTransform(const VkSurfaceCapabilitiesKHR& caps, uint32_t& rotation);
    bool swapchainNeedsRotationUpdate();

    void recordCmdBuf(VkCommandBuffer cb, VkFramebuffer targetFb, VkRenderPass targetRp,
        const std::vector<DrawEntry>& draws,
        std::vector<VkImageMemoryBarrier>& ahbTransitions,
        std::vector<VkImageMemoryBarrier>& preUpload,
        std::vector<VkImageMemoryBarrier>& postUpload,
        VkBuffer cursorUpload, bool hasCursorUpload,
        float ox, float oy, float sx, float sy, float cw, float ch,
        short ptrX, short ptrY, short curHotX, short curHotY,
        short curW, short curH, short curUpW, short curUpH, short curTexW, short curTexH, bool curVis);
    void renderLoop();
    void renderFrame();

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    VkShaderModule  makeShader(const uint32_t* code, size_t sz);
};
