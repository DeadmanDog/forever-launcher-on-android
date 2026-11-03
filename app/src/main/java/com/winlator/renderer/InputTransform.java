package com.winlator.renderer;

/**
 * The inverse of what the compositor does to the picture: pointer position in the view -> position in the guest screen.
 * guest = (view - offset) * scale, per axis. It follows the same three output modes as VulkanRenderer.updateTransform, so the image
 * and the pointer always agree (the touch view used to assume FIT for every mode).
 */
public final class InputTransform {
    public static final int FIT = 0, STRETCH = 1, FILL = 2;

    private InputTransform() {}

    /** @return {offsetX, offsetY, scaleX, scaleY}. */
    public static float[] inverse(int mode, int outerW, int outerH, int innerW, int innerH) {
        if (outerW <= 0 || outerH <= 0 || innerW <= 0 || innerH <= 0) return new float[]{0f, 0f, 1f, 1f};
        ViewTransformation fit = new ViewTransformation();
        fit.update(outerW, outerH, innerW, innerH);
        float inv = 1.0f / fit.aspect;
        // The exact half-size RedMagic mode is checked first by the compositor, whatever the selected output mode is.
        if (fit.isNativeAspectResolution() || mode == FIT) {
            return new float[]{fit.inputOffsetX(outerW, innerW), fit.inputOffsetY(outerH, innerH), inv, inv};
        }
        if (mode == STRETCH) {
            // The whole guest screen is stretched over the whole view.
            return new float[]{0f, 0f, (float) innerW / outerW, (float) innerH / outerH};
        }
        // FILL: one uniform scale that covers the view, centred (the parts that do not fit are cut off).
        float scale = Math.max((float) outerW / innerW, (float) outerH / innerH);
        return new float[]{(outerW - innerW * scale) * 0.5f, (outerH - innerH * scale) * 0.5f, 1.0f / scale, 1.0f / scale};
    }
}
