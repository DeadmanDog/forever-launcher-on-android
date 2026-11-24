package com.winlator.xserver;

import android.util.SparseArray;

public class PixmapManager extends XResourceManager {
    public final Visual visual;
    public final Visual[] supportedVisuals;
    public final PixmapFormat[] supportedPixmapFormats;
    /**
     * Depths a pixmap may have without a visual on the screen. libXrender only believes RENDER exists when pixmaps of depth
     * 1, 4, 8, 24 and 32 can be created (XRenderHasDepths probes the depths the screen does not list); without them
     * libXcursor reduced every colour cursor to black and white. Their storage is 32 bits per pixel like every drawable, and
     * they are not listed in the connection setup.
     */
    private final Visual[] pixmapOnlyVisuals;
    private final SparseArray<Pixmap> pixmaps = new SparseArray<>();

    public PixmapManager() {
        visual = new Visual(IDGenerator.generate(), true, 32, 24, 0xff0000, 0x00ff00, 0x0000ff);
        supportedVisuals = new Visual[]{visual, new Visual(IDGenerator.generate(), false, 1, 1, 0, 0, 0)};

        supportedPixmapFormats = new PixmapFormat[] {
                new PixmapFormat(1, 1, 32),
                new PixmapFormat(24, 32, 32),
                new PixmapFormat(32, 32, 32)
        };
        pixmapOnlyVisuals = new Visual[]{
                new Visual(IDGenerator.generate(), false, 4, 4, 0, 0, 0),
                new Visual(IDGenerator.generate(), false, 8, 8, 0, 0, 0),
                new Visual(IDGenerator.generate(), false, 24, 8, 0xff0000, 0x00ff00, 0x0000ff)
        };
    }

    public Pixmap getPixmap(int id) {
        return pixmaps.get(id);
    }

    public Pixmap createPixmap(Drawable drawable) {
        if (pixmaps.indexOfKey(drawable.id) >= 0) return null;
        Pixmap pixmap = new Pixmap(drawable);
        pixmaps.put(drawable.id, pixmap);
        triggerOnCreateResourceListener(pixmap);
        return pixmap;
    }

    public void freePixmap(int id) {
        triggerOnFreeResourceListener(pixmaps.get(id));
        pixmaps.remove(id);
    }

    public Visual getVisualForDepth(byte depth) {
        if (depth == visual.depth) return visual;
        for (Visual visual : supportedVisuals) {
            if (depth == visual.depth) return visual;
        }
        for (Visual visual : pixmapOnlyVisuals) {
            if (depth == visual.depth) return visual;
        }
        return null;
    }

    public Visual getVisual(int id) {
        if (id == visual.id) return visual;
        for (Visual visual : supportedVisuals) {
            if (id == visual.id && visual.displayable) return visual;
        }
        return null;
    }

}
