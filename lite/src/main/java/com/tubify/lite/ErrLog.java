package com.tubify.lite;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tiny persistent error log for development builds. One line per entry in a
 * capped file under filesDir; survives crashes so last-run errors are visible
 * on next launch. Personal data (cookies, tokens, emails, URL queries) is
 * redacted before writing.
 */
final class ErrLog {
    private static final int MAX_BYTES = 40 * 1024;
    private static File sFile;

    private ErrLog() {
    }

    static synchronized void init(Context ctx) {
        if (sFile == null) {
            sFile = new File(ctx.getFilesDir(), "errorlog.txt");
        }
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                log("crash", sw.toString());
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            } else {
                System.exit(2);
            }
        });
    }

    static synchronized void log(String tag, String msg) {
        if (sFile == null || msg == null) {
            return;
        }
        try {
            String flat = redact(msg).replace('\n', ' ').replaceAll(" +", " ").trim();
            if (flat.length() > 600) {
                flat = flat.substring(0, 600);
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " [" + tag + "] " + flat;
            FileWriter w = new FileWriter(sFile, true);
            w.write(line + "\n");
            w.close();
            trim();
        } catch (Throwable ignored) {
        }
    }

    static synchronized String read() {
        try {
            if (sFile != null && sFile.exists()) {
                String s = new String(Files.readAllBytes(sFile.toPath()), StandardCharsets.UTF_8).trim();
                return s.isEmpty() ? "No errors recorded." : s;
            }
        } catch (Throwable ignored) {
        }
        return "No errors recorded.";
    }

    static synchronized void clear() {
        try {
            if (sFile != null) {
                sFile.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void trim() {
        try {
            if (sFile.length() <= MAX_BYTES) {
                return;
            }
            byte[] all = Files.readAllBytes(sFile.toPath());
            int keep = MAX_BYTES * 3 / 4;
            byte[] tail = new byte[keep];
            System.arraycopy(all, all.length - keep, tail, 0, keep);
            Files.write(sFile.toPath(), tail);
        } catch (Throwable ignored) {
        }
    }

    private static String redact(String s) {
        String r = s;
        r = r.replaceAll("(https?://[^/?\s]+)[?#][^\s]*", "$1?...");
        r = r.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "[email]");
        r = r.replaceAll("(?i)(cookie|authorization|session|password)=\S+", "$1=[redacted]");
        r = r.replaceAll("[A-Za-z0-9_-]{32,}", "[token]");
        return r;
    }
}
