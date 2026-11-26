"use strict";

// Runs at document_start on YouTube pages: asks the background script to
// (re)inject the tweak CSS into this tab, and mirrors the same CSS in a
// <style> element as a fallback. SPA navigations keep the document, so the
// injected stylesheet survives in-page navigation. Also injects the Tubify
// gear button into the YouTube header, right after the logo.

const DEFAULTS = { shorts: true, suggestions: true, comments: false, home: false };

const GEAR_CSS =
  "#tubify-gear{display:inline-flex;align-items:center;justify-content:center;" +
  "width:28px;height:28px;margin-left:-10px;padding:0;border:1px solid #20232E;border-radius:50%;" +
  "background:#12141C;color:#E8EAF0;font-size:16px;line-height:1;" +
  "vertical-align:middle;flex:0 0 auto;-webkit-tap-highlight-color:transparent;}" +
  "#tubify-gear:active{background:#20232E;}";

// Always-on: remove YouTube's "Open App" prompts (button in the top bar,
// watch-metadata app promo, open-app bottom sheets, deep-link anchors).
const OPENAPP_CSS =
  'a[href^="vnd.youtube:"], a[href^="intent:"], a[href^="youtube://"],' +
  "ytm-watch-metadata-app-promo-renderer, ytm-open-app-button-renderer, " +
  '#open-app-button, ' +
  '.ytm-bottom-sheet-overlay-renderer-container:has(' +
  '.ytm-bottom-sheet-overlay-renderer-header[style*="static/open-app"])' +
  " { display: none !important; }";

const CSS = {
  shorts: [
    'a[href^="/shorts"]',
    'a[href*="youtube.com/shorts"]',
    'ytm-pivot-bar-item-renderer:has(a[href*="/shorts"])',
    'ytm-pivot-bar-item-renderer:has(.pivot-shorts)',
    "ytm-reel-shelf-renderer",
    "ytm-rich-shelf-renderer",
    "ytm-shorts-lockup-view-model",
    "ytm-shorts-lockup-view-model-v2",
    "ytm-select-with-timeline-renderer",
    "ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model)",
    "ytm-rich-item-renderer:has(ytm-shorts-lockup-view-model)",
    'ytm-video-with-context-renderer:has(a[href*="/shorts"])',
    'ytm-tab-renderer:has(a[href*="/shorts"])',
    "ytd-reel-shelf-renderer",
    "ytd-rich-shelf-renderer",
    "ytd-reel-video-renderer",
    'ytd-rich-item-renderer:has(a[href*="/shorts"])',
    'ytd-tab-renderer:has(a[href*="/shorts"])'
  ].join(", ") + " { display: none !important; }",
  suggestions: [
    "ytm-watch-next-secondary-results-renderer",
    "ytm-related-chip-cloud-renderer",
    'ytm-item-section-renderer[section-identifier="related-items"]',
    'ytm-item-section-renderer[section-identifier="watch-next-feed"]',
    "ytd-watch-next-secondary-results-renderer",
    "#related"
  ].join(", ") + " { display: none !important; }",
  home: [
    ".tubify-home ytm-rich-grid-renderer",
    ".tubify-home ytm-rich-section-renderer",
    ".tubify-home ytm-single-column-browse-results-renderer",
    "ytd-browse[page-subtype='home'] ytd-rich-grid-renderer"
  ].join(", ") + " { display: none !important; }",
  comments: [
    "ytm-comment-section-renderer",
    "ytm-comments-entry-point-header-renderer",
    'ytm-item-section-renderer[section-identifier="comments-entry-point"]',
    'ytm-engagement-panel-section-list-renderer[target-id*="comments-section"]',
    "ytd-comments",
    "#comments"
  ].join(", ") + " { display: none !important; }"
};

let styleEl = null;
let lastSettings = null;
let lastPath = null;

// Page-scoped marker class: some tweaks (home feed) must only apply on "/".
function updatePathClass() {
  const path = location.pathname;
  if (path === lastPath) return;
  lastPath = path;
  document.documentElement.classList.toggle("tubify-home", path === "/");
  if (lastSettings) apply(lastSettings);
}

function apply(settings) {
  let css = "";
  for (const key of Object.keys(CSS)) {
    if (settings[key]) css += CSS[key] + "\n";
  }
  css += OPENAPP_CSS + "\n" + GEAR_CSS;
  if (!styleEl || !styleEl.isConnected) {
    styleEl = document.createElement("style");
    styleEl.id = "tubify-tweaks";
    (document.head || document.documentElement).appendChild(styleEl);
  }
  if (styleEl.textContent !== css) styleEl.textContent = css;
}

try {
  browser.runtime.sendMessage({ type: "tubify-apply" });
} catch (e) {}

browser.storage.local.get("settings").then((res) => {
  lastSettings = Object.assign({}, DEFAULTS, res.settings || {});
  apply(lastSettings);
});

browser.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.settings) {
    lastSettings = Object.assign({}, DEFAULTS, changes.settings.newValue || {});
    apply(lastSettings);
  }
});

// Forward this extension's JS errors (not page errors) to the app error log
// via the background script.
window.addEventListener("error", (e) => {
  if (e.filename && e.filename.indexOf("moz-extension://") === 0) {
    try {
      browser.runtime.sendMessage({ type: "tubify-log",
        msg: e.message + " @" + e.filename.split("/").pop() + ":" + e.lineno });
    } catch (err) {}
  }
});
window.addEventListener("unhandledrejection", (e) => {
  try {
    browser.runtime.sendMessage({ type: "tubify-log",
      msg: "promise: " + ((e.reason && e.reason.message) || e.reason) });
  } catch (err) {}
});

// --- Tubify gear button next to the YouTube logo ---

const GEAR_ID = "tubify-gear";
const LOGO_SEL =
  "ytm-topbar-logo-renderer, c3-topbar-logo, " +
  "ytm-mobile-topbar-renderer #logo, a#logo, .mobile-topbar-logo";

function insertGear() {
  if (document.getElementById(GEAR_ID)) return true;
  const logo = document.querySelector(LOGO_SEL);
  if (!logo || !logo.parentNode) return false;
  const btn = document.createElement("button");
  btn.id = GEAR_ID;
  btn.type = "button";
  btn.setAttribute("aria-label", "Tubify settings");
  btn.textContent = "⚙";
  btn.addEventListener("click", (ev) => {
    ev.preventDefault();
    ev.stopPropagation();
    try {
      browser.runtime.sendMessage({ type: "tubify-open-settings" });
    } catch (e) {}
  });
  logo.parentNode.insertBefore(btn, logo.nextSibling);
  return true;
}

// The masthead renders after document_start and YouTube's SPA re-renders it
// on navigation; re-insert the gear whenever it disappears.
// Remove any "Open App" pill the CSS selectors missed (markup changes).
function sweepOpenApp() {
  const els = document.querySelectorAll(
    "ytm-mobile-topbar-renderer a, ytm-mobile-topbar-renderer button, " +
    "ytm-topbar-menu-button-renderer a, ytm-topbar-menu-button-renderer button");
  for (const el of els) {
    const t = (el.textContent || "").trim().toLowerCase();
    if (t === "open app" || t === "open in app" || t === "get the app") {
      el.remove();
    }
  }
}

updatePathClass();
insertGear();
new MutationObserver(() => {
  if (!document.getElementById(GEAR_ID)) insertGear();
  sweepOpenApp();
  updatePathClass();
}).observe(document.documentElement, { childList: true, subtree: true });
