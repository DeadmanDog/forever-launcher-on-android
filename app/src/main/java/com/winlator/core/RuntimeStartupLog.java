package com.winlator.core;

import java.io.File;
import java.io.IOException;

/** First bytes from both child pipes, independent of rotating tail logs. */
final class RuntimeStartupLog {
    static final long MAX_BYTES = 512L * 1024;
    private final RuntimeLog log;
    private int readers = 2;
    RuntimeStartupLog(File file) throws IOException { log = new RuntimeLog(file, MAX_BYTES); }
    synchronized void append(byte[] bytes, int count) { log.append(bytes, count); }
    synchronized void readerFinished() { if (--readers == 0) log.close(); }
}
