package com.winlator.xserver.events;

import com.winlator.xconnector.XOutputStream;
import com.winlator.xserver.extensions.MITSHMExtension;
import java.io.IOException;

/** MIT-SHM completion: the synchronous CPU blit no longer reads this segment. */
public final class ShmCompletionNotify extends Event {
    private final int drawableId, shmseg, offset;
    public ShmCompletionNotify(byte eventCode, int drawableId, int shmseg, int offset) {
        super(Byte.toUnsignedInt(eventCode));
        this.drawableId = drawableId;
        this.shmseg = shmseg;
        this.offset = offset;
    }
    @Override public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        outputStream.writeByte(code);
        outputStream.writeByte((byte)0);
        outputStream.writeShort(sequenceNumber);
        outputStream.writeInt(drawableId);
        outputStream.writeShort((short)3);
        outputStream.writeByte(MITSHMExtension.MAJOR_OPCODE);
        outputStream.writeByte((byte)0);
        outputStream.writeInt(shmseg);
        outputStream.writeInt(offset);
        outputStream.writePad(12);
    }
}
