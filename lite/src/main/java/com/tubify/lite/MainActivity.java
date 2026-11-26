package com.tubify.lite;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOME = "https://m.youtube.com";

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
            "/ad_break"
    };

    private WebView mWeb;
    private Chrome mChrome;
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomCallback;
    private SharedPreferences mPrefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ErrLog.init(this);
        mPrefs = getSharedPreferences("tubify_lite", MODE_PRIVATE);
        applyImmersive();

        FrameLayout root = new FrameLayout(this);
        mWeb = new WebView(this);
        root.addView(mWeb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

        mWeb.addJavascriptInterface(new TubifyJs(), "Tubify");
        mWeb.setWebViewClient(new Client());
        mChrome = new Chrome();
        mWeb.setWebChromeClient(mChrome);

        if (savedInstanceState != null) {
            mWeb.restoreState(savedInstanceState);
        } else {
            mWeb.loadUrl(HOME);
        }
    }

    private class TubifyJs {
        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> showSettings());
        }
    }

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (mPrefs.getBoolean("adblock", true) && request.getUrl() != null) {
                String url = request.getUrl().toString().toLowerCase(Locale.US);
                for (String p : AD_PATTERNS) {
                    if (url.contains(p)) {
                        // 204 + permissive CORS: the page sees a clean empty
                        // response instead of a CORS/network error, so YouTube
                        // scripts do not misbehave or spam the console.
                        java.util.Map<String, String> headers = new java.util.HashMap<>();
                        headers.put("Access-Control-Allow-Origin", "*");
                        return new WebResourceResponse("text/plain", "utf-8",
                                204, "No Content", headers,
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
        public void onReceivedError(WebView view, WebResourceRequest request,
                android.webkit.WebResourceError error) {
            if (error != null) {
                ErrLog.log("page", "load error " + error.getErrorCode() + " "
                        + error.getDescription() + " " + request.getUrl());
            }
            if (request.isForMainFrame() && !isFinishing()) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("Connection problem")
                        .setMessage("Could not load YouTube. Check your connection.")
                        .setPositiveButton("Retry", (d, w) -> mWeb.reload())
                        .setNegativeButton("Close", (d, w) -> finish())
                        .show();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                WebResourceResponse errorResponse) {
            if (request.isForMainFrame()) {
                ErrLog.log("page", "http " + errorResponse.getStatusCode()
                        + " " + request.getUrl());
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectTweaks();
            injectAdSkipper();
            injectGear();
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
            if (msg.messageLevel() != android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                return false;
            }
            String m = msg.message();
            String low = m.toLowerCase(Locale.US);
            for (String p : AD_PATTERNS) {
                if (low.contains(p)) {
                    // Expected noise from blocked ad requests (CORS, ERR_BLOCKED).
                    return false;
                }
            }
            if ((low.contains("cors") || low.contains("err_blocked"))
                    && (low.contains("doubleclick") || low.contains("pagead")
                            || low.contains("adservice"))) {
                return false;
            }
            ErrLog.log("js", m + " @" + msg.sourceId() + ":" + msg.lineNumber());
            return false;
        }

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

    // --- Gear button injected into the YouTube header, next to the logo ---

    private void injectGear() {
        String js = "(function(){"
                + "if(window.__tubifyGear)return;window.__tubifyGear=1;"
                + "var ID='tubify-gear';"
                + "var st=document.createElement('style');st.id='tubify-gear-css';"
                + "st.textContent='#tubify-gear{display:inline-flex;align-items:center;"
                + "justify-content:center;width:36px;height:36px;margin-left:8px;padding:0;"
                + "border:none;border-radius:50%;background:rgba(40,40,40,0.9);color:#fff;"
                + "font-size:20px;line-height:1;vertical-align:middle;flex:0 0 auto;"
                + "-webkit-tap-highlight-color:transparent}"
                + "#tubify-gear:active{background:rgba(90,90,90,0.9)}';"
                + "(document.head||document.documentElement).appendChild(st);"
                + "function ins(){"
                + "if(document.getElementById(ID))return;"
                + "var logo=document.querySelector('ytm-topbar-logo-renderer, c3-topbar-logo,"
                + " ytm-mobile-topbar-renderer #logo, a#logo, .mobile-topbar-logo');"
                + "if(!logo||!logo.parentNode)return;"
                + "var b=document.createElement('button');b.id=ID;b.type='button';"
                + "b.setAttribute('aria-label','Tubify Lite settings');"
                + "b.textContent='\u2699';"
                + "b.addEventListener('click',function(ev){"
                + "ev.preventDefault();ev.stopPropagation();"
                + "try{Tubify.openSettings();}catch(e){}});"
                + "logo.parentNode.insertBefore(b,logo.nextSibling);}"
                + "ins();"
                + "new MutationObserver(function(){"
                + "if(!document.getElementById(ID))ins();"
                + "}).observe(document.documentElement,{childList:true,subtree:true});"
                + "})();";
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

    // --- Settings sheet (dark, rounded cards, switches) --------------------

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
        title.setText("Tubify Lite settings");
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
        sheet.addView(settingRow("Ad blocking", "Blocks ad requests, skips video ads",
                mPrefs.getBoolean("adblock", true), (checked) ->
                    mPrefs.edit().putBoolean("adblock", checked).apply()));

        sheet.addView(sectionLabel("INTERFACE"));
        sheet.addView(settingRow("Hide Shorts", null,
                mPrefs.getBoolean("shorts", true),
                (checked) -> setTweak("shorts", checked)));
        sheet.addView(settingRow("Hide suggestions", null,
                mPrefs.getBoolean("suggestions", true),
                (checked) -> setTweak("suggestions", checked)));
        sheet.addView(settingRow("Hide comments", null,
                mPrefs.getBoolean("comments", false),
                (checked) -> setTweak("comments", checked)));

        sheet.addView(sectionLabel("DEVELOPER"));
        TextView errRow = new TextView(this);
        errRow.setText("Error log");
        errRow.setTextColor(Color.WHITE);
        errRow.setTextSize(16);
        GradientDrawable errBg = new GradientDrawable();
        errBg.setColor(0xFF2A2A2A);
        errBg.setCornerRadius(dp(14));
        errRow.setBackground(errBg);
        errRow.setPadding(dp(16), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = dp(8);
        sheet.addView(errRow, elp);
        errRow.setOnClickListener(v -> {
            dialog.dismiss();
            showErrorLog();
        });

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

    private void showErrorLog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(0xFF1B1B1B);
        boxBg.setCornerRadius(dp(20));
        box.setBackground(boxBg);
        box.setPadding(dp(16), dp(14), dp(16), dp(8));

        TextView title = new TextView(this);
        title.setText("Error log");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(ErrLog.read());
        body.setTextColor(0xFFCCCCCC);
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
                    "Tubify Lite error log", ErrLog.read()));
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
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.7f);
            w.setLayout(width, height);
        }
        dialog.show();
    }

    private TextView logButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFFFF4444);
        b.setTextSize(14);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setPadding(dp(14), dp(10), dp(6), dp(4));
        return b;
    }

    private void setTweak(String key, boolean checked) {
        mPrefs.edit().putBoolean(key, checked).apply();
        injectTweaks();
        injectAdSkipper();
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

    private View settingRow(String title, String subtitle,
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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mWeb.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Persist cookies (sign-in) without pausing playback: background
        // audio keeps playing, matching the full Tubify app.
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (mWeb != null) {
            ViewGroup parent = (ViewGroup) mWeb.getParent();
            if (parent != null) {
                parent.removeView(mWeb);
            }
            mWeb.destroy();
        }
        super.onDestroy();
    }
}
