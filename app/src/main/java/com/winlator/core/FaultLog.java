package com.winlator.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Small always-on record of faults the app survives on purpose (for example a dropped X client), so they can be
 * read from the diagnostics folder without adb. Append-only, capped, never throws.
 */
public final class FaultLog {
    private static final long MAX_BYTES = 256L * 1024;
    private static volatile File file;

    private FaultLog() {}

    public static void init(Context context) {
        file = new File(context.getFilesDir(), "diagnostics/wow/faults.txt");
    }

    public static synchronized void record(String where, Throwable error) {
        File target = file;
        if (target == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            target.getParentFile().mkdirs();
            if (target.length() > MAX_BYTES) return;
            StringWriter text = new StringWriter();
            PrintWriter writer = new PrintWriter(text);
            writer.println(System.currentTimeMillis() + " " + where + " [" + Thread.currentThread().getName() + "]");
            error.printStackTrace(writer);
            writer.flush();
            try (FileOutputStream out = new FileOutputStream(target, true)) {
                out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }
}
