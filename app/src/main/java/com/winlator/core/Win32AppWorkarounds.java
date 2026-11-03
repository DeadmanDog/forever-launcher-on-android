package com.winlator.core;

import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Window;
import com.winlator.xserver.XServer;

/** Applies the container's CPU affinity masks to the processes of newly mapped application windows. */
public class Win32AppWorkarounds {
    private final XServer xServer;
    private volatile short taskAffinityMask;
    private volatile short taskAffinityMaskWoW64;

    public Win32AppWorkarounds(XServer xServer) {
        this.xServer = xServer;
    }

    public void setTaskAffinityMasks(int taskAffinityMask, int taskAffinityMaskWoW64) {
        this.taskAffinityMask = (short) taskAffinityMask;
        this.taskAffinityMaskWoW64 = (short) taskAffinityMaskWoW64;
    }

    private void setProcessAffinity(Window window, int processAffinity) {
        int processId = window.getProcessId();
        String className = window.getClassName();
        WinHandler winHandler = this.xServer.getWinHandler();
        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        } else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(window.getClassName(), processAffinity);
        }
    }

    public void applyWindowWorkarounds(Window window) {
        int windowGroup = window.getWMHintsValue(Window.WMHints.WINDOW_GROUP);
        if (window.isRenderable() && !window.getClassName().isEmpty() && windowGroup == window.id) {
            int processAffinity = window.isWoW64() ? this.taskAffinityMaskWoW64 : this.taskAffinityMask;
            if (processAffinity != 0) {
                setProcessAffinity(window, processAffinity);
            }
        }
    }
}
