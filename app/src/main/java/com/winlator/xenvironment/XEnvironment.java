package com.winlator.xenvironment;

import android.content.Context;

import com.winlator.core.FileUtils;
import com.winlator.xenvironment.components.BionicProgramLauncherComponent;
import com.winlator.xenvironment.components.MicrophoneComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.XServer;
import com.winlator.xserver.extensions.PresentExtension;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

public class XEnvironment implements Iterable<EnvironmentComponent> {
    private final Context context;
    private final ImageFs imageFs;
    private final ArrayList<EnvironmentComponent> components = new ArrayList<>();

    public XEnvironment(Context context, ImageFs imageFs) {
        this.context = context;
        this.imageFs = imageFs;
    }

    public Context getContext() {
        return context;
    }

    public ImageFs getImageFs() {
        return imageFs;
    }

    public void addComponent(EnvironmentComponent environmentComponent) {
        environmentComponent.environment = this;
        components.add(environmentComponent);
    }

    public <T extends EnvironmentComponent> T getComponent(Class<T> componentClass) {
        for (EnvironmentComponent component : components) {
            if (component.getClass() == componentClass) return (T)component;
        }
        return null;
    }

    @Override
    public Iterator<EnvironmentComponent> iterator() {
        return components.iterator();
    }

    public static File getTmpDir(Context context) {
        File tmpDir = new File(context.getFilesDir(), "tmp");
        if (!tmpDir.isDirectory()) {
            tmpDir.mkdirs();
            FileUtils.chmod(tmpDir, 0771);
        }
        return tmpDir;
    }

    public void startEnvironmentComponents() {
        FileUtils.clear(getTmpDir(getContext()));
        for (EnvironmentComponent environmentComponent : this) environmentComponent.start();
    }

    public void stopEnvironmentComponents() {
        // Consumers start last. Stop Wine/renderers before closing their XServer,
        // shared-memory and audio dependencies, including during partial startup.
        RuntimeException failure = null;
        for (int i = components.size() - 1; i >= 0; i--) {
            try {
                components.get(i).stop();
            } catch (RuntimeException error) {
                // Continue cleanup; the caller still receives the original failure.
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    public void onPause() {
        // Pause game processes FIRST
        pauseGameProcesses();

        // Then pause audio components
        PulseAudioComponent pulseAudioComponent = getComponent(PulseAudioComponent.class);
        if (pulseAudioComponent != null) pulseAudioComponent.pause();
        // Release the microphone while backgrounded (drops the mic indicator and its power cost)
        MicrophoneComponent microphoneComponent = getComponent(MicrophoneComponent.class);
        if (microphoneComponent != null) microphoneComponent.pause();
    }

    /** The app left the foreground while the game keeps running: the microphone is released and buffers go back on a timer. */
    public void onBackground() {
        MicrophoneComponent microphoneComponent = getComponent(MicrophoneComponent.class);
        if (microphoneComponent != null) microphoneComponent.pause();
        setPresentBackground(true);
    }

    private void setPresentBackground(boolean background) {
        XServerComponent xServerComponent = getComponent(XServerComponent.class);
        XServer xServer = xServerComponent != null ? xServerComponent.getXServer() : null;
        PresentExtension present = xServer != null ? xServer.getExtension(PresentExtension.MAJOR_OPCODE) : null;
        if (present != null) present.setBackground(background);
    }

    public void onResume() {
        setPresentBackground(false);
        // Resume audio so it's ready when game processes wake up
        PulseAudioComponent pulseAudioComponent = getComponent(PulseAudioComponent.class);
        if (pulseAudioComponent != null) pulseAudioComponent.resume();
        MicrophoneComponent microphoneComponent = getComponent(MicrophoneComponent.class);
        if (microphoneComponent != null) microphoneComponent.resume();

        // Then resume game processes
        resumeGameProcesses();
    }

    public void pauseGameProcesses() {
        BionicProgramLauncherComponent bionicProgramLauncherComponent = getComponent(BionicProgramLauncherComponent.class);
        if (bionicProgramLauncherComponent != null) bionicProgramLauncherComponent.suspendProcess();
    }

    public void resumeGameProcesses() {
        BionicProgramLauncherComponent bionicProgramLauncherComponent = getComponent(BionicProgramLauncherComponent.class);
        if (bionicProgramLauncherComponent != null) bionicProgramLauncherComponent.resumeProcess();
    }
}
