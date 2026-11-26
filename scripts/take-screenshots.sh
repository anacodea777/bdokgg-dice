#!/usr/bin/env bash
# Drives the debug APK on a running emulator and saves README screenshots.
# Usage: bash scripts/take-screenshots.sh [output-dir]
set -euo pipefail

PKG=com.bdokgg.dice
APK=app/build/outputs/apk/debug/app-debug.apk
OUT=${1:-screenshots}
mkdir -p "$OUT"

adb wait-for-device
adb install -r "$APK"

# Instant window transitions, but keep animator scale at 1 so the dice really roll.
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 1

# Clean status bar.
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" | grep -v "^Broadcasting" || true; }
clean_status_bar() {
  demo enter
  demo clock -e hhmm 0930
  demo battery -e level 100 -e plugged false
  demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
  demo notifications -e visible false
}

# SystemUI may still be starting right after boot; give it time, then check.
sleep 10
echo "sysui_demo_allowed=$(adb shell settings get global sysui_demo_allowed)"
clean_status_bar

# Taps "Wait" on a system "isn't responding" dialog if one is showing.
dismiss_anr() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
  adb shell cat /sdcard/ui.xml > /tmp/anr.xml 2>/dev/null || return 0
  grep -q "responding" /tmp/anr.xml || return 0
  local node; node=$(grep -o '<node [^>]*text="Wait"[^>]*>' /tmp/anr.xml | head -1 || true)
  [ -z "$node" ] && return 0
  read -r x1 y1 x2 y2 < <(echo "$node" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  echo "dismissing ANR dialog"
  adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
  sleep 1
}

# Re-apply demo mode before every shot: SystemUI sometimes drops it.
shot() { sleep "${2:-1}"; dismiss_anr; clean_status_bar; sleep 0.3; adb exec-out screencap -p > "$OUT/$1.png"; echo "saved $1"; }

# Prints "x y" for the centre of the view with the given resource id (or content-desc).
find_view() {
  local node="" try
  for try in 1 2 3 4 5 6; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    adb shell cat /sdcard/ui.xml > /tmp/ui.xml 2>/dev/null || true
    node=$(grep -o "<node [^>]*resource-id=\"$PKG:id/$1\"[^>]*>" /tmp/ui.xml | head -1 || true)
    if [ -z "$node" ] && [ -n "${2:-}" ]; then
      node=$(grep -o "<node [^>]*content-desc=\"$2\"[^>]*>" /tmp/ui.xml | head -1 || true)
    fi
    [ -n "$node" ] && break
    echo "view $1 not found (try $try), waiting" >&2
    # Dismiss a possible system "not responding" dialog.
    grep -o 'text="Wait"' /tmp/ui.xml >/dev/null && adb shell input keyevent KEYCODE_ENTER || true
    sleep 3
  done
  if [ -z "$node" ]; then echo "view $1 not found" >&2; return 1; fi
  read -r x1 y1 x2 y2 < <(echo "$node" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  echo "$(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))"
}

tap() { local xy; xy=$(find_view "$@"); adb shell input tap $xy; }

# Tap ROLL and grab a burst of frames while the dice are in the air. A screencap
# takes about a second on the emulator, so slow animators down 8x for this one
# roll. Frames are saved as <name>_f1..f6; pick the best one by eye.
roll_and_capture() {
  local xy; xy=$(find_view rollButton)
  dismiss_anr
  clean_status_bar
  adb shell settings put global animator_duration_scale 8
  sleep 1
  adb shell "input tap $xy; for i in 1 2 3 4 5 6; do screencap -p /sdcard/f\$i.png; done"
  adb shell settings put global animator_duration_scale 1
  for i in 1 2 3 4 5 6; do adb pull "/sdcard/f$i.png" "$OUT/$1_f$i.png" >/dev/null; done
  echo "saved $1 frames"
  sleep 12
}

launch() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
}

# Start from a clean state.
adb shell pm clear "$PKG" >/dev/null

# ---- English ----
adb shell cmd locale set-app-locales "$PKG" --locales en
launch
sleep 5
dismiss_anr
shot 01_home_en 3

roll_and_capture 02_rolling_en
shot 03_result_en 3

# Three dice, two more rolls so the history fills up.
tap plusButton; sleep 0.5
tap rollButton; sleep 3
tap rollButton
shot 04_three_dice_en 3

# Six dice.
for i in 1 2 3; do tap plusButton; sleep 0.5; done
tap rollButton
shot 05_six_dice_en 3

# Overflow menu.
tap overflow "More options"
shot 06_menu_en 3
adb shell input keyevent KEYCODE_BACK

# ---- Bengali ----
adb shell cmd locale set-app-locales "$PKG" --locales bn
launch
sleep 3
tap minusButton; sleep 0.5
tap minusButton; sleep 0.5
tap rollButton
shot 07_result_bn 3

demo exit
ls -l "$OUT"
