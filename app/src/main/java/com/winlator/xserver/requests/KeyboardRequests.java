package com.winlator.xserver.requests;

import static com.winlator.xserver.Keyboard.KEYSYMS_PER_KEYCODE;
import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Keyboard;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.BadValue;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class KeyboardRequests {
    public static void getKeyboardMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        // Keycodes are CARD8: read unsigned (a first keycode of 128 or more used to be a negative byte).
        int firstKeycode = inputStream.readUnsignedByte();
        int count = inputStream.readUnsignedByte();
        inputStream.skip(2);

        // X11: first-keycode >= min-keycode and first-keycode + count - 1 <= max-keycode, otherwise BadValue.
        if (firstKeycode < Keyboard.MIN_KEYCODE) throw new BadValue(firstKeycode);
        if (firstKeycode + count - 1 > Keyboard.MAX_KEYCODE) throw new BadValue(count);

        int[] keysyms = client.xServer.keyboard.keysyms;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(KEYSYMS_PER_KEYCODE);
            outputStream.writeShort(client.getSequenceNumber());
            // The reply carries KEYSYMS_PER_KEYCODE keysyms for every keycode: count * n words.
            outputStream.writeInt(count * KEYSYMS_PER_KEYCODE);
            outputStream.writePad(24);

            int start = (firstKeycode - Keyboard.MIN_KEYCODE) * KEYSYMS_PER_KEYCODE;
            for (int i = 0, total = count * KEYSYMS_PER_KEYCODE; i < total; i++) {
                int index = start + i;
                outputStream.writeInt(index < keysyms.length ? keysyms[index] : 0);
            }
        }
    }

    public static void getModifierMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(2);
            outputStream.writePad(24);
            outputStream.writePad(8);
        }
    }
}