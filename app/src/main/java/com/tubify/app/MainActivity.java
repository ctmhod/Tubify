package com.tubify.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
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
import org.mozilla.geckoview.WebExtensionController;

public class MainActivity extends Activity {
    private static final String UBO_XPI =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi";
    private static final String UBO_ID = "uBlock0@raymondhill.net";
    private static final String TWEAKS_URI = "resource://android/assets/tweaks/";
    private static final String TWEAKS_ID = "tweaks@tubify.app";
    private static final String[] TWEAK_KEYS = {"shorts", "suggestions", "comments"};

    private static GeckoRuntime sRuntime;

    private GeckoView mView;
    private GeckoSession mSession;
    private SharedPreferences mPrefs;
    private WebExtensionController mExtController;
    private WebExtension.Port mTweaksPort;
    private WebExtension mUbo;
    private boolean mCanGoBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences("tubify", MODE_PRIVATE);
        applyImmersive();

        FrameLayout root = new FrameLayout(this);
        mView = new GeckoView(this);
        root.addView(mView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .build();
            sRuntime = GeckoRuntime.create(this, settings);
        }
        {
            mExtController = sRuntime.getWebExtensionController();
            mExtController.setPromptDelegate(new WebExtensionController.PromptDelegate() {
                @Override
                public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                        WebExtension extension, String[] permissions, String[] origins,
                        String[] dataCollectionPermissions) {
                    return GeckoResult.fromValue(
                            new WebExtension.PermissionPromptResponse(true, null, null));
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
            syncUbo();
        }

        mSession = new GeckoSession(new GeckoSessionSettings());
        GeckoSessionSettings ss = mSession.getSettings();
        ss.setAllowJavascript(true);
        ss.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
        ss.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        ss.setSuspendMediaWhenInactive(false);

        mSession.setContentDelegate(new GeckoSession.ContentDelegate() {
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
        });

        mSession.open(sRuntime);
        mView.setSession(mSession);
        mView.coverUntilFirstPaint(Color.BLACK);
        mSession.loadUri("https://m.youtube.com");
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
        sheetBg.setColor(0xFF1B1B1B);
        sheetBg.setCornerRadius(dp(20));
        sheet.setBackground(sheetBg);
        sheet.setPadding(dp(20), dp(18), dp(20), dp(12));

        TextView title = new TextView(this);
        title.setText("Tubify settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        sheet.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Tweaks apply instantly");
        subtitle.setTextColor(0xFF9E9E9E);
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(2), 0, dp(14));
        sheet.addView(subtitle);

        sheet.addView(sectionLabel("BLOCKING"));
        sheet.addView(settingRow(dialog, "Ad blocking", "uBlock Origin",
                mPrefs.getBoolean("adblock", true), (checked) -> {
                    mPrefs.edit().putBoolean("adblock", checked).apply();
                    syncUbo();
                }));

        sheet.addView(sectionLabel("INTERFACE"));
        sheet.addView(settingRow(dialog, "Hide Shorts", null,
                mPrefs.getBoolean("shorts", true),
                (checked) -> setTweak("shorts", checked)));
        sheet.addView(settingRow(dialog, "Hide suggestions", null,
                mPrefs.getBoolean("suggestions", true),
                (checked) -> setTweak("suggestions", checked)));
        sheet.addView(settingRow(dialog, "Hide comments", null,
                mPrefs.getBoolean("comments", false),
                (checked) -> setTweak("comments", checked)));

        TextView done = new TextView(this);
        done.setText("Done");
        done.setTextColor(0xFFFF4444);
        done.setTextSize(15);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(16), dp(12), dp(4), dp(4));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.gravity = Gravity.END;
        dlp.topMargin = dp(6);
        sheet.addView(done, dlp);
        done.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(sheet);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void setTweak(String key, boolean checked) {
        mPrefs.edit().putBoolean(key, checked).apply();
        sendTweaksSettings();
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(0xFF8A8A8A);
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
        rowBg.setColor(0xFF2A2A2A);
        rowBg.setCornerRadius(dp(14));
        row.setBackground(rowBg);
        row.setPadding(dp(16), dp(10), dp(12), dp(10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        texts.addView(label);
        if (subtitle != null) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextColor(0xFF9E9E9E);
            sub.setTextSize(12);
            sub.setPadding(0, dp(1), 0, 0);
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
                new int[]{Color.WHITE, 0xFFBDBDBD}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0xFFFF4444, 0xFF4A4A4A}));
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
        mExtController.ensureBuiltIn(TWEAKS_URI, TWEAKS_ID).accept(extension -> {
            if (extension == null) {
                return;
            }
            extension.setMessageDelegate(new WebExtension.MessageDelegate() {
                @Override
                public void onConnect(WebExtension.Port port) {
                    mTweaksPort = port;
                    port.setDelegate(new WebExtension.PortDelegate() {
                        @Override
                        public void onMessage(WebExtension.Port p, Object message) {
                            if (message instanceof JSONObject
                                    && "open-settings".equals(
                                            ((JSONObject) message).optString("type"))) {
                                runOnUiThread(() -> showSettings());
                            }
                        }
                    });
                    sendTweaksSettings();
                }
            }, "browser");
            // On the very first run the page can load before the extension is
            // active; reload once so the content script and CSS apply.
            if (!mPrefs.getBoolean("tweaks_first_reload", false)) {
                mPrefs.edit().putBoolean("tweaks_first_reload", true).apply();
                if (mSession != null) {
                    mSession.reload();
                }
            }
        }, error -> android.util.Log.e("Tubify", "tweaks install failed", error));
    }

    private void sendTweaksSettings() {
        if (mTweaksPort == null) {
            return;
        }
        JSONObject msg = new JSONObject();
        try {
            msg.put("shorts", mPrefs.getBoolean("shorts", true));
            msg.put("suggestions", mPrefs.getBoolean("suggestions", true));
            msg.put("comments", mPrefs.getBoolean("comments", false));
            mTweaksPort.postMessage(msg);
        } catch (JSONException ignored) {
        }
    }

    private void syncUbo() {
        final boolean want = mPrefs.getBoolean("adblock", true);
        mExtController.list().accept(extensions -> {
            WebExtension found = null;
            if (extensions != null) {
                for (WebExtension e : extensions) {
                    if (e.id != null && e.id.equals(UBO_ID)) {
                        found = e;
                        break;
                    }
                }
            }
            if (found == null) {
                if (want && !mPrefs.getBoolean("ubo", false)) {
                    mExtController.install(UBO_XPI).accept(
                            installed -> mPrefs.edit().putBoolean("ubo", true).apply(),
                            error -> android.util.Log.e("Tubify", "uBlock install failed", error));
                }
                return;
            }
            mUbo = found;
            boolean enabled = mUbo.metaData != null && mUbo.metaData.enabled;
            if (want && !enabled) {
                mExtController.enable(mUbo, WebExtensionController.EnableSource.USER);
            } else if (!want && enabled) {
                mExtController.disable(mUbo, WebExtensionController.EnableSource.USER);
            }
        });
    }

    private void applyImmersive() {
        Window w = getWindow();
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
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
}
