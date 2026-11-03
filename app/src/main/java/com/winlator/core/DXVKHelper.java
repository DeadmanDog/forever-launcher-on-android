package com.winlator.core;

import android.content.Context;

import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;

public class DXVKHelper {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("DXVK_STATE_CACHE_PATH", imageFs.getRootDir().getAbsolutePath() + ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        envVars.put("DXVK_CONFIG", buildConfig(config));
        applyRuntimeOptions(config, envVars);
    }

    /** DXVK_CONFIG is a raw environment value, not a shell-quoted multiline file. */
    public static String buildConfig(KeyValueSet config) {
        String content = "";
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+"\n";
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+"\n";
        }

        String maxFeatureLevel = config.get("maxFeatureLevel");
        if (!maxFeatureLevel.isEmpty() && !maxFeatureLevel.equals("0")) {
            content += "d3d11.maxFeatureLevel = "+maxFeatureLevel+"\n";
        }


        String customDevice = config.get("customDevice");
        String[] parts = customDevice.split(":", 3);
        if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty()) {
            content = (((((content + "dxgi.customDeviceId = " + parts[0] + "\n") + "dxgi.customVendorId = " + parts[1] + "\n") + "d3d9.customDeviceId = " + parts[0] + "\n") + "d3d9.customVendorId = " + parts[1] + "\n") + "dxgi.customDeviceDesc = \"" + parts[2] + "\"\n") + "d3d9.customDeviceDesc = \"" + parts[2] + "\"\n";
        }
        if (config.getBoolean("constantBufferRangeCheck")) {
            content = content + "d3d11.constantBufferRangeCheck = \"True\"\n";
        }

        return content.replace("\n", ";");
    }

    private static void applyRuntimeOptions(KeyValueSet config, EnvVars envVars) {
        String featureLevel = config.get("maxFeatureLevel");
        if (!featureLevel.isEmpty() && !featureLevel.equals("0"))
            envVars.put("DXVK_FEATURE_LEVEL", featureLevel);
        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0"))
            envVars.put("DXVK_FRAME_RATE", framerate);
        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0")) envVars.put("DXVK_ASYNC", "1");
        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0")) envVars.put("DXVK_GPLASYNCCACHE", "1");
        // Do not advertise a nonexistent Unix config path to a Windows DLL.
        // Explicit user DXVK_CONFIG / DXVK_CONFIG_FILE overrides are merged later.
    }

    public static void setVKD3DEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        String featureLevel = config.get("vkd3dFeatureLevel", "12_1");
        envVars.put("VKD3D_FEATURE_LEVEL", featureLevel);
    }
}
