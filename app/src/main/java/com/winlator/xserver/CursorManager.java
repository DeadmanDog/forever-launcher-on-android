package com.winlator.xserver;

import android.util.SparseArray;

import java.nio.IntBuffer;

public class CursorManager extends XResourceManager {
    private final SparseArray<Cursor> cursors = new SparseArray<>();
    private final DrawableManager drawableManager;

    public CursorManager(DrawableManager drawableManager) {
        this.drawableManager = drawableManager;
    }

    public Cursor getCursor(int id) {
        return cursors.get(id);
    }

    public Cursor createCursor(int id, short x, short y, Pixmap sourcePixmap, Pixmap maskPixmap) {
        if (cursors.indexOfKey(id) >= 0) return null;
        Drawable drawable = drawableManager.createDrawable(0, sourcePixmap.drawable.width, sourcePixmap.drawable.height, sourcePixmap.drawable.visual);
        Cursor cursor = new Cursor(id, x, y, drawable, sourcePixmap.drawable, maskPixmap != null ? maskPixmap.drawable : null);
        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    /**
     * RENDER CreateCursor: a full-colour cursor from a 32-bit picture. X gives premultiplied ARGB; the renderer blends straight
     * alpha, so the colour is divided back by its alpha. A fully transparent image is an invisible cursor.
     */
    public Cursor createArgbCursor(int id, short x, short y, Drawable source) {
        if (cursors.indexOfKey(id) >= 0) return null;
        Drawable drawable = drawableManager.createDrawable(0, source.width, source.height, source.visual);
        IntBuffer src = source.getData().asIntBuffer(), dst = drawable.getData().asIntBuffer();
        boolean visible = false;
        for (int i = 0, n = Math.min(src.capacity(), dst.capacity()); i < n; i++) {
            dst.put(i, unpremultiply(src.get(i)));
            if ((src.get(i) >>> 24) != 0) visible = true;
        }
        drawable.forceUpdate();
        Cursor cursor = new Cursor(id, x, y, drawable, drawable, null);
        cursor.setVisible(visible);
        cursors.put(id, cursor);
        triggerOnCreateResourceListener(cursor);
        return cursor;
    }

    public static int unpremultiply(int argb) {
        int a = argb >>> 24;
        if (a == 0) return 0;
        if (a == 255) return argb;
        int r = Math.min(255, (((argb >> 16) & 0xff) * 255 + a / 2) / a);
        int g = Math.min(255, (((argb >> 8) & 0xff) * 255 + a / 2) / a);
        int b = Math.min(255, ((argb & 0xff) * 255 + a / 2) / a);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public void freeCursor(int id) {
        triggerOnFreeResourceListener(cursors.get(id));
        cursors.remove(id);
    }

    private static boolean isEmptyMaskImage(Drawable maskImage) {
        IntBuffer maskData = maskImage.getData().asIntBuffer();
        boolean result = true;
        for (int i = 0; i < maskData.capacity(); i++) {
            if (maskData.get(i) != 0x000000) {
                result = false;
                break;
            }
        }
        return result;
    }

    public void recolorCursor(Cursor cursor, byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue) {
        if (cursor.maskImage != null) {
            boolean visible = !isEmptyMaskImage(cursor.maskImage);
            cursor.setVisible(visible);
            if (visible) cursor.cursorImage.drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, cursor.sourceImage, cursor.maskImage);
        }
    }
}