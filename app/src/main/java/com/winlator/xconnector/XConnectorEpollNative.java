package com.winlator.xconnector;

import androidx.annotation.Keep;
import java.io.IOException;
import java.nio.ByteBuffer;

@Keep
final class XConnectorEpollNative {
    static {
        System.loadLibrary("xconnectorpatch");
    }

    private XConnectorEpollNative() {
    }

    @Keep
    static native boolean addFdToEpoll(int epollFd, int fd);

    @Keep
    static native int createAFUnixSocket(String path);

    @Keep
    static native int createEpollFd();

    @Keep
    static native int createEventFd();

    @Keep
    static native boolean doEpollIndefinitely(XConnectorEpoll connector, int epollFd, int serverFd, boolean addClientToEpoll);

    @Keep
    static native void removeFdFromEpoll(int epollFd, int fd);

    @Keep
    static native void signalEventFd(int fd);

    @Keep
    static native void drainEventFd(int fd);

    @Keep
    static native int sendSocket(int fd, ByteBuffer data, int offset, int length, int ancillaryFd) throws IOException;

    /** Never blocks: bytes accepted, or -2 when the socket buffer is full and nothing was consumed. */
    @Keep
    static native int sendSocketNoWait(int fd, ByteBuffer data, int offset, int length, int ancillaryFd) throws IOException;

    @Keep
    static native void setFdEvents(int epollFd, int fd, boolean readable, boolean writable);

    @Keep
    static native int receiveSocket(int fd, ByteBuffer data, int offset, int length, ClientSocket ancillaryReceiver) throws IOException;

    @Keep
    static native void closeFd(int fd);
}
