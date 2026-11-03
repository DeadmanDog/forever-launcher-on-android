package com.winlator.xconnector;

import androidx.annotation.Keep;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

public class ClientSocket {
    public final int fd;
    private final SocketWritePump.Transport writer = this::send;
    private final ArrayDeque<Integer> ancillaryFds = new ArrayDeque<>();

    public ClientSocket(int fd) {
        this.fd = fd;
    }

    public boolean hasAncillaryFds() {
        return !ancillaryFds.isEmpty();
    }

    public int getAncillaryFd() {
        return hasAncillaryFds() ? ancillaryFds.poll() : -1;
    }

    public void closeAncillaryFds() {
        while (!ancillaryFds.isEmpty()) XConnectorEpollNative.closeFd(ancillaryFds.removeFirst());
    }

    @Keep
    public void addAncillaryFd(int ancillaryFd) {
        ancillaryFds.add(ancillaryFd);
    }

    public int read(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = XConnectorEpollNative.receiveSocket(fd, data, position, data.remaining(), null);
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to read data.");
    }

    public void write(ByteBuffer data) throws IOException {
        SocketWritePump.writeFully(data, -1, writer);
    }

    public int recvAncillaryMsg(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = XConnectorEpollNative.receiveSocket(fd, data, position, data.remaining(), this);
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        else if (bytesRead == 0) {
            return -1;
        }
        else throw new IOException("Failed to receive ancillary messages.");
    }

    public void sendAncillaryMsg(ByteBuffer data, int ancillaryFd) throws IOException {
        SocketWritePump.writeFully(data, ancillaryFd, writer);
    }

    /** Never blocks: bytes accepted by the socket, or -2 when it is full and nothing was consumed. */
    int sendNoWait(ByteBuffer data, int offset, int length, int ancillaryFd) throws IOException {
        return XConnectorEpollNative.sendSocketNoWait(fd, data, offset, length, ancillaryFd);
    }

    private int send(ByteBuffer data, int offset, int length, int ancillaryFd) throws IOException {
        return XConnectorEpollNative.sendSocket(fd, data, offset, length, ancillaryFd);
    }
}
