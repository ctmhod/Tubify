#!/bin/bash
# README screenshots. Content rule: search results for Quran recitation only, no music.
PKG=$1; APK=$2; OUT=$3; W=$4
mkdir -p "$OUT"
adb install -r "$APK" >/dev/null
adb shell wm size 720x1440; adb shell wm density 320
shot(){ adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png "$OUT/$1.png" >/dev/null 2>&1; }
adb shell am start -a android.intent.action.VIEW -d "https://m.youtube.com/results?search_query=quran+recitation" -p $PKG >/dev/null 2>&1; sleep $W; shot 01-search
adb shell am force-stop $PKG
adb shell am start -a android.intent.action.VIEW -d "https://m.youtube.com/results?search_query=quran+recitation" -p $PKG --ez tubify_open_settings true >/dev/null 2>&1; sleep $W; shot 02-settings
adb shell input swipe 160 500 160 100 300; sleep 1; shot 03-settings-more
true
