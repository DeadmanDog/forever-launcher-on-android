package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * The part of RENDER that libXcursor uses for full-colour cursors: version 0.7, the picture formats, CreatePicture/FreePicture
 * on a pixmap and CreateCursor from a 32-bit picture. Without it libXcursor (and so Wine) reduces every colour cursor to two
 * colours and a 1-bit mask. Version 0.7 keeps animated cursors on their first frame (CreateAnimCursor needs 0.8).
 * Drawing requests are accepted and ignored: Wine's own RENDER drawing is switched off at launch (ClientSideWithRender=N).
 */
public class RenderExtension implements Extension {
    public static final byte MAJOR_OPCODE = -107;
    public static final int MINOR_VERSION = 7;
    /** PictFormat ids: ARGB32 (the cursor format) and the format of the depth-32 root visual (no alpha). */
    public static final int FORMAT_ARGB32 = 0x50001, FORMAT_RGB32 = 0x50002;
    private static final int QUERY_VERSION = 0, QUERY_PICT_FORMATS = 1, QUERY_PICT_INDEX_VALUES = 2, CREATE_PICTURE = 4,
        FREE_PICTURE = 7, CREATE_CURSOR = 27, QUERY_FILTERS = 29;
    private static final int ERROR_PICT_FORMAT = 0, ERROR_PICTURE = 1;
    private static final byte PICT_TYPE_DIRECT = 1;

    private static final class Picture {
        final XClient owner;
        final Drawable drawable;
        Picture(XClient owner, Drawable drawable) { this.owner = owner; this.drawable = drawable; }
    }

    private final SparseArray<Picture> pictures = new SparseArray<>();
    private byte firstErrorId = 0;

    @Override public String getName() { return "RENDER"; }
    @Override public byte getMajorOpcode() { return MAJOR_OPCODE; }
    @Override public byte getFirstErrorId() { return firstErrorId; }
    @Override public byte getFirstEventId() { return 0; }
    @Override public int getNumErrors() { return 5; }
    @Override public void setFirstErrorId(byte id) { firstErrorId = id; }

    private XRequestError renderError(int code, int value) { return new XRequestError(firstErrorId + code, value); }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        switch (client.getRequestData()) {
            case QUERY_VERSION: queryVersion(client, inputStream, outputStream); break;
            case QUERY_PICT_FORMATS: queryPictFormats(client, outputStream); break;
            case QUERY_PICT_INDEX_VALUES: throw renderError(ERROR_PICT_FORMAT, inputStream.readInt());
            case CREATE_PICTURE: createPicture(client, inputStream); break;
            case FREE_PICTURE: freePicture(client, inputStream); break;
            case CREATE_CURSOR: createCursor(client, inputStream); break;
            case QUERY_FILTERS: queryFilters(client, outputStream); break;
            default: break;
        }
        client.skipRequest();
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(8);
        try (XStreamLock lock = outputStream.lock()) { writeVersionReply(outputStream, client.getSequenceNumber()); }
    }

    static void writeVersionReply(XOutputStream out, short sequence) {
        out.writeByte(RESPONSE_CODE_SUCCESS);
        out.writeByte((byte)0);
        out.writeShort(sequence);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(MINOR_VERSION);
        out.writePad(16);
    }

    private static void writeFormat(XOutputStream out, int id, int depth, int alphaShift, int alphaMask) {
        out.writeInt(id);
        out.writeByte(PICT_TYPE_DIRECT);
        out.writeByte((byte)depth);
        out.writePad(2);
        out.writeShort((short)16); out.writeShort((short)0xff);
        out.writeShort((short)8); out.writeShort((short)0xff);
        out.writeShort((short)0); out.writeShort((short)0xff);
        out.writeShort((short)alphaShift); out.writeShort((short)alphaMask);
        out.writeInt(0);
    }

    private void queryPictFormats(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writePictFormatsReply(outputStream, client.getSequenceNumber(), client.xServer.pixmapManager.visual.id);
        }
    }

    /** Two formats, one screen with one depth (32) holding the root visual, and its subpixel order (unknown). */
    static void writePictFormatsReply(XOutputStream out, short sequence, int rootVisualId) {
        final int numFormats = 2, numScreens = 1, numDepths = 1, numVisuals = 1, numSubpixel = 1;
        final int length = numFormats * 28 + numScreens * 8 + numDepths * 8 + numVisuals * 8 + numSubpixel * 4;
        out.writeByte(RESPONSE_CODE_SUCCESS);
        out.writeByte((byte)0);
        out.writeShort(sequence);
        out.writeInt(length / 4);
        out.writeInt(numFormats);
        out.writeInt(numScreens);
        out.writeInt(numDepths);
        out.writeInt(numVisuals);
        out.writeInt(numSubpixel);
        out.writePad(4);
        writeFormat(out, FORMAT_ARGB32, 32, 24, 0xff);
        writeFormat(out, FORMAT_RGB32, 32, 0, 0);
        out.writeInt(numDepths);
        out.writeInt(FORMAT_RGB32);
        out.writeByte((byte)32);
        out.writePad(1);
        out.writeShort((short)numVisuals);
        out.writePad(4);
        out.writeInt(rootVisualId);
        out.writeInt(FORMAT_RGB32);
        out.writeInt(0);
    }

    private void queryFilters(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) { writeFiltersReply(outputStream, client.getSequenceNumber()); }
    }

    static void writeFiltersReply(XOutputStream out, short sequence) {
        out.writeByte(RESPONSE_CODE_SUCCESS);
        out.writeByte((byte)0);
        out.writeShort(sequence);
        out.writeInt(0);
        out.writeInt(0);
        out.writeInt(0);
        out.writePad(16);
    }

    private void createPicture(XClient client, XInputStream inputStream) throws XRequestError {
        int pictureId = inputStream.readInt();
        int drawableId = inputStream.readInt();
        int format = inputStream.readInt();
        if (!client.isValidResourceId(pictureId)) throw new BadIdChoice(pictureId);
        if (format != FORMAT_ARGB32 && format != FORMAT_RGB32) throw renderError(ERROR_PICT_FORMAT, format);
        Drawable drawable;
        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
            drawable = client.xServer.drawableManager.getDrawable(drawableId);
        }
        if (drawable == null) throw new BadDrawable(drawableId);
        if (drawable.visual.depth != 32) throw new BadMatch();
        synchronized (pictures) {
            if (pictures.indexOfKey(pictureId) >= 0) throw new BadIdChoice(pictureId);
            pictures.put(pictureId, new Picture(client, drawable));
        }
    }

    private void freePicture(XClient client, XInputStream inputStream) throws XRequestError {
        int pictureId = inputStream.readInt();
        synchronized (pictures) {
            if (pictures.indexOfKey(pictureId) < 0) throw renderError(ERROR_PICTURE, pictureId);
            pictures.remove(pictureId);
        }
    }

    private void createCursor(XClient client, XInputStream inputStream) throws XRequestError {
        int cursorId = inputStream.readInt();
        int pictureId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        if (!client.isValidResourceId(cursorId)) throw new BadIdChoice(cursorId);
        Picture picture;
        synchronized (pictures) { picture = pictures.get(pictureId); }
        if (picture == null) throw renderError(ERROR_PICTURE, pictureId);
        Drawable source = picture.drawable;
        if (source.getData() == null || x < 0 || y < 0 || x >= source.width || y >= source.height) throw new BadMatch();
        try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
            Cursor cursor = client.xServer.cursorManager.createArgbCursor(cursorId, x, y, source);
            if (cursor == null) throw new BadIdChoice(cursorId);
            client.registerAsOwnerOfResource(cursor);
        }
    }

    /** Pictures of a closed connection are freed with it. */
    public void onClientDisconnected(XClient client) {
        synchronized (pictures) {
            for (int i = pictures.size() - 1; i >= 0; i--) if (pictures.valueAt(i).owner == client) pictures.removeAt(i);
        }
    }

    int pictureCount() { synchronized (pictures) { return pictures.size(); } }
}
