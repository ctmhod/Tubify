#!/bin/bash
# usage: probe_full.sh APK OUT  -- does uBlock Origin finish installing in Full when the app is left running?
APK=$1; OUT=$2; mkdir -p "$OUT"; R="$OUT/report.txt"; : > "$R"
log(){ echo "$*" | tee -a "$R"; }
PKG=com.tubify.app
adb install -r "$APK" >/dev/null 2>&1 && log "installed" || log "FAIL install"
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 40
for t in 1 2 3 4 5 6 7 8 9; do
  adb shell input tap 122 24; sleep 6
  adb shell input swipe 160 500 160 100 300; adb shell input swipe 160 500 160 100 300; adb shell input swipe 160 500 160 100 300; sleep 1
  timeout 40 adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml "$OUT/ui-$t.xml" >/dev/null 2>&1
  timeout 40 adb exec-out screencap -p > "$OUT/diag-$t.png" 2>/dev/null
  S=$(grep -o 'text="uBlock Origin: [^"]*"' "$OUT/ui-$t.xml" | head -1)
  log "round $t ($((40+t*20))s): $S"
  echo "$S" | grep -q "active" && break
  adb shell input keyevent 4; sleep 14
done
adb shell am start -a android.intent.action.VIEW -d "https://m.youtube.com/results?search_query=music" -p $PKG >/dev/null 2>&1; sleep 40
timeout 40 adb exec-out screencap -p > "$OUT/search.png" 2>/dev/null
adb logcat -d > "$OUT/logcat.txt"; grep -i -E "ublock|InstallPrompt|install failed|ErrLog" "$OUT/logcat.txt" | grep -v WARN | cut -c1-200 | head -20 >> "$R"
log DONE
