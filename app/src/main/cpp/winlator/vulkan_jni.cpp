#include <jni.h>
#include <pthread.h>
#include <new>
#include <memory>
#include <vector>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include "../extras/adrenotools/include/adrenotools/driver.h"
#include "VulkanRendererContext.h"

static void* openAdrenotoolsDriver(const char* driverPath, const char* libraryName, const char* nativeLibDir) {
    if (!driverPath || !libraryName || !nativeLibDir) return nullptr;
    if (access(driverPath, F_OK) != 0) {
        __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer",
            "openAdrenotoolsDriver: driverPath not accessible: %s", driverPath);
        return nullptr;
    }
    char tmpdir[512];
    snprintf(tmpdir, sizeof(tmpdir), "%stemp", driverPath);
    mkdir(tmpdir, S_IRWXU | S_IRWXG);
    __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Renderer",
        "openAdrenotoolsDriver: driverPath=%s lib=%s nativeLibDir=%s tmp=%s",
        driverPath, libraryName, nativeLibDir, tmpdir);
    setenv("ADRENOTOOLS_DRIVER_PATH", driverPath, 1);
    setenv("ADRENOTOOLS_DRIVER_NAME", libraryName, 1);
    setenv("ADRENOTOOLS_HOOKS_PATH", nativeLibDir, 1);
    const char* redirectDir = getenv("ADRENOTOOLS_REDIRECT_DIR");
    int featureFlags = ADRENOTOOLS_DRIVER_CUSTOM;
    if (redirectDir && redirectDir[0] != '\0') {
        featureFlags |= ADRENOTOOLS_DRIVER_FILE_REDIRECT;
    } else {
        unsetenv("ADRENOTOOLS_DRIVER_FILE_REDIRECT");
    }
    void* handle = adrenotools_open_libvulkan(
        RTLD_LOCAL | RTLD_NOW,
        featureFlags,
        tmpdir,
        nativeLibDir,
        driverPath,
        libraryName,
        (redirectDir && redirectDir[0] != '\0') ? redirectDir : nullptr,
        nullptr);
    if (!handle) {
        __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer",
            "openAdrenotoolsDriver: adrenotools_open_libvulkan failed");
    } else {
        __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Renderer",
            "openAdrenotoolsDriver: SUCCESS handle=%p", handle);
    }
    return handle;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeInit(
    JNIEnv* env, jobject, jobject surface, jint w, jint h,
    jstring jDriverPath, jstring jLibraryName, jstring jNativeLibDir)
{
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return 0;
    void* adrenotoolsHandle = nullptr;
    if (jDriverPath && jLibraryName && jNativeLibDir) {
        // A failed string acquisition leaves a Java exception pending: no further JNI call may be made until it is handled.
        // Stop at the first failure, release what was acquired, give the window back and let the exception reach Java.
        const char* dp  = env->GetStringUTFChars(jDriverPath, nullptr);
        const char* lib = dp ? env->GetStringUTFChars(jLibraryName, nullptr) : nullptr;
        const char* nld = lib ? env->GetStringUTFChars(jNativeLibDir, nullptr) : nullptr;
        if (dp && lib && nld) adrenotoolsHandle = openAdrenotoolsDriver(dp, lib, nld);
        const bool acquired = dp && lib && nld;
        if (nld) env->ReleaseStringUTFChars(jNativeLibDir, nld);
        if (lib) env->ReleaseStringUTFChars(jLibraryName,  lib);
        if (dp)  env->ReleaseStringUTFChars(jDriverPath,   dp);
        if (!acquired) {
            ANativeWindow_release(win);
            return 0;
        }
    }
    try { return reinterpret_cast<jlong>(new VulkanRendererContext(win, w, h, adrenotoolsHandle)); }
    catch (...) {
        ANativeWindow_release(win);
        if (adrenotoolsHandle) dlclose(adrenotoolsHandle);
        return 0;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeResize(JNIEnv*, jobject, jlong h, jint w, jint ht) {
    auto* r=reinterpret_cast<VulkanRendererContext*>(h); if (r) r->onSurfaceResized(w,ht);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDestroy(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<VulkanRendererContext*>(h);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeUpdateWindowContent(
    JNIEnv* env, jobject, jlong handle, jlong id, jobject buf, jshort w, jshort h, jshort stride, jint offX, jint offY, jint winW, jint winH)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r||!buf) return;
    void* px=env->GetDirectBufferAddress(buf);
    if (w<=0 || h<=0) return;
    const jlong rowPx = stride>0 ? (jlong)stride : (jlong)w;
    if (px && env->GetDirectBufferCapacity(buf)>=((jlong)(h-1)*rowPx+w)*4)
        r->updateWindowContent(id,px,w,h,stride,offX,offY,winW,winH);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeUpdateWindowContentAHB(
    JNIEnv*, jobject, jlong handle, jlong id, jlong ahbPtr, jshort w, jshort h, jint offX, jint offY, jint winW, jint winH, jlong idleToken)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r||!ahbPtr) return;
    // A C++ exception must never leave a JNI function (it would abort the process): out-of-memory here only skips this frame's update.
    try { r->updateWindowContentAHB(id,reinterpret_cast<AHardwareBuffer*>(ahbPtr),w,h,offX,offY,winW,winH,idleToken); }
    catch (...) { __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer","AHB window update failed (out of memory); previous content kept"); }
}
// ---- Present buffer hand-back: render thread -> Java VulkanRenderer.onPixmapIdle(long) ----
namespace {
JavaVM* g_idleVm = nullptr;
pthread_key_t g_detachKey;
bool g_detachKeyOk = false;
pthread_once_t g_detachOnce = PTHREAD_ONCE_INIT;
void detachThread(void*) { if (g_idleVm) g_idleVm->DetachCurrentThread(); }
void makeDetachKey() { g_detachKeyOk = pthread_key_create(&g_detachKey, detachThread) == 0; }

// The render thread is a native thread: attach it once, and detach it automatically when it ends. When that cannot be arranged
// (no thread-exit hook) the thread is not attached at all: the hand-back is then skipped and the Java time limit takes over.
JNIEnv* envForThisThread(JavaVM* vm) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    pthread_once(&g_detachOnce, makeDetachKey);
    if (!g_detachKeyOk) return nullptr;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    if (pthread_setspecific(g_detachKey, env) != 0) {
        vm->DetachCurrentThread();
        return nullptr;
    }
    return env;
}

struct IdleBridge {
    JavaVM* vm; jobject target; jmethodID method;
    ~IdleBridge() {
        // Dropped with the context's sink: give the global reference back on a thread that can.
        if (JNIEnv* env = envForThisThread(vm)) env->DeleteGlobalRef(target);
    }
};
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetPresentIdle(JNIEnv* env, jobject thiz, jlong handle, jboolean on) {
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return;
    // A C++ exception must never leave a JNI function (it would abort the process). Out of memory in here only means that no
    // hand-back is installed: the Java side's time limit then returns every Present buffer, as it does for any lost callback.
    try {
        if (!on) { r->setIdleSink(nullptr); return; }
        JavaVM* vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK || !vm) return;
        g_idleVm = vm;
        jclass cls = env->GetObjectClass(thiz);
        jmethodID method = cls ? env->GetMethodID(cls, "onPixmapIdle", "(J)V") : nullptr;
        if (!method) { env->ExceptionClear(); return; }
        jobject target = env->NewGlobalRef(thiz);
        if (!target) { env->ExceptionClear(); return; }
        // From here the global reference has an owner as soon as the IdleBridge exists; if even that cannot be allocated, give
        // it back by hand. reset() deletes the bridge (and so the reference) when its control block cannot be allocated.
        IdleBridge* raw = new (std::nothrow) IdleBridge{vm, target, method};
        if (!raw) { env->DeleteGlobalRef(target); return; }
        std::shared_ptr<IdleBridge> bridge;
        bridge.reset(raw);   // never a temporary: its destructor drops the reference
        r->setIdleSink([bridge](const std::vector<int64_t>& tokens) {
            JNIEnv* e = envForThisThread(bridge->vm);
            if (!e) return;
            for (int64_t token : tokens) {
                e->CallVoidMethod(bridge->target, bridge->method, static_cast<jlong>(token));
                if (e->ExceptionCheck()) e->ExceptionClear();   // a failing hand-back must not take the render thread down
            }
        });
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR,"Winlator_Renderer","Present hand-back not installed (out of memory)");
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetTransform(
    JNIEnv*, jobject, jlong handle, jfloat ox, jfloat oy, jfloat sx, jfloat sy)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle); if (r) r->setTransform(ox,oy,sx,sy);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetPointerPos(JNIEnv*, jobject, jlong handle, jshort x, jshort y) {
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle); if (r) r->updatePointerPosition(x,y);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetCursorVisible(JNIEnv*, jobject, jlong handle, jboolean v) {
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle); if (r) r->setCursorVisible(v);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeUpdateCursorImage(
    JNIEnv* env, jobject, jlong handle, jobject buf, jshort w, jshort h, jshort hotX, jshort hotY)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r||!buf) return;
    void* px=env->GetDirectBufferAddress(buf);
    if (px && env->GetDirectBufferCapacity(buf)>=(jlong)w*h*4)
        r->updateCursorImage(px,w,h,hotX,hotY);
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetRenderList(
    JNIEnv* env, jobject, jlong handle, jlongArray jids, jintArray jxs, jintArray jys, jint count)
{
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r||count<0) return;
    // An empty list is a real state (nothing to composite, e.g. while the game is on the scanout path): clear it.
    if (count==0) { r->setRenderList(nullptr,nullptr,nullptr,0); return; }
    if (!jids||!jxs||!jys) return;
    if (env->GetArrayLength(jids)<count || env->GetArrayLength(jxs)<count || env->GetArrayLength(jys)<count) return;

    // Copy the three arrays out instead of pinning them: no critical section is held while setRenderList waits for the
    // render mutex or allocates, and a failed copy (pending Java exception) stops before any further JNI call.
    try {
        std::vector<int64_t> ids((size_t)count);
        std::vector<jint> xs((size_t)count), ys((size_t)count);
        env->GetLongArrayRegion(jids,0,count,reinterpret_cast<jlong*>(ids.data()));
        if (env->ExceptionCheck()) return;
        env->GetIntArrayRegion(jxs,0,count,xs.data());
        if (env->ExceptionCheck()) return;
        env->GetIntArrayRegion(jys,0,count,ys.data());
        if (env->ExceptionCheck()) return;
        r->setRenderList(ids.data(),xs.data(),ys.data(),count);
    } catch (...) {
        // An allocation failure must not unwind into the JVM; the next scene update resends the list.
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeRemoveWindow(JNIEnv*, jobject, jlong handle, jlong id) {
    auto* r=reinterpret_cast<VulkanRendererContext*>(handle); if (r) r->removeWindow(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeInitScanout(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->initScanout();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDestroyScanout(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->destroyScanout();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeScanoutSetBuffer(
    JNIEnv*, jobject, jlong handle, jlong ahbPtr, jint x, jint y, jint w, jint h, jint fenceFd)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r && ahbPtr) r->scanoutSetBuffer(reinterpret_cast<AHardwareBuffer*>(ahbPtr), x, y, w, h, (int)fenceFd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeScanoutSetCursorImage(
    JNIEnv* env, jobject, jlong handle, jobject buf, jshort w, jshort h, jshort stride)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !buf) return;
    void* px = env->GetDirectBufferAddress(buf);
    if (w<=0 || h<=0) return;
    const jlong rowPx = stride>0 ? (jlong)stride : (jlong)w;
    if (px && env->GetDirectBufferCapacity(buf) >= ((jlong)(h-1)*rowPx+w)*4)
        r->scanoutSetCursorImage(px, w, h, stride);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeScanoutSetCursorPos(
    JNIEnv*, jobject, jlong handle, jshort x, jshort y, jshort hotX, jshort hotY)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->scanoutSetCursorPos(x, y, hotX, hotY);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeIsScanoutActive(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    return r ? (jboolean)r->scanoutActive.load() : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeScanoutSetDst(
    JNIEnv*, jobject, jlong handle, jint x, jint y, jint w, jint h)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->scanoutSetDst(x, y, w, h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetScanoutWindow(
    JNIEnv* env, jobject, jlong handle, jobject gameSurface, jobject cursorSurface)
{
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return;
    ANativeWindow* gw = ANativeWindow_fromSurface(env, gameSurface);
    ANativeWindow* cw = ANativeWindow_fromSurface(env, cursorSurface);
    if (!gw || !cw) {
        if (gw) ANativeWindow_release(gw);
        if (cw) ANativeWindow_release(cw);
        r->initScanout();
        return;
    }
    r->initScanoutFromWindows(gw, cw);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetVerboseLog(JNIEnv*, jobject, jlong handle, jboolean v) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setVerboseLog((bool)v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDumpRendererInfo(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->dumpRendererInfo();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeIsGameFrameDelivered(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    return r ? (jboolean)r->gameFrameDelivered.load() : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetFilterMode(JNIEnv*, jobject, jlong handle, jint mode) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setFilterMode((int)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetSwapRB(JNIEnv*, jobject, jlong handle, jboolean enabled) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setSwapRB(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetPresentMode(JNIEnv*, jobject, jlong handle, jint mode) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setPresentMode((VkPresentModeKHR)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetEffect(
    JNIEnv*, jobject, jlong handle, jint effectId, jfloat sharpness,
    jint effectMask, jfloat brightness, jfloat contrast, jfloat gamma) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->setEffect(
        (int)effectId,
        (float)sharpness,
        (int)effectMask,
        (float)brightness,
        (float)contrast,
        (float)gamma
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDetachSurface(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->detachSurface();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeReattachSurface(JNIEnv* env, jobject, jlong handle, jobject surface) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !surface) return JNI_FALSE;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return JNI_FALSE;
    bool ok = r->reattachSurface(win);
    if (ok && r->scanoutActive.load()) {
        r->destroyScanout();
    }
    return (jboolean)ok;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetSchedulingHints(JNIEnv*, jobject, jlong handle,
        jboolean priority, jboolean adpf, jlong targetNs) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return;
    r->displayPriorityHint.store(priority);
    r->hintTargetNs.store(targetNs > 0 ? targetNs : 16666667);
    r->adpfHint.store(adpf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetFrameInterval(JNIEnv*, jobject, jlong handle, jlong intervalNs) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return;
    r->frameIntervalNs.store(intervalNs > 0 ? intervalNs : 0);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeTakeDisplayStats(JNIEnv* env, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return nullptr;
    int64_t values[VulkanRendererContext::kDisplayStats];
    r->takeDisplayStats(values);
    jlongArray out = env->NewLongArray(VulkanRendererContext::kDisplayStats);
    if (out) env->SetLongArrayRegion(out, 0, VulkanRendererContext::kDisplayStats, reinterpret_cast<const jlong*>(values));
    return out;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativePhaseStats(JNIEnv* env, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return nullptr;
    int64_t values[4];
    r->phaseStats(values);
    jlongArray out = env->NewLongArray(4);
    if (out) env->SetLongArrayRegion(out, 0, 4, reinterpret_cast<const jlong*>(values));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetFifoDrain(JNIEnv*, jobject, jlong handle, jint mode) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->fifoDrainMode.store(mode >= VulkanRendererContext::kDrainOff && mode <= VulkanRendererContext::kDrainProbe
        ? mode : VulkanRendererContext::kDrainOff);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeTakeFrameLog(JNIEnv* env, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r) return nullptr;
    std::vector<int64_t> rows((size_t)VulkanRendererContext::kFrameLogRows * VulkanRendererContext::kFrameLogFields);
    const int count = r->takeFrameLog(rows.data(), VulkanRendererContext::kFrameLogRows);
    const jsize length = count * VulkanRendererContext::kFrameLogFields;
    jlongArray out = env->NewLongArray(length);
    if (out && length) env->SetLongArrayRegion(out, 0, length, reinterpret_cast<const jlong*>(rows.data()));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeSetFrameTrace(JNIEnv*, jobject, jlong handle, jboolean enabled) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->frameTraceEnabled.store(enabled == JNI_TRUE);
}
