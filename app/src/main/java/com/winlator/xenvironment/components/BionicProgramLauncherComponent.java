package com.winlator.xenvironment.components;

import android.app.Service;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Process;

import androidx.annotation.NonNull;

import com.winlator.PrefManager;

import com.winlator.container.Container;
import com.winlator.container.ContainerOverlay;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.Callback;
import com.winlator.core.envvars.EnvVars;
import com.winlator.core.ProcessHelper;
import com.winlator.core.WineInfo;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import app.gamenative.BuildConfig;
import app.gamenative.PluviaApp;

public class BionicProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private EnvVars envVars;
    private WineInfo wineInfo;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();
    private static final LaunchGeneration launches = new LaunchGeneration();
    private long ownedLaunch = -1;
    private boolean wow64Mode = true;
    private final ContentsManager contentsManager;
    private final ContentProfile wineProfile;
    private Container container;
    private File workingDir;

    public void setWineInfo(WineInfo wineInfo) {
        this.wineInfo = wineInfo;
    }
    public void setContainer(Container container) { this.container = container; }

    // The exec/W^X shim every Wine process preloads: the app's own libwowexec.so (see WowRuntimeTuning.execShim).
    // Without it Android's W^X rules stop Wine from starting. The container's disableLibredirect flag no longer matters.
    private String resolveLibredirectPreload(ImageFs imageFs) {
        return app.gamenative.ui.screen.wow.WowRuntimeTuning.execShim(environment.getContext().getApplicationInfo().nativeLibraryDir);
    }

    public BionicProgramLauncherComponent(ContentsManager contentsManager, ContentProfile wineProfile) {

        this.contentsManager = contentsManager;
        this.wineProfile = wineProfile;

    }

    private Runnable preUnpack;
    public void setPreUnpack(Runnable r) { this.preUnpack = r; }
    @Override
    public void start() {

        // The previous session's wineserver may still be finishing in the background (it saves the registry on its way out).
        if (!PluviaApp.awaitTeardown(TEARDOWN_WAIT_MS)) {
            // The launcher already waited for it before it came here, so this is a second, independent gate. Starting anyway would
            // let the old wineserver and the new session share a prefix: do not start.
            throw new IllegalStateException("The previous game session is still closing.");
        }
        synchronized (lock) {
            stopCurrentLaunchLocked(false);
            ownedLaunch = launches.current();
            if (preUnpack != null) preUnpack.run();
            pid = execGuestProgram();
            PluviaApp.setKeepAlive(pid > 0);
        }

    }

    @Override
    public void stop() {
        synchronized (lock) {
            // A delayed teardown from an older screen cannot kill the new session.
            if (!launches.isCurrent(ownedLaunch)) return;
            stopCurrentLaunchLocked(true);
        }
    }

    /**
     * Kills the guest processes, then asks the wineserver to exit. The kills are immediate; the wineserver request can take seconds
     * when the server is wedged, so a stop (which runs on the UI thread when the player exits) finishes it in the background:
     * the session stays marked alive until it is done and the next launch waits for it. A start replaces a launch in place and
     * waits for the request itself.
     */
    private void stopCurrentLaunchLocked(boolean wineserverInBackground) {
        launches.invalidate();
        if (pid != -1) {
            Process.killProcess(pid);
            List<ProcessHelper.ProcessInfo> subProcesses = ProcessHelper.listSubProcesses();
            for (ProcessHelper.ProcessInfo subProcess : subProcesses) Process.killProcess(subProcess.pid);
        }
        pid = -1;
        if (wineserverInBackground) {
            PluviaApp.runTeardownInBackground(this::killWineserver);
        } else {
            PluviaApp.setKeepAlive(false);
            killWineserver();
        }
    }

    // Bounded: a wedged wineserver must not hang the caller for ever.
    private void killWineserver() {
        execShellCommand("wineserver -k", true, null, null, WINESERVER_KILL_TIMEOUT_SECONDS);
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public boolean isWoW64Mode() {
        return wow64Mode;
    }

    public void setWoW64Mode(boolean wow64Mode) {
        this.wow64Mode = wow64Mode;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }



    public void setWorkingDir(File workingDir) {
        this.workingDir = workingDir;
    }

    private int execGuestProgram() {

        ImageFs imageFs = environment.getImageFs();
        final int MAX_PLAYERS = 4;

        // Get the number of enabled players directly from ControllerManager.
        for (int i = 0; i < MAX_PLAYERS; i++) {
            String memPath;
            if (i == 0) {
                // Player 1 uses the original, non-numbered path that is known to work.
                memPath = new File(imageFs.getRootDir(), "tmp/gamepad.mem").getAbsolutePath();
            } else {
                // Players 2, 3, 4 use a 1-based index.
                memPath = new File(imageFs.getRootDir(), "tmp/gamepad" + i + ".mem").getAbsolutePath();
            }

            File memFile = new File(memPath);
            memFile.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(memFile, "rw")) {
                raf.setLength(64);
            } catch (IOException e) {
            }
        }
        Context context = environment.getContext();
        imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();

        PrefManager.init(context);
        boolean shareAndroidClipboard = PrefManager.getBoolean("share_android_clipboard", false);

        envVars.put("WINEMU_HOST_PKG", context.getPackageName());
        envVars.put("HOST_PKG", context.getPackageName());
        envVars.put("EVSHIM_BASE_PATH", context.getFilesDir().getAbsolutePath());

        if (shareAndroidClipboard) {
            envVars.put("WINE_FROM_ANDROID_CLIPBOARD", "1");
            envVars.put("WINE_TO_ANDROID_CLIPBOARD", "1");
        }

        EnvVars envVars = new EnvVars();

        // Use the ControllerManager's dynamic count for the environment variable
        envVars.put("EVSHIM_MAX_PLAYERS", String.valueOf(MAX_PLAYERS));
        envVars.put("EVSHIM_SHM_ID", 1);

        // Setting up essential environment variables for Wine
        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
        new File(imageFs.home_path + "/.wine/drive_c" + rootDir.getPath() + "/usr/tmp").mkdirs();
        envVars.put("DISPLAY", ":0");

        String winePath = imageFs.getWinePath() + "/bin";

        envVars.put("PATH", winePath + ":" +
                rootDir.getPath() + "/usr/bin");
        envVars.put("REDIRECT_EXEC__PROC_SELF_EXE", winePath + "/wine");

        String ldLibraryPath = rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64";
        ldLibraryPath += ":" + imageFs.getWinePath() + "/lib";
        envVars.put("LD_LIBRARY_PATH", ldLibraryPath);
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("FONTCONFIG_PATH", rootDir.getPath() + "/usr/etc/fonts");

        envVars.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
        envVars.put("XDG_CONFIG_DIRS", rootDir.getPath() + "/usr/etc/xdg");
        envVars.put("VK_LAYER_PATH", rootDir.getPath() + "/usr/share/vulkan/implicit_layer.d" + ":" + rootDir.getPath() + "/usr/share/vulkan/explicit_layer.d");
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        envVars.put("OPENSSL_CONF", rootDir.getPath() + "/usr/etc/tls/openssl.cnf");
        envVars.put("SSL_CERT_FILE", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        envVars.put("SSL_CERT_DIR", rootDir.getPath() + "/usr/etc/tls/certs");
        // Wine's crypt32 imports this bundle into the Windows ROOT certificate store. Without it
        // the store only holds Wine's built-in Microsoft roots: Android 14+ moved the system CAs
        // to an APEX path Wine does not scan, so TLS verification fails for anything that trusts
        // via the Windows store (e.g. the EOS SDK's websockets, winhttp/wininet callers).
        envVars.put("WINE_ADDITIONAL_CERTS_DIR", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        envVars.put("WINE_X11FORCEGLX", "1");
        envVars.put("WINE_GST_NO_GL", "1");

        String primaryDNS = "8.8.4.4";
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
        android.net.Network activeNetwork = connectivityManager != null ? connectivityManager.getActiveNetwork() : null;
        // The network can vanish between the two calls, which makes getLinkProperties() return null.
        android.net.LinkProperties linkProperties = activeNetwork != null ? connectivityManager.getLinkProperties(activeNetwork) : null;
        if (linkProperties != null) {
            ArrayList<InetAddress> dnsServers = new ArrayList<>(linkProperties.getDnsServers());

            // Check if the dnsServers list is not empty before getting an item
            if (!dnsServers.isEmpty()) {
                InetAddress selectedDNS = dnsServers.get(0);
                for (InetAddress dnsServer : dnsServers) {
                    if (dnsServer instanceof Inet4Address) {
                        selectedDNS = dnsServer;
                        break;
                    }
                }
                primaryDNS = selectedDNS.getHostAddress();
            }
        }
        envVars.put("ANDROID_RESOLV_DNS", primaryDNS);
        envVars.put("WINE_NEW_NDIS", "1");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String evshimPath = context.getApplicationInfo().nativeLibraryDir + "/libevshim.so";
        String replacePath = resolveLibredirectPreload(imageFs);

        if (new File(sysvPath).exists()) ld_preload += sysvPath;

        ld_preload += ":" + evshimPath;
        String dnsV4MappedPath = context.getApplicationInfo().nativeLibraryDir + "/libgamenative_dns_v4mapped.so";
        if (new File(dnsV4MappedPath).exists()) {
            ld_preload += ":" + dnsV4MappedPath;
        }
        if (replacePath != null) ld_preload += ":" + replacePath;

        envVars.put("LD_PRELOAD", ld_preload);
        envVars.put("EVSHIM_WINE", 1);
        envVars.put("EVSHIM_SHM_NAME", "controller-shm0");

        // Merge any additional environment variables from external sources
        if (this.envVars != null) {
            envVars.putAll(this.envVars);
        }

        try {
            ContainerOverlay.applyBionicLaunchEnv(context, container, envVars);
        }
        catch (IllegalStateException e) {
            return -1;
        }


        // Construct the command to the Wine executable
        String command = "";
        String overriddenCommand = envVars.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
        if (!overriddenCommand.isEmpty()) {
            String[] parts = overriddenCommand.split(";");
            for (String part : parts)
                command += part + " ";
            command = command.trim();
        }
        else {
            command = getFinalCommand(winePath, guestExecutable);
        }

        // Preserve explicit WoW user overrides after runtime/overlay defaults have been assembled.
        app.gamenative.ui.screen.wow.WowLaunchEnvironment.mergeUserOverrides(container, envVars);
        if (container.id.equals("wow_forever"))
            app.gamenative.ui.screen.wow.WowRuntimeTuning.apply(context, container, envVars);
        // A saved WINEDLLOVERRIDES replaced the DX12 native DLL rules above; DX12 must keep them.
        app.gamenative.ui.screen.wow.WowVkd3dRuntime.INSTANCE.environment(container, envVars);
        if (container.id.equals("wow_forever")) {
            app.gamenative.ui.screen.wow.WowX11Options.ensure(new File(rootDir, ImageFs.WINEPREFIX + "/user.reg"));
            // 1.4.9: the Microsoft fonts and Winlator helpers an older container pattern copied into the prefix are not ours to ship.
            app.gamenative.ui.screen.wow.WowLegacyFiles.removeFrom(new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows"));
        }

        if (app.gamenative.display.WowDisplayBackend.isGamescope(container))
            command = app.gamenative.display.WowGamescopeRuntime.wrap(context, container, rootDir, envVars, command);
        File diagnosticDirectory = app.gamenative.ui.screen.wow.WowDiagnostics.prepare(context, container, envVars);
        File probeDirectory = app.gamenative.ui.screen.wow.WowDriverProbe.prepare(context, container, envVars, diagnosticDirectory);
        if (probeDirectory != null && wineInfo.isArm64EC()) {
            // Same Wine, prefix, environment and vulkan-1/Wrapper chain; no app-side Vulkan probe.
            try {
                ProcessHelper.exec(winePath + "/wine C:\\windows\\temp\\wow-driver-probe.exe",
                    envVars.toStringArray(), workingDir != null ? workingDir : rootDir,
                    (status) -> { }, probeDirectory);
            } catch (RuntimeException ignored) { }
        }
        final long launchToken = ownedLaunch;
        int startedPid = ProcessHelper.exec(command, envVars.toStringArray(), workingDir != null ? workingDir : rootDir, (status) -> {
            app.gamenative.ui.screen.wow.WowDiagnostics.finish(diagnosticDirectory);
            synchronized (lock) {
                if (!launches.isCurrent(launchToken)) return;
                pid = -1;
                PluviaApp.setKeepAlive(false);
                if (terminationCallback != null) terminationCallback.call(status);
            }
        }, diagnosticDirectory);

            return startedPid;

    }

    @NonNull
    private String getFinalCommand(String winePath, String guestExecutable) {
        // ARM64EC Wine runs the guest program directly; there is no emulator wrapper.
        return winePath + "/" + guestExecutable;
    }

    public void suspendProcess() {
        ProcessHelper.pauseAllWineProcesses();
    }

    public void resumeProcess() {
        ProcessHelper.resumeAllWineProcesses();
    }

    public String execShellCommand(String command) {
        return execShellCommand(command, true);
    }

    public String execShellCommand(String command, boolean includeStderr) {
        return execShellCommand(command, includeStderr, null, null);
    }

    private static final int WINESERVER_KILL_TIMEOUT_SECONDS = 5;
    private static final long TEARDOWN_WAIT_MS = 30_000L;

    public String execShellCommand(String command, boolean includeStderr, EnvVars extraEnv, String[] unsetEnv) {
        return execShellCommand(command, includeStderr, extraEnv, unsetEnv, -1);
    }

    /** @param timeoutSeconds seconds to wait for the command before it is killed; -1 waits without limit */
    public String execShellCommand(String command, boolean includeStderr, EnvVars extraEnv, String[] unsetEnv, int timeoutSeconds) {

        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        EnvVars envVars = new EnvVars();

        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", imageFs.getRootDir().getPath() + "/tmp");
        envVars.put("DISPLAY", ":0");

        String winePath = imageFs.getWinePath() + "/bin";

        envVars.put("PATH", winePath + ":" + rootDir.getPath() + "/usr/bin");
        envVars.put("REDIRECT_EXEC__PROC_SELF_EXE", winePath + "/wine");

        String ldLibraryPath = rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64";
        ldLibraryPath += ":" + imageFs.getWinePath() + "/lib";
        envVars.put("LD_LIBRARY_PATH", ldLibraryPath);
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String replacePath = resolveLibredirectPreload(imageFs);

        if (new File(sysvPath).exists()) ld_preload += sysvPath;

        if (replacePath != null) ld_preload += ":" + replacePath;

        envVars.put("LD_PRELOAD", ld_preload);

        if (this.envVars != null) envVars.putAll(this.envVars);
        try {
            ContainerOverlay.applyBionicLaunchEnv(context, container, envVars);
        }
        catch (IllegalStateException e) {
            return "";
        }
        if (extraEnv != null) envVars.putAll(extraEnv);
        if (unsetEnv != null) for (String name : unsetEnv) envVars.remove(name);

        String finalCommand = getFinalCommand(winePath, command);

        return ProcessHelper.execWithOutput(finalCommand, envVars.toStringArray(),
                workingDir != null ? workingDir : imageFs.getRootDir(), includeStderr, timeoutSeconds);

    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public File getWorkingDir() {
        return workingDir;
    }
}
