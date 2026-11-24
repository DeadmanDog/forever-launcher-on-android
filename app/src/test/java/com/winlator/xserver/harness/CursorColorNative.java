package com.winlator.xserver.harness;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Host-only JNI bridge to the unchanged production drawable.c, not a Java copy of its pixel operations. */
public final class CursorColorNative {
    static {
        try {
            Path out = Path.of("build/cursor-color-proof").toAbsolutePath();
            Files.createDirectories(out);
            String source = new String(Files.readAllBytes(Path.of("src/main/cpp/winlator/drawable.c")), java.nio.charset.StandardCharsets.UTF_8);
            // Android Bitmap is unrelated to X PutImage. All preceding drawing functions stay byte-for-byte intact.
            source = source.substring(0, source.indexOf("JNIEXPORT void JNICALL\nJava_com_winlator_xserver_Drawable_fromBitmap"))
                .replace("#include <android/bitmap.h>", "");
            source = "#define Java_com_winlator_xserver_Drawable_copyArea Java_com_winlator_xserver_harness_CursorColorNative_copyArea\n"
                + "#define Java_com_winlator_xserver_Drawable_drawAlphaMaskedBitmap Java_com_winlator_xserver_harness_CursorColorNative_drawAlphaMaskedBitmap\n" + source;
            Path input = out.resolve("drawable-jni.c"), library = out.resolve("libcursor_color_test.so");
            Files.write(input, source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path jdk = Path.of(System.getProperty("java.home"));
            Process compiler = new ProcessBuilder(List.of("cc", "-shared", "-fPIC", "-O1", "-Wall", "-Werror",
                "-I" + jdk.resolve("include"), "-I" + jdk.resolve("include/linux"), input.toString(), "-o", library.toString()))
                .redirectErrorStream(true).start();
            String log = new String(compiler.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (compiler.waitFor() != 0) throw new IllegalStateException("cursor JNI compile failed: " + log);
            System.load(library.toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    public static native void copyArea(short sx, short sy, short dx, short dy, short width, short height,
        short sourceStride, short destinationStride, ByteBuffer source, ByteBuffer destination);
    public static native void drawAlphaMaskedBitmap(byte fr, byte fg, byte fb, byte br, byte bg, byte bb,
        ByteBuffer source, ByteBuffer mask, ByteBuffer destination);
}
