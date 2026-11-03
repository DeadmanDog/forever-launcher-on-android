package com.winlator.xconnector;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Preserve stream bytes across short writes; transfer an ancillary FD only once. */
final class SocketWritePump {
    interface Transport {
        int send(ByteBuffer data, int offset, int length, int ancillaryFd) throws IOException;
    }

    static void writeFully(ByteBuffer data, int ancillaryFd, Transport transport) throws IOException {
        while (data.hasRemaining()) {
            int remaining = data.remaining();
            int written = transport.send(data, data.position(), remaining, ancillaryFd);
            if (written <= 0 || written > remaining) {
                throw new IOException("Socket write made invalid progress: " + written);
            }
            data.position(data.position() + written);
            ancillaryFd = -1;
        }
    }

    private SocketWritePump() {}
}
