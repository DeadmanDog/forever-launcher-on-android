package com.winlator.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class GPUHelper {
    public static int VK_API_VERSION_1_3 = vkMakeVersion(1, 3, 0);

    static {
        System.loadLibrary("winlator");
    }

    private static final ExecutorService io =
            Executors.newSingleThreadExecutor();        // created once

    // start the work immediately; runs exactly once, then the worker thread ends (it used to wait for ever for work that never comes)
    private static final CompletableFuture<Integer> apiVersionFuture =
            CompletableFuture.supplyAsync(GPUHelper::vkGetApiVersion, io).whenComplete((version, error) -> io.shutdown());

    // Note: Removed @CriticalNative to allow proper JNI error handling and logging
    // in the native implementation. Performance is not impacted since this is
    // called asynchronously on a background thread.
    public static native int vkGetApiVersion();

    public static int vkGetApiVersionSafe() {
        try {
            return apiVersionFuture.getNow(VK_API_VERSION_1_3);
        } catch (CompletionException ex) {
            return VK_API_VERSION_1_3;
        }
    }

    public static int vkVersionPatch(){
        try {
            return vkGetApiVersionSafe() & 0xFFF;
        } catch (UnsatisfiedLinkError e) {
            return 0; // Fallback if library not loaded
        } catch (Exception e) {
            return 0; // Fallback for any other error
        }
    }

    public static int vkMakeVersion(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | patch;
    }

}
