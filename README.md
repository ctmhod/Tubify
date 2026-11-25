# Tubify

Tubify is a minimal YouTube app for Android built on the GeckoView engine
(the engine behind Firefox). It shows YouTube fullscreen like the installed
app, with no browser UI, and adds content control through real WebExtensions.

## Features

- Fullscreen, PWA-like YouTube (m.youtube.com), no URL bar, no tabs.
- Ad blocking powered by uBlock Origin, installed from addons.mozilla.org
  on first run. Toggle: gear icon, "Ad blocking (uBlock Origin)".
- Bundled "Tubify Tweaks" extension with toggles in the gear menu:
  hide Shorts, hide suggestions, hide comments.
- Video fullscreen with rotation; background playback kept alive where
  the system allows it.
- English only, small codebase (Java + GeckoView, no other libraries).

## Build

GitHub Actions builds signed release APKs for arm64-v8a and armeabi-v7a
(`.github/workflows/release.yml`). Releases are published to GitHub
Releases only.

## Notes

Tubify is an independent client. It is not affiliated with YouTube or
Google. uBlock Origin is developed by Raymond Hill (GPL-3.0) and is
installed from Mozilla's add-on store, not bundled.
