# TV Keys

Makes the remote's app buttons work on the living-room Toshiba 55M550LP (Hisense-built Google TV, Android 11,
firmware 01.03.05.04, remote CT-95042) after the Google TV home was disabled, **without** a key-filtering
accessibility service, so a held volume key still ramps. It also mutes Prime Video ads and SonyLIV live-match ads
(below).

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

## Ad mute (Prime Video, SonyLIV live matches)

`AdMuteService` mutes the TV while Prime Video plays an ad, or while a SonyLIV live match is in an ad break, and
unmutes when it ends. Mute only: it never skips, seeks or blocks anything.

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
  Not verified: a mid-roll's end with mute on, live streams.
- **SonyLIV live matches** (`SonyLivAds`). Their ads are stitched into the stream on the server (AWS MediaTailor),
  and SonyLIV's media session doesn't change during them. SonyLIV's own log does: it logs every video request, and ad
  video comes from MediaTailor's `/v1/segment/` path 0.5–6.4 s before it reaches the screen; the audio decoder logs a
  reconfiguration (`CCodecConfig`) at the moment playback crosses between match and ad. Each switch is queued and
  applied at the next decoder change; a decoder change with nothing queued (pause, resume, language change) is
  ignored. The log is read only while SonyLIV has a media session (`logcat --pid`, tags `OkHttp` and
  `CCodecConfig`), at 0.5 % of one core. Extra safety: unmutes if the match has been loading for 20 s without a
  decoder change. Measured and verified by ear on 2026-11-07 (India vs NZ one-day match); details in the main guide,
  Part 3. **Not muted:** a break MediaTailor leaves unfilled plays the broadcaster's own ads inside the match video. SonyLIV then loads nothing from the ad path and the decoder doesn't change, so there is no signal and TV Keys can't tell (seen once, 10:57 on 2026-11-07; nothing else in the log marked it either).
  SonyLIV episodes need no mute here: the DNS filter already stops their ads. JioHotstar was not tested.

Enable: `adb shell cmd notification allow_listener dev.viral.tvkeys/dev.viral.tvkeys.AdMuteService`
Disable: `adb shell cmd notification disallow_listener dev.viral.tvkeys/dev.viral.tvkeys.AdMuteService`
Watch its decisions: `adb shell logcat -s TVKeys.AdMute TVKeys.SonyLIV`

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
