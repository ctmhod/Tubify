"use strict";

const DEFAULTS = { shorts: true, suggestions: true, comments: false };

const CSS = {
  shorts: [
    'a[href^="/shorts"]',
    'ytm-reel-shelf-renderer',
    'ytm-rich-shelf-renderer',
    'ytm-shorts-lockup-view-model',
    'ytm-shorts-lockup-view-model-v2',
    'ytm-video-with-context-renderer:has(a[href^="/shorts"])',
    'ytd-reel-shelf-renderer',
    'ytd-rich-shelf-renderer',
    'ytd-reel-video-renderer',
    'ytd-rich-item-renderer:has(a[href^="/shorts"])'
  ].join(", ") + " { display: none !important; }",
  suggestions: [
    'ytm-watch-next-secondary-results-renderer',
    'ytm-related-chip-cloud-renderer',
    'ytm-item-section-renderer[section-identifier="related-items"]',
    'ytd-watch-next-secondary-results-renderer',
    '#related'
  ].join(", ") + " { display: none !important; }",
  comments: [
    'ytm-comment-section-renderer',
    'ytd-comments',
    '#comments'
  ].join(", ") + " { display: none !important; }"
};

let styleEl = null;

function apply(settings) {
  let css = "";
  for (const key of Object.keys(CSS)) {
    if (settings[key]) {
      css += CSS[key] + "\n";
    }
  }
  if (!styleEl) {
    styleEl = document.createElement("style");
    styleEl.id = "tubify-tweaks";
    (document.head || document.documentElement).appendChild(styleEl);
  }
  if (styleEl.textContent !== css) {
    styleEl.textContent = css;
  }
}

browser.storage.local.get("settings").then((res) => {
  apply(Object.assign({}, DEFAULTS, res.settings || {}));
});

browser.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.settings) {
    apply(Object.assign({}, DEFAULTS, changes.settings.newValue || {}));
  }
});
