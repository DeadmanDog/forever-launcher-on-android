#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/bitmap.h>

/*
 * Software drawing for X11 drawables (32-bit B8G8R8X8 pixels stored in direct ByteBuffers).
 * All coordinates, sizes and strides arrive as Java shorts and keep the original 16-bit semantics.
 */

/*
 * A rectangle [x, x+width) x [y, y+height) with row pitch [stride] pixels must lie inside a buffer of [capacity] bytes. The last
 * row only needs its own width, not a full stride. All math is done in 64 bits: strides and widths come from 16-bit values
 * and their byte products overflow 16 and 32 bits (an 8192 px row is 32768 bytes).
 */
static int rectFits(jlong capacity, int x, int y, int width, int height, int stride) {
    if (capacity < 0 || x < 0 || y < 0 || width < 1 || height < 1 || stride < 1) return 0;
    if (x + (jlong) width > stride) return 0;
    const jlong lastPixel = ((jlong) (y + height - 1) * stride + x + width);
    return lastPixel * 4 <= capacity;
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_copyArea(JNIEnv *env, jclass obj, jshort srcX, jshort srcY, jshort dstX, jshort dstY,
                                            jshort width, jshort height, jshort srcStride, jshort dstStride,
                                            jobject srcData, jobject dstData) {
    uint8_t *src = (uint8_t *) (*env)->GetDirectBufferAddress(env, srcData);
    uint8_t *dst = (uint8_t *) (*env)->GetDirectBufferAddress(env, dstData);
    if (!src || !dst) return;
    // A request that leaves either buffer is ignored instead of touching memory outside it.
    if (!rectFits((*env)->GetDirectBufferCapacity(env, srcData), srcX, srcY, width, height, srcStride) ||
        !rectFits((*env)->GetDirectBufferCapacity(env, dstData), dstX, dstY, width, height, dstStride)) return;

    const size_t rowBytes = (size_t) width * 4;
    const size_t srcStep = (size_t) srcStride * 4;
    const size_t dstStep = (size_t) dstStride * 4;
    src += ((size_t) srcY * srcStride + srcX) * 4;
    dst += ((size_t) dstY * dstStride + dstX) * 4;
    if (srcX == 0 && dstX == 0 && width == srcStride && width == dstStride) {
        // Whole rows on both sides: the rectangle is one contiguous block of memory, so a single memmove replaces one call per
        // row. memmove handles an overlap of source and destination exactly like the row-by-row direction logic below.
        memmove(dst, src, rowBytes * (size_t) height);
        return;
    }
    if ((uintptr_t) dst > (uintptr_t) src) {
        // The destination lies after the source in memory: when the rectangles overlap (a downward scroll inside one drawable) the
        // rows must go bottom-up, or an early row would overwrite source rows that are still to be read. memmove only covers
        // the overlap inside a single row.
        src += (size_t) (height - 1) * srcStep;
        dst += (size_t) (height - 1) * dstStep;
        for (int i = 0; i < height; i++) {
            memmove(dst, src, rowBytes);
            src -= srcStep;
            dst -= dstStep;
        }
        return;
    }
    for (int i = 0; i < height; i++) {
        memmove(dst, src, rowBytes);
        src += srcStep;
        dst += dstStep;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_copyAreaOp(JNIEnv *env, jclass obj, jshort srcX, jshort srcY, jshort dstX, jshort dstY,
                                              jshort width, jshort height, jshort srcStride, jshort dstStride,
                                              jobject srcData, jobject dstData, jint gcFunction) {
    uint8_t *src = (uint8_t *) (*env)->GetDirectBufferAddress(env, srcData);
    uint8_t *dst = (uint8_t *) (*env)->GetDirectBufferAddress(env, dstData);

    if (!src || !dst) return;
    if (!rectFits((*env)->GetDirectBufferCapacity(env, srcData), srcX, srcY, width, height, srcStride) ||
        !rectFits((*env)->GetDirectBufferCapacity(env, dstData), dstX, dstY, width, height, dstStride)) return;

    size_t srcOffset = ((size_t) srcY * srcStride + srcX) * 4;
    size_t dstOffset = ((size_t) dstY * dstStride + dstX) * 4;
    const size_t srcStep = (size_t) srcStride * 4;
    const size_t dstStep = (size_t) dstStride * 4;

    // Overlap inside one drawable: when the destination lies after the source in memory the rows (and the pixels of a row) are
    // processed from the end, so that no source pixel is overwritten before it has been read.
    const int reverse = (uintptr_t) (dst + dstOffset) > (uintptr_t) (src + srcOffset);
    const uint32_t rows = (uint16_t) height;
    const uint32_t columns = (uint32_t) (uint16_t) width;
    for (uint32_t row = 0; row < rows; row++) {
        const uint32_t y = reverse ? rows - 1 - row : row;
        const size_t rowSrc = srcOffset + (size_t) y * srcStep;
        const size_t rowDst = dstOffset + (size_t) y * dstStep;
        for (uint32_t column = 0; column < columns; column++) {
            const uint32_t x = (reverse ? columns - 1 - column : column) * 4;
            const uint8_t *s = src + rowSrc + x;
            uint8_t *d = dst + rowDst + x;
            const uint32_t srcColor = (uint32_t) s[0] << 16 | (uint32_t) s[1] << 8 | s[2];
            const uint32_t dstColor = (uint32_t) d[0] << 16 | (uint32_t) d[1] << 8 | d[2];
            uint32_t result = dstColor;

            if ((uint32_t) gcFunction <= 15) {
                switch (gcFunction) {
                    case 0: result = 0; break;                          // CLEAR
                    case 1: result = srcColor & dstColor; break;        // AND
                    case 2: result = srcColor & ~dstColor; break;       // AND_REVERSE
                    case 3: result = srcColor; break;                   // COPY
                    case 4: result = ~srcColor & dstColor; break;       // AND_INVERTED
                    case 5: result = dstColor; break;                   // NO_OP
                    case 6: result = srcColor ^ dstColor; break;        // XOR
                    case 7: result = srcColor | dstColor; break;        // OR
                    case 8: result = ~(srcColor | dstColor); break;     // NOR
                    case 9: result = ~(srcColor ^ dstColor); break;     // EQUIV
                    case 10: result = ~dstColor; break;                 // INVERT
                    case 11: result = srcColor | ~dstColor; break;      // OR_REVERSE
                    case 12: result = ~srcColor; break;                 // COPY_INVERTED
                    case 13: result = ~srcColor | dstColor; break;      // OR_INVERTED
                    case 14: result = ~(srcColor & dstColor); break;    // NAND
                    case 15: result = 0xffffff; break;                  // SET
                }
            }

            d[0] = (uint8_t) (result >> 16);
            d[1] = (uint8_t) (result >> 8);
            d[2] = (uint8_t) result;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_fillRect(JNIEnv *env, jclass obj, jshort x, jshort y, jshort width, jshort height,
                                            jint color, jshort stride, jobject data) {
    uint8_t *dst = (uint8_t *) (*env)->GetDirectBufferAddress(env, data);
    if (!dst || !rectFits((*env)->GetDirectBufferCapacity(env, data), x, y, width, height, stride)) return;

    const uint8_t pixel[4] = {(uint8_t) color, (uint8_t) (color >> 8), (uint8_t) (color >> 16), 0xff};
    const size_t rowBytes = (size_t) width * 4;
    uint8_t *row = malloc(rowBytes);
    if (!row) return;
    for (size_t i = 0; i < rowBytes; i += 4) memcpy(row + i, pixel, 4);

    size_t offset = ((size_t) y * stride + x) * 4;
    const size_t step = (size_t) stride * 4;
    for (int i = 0; i < height; i++) {
        memcpy(dst + offset, row, rowBytes);
        offset += step;
    }
    free(row);
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_drawLine(JNIEnv *env, jclass obj, jshort x0, jshort y0, jshort x1, jshort y1,
                                            jint color, jshort lineWidth, jshort stride, jobject data) {
    uint8_t *dst = (uint8_t *) (*env)->GetDirectBufferAddress(env, data);
    const jlong capacity = (*env)->GetDirectBufferCapacity(env, data);
    if (!dst || lineWidth < 1 || stride < 1 || capacity < 0) return;

    int x = x0, y = y0;
    const int dx = abs(x1 - x0);
    const int dy = abs(y1 - y0);
    const int stepX = x1 > x0 ? 1 : -1;
    const int stepY = y1 > y0 ? 1 : -1;
    int err = dx - dy;

    const uint8_t pixel[4] = {(uint8_t) color, (uint8_t) (color >> 8), (uint8_t) (color >> 16), 0xff};
    const size_t rowBytes = (size_t) lineWidth * 4;
    uint8_t *row = malloc(rowBytes);
    if (!row) return;
    for (size_t i = 0; i < rowBytes; i += 4) memcpy(row + i, pixel, 4);

    while (1) {
        // The line keeps its real geometry (endpoints may lie outside the drawable); each block is cut to the buffer: columns
        // [0, stride), and a row only if its drawn part lies inside the buffer.
        const int bx0 = x < 0 ? 0 : x;
        int bx1 = x + lineWidth;
        if (bx1 > stride) bx1 = stride;
        if (bx1 > bx0) {
            const size_t cutBytes = (size_t) (bx1 - bx0) * 4;
            for (int i = 0; i < lineWidth; i++) {
                const int by = y + i;
                if (by < 0) continue;
                const jlong end = ((jlong) by * stride + bx1) * 4;
                if (end > capacity) break;
                memcpy(dst + ((size_t) by * stride + bx0) * 4, row, cutBytes);
            }
        }

        if ((uint16_t) x == (uint16_t) x1 && (uint16_t) y == (uint16_t) y1) break;

        const int e2 = 2 * err;
        if (e2 >= -dy) {
            err -= dy;
            x += stepX;
        }
        if (e2 <= dx) {
            err += dx;
            y += stepY;
        }
    }
    free(row);
}

/*
 * Draws the rectangle [srcX, srcX+width) x [srcY, srcY+height) of a 1 bit per pixel image whose rows are [srcWidth] bits wide (padded to
 * 32 bits) into the 32 bit drawable at (dstX, dstY), whose row pitch is [dstStride] pixels. Both rectangles must lie inside their buffers,
 * otherwise the request is ignored.
 */
JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_drawBitmap(JNIEnv *env, jclass obj, jshort srcX, jshort srcY, jshort dstX, jshort dstY,
                                              jshort width, jshort height, jshort srcWidth, jshort dstStride,
                                              jobject srcData, jobject dstData) {
    const uint8_t *src = (const uint8_t *) (*env)->GetDirectBufferAddress(env, srcData);
    uint32_t *dst = (uint32_t *) (*env)->GetDirectBufferAddress(env, dstData);
    if (!src || !dst || width < 1 || height < 1 || srcX < 0 || srcY < 0 || srcWidth < 1) return;
    if ((jlong) srcX + width > srcWidth) return;

    const size_t srcRow = (((size_t) srcWidth + 31) >> 3) & ~(size_t) 3;   // 1 bit per pixel, rows padded to 32 bits
    if (((jlong) srcY + height) * (jlong) srcRow > (*env)->GetDirectBufferCapacity(env, srcData)) return;
    if (!rectFits((*env)->GetDirectBufferCapacity(env, dstData), dstX, dstY, width, height, dstStride)) return;

    for (int y = 0; y < height; y++) {
        const uint8_t *sourceRow = src + (size_t) (srcY + y) * srcRow;
        uint32_t *destination = dst + (size_t) (dstY + y) * dstStride + dstX;
        for (int x = 0; x < width; x++) {
            const int bitIndex = srcX + x;
            const uint32_t bit = (sourceRow[bitIndex >> 3] >> (bitIndex & 7)) & 1;
            destination[x] = bit ? 0xffffff : 0;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(JNIEnv *env, jclass obj, jbyte foreRed, jbyte foreGreen, jbyte foreBlue,
                                                         jbyte backRed, jbyte backGreen, jbyte backBlue,
                                                         jobject srcData, jobject maskData, jobject dstData) {
    const uint32_t *src = (const uint32_t *) (*env)->GetDirectBufferAddress(env, srcData);
    const uint32_t *mask = (const uint32_t *) (*env)->GetDirectBufferAddress(env, maskData);
    uint32_t *dst = (uint32_t *) (*env)->GetDirectBufferAddress(env, dstData);
    // The three buffers are read and written in step: the shortest one bounds the loop.
    jlong count = (*env)->GetDirectBufferCapacity(env, dstData) / 4;
    const jlong srcCount = (*env)->GetDirectBufferCapacity(env, srcData) / 4;
    const jlong maskCount = (*env)->GetDirectBufferCapacity(env, maskData) / 4;
    if (srcCount < count) count = srcCount;
    if (maskCount < count) count = maskCount;
    if (!src || !mask || !dst || count < 1) return;

    // Each channel is one unsigned byte (the Java side passes the high byte of the protocol's 16-bit value). The old code mixed the
    // sign-extended bytes: a channel was either 0 or full, and a set blue sign turned every channel on (blue came out white).
    const uint32_t foreColor = ((uint32_t) (uint8_t) foreRed << 16) | ((uint32_t) (uint8_t) foreGreen << 8) | (uint32_t) (uint8_t) foreBlue;
    const uint32_t backColor = ((uint32_t) (uint8_t) backRed << 16) | ((uint32_t) (uint8_t) backGreen << 8) | (uint32_t) (uint8_t) backBlue;

    for (jlong i = 0; i < count; i++) {
        if (mask[i] == 0xffffff) dst[i] = (src[i] == 0xffffff ? foreColor : backColor) | 0xff000000;
        else dst[i] = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_xserver_Drawable_fromBitmap(JNIEnv *env, jclass obj, jobject bitmap, jobject data) {
    uint8_t *dst = (uint8_t *) (*env)->GetDirectBufferAddress(env, data);

    AndroidBitmapInfo info;
    void *pixels = NULL;
    AndroidBitmap_getInfo(env, bitmap, &info);
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (pixels) memcpy(dst, pixels, (size_t) info.width * info.height * 4);
    AndroidBitmap_unlockPixels(env, bitmap);
}
