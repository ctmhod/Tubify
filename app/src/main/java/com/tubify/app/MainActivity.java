package com.tubify.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebExtensionController;

public class MainActivity extends Activity {
    // Tubify design tokens (Material 3 dark), colors from the launcher icon.
    // Theme palettes (base, card, border, text, text2, accent). Dark is the
    // Tubify classic look; Light is Tubify's own light palette. Accent is the
    // Tubify red in both.
    private static final int[] PALETTE_DARK = {0xFF07080D, 0xFF12141C, 0xFF20232E,
            0xFFE8EAF0, 0xFF8A90A0, 0xFFFF4444};
    private static final int[] PALETTE_LIGHT = {0xFFFAFAFC, 0xFFFFFFFF, 0xFFE2E4EC,
            0xFF14161D, 0xFF5A6072, 0xFFFF4444};
    private int COL_BASE, COL_CARD, COL_BORDER, COL_TEXT, COL_TEXT2, COL_ACCENT;

    private boolean isDark() {
        String t = mPrefs.getString("theme", "auto");
        if (t.equals("dark")) return true;
        if (t.equals("light")) return false;
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }


    /** Makes YouTube follow the Tubify theme through Gecko's preferred color scheme. */
    private void applyYtTheme() {
        try {
            if (sRuntime == null) return;
            String t = mPrefs.getString("theme", "auto");
            int v = t.equals("dark") ? GeckoRuntimeSettings.COLOR_SCHEME_DARK
                    : t.equals("light") ? GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                    : GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM;
            sRuntime.getSettings().setPreferredColorScheme(v);
        } catch (Exception ignored) { }
    }

    private void resolveTheme() {
        int[] p = isDark() ? PALETTE_DARK : PALETTE_LIGHT;
        COL_BASE = p[0];
        COL_CARD = p[1];
        COL_BORDER = p[2];
        COL_TEXT = p[3];
        COL_TEXT2 = p[4];
        COL_ACCENT = p[5];
    }

    private static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }


    private static final String UBO_XPI =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi";
    private static final String UBO_ID = "uBlock0@raymondhill.net";
    private static final String AG_XPI =
            "https://addons.mozilla.org/firefox/downloads/latest/adguard-adblocker/latest.xpi";
    private static final String AG_ID = "adguardadblocker@adguard.com";
    private static final String SB_XPI =
            "https://addons.mozilla.org/firefox/downloads/latest/sponsorblock/latest.xpi";
    private static final String SB_ID = "sponsorBlocker@ajay.app";
    private static final String ABP_XPI =
            "https://addons.mozilla.org/firefox/downloads/latest/adblock-plus/latest.xpi";
    private static final String ABP_ID = "{d10d0bf8-f5b5-c8b4-a8b2-2b9879e08c5d}";
    private static final String TWEAKS_URI = "resource://android/assets/tweaks/";
    private static final String TWEAKS_ID = "tweaks@tubify.app";
    private static final String[] TWEAK_KEYS = {"shorts", "suggestions", "comments"};

    private static GeckoRuntime sRuntime;

    private GeckoView mView;
    private GeckoSession mSession;
    private View mProgress;
    private SharedPreferences mPrefs;
    private WebExtensionController mExtController;
    private WebExtension.Port mTweaksPort;
    private WebExtension mUbo;
    private boolean mCanGoBack;
    private boolean mPageLoaded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ErrLog.init(this);
        logPreviousExits();
        mPrefs = getSharedPreferences("tubify", MODE_PRIVATE);
        String forcedTheme = getIntent().getStringExtra("tubify_theme");
        if (forcedTheme != null) mPrefs.edit().putString("theme", forcedTheme).apply();
        resolveTheme();
        applyImmersive();

        FrameLayout root = new FrameLayout(this);
        mView = new GeckoView(this);
        root.addView(mView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mProgress = new View(this);
        GradientDrawable progressBg = new GradientDrawable();
        progressBg.setColor(COL_ACCENT);
        mProgress.setBackground(progressBg);
        mProgress.setVisibility(View.GONE);
        root.addView(mProgress, new FrameLayout.LayoutParams(0, dp(3)));
        setContentView(root);
        if (getIntent().getBooleanExtra("tubify_open_settings", false)) {
            root.postDelayed(() -> showSettings(), 4000);
        }

        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .build();
            sRuntime = GeckoRuntime.create(this, settings);
        }
        applyYtTheme();
        {
            mExtController = sRuntime.getWebExtensionController();
            mExtController.setPromptDelegate(new WebExtensionController.PromptDelegate() {
                @Override
                public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                        WebExtension extension, String[] permissions, String[] origins,
                        String[] dataCollectionPermissions) {
                    return GeckoResult.fromValue(
                            new WebExtension.PermissionPromptResponse(Boolean.TRUE, Boolean.TRUE, Boolean.TRUE));
                }

                @Override
                public GeckoResult<AllowOrDeny> onOptionalPrompt(
                        WebExtension extension, String[] permissions, String[] origins,
                        String[] dataCollectionPermissions) {
                    return GeckoResult.allow();
                }

                @Override
                public GeckoResult<AllowOrDeny> onUpdatePrompt(
                        WebExtension extension, String[] permissions, String[] origins,
                        String[] dataCollectionPermissions) {
                    return GeckoResult.allow();
                }
            });
            installTweaks();
            syncBlockers();
        }

        mSession = new GeckoSession(new GeckoSessionSettings());
        GeckoSessionSettings ss = mSession.getSettings();
        ss.setAllowJavascript(true);
        ss.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
        ss.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        ss.setSuspendMediaWhenInactive(false);

        mSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(GeckoSession session) {
                ErrLog.log("gecko", "content process crash");
            }

            @Override
            public void onFullScreen(GeckoSession session, boolean fullScreen) {
                if (fullScreen) {
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                } else {
                    applyImmersive();
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
            }
        });

        mSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBack) {
                mCanGoBack = canGoBack;
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session,
                    LoadRequest request) {
                if (request.uri != null && !request.uri.startsWith("http://")
                        && !request.uri.startsWith("https://")) {
                    // Never fire app-launch intents (youtube://, intent://, vnd.youtube:).
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                return null;
            }

            @Override
            public GeckoResult<String> onLoadError(GeckoSession session, String uri,
                    WebRequestError error) {
                ErrLog.log("page", "load error "
                        + (error != null ? error.category + "/" + error.code : "") + " " + uri);
                if (uri == null || !uri.startsWith("http")) {
                    return null;
                }
                // Only genuine network failures get Tubify's page; certificate,
                // content and other categories keep Gecko's own error page so a
                // healthy page is never replaced by a false error.
                if (error == null || error.category != WebRequestError.ERROR_CATEGORY_NETWORK) {
                    return null;
                }
                String detail = ("network error " + error.code)
                        .replace("<", "").replace(">", "");
                String html = "<!doctype html><meta name=viewport content='width=device-width'>"
                        + "<body style='margin:0;background:" + hex(COL_BASE) + ";color:" + hex(COL_TEXT) + ";"
                        + "font-family:sans-serif;display:flex;flex-direction:column;"
                        + "align-items:center;justify-content:center;height:100vh;text-align:center'>"
                        + "<div style='font-size:64px;font-weight:bold;color:#FF4444'>T</div>"
                        + "<div style='font-size:20px;font-weight:bold;margin:16px 0 8px'>"
                        + "Couldn't load YouTube</div>"
                        + "<div style='color:" + hex(COL_TEXT2) + ";font-size:14px;margin-bottom:28px'>"
                        + "Check your connection and try again.</div>"
                        + "<div style='color:" + hex(COL_TEXT2) + ";font-size:11px;margin:-18px 24px 24px'>"
                        + detail + "</div>"
                        + "<a href='" + uri.replace("'", "%27") + "' style='background:" + hex(COL_CARD) + ";"
                        + "border:1px solid " + hex(COL_BORDER) + ";color:" + hex(COL_TEXT) + ";padding:14px 42px;"
                        + "border-radius:28px;text-decoration:none;font-size:16px'>Retry</a>"
                        + "</body>";
                return GeckoResult.fromValue(
                        "data:text/html;charset=utf-8," + Uri.encode(html, ":/#?&='+"));
            }
        });

        mSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession session, String url) {
                mPageLoaded = false;
            }

            @Override
            public void onProgressChange(GeckoSession session, int progress) {
                if (progress >= 100) {
                    mProgress.setVisibility(View.GONE);
                    return;
                }
                mProgress.setVisibility(View.VISIBLE);
                ViewGroup.LayoutParams lp = mProgress.getLayoutParams();
                lp.width = (int) (getResources().getDisplayMetrics().widthPixels
                        * Math.max(progress, 5) / 100f);
                mProgress.setLayoutParams(lp);
            }

            @Override
            public void onPageStop(GeckoSession session, boolean success) {
                mPageLoaded = true;
            }
        });

        mSession.open(sRuntime);
        mView.setSession(mSession);
        mView.coverUntilFirstPaint(Color.BLACK);
        String t = ytTarget(getIntent());
        mSession.loadUri(t != null ? t : "https://m.youtube.com");
    }

    /** Maps an incoming VIEW link (youtube.com, youtu.be) to a m.youtube.com URL, or null. */
    private static String ytTarget(android.content.Intent in) {
        if (in == null || !android.content.Intent.ACTION_VIEW.equals(in.getAction())) return null;
        android.net.Uri u = in.getData();
        if (u == null || u.getHost() == null) return null;
        String sch = String.valueOf(u.getScheme());
        if (!sch.equals("https") && !sch.equals("http")) return null;
        String h = u.getHost().toLowerCase(java.util.Locale.US);
        if (h.equals("youtu.be")) {
            String id = u.getLastPathSegment();
            if (id == null) return null;
            String t = u.getQueryParameter("t");
            return "https://m.youtube.com/watch?v=" + android.net.Uri.encode(id)
                    + (t != null ? "&t=" + android.net.Uri.encode(t) : "");
        }
        if (h.equals("youtube.com") || h.endsWith(".youtube.com")) {
            String path = u.getEncodedPath() == null ? "/" : u.getEncodedPath();
            String q = u.getEncodedQuery();
            return "https://m.youtube.com" + path + (q != null ? "?" + q : "");
        }
        return null;
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String t = ytTarget(intent);
        if (t != null && mSession != null) mSession.loadUri(t);
    }

    // --- Settings sheet (dark, rounded cards, switches) --------------------
    // The settings gear itself lives inside the YouTube header, injected by
    // the tweaks extension next to the logo.

    private void showSettings() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(COL_BASE);
        float sheetR = dp(28);
        sheetBg.setCornerRadii(new float[]{sheetR, sheetR, sheetR, sheetR, 0, 0, 0, 0});
        sheet.setBackground(sheetBg);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(28));
        View handle = new View(this);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(COL_BORDER);
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(32), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(14);
        sheet.addView(handle, hlp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        
        TextView title = new TextView(this);
        title.setText("Tubify settings");
        title.setTextColor(COL_TEXT);
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView subtitle = new TextView(this);
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        subtitle.setText("v" + ver + " - tweaks apply instantly");
        subtitle.setTextColor(COL_TEXT2);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        content.addView(subtitle);

        content.addView(sectionLabel("BLOCKING"));
        content.addView(settingRow(dialog, "Block ads", "Blocks ads on YouTube",
                mPrefs.getBoolean("adblock", true), (checked) -> {
                    mPrefs.edit().putBoolean("adblock", checked)
                            .putBoolean("adguard", false).putBoolean("abp", false).apply();
                    syncBlockers();
                    // The extension switch is asynchronous; reload once it has applied.
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (mSession != null) mSession.reload();
                    }, 900);
                }));
        content.addView(settingRow(dialog, "Block sponsors", "Skips sponsor segments inside videos",
                mPrefs.getBoolean("sponsors", true), (checked) -> {
                    mPrefs.edit().putBoolean("sponsors", checked).apply();
                    syncBlockers();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (mSession != null) mSession.reload();
                    }, 900);
                }));
        TextView sbNote = new TextView(this);
        sbNote.setText("Segment data from SponsorBlock (sponsor.ajay.app), CC BY-NC-SA 4.0");
        sbNote.setTextColor(COL_TEXT2);
        sbNote.setTextSize(11);
        sbNote.setPadding(dp(4), dp(2), dp(4), dp(6));
        content.addView(sbNote);

        content.addView(sectionLabel("INTERFACE"));
        content.addView(themeRow(dialog));
        content.addView(settingRow(dialog, "Hide Shorts", null,
                mPrefs.getBoolean("shorts", false),
                (checked) -> setTweak("shorts", checked)));
        content.addView(settingRow(dialog, "Hide suggestions", null,
                mPrefs.getBoolean("suggestions", false),
                (checked) -> setTweak("suggestions", checked)));
        content.addView(settingRow(dialog, "Hide comments", null,
                mPrefs.getBoolean("comments", false),
                (checked) -> setTweak("comments", checked)));
        content.addView(settingRow(dialog, "Hide home feed", "Start on a blank home",
                mPrefs.getBoolean("home", false),
                (checked) -> setTweak("home", checked)));

        content.addView(actionRow("Reset to defaults", "Auto theme, ads and sponsors blocked, nothing hidden", () -> {
            mPrefs.edit().putString("theme", "auto").putBoolean("adblock", true).putBoolean("sponsors", true)
                    .putBoolean("adguard", false).putBoolean("abp", false)
                    .putBoolean("shorts", false).putBoolean("suggestions", false)
                    .putBoolean("comments", false).putBoolean("home", false).apply();
            resolveTheme();
            applyYtTheme();
            syncBlockers();
            sendTweaksSettings();
            if (mSession != null) mSession.reload();
            dialog.dismiss();
            showSettings();
        }));

        content.addView(sectionLabel("DEVELOPER"));
        content.addView(linkRow(dialog, "Source code", "github.com/ctmhod/Tubify", "https://github.com/ctmhod/Tubify"));
        content.addView(linkRow(dialog, "Developer", "@ctmhod on Telegram", "https://t.me/ctmhod"));
        TextView errRow = new TextView(this);
        errRow.setText("Error log");
        errRow.setTextColor(COL_TEXT);
        errRow.setTextSize(16);
        GradientDrawable errBg = new GradientDrawable();
        errBg.setColor(COL_CARD);
        errBg.setCornerRadius(dp(16));
        errBg.setStroke(dp(1), COL_BORDER);
        errRow.setBackground(errBg);
        errRow.setPadding(dp(16), dp(12), dp(12), dp(12));
        errRow.setMinimumHeight(dp(64));
        errRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = dp(8);
        content.addView(errRow, elp);
        errRow.setOnClickListener(v -> {
            dialog.dismiss();
            showErrorLog();
        });
        content.addView(linkRow(dialog, "Releases", "Latest APKs", "https://github.com/ctmhod/Tubify/releases"));
        content.addView(linkRow(dialog, "Report an issue", "github.com/ctmhod/Tubify/issues", "https://github.com/ctmhod/Tubify/issues"));

        content.addView(sectionLabel("DIAGNOSTICS"));
        TextView diag = new TextView(this);
        diag.setText(buildDiagnostics());
        diag.setTextColor(COL_TEXT2);
        diag.setTextSize(12);
        diag.setPadding(dp(4), 0, dp(4), dp(4));
        content.addView(diag);

        TextView done = new TextView(this);
        done.setText("Done");
        done.setTextColor(COL_ACCENT);
        done.setTextSize(15);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(16), dp(12), dp(4), dp(4));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.END;
        dlp.topMargin = dp(6);
        content.addView(done, dlp);
        done.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void setTweak(String key, boolean checked) {
        mPrefs.edit().putBoolean(key, checked).apply();
        sendTweaksSettings();
    }


    private View themeRow(final Dialog dialog) {
        String cur = mPrefs.getString("theme", "auto");
        String sub = cur.equals("dark") ? "Dark"
                : cur.equals("light") ? "Light" : "Auto (follow system)";
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_CARD);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), COL_BORDER);
        row.setBackground(bg);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("Theme");
        t.setTextColor(COL_TEXT);
        t.setTextSize(16);
        row.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(COL_TEXT2);
        s.setTextSize(12);
        s.setPadding(0, dp(2), 0, 0);
        row.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> showThemeChooser(dialog));
        return row;
    }

    private void showThemeChooser(final Dialog settingsDialog) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(COL_BASE);
        boxBg.setCornerRadius(dp(16));
        box.setBackground(boxBg);
        box.setPadding(dp(20), dp(14), dp(20), dp(14));
        TextView title = new TextView(this);
        title.setText("Theme");
        title.setTextColor(COL_TEXT);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(6));
        box.addView(title);
        final String[] keys = {"auto", "dark", "light"};
        final String[] names = {"Auto (follow system)", "Dark", "Light"};
        for (int i = 0; i < keys.length; i++) {
            final int idx = i;
            TextView opt = new TextView(this);
            opt.setText(names[i]);
            opt.setTextColor(COL_TEXT);
            opt.setTextSize(15);
            opt.setPadding(dp(8), dp(10), dp(8), dp(10));
            box.addView(opt);
            opt.setOnClickListener(v -> {
                mPrefs.edit().putString("theme", keys[idx]).apply();
                resolveTheme();
                applyYtTheme();
                if (mSession != null) mSession.reload();
                dialog.dismiss();
                settingsDialog.dismiss();
                showSettings();
            });
        }
        dialog.setContentView(box);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
    }


    private View actionRow(String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_CARD);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), COL_BORDER);
        row.setBackground(bg);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(COL_TEXT);
        t.setTextSize(16);
        row.addView(t);
        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(COL_TEXT2);
        s.setTextSize(12);
        s.setPadding(0, dp(2), 0, 0);
        row.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private View linkRow(Dialog dialog, String title, String subtitle, String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COL_CARD);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), COL_BORDER);
        row.setBackground(bg);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(COL_TEXT);
        t.setTextSize(16);
        row.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(COL_TEXT2);
            s.setTextSize(12);
            s.setPadding(0, dp(2), 0, 0);
            row.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
            }
        });
        return row;
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(COL_ACCENT);
        label.setTextSize(12);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.08f);
        label.setPadding(dp(2), dp(8), 0, dp(6));
        return label;
    }

    private interface ToggleHandler {
        void onToggle(boolean checked);
    }

    private View settingRow(Dialog dialog, String title, String subtitle,
            boolean checked, ToggleHandler handler) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(COL_CARD);
        rowBg.setCornerRadius(dp(16));
        rowBg.setStroke(dp(1), COL_BORDER);
        row.setBackground(rowBg);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(COL_TEXT);
        label.setTextSize(16);
        texts.addView(label);
        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextColor(COL_TEXT2);
            sub.setTextSize(12);
            sub.setPadding(0, dp(2), 0, 0);
            texts.addView(sub);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, tlp);

        Switch sw = new Switch(this);
        sw.setShowText(false);
        sw.setSwitchMinWidth(dp(44));
        sw.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Color.WHITE, COL_TEXT2}));
        {
            GradientDrawable on = new GradientDrawable();
            on.setColor(COL_ACCENT);
            on.setCornerRadius(dp(14));
            on.setSize(dp(46), dp(26));
            GradientDrawable off = new GradientDrawable();
            off.setColor((COL_TEXT2 & 0x00FFFFFF) | 0x66000000);
            off.setCornerRadius(dp(14));
            off.setSize(dp(46), dp(26));
            android.graphics.drawable.StateListDrawable track =
                    new android.graphics.drawable.StateListDrawable();
            track.addState(new int[]{android.R.attr.state_checked}, on);
            track.addState(new int[]{}, off);
            sw.setTrackDrawable(track);
            sw.setTrackTintList(null);
        }
        sw.setOnCheckedChangeListener((button, isChecked) -> handler.onToggle(isChecked));
        row.addView(sw);

        sw.setChecked(checked);
        row.setOnClickListener(v -> sw.toggle());

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        wrap.setPadding(0, 0, 0, dp(8));
        return wrap;
    }

    // --- Extension plumbing -------------------------------------------------

    private void installTweaks() {
        mPrefs.edit()
                .putString("diag_tweaks", "installing")
                .putBoolean("diag_bridge", false)
                .apply();
        mExtController.ensureBuiltIn(TWEAKS_URI, TWEAKS_ID).accept(extension -> {
            if (extension == null) {
                mPrefs.edit().putString("diag_tweaks", "failed: no extension returned").apply();
                return;
            }
            final String bundledVer = extension.metaData != null && extension.metaData.version != null
                    ? extension.metaData.version : "?";
            mPrefs.edit().putString("diag_tweaks", "installed v" + bundledVer).apply();
            extension.setMessageDelegate(new WebExtension.MessageDelegate() {
                @Override
                public void onConnect(WebExtension.Port port) {
                    mTweaksPort = port;
                    mPrefs.edit().putBoolean("diag_bridge", true).apply();
                    port.setDelegate(new WebExtension.PortDelegate() {
                        @Override
                        public void onPortMessage(Object message, WebExtension.Port p) {
                            if (!(message instanceof JSONObject)) {
                                return;
                            }
                            String type = ((JSONObject) message).optString("type");
                            if ("open-settings".equals(type)) {
                                runOnUiThread(() -> showSettings());
                            } else if ("log".equals(type)) {
                                ErrLog.log("ext", ((JSONObject) message).optString("msg"));
                            } else if ("tweaks-ready".equals(type)) {
                                String v = ((JSONObject) message).optString("version");
                                mPrefs.edit().putString("diag_tweaks",
                                        "active v" + (v.isEmpty() ? bundledVer : v)).apply();
                            }
                        }

                        @Override
                        public void onDisconnect(WebExtension.Port p) {
                            if (p == mTweaksPort) {
                                mTweaksPort = null;
                            }
                            mPrefs.edit().putBoolean("diag_bridge", false).apply();
                        }
                    });
                    sendTweaksSettings();
                }
            }, "browser");
            // The page can finish loading before the extension is active (not
            // only on the very first run); reload once so the content script
            // and its CSS actually apply to the loaded page.
            if (mSession != null
                    && (mPageLoaded || !mPrefs.getBoolean("tweaks_first_reload", false))) {
                mPrefs.edit().putBoolean("tweaks_first_reload", true).apply();
                mSession.reload();
            }
        }, error -> {
            android.util.Log.e("Tubify", "tweaks install failed", error);
            String msg = error != null && error.getMessage() != null ? error.getMessage() : "unknown";
            mPrefs.edit().putString("diag_tweaks", "failed: " + msg).apply();
            ErrLog.log("tweaks", "install failed: " + msg);
        });
    }

    private void sendTweaksSettings() {
        if (mTweaksPort == null) {
            return;
        }
        JSONObject msg = new JSONObject();
        try {
            msg.put("shorts", mPrefs.getBoolean("shorts", false));
            msg.put("suggestions", mPrefs.getBoolean("suggestions", false));
            msg.put("comments", mPrefs.getBoolean("comments", false));
            msg.put("home", mPrefs.getBoolean("home", false));
            mTweaksPort.postMessage(msg);
        } catch (JSONException ignored) {
        }
    }

    private void syncBlockers() {
        syncBlocker("adblock", true, "diag_ubo", "ubo", UBO_ID, UBO_XPI, "uBlock Origin");
        syncBlocker("adguard", false, "diag_ag", "ag", AG_ID, AG_XPI, "AdGuard");
        syncBlocker("abp", false, "diag_abp", "abp", ABP_ID, ABP_XPI, "AdBlock Plus");
        syncBlocker("sponsors", true, "diag_sb", "sb", SB_ID, SB_XPI, "SponsorBlock");
    }

    private void syncBlocker(String prefKey, boolean def, String diagKey, String installedKey,
            String extId, String xpiUrl, String name) {
        final boolean want = mPrefs.getBoolean(prefKey, def);
        if (!want) {
            mPrefs.edit().putString(diagKey, "off").apply();
        }
        mExtController.list().accept(extensions -> {
            WebExtension found = null;
            if (extensions != null) {
                for (WebExtension e : extensions) {
                    if (e.id != null && e.id.equals(extId)) {
                        found = e;
                        break;
                    }
                }
            }
            if (found == null) {
                if (want && !mPrefs.getBoolean(installedKey, false)) {
                    mPrefs.edit().putString(diagKey, "installing").apply();
                    mExtController.install(xpiUrl).accept(
                            installed -> mPrefs.edit()
                                    .putBoolean(installedKey, true)
                                    .putString(diagKey, "active")
                                    .apply(),
                            error -> {
                                String msg = error != null && error.getMessage() != null
                                        ? error.getMessage() : "unknown";
                                mPrefs.edit().putString(diagKey,
                                        "install failed: " + msg + " - retries on next start").apply();
                                ErrLog.log("blocker", name + " install failed: " + msg);
                            });
                } else if (want) {
                    mPrefs.edit().putString(diagKey, "install pending - retries on next start").apply();
                }
                return;
            }
            boolean enabled = found.metaData != null && found.metaData.enabled;
            if (want && !enabled) {
                mExtController.enable(found, WebExtensionController.EnableSource.USER);
                mPrefs.edit().putString(diagKey, "active").apply();
            } else if (!want && enabled) {
                mExtController.disable(found, WebExtensionController.EnableSource.USER);
                mPrefs.edit().putString(diagKey, "off").apply();
            } else {
                mPrefs.edit().putString(diagKey, want ? "active" : "off").apply();
            }
        });
    }

    private void showErrorLog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(COL_BASE);
        float boxR = dp(28);
        boxBg.setCornerRadii(new float[]{boxR, boxR, boxR, boxR, 0, 0, 0, 0});
        box.setBackground(boxBg);
        box.setPadding(dp(16), dp(10), dp(16), dp(8));
        View handle = new View(this);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(COL_BORDER);
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(32), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(14);
        box.addView(handle, hlp);


        TextView title = new TextView(this);
        title.setText("Error log");
        title.setTextColor(COL_TEXT);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(ErrLog.read());
        body.setTextColor(COL_TEXT);
        body.setTextSize(11);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(8), 0, dp(8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(body);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        box.addView(sv, slp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        TextView copy = logButton("Copy");
        TextView clear = logButton("Clear");
        TextView close = logButton("Close");
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Tubify error log", ErrLog.read()));
            android.widget.Toast.makeText(this, "Error log copied",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        clear.setOnClickListener(v -> {
            ErrLog.clear();
            body.setText(ErrLog.read());
        });
        close.setOnClickListener(v -> dialog.dismiss());
        btns.addView(copy);
        btns.addView(clear);
        btns.addView(close);
        box.addView(btns);

        dialog.setContentView(box);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.75f);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
        }
        dialog.show();
    }

    private TextView logButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(COL_ACCENT);
        b.setTextSize(14);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setPadding(dp(14), dp(10), dp(6), dp(4));
        return b;
    }

    private String buildDiagnostics() {
        String tweaks = mPrefs.getString("diag_tweaks", "not installed yet");
        boolean bridge = mPrefs.getBoolean("diag_bridge", false);
        String ubo = mPrefs.getString("diag_ubo", "not installed yet");
        String ag = mPrefs.getString("diag_ag", "off");
        String abp = mPrefs.getString("diag_abp", "off");
        return "Tweaks extension: " + tweaks + "\n"
                + "App bridge: " + (bridge ? "connected" : "not connected") + "\n"
                + "Ad blocking: " + ubo + "\n"
                + "Sponsors: " + mPrefs.getString("diag_sb", "not installed yet");
    }

    private void applyImmersive() {
        Window w = getWindow();
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        w.setStatusBarColor(COL_BASE);
        w.setNavigationBarColor(COL_BASE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onBackPressed() {
        if (mCanGoBack) {
            mSession.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (mSession != null) {
            mSession.close();
        }
        super.onDestroy();
    }

    private void logPreviousExits() {
        // Native/child-process crashes bypass the Java crash handler; on the
        // next launch, ApplicationExitInfo (API 30+) still tells us what died.
        if (android.os.Build.VERSION.SDK_INT < 30) {
            return;
        }
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> exits =
                    am.getHistoricalProcessExitReasons(getPackageName(), 0, 5);
            for (android.app.ApplicationExitInfo info : exits) {
                ErrLog.log("exit", "reason=" + info.getReason() + " status="
                        + info.getStatus() + " process=" + info.getProcessName()
                        + " importance=" + info.getImportance()
                        + (info.getDescription() != null ? " desc=" + info.getDescription() : ""));
                try {
                    java.io.InputStream trace = info.getTraceInputStream();
                    if (trace != null) {
                        byte[] buf = new byte[700];
                        int n = trace.read(buf);
                        trace.close();
                        if (n > 0) {
                            ErrLog.log("trace", new String(buf, 0, n, "UTF-8"));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

}
