package com.winlator.xserver.requests;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Visual;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.extensions.Extension;

import java.io.IOException;

/**
 * Core requests this server has no real support for, but whose caller waits for a reply: a minimal valid reply (or the error
 * Xlib expects and swallows) instead of silence, which left the caller blocked for ever. Opcodes from the X11 protocol spec.
 */
public abstract class CoreFallbackRequests {
    public static final byte LIST_PROPERTIES = 21;
    public static final byte CONVERT_SELECTION = 24;
    public static final byte GRAB_KEYBOARD = 31;
    public static final byte GET_MOTION_EVENTS = 39;
    public static final byte QUERY_FONT = 47;
    public static final byte QUERY_TEXT_EXTENTS = 48;
    public static final byte LIST_FONTS_WITH_INFO = 50;
    public static final byte GET_FONT_PATH = 52;
    public static final byte LIST_INSTALLED_COLORMAPS = 83;
    public static final byte ALLOC_COLOR = 84;
    public static final byte ALLOC_NAMED_COLOR = 85;
    public static final byte ALLOC_COLOR_CELLS = 86;
    public static final byte ALLOC_COLOR_PLANES = 87;
    public static final byte QUERY_COLORS = 91;
    public static final byte LOOKUP_COLOR = 92;
    public static final byte QUERY_BEST_SIZE = 97;
    public static final byte LIST_EXTENSIONS = 99;
    public static final byte GET_KEYBOARD_CONTROL = 103;
    public static final byte GET_POINTER_CONTROL = 106;
    public static final byte LIST_HOSTS = 110;
    public static final byte SET_POINTER_MAPPING = 116;
    public static final byte SET_MODIFIER_MAPPING = 118;

    /** Every core request that has a reply (X11 protocol, section 9 / "Encoding"). */
    private static final int[] REPLY_OPCODES = {3, 14, 15, 16, 17, 20, 21, 23, 26, 31, 38, 39, 40, 43, 44, 47, 48, 49, 50, 52, 73,
        83, 84, 85, 86, 87, 91, 92, 97, 98, 99, 101, 103, 106, 108, 110, 116, 117, 118, 119};

    public static boolean expectsReply(int opcode) {
        for (int replyOpcode : REPLY_OPCODES) if (replyOpcode == opcode) return true;
        return false;
    }

    /** A 32-byte reply with only a status/detail byte and no data: GrabKeyboard, SetPointerMapping, SetModifierMapping. */
    public static void statusReply(XClient client, XOutputStream outputStream, byte status) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(status);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }
    }

    public static void listProperties(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        int[] atoms = window.getPropertyAtoms();
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(atoms.length);
            outputStream.writeShort((short)atoms.length);
            outputStream.writePad(22);
            for (int atom : atoms) outputStream.writeInt(atom);
        }
    }

    /** GetMotionEvents (no motion history buffer), GetFontPath, ListInstalledColormaps, ListHosts: an empty list. */
    public static void emptyListReply(XClient client, XOutputStream outputStream, byte detail) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(detail);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0); // nEvents (CARD32) / nPaths, nCmaps, nHosts (CARD16 + pad): zero either way
            outputStream.writePad(20);
        }
    }

    /** ListFontsWithInfo: there are no fonts, so only the last-reply marker (name length 0). */
    public static void listFontsWithInfo(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(7);
            outputStream.writePad(52);
        }
    }

    public static void queryBestSize(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4);
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort(width);
            outputStream.writeShort(height);
            outputStream.writePad(20);
        }
    }

    public static void listExtensions(XClient client, XOutputStream outputStream) throws IOException {
        XServer xServer = client.xServer;
        int count = xServer.extensions.size();
        byte[][] names = new byte[count][];
        int bytes = 0;
        for (int i = 0; i < count; i++) {
            Extension extension = xServer.extensions.valueAt(i);
            names[i] = extension.getName().getBytes(XServer.LATIN1_CHARSET);
            bytes += 1 + names[i].length;
        }
        int pad = -bytes & 3;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)count);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((bytes + pad) / 4);
            outputStream.writePad(24);
            for (byte[] name : names) {
                outputStream.writeByte((byte)name.length);
                outputStream.write(name);
            }
            if (pad > 0) outputStream.writePad(pad);
        }
    }

    public static void getKeyboardControl(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1);            // global-auto-repeat: On
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(5);
            outputStream.writeInt(0);                   // led-mask
            outputStream.writeByte((byte)0);            // key-click-percent
            outputStream.writeByte((byte)50);           // bell-percent
            outputStream.writeShort((short)400);        // bell-pitch
            outputStream.writeShort((short)100);        // bell-duration
            outputStream.writePad(2);
            for (int i = 0; i < 32; i++) outputStream.writeByte((byte)0xFF); // auto-repeats: every key
        }
    }

    public static void getPointerControl(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)2);          // acceleration numerator
            outputStream.writeShort((short)1);          // acceleration denominator
            outputStream.writeShort((short)4);          // threshold
            outputStream.writePad(18);
        }
    }

    public static void allocColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4);
        int red = inputStream.readUnsignedShort();
        int green = inputStream.readUnsignedShort();
        int blue = inputStream.readUnsignedShort();
        inputStream.skip(2);
        Visual visual = client.xServer.pixmapManager.visual;
        int pixel = toPixel(red, visual.redMask) | toPixel(green, visual.greenMask) | toPixel(blue, visual.blueMask);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)fromPixel(pixel, visual.redMask));
            outputStream.writeShort((short)fromPixel(pixel, visual.greenMask));
            outputStream.writeShort((short)fromPixel(pixel, visual.blueMask));
            outputStream.writePad(2);
            outputStream.writeInt(pixel);
            outputStream.writePad(12);
        }
    }

    public static void queryColors(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(4);
        int count = Math.max(0, client.getRemainingRequestLength() / 4);
        int[] pixels = new int[count];
        for (int i = 0; i < count; i++) pixels[i] = inputStream.readInt();
        Visual visual = client.xServer.pixmapManager.visual;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(count * 2);
            outputStream.writeShort((short)count);
            outputStream.writePad(22);
            for (int pixel : pixels) {
                outputStream.writeShort((short)fromPixel(pixel, visual.redMask));
                outputStream.writeShort((short)fromPixel(pixel, visual.greenMask));
                outputStream.writeShort((short)fromPixel(pixel, visual.blueMask));
                outputStream.writePad(2);
            }
        }
    }

    /** A 16-bit colour channel into a TrueColor pixel field. */
    static int toPixel(int value16, int mask) {
        if (mask == 0) return 0;
        int shift = Integer.numberOfTrailingZeros(mask);
        int bits = Integer.bitCount(mask);
        return ((value16 >>> (16 - bits)) << shift) & mask;
    }

    /** A TrueColor pixel field back to a 16-bit colour channel (scaled to the full range). */
    static int fromPixel(int pixel, int mask) {
        if (mask == 0) return 0;
        int shift = Integer.numberOfTrailingZeros(mask);
        int bits = Integer.bitCount(mask);
        long max = (1L << bits) - 1;
        long value = ((pixel & (long)mask & 0xFFFFFFFFL) >>> shift);
        return (int)(value * 65535L / max);
    }
}
