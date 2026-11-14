package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.core.Callback;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.NativeTexture;
import com.winlator.renderer.VulkanRenderer;
import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class DRI3Extension implements Extension {
    public static final byte MAJOR_OPCODE = -102;
    private byte firstEventId = 0;
    private byte firstErrorId = 0;
    private final Callback<Drawable> onDestroyDrawableListener = (drawable) -> {
        ByteBuffer data = drawable.getData();
        SysVSharedMemory.unmapSHMSegment(data, data.capacity());
    };

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte OPEN = 1;
        private static final byte PIXMAP_FROM_BUFFER = 2;
        private static final byte PIXMAP_FROM_BUFFERS = 7;
    }

    @Override
    public String getName() {
        return "DRI3";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public int getNumEvents() { return 0; }

    @Override
    public int getNumErrors() { return 0; }

    @Override
    public void setFirstEventId(byte id) { this.firstEventId = id; }

    @Override
    public void setFirstErrorId(byte id) { this.firstErrorId = id; }

    @Override
    public byte getFirstEventId() { return firstEventId; }

    @Override
    public byte getFirstErrorId() { return firstErrorId; }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void open(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }
    }

    private void pixmapFromBuffer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int size = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short stride = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(1);

        int fd = inputStream.getAncillaryFd();
        try {
            validateTarget(client, pixmapId, windowId);
            int transferred = fd;
            fd = -1; // the import helper now owns the descriptor, including failure
            pixmapFromFd(client, pixmapId, width, height, Short.toUnsignedInt(stride), 0, depth, transferred, size);
        } finally {
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }

    private void pixmapFromBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int numBuffers = inputStream.readUnsignedByte();
        inputStream.skip(3);
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        int stride = inputStream.readInt();
        int offset = inputStream.readInt();
        inputStream.skip(24);
        byte depth = inputStream.readByte();
        inputStream.skip(3);
        long modifiers = inputStream.readLong();

        int fd = inputStream.getAncillaryFd();
        // Planes 1..3 travel as descriptors of their own: only plane 0 is imported, but every one of them must leave the
        // per-socket queue, or the next descriptor-carrying request takes one of them as its own.
        closeFds(inputStream, numBuffers - 1);
        try {
            validateTarget(client, pixmapId, windowId);
            if (modifiers != 1255 && modifiers != 1274) throw new BadImplementation();
            int transferred = fd;
            fd = -1;
            if (modifiers == 1255) pixmapFromHardwareBuffer(client, pixmapId, width, height, depth, transferred);
            else pixmapFromFd(client, pixmapId, width, height, stride, offset, depth, transferred, (long)stride * height);
        } finally {
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }

    /** Pops [count] descriptors this request carried but nothing imports, and closes them. */
    private static void closeFds(XInputStream inputStream, int count) {
        for (int i = 0; i < count; i++) XConnectorEpoll.closeFd(inputStream.getAncillaryFd());
    }

    /** How many descriptors a DRI3 request without an import here carries (DRI3 1.0-1.4: FenceFromFD, ImportSyncobj). */
    static int fdsCarriedBy(int opcode) {
        switch (opcode) {
            case FENCE_FROM_FD:
            case IMPORT_SYNCOBJ:
                return 1;
            default:
                return 0;
        }
    }

    private static final int FENCE_FROM_FD = 4;
    private static final int IMPORT_SYNCOBJ = 10;

    private void validateTarget(XClient client, int pixmapId, int windowId) throws XRequestError {
        if (client.xServer.windowManager.getWindow(windowId) == null) throw new BadWindow(windowId);
        if (client.xServer.drawableManager.getDrawable(pixmapId) != null ||
                client.xServer.pixmapManager.getPixmap(pixmapId) != null) throw new BadIdChoice(pixmapId);
    }

    private void validateStorage(XClient client, int pixmapId, short width, short height, byte depth, int fd)
            throws XRequestError {
        if (fd < 0) throw new BadAlloc();
        if (width <= 0 || height <= 0) throw new BadValue(width <= 0 ? width : height);
        if (client.xServer.pixmapManager.getVisualForDepth(depth) == null) throw new BadMatch();
        if (client.xServer.drawableManager.getDrawable(pixmapId) != null ||
                client.xServer.pixmapManager.getPixmap(pixmapId) != null) throw new BadIdChoice(pixmapId);
    }

    private void pixmapFromHardwareBuffer(XClient client, int pixmapId, short width, short height, byte depth, int fd)
            throws IOException, XRequestError {
        NativeTexture image = null;
        Drawable drawable = null;
        boolean installed = false;
        try {
            validateStorage(client, pixmapId, width, height, depth, fd);
            GPUImage gpuImage = new GPUImage(fd, !(client.xServer.getRenderer() instanceof VulkanRenderer));
            image = gpuImage;
            long ptr = gpuImage.getHardwareBufferPtr();
            if (ptr == 0) throw new BadAlloc();
            // DRI3 dimensions are logical pixels. Imported AHB CPU stride may be unset.
            drawable = client.xServer.drawableManager.createImportedDrawable(pixmapId, width, height, depth);
            if (drawable == null) throw new BadIdChoice(pixmapId);
            drawable.setTexture(image);
            Pixmap pixmap = client.xServer.pixmapManager.createPixmap(drawable);
            if (pixmap == null) throw new BadIdChoice(pixmapId);
            // Like CreatePixmap: the client owns it, so a disconnect without FreePixmap still releases it.
            client.registerAsOwnerOfResource(pixmap);
            installed = true;
        } finally {
            if (!installed) {
                if (drawable != null) client.xServer.drawableManager.removeDrawable(pixmapId);
                else if (image != null) image.destroy();
            }
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }

    private void pixmapFromFd(XClient client, int pixmapId, short width, short height, int stride, int offset,
            byte depth, int fd, long size) throws IOException, XRequestError {
        ByteBuffer buffer = null;
        Drawable drawable = null;
        boolean installed = false;
        try {
            validateStorage(client, pixmapId, width, height, depth, fd);
            if (stride < (long)width * 4 || stride % 4 != 0 || stride / 4 > Short.MAX_VALUE ||
                    offset < 0 || size < (long)stride * height || size > Integer.MAX_VALUE) throw new BadValue(stride);
            // Writable when the descriptor allows it (the client may draw into the pixmap with core requests and expects to see
            // it); a descriptor opened read-only still maps, but the pixmap then refuses CPU drawing.
            buffer = SysVSharedMemory.mapSHMSegment(fd, size, offset, false);
            boolean readOnlyMapping = false;
            if (buffer == null) {
                buffer = SysVSharedMemory.mapSHMSegment(fd, size, offset, true);
                readOnlyMapping = true;
            }
            if (buffer == null) throw new BadAlloc();
            // Preserve existing storage stride semantics on the CPU-copy fallback.
            short totalWidth = (short)(stride / 4);
            drawable = client.xServer.drawableManager.createImportedDrawable(pixmapId, totalWidth, height, depth);
            if (drawable == null) throw new BadIdChoice(pixmapId);
            drawable.setData(buffer);
            drawable.setReadOnly(readOnlyMapping);
            drawable.setOnDestroyListener(onDestroyDrawableListener);
            Pixmap pixmap = client.xServer.pixmapManager.createPixmap(drawable);
            if (pixmap == null) throw new BadIdChoice(pixmapId);
            // Like CreatePixmap: the client owns it, so a disconnect without FreePixmap still releases it.
            client.registerAsOwnerOfResource(pixmap);
            installed = true;
        } finally {
            if (!installed) {
                if (drawable != null) client.xServer.drawableManager.removeDrawable(pixmapId);
                else if (buffer != null) SysVSharedMemory.unmapSHMSegment(buffer, buffer.capacity());
            }
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.OPEN :
                try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    open(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFER:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffer(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFERS:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffers(client, inputStream, outputStream);
                }
                break;
            default:
                // Unsupported, but its descriptors must still leave the queue (and not leak).
                closeFds(inputStream, fdsCarriedBy(opcode));
                throw new BadImplementation();
        }
    }
}
