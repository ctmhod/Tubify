package com.tubify.lite;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com";
    private static final String[] TWEAK_KEYS = {"shorts", "suggestions", "comments"};

    // Network-level ad blocking: substrings matched against every request URL.
    private static final String[] AD_PATTERNS = {
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.",
            "youtube.com/api/stats/ads",
            "youtube.com/pagead/",
            "youtube.com/ptracking",
            "youtube.com/get_midroll",
            "youtube.com/api/stats/qoe?",
            "/ad_break"
    };

    private WebView mWeb;
    private Chrome mChrome;
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomCallback;
    private SharedPreferences mPrefs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences("tubify_lite", MODE_PRIVATE);
        applyImmersive();

        FrameLayout root = new FrameLayout(this);
        mWeb = new WebView(this);
        root.addView(mWeb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView gear = buildGear();
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        glp.setMargins(0, dp(8), dp(8), 0);
        root.addView(gear, glp);
        setContentView(root);

        WebSettings s = mWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // Look like a normal Chrome Mobile browser, not an embedded WebView:
        // Google sign-in refuses WebView user agents ("disallowed_useragent").
        String ua = s.getUserAgentString();
        ua = ua.replace("; wv)", ")");
        ua = ua.replace("Version/4.0 ", "");
        s.setUserAgentString(ua);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(mWeb, true);

        mWeb.setWebViewClient(new Client());
        mChrome = new Chrome();
        mWeb.setWebChromeClient(mChrome);

        if (savedInstanceState == null) {
            mWeb.loadUrl(HOME);
        }
    }

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (mPrefs.getBoolean("adblock", true) && request.getUrl() != null) {
                String url = request.getUrl().toString().toLowerCase(Locale.US);
                for (String p : AD_PATTERNS) {
                    if (url.contains(p)) {
                        return new WebResourceResponse("text/plain", "utf-8",
                                new ByteArrayInputStream(new byte[0]));
                    }
                }
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String h = u.getHost() == null ? "" : u.getHost();
            if (h.endsWith("youtube.com") || h.endsWith("googlevideo.com")
                    || h.endsWith("ytimg.com") || h.endsWith("ggpht.com")
                    || h.endsWith("google.com") || h.endsWith("gstatic.com")
                    || h.endsWith("googleusercontent.com")) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) {
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectTweaks();
            injectAdSkipper();
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            mCustomView = view;
            mCustomCallback = callback;
            ViewGroup root = (ViewGroup) mWeb.getParent();
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mWeb.setVisibility(View.GONE);
            setFullscreenUi(true);
        }

        @Override
        public void onHideCustomView() {
            if (mCustomView == null) return;
            ViewGroup root = (ViewGroup) mWeb.getParent();
            root.removeView(mCustomView);
            mCustomView = null;
            if (mCustomCallback != null) mCustomCallback.onCustomViewHidden();
            mWeb.setVisibility(View.VISIBLE);
            setFullscreenUi(false);
        }
    }

    private void setFullscreenUi(boolean full) {
        getWindow().getDecorView().setSystemUiVisibility(full
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    // --- Tweak CSS injection ---------------------------------------------

    private void injectTweaks() {
        boolean shorts = mPrefs.getBoolean("shorts", true);
        boolean suggestions = mPrefs.getBoolean("suggestions", true);
        boolean comments = mPrefs.getBoolean("comments", false);
        StringBuilder css = new StringBuilder();
        if (shorts) css.append(
                "a[href^=\"/shorts\"],ytm-reel-shelf-renderer,ytm-rich-shelf-renderer,"
                + "ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,"
                + "ytm-video-with-context-renderer:has(a[href^=\"/shorts\"]),"
                + "ytd-reel-shelf-renderer,ytd-rich-shelf-renderer,ytd-reel-video-renderer,"
                + "ytd-rich-item-renderer:has(a[href^=\"/shorts\"])"
                + "{display:none!important}");
        if (suggestions) css.append(
                "ytm-watch-next-secondary-results-renderer,ytm-related-chip-cloud-renderer,"
                + "ytm-item-section-renderer[section-identifier=\"related-items\"],"
                + "ytd-watch-next-secondary-results-renderer,#related{display:none!important}");
        if (comments) css.append(
                "ytm-comment-section-renderer,ytd-comments,#comments{display:none!important}");
        String js = "(function(){var e=document.getElementById('tubify-tweaks');"
                + "if(!e){e=document.createElement('style');e.id='tubify-tweaks';"
                + "(document.head||document.documentElement).appendChild(e);}"
                + "e.textContent=" + jsString(css.toString()) + ";})();";
        mWeb.evaluateJavascript(js, null);
    }

    // --- Ad skipper: mute + fast-forward skippable/unskippable video ads ---

    private void injectAdSkipper() {
        if (!mPrefs.getBoolean("adblock", true)) return;
        String js = "(function(){if(window.__tubifySkip)return;window.__tubifySkip=1;"
                + "setInterval(function(){try{"
                + "var ad=document.querySelector('.ad-showing,.ad-interrupting');"
                + "var v=document.querySelector('video');"
                + "if(ad&&v){v.muted=true;if(isFinite(v.duration)&&v.duration>0){"
                + "v.currentTime=v.duration;}v.playbackRate=16;}"
                + "var b=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,"
                + ".ytp-ad-skip-button-modern,button.ytp-ad-overlay-close-button');"
                + "if(b){b.click();}"
                + "var ov=document.querySelector('.ytp-ad-overlay-close-button');"
                + "if(ov){ov.click();}"
                + "}catch(e){}},700);})();";
        mWeb.evaluateJavascript(js, null);
    }

    private static String jsString(String s) {
        StringBuilder b = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\'': b.append("\\'"); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                default: b.append(c);
            }
        }
        return b.append('\'').toString();
    }

    // --- Gear + settings dialog ------------------------------------------

    private TextView buildGear() {
        TextView gear = new TextView(this);
        gear.setText("\u2699");
        gear.setTextColor(Color.WHITE);
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        gear.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(22));
        gear.setBackground(bg);
        gear.setOnClickListener(v -> showSettings());
        return gear;
    }

    private void showSettings() {
        String[] labels = {
                "Ad blocking",
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
                .setTitle("Tubify Lite settings")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    if (which == 0) {
                        mPrefs.edit().putBoolean("adblock", isChecked).apply();
                    } else {
                        mPrefs.edit().putBoolean(TWEAK_KEYS[which - 1], isChecked).apply();
                    }
                })
                .setPositiveButton("Done", (dialog, which) -> {
                    injectTweaks();
                    injectAdSkipper();
                })
                .show();
    }

    // --- Activity plumbing ------------------------------------------------

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
        if (mCustomView != null) {
            mChrome.onHideCustomView();
            return;
        }
        if (mWeb.canGoBack()) {
            mWeb.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mWeb.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mWeb.onResume();
    }

    @Override
    protected void onDestroy() {
        if (mWeb != null) {
            mWeb.destroy();
        }
        super.onDestroy();
    }
}
