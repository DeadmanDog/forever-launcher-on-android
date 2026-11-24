package com.winlator.xserver.harness;

import com.winlator.xserver.Drawable;
import java.nio.ByteBuffer;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Only replaces Android library loading; PutImage copies are delegated to the real production C function through JNI. */
@Implements(Drawable.class)
public class ShadowCursorDrawable {
    @Implementation protected static void __staticInitializer__() { }
    @Implementation protected static void copyArea(short sx, short sy, short dx, short dy, short width, short height,
        short sourceStride, short destinationStride, ByteBuffer source, ByteBuffer destination) {
        CursorColorNative.copyArea(sx, sy, dx, dy, width, height, sourceStride, destinationStride, source, destination);
    }
    @Implementation protected static void drawAlphaMaskedBitmap(byte fr, byte fg, byte fb, byte br, byte bg, byte bb,
        ByteBuffer source, ByteBuffer mask, ByteBuffer destination) {
        CursorColorNative.drawAlphaMaskedBitmap(fr, fg, fb, br, bg, bb, source, mask, destination);
    }
}
