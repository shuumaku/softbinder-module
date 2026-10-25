package dev.softbinder.module;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * File logger; the main logcat buffer is far too small to keep system_server
 * output.
 */
final class Journal {
    static final String FILE = "/data/system/softbinder.log";
    private static final long MAX_BYTES = 128 * 1024;

    static volatile boolean debug;

    private Journal() {
    }

    static void e(String msg) {
        write("E " + msg);
    }

    static void d(String msg) {
        if (debug)
            write("D " + msg);
    }

    private static synchronized void write(String line) {
        try {
            File f = new File(FILE);
            if (f.length() > MAX_BYTES)
                f.delete();
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + line + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
