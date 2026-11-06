#!/bin/bash
# Step 1 for ad mute on a new app: does the app's media session look different during an ad?
#
# Runs on the computer and polls once a second ON PURPOSE (investigation only; the app itself never polls).
# Prints one line each time the app's session or the TV's mute state changes. Play a title with ads and write down
# the clock time when an ad starts, when it ends, and when the film is back. Ctrl-C to stop. Read-only.
#
#   ANDROID_SERIAL=192.168.1.50:5555 tools/watch-ad-signal.sh com.amazon.amazonvideo.livingroom
#
# Columns: state (3 playing, 2 paused, 6 buffering, 1 stopped), speed, actions (a bit mask), and whether SEEK_TO
# (256) is in it. Prime Video during an ad: state=3 speed=0.0 seek=no; during the film: seek=yes.
set -euo pipefail
PKG="${1:?usage: $0 <package>, e.g. com.amazon.amazonvideo.livingroom}"

session_of() {   # dumpsys media_session on stdin -> "state=3 speed=0.0 actions=1049095" for $PKG, or "no-session"
  awk -v pkg="$PKG" '
    /^    [^ ]/ { insess = 0 }                       # each session starts at 4 spaces of indent
    index($0, "package=" pkg) && $0 ~ ("package=" pkg "$") { insess = 1 }
    insess && /^      state=null/ { print "state=null"; found = 1; exit }   # session exists, nothing playing
    insess && /state=PlaybackState/ {
      s = $0
      match(s, /state=[0-9-]+/);   st = substr(s, RSTART, RLENGTH)
      match(s, /speed=[0-9.-]+/);  sp = substr(s, RSTART, RLENGTH)
      match(s, /actions=[0-9]+/);  ac = substr(s, RSTART, RLENGTH)
      print st, sp, ac; found = 1; exit
    }
    END { if (!found) print "no-session" }'
}

echo "Watching $PKG. Times are this computer's clock."
last=""
while true; do
  session=$(adb shell dumpsys media_session | session_of)
  muted=$(adb shell dumpsys audio | grep -A1 '^- STREAM_MUSIC' | tail -1 | tr -d ' ')
  seek=""
  if [[ $session =~ actions=([0-9]+) ]]; then
    if (( BASH_REMATCH[1] & 256 )); then seek="seek=yes"; else seek="seek=no"; fi
  fi
  now="$session $seek $muted"
  if [[ "$now" != "$last" ]]; then
    echo "$(date +%H:%M:%S) $now"
    last="$now"
  fi
  sleep 1
done
