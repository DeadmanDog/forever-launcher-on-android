package com.winlator.xconnector;

import java.io.IOException;
import java.util.function.BooleanSupplier;

final class BufferedRequestDispatcher {
    private BufferedRequestDispatcher() { }

    static void drain(Client client, RequestHandler handler, BooleanSupplier running) throws IOException {
        XInputStream input = client.getInputStream();
        int committed = input.getActivePosition();
        while (running.getAsBoolean() && !client.requestGate.isBlocked() && handler.handleRequest(client)) {
            committed = input.getActivePosition();
        }
        // Rewind only an incomplete request, never requests committed before a fence wait.
        input.setActivePosition(committed);
    }
}
