package com.winlator.container;

import android.os.Environment;

import com.winlator.core.DefaultVersion;
import com.winlator.core.WineInfo;
import com.winlator.core.WineThemeManager;
import com.winlator.winhandler.WinHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.Locale;

public class Container {
    // External display modes
    public static final String EXTERNAL_DISPLAY_MODE_OFF = "off";
    public static final String EXTERNAL_DISPLAY_MODE_TOUCHPAD = "touchpad";
    public static final String EXTERNAL_DISPLAY_MODE_KEYBOARD = "keyboard";
    public static final String EXTERNAL_DISPLAY_MODE_HYBRID = "hybrid";
    public static final String DEFAULT_EXTERNAL_DISPLAY_MODE = EXTERNAL_DISPLAY_MODE_OFF;

    public static final String DEFAULT_ENV_VARS = "WRAPPER_MAX_IMAGE_COUNT=0 MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB WINEESYNC=0 WINEFSYNC=0 WINENTSYNC=1 PROTON_NO_NTSYNC=0 PULSE_LATENCY_MSEC=40";
    public static final String DEFAULT_SCREEN_SIZE_16_9 = "1280x720";
    public static final String DEFAULT_SCREEN_SIZE_16_10 = "1280x800";
    public static final String DEFAULT_SCREEN_SIZE_4_3 = "1280x960";
    public static final String DEFAULT_GRAPHICS_DRIVER = DefaultVersion.DEFAULT_GRAPHICS_DRIVER;
    public static final String DEFAULT_AUDIO_DRIVER = "pulseaudio";
    public static final String DEFAULT_DXWRAPPER = "dxvk";
    public static final String DEFAULT_DISPLAY_RENDERER = "vulkan";
    public static final String DEFAULT_DDRAWRAPPER = "none";
    public static final String DEFAULT_DXWRAPPERCONFIG = "version=" + DefaultVersion.DXVK + ",framerate=0,maxDeviceMemory=0,async=" + DefaultVersion.ASYNC + ",asyncCache=" + DefaultVersion.ASYNC_CACHE + ",vkd3dVersion=" + DefaultVersion.VKD3D + ",vkd3dLevel=12_1" + ",ddrawrapper=" + Container.DEFAULT_DDRAWRAPPER + ",csmt=3" + ",gpuName=NVIDIA GeForce GTX 480" + ",videoMemorySize=2048" + ",strict_shader_math=1" + ",OffscreenRenderingMode=fbo" + ",renderer=gl";;
    public static final String DEFAULT_GRAPHICSDRIVERCONFIG = "vulkanVersion=1.3" + ",version=" + DefaultVersion.WRAPPER + ",blacklistedExtensions=" + ",maxDeviceMemory=0" + ",presentMode=mailbox" + ",syncFrame=0" + ",disablePresentWait=0" + ",resourceType=auto" + ",bcnEmulation=auto" + ",bcnEmulationType=compute" + ",bcnEmulationCache=0" + ",gpuName=Device";
    public static final String DEFAULT_DRIVES = "D:"+Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)+"E:/data/data/forever.launcher/storage";
    public static final String DEFAULT_VARIANT = DefaultVersion.VARIANT;
    public static final byte STARTUP_SELECTION_NORMAL = 0;
    public static final byte STARTUP_SELECTION_AGGRESSIVE = 2;

    // Process suspend policy for runtime behavior while in-game.
    public static final String SUSPEND_POLICY_AUTO = "auto";
    public static final String SUSPEND_POLICY_NEVER = "never";
    public static final String SUSPEND_POLICY_MANUAL = "manual";

    public static final String BIONIC = "bionic";
    public final String id;
    private String name;
    private String screenSize = DEFAULT_SCREEN_SIZE_16_9;
    private String envVars = DEFAULT_ENV_VARS;
    private String graphicsDriver = DEFAULT_GRAPHICS_DRIVER;
    private String dxwrapper = DEFAULT_DXWRAPPER;
    private String dxwrapperConfig = DEFAULT_DXWRAPPERCONFIG;
    private String graphicsDriverConfig = DEFAULT_GRAPHICSDRIVERCONFIG;
    private String rendererPresentMode = "fifo";
    private String displayRenderer = Container.DEFAULT_DISPLAY_RENDERER;
    private String audioDriver = DEFAULT_AUDIO_DRIVER;
    private boolean pulseaudioLowLatency = false;
    /** Exposes the Android microphone to Wine/Proton as a capture device. Opt-in, off by default. */
    private boolean micEnabled = false;
    private String drives = DEFAULT_DRIVES;
    private String wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
    private boolean wow64Mode = true;
    private boolean needsUnpacking = true;
    private byte startupSelection = STARTUP_SELECTION_AGGRESSIVE;
    private String cpuList;
    private String cpuListWoW64;
    private String desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME;
    private File rootDir;
    private JSONObject extraData;
    private int inputType = WinHandler.PreferredInputApi.BOTH.ordinal();

    private String graphicsDriverVersion = "25.1.0"; // Default version or fallback

    private String execArgs = ""; // Default exec arguments
    private String executablePath = ""; // Executable path for container
    private boolean disableLibredirect;

    // Preferred game language for Goldberg force_language.txt
    private String language = "english";

    private byte dinputMapperType = 1;  // 1=standard, 2=XInput mapper
    // Disable external mouse input
    private boolean disableMouseInput = false;
    // Touchscreen mode
    private boolean touchscreenMode = false;
    // Serialised JSON gesture configuration (used when touchscreenMode is true)
    private String gestureConfig = "";
    // External display input handling
    private String externalDisplayMode = DEFAULT_EXTERNAL_DISPLAY_MODE;
    // Swap game/input between internal and external displays
    private boolean externalDisplaySwap = false;
    // Prefer DRI3 WSI path
    private boolean useDRI3 = true;
    private String lc_all = "en_US.utf8";


    private String suspendPolicy = SUSPEND_POLICY_MANUAL;

    private boolean portraitMode = false;
    private boolean portraitBelowCutout = false;

    private String containerVariant = DEFAULT_VARIANT;

    private String basePrefix = "";

    public String getGraphicsDriverVersion() {
        return graphicsDriverVersion;
    }

    public void setGraphicsDriverVersion(String graphicsDriverVersion) {
        this.graphicsDriverVersion = graphicsDriverVersion;
    }

    public String getExecArgs() {
        return execArgs;
    }

    public void setExecArgs(String execArgs) {
        this.execArgs = execArgs != null ? execArgs : "";
    }

    public String getExecutablePath() {
        return executablePath;
    }

    public void setExecutablePath(String executablePath) {
        this.executablePath = executablePath != null ? executablePath : "";
    }

    public Container(String id) {
        this.id = id;
        this.name = "Container-"+id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScreenSize() {
        return screenSize;
    }

    public void setScreenSize(String screenSize) {
        this.screenSize = screenSize;
    }

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars != null ? envVars : "";
    }

    public String getGraphicsDriver() {
        return graphicsDriver;
    }

    public void setGraphicsDriver(String graphicsDriver) {
        this.graphicsDriver = graphicsDriver;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public String getGraphicsDriverConfig() {
        return this.graphicsDriverConfig;
    }

    public void setGraphicsDriverConfig(String graphicsDriverConfig) {
        this.graphicsDriverConfig = graphicsDriverConfig != null ? graphicsDriverConfig : "";
    }

    public String getRendererPresentMode() { return rendererPresentMode; }

    public void setRendererPresentMode(String v) { this.rendererPresentMode = v != null ? v : "fifo"; }

    public String getDisplayRenderer() { return displayRenderer; }

    public void setDisplayRenderer(String v) { this.displayRenderer = v; }

    public String getDXWrapperConfig() {
        return dxwrapperConfig;
    }

    public void setDXWrapperConfig(String dxwrapperConfig) {
        this.dxwrapperConfig = dxwrapperConfig != null ? dxwrapperConfig : "";
    }

    public String getAudioDriver() {
        return audioDriver;
    }

    public void setAudioDriver(String audioDriver) {
        this.audioDriver = audioDriver;
    }

    public boolean getPulseaudioLowLatency() {
        return pulseaudioLowLatency;
    }

    public void setPulseaudioLowLatency(boolean pulseaudioLowLatency) {
        this.pulseaudioLowLatency = pulseaudioLowLatency;
    }

    public boolean getMicEnabled() {
        return micEnabled;
    }

    public void setMicEnabled(boolean micEnabled) {
        this.micEnabled = micEnabled;
    }

    public String getDrives() {
        return drives;
    }

    public void setDrives(String drives) {
        this.drives = drives;
    }

    public boolean isDisableLibredirect() {
        return disableLibredirect;
    }

    public void setDisableLibredirect(boolean disableLibredirect) {
        this.disableLibredirect = disableLibredirect;
    }

    public void setLanguage(String language) {
        this.language = (language != null && !language.isEmpty()) ? language : "english";
    }

    public boolean isWoW64Mode() {
        return wow64Mode;
    }

    public void setWoW64Mode(boolean wow64Mode) {
        this.wow64Mode = wow64Mode;
    }

    public boolean isNeedsUnpacking() {
        return needsUnpacking;
    }

    public void setNeedsUnpacking(boolean needsUnpacking) {
        this.needsUnpacking = needsUnpacking;
    }

    public byte getStartupSelection() {
        return startupSelection;
    }

    public void setStartupSelection(byte startupSelection) {
        this.startupSelection = startupSelection;
    }

    public String getCPUList(boolean allowFallback) {
        return cpuList != null ? cpuList : (allowFallback ? getFallbackCPUList() : null);
    }

    public void setCPUList(String cpuList) {
        this.cpuList = cpuList != null && !cpuList.isEmpty() ? cpuList : null;
    }

    public String getCPUListWoW64(boolean allowFallback) {
        return cpuListWoW64 != null ? cpuListWoW64 : (allowFallback ? getFallbackCPUListWoW64() : null);
    }

    public void setCPUListWoW64(String cpuListWoW64) {
        this.cpuListWoW64 = cpuListWoW64 != null && !cpuListWoW64.isEmpty() ? cpuListWoW64 : null;
    }










    public File getRootDir() {
        return rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    public void setExtraData(JSONObject extraData) {
        this.extraData = extraData;
    }

    public void setContainerVariant(String variant) {
        this.containerVariant = variant;
    }

    public String getContainerVariant() {
        return this.containerVariant;
    }

    public String getBasePrefix() {
        return basePrefix != null ? basePrefix : "";
    }

    public void setBasePrefix(String basePrefix) {
        this.basePrefix = basePrefix != null ? basePrefix : "";
    }

    public boolean isOverlay() {
        return ContainerOverlay.isEligible(this) && !getBasePrefix().isEmpty();
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return extraData != null && extraData.has(name) ? extraData.getString(name) : fallback;
        }
        catch (JSONException e) {
            return fallback;
        }
    }

    public void putExtra(String name, Object value) {
        if (extraData == null) extraData = new JSONObject();
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);
        }
        catch (JSONException e) {
        }
    }

    public String getWineVersion() {
        return wineVersion;
    }

    public void setWineVersion(String wineVersion) {
        this.wineVersion = wineVersion;
    }

    public File getConfigFile() {
        return new File(rootDir, ".container");
    }

    public String getDesktopTheme() {
        return desktopTheme;
    }

    public void setDesktopTheme(String desktopTheme) {
        this.desktopTheme = desktopTheme;
    }

    public int getInputType() {
        return inputType;
    }

    public void setInputType(int inputType) {
        this.inputType = inputType;
    }

    /**
     * Gets the DirectInput mapper type: 1=standard, 2=XInput mapper
     */
    public byte getDinputMapperType() {
        return dinputMapperType;
    }

    /**
     * Sets the DirectInput mapper type: 1=standard, 2=XInput mapper
     */
    public void setDinputMapperType(byte dinputMapperType) {
        this.dinputMapperType = dinputMapperType;
    }

    public Iterable<String[]> drivesIterator() {
        return drivesIterator(drives);
    }

    public static Iterable<String[]> drivesIterator(final String drives) {
        final int[] index = {drives.indexOf(":")};
        final String[] item = new String[2];
        return () -> new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return index[0] != -1;
            }

            @Override
            public String[] next() {
                item[0] = String.valueOf(drives.charAt(index[0]-1));
                int nextIndex = drives.indexOf(":", index[0]+1);
                item[1] = drives.substring(index[0]+1, nextIndex != -1 ? nextIndex-1 : drives.length());
                index[0] = nextIndex;
                return item;
            }
        };
    }

    private JSONObject savedKnownData;

    public synchronized void saveData() {

        try {
            JSONObject desired = serializeData();
            AtomicContainerConfig.save(getConfigFile(), savedKnownData, desired);
            savedKnownData = new JSONObject(desired.toString());
        } catch (java.io.IOException | JSONException e) {
            throw new IllegalStateException("Unable to persist container " + id, e);
        }

    }

    private JSONObject serializeData() throws JSONException {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("screenSize", screenSize);
            data.put("envVars", envVars);
            data.put("cpuList", cpuList);
            data.put("cpuListWoW64", cpuListWoW64);
            data.put("graphicsDriver", graphicsDriver);
            data.put("graphicsDriverVersion", graphicsDriverVersion); // Ensure this is added
            if (!graphicsDriverConfig.isEmpty()) data.put("graphicsDriverConfig", graphicsDriverConfig);
            data.put("rendererPresentMode", rendererPresentMode);
            data.put("displayRendererMode", displayRenderer);
            data.put("dxwrapper", dxwrapper);
            if (!dxwrapperConfig.isEmpty()) data.put("dxwrapperConfig", dxwrapperConfig);
            data.put("audioDriver", audioDriver);
            data.put("pulseaudioLowLatency", pulseaudioLowLatency);
            data.put("micEnabled", micEnabled);
            data.put("drives", drives);
            data.put("inputType", inputType);
            data.put("dinputMapperType", dinputMapperType);
            data.put("wow64Mode", wow64Mode);
            data.put("startupSelection", startupSelection);
            data.put("desktopTheme", desktopTheme);
            data.put("extraData", extraData);
            data.put("execArgs", execArgs);
            data.put("executablePath", executablePath);
            data.put("needsUnpacking", needsUnpacking);
            data.put("disableLibredirect", disableLibredirect);
            data.put("disableMouseInput", disableMouseInput);
            data.put("touchscreenMode", touchscreenMode);
            if (gestureConfig != null && !gestureConfig.isEmpty()) {
                data.put("gestureConfig", gestureConfig);
            }
            data.put("externalDisplayMode", externalDisplayMode);
            data.put("externalDisplaySwap", externalDisplaySwap);
            data.put("useDRI3", useDRI3);
            data.put("language", language);
            data.put("containerVariant", containerVariant);
            if (!getBasePrefix().isEmpty()) data.put("basePrefix", basePrefix);
            data.put("lc_all", lc_all);
            data.put("suspendPolicy", suspendPolicy);
            data.put("portraitMode", portraitMode);
            data.put("portraitBelowCutout", portraitBelowCutout);

            if (!WineInfo.isMainWineVersion(wineVersion)) data.put("wineVersion", wineVersion);
            return data;
    }

    public void loadData(JSONObject data) throws JSONException {
        wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
        dxwrapperConfig = "";

        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String key = it.next();
            switch (key) {
                case "name" :
                    setName(data.getString(key));
                    break;
                case "screenSize" :
                    setScreenSize(data.getString(key));
                    break;
                case "envVars" :
                    setEnvVars(data.getString(key));
                    break;
                case "cpuList" :
                    setCPUList(data.getString(key));
                    break;
                case "cpuListWoW64" :
                    setCPUListWoW64(data.getString(key));
                    break;
                case "graphicsDriver" :
                    setGraphicsDriver(data.getString(key));
                    break;
                case "graphicsDriverVersion":
                    setGraphicsDriverVersion(data.getString(key));
                    break;
                case "graphicsDriverConfig" :
                    setGraphicsDriverConfig(data.getString(key));
                    break;
                case "rendererPresentMode" :
                    setRendererPresentMode(data.getString(key));
                    break;
                case "displayRendererMode" :
                    setDisplayRenderer(data.getString(key));
                    break;
                case "dxwrapper" :
                    setDXWrapper(data.getString(key));
                    break;
                case "dxwrapperConfig" :
                    setDXWrapperConfig(data.getString(key));
                    break;
                case "drives" :
                    setDrives(data.getString(key));
                    break;
                case "language" :
                    setLanguage(data.getString(key));
                    break;
                case "containerVariant" :
                    setContainerVariant(data.getString(key));
                    break;
                case "inputType" :
                    setInputType(data.getInt(key));
                    break;
                case "dinputMapperType" :
                    setDinputMapperType((byte) data.getInt(key));
                    break;
                case "wow64Mode" :
                    setWoW64Mode(data.getBoolean(key));
                    break;
                case "startupSelection" :
                    setStartupSelection((byte)data.getInt(key));
                    break;
                case "extraData" : {
                    JSONObject extraData = data.getJSONObject(key);
                    setExtraData(extraData);
                    break;
                }
                case "wineVersion" :
                    setWineVersion(data.getString(key));
                    break;
                case "audioDriver" :
                    setAudioDriver(data.getString(key));
                    break;
                case "pulseaudioLowLatency" :
                    setPulseaudioLowLatency(data.getBoolean(key));
                    break;
                case "micEnabled" :
                    setMicEnabled(data.getBoolean(key));
                    break;
                case "desktopTheme" :
                    setDesktopTheme(data.getString(key));
                    break;

                case "execArgs" :
                    setExecArgs(data.getString(key));
                    break;
                case "executablePath" :
                    setExecutablePath(data.getString(key));
                    break;
                case "needsUnpacking" :
                    setNeedsUnpacking(data.getBoolean(key));
                    break;
                case "disableLibredirect" :
                    setDisableLibredirect(data.getBoolean(key));
                    break;
                case "disableMouseInput" :
                    setDisableMouseInput(data.getBoolean(key));
                    break;
                case "touchscreenMode" :
                    setTouchscreenMode(data.getBoolean(key));
                    break;
                case "gestureConfig" :
                    setGestureConfig(data.optString(key, ""));
                    break;
                case "externalDisplayMode" :
                    setExternalDisplayMode(data.getString(key));
                    break;
                case "externalDisplaySwap" :
                    setExternalDisplaySwap(data.getBoolean(key));
                    break;
                case "useDRI3" :
                    setUseDRI3(data.getBoolean(key));
                    break;
                case "lc_all" :
                    setLC_ALL(data.getString(key));
                    break;
                case "suspendPolicy":
                    setSuspendPolicy(data.getString(key));
                    break;
                case "portraitMode":
                    this.portraitMode = data.getBoolean(key);
                    break;
                case "portraitBelowCutout":
                    this.portraitBelowCutout = data.getBoolean(key);
                    break;
                case "basePrefix":
                    setBasePrefix(data.optString(key, ""));
                    break;
            }
        }
        savedKnownData = new JSONObject(serializeData().toString());
    }

    public static String normalizeSuspendPolicy(String suspendPolicy) {
        String normalized = (suspendPolicy == null) ? "" : suspendPolicy.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case SUSPEND_POLICY_NEVER:
                return SUSPEND_POLICY_NEVER;
            case SUSPEND_POLICY_MANUAL:
                return SUSPEND_POLICY_MANUAL;
            case SUSPEND_POLICY_AUTO:
            default:
                return SUSPEND_POLICY_AUTO;
        }
    }

    public String getLC_ALL() {
        return lc_all;
    }

    public void setLC_ALL(String lc_all) {
        this.lc_all = lc_all;
    }

    public String getSuspendPolicy() {
        return normalizeSuspendPolicy(suspendPolicy);
    }

    public void setSuspendPolicy(String suspendPolicy) {
        this.suspendPolicy = normalizeSuspendPolicy(suspendPolicy);
    }

    public boolean isPortraitMode() {
        return portraitMode;
    }

    public boolean isPortraitBelowCutout() {
        return portraitBelowCutout;
    }

    public static String getFallbackCPUList() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }

    public static String getFallbackCPUListWoW64() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = numProcessors / 2; i < numProcessors; i++) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        return cpuList;
    }

    // Disable external mouse input
    public boolean isDisableMouseInput() {
        return disableMouseInput;
    }

    public void setDisableMouseInput(boolean disableMouseInput) {
        this.disableMouseInput = disableMouseInput;
    }

    // Touchscreen mode
    public boolean isTouchscreenMode() {
        return touchscreenMode;
    }

    public void setTouchscreenMode(boolean touchscreenMode) {
        this.touchscreenMode = touchscreenMode;
    }

    // Gesture configuration JSON
    public String getGestureConfig() {
        return gestureConfig != null ? gestureConfig : "";
    }

    public void setGestureConfig(String gestureConfig) {
        this.gestureConfig = gestureConfig != null ? gestureConfig : "";
    }

    // External display mode
    public String getExternalDisplayMode() {
        return externalDisplayMode != null ? externalDisplayMode : DEFAULT_EXTERNAL_DISPLAY_MODE;
    }

    public void setExternalDisplayMode(String externalDisplayMode) {
        this.externalDisplayMode = externalDisplayMode != null ? externalDisplayMode : DEFAULT_EXTERNAL_DISPLAY_MODE;
    }

    public boolean isExternalDisplaySwap() {
        return externalDisplaySwap;
    }

    public void setExternalDisplaySwap(boolean externalDisplaySwap) {
        this.externalDisplaySwap = externalDisplaySwap;
    }

    // Use DRI3 WSI
    public boolean isUseDRI3() {
        return useDRI3;
    }

    public void setUseDRI3(boolean useDRI3) {
        this.useDRI3 = useDRI3;
    }

    public String getCPUList() {
        return getCPUList(true);
    }

    public String getCPUListWoW64() {
        return getCPUListWoW64(true);
    }

    public String getLanguage() {
        return language != null ? language : "english";
    }
}
