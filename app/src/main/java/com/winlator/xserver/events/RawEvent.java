package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;

import java.io.IOException;

/** An event built by a client and passed on by SendEvent. */
public class RawEvent extends Event {
    private static final int KEYMAP_NOTIFY = 11;
    private static final int SEND_EVENT_FLAG = 0x80;
    private final byte[] data;

    public RawEvent(byte[] data) {
        super(data[0]);
        this.data = data;
    }

    /**
     * X11 SendEvent: the most significant bit of the code is set (the recipient's XAnyEvent.send_event), and the sequence number
     * is the recipient's own, not whatever the sender put there (KeymapNotify carries no sequence number).
     */
    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        byte[] wire = data.clone();
        wire[0] = (byte)(wire[0] | SEND_EVENT_FLAG);
        if ((data[0] & 0x7F) != KEYMAP_NOTIFY) {
            boolean bigEndian = outputStream.buffer.order() == java.nio.ByteOrder.BIG_ENDIAN;
            wire[bigEndian ? 2 : 3] = (byte)(sequenceNumber >> 8);
            wire[bigEndian ? 3 : 2] = (byte)sequenceNumber;
        }
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.write(wire);
        }
    }
}
