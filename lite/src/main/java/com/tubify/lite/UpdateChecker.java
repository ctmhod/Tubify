package com.tubify.lite;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks GitHub Releases of the app's own repo for a newer version and can
 * download the right APK and hand it to Android's installer. No account, no
 * token: the repo is public and the releases API is free for this.
 */
public class UpdateChecker {

    private static final String REPO = "ctmhod/Tubify";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    // Full build APKs are named Tubify-v<ver>-<abi>.apk, Lite is Tubify-Lite-<ver>.apk.
    private static final String ASSET_PREFIX = BuildConfig.APPLICATION_ID.endsWith(".lite")
            ? "Tubify-Lite-" : "Tubify-v";

    public interface CheckListener {
        /** Already on the newest release. */
        void onLatest();
        /** A newer release exists and an APK asset was picked for this device. */
        void onUpdate(String tag, String assetName, String assetUrl, long sizeBytes);
        void onError(String message);
    }

    public interface DownloadListener {
        void onProgress(int percent);
        void onDone(File apk);
        void onError(String message);
    }

    public static void check(final String currentVersion, final CheckListener listener) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(LATEST_URL).openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "Tubify-update-check");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("GitHub returned HTTP " + code);
                String body = readAll(conn.getInputStream());
                JSONObject rel = new JSONObject(body);
                String tag = rel.optString("tag_name", "");
                if (!isNewer(tag, currentVersion)) {
                    ui.post(listener::onLatest);
                    return;
                }
                JSONArray assets = rel.optJSONArray("assets");
                String name = null, url = null;
                long size = 0;
                if (assets != null) {
                    // Prefer the APK for the device's own ABI (arm64 first on most phones).
                    for (String abi : Build.SUPPORTED_ABIS) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            String n = a.optString("name", "");
                            if (n.startsWith(ASSET_PREFIX) && n.endsWith(".apk") && n.contains(abi)) {
                                name = n;
                                url = a.optString("browser_download_url");
                                size = a.optLong("size");
                                break;
                            }
                        }
                        if (url != null) break;
                    }
                    // Fall back to the universal APK (or Lite's single APK).
                    if (url == null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            String n = a.optString("name", "");
                            if (n.startsWith(ASSET_PREFIX) && n.endsWith(".apk")
                                    && (ASSET_PREFIX.equals("Tubify-Lite-") || n.contains("universal"))) {
                                name = n;
                                url = a.optString("browser_download_url");
                                size = a.optLong("size");
                                break;
                            }
                        }
                    }
                }
                if (url == null) throw new Exception("No APK found in release " + tag);
                final String fTag = tag, fName = name, fUrl = url;
                final long fSize = size;
                ui.post(() -> listener.onUpdate(fTag, fName, fUrl, fSize));
            } catch (Exception e) {
                ui.post(() -> listener.onError(friendly(e)));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "update-check").start();
    }

    public static void download(final String url, final File dest, final DownloadListener listener) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", "Tubify-update-check");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                long total = conn.getContentLength();
                File parent = dest.getParentFile();
                if (parent != null) parent.mkdirs();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[16384];
                    long got = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        got += n;
                        if (total > 0) {
                            int pct = (int) (got * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                ui.post(() -> listener.onProgress(pct));
                            }
                        }
                    }
                }
                if (total > 0 && dest.length() != total) throw new Exception("Download incomplete");
                ui.post(() -> listener.onDone(dest));
            } catch (Exception e) {
                dest.delete();
                ui.post(() -> listener.onError(friendly(e)));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "update-download").start();
    }

    /** True when the app may request package installs without asking the user first. */
    public static boolean canInstall(Activity activity) {
        return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
    }

    /** Opens the system screen where the user allows this app to install APKs. */
    public static void openInstallPermission(Activity activity) {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(i);
    }

    /** Hands the downloaded APK to Android's package installer (shows the install prompt). */
    public static void install(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(i);
    }

    /** Numeric compare: "v0.1.57" is newer than "0.1.56". Missing parts count as 0. */
    public static boolean isNewer(String tag, String current) {
        int[] a = parts(tag);
        int[] b = parts(current);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        if (v == null) return new int[0];
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        String[] tok = v.split("[.\\-]");
        int[] out = new int[tok.length];
        for (int i = 0; i < tok.length; i++) {
            try {
                out[i] = Integer.parseInt(tok[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
        in.close();
        return sb.toString();
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private UpdateChecker() {
    }
}
