package com.winlator.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Keeps a log written by a process we do not own (the PulseAudio daemon logs straight into its file) from growing without
 * bound: when the file passes the limit its content is copied to "<name>.1" and the file is emptied.
 */
public final class LogSizeGuard implements AutoCloseable {
    private final Thread thread;

    private LogSizeGuard(File log, long maxBytes, long periodMs) {
        thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    trimOnce(log, maxBytes);
                    Thread.sleep(periodMs);
                }
            } catch (InterruptedException ignored) { }
        }, "log-size-guard");
        thread.setDaemon(true);
        thread.start();
    }

    public static LogSizeGuard watch(File log, long maxBytes, long periodMs) {
        return new LogSizeGuard(log, maxBytes, periodMs);
    }

    /** Copy-truncate once. Returns true when the file was over the limit. */
    public static boolean trimOnce(File log, long maxBytes) {
        if (!log.isFile() || log.length() <= maxBytes) return false;
        File previous = new File(log.getPath() + ".1");
        try {
            Files.copy(log.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try (RandomAccessFile file = new RandomAccessFile(log, "rw")) { file.setLength(0); }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override public void close() { thread.interrupt(); }
}
