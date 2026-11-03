package com.winlator.xserver;

import android.graphics.Bitmap;

import com.winlator.core.Callback;
import com.winlator.math.Mathf;
import com.winlator.renderer.Texture;
import com.winlator.renderer.NativeTexture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Drawable extends XResource {
    private ByteBuffer data;
    public final short height;
    private boolean offscreenStorage;
    private Callback<Drawable> onDestroyListener;
    private Runnable onDrawListener;
    private final UpdateBatch updateBatch = new UpdateBatch();
    public final Object renderLock;
    private Texture texture;
    public final Visual visual;
    public final short width;

    private static native void copyArea(short s, short s2, short s3, short s4, short s5, short s6, short s7, short s8, ByteBuffer byteBuffer, ByteBuffer byteBuffer2);

    private static native void copyAreaOp(short s, short s2, short s3, short s4, short s5, short s6, short s7, short s8, ByteBuffer byteBuffer, ByteBuffer byteBuffer2, int i);

    private static native void drawAlphaMaskedBitmap(byte b, byte b2, byte b3, byte b4, byte b5, byte b6, ByteBuffer byteBuffer, ByteBuffer byteBuffer2, ByteBuffer byteBuffer3);

    private static native void drawBitmap(short srcX, short srcY, short dstX, short dstY, short width, short height, short srcWidth, short dstStride, ByteBuffer src, ByteBuffer dst);

    private static native void drawLine(short s, short s2, short s3, short s4, int i, short s5, short s6, ByteBuffer byteBuffer);

    private static native void fillRect(short s, short s2, short s3, short s4, int i, short s5, ByteBuffer byteBuffer);

    private static native void fromBitmap(Bitmap bitmap, ByteBuffer byteBuffer);

    static {
        System.loadLibrary("winlator");
    }

    public Drawable(int id, int width, int height, Visual visual) {
        this(id, width, height, visual, true);
    }

    /** Imported pixmaps already supply storage: no discarded CPU or AHB allocation. */
    public Drawable(int id, int width, int height, Visual visual, boolean allocateStorage) {
        super(id);
        this.texture = allocateStorage ? new Texture() : null;
        this.offscreenStorage = false;
        this.renderLock = new Object();
        this.width = (short)width;
        this.height = (short)height;
        this.visual = visual;

        if (!allocateStorage) return;
        this.data = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static Drawable fromBitmap(Bitmap bitmap) {
        Drawable drawable = new Drawable(0, bitmap.getWidth(), bitmap.getHeight(), null);
        fromBitmap(bitmap, drawable.data);
        return drawable;
    }

    public Texture getTexture() {
        return texture;
    }

    public void setTexture(Texture texture) {
        if (texture instanceof NativeTexture) data = ((NativeTexture)texture).getVirtualData();
        this.texture = texture;
    }

    public ByteBuffer getData() {
        return data;
    }

    // Alias for {@link #getData()}. Added to mirror the API expected by
    // {@code com.winlator.renderer.VulkanRenderer} (ported from Winlator-Ludashi),
    // which uses {@code getBuffer()}. Keeping the alias avoids diverging from upstream.
    public ByteBuffer getBuffer() {
        return data;
    }

    // A pixmap whose storage was imported read-only (a DRI3 file descriptor opened without write access) cannot be drawn into:
    // such requests are ignored instead of faulting on the read-only mapping.
    private volatile boolean readOnly;
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean value) { readOnly = value; }

    /**
     * Clips a copy to both rectangles (negative and oversized coordinates on either side move the other side with them, like
     * X11 does). Returns {srcX, srcY, dstX, dstY, width, height}, or null when nothing is left to copy.
     */
    private static int[] clipCopy(int srcX, int srcY, int dstX, int dstY, int width, int height,
                                  int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        if (dstX < 0) { srcX -= dstX; width += dstX; dstX = 0; }
        if (dstY < 0) { srcY -= dstY; height += dstY; dstY = 0; }
        if (srcX < 0) { dstX -= srcX; width += srcX; srcX = 0; }
        if (srcY < 0) { dstY -= srcY; height += srcY; srcY = 0; }
        if (dstX + width > dstWidth) width = dstWidth - dstX;
        if (dstY + height > dstHeight) height = dstHeight - dstY;
        if (srcX + width > srcWidth) width = srcWidth - srcX;
        if (srcY + height > srcHeight) height = srcHeight - srcY;
        if (width <= 0 || height <= 0) return null;
        return new int[]{srcX, srcY, dstX, dstY, width, height};
    }

    private volatile boolean directScanout;
    public boolean isDirectScanout() { return directScanout; }
    public void setDirectScanout(boolean value) { directScanout = value; }

    public void setData(ByteBuffer data) {
        this.data = data;
    }

    private short getStride() {
        return texture instanceof NativeTexture ? ((NativeTexture)texture).getStride() : width;
    }

    public void setOnDrawListener(Runnable onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public Callback<Drawable> getOnDestroyListener() {
        return onDestroyListener;
    }

    public void setOnDestroyListener(Callback<Drawable> onDestroyListener) {
        this.onDestroyListener = onDestroyListener;
    }

    public void drawImage(short srcX, short srcY, short dstX, short dstY, short width, short height, byte depth, ByteBuffer data, short totalWidth, short totalHeight) {
        ByteBuffer byteBuffer = this.data;
        if (byteBuffer == null || readOnly) {
            return;
        }
        boolean drew = false;
        if (depth == 1) {
            // The same clipping as the 32 bit path: the source rectangle and the destination position are honoured, and nothing
            // outside the drawable is written.
            int[] clip = clipCopy(srcX, srcY, dstX, dstY, width, height, totalWidth, totalHeight, this.width, this.height);
            if (clip != null) {
                drawBitmap((short)clip[0], (short)clip[1], (short)clip[2], (short)clip[3], (short)clip[4], (short)clip[5],
                    totalWidth, this.getStride(), data, byteBuffer);
                drew = true;
            }
        }
        else {
            if (depth == 24 || depth == 32) {
                int[] clip = clipCopy(srcX, srcY, dstX, dstY, width, height, totalWidth, totalHeight, this.width, this.height);
                if (clip != null) {
                    copyArea((short)clip[0], (short)clip[1], (short)clip[2], (short)clip[3], (short)clip[4], (short)clip[5],
                        totalWidth, this.getStride(), data, this.data);
                    drew = true;
                }
            }
        }
        this.data.rewind();
        data.rewind();
        // An image entirely outside the drawable (or of an unsupported depth) changed no pixel: the renderer is not told, so it
        // does not copy the whole window again for nothing.
        if (drew) forceUpdate();
    }

    public ByteBuffer getImage(short x, short y, short width, short height) {
        ByteBuffer dstData = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        if (this.data == null) {
            return dstData;
        }
        x = (short)Mathf.clamp(x, 0, this.width-1);
        y = (short)Mathf.clamp(y, 0, this.height-1);
        if ((x + width) > this.width) width = (short)(this.width - x);
        if ((y + height) > this.height) height = (short)(this.height - y);

        copyArea(x, y, (short)0, (short)0, width, height, this.getStride(), width, this.data, dstData);

        this.data.rewind();
        dstData.rewind();
        return dstData;
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable) {
        copyArea(srcX, srcY, dstX, dstY, width, height, drawable, GraphicsContext.Function.COPY);
    }

    public void copyArea(short srcX, short srcY, short dstX, short dstY, short width, short height, Drawable drawable, GraphicsContext.Function gcFunction) {
        if (this.data != null && drawable.data != null && !readOnly) {
            int[] clip = clipCopy(srcX, srcY, dstX, dstY, width, height, drawable.width, drawable.height, this.width, this.height);
            if (clip == null) return;
            short sx = (short)clip[0], sy = (short)clip[1], dx = (short)clip[2], dy = (short)clip[3], w = (short)clip[4], h = (short)clip[5];

            if (gcFunction == GraphicsContext.Function.COPY) {
                copyArea(sx, sy, dx, dy, w, h, drawable.getStride(), this.getStride(), drawable.data, this.data);
            }
            else copyAreaOp(sx, sy, dx, dy, w, h, drawable.getStride(), this.getStride(), drawable.data, this.data, gcFunction.ordinal());

            this.data.rewind();
            drawable.data.rewind();
            forceUpdate();
        }
    }

    public void fillColor(int color) {
        fillRect(0, 0, width, height, color);
    }

    public void fillRect(int x, int y, int width, int height, int color) {
        if (this.data == null || readOnly) {
            return;
        }
        // The part of the rectangle inside the drawable. (It used to move the origin onto the edge and keep the size, which
        // painted pixels for rectangles that are partly or entirely outside.)
        int[] r = RectClip.intersect(x, y, width, height, this.width, this.height);
        if (r == null) return;   // nothing visible: nothing drawn, nobody told

        fillRect((short)r[0], (short)r[1], (short)(r[2] - r[0]), (short)(r[3] - r[1]), color, this.getStride(), this.data);
        this.data.rewind();
        forceUpdate();
    }

    public void drawLines(int color, int lineWidth, short... points) {
        beginBatch();
        try {
            for (int i = 2; i < points.length; i += 2) {
                drawLine(points[i-2], points[i-1], points[i+0], points[i+1], color, (short)lineWidth);
            }
        } finally {
            endBatch();
        }
    }

    public void drawLine(int x0, int y0, int x1, int y1, int color, int lineWidth) {
        if (this.data == null || readOnly) {
            return;
        }
        // A line wholly outside the drawable changes nothing and is not announced.
        if (lineWidth < 1 || RectClip.intersect(Math.min(x0, x1), Math.min(y0, y1),
                (long) Math.abs((long) x1 - x0) + lineWidth, (long) Math.abs((long) y1 - y0) + lineWidth, this.width, this.height) == null) return;

        // The endpoints keep their real position and the native rasteriser cuts every block to the drawable. (Clamping each
        // endpoint on its own moved it along the edge and changed the slope of any line that left the drawable.)
        drawLine((short)x0, (short)y0, (short)x1, (short)y1, color, (short)lineWidth, this.getStride(), this.data);

        this.data.rewind();
        forceUpdate();
    }

    public void drawAlphaMaskedBitmap(byte foreRed, byte foreGreen, byte foreBlue, byte backRed, byte backGreen, byte backBlue, Drawable srcDrawable, Drawable maskDrawable) {
        ByteBuffer byteBuffer;
        ByteBuffer byteBuffer2 = this.data;
        if (byteBuffer2 != null && !readOnly && (byteBuffer = srcDrawable.data) != null) {
            ByteBuffer byteBuffer3 = maskDrawable.data;
            if (byteBuffer3 == null) {
                return;
            }
            drawAlphaMaskedBitmap(foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue, byteBuffer, byteBuffer3, byteBuffer2);
            this.data.rewind();
            forceUpdate();
        }
    }

    /** Notifies once for everything drawn between [beginBatch] and [endBatch]. Always pair them with try/finally. */
    public void beginBatch() {
        updateBatch.begin();
    }

    public void endBatch() {
        if (updateBatch.end()) notifyContentChanged();
    }

    public void forceUpdate() {
        if (updateBatch.changed()) notifyContentChanged();
    }

    private void notifyContentChanged() {
        if (!this.offscreenStorage) {
            Runnable runnable = this.onDrawListener;
            if (runnable != null) {
                runnable.run();
            }
        }
    }

}
