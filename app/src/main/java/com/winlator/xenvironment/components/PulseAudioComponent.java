package com.winlator.xenvironment.components;

import android.content.Context;

import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.XEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the bundled PulseAudio server (AAudio sink for the game's sound, optional pipe source for the
 * microphone) and pauses/resumes the sink with the app's lifecycle via pactl suspend-sink.
 */
public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    private final String SINK_NAME = "AAudioSink";

    /** Name of the microphone source exposed to Wine/Proton. */
    public static final String MIC_SOURCE_NAME = "GameNativeMic";
    /** Stock PulseAudio module used to publish the Android microphone as a capture device. */
    private static final String PIPE_SOURCE_MODULE = "module-pipe-source.so";
    private static final String MIC_FIFO_NAME = "mic.fifo";

    private float volume = 1.0f;
    private byte performanceMode = 1;
    private final AtomicBoolean isPauseResumeRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private boolean lowLatency = false;
    private final boolean enableAudioOutput;
    private final boolean enableAudioInput;

    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();

    public PulseAudioComponent(UnixSocketConfig socketConfig, boolean lowLatency, boolean enableAudioInput, boolean enableAudioOutput) {
        this.socketConfig = socketConfig;
        this.lowLatency = lowLatency;
        this.enableAudioInput = enableAudioInput;
        this.enableAudioOutput = enableAudioOutput;
    }

    /** Working directory the bundled PulseAudio server runs from. */
    public static File getWorkingDir(Context context) {
        return new File(context.getFilesDir(), "/pulseaudio");
    }

    /** Path of the FIFO that {@code module-pipe-source} reads the microphone PCM from. */
    public static File getMicFifoFile(Context context) {
        return new File(getWorkingDir(context), MIC_FIFO_NAME);
    }

    /**
     * Microphone support needs PulseAudio's stock {@code module-pipe-source}, which is shipped inside
     * the bundled pulseaudio asset. Older assets do not contain it, so check before referencing it -
     * a missing module would make the whole server fail to start and kill audio output entirely.
     */
    public static boolean isMicModuleAvailable(Context context) {
        return new File(getWorkingDir(context), "modules/" + PIPE_SOURCE_MODULE).isFile();
    }

    private void killAllPulseAudioProcesses() {
        List<ProcessHelper.ProcessInfo> allProcesses = ProcessHelper.listSubProcesses();
        List<Integer> pulsePids = new ArrayList<>();

        for (ProcessHelper.ProcessInfo info : allProcesses) {
            if (info.name.contains("libpulseaudio.so")) {
                pulsePids.add(info.pid);
            }
        }

        if (!pulsePids.isEmpty()) {

            for (int pid : pulsePids) {
                ProcessHelper.killProcess(pid);
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void start() {
        singleThreadExecutor.execute(() -> {
            killAllPulseAudioProcesses();
            startPulseAudio();
            isPaused.set(false);
        });
    }

    private volatile com.winlator.core.LogSizeGuard logGuard;

    @Override
    public void stop() {
        singleThreadExecutor.execute(() -> {
            com.winlator.core.LogSizeGuard guard = logGuard;
            if (guard != null) { guard.close(); logGuard = null; }
            killAllPulseAudioProcesses();
            isPaused.set(false);
        });
        singleThreadExecutor.shutdown();
    }

    public void pause() {
        singleThreadExecutor.execute(() -> {
            if (!isPaused.get()) {
                if (!isPauseResumeRunning.get()) {
                    isPauseResumeRunning.set(true);

                    if (updateSink(true)) isPaused.set(true);

                    isPauseResumeRunning.set(false);
                }
            }
        });
    }

    public void resume() {
        singleThreadExecutor.execute(() -> {
            if (isPaused.get()) {
                if (!isPauseResumeRunning.get()) {
                    isPauseResumeRunning.set(true);

                    if (updateSink(false)) isPaused.set(false);

                    isPauseResumeRunning.set(false);
                }
            }
        });
    }

    private void startPulseAudio() {
        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        File workingDir = getWorkingDir(context);
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        // Clear any previous staled files, e.g. cookie
        File configDir = new File(workingDir, "/.config");
        if (configDir.exists()) {
            FileUtils.delete(configDir);
        }

        File configFile = new File(workingDir, "default.pa");

        List<String> configLines = new ArrayList<>();
        configLines.add("load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket=\""+socketConfig.path+"\"");

        // Add config for audio input
        if (enableAudioInput) {
            configLines.addAll(buildMicConfigLines(context, workingDir));
        }

        // Add config for audio output
        if (enableAudioOutput) {
            String sinkParams = "volume=" + this.volume + " performance_mode=" + ((int) this.performanceMode);

            if (lowLatency) {
                sinkParams += " low_latency=true";
            }

            configLines.add("load-module module-aaudio-sink " + sinkParams);
            configLines.add("set-default-sink " + SINK_NAME);
        }

        // Apps that ask for "the" capture device must get the microphone, not the sink's monitor source.
        if (enableAudioInput && !isEmptyMic(configLines)) {
            configLines.add("set-default-source " + MIC_SOURCE_NAME);
        }

        FileUtils.writeString(configFile, String.join("\n", configLines));

        File modulesDir = new File(workingDir, "modules");

        EnvVars envVars = new EnvVars();
        envVars.put("LD_LIBRARY_PATH", "/system/lib64:"+nativeLibraryDir+":"+modulesDir);
        envVars.put("HOME", workingDir);
        envVars.put("TMPDIR", XEnvironment.getTmpDir(context));

        String command = nativeLibraryDir+"/libpulseaudio.so";
        command += " --system=false";
        command += " --disable-shm=true";
        command += " --fail=false";
        command += " -n --file=default.pa";
        command += " --daemonize=true";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";

        // Audio diagnostics (Settings -> Diagnostics): verbose server log next to the other diagnostic logs.
        if (app.gamenative.ui.screen.wow.WowDiagnostics.audioDebug(context)) {
            File pulseLog = new File(context.getFilesDir(), "diagnostics/wow/pulse.log");
            //noinspection ResultOfMethodCallIgnored
            pulseLog.getParentFile().mkdirs();
            //noinspection ResultOfMethodCallIgnored
            pulseLog.delete();
            command += " -vvv --log-target=file:" + pulseLog.getAbsolutePath();
            // The daemon writes this file itself: its size is bounded from here (4 MiB, then the previous half goes to pulse.log.1).
            new File(pulseLog.getPath() + ".1").delete();
            logGuard = com.winlator.core.LogSizeGuard.watch(pulseLog, 4L * 1024 * 1024, 5_000);
        }

        ProcessHelper.execWithOutput(command, envVars.toStringArray(), workingDir, true);
    }

    /**
     * Builds the {@code default.pa} lines that publish the Android microphone as a PulseAudio source.
     *
     * Wine/Proton's {@code winepulse.drv} enumerates every source reported by the server, so this is
     * all that is required for the microphone to appear as a Windows recording device inside the
     * container. No Wine-side patch and no registry change is needed.
     *
     * Returns an empty list (i.e. audio output behaves exactly as before) when the microphone is
     * disabled for this container or when the bundled PulseAudio build does not ship
     * {@code module-pipe-source.so}.
     */
    private List<String> buildMicConfigLines(Context context, File workingDir) {
        List<String> lines = new ArrayList<>();

        if (!isMicModuleAvailable(context)) {
            return lines;
        }

        // module-pipe-source does mkfifo() itself and fails on EEXIST, so clear any stale FIFO left
        // behind by a server that was killed instead of shut down.
        File fifoFile = new File(workingDir, MIC_FIFO_NAME);
        //noinspection ResultOfMethodCallIgnored
        fifoFile.delete();

        lines.add("load-module module-pipe-source"
            + " source_name=" + MIC_SOURCE_NAME
            + " file=\"" + fifoFile.getAbsolutePath() + "\""
            + " format=s16le"
            + " rate=" + MicrophoneComponent.SAMPLE_RATE
            + " channels=" + MicrophoneComponent.CHANNEL_COUNT
            + " source_properties=device.description=Microphone");
        // The default source is set after the sink is loaded (see startPulseAudio): the sink's own
        // monitor source appears with it and could otherwise become the default capture device.
        return lines;
    }

    private static boolean isEmptyMic(List<String> lines) {
        for (String line : lines) if (line.startsWith("load-module module-pipe-source")) return false;
        return true;
    }

    private String execPactlCommand(String command) {        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");

        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        File modulesDir = new File(workingDir, "modules");
        EnvVars envVars = new EnvVars();
        envVars.put("LD_LIBRARY_PATH", "/system/lib64:" + nativeLibraryDir + ":" + modulesDir);
        envVars.put("HOME", workingDir);
        envVars.put("TMPDIR", XEnvironment.getTmpDir(context));
        envVars.put("PULSE_SERVER", socketConfig.path);

        return ProcessHelper.execWithOutput(workingDir + "/pactl " + command, envVars.toStringArray(), workingDir, true, 5);
    }

    private boolean updateSink(boolean suspend) {
        if (!suspend) {
            return !execPactlCommand("suspend-sink " + SINK_NAME + " false").toLowerCase().contains("process timeout");
        } else {
            return !execPactlCommand("suspend-sink " + SINK_NAME + " true").toLowerCase().contains("process timeout");
        }
    }

}
