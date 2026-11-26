#!/bin/bash
# usage: qa.sh PKG NEW_APK OLD_APK OUTDIR WAIT
PKG=$1; NEW=$2; OLD=$3; OUT=$4; W=$5
mkdir -p "$OUT"; R="$OUT/report.txt"; : > "$R"
adb(){ timeout 60 /usr/bin/env adb "$@"; }
log(){ echo "$*" | tee -a "$R"; }
shot(){ timeout 60 adb shell screencap -p /sdcard/s.png; timeout 60 adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null 2>&1 || true; }
dump(){ timeout 60 adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml "$OUT/$1.xml" >/dev/null 2>&1 || true; }
has(){ grep -q "text=\"$2\"" "$OUT/$1.xml" && log "PASS $1 has '$2'" || log "FAIL $1 missing '$2'"; }
tap(){ dump cur; P=$(python3 "$(dirname "$0")/tap.py" "$OUT/cur.xml" "$1") && adb shell input tap $P && log "tapped '$1' at $P" || log "FAIL cannot find '$1' to tap"; sleep 1.5; }
checked(){ dump c; grep -o 'checked="true"' "$OUT/c.xml" | wc -l; }
launch_settings(){ adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity -e tubify_theme "$1" --ez tubify_open_settings true >/dev/null; sleep $W; }
fatal(){ adb logcat -d > "$OUT/logcat-$1.txt"; if grep -A2 "FATAL EXCEPTION" "$OUT/logcat-$1.txt" | grep -q "Process: $PKG"; then log "FAIL $1 FATAL EXCEPTION"; grep -A8 "FATAL EXCEPTION" "$OUT/logcat-$1.txt" | head -20 >> "$R"; else log "PASS $1 no fatal exception"; fi; adb logcat -c; }

log "== 1 upgrade: install OLD then NEW over it"
adb uninstall $PKG >/dev/null 2>&1
adb install -r "$OLD" >/dev/null && log "old installed"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 20; fatal old
adb install -r "$NEW" >/dev/null && log "PASS upgrade install over old"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep $W; fatal upgrade
shot 01-upgraded

log "== 2 fresh install default state"
adb uninstall $PKG >/dev/null 2>&1; adb install -r "$NEW" >/dev/null && log "fresh installed"
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez tubify_open_settings true >/dev/null; sleep $W
dump s1; shot 02-settings-top
for t in "Theme" "Auto (follow system)" "Hide Shorts" "Hide suggestions" "Hide comments" "Hide home feed"; do has s1 "$t"; done
adb shell input swipe 160 500 160 100 300; sleep 1; dump s2; shot 03-settings-scrolled
for t in "Source code" "Developer" "Error log" "Releases" "Report an issue" "Done"; do has s2 "$t"; done
adb shell input swipe 160 500 160 100 300; sleep 1; dump s3; shot 04-settings-bottom
has s3 "Done"

log "== 3 toggles"
adb shell input swipe 160 100 160 600 300; adb shell input swipe 160 100 160 600 300; sleep 1
B=$(checked); log "switches on at start: $B"
tap "Hide comments"; A=$(checked); log "after Hide comments tap: $A"; [ "$A" != "$B" ] && log "PASS comments toggled" || log "FAIL comments toggle did not change state"
tap "Hide Shorts"; C=$(checked); [ "$C" != "$A" ] && log "PASS shorts toggled" || log "FAIL shorts toggle did not change state"
tap "Hide suggestions"; D=$(checked); [ "$D" != "$C" ] && log "PASS suggestions toggled" || log "FAIL suggestions toggle did not change state"
shot 05-toggled
tap "Hide Shorts"; tap "Hide suggestions"; tap "Hide comments"
E=$(checked); [ "$E" = "$B" ] && log "PASS toggles restore" || log "FAIL toggles did not restore ($B vs $E)"

log "== 4 theme chooser + persistence"
tap "Theme"; dump ch; shot 06-theme-chooser
has ch "Dark"; has ch "Light"
tap "Dark"; dump t1; has t1 "Dark"; shot 07-after-dark
adb shell am force-stop $PKG; adb shell am start -n $PKG/.MainActivity --ez tubify_open_settings true >/dev/null; sleep $W
dump t2; has t2 "Dark"; shot 08-persisted-dark
tap "Theme"; tap "Light"; dump t3; has t3 "Light"; shot 09-after-light
tap "Theme"; tap "Auto"; dump t4; has t4 "Auto (follow system)"

log "== 5 error log + links"
adb shell input swipe 160 500 160 100 300; adb shell input swipe 160 500 160 100 300; sleep 1
tap "Error log"; sleep 1; dump el; shot 10-error-log
adb shell input keyevent 4; sleep 1

log "== 6 rotation"
adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 3
dump rot; shot 11-landscape
adb shell settings put system user_rotation 0; sleep 3
dump rot2; shot 12-portrait-back
fatal rotation

log "== 7 back key, process kill, relaunch"
adb shell input keyevent 4; sleep 1; adb shell am kill $PKG; sleep 1
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep $W; fatal relaunch
shot 13-relaunch

log "== 8 offline launch"
adb shell svc wifi disable; adb shell svc data disable; sleep 3
adb shell am force-stop $PKG; adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep $W
shot 14-offline; fatal offline
adb shell svc wifi enable; adb shell svc data enable; sleep 3

log "== 9 YouTube flows (deep links)"
yt(){ adb shell am force-stop $PKG; adb logcat -c; adb shell am start -a android.intent.action.VIEW -d "$2" $PKG >/dev/null 2>&1 || adb shell am start -a android.intent.action.VIEW -d "$2" -p $PKG >/dev/null 2>&1; sleep $W; shot "$1"; adb logcat -d > "$OUT/logcat-$1.txt"; if adb shell pidof $PKG | grep -q .; then log "PASS $1 app alive"; else log "FAIL $1 app not running"; fi; fatal "$1"; }
yt 20-search "https://m.youtube.com/results?search_query=music"
yt 21-watch "https://m.youtube.com/watch?v=jNQXAC9IVRw"
yt 22-playlist "https://m.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
yt 23-subscriptions "https://m.youtube.com/feed/subscriptions"
yt 24-signin "https://accounts.google.com/ServiceLogin?service=youtube"
yt 25-shorts-link "https://m.youtube.com/shorts/aqz-KE-bpKQ"
yt 26-channel "https://m.youtube.com/@YouTube"
log "== 10 DOM dump and themed pages"
ytx(){ adb shell am force-stop $PKG; adb logcat -c; adb shell am start -a android.intent.action.VIEW -d "$2" -p $PKG --ez tubify_dump_dom true $3 $4 $5 $6 >/dev/null 2>&1; sleep $((W+12)); shot "$1"; adb logcat -d > "$OUT/logcat-$1.txt"; if adb shell pidof $PKG | grep -q .; then log "PASS $1 app alive"; else log "FAIL $1 app not running"; fi; }
ytx 30-home-dump "https://m.youtube.com/"
ytx 31-watch-dump "https://m.youtube.com/watch?v=jNQXAC9IVRw"
ytx 32-home-dark "https://m.youtube.com/" -e tubify_theme dark
ytx 33-watch-dark "https://m.youtube.com/watch?v=jNQXAC9IVRw"
ytx 34-home-light "https://m.youtube.com/" -e tubify_theme light
ytx 35-watch-light "https://m.youtube.com/watch?v=jNQXAC9IVRw"
log "== 11 toggle states on real pages"
ytx 40-watch-comments-hidden "https://m.youtube.com/watch?v=jNQXAC9IVRw" --ez tubify_comments true
ytx 41-watch-comments-shown "https://m.youtube.com/watch?v=jNQXAC9IVRw" --ez tubify_comments false
ytx 42-search-shorts-on "https://m.youtube.com/results?search_query=music" --ez tubify_shorts true
ytx 43-search-shorts-off "https://m.youtube.com/results?search_query=music" --ez tubify_shorts false
ytx 44-logo "https://m.youtube.com/results?search_query=music"
log "DONE"
