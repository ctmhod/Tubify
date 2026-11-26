package com.tubify.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
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
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView gear = buildGear();
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        glp.setMargins(0, dp(8), dp(8), 0);
        root.addView(gear, glp);
        setContentView(root);

        if (sRuntime == null) {
            GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .build();
            sRuntime = GeckoRuntime.create(this, settings);
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
                    gear.setVisibility(View.GONE);
                } else {
                    applyImmersive();
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    gear.setVisibility(View.VISIBLE);
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

    private TextView buildGear() {
        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        gear.setTextColor(Color.WHITE);
        gear.setGravity(Gravity.CENTER);
        gear.setContentDescription("Tubify settings");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        gear.setBackground(bg);
        gear.setOnClickListener(v -> showSettings());
        return gear;
    }

    private void showSettings() {
        String[] labels = {
                "Ad blocking (uBlock Origin)",
                "Hide Shorts",
                "Hide suggestions",
                "Hide comments"
        };
        boolean[] checked = {
                mPrefs.getBoolean("adblock", true),
                mPrefs.getBoolean("shorts", true),
                mPrefs.getBoolean("suggestions", true),
                mPrefs.getBoolean("comments", false)
        };
        new AlertDialog.Builder(this)
                .setTitle("Tubify settings")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    if (which == 0) {
                        mPrefs.edit().putBoolean("adblock", isChecked).apply();
                        syncUbo();
                    } else {
                        mPrefs.edit().putBoolean(TWEAK_KEYS[which - 1], isChecked).apply();
                    }
                })
                .setPositiveButton("Done", (dialog, which) -> sendTweaksSettings())
                .show();
    }

    private void installTweaks() {
        mExtController.ensureBuiltIn(TWEAKS_URI, TWEAKS_ID).accept(extension -> {
            if (extension == null) {
                return;
            }
            extension.setMessageDelegate(new WebExtension.MessageDelegate() {
                @Override
                public void onConnect(WebExtension.Port port) {
                    mTweaksPort = port;
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
                if (want && !mPrefs.getBoolean("ubo_tried", false)) {
                    mPrefs.edit().putBoolean("ubo_tried", true).apply();
                    mExtController.install(UBO_XPI).accept(
                            installed -> mPrefs.edit().putBoolean("ubo", true).apply(),
                            error -> mPrefs.edit().putBoolean("ubo_tried", false).apply());
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
