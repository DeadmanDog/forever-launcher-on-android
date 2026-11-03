package com.winlator.xserver;

/** Rectangle clipping against a drawable, in long arithmetic (the request values are 16-bit but their sums are not). */
public final class RectClip {
    private RectClip() {}

    /**
     * The part of the rectangle (x, y, width, height) that lies inside [0, boundW) x [0, boundH), as {x0, y0, x1, y1}
     * (end exclusive), or null when nothing of it is inside.
     */
    public static int[] intersect(long x, long y, long width, long height, int boundW, int boundH) {
        if (width <= 0 || height <= 0 || boundW <= 0 || boundH <= 0) return null;
        long x0 = Math.max(0L, x), y0 = Math.max(0L, y);
        long x1 = Math.min((long) boundW, x + width), y1 = Math.min((long) boundH, y + height);
        if (x1 <= x0 || y1 <= y0) return null;
        return new int[]{(int) x0, (int) y0, (int) x1, (int) y1};
    }

    /** Number of pixels of the rectangle that are inside the bounds. */
    public static long visibleArea(long x, long y, long width, long height, int boundW, int boundH) {
        int[] r = intersect(x, y, width, height, boundW, boundH);
        return r == null ? 0L : (long) (r[2] - r[0]) * (r[3] - r[1]);
    }
}
