"use strict";

// Tubify Tweaks background script.
// Applies tweak CSS with browser.tabs.insertCSS (user-origin stylesheets are
// not affected by the page's Content-Security-Policy, unlike <style> nodes),
// keeps settings in storage, and receives live updates from the Android app
// over the connectNative("browser") port.

const DEFAULTS = { shorts: true, suggestions: true, comments: false };

const CSS = {
  shorts: [
    'a[href^="/shorts"]',
    "ytm-reel-shelf-renderer",
    "ytm-rich-shelf-renderer",
    "ytm-shorts-lockup-view-model",
    "ytm-shorts-lockup-view-model-v2",
    "ytm-select-with-timeline-renderer",
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
  return css;
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

// Content script asks to be (re)styled as early as possible.
browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.type === "tubify-apply" && sender.tab) {
    applied.delete(sender.tab.id);
    applyToTab(sender.tab.id);
  }
});

// Live settings bridge from the Android app.
function relay(msg) {
  if (msg && typeof msg === "object") {
    browser.storage.local.set({ settings: msg });
  }
}
try {
  const port = browser.runtime.connectNative("browser");
  port.onMessage.addListener(relay);
} catch (e) {
  // App side not connected yet; storage still carries the last settings.
}
