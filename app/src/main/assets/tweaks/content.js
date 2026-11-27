"use strict";

// Runs at document_start on YouTube pages: asks the background script to
// (re)inject the tweak CSS into this tab, and mirrors the same CSS in a
// <style> element as a fallback. SPA navigations keep the document, so the
// injected stylesheet survives in-page navigation. Also injects the Tubify
// gear button into the YouTube header, right after the logo.

const DEFAULTS = { shorts: false, suggestions: false, comments: false, home: false, sponsors: true, bgplay: false };

const GEAR_CSS =
  "#tubify-gear{display:inline-flex;align-items:center;justify-content:center;" +
  "width:28px;height:28px;margin-left:-5px;margin-right:5px;padding:0;border:1px solid #20232E;border-radius:50%;" +
  "background:#12141C;color:#E8EAF0;font-size:16px;line-height:1;" +
  "vertical-align:middle;flex:0 0 auto;-webkit-tap-highlight-color:transparent;}" +
  "html.tubify-wide #tubify-gear{margin-left:1px;margin-right:0;}" +
  "#tubify-gear:active{background:#20232E;}";

// Always-on: remove YouTube's "Open App" prompts (button in the top bar,
// watch-metadata app promo, open-app bottom sheets, deep-link anchors).
const OPENAPP_CSS =
  'a[href^="vnd.youtube:"], a[href^="intent:"], a[href^="youtube://"],' +
  "ytm-watch-metadata-app-promo-renderer, ytm-open-app-button-renderer, " +
  '#open-app-button, ' +
  '.ytm-bottom-sheet-overlay-renderer-container:has(' +
  '.ytm-bottom-sheet-overlay-renderer-header[style*="static/open-app"])' +
  " { display: none !important; }" +
  // YouTube logo about 10% larger via transform, so the gear keeps its size.
  "ytm-home-logo svg, ytm-home-logo img, ytm-topbar-logo-renderer svg" +
  " { transform: scale(1.1); transform-origin: left center; margin-right: 8px; }";

const CSS = {
  shorts: [
    'a[href^="/shorts"]',
    'a[href*="youtube.com/shorts"]',
    "ytm-reel-shelf-renderer",
    "ytm-shorts-lockup-view-model",
    "ytm-shorts-lockup-view-model-v2",
    "ytd-reel-shelf-renderer",
    "ytd-reel-video-renderer"
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
    "comments-entry-point-teaser-view-model",
    "yt-video-metadata-carousel-view-model",
    "yt-comment-teaser-carousel-item-view-model",
    '[section-identifier*="comment"]',
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
let wideTimer = 0;
function updateWide() {
  wideTimer = 0;
  const s = document.querySelector("ytm-topbar-logo-renderer svg, c3-topbar-logo svg, ytm-home-logo svg");
  if (!s) return;
  document.documentElement.classList.toggle("tubify-wide", s.getBoundingClientRect().width > 60);
}

function updatePathClass() {
  if (!wideTimer) wideTimer = setTimeout(updateWide, 150);
  const path = location.pathname;
  if (path === lastPath) return;
  lastPath = path;
  try { browser.runtime.sendMessage({ type: "tubify-path", path }); } catch (e) {}
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


// --- Structural Shorts hider (same logic as Tubify Lite) ---
const SHORTS_SEL = 'a[href*="/shorts"],ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2';
function hideEl(el) { el.style.display = "none"; el.setAttribute("data-tubify-shorts", "1"); }
function killShorts() {
  if (!lastSettings || !lastSettings.shorts) {
    document.querySelectorAll("[data-tubify-shorts]").forEach((el) => {
      el.style.display = "";
      el.removeAttribute("data-tubify-shorts");
    });
    return;
  }
  document.querySelectorAll("ytm-pivot-bar-item-renderer").forEach((el) => {
    if (el.querySelector('a[href*="/shorts"],.pivot-shorts')) hideEl(el);
  });
  // Only single video cells, never feed containers.
  document.querySelectorAll(
    "ytm-video-with-context-renderer,ytd-video-renderer,ytm-rich-item-renderer," +
    "ytd-rich-item-renderer,ytm-compact-video-renderer").forEach((el) => {
    if (el.querySelector(SHORTS_SEL)) hideEl(el);
  });
  // Shelves are hidden only when titled Shorts.
  document.querySelectorAll(
    "ytm-rich-section-renderer,ytm-item-section-renderer,ytm-shelf-renderer,ytm-rich-shelf-renderer").forEach((el) => {
    const h = el.querySelector("h2,.shelf-title,.rich-shelf-title,ytm-shelf-header-renderer");
    if (h && /^\s*shorts\s*$/i.test(h.textContent || "")) hideEl(el);
  });
}
let killPending = false;
new MutationObserver(() => {
  if (killPending) return;
  killPending = true;
  setTimeout(() => { killPending = false; killShorts(); }, 300);
}).observe(document.documentElement, { childList: true, subtree: true });
setTimeout(killShorts, 1500);

// --- Built-in sponsor skipping (SponsorBlock data, CC BY-NC-SA 4.0) ---
// Segments are fetched by the background script. No SponsorBlock UI is shown.
let sbCur = null, sbSegs = [], sbLast = -1;
function sbVideoId() {
  const m = location.search.match(/[?&]v=([\w-]{11})/);
  return (location.pathname === "/watch" && m) ? m[1] : null;
}
setInterval(() => {
  try {
    if (!lastSettings || !lastSettings.sponsors) return;
    const id = sbVideoId();
    if (id !== sbCur) {
      sbCur = id; sbSegs = []; sbLast = -1;
      if (id) {
        browser.runtime.sendMessage({ type: "tubify-sb", id }).then((segs) => {
          if (sbCur === id && Array.isArray(segs)) sbSegs = segs;
        }).catch(() => {});
      }
    }
    const v = document.querySelector("video");
    if (!v || !sbSegs.length || v.paused) return;
    const t = v.currentTime;
    for (let i = 0; i < sbSegs.length; i++) {
      if (t >= sbSegs[i][0] && t < sbSegs[i][1] - 0.4 && sbLast !== i) {
        sbLast = i;
        v.currentTime = sbSegs[i][1];
        break;
      }
    }
  } catch (e) {}
}, 500);


// --- Background playback: keep the page "visible" so YouTube does not pause ---
let bgOn = false;
function bgSync() { bgOn = !!(lastSettings && lastSettings.bgplay); }
try {
  const pd = document.wrappedJSObject;
  const realVs = Object.getOwnPropertyDescriptor(Document.prototype, "visibilityState");
  const getHidden = exportFunction(function () { return bgOn ? false : (realVs ? realVs.get.call(pd) === "hidden" : false); }, window);
  const getVis = exportFunction(function () { return bgOn ? "visible" : (realVs ? realVs.get.call(pd) : "visible"); }, window);
  Object.defineProperty(pd, "hidden", { get: getHidden, configurable: true });
  Object.defineProperty(pd, "visibilityState", { get: getVis, configurable: true });
} catch (e) {}
["visibilitychange", "webkitvisibilitychange"].forEach((n) => {
  document.addEventListener(n, (e) => { if (bgOn) e.stopImmediatePropagation(); }, true);
});
let bgSeen = "";
setInterval(() => {
  bgSync();
  try {
    const real = document.visibilityState;
    if (real !== bgSeen) {
      bgSeen = real;
      browser.runtime.sendMessage({ type: "tubify-log", msg: "bg real=" + real + " pageSees=" + document.wrappedJSObject.visibilityState + " on=" + bgOn });
    }
  } catch (e) {}
}, 1000);
