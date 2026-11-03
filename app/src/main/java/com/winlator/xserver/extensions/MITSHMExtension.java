package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.GraphicsContext;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadGraphicsContext;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadAccess;
import com.winlator.xserver.events.ShmCompletionNotify;
import com.winlator.xserver.errors.BadSHMSegment;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MITSHMExtension implements Extension {
    public static final byte MAJOR_OPCODE = -101;
    private byte firstEventId = 0;
    private byte firstErrorId = 0;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte ATTACH = 1;
        private static final byte DETACH = 2;
        private static final byte PUT_IMAGE = 3;
    }

    @Override
    public String getName() {
        return "MIT-SHM";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public int getNumEvents() { return 1; }

    @Override
    public int getNumErrors() { return 1; }

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeByte((byte)0);
            outputStream.writePad(15);
        }
    }

    private static void attach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int xid = inputStream.readInt();
        int shmid = inputStream.readInt();
        inputStream.skip(4);
        client.xServer.getSHMSegmentManager().attach(xid, shmid, client);
    }

    private static void detach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        client.xServer.getSHMSegmentManager().detach(inputStream.readInt());
    }

    private void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short totalWidth = inputStream.readShort();
        short totalHeight = inputStream.readShort();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short srcWidth = inputStream.readShort();
        short srcHeight = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte depth = inputStream.readByte();
        int format = inputStream.readUnsignedByte();
        boolean sendEvent = inputStream.readUnsignedByte() != 0;
        inputStream.skip(1);
        int shmseg = inputStream.readInt();
        long offset = inputStream.readUnsignedInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        ByteBuffer data = client.xServer.getSHMSegmentManager().getData(shmseg);
        if (data == null) throw new BadSHMSegment(shmseg);

        if (graphicsContext.getFunction() != GraphicsContext.Function.COPY) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        // The renderer accepts packed ZPixmap and one-bit bitmap input only.
        if (format != 0 && format != 2) throw new BadValue(format);
        if ((format == 0 && depth != 1) || (format == 2 && depth != 24 && depth != 32)) throw new BadMatch();
        int imageW = Short.toUnsignedInt(totalWidth);
        int imageH = Short.toUnsignedInt(totalHeight);
        int x = Short.toUnsignedInt(srcX), y = Short.toUnsignedInt(srcY);
        int w = Short.toUnsignedInt(srcWidth), h = Short.toUnsignedInt(srcHeight);
        if (imageW > Short.MAX_VALUE || imageH > Short.MAX_VALUE || x + w > imageW || y + h > imageH) throw new BadValue(imageW);
        long rowBytes = depth == 1 ? ((imageW + 31L) / 32L) * 4L : imageW * 4L;
        long imageBytes = rowBytes * imageH;
        if ((offset & 3L) != 0 || offset > data.capacity()) throw new BadValue((int)offset);
        if (imageBytes > data.capacity() - offset) throw new BadAccess();
        // Slice without mutating the manager's shared ByteBuffer position/limit.
        ByteBuffer image = data.duplicate().order(data.order());
        image.clear().position((int)offset).limit((int)(offset + imageBytes));
        image = image.slice().order(data.order());
        drawable.drawImage(srcX, srcY, dstX, dstY, srcWidth, srcHeight, depth, image, totalWidth, totalHeight);
        if (sendEvent) client.sendEvent(new ShmCompletionNotify(firstEventId, drawableId, shmseg, (int)offset));

        // Only a blit whose clipped destination covers >=80% of the drawable
        // counts as a presented frame.
        // The visible part: a destination left of or above the drawable cuts that much off the image.
        long visible = com.winlator.xserver.RectClip.visibleArea(dstX, dstY, w, h, drawable.width, drawable.height);
        if (visible > 0
                && visible * 5 >= (long) drawable.width * drawable.height * 4
                && client.connectorClient != null) {
            long delayNs = com.winlator.xserver.ShmFramePacer.framePresented(drawableId);
            if (delayNs > 0) {
                client.connectorClient.getConnector().pauseClientReads(client.connectorClient, delayNs);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.ATTACH :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    attach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.DETACH :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    detach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PUT_IMAGE :
                try (XLock lock = client.xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                    putImage(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
