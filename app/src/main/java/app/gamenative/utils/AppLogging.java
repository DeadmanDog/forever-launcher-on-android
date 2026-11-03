package app.gamenative.utils;

/** Silence prebuilt native libraries in this process before runtime initialization. */
public final class AppLogging {
    private AppLogging() { }
    public static void disable() {
        try {
            System.loadLibrary("wow_logging_control");
            disableNative();
        } catch (UnsatisfiedLinkError ignored) {
            // API/source diagnostics are still removed when native control is unavailable.
        }
    }
    /** Last launch-local step: stored runtime settings and cache locations stay intact. */
    public static void quietRuntimeEnv(com.winlator.core.envvars.EnvVars env) {
        env.put("WINEDEBUG", "-all");
        env.put("WINE_LOG_PEB_DATA", "0");
        env.put("DXVK_LOG_LEVEL", "none");
        env.put("VKD3D_DEBUG", "none");
        env.put("VKD3D_SHADER_DEBUG", "none");
        // Keep optional DLL-side files/buffers out of the bounded diagnostic pipe.
        env.remove("VKD3D_LOG_FILE");
        env.remove("VKD3D_LOG_BUFFERED");
        env.put("GN_OVERLAY_DEBUG", "0");
        env.remove("GN_OVERLAY_LOG");
    }
    /** Scoped runtime diagnostics: no tracing, PEB/environment dumps or emulator verbosity. */
    public static void runtimeEnv(com.winlator.core.envvars.EnvVars env, boolean enabled) {
        quietRuntimeEnv(env);
        if (enabled) {
            // +loaddll: one line per loaded DLL, so a session shows which runtime modules WoW really uses.
            env.put("WINEDEBUG", "-all,err+all,warn+seh,+loaddll");
            env.put("DXVK_LOG_LEVEL", "warn");
            env.put("VKD3D_DEBUG", "warn");
            env.put("VKD3D_SHADER_DEBUG", "warn");
            // Route DXVK to the captured stderr pipe; avoid unbounded DLL-side log files.
            env.put("DXVK_LOG_PATH", "none");
        }
    }
    public static void setNativeEnabled(boolean enabled, boolean detailed) {
        try {
            System.loadLibrary("wow_logging_control");
            configureNative(enabled, detailed);
        } catch (UnsatisfiedLinkError ignored) { }
    }
    public static void runtimeEnv(com.winlator.core.envvars.EnvVars env, boolean enabled, boolean detailed) {
        runtimeEnv(env, enabled);
        if (enabled && detailed) {
            env.put("WINEDEBUG", "-all,err+all,warn+all,+seh,+loaddll,+timestamp,+pid");
            env.put("DXVK_LOG_LEVEL", "debug");
            env.put("VKD3D_DEBUG", "trace");
            env.put("VKD3D_SHADER_DEBUG", "trace");
        }
    }
    /** Audio diagnostics (v1.0.1 beta3): adds the Wine audio channels (winepulse, mmdevapi) to the diagnostic WINEDEBUG. */
    public static void runtimeEnv(com.winlator.core.envvars.EnvVars env, boolean enabled, boolean detailed, boolean audio) {
        runtimeEnv(env, enabled, detailed);
        if (enabled && audio) env.put("WINEDEBUG", env.get("WINEDEBUG") + ",+pulse,+mmdevapi,+timestamp");
    }
    private static native void configureNative(boolean enabled, boolean detailed);
    private static native void disableNative();
}
