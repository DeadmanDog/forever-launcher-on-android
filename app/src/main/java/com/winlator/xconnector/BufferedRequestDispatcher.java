package com.winlator.xconnector;

import java.io.IOException;
import java.util.function.BooleanSupplier;

final class BufferedRequestDispatcher {
    private BufferedRequestDispatcher() { }

    static void drain(Client client, RequestHandler handler, BooleanSupplier running) throws IOException {
        XInputStream input = client.getInputStream();
        // Replies made in this round stay in the output buffer and go out together when it ends, on every exit path: a client
        // that waits for a reply (or stops sending until it gets one) always has it once the round is over.
        XOutputStream output = handler.batchesReplies() ? client.getOutputStream() : null;
        if (output != null) output.beginBatch();
        int committed = input.getActivePosition();
        try {
            while (running.getAsBoolean() && !client.requestGate.isBlocked() && handler.handleRequest(client)) {
                committed = input.getActivePosition();
            }
        } finally {
            // Rewind only an incomplete request, never requests committed before a fence wait.
            input.setActivePosition(committed);
            if (output != null) output.endBatch();
        }
    }
}
