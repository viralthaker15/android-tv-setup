#!/bin/bash
# Step 1 on a new TV: does the firmware log remote key presses, and with what codes?
#
# Start it, then press each remote button once, slowly (2-3 s apart), in an order you write down.
# It records the log for N seconds (default 60), then prints the window manager's key-down lines, which is what
# TV Keys reads. The full log is kept next to it for a closer look. Read-only: it changes nothing on the TV.
#
#   ANDROID_SERIAL=192.168.1.50:5555 tools/find-keys.sh 60
set -euo pipefail
SECS="${1:-60}"
OUT="keys-$(date +%Y%m%d-%H%M%S).log"

echo "Recording for ${SECS}s into $OUT. Press the buttons now, slowly, and note the order."
# -T 1: start at the newest line, so nothing from before this run shows up. main+system: firmwares differ.
adb logcat -b main,system -v threadtime -T 1 > "$OUT" 2>&1 &
LOGCAT=$!
trap 'kill $LOGCAT 2>/dev/null || true' EXIT
sleep "$SECS"
kill $LOGCAT 2>/dev/null || true
wait $LOGCAT 2>/dev/null || true

echo
echo "== Key-down lines from the window manager (the three shapes seen so far on TVs):"
# Hisense/Toshiba, TCL Android 9:  WindowManager: interceptKeyTi keyCode=293 down=true repeatCount=0 ...
# Xiaomi:                          WindowManager: interceptKeyTq keycode=190 ...
# TCL Google TV (Android 12):      WindowManager: handleKeyBeforeQueueing event=KeyEvent { action=ACTION_DOWN, keyCode=4065, ...
grep -E 'WindowManager' "$OUT" \
  | grep -E 'interceptKeyT[iq]|handleKeyBeforeQueueing' \
  | grep -v -E 'down=false|ACTION_UP' || echo "(none: this firmware does not log keys under WindowManager)"

echo
echo "== Any other line that mentions a key code (vendor key managers, launchers):"
grep -i -E 'keycode[=: ]' "$OUT" | grep -v -E 'WindowManager' | cut -c1-220 | head -40 || true

echo
echo "Full log: $OUT"
echo "If nothing above lines up with your presses, look at raw input: adb shell getevent -lt  (Ctrl-C to stop)"
