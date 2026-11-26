"use strict";

// Runs at document_start on YouTube pages: asks the background script to
// (re)inject the tweak CSS into this tab, and mirrors the same CSS in a
// <style> element as a fallback. SPA navigations keep the document, so the
// injected stylesheet survives in-page navigation. Also injects the Tubify
// gear button into the YouTube header, right after the logo.

const DEFAULTS = { shorts: true, suggestions: true, comments: false };

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

let styleEl = null;

function apply(settings) {
  let css = "";
  for (const key of Object.keys(CSS)) {
    if (settings[key]) css += CSS[key] + "\n";
  }
  css += GEAR_CSS;
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
  apply(Object.assign({}, DEFAULTS, res.settings || {}));
});

browser.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.settings) {
    apply(Object.assign({}, DEFAULTS, changes.settings.newValue || {}));
  }
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
insertGear();
new MutationObserver(() => {
  if (!document.getElementById(GEAR_ID)) insertGear();
}).observe(document.documentElement, { childList: true, subtree: true });
