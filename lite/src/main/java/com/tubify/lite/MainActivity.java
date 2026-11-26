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
    // Tubify design tokens (Material 3 dark), colors from the launcher icon.
    private static final int COL_BASE = 0xFF07080D;
    private static final int COL_CARD = 0xFF12141C;
    private static final int COL_BORDER = 0xFF20232E;
    private static final int COL_TEXT = 0xFFE8EAF0;
    private static final int COL_TEXT2 = 0xFF8A90A0;
    private static final int COL_ACCENT = 0xFFFF4444;


    private static final String HOME = "https://m.youtube.com";

    // Network-level ad blocking: substrings matched against every request URL.
    private static final String[] AD_PATTERNS = {
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.",
            "ads.youtube.com",
            "youtube.com/api/stats/ads",
            "youtube.com/pagead/",
            "youtube.com/ptracking",
            "youtube.com/get_midroll",
            "youtube.com/ad_break",
            "youtube.com/ytcs/ad",
            "youtube.com/ads/",
            "googletagservices.com",
            "google.com/pagead"
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
        logPreviousExits();
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
            if (request.getUrl() != null && request.getUrl().getHost() != null
                    && request.getUrl().getHost().endsWith("googlevideo.com")) {
                // Media streams: never intercept (range requests, CORS, playback).
                return super.shouldInterceptRequest(view, request);
            }
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
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            if (scheme.equals("intent") || scheme.equals("youtube")
                    || scheme.equals("vnd.youtube") || scheme.equals("market")) {
                // Never fire app-launch intents.
                return true;
            }
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
        public boolean onRenderProcessGone(WebView view,
                android.webkit.RenderProcessGoneDetail detail) {
            ErrLog.log("web", "render process gone, didCrash=" + detail.didCrash());
            view.destroy();
            recreate();
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                android.webkit.WebResourceError error) {
            if (error != null && request.getUrl() != null) {
                String u = request.getUrl().toString().toLowerCase(Locale.US);
                boolean adNoise = false;
                for (String p : AD_PATTERNS) {
                    if (u.contains(p)) { adNoise = true; break; }
                }
                if (!adNoise) {
                    String tag = u.contains("googlevideo.com") ? "media" : "page";
                    ErrLog.log(tag, "load error " + error.getErrorCode() + " "
                            + error.getDescription() + " " + request.getUrl());
                }
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
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            injectAdCleaner();
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectAdCleaner();
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
                "a[href^=\"/shorts\"],a[href*=\"youtube.com/shorts\"],"
                + "ytm-reel-shelf-renderer,ytm-rich-shelf-renderer,"
                + "ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,"
                + "ytm-select-with-timeline-renderer,"
                + "ytd-reel-shelf-renderer,ytd-rich-shelf-renderer,ytd-reel-video-renderer"
                + "{display:none!important}");;
        if (suggestions) css.append(
                "ytm-watch-next-secondary-results-renderer,ytm-related-chip-cloud-renderer,"
                + "ytm-item-section-renderer[section-identifier=\"related-items\"],"
                + "ytm-item-section-renderer[section-identifier=\"watch-next-feed\"],"
                + "ytd-watch-next-secondary-results-renderer,#related{display:none!important}");
        if (mPrefs.getBoolean("home", false)) css.append(
                ".tubify-home ytm-rich-grid-renderer,.tubify-home ytm-rich-section-renderer,"
                + ".tubify-home ytm-single-column-browse-results-renderer{display:none!important}");
        if (comments) css.append(
                "ytm-comment-section-renderer,ytm-comments-entry-point-header-renderer,"
                + "ytm-item-section-renderer[section-identifier=\"comments-entry-point\"],"
                + "ytm-engagement-panel-section-list-renderer[target-id*=\"comments-section\"],"
                + "ytd-comments,#comments{display:none!important}");
        // Cosmetic ad hiding (only while ad blocking is on) and the always-on
        // removal of YouTube's "Open App" prompts.
        if (mPrefs.getBoolean("adblock", true)) {
            css.append("ytm-ad-slot-renderer,ytm-promoted-sparkles-web-renderer,"
                    + "ytm-in-feed-ad-layout-renderer,ytm-promoted-video-renderer,"
                    + "ytm-compact-promoted-video-renderer,ytm-display-ad-renderer,"
                    + ".video-ads,.ytp-ad-module,.ytp-ad-overlay-container,"
                    + ".ytp-ad-message-overlay,#player-ads,'
                    + "ad-slot-renderer,lazy-list>ad-slot-renderer,"
                    + "ytm-rich-item-renderer>ad-slot-renderer,"
                    + "ytm-companion-slot,ytm-companion-ad-renderer{display:none!important}");
        }
        css.append("a[href^=\"vnd.youtube:\"],a[href^=\"intent:\"],a[href^=\"youtube://\"],"
                + "ytm-watch-metadata-app-promo-renderer,ytm-open-app-button-renderer,"
                + "#open-app-button{display:none!important}");
        String js = "(function(){var e=document.getElementById('tubify-tweaks');"
                + "if(!e){e=document.createElement('style');e.id='tubify-tweaks';"
                + "(document.head||document.documentElement).appendChild(e);}"
                + "e.textContent=" + jsString(css.toString()) + ";})();";
        // Structural Shorts hider: CSS :has() is unsupported on WebView < 105,
        // so hide via JS instead (works on every WebView). Cleanup runs first so
        // turning the toggle off restores the elements.
        js += "(function(){document.querySelectorAll('[data-tubify-shorts]').forEach("
                + "function(el){el.style.display='';el.removeAttribute('data-tubify-shorts');});})();";
        if (shorts) js += "(function(){var kill=function(){var p=0,n=0;"
                + "document.querySelectorAll('ytm-pivot-bar-item-renderer').forEach(function(el){"
                + "if(el.querySelector('a[href*=\"/shorts\"],.pivot-shorts')){el.style.display='none';"
                + "el.setAttribute('data-tubify-shorts','1');p++;}});"
                + "document.querySelectorAll('ytm-tab-renderer,ytd-tab-renderer,ytm-video-with-context-renderer,"
                + "ytd-video-renderer,ytm-rich-item-renderer,ytd-rich-item-renderer,ytm-rich-section-renderer')"
                + ".forEach(function(el){if(el.querySelector('a[href*=\"/shorts\"],ytm-shorts-lockup-view-model,"
                + "ytm-shorts-lockup-view-model-v2')){el.style.display='none';"
                + "el.setAttribute('data-tubify-shorts','1');n++;}});"
                + "if(p>0&&p!==window.__tubifyP){window.__tubifyP=p;"
                + "console.log('TUBIFY_TWEAK shorts pivot='+p+' items='+n);}};"
                + "var pending=false;var sched=function(){if(pending)return;pending=true;"
                + "setTimeout(function(){pending=false;kill();},300);};"
                + "kill();if(!window.__tubifyMO){window.__tubifyMO=new MutationObserver(sched);"
                + "window.__tubifyMO.observe(document.documentElement,{childList:true,subtree:true});}})();";
        mWeb.evaluateJavascript(js, null);
    }

    // --- Gear button injected into the YouTube header, next to the logo ---

    private void injectGear() {
        String js = "(function(){"
                + "if(window.__tubifyGear)return;window.__tubifyGear=1;"
                + "var ID='tubify-gear';"
                + "var st=document.createElement('style');st.id='tubify-gear-css';"
                + "st.textContent='#tubify-gear{display:inline-flex;align-items:center;"
                + "justify-content:center;width:36px;height:36px;margin-left:-10px;padding:0;"
                + "border:1px solid #20232E;border-radius:50%;background:#12141C;color:#E8EAF0;"
                + "font-size:20px;line-height:1;vertical-align:middle;flex:0 0 auto;"
                + "-webkit-tap-highlight-color:transparent}"
                + "#tubify-gear:active{background:#20232E}';"
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
                + "var LP=null;"
                + "function pc(){var p=location.pathname;if(p===LP)return;LP=p;"
                + "document.documentElement.classList.toggle('tubify-home',p==='/');}"
                + "pc();"
                + "new MutationObserver(function(){"
                + "if(!document.getElementById(ID))ins();pc();"
                + "}).observe(document.documentElement,{childList:true,subtree:true});"
                + "})();";
        mWeb.evaluateJavascript(js, null);
    }

    // --- Ad skipper: mute + fast-forward skippable/unskippable video ads ---

    private void injectAdCleaner() {
        if (!mPrefs.getBoolean("adblock", true)) return;
        // Strips ad payloads from YouTube data before page scripts see it
        // (uBlock's approach): adPlacements/adSlots -> no_ads in XHR/fetch
        // text, playerAds/adPlacements/adSlots/adBreakHeartbeatParams deleted
        // from parsed JSON and ytInitialPlayerResponse. ES5-compatible so it
        // runs on any WebView. Limit: server-stitched (SABR) ads baked into
        // the video stream cannot be removed in a WebView.
        String js = "(function(){if(window.__tubifyClean)return;window.__tubifyClean=1;"
                + "var KEYS=['playerAds','adPlacements','adSlots','adBreakHeartbeatParams'];"
                + "var Q=String.fromCharCode(34);"
                + "function strip(o,d){if(!o||typeof o!=='object'||d>4)return o;"
                + "for(var i=0;i<KEYS.length;i++){if(o[KEYS[i]]!==undefined){"
                + "try{delete o[KEYS[i]];}catch(e){o[KEYS[i]]=undefined;}}}"
                + "if(d<4){for(var k in o){if(o.hasOwnProperty(k)&&o[k]&&typeof o[k]==='object'){strip(o[k],d+1);}}}"
                + "return o;}"
                + "function cleanText(t){if(typeof t!=='string')return t;"
                + "if(t.indexOf('adPlacements')<0&&t.indexOf('adSlots')<0)return t;"
                + "return t.split(Q+'adPlacements'+Q).join(Q+'no_ads'+Q)"
                + ".split(Q+'adSlots'+Q).join(Q+'no_ads'+Q);}"
                + "try{var O=window.XMLHttpRequest.prototype;"
                + "var gt=Object.getOwnPropertyDescriptor(O,'responseText').get;"
                + "Object.defineProperty(O,'responseText',{configurable:true,"
                + "get:function(){return cleanText(gt.call(this));}});"
                + "var gr=Object.getOwnPropertyDescriptor(O,'response').get;"
                + "Object.defineProperty(O,'response',{configurable:true,"
                + "get:function(){var r=gr.call(this);return typeof r==='string'?cleanText(r):r;}});"
                + "}catch(e){}"
                + "try{if(window.Response){var t=window.Response.prototype.text;"
                + "window.Response.prototype.text=function(){return t.call(this).then(cleanText);};"
                + "var j=window.Response.prototype.json;"
                + "window.Response.prototype.json=function(){return j.call(this).then(function(o){return strip(o,0);});};}"
                + "}catch(e){}"
                + "try{var JP=JSON.parse;JSON.parse=function(s){var o=JP.apply(this,arguments);"
                + "if(typeof s==='string'&&(s.indexOf('playerAds')>=0||s.indexOf('adPlacements')>=0"
                + "||s.indexOf('adSlots')>=0||s.indexOf('adBreakHeartbeatParams')>=0)){strip(o,0);}"
                + "return o;};}catch(e){}"
                + "try{var ypr;Object.defineProperty(window,'ytInitialPlayerResponse',"
                + "{configurable:true,get:function(){return ypr;},set:function(v){ypr=strip(v,0);}});}catch(e){}"
                + "console.log('TUBIFY_TWEAK adclean on');})();";
        mWeb.evaluateJavascript(js, null);
    }

    private void injectAdSkipper() {
        if (!mPrefs.getBoolean("adblock", true)) return;
        // Reacts fast to ad pods: mute + fast-forward the ad video, click any
        // skip/close control. Limit: the ad may play muted for a fraction of a
        // second before the timer catches it; streamed (SABR) ads baked into
        // the media cannot be removed by a WebView at all.
        String js = "(function(){if(window.__tubifySkip)return;window.__tubifySkip=1;"
                + "setInterval(function(){try{"
                + "var ad=document.querySelector('.ad-showing,.ad-interrupting');"
                + "var v=document.querySelector('video');"
                + "if(ad&&v){v.muted=true;if(isFinite(v.duration)&&v.duration>0){"
                + "v.currentTime=v.duration;}v.playbackRate=16;}"
                + "var b=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,"
                + ".ytp-ad-skip-button-modern,.ytp-ad-skip-button-slot button,"
                + "button.ytp-ad-overlay-close-button');"
                + "if(b){b.click();}"
                + "var ov=document.querySelector('.ytp-ad-overlay-close-button');"
                + "if(ov){ov.click();}"
                + "}catch(e){}},350);})();";
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
        title.setText("Tubify Lite settings");
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
        content.addView(settingRow("Ad blocking", "Blocks ad requests, skips video ads",
                mPrefs.getBoolean("adblock", true), (checked) ->
                    mPrefs.edit().putBoolean("adblock", checked).apply()));

        content.addView(sectionLabel("INTERFACE"));
        content.addView(settingRow("Hide Shorts", null,
                mPrefs.getBoolean("shorts", true),
                (checked) -> setTweak("shorts", checked)));
        content.addView(settingRow("Hide suggestions", null,
                mPrefs.getBoolean("suggestions", true),
                (checked) -> setTweak("suggestions", checked)));
        content.addView(settingRow("Hide comments", null,
                mPrefs.getBoolean("comments", false),
                (checked) -> setTweak("comments", checked)));
        content.addView(settingRow("Hide home feed", "Start on a blank home",
                mPrefs.getBoolean("home", false),
                (checked) -> setTweak("home", checked)));

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

    private void setTweak(String key, boolean checked) {
        mPrefs.edit().putBoolean(key, checked).apply();
        injectTweaks();
        injectAdSkipper();
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

    private View settingRow(String title, String subtitle,
            boolean checked, ToggleHandler handler) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable rowBg = new GradientDrawable();
        rowBg.setColor(COL_CARD);
        rowBg.setCornerRadius(dp(16));
        rowBg.setStroke(dp(1), COL_BORDER);
        row.setBackground(rowBg);
        row.setPadding(dp(16), dp(10), dp(12), dp(10));

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
                new int[]{Color.WHITE, COL_TEXT2}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{COL_ACCENT, COL_BORDER}));
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
        w.setStatusBarColor(COL_BASE);
        w.setNavigationBarColor(COL_BASE);
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
