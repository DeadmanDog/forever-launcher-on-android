package com.winlator.xconnector;

import java.io.IOException;

public interface RequestHandler {
    boolean handleRequest(Client client) throws IOException;

    /**
     * 2026-11-12 review: true when the replies to one round of buffered requests may go out in one socket write at the end of the
     * round (Xorg flushes once per dispatch round too) instead of one write per reply. Off for handlers whose replies carry a file
     * descriptor (it travels with the first byte of a write).
     */
    default boolean batchesReplies() { return false; }
}
