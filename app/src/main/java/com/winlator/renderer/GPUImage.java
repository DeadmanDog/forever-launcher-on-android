package com.winlator.renderer;

import androidx.annotation.Keep;
import java.nio.ByteBuffer;

public class GPUImage extends NativeTexture {
    private long hardwareBufferPtr;
    private ByteBuffer virtualData;
    private short stride;

    static {
        System.loadLibrary("extras");
    }

    public GPUImage(int socketFd, boolean cpuAccess) {
        hardwareBufferPtr = hardwareBufferFromSocket(socketFd);
        // DRI3 pixmaps are written by the guest GPU and sampled by the Vulkan
        // compositor. A CPU write lock here can stall the import and needlessly
        // hold the buffer locked for the pixmap's entire lifetime.
        if (cpuAccess && hardwareBufferPtr != 0) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
            if (virtualData == null) {
                destroyHardwareBuffer(hardwareBufferPtr, false);
                hardwareBufferPtr = 0;
            }
        }
    }

    public short getStride() {
        return stride;
    }

    @Keep
    private void setStride(short stride) {
        this.stride = stride;
    }

    public ByteBuffer getVirtualData() {
        return virtualData;
    }

    @Override
    public void destroy() {
        if (hardwareBufferPtr != 0) {
            destroyHardwareBuffer(hardwareBufferPtr, virtualData != null);
            hardwareBufferPtr = 0;
        }
        virtualData = null;
    }

    public long getHardwareBufferPtr() {
        return this.hardwareBufferPtr;
    }

    public void lock() {
        if (hardwareBufferPtr != 0 && virtualData == null) {
            virtualData = lockHardwareBuffer(hardwareBufferPtr);
        }
    }

    public int unlock() {
        if (hardwareBufferPtr != 0 && virtualData != null) {
            int fenceFd = unlockHardwareBuffer(hardwareBufferPtr);
            virtualData = null;
            return fenceFd;
        }
        return -1;
    }

    private native long hardwareBufferFromSocket(int fd);

    private native void destroyHardwareBuffer(long hardwareBufferPtr, boolean cpuLocked);

    private native ByteBuffer lockHardwareBuffer(long hardwareBufferPtr);

    private native int unlockHardwareBuffer(long hardwareBufferPtr);
}
