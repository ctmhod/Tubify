"use strict";

// Bridge between the Tubify app and this extension: the app sends the
// current tweak toggles, we persist them for the content script.
function relay(msg) {
  browser.storage.local.set({ settings: msg });
}

try {
  const port = browser.runtime.connectNative("browser");
  port.onMessage.addListener(relay);
} catch (e) {
  // Native side not ready yet; the app re-sends on the next connect.
}
