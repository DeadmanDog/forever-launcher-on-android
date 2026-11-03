package com.winlator.renderer;

public class ViewTransformation {
    public int viewOffsetX;
    public int viewOffsetY;
    public int viewWidth;
    public int viewHeight;
    public float aspect;
    public float sceneScaleX;
    public float sceneScaleY;
    public float sceneOffsetX;
    public float sceneOffsetY;
    private boolean nativeAspectResolution;

    public void update(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        // 1344x608 is exactly half of the RedMagic 11 Pro display (2688x1216): one uniform whole-number scale, no edge rounding.
        nativeAspectResolution = innerWidth == 1344 && innerHeight == 608;
        aspect = Math.min((float)outerWidth / innerWidth, (float)outerHeight / innerHeight);
        if (nativeAspectResolution && outerWidth > 0 && outerHeight > 0) {
            float fill = Math.max((float)outerWidth / innerWidth, (float)outerHeight / innerHeight);
            // Uniform scaling only. Accept at most one guest pixel at each edge
            // for physical-display integer rounding; wider mismatches stay FIT.
            float edgeX = (innerWidth * fill - outerWidth) / (2.0f * fill);
            float edgeY = (innerHeight * fill - outerHeight) / (2.0f * fill);
            if (edgeX <= 1.0f && edgeY <= 1.0f) aspect = fill;
        }
        viewWidth = (int)Math.ceil(innerWidth * aspect);
        viewHeight = (int)Math.ceil(innerHeight * aspect);
        viewOffsetX = (int)((outerWidth - innerWidth * aspect) * 0.5f);
        viewOffsetY = (int)((outerHeight - innerHeight * aspect) * 0.5f);

        sceneScaleX = (innerWidth * aspect) / outerWidth;
        sceneScaleY = (innerHeight * aspect) / outerHeight;
        sceneOffsetX = (innerWidth - innerWidth * sceneScaleX) * 0.5f;
        sceneOffsetY = (innerHeight - innerHeight * sceneScaleY) * 0.5f;
    }
    public boolean isNativeAspectResolution() { return nativeAspectResolution; }

    // Input uses exactly the continuous compositor transform, including the
    // subpixel offset. Keep the historical integer offsets for other modes.
    public float inputOffsetX(int outerWidth, int innerWidth) {
        return nativeAspectResolution ? (outerWidth - innerWidth * aspect) * 0.5f : viewOffsetX;
    }
    public float inputOffsetY(int outerHeight, int innerHeight) {
        return nativeAspectResolution ? (outerHeight - innerHeight * aspect) * 0.5f : viewOffsetY;
    }
}
