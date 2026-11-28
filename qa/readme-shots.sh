#!/bin/bash
# README screenshots. Content rule: Quran recitation only, no music, nothing immodest.
# One screenshot per screen. Status bar is cropped later, before committing.
PKG=$1; APK=$2; OUT=$3; W=$4
mkdir -p "$OUT"
adb install -r "$APK" >/dev/null

set_display(){
  for i in 1 2 3 4 5 6; do
    adb shell wm size 720x1440; adb shell wm density 320; sleep 2
    S=$(adb shell wm size | tail -1)
    case "$S" in *720x1440*) return 0;; esac
    sleep 2
  done
  echo "WARN: wm size did not stick: $S"
}
set_display

shot(){ adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null 2>&1; }
dump(){ adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml "$OUT/cur.xml" >/dev/null 2>&1; }
tap(){ for i in 1 2 3 4 5; do dump; if P=$(python3 "$(dirname "$0")/tap.py" "$OUT/cur.xml" "$1"); then adb shell input tap $P; sleep 1.5; return 0; fi; adb shell input swipe 160 500 160 250 300; sleep 1; done; return 1; }
focused(){ adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -2 | grep -q "$PKG"; }
open_url(){ adb shell am force-stop $PKG; sleep 1; adb shell am start -a android.intent.action.VIEW -d "$2" -p $PKG $3 >/dev/null 2>&1; sleep $W; focused || { adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep $W; }; shot "$1"; }
settings(){ adb shell am force-stop $PKG; sleep 1; adb shell am start -n $PKG/.MainActivity -e tubify_theme dark --ez tubify_open_settings true $2 >/dev/null; sleep $W; focused || { adb shell am start -n $PKG/.MainActivity -e tubify_theme dark --ez tubify_open_settings true $2 >/dev/null; sleep $W; }; }

# --- Browsing (Quran content only) ---
open_url 01-search "https://m.youtube.com/results?search_query=quran+recitation"
# tap the first video thumbnail (scaled to the real display size)
read SW SH <<DIMS
$(adb shell wm size | tail -1 | grep -o '[0-9x]*' | tr x ' ')
DIMS
for i in 1 2; do
  adb shell input tap $((SW/2)) $((SH*22/100)); sleep $W
  focused && break
  open_url 01-search "https://m.youtube.com/results?search_query=quran+recitation"
done
shot 02-watch

# --- Settings ---
set_display
settings x
shot 03-settings-blocking
adb shell input swipe 160 500 160 180 300; sleep 1; shot 04-settings-interface
adb shell input swipe 160 500 160 180 300; sleep 1; shot 05-settings-about
tap "Theme"; sleep 1; shot 06-theme-chooser
adb shell input keyevent 4; sleep 1
tap "Check for updates"; sleep 12; shot 07-update-latest
dump; grep -q "You are on the latest version\|Update available\|Checking for updates" "$OUT/cur.xml" && echo "update dialog shown" || echo "WARN update dialog state unknown"
adb shell input keyevent 4; sleep 1
tap "Error log"; sleep 2; shot 08-error-log
adb shell input keyevent 4; sleep 1
true
