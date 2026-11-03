/* Host regression for the bounds-checked software drawing in app/src/main/cpp/winlator/drawable.c (fake JNI function table). */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The production file reads <android/bitmap.h>; the bitmap entry points are not exercised here. */
JNIEXPORT void JNICALL Java_com_winlator_xserver_Drawable_drawLine(JNIEnv*, jclass, jshort, jshort, jshort, jshort, jint, jshort, jshort, jobject);
JNIEXPORT void JNICALL Java_com_winlator_xserver_Drawable_drawBitmap(JNIEnv*, jclass, jshort, jshort, jshort, jshort, jshort, jshort, jshort, jshort, jobject, jobject);
JNIEXPORT void JNICALL Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(JNIEnv*, jclass, jbyte, jbyte, jbyte, jbyte, jbyte, jbyte, jobject, jobject, jobject);
JNIEXPORT void JNICALL Java_com_winlator_xserver_Drawable_copyArea(JNIEnv*, jclass, jshort, jshort, jshort, jshort, jshort, jshort, jshort, jshort, jobject, jobject);
JNIEXPORT void JNICALL Java_com_winlator_xserver_Drawable_copyAreaOp(JNIEnv*, jclass, jshort, jshort, jshort, jshort, jshort, jshort, jshort, jshort, jobject, jobject, jint);
JNIEXPORT void JNICALL Java_com_winlator_xserver_Drawable_fillRect(JNIEnv*, jclass, jshort, jshort, jshort, jshort, jint, jshort, jobject);

typedef struct { void* address; jlong capacity; } Buffer;
static void* JNICALL fakeAddress(JNIEnv* env, jobject buffer) { (void) env; return ((Buffer*) buffer)->address; }
static jlong JNICALL fakeCapacity(JNIEnv* env, jobject buffer) { (void) env; return ((Buffer*) buffer)->capacity; }

static void check(int ok, const char* message) { if (!ok) { fprintf(stderr, "FAIL %s\n", message); exit(2); } }

static int allEqual(const uint8_t* bytes, size_t length, uint8_t value) {
    for (size_t i = 0; i < length; i++) if (bytes[i] != value) return 0;
    return 1;
}

int main(void) {
    struct JNINativeInterface_ table; memset(&table, 0, sizeof(table));
    table.GetDirectBufferAddress = fakeAddress; table.GetDirectBufferCapacity = fakeCapacity;
    JNIEnv env = &table; JNIEnv* e = &env;

    /* 1. an in-bounds copy lands where it should */
    uint8_t srcPixels[10 * 10 * 4], dstPixels[10 * 10 * 4];
    memset(srcPixels, 7, sizeof(srcPixels)); memset(dstPixels, 0, sizeof(dstPixels));
    Buffer src = { srcPixels, sizeof(srcPixels) }, dst = { dstPixels, sizeof(dstPixels) };
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 1, 1, 2, 2, 3, 3, 10, 10, (jobject) &src, (jobject) &dst);
    check(dstPixels[(2 * 10 + 2) * 4] == 7 && dstPixels[(4 * 10 + 4) * 4 + 3] == 7, "in-bounds copy");
    check(dstPixels[(2 * 10 + 1) * 4] == 0 && dstPixels[(5 * 10 + 2) * 4] == 0, "copy stays inside the rectangle");

    /* 2. requests that leave either buffer are ignored, not executed on foreign memory */
    memset(dstPixels, 0, sizeof(dstPixels));
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, -1, 0, 0, 0, 3, 3, 10, 10, (jobject) &src, (jobject) &dst);
    check(allEqual(dstPixels, sizeof(dstPixels), 0), "negative source x is ignored");
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 8, 0, 0, 0, 5, 3, 10, 10, (jobject) &src, (jobject) &dst);
    check(allEqual(dstPixels, sizeof(dstPixels), 0), "source wider than its row is ignored");
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 0, 8, 0, 0, 3, 5, 10, 10, (jobject) &src, (jobject) &dst);
    check(allEqual(dstPixels, sizeof(dstPixels), 0), "source below the buffer is ignored");
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 0, 0, 9, 9, 3, 3, 10, 10, (jobject) &src, (jobject) &dst);
    check(allEqual(dstPixels, sizeof(dstPixels), 0), "destination outside the buffer is ignored");
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 0, 0, 0, 0, 0, 3, 10, 10, (jobject) &src, (jobject) &dst);
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 0, 0, 0, 0, 3, 3, 0, 10, (jobject) &src, (jobject) &dst);
    check(allEqual(dstPixels, sizeof(dstPixels), 0), "empty and zero-stride requests are ignored");

    /* 3. a wide row: 8192 px = 32768 bytes does not fit a 16-bit step (it used to wrap to a negative one) */
    const int wide = 8192, rows = 3;
    uint8_t* bigSrc = malloc((size_t) wide * rows * 4); uint8_t* bigDst = calloc((size_t) wide * rows * 4, 1);
    for (size_t i = 0; i < (size_t) wide * rows * 4; i++) bigSrc[i] = (uint8_t) (i * 31 + 5);
    Buffer bs = { bigSrc, (jlong) wide * rows * 4 }, bd = { bigDst, (jlong) wide * rows * 4 };
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 0, 0, 0, 0, (jshort) wide, rows, (jshort) wide, (jshort) wide, (jobject) &bs, (jobject) &bd);
    check(memcmp(bigSrc, bigDst, (size_t) wide * rows * 4) == 0, "8192 px wide rows are copied completely");
    memset(bigDst, 0, (size_t) wide * rows * 4);
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 100, 1, 200, 2, 5000, 1, (jshort) wide, (jshort) wide, (jobject) &bs, (jobject) &bd);
    check(memcmp(bigSrc + ((size_t) 1 * wide + 100) * 4, bigDst + ((size_t) 2 * wide + 200) * 4, 5000 * 4) == 0, "wide partial row copy");
    check(bigDst[((size_t) 2 * wide + 199) * 4] == 0 && bigDst[((size_t) 2 * wide + 5200) * 4] == 0, "wide partial row stays inside its span");

    /* 4. a copy inside one drawable may overlap itself */
    uint8_t same[8 * 4];
    for (int i = 0; i < 8; i++) { same[i * 4] = (uint8_t) (i + 1); same[i * 4 + 1] = same[i * 4 + 2] = same[i * 4 + 3] = 0; }
    Buffer one = { same, sizeof(same) };
    Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 0, 0, 2, 0, 6, 1, 8, 8, (jobject) &one, (jobject) &one);
    check(same[2 * 4] == 1 && same[7 * 4] == 6, "overlapping copy keeps the source order (memmove)");

    /* 5. fill: inside, and requests outside the buffer */
    memset(dstPixels, 0, sizeof(dstPixels));
    Java_com_winlator_xserver_Drawable_fillRect(e, NULL, 1, 1, 2, 2, 0x00112233, 10, (jobject) &dst);
    check(dstPixels[(1 * 10 + 1) * 4] == 0x33 && dstPixels[(2 * 10 + 2) * 4 + 2] == 0x11 && dstPixels[(3 * 10 + 3) * 4] == 0, "fill inside");
    memset(dstPixels, 0, sizeof(dstPixels));
    Java_com_winlator_xserver_Drawable_fillRect(e, NULL, 8, 8, 5, 5, 0x00ffffff, 10, (jobject) &dst);
    Java_com_winlator_xserver_Drawable_fillRect(e, NULL, 0, 9, 10, 4, 0x00ffffff, 10, (jobject) &dst);
    check(allEqual(dstPixels, sizeof(dstPixels), 0), "fill beyond the buffer is ignored");

    /* 6. the operation variant is bounded the same way */
    memset(dstPixels, 0, sizeof(dstPixels));
    Java_com_winlator_xserver_Drawable_copyAreaOp(e, NULL, 0, 0, 8, 8, 5, 5, 10, 10, (jobject) &src, (jobject) &dst, 3);
    check(allEqual(dstPixels, sizeof(dstPixels), 0), "copyAreaOp beyond the buffer is ignored");
    Java_com_winlator_xserver_Drawable_copyAreaOp(e, NULL, 0, 0, 0, 0, 2, 2, 10, 10, (jobject) &src, (jobject) &dst, 3);
    check(dstPixels[0] == 7 && dstPixels[(1 * 10 + 1) * 4 + 2] == 7, "copyAreaOp inside the buffer");

    /* 7. every overlapping rectangle of one 8x8 drawable equals the result computed from a snapshot of the original pixels */
    for (int fn = 0; fn < 5; fn++) {
        const int gc = fn == 0 ? -1 : (fn == 1 ? 3 : (fn == 2 ? 6 : (fn == 3 ? 1 : 7)));   /* -1 = copyArea, then COPY/XOR/AND/OR */
        for (int w = 1; w <= 5; w++) for (int h = 1; h <= 5; h++)
        for (int sx = 0; sx + w <= 8; sx++) for (int sy = 0; sy + h <= 8; sy++)
        for (int dx = 0; dx + w <= 8; dx++) for (int dy = 0; dy + h <= 8; dy++) {
            uint8_t grid[8 * 8 * 4], snapshot[8 * 8 * 4], expected[8 * 8 * 4];
            for (int i = 0; i < 8 * 8; i++) { grid[i * 4] = (uint8_t) (i * 7 + 3); grid[i * 4 + 1] = (uint8_t) (i * 13 + 1); grid[i * 4 + 2] = (uint8_t) (i * 29 + 5); grid[i * 4 + 3] = 0xff; }
            memcpy(snapshot, grid, sizeof(grid)); memcpy(expected, grid, sizeof(grid));
            for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) {
                const uint8_t *sp = snapshot + ((sy + yy) * 8 + sx + xx) * 4; const uint8_t *dp = snapshot + ((dy + yy) * 8 + dx + xx) * 4;
                uint8_t *out = expected + ((dy + yy) * 8 + dx + xx) * 4;
                for (int c = 0; c < 3; c++) {
                    uint8_t v = sp[c];
                    if (gc == 6) v = sp[c] ^ dp[c]; else if (gc == 1) v = sp[c] & dp[c]; else if (gc == 7) v = sp[c] | dp[c];
                    out[c] = v;
                }
                if (gc < 0) out[3] = sp[3];
            }
            Buffer one8 = { grid, sizeof(grid) };
            if (gc < 0) Java_com_winlator_xserver_Drawable_copyArea(e, NULL, (jshort) sx, (jshort) sy, (jshort) dx, (jshort) dy, (jshort) w, (jshort) h, 8, 8, (jobject) &one8, (jobject) &one8);
            else Java_com_winlator_xserver_Drawable_copyAreaOp(e, NULL, (jshort) sx, (jshort) sy, (jshort) dx, (jshort) dy, (jshort) w, (jshort) h, 8, 8, (jobject) &one8, (jobject) &one8, gc);
            if (memcmp(grid, expected, sizeof(grid)) != 0) {
                fprintf(stderr, "FAIL overlap fn=%d src=(%d,%d) dst=(%d,%d) %dx%d\n", gc, sx, sy, dx, dy, w, h);
                return 2;
            }
        }
    }

    /* 8. 1 bit PutImage: the destination position, the source rectangle and the destination size are honoured */
    {
        uint8_t bits[4 * 2];                       /* 2 rows of a 10 bit wide image: 4 bytes per row (padded to 32 bits) */
        memset(bits, 0, sizeof(bits)); bits[0] = 0x03; bits[4] = 0x02;   /* row0: bits 0,1 set; row1: bit 1 set */
        Buffer bitmap = { bits, sizeof(bits) };
        uint32_t target[4 * 4]; memset(target, 0xaa, sizeof(target));
        Buffer tbuf = { target, sizeof(target) };
        Java_com_winlator_xserver_Drawable_drawBitmap(e, NULL, 0, 0, 1, 1, 3, 2, 10, 4, (jobject) &bitmap, (jobject) &tbuf);
        check(target[1 * 4 + 1] == 0xffffff && target[1 * 4 + 2] == 0xffffff && target[1 * 4 + 3] == 0, "bitmap row 0 lands at (1,1)");
        check(target[2 * 4 + 1] == 0 && target[2 * 4 + 2] == 0xffffff, "bitmap row 1 lands at (1,2)");
        check(target[0] == 0xaaaaaaaa && target[3 * 4] == 0xaaaaaaaa, "pixels outside the rectangle are untouched");
        /* a 4x2 bitmap into a 2x2 destination (the former heap overflow): ignored */
        uint32_t small[2 * 2]; memset(small, 0x55, sizeof(small)); Buffer sbuf = { small, sizeof(small) };
        Java_com_winlator_xserver_Drawable_drawBitmap(e, NULL, 0, 0, 0, 0, 4, 2, 10, 2, (jobject) &bitmap, (jobject) &sbuf);
        check(small[0] == 0x55555555 && small[3] == 0x55555555, "a bitmap larger than its destination is ignored");
        /* a source rectangle outside the image, a truncated source */
        Java_com_winlator_xserver_Drawable_drawBitmap(e, NULL, 8, 0, 0, 0, 4, 1, 10, 4, (jobject) &bitmap, (jobject) &tbuf);
        Buffer shortBits = { bits, 4 };
        uint32_t keep[4 * 4]; memset(keep, 0x77, sizeof(keep)); Buffer kbuf = { keep, sizeof(keep) };
        Java_com_winlator_xserver_Drawable_drawBitmap(e, NULL, 0, 0, 0, 0, 3, 2, 10, 4, (jobject) &shortBits, (jobject) &kbuf);
        check(keep[0] == 0x77777777, "a truncated bit source is ignored");
    }

    /* 9. drawLine: a 8192 px row pitch used to turn into a negative 16-bit step; nothing may be written before the buffer */
    {
        const int wide = 8192;
        uint8_t* guardBuf = calloc((size_t) wide * 2 * 4 + 64, 1);
        uint8_t* area = guardBuf + 32;
        memset(guardBuf, 0xcd, 32); memset(area + (size_t) wide * 2 * 4, 0xcd, 32);
        Buffer lbuf = { area, (jlong) wide * 2 * 4 };
        Java_com_winlator_xserver_Drawable_drawLine(e, NULL, 0, 0, 0, 0, 0x00ff0000, 2, (jshort) wide, (jobject) &lbuf);
        check(area[0] == 0 && area[1] == 0 && area[2] == 0xff && area[3] == 0xff, "first pixel drawn");
        check(area[(size_t) wide * 4 + 2] == 0xff, "second row of the 2 px line is on the next row, not before the buffer");
        for (int i = 0; i < 32; i++) check(guardBuf[i] == 0xcd, "no write before the buffer");
        for (int i = 0; i < 32; i++) check(area[(size_t) wide * 2 * 4 + i] == 0xcd, "no write after the buffer");
        free(guardBuf);
        /* a normal diagonal line */
        uint32_t small[8 * 8]; memset(small, 0, sizeof(small)); Buffer sb = { small, sizeof(small) };
        Java_com_winlator_xserver_Drawable_drawLine(e, NULL, 0, 0, 5, 5, 0x00112233, 1, 8, (jobject) &sb);
        for (int i = 0; i <= 5; i++) check((small[i * 8 + i] & 0xffffff) == 0x332211 || (small[i * 8 + i] & 0xffffff) == 0x112233, "diagonal pixel");
        /* a line whose end leaves the buffer is clipped, not written outside */
        uint32_t tiny[4 * 4]; memset(tiny, 0, sizeof(tiny)); Buffer tb = { tiny, sizeof(tiny) };
        Java_com_winlator_xserver_Drawable_drawLine(e, NULL, 0, 0, 40, 40, 0x00ffffff, 1, 4, (jobject) &tb);
        check((tiny[0] & 0xffffff) != 0, "the part inside is drawn");
    }

    /* 10. masked bitmap: the loop is bounded by the shortest of the three buffers */
    {
        uint32_t src4[4] = { 0xffffff, 0xffffff, 0, 0 }, mask4[2] = { 0xffffff, 0xffffff }, out[8]; memset(out, 0x11, sizeof(out));
        Buffer sbuf = { src4, sizeof(src4) }, mbuf = { mask4, sizeof(mask4) }, obuf = { out, sizeof(out) };
        Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(e, NULL, 1, 2, 3, 4, 5, 6, (jobject) &sbuf, (jobject) &mbuf, (jobject) &obuf);
        check(out[2] == 0x11111111 && out[7] == 0x11111111, "nothing written beyond the mask");
    }

    /* 11. full-width spans are one memmove: scrolling inside one drawable gives the same result as the row-by-row copy */
    {
        enum { W = 6, H = 8 };
        uint32_t before[W * H], work[W * H], expect[W * H];
        for (int i = 0; i < W * H; i++) before[i] = 0x01000000u + (uint32_t) i * 0x10101u;
        /* down by two rows (overlapping, destination after source) */
        memcpy(work, before, sizeof(work)); memcpy(expect, before, sizeof(expect));
        for (int r = 5; r >= 0; r--) memcpy(&expect[(r + 2) * W], &before[r * W], W * 4);   /* reference: bottom-up row copy */
        Buffer wb = { work, sizeof(work) };
        Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 0, 0, 0, 2, W, 6, W, W, (jobject) &wb, (jobject) &wb);
        check(memcmp(work, expect, sizeof(work)) == 0, "full-span downward scroll equals the row-wise result");
        /* up by three rows (overlapping, destination before source) */
        memcpy(work, before, sizeof(work)); memcpy(expect, before, sizeof(expect));
        for (int r = 0; r < 5; r++) memcpy(&expect[r * W], &before[(r + 3) * W], W * 4);
        Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 0, 3, 0, 0, W, 5, W, W, (jobject) &wb, (jobject) &wb);
        check(memcmp(work, expect, sizeof(work)) == 0, "full-span upward scroll equals the row-wise result");
        /* between two buffers, and a partial-width copy (not the fast path) is unchanged */
        uint32_t other[W * H]; memset(other, 0, sizeof(other)); Buffer ob2 = { other, sizeof(other) }, bb = { before, sizeof(before) };
        Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 0, 1, 0, 0, W, 4, W, W, (jobject) &bb, (jobject) &ob2);
        check(memcmp(other, &before[W], W * 4 * 4) == 0 && other[4 * W] == 0, "full-span copy between buffers");
        memset(other, 0, sizeof(other));
        Java_com_winlator_xserver_Drawable_copyArea(e, NULL, 1, 1, 2, 3, 3, 2, W, W, (jobject) &bb, (jobject) &ob2);
        check(other[3 * W + 2] == before[1 * W + 1] && other[4 * W + 4] == before[2 * W + 3] && other[3 * W + 1] == 0, "partial-width copy keeps its rows apart");
    }

    /* 12. a line that leaves the drawable keeps its real slope: it equals the plain Bresenham line cut pixel by pixel */
    {
        enum { N = 8 };
        static const int lines[][4] = { { -3, 1, 6, 6 }, { 0, 1, 6, 6 }, { 9, 2, 1, 7 }, { 3, -4, 5, 12 }, { -20, -20, 20, 20 }, { 2, 6, 7, 0 } };
        for (unsigned n = 0; n < sizeof(lines) / sizeof(lines[0]); n++) {
            uint32_t got[N * N], want[N * N]; memset(got, 0, sizeof(got)); memset(want, 0, sizeof(want));
            Buffer gb = { got, sizeof(got) };
            Java_com_winlator_xserver_Drawable_drawLine(e, NULL, (jshort) lines[n][0], (jshort) lines[n][1], (jshort) lines[n][2], (jshort) lines[n][3], 0x00abcdef, 1, N, (jobject) &gb);
            int x = lines[n][0], y = lines[n][1];
            const int dx = abs(lines[n][2] - x), dy = abs(lines[n][3] - y), sx = lines[n][2] > x ? 1 : -1, sy = lines[n][3] > y ? 1 : -1;
            int err = dx - dy;
            for (;;) {
                if (x >= 0 && x < N && y >= 0 && y < N) want[y * N + x] = 0xffabcdefu;   /* bytes ef cd ab ff in memory */
                if (x == lines[n][2] && y == lines[n][3]) break;
                const int e2 = 2 * err;
                if (e2 >= -dy) { err -= dy; x += sx; }
                if (e2 <= dx) { err += dx; y += sy; }
            }
            check(memcmp(got, want, sizeof(got)) == 0, "a cut line has the pixels of the uncut line inside the drawable");
        }
        /* thick line: a block that is only partly inside draws its visible part (it used to be skipped as a whole) */
        uint32_t thick[N * N]; memset(thick, 0, sizeof(thick)); Buffer tk = { thick, sizeof(thick) };
        Java_com_winlator_xserver_Drawable_drawLine(e, NULL, -1, 2, -1, 2, 0x00ffffff, 3, N, (jobject) &tk);
        check(thick[2 * N + 0] != 0 && thick[2 * N + 1] != 0 && thick[4 * N + 1] != 0 && thick[2 * N + 2] == 0 && thick[1 * N + 0] == 0, "a thick block over the edge draws its visible part");
        /* nothing at all inside: nothing written */
        memset(thick, 0, sizeof(thick));
        Java_com_winlator_xserver_Drawable_drawLine(e, NULL, -30, -30, -20, -2, 0x00ffffff, 1, N, (jobject) &tk);
        check(allEqual((const uint8_t*) thick, sizeof(thick), 0), "a line wholly outside writes nothing");
    }

    /* 13. monochrome cursor colours: each channel is an unsigned byte, not a sign */
    {
        uint32_t srcWhite[1] = { 0xffffff }, srcBlack[1] = { 0 }, maskOn[1] = { 0xffffff }, out1[1];
        Buffer sw = { srcWhite, 4 }, sk = { srcBlack, 4 }, mk = { maskOn, 4 }, o1 = { out1, 4 };
        /* foreground (source bit set): pure blue must stay blue */
        Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(e, NULL, 0, 0, (jbyte) 0xff, 0, 0, 0, (jobject) &sw, (jobject) &mk, (jobject) &o1);
        check(out1[0] == 0xff0000ffu, "blue mono cursor colour");
        Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(e, NULL, (jbyte) 0xff, 0, 0, 0, 0, 0, (jobject) &sw, (jobject) &mk, (jobject) &o1);
        check(out1[0] == 0xffff0000u, "red mono cursor colour");
        Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(e, NULL, 0, (jbyte) 0xff, 0, 0, 0, 0, (jobject) &sw, (jobject) &mk, (jobject) &o1);
        check(out1[0] == 0xff00ff00u, "green mono cursor colour");
        /* an in-between colour keeps its value (it used to become 0 or full) */
        Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(e, NULL, (jbyte) 0x80, (jbyte) 0x40, (jbyte) 0x20, 0, 0, 0, (jobject) &sw, (jobject) &mk, (jobject) &o1);
        check(out1[0] == 0xff804020u, "intermediate mono cursor colour");
        /* background (source bit clear) with white, and the usual black/white pair */
        Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(e, NULL, 0, 0, 0, (jbyte) 0xff, (jbyte) 0xff, (jbyte) 0xff, (jobject) &sk, (jobject) &mk, (jobject) &o1);
        check(out1[0] == 0xffffffffu, "white background of a black and white cursor");
        Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(e, NULL, 0, 0, 0, (jbyte) 0xff, (jbyte) 0xff, (jbyte) 0xff, (jobject) &sw, (jobject) &mk, (jobject) &o1);
        check(out1[0] == 0xff000000u, "black foreground of a black and white cursor");
        uint32_t maskOff[1] = { 0 }; Buffer mo = { maskOff, 4 };
        Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap(e, NULL, 0, 0, (jbyte) 0xff, 0, 0, 0, (jobject) &sw, (jobject) &mo, (jobject) &o1);
        check(out1[0] == 0, "a masked-out pixel stays transparent");
    }

    free(bigSrc); free(bigDst);
    puts("PASS drawable bounds");
    return 0;
}
