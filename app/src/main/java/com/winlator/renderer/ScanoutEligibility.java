package com.winlator.renderer;

/** Geometry and composition gate shared by the renderer and host tests. */
public final class ScanoutEligibility {
    private ScanoutEligibility() { }
    public static float frameRateTarget(int limiter, int refresh, float display) {
        return limiter > 0 ? limiter : refresh > 0 ? refresh : display > 0 ? display : 60f;
    }
    public static boolean eligible(boolean requested, boolean blocked, boolean effects,
            boolean hud, boolean foreground, int visibleWindows,
            int x, int y, int width, int height, int screenWidth, int screenHeight, boolean hardwareBuffer) {
        return reason(requested, blocked, effects, hud, foreground, visibleWindows,
            x, y, width, height, screenWidth, screenHeight, hardwareBuffer) == null;
    }
    /** First condition that prevents scanout, or null when the frame is eligible. Diagnostics only. */
    public static String reason(boolean requested, boolean blocked, boolean effects,
            boolean hud, boolean foreground, int visibleWindows,
            int x, int y, int width, int height, int screenWidth, int screenHeight, boolean hardwareBuffer) {
        if (!requested) return "off";
        if (blocked) return "ui";
        if (effects) return "effects";
        if (hud) return "hud";
        if (!foreground) return "background";
        if (visibleWindows != 1) return "windows";
        if (x != 0 || y != 0 || width != screenWidth || height != screenHeight || width <= 0 || height <= 0) return "geometry";
        if (!hardwareBuffer) return "not-ahb";
        return null;
    }
}
