"use strict";

// Tubify Tweaks background script.
// Applies tweak CSS with browser.tabs.insertCSS (user-origin stylesheets are
// not affected by the page's Content-Security-Policy, unlike <style> nodes),
// keeps settings in storage, and receives live updates from the Android app
// over the connectNative("browser") port.

const DEFAULTS = { shorts: true, suggestions: true, comments: false };

// Styles for the Tubify gear button that the content script injects into the
// YouTube header next to the logo.
const GEAR_CSS =
  "#tubify-gear{display:inline-flex;align-items:center;justify-content:center;" +
  "width:36px;height:36px;margin-left:8px;padding:0;border:none;border-radius:50%;" +
  "background:rgba(40,40,40,0.9);color:#fff;font-size:20px;line-height:1;" +
  "vertical-align:middle;flex:0 0 auto;-webkit-tap-highlight-color:transparent;}" +
  "#tubify-gear:active{background:rgba(90,90,90,0.9);}";

const CSS = {
  shorts: [
    'a[href^="/shorts"]',
    "ytm-reel-shelf-renderer",
    "ytm-rich-shelf-renderer",
    "ytm-shorts-lockup-view-model",
    "ytm-shorts-lockup-view-model-v2",
    "ytm-select-with-timeline-renderer",
    "ytm-pivot-bar-item-renderer:has(.pivot-shorts)",
    "ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model)",
    'ytm-video-with-context-renderer:has(a[href^="/shorts"])',
    "ytd-reel-shelf-renderer",
    "ytd-rich-shelf-renderer",
    "ytd-reel-video-renderer",
    'ytd-rich-item-renderer:has(a[href^="/shorts"])'
  ].join(", ") + " { display: none !important; }",
  suggestions: [
    "ytm-watch-next-secondary-results-renderer",
    "ytm-related-chip-cloud-renderer",
    'ytm-item-section-renderer[section-identifier="related-items"]',
    'ytm-item-section-renderer[section-identifier="watch-next-feed"]',
    "ytd-watch-next-secondary-results-renderer",
    "#related"
  ].join(", ") + " { display: none !important; }",
  comments: [
    "ytm-comment-section-renderer",
    "ytm-comments-entry-point-header-renderer",
    "ytd-comments",
    "#comments"
  ].join(", ") + " { display: none !important; }"
};

let settings = Object.assign({}, DEFAULTS);
// Per-tab last injected code, so removeCSS gets the exact string back.
const applied = new Map();

function cssFor(s) {
  let css = "";
  for (const key of Object.keys(CSS)) {
    if (s[key]) css += CSS[key] + "\n";
  }
  return css + GEAR_CSS;
}

function isYouTube(url) {
  return /^https?:\/\/(m\.|www\.)?youtube\.com\//.test(url || "");
}

function applyToTab(tabId) {
  const next = cssFor(settings);
  const prev = applied.get(tabId);
  if (prev === next) return;
  if (prev) {
    browser.tabs.removeCSS(tabId, { code: prev, cssOrigin: "user" }).catch(() => {});
  }
  if (next) {
    browser.tabs
      .insertCSS(tabId, { code: next, cssOrigin: "user", runAt: "document_start" })
      .then(() => applied.set(tabId, next))
      .catch(() => {});
  } else {
    applied.delete(tabId);
  }
}

function applyToAllYouTubeTabs() {
  browser.tabs.query({ url: ["*://m.youtube.com/*", "*://www.youtube.com/*", "*://youtube.com/*"] })
    .then((tabs) => {
      for (const t of tabs) applyToTab(t.id);
    })
    .catch(() => {});
}

browser.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.status === "loading" && isYouTube(tab.url)) {
    applied.delete(tabId);
    applyToTab(tabId);
  }
});
browser.tabs.onRemoved.addListener((tabId) => applied.delete(tabId));

browser.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.settings) {
    settings = Object.assign({}, DEFAULTS, changes.settings.newValue || {});
    applyToAllYouTubeTabs();
  }
});

browser.storage.local.get("settings").then((res) => {
  settings = Object.assign({}, DEFAULTS, res.settings || {});
  applyToAllYouTubeTabs();
});

// Live settings bridge from the Android app. The app registers its native
// message delegate asynchronously after installing this extension, so the
// first connectNative call can land before the delegate exists and get a dead
// port. Reconnect until the port stays up, and reconnect after any disconnect.
function relay(msg) {
  if (msg && typeof msg === "object") {
    browser.storage.local.set({ settings: msg });
  }
}
let nativePort = null;
function connectApp() {
  let p;
  try {
    p = browser.runtime.connectNative("browser");
  } catch (e) {
    nativePort = null;
    setTimeout(connectApp, 2000);
    return;
  }
  nativePort = p;
  p.onMessage.addListener(relay);
  p.onDisconnect.addListener(() => {
    if (nativePort === p) nativePort = null;
    setTimeout(connectApp, 2000);
  });
  // Tell the app the extension side of the bridge is alive.
  try {
    p.postMessage({ type: "tweaks-ready", version: browser.runtime.getManifest().version });
  } catch (e) {}
}
connectApp();

// Content script asks to be (re)styled as early as possible, and forwards
// gear-button taps to the Android app so it can open the settings sheet.
browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === "tubify-apply" && sender.tab) {
    applied.delete(sender.tab.id);
    applyToTab(sender.tab.id);
  }
  if (msg && msg.type === "tubify-open-settings" && nativePort) {
    try {
      nativePort.postMessage({ type: "open-settings" });
    } catch (e) {}
  }
});
