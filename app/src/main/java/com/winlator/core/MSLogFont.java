package com.winlator.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Windows LOGFONT with the fixed Tahoma defaults; only the weight is configurable (normal 400, bold 700). */
public class MSLogFont {
    private int weight = 400;

    public MSLogFont setWeight(int weight) {
        this.weight = weight;
        return this;
    }

    public byte[] toByteArray() {
        ByteBuffer data = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(-11);   // height
        data.putInt(0);     // width
        data.putInt(0);     // escapement
        data.putInt(0);     // orientation
        data.putInt(weight);
        data.put((byte) 0); // italic
        data.put((byte) 0); // underline
        data.put((byte) 0); // strike out
        data.put((byte) 0); // char set
        data.put((byte) 0); // out precision
        data.put((byte) 0); // clip precision
        data.put((byte) 0); // quality
        data.put((byte) 34); // pitch and family

        String faceName = "Tahoma";
        for (int i = 0; i < faceName.length(); i++) data.putChar(faceName.charAt(i));
        return data.array();
    }
}
