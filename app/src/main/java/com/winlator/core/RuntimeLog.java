package com.winlator.core;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** A bounded stream sink. Once full/failed it discards; child pipes must still be drained. */
public final class RuntimeLog implements Closeable {
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    private BufferedOutputStream output;
    private final File file;
    private final long limit;
    private final boolean rotating;
    private long remaining;
    private boolean failed;
    public RuntimeLog(File file, long maxBytes) throws IOException {
        this(file, maxBytes, false);
    }
    public RuntimeLog(File file, long maxBytes, boolean rotating) throws IOException {
        this(file, maxBytes, rotating, null);
    }
    /** [header]: written at the start of the file and of every file a rotation starts (a CSV keeps its column names). */
    public RuntimeLog(File file, long maxBytes, boolean rotating, byte[] header) throws IOException {
        this.file = file;
        this.limit = maxBytes;
        this.rotating = rotating;
        this.header = header != null && header.length < maxBytes ? header : null;
        if (maxBytes < 0) throw new IllegalArgumentException("Negative log limit");
        output = new BufferedOutputStream(new FileOutputStream(file), 8192);
        remaining = maxBytes;
        writeHeader();
    }
    private final byte[] header;
    private void writeHeader() throws IOException {
        if (header == null) return;
        output.write(header);
        output.flush();
        remaining -= header.length;
    }
    public synchronized void append(byte[] bytes, int count) {
        if (failed || limit == 0) return;
        int offset = 0;
        try {
            while (offset < count) {
                if (remaining == 0) {
                    if (!rotating) return;
                    output.close();
                    File previous = new File(file.getPath() + ".previous");
                    if (previous.exists() && !previous.delete()) throw new IOException("Log rotation failed");
                    if (!file.renameTo(previous)) throw new IOException("Log rotation failed");
                    output = new BufferedOutputStream(new FileOutputStream(file), 8192);
                    remaining = limit;
                    writeHeader();
                }
                int length = (int)Math.min(count - offset, remaining);
                output.write(bytes, offset, length);
                remaining -= length;
                offset += length;
                output.flush();
            }
        } catch (IOException ignored) { failed = true; }
    }
    @Override public synchronized void close() {
        failed = true;
        try { output.close(); } catch (IOException ignored) { }
    }
}
