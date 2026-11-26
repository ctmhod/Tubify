#!/bin/bash
# usage: probe_full.sh APK OUT
APK=$1; OUT=$2; mkdir -p "$OUT"; R="$OUT/report.txt"; : > "$R"
log(){ echo "$*" | tee -a "$R"; }
PKG=com.tubify.app
adb install -r "$APK" >/dev/null 2>&1 && log "installed" || log "FAIL install"
adb shell am start -n $PKG/.MainActivity --ez tubify_open_settings true >/dev/null
for t in 20 40 70; do
  sleep 20
  log "t=$t alive: $(adb shell pidof $PKG | head -c 20)"
  if timeout 40 adb exec-out screencap -p > "$OUT/shot-$t.png" 2>/dev/null && [ -s "$OUT/shot-$t.png" ]; then log "screencap ok at $t ($(stat -c %s $OUT/shot-$t.png) bytes)"; else log "screencap FAIL/timeout at $t"; fi
  timeout 40 adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1 && adb pull /sdcard/u.xml "$OUT/ui-$t.xml" >/dev/null 2>&1 && log "uidump ok at $t" || log "uidump FAIL at $t"
done
adb logcat -d > "$OUT/logcat.txt"; grep -c "FATAL EXCEPTION" "$OUT/logcat.txt" | sed 's/^/fatal count: /' | tee -a "$R"
log DONE
