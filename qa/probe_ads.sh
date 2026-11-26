#!/bin/bash
# usage: probe_ads.sh NEW_APK OLD_APK OUT  -- Full: logo size old vs new, and uBlock on vs off on an ad-block test page
NEW=$1; OLD=$2; OUT=$3; mkdir -p "$OUT"; R="$OUT/report.txt"; : > "$R"
PKG=com.tubify.app
log(){ echo "$*" | tee -a "$R"; }
shot(){ timeout 40 adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }
dump(){ timeout 40 adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml "$OUT/$1.xml" >/dev/null 2>&1; }
view(){ adb shell am force-stop $PKG; adb shell am start -a android.intent.action.VIEW -d "$1" -p $PKG >/dev/null 2>&1; }
SEARCH="https://m.youtube.com/results?search_query=music"
ADTEST="https://d3ward.github.io/toolz/adblock.html"
adb install -r -d "$OLD" >/dev/null 2>&1 && log "old installed"
view "$SEARCH"; sleep 45; shot logo-old
adb install -r "$NEW" >/dev/null 2>&1 && log "new installed"
view "$SEARCH"; sleep 45; shot logo-new
# let uBlock install and finish
sleep 90
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --ez tubify_open_settings true >/dev/null; sleep 20
adb shell input swipe 160 500 160 100 300; adb shell input swipe 160 500 160 100 300; adb shell input swipe 160 500 160 100 300; sleep 1
dump s-on; shot settings-on
log "diag: $(grep -o 'uBlock Origin: [a-zA-Z ]*' $OUT/s-on.xml | head -1)"
view "$ADTEST"; sleep 60; shot ads-on-1
adb shell input swipe 160 450 160 150 400; sleep 3; shot ads-on-2
# turn uBlock off
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --ez tubify_open_settings true >/dev/null; sleep 20
dump s1
P=$(python3 "$(dirname "$0")/tap.py" "$OUT/s1.xml" "uBlock Origin") && adb shell input tap $P && log "tapped uBlock row at $P" || log "FAIL no uBlock row"
sleep 8
adb shell input swipe 160 500 160 100 300; adb shell input swipe 160 500 160 100 300; adb shell input swipe 160 500 160 100 300; sleep 1
dump s-off; shot settings-off
log "diag after off: $(grep -o 'uBlock Origin: [a-zA-Z ]*' $OUT/s-off.xml | head -1)"
view "$ADTEST"; sleep 60; shot ads-off-1
adb shell input swipe 160 450 160 150 400; sleep 3; shot ads-off-2
adb logcat -d > "$OUT/logcat.txt"
log DONE
