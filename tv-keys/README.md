# TV Keys

Makes the remote's app buttons work on the living-room Toshiba 55M550LP (Hisense-built Google TV, Android 11,
firmware 01.03.05.04, remote CT-95042) after the Google TV home was disabled, **without** a key-filtering
accessibility service, so a held volume key still ramps. It also mutes Prime Video ads (below).

**Setting this up on a different TV?** Read [CLAUDE.md](../CLAUDE.md) at the top of this repo first. It has the reasoning, the measurements
from this TV and a step-by-step for finding your own TV's key codes and ad signal. The key codes below are for this
one remote and will be different on yours.

| Button | keyCode | Opens |
|---|---|---|
| Gear | 83 | TV Settings |
| YouTube | 293 | YouTube |
| Prime Video | 295 | Prime Video |
| Google Play | 303 | Play Store |
| Apps | 284 | CleanYourTV launcher's App library |

## How it works

- `KeysService` is an accessibility service that asks for nothing: no key filtering, no window content, only
  announcement events (`dumpsys accessibility` shows `capabilities=0`). Android binds it at boot, which keeps the
  process alive and lets it open apps from the background.
- A thread runs `logcat -b main -s WindowManager:D` and blocks on its output. This firmware logs
  `interceptKeyTi keyCode=N down=true repeatCount=0` for every key it dispatches; a match opens the mapped app.
  Nothing is consumed, so each button still does whatever the firmware does with it (nothing, for these).
- After the first `interceptKey` line it restarts logcat with `--pid=<system_server>`, which logd filters itself,
  so the reader sleeps through the ~24 lines/s other apps log at idle. The restart resumes from that line's
  timestamp and skips what was already handled.

Measured on the TV, idle on Home, 2 × 120 s: the app and its logcat 0.00–0.01 % of one core; logd 0.20 → 0.28 %.

## Ad mute (Prime Video)

`AdMuteService` mutes the TV while Prime Video plays an ad and unmutes when it ends. Mute only: it never skips, seeks
or blocks anything.

- **Signal.** During an ad break Prime's media session reports `STATE_PLAYING` at speed 0 and drops the seek actions
  (`actions` 1049455 → 1049095); seek comes back the moment the ad ends. Film playback always keeps `SEEK_TO`, and a
  live stream (no seek either) plays at speed 1, so all three conditions are required. Measured on a pre-roll and a
  mid-roll on two titles. Prime's log and accessibility tree carry no ad signal.
- **Access.** A notification listener, because that is the only way an ordinary app may watch other apps' media
  sessions. It ignores notifications. Callbacks only, no polling.
- **Safety.** Saves a "muted by us" flag before muting. Unmutes when the ad ends, when Prime pauses, stops or goes
  away, when the service restarts, and after 5 minutes at most. Never changes the volume level. Leaves the sound alone
  if it was already muted, or if you press volume/mute during the ad.
- **Verified** on 2026-11-06: a 32 s pre-roll muted 43 ms after Prime reported it and unmuted 44 ms after it ended.
  Not verified: a mid-roll's end with mute on, live streams. SonyLIV needs no mute on this TV (the DNS filter
  already stops its ads; see the main guide, Part 1 step 8). JioHotstar was not tested. `APPS` lists Prime only.

Enable: `adb shell cmd notification allow_listener dev.viral.tvkeys/dev.viral.tvkeys.AdMuteService`
Disable: `adb shell cmd notification disallow_listener dev.viral.tvkeys/dev.viral.tvkeys.AdMuteService`
Watch its decisions: `adb shell logcat -s TVKeys.AdMute`

## Build and install

```bash
./build.sh                                   # JDK 17 + SDK platform 34 / build-tools 34.0.0
adb install build/tv-keys.apk
adb shell pm grant dev.viral.tvkeys android.permission.READ_LOGS
sleep 5   # enabling straight after install can be dropped before the service is registered
adb shell settings put secure enabled_accessibility_services dev.viral.tvkeys/.KeysService
adb shell settings put secure accessibility_enabled 1
```

Idle cost: `ANDROID_SERIAL=<tv-ip>:5555 python3 -I tools/idle_cpu.py --seconds 120 --runs 2`

## Undo

```bash
adb shell cmd notification disallow_listener dev.viral.tvkeys/dev.viral.tvkeys.AdMuteService
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure accessibility_enabled 0
adb uninstall dev.viral.tvkeys
```

## If the buttons stop after a firmware update

Look for the key line first: `adb shell logcat -b main -s WindowManager:D | grep interceptKeyTi` while pressing a
button. If it is gone, the firmware stopped logging keys and this approach no longer works on that build.
