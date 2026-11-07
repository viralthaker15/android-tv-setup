# Android TV clean-up, remote buttons and ad mute: the guide

Read this whole file before touching the TV. It is written for whoever helps a friend do on their own TV what was
done on one Toshiba Google TV, whatever the brand: what was done, why each piece is built the way it is, what was
measured, and the exact steps to work it out again on a different TV. Facts measured on the original TV are marked
as such. Anything about other brands comes from the CleanYourTV device notes (linked) or is marked unverified.

## What is in this repo

| Part | Folder | What it does |
|---|---|---|
| 1. Clean-up | (uses [CleanYourTV](https://github.com/Gathin23/CleanYourTV)) + `cleanyourtv-launcher-patch/` | Removes the stock Google TV home and its full-screen ads, disables preloaded junk, replaces Home with a light launcher, blocks ad/tracker domains, keeps animations at 0.5×. |
| 2. Remote buttons | `tv-keys/` (`KeysService`) | Removing the stock home kills the remote's app buttons. TV Keys makes them work again **without filtering keys**, so a held Volume+ still ramps smoothly. |
| 3. Ad mute | `tv-keys/` (`AdMuteService`) | Mutes the TV while Prime Video plays an ad, or while a SonyLIV live match is in an ad break, and puts the sound back the moment it ends. Mute only: never skips, seeks past or blocks an ad. |
| The original TV | `toshiba-55m550lp/` | The full record: every change with its undo (`CHANGES.md`), results (`README.md`, `before-after.html`), the debloat list, the theme, benchmarks. |

TV Keys is one 25 KB app (`dev.viral.tvkeys`, no internet permission). Its two parts are independent.

## First decide what applies to this TV

| Question | How to check | What it means |
|---|---|---|
| Is it Android TV / Google TV? | Settings → About (or Device Preferences → About) shows an Android TV OS build | Required. Samsung (Tizen), LG (webOS), Roku and Hisense VIDAA run no Android apps: nothing here works on them. Fire TV is Android-based; untested. |
| Does the owner want the stock home and its ads gone, and a faster UI? | Ask; look at the home screen | Part 1. Optional, and the biggest change. Parts 2 and 3 work without it. |
| Do the remote's app buttons work? | Press each one from Home and inside an app | If they work (stock home still enabled), **skip Part 2**: do not enable `KeysService`. Mapping a button that already works makes it do two things. Check again after Part 1. |
| Does the owner want ad mute? | Ask | Part 3. Works on its own. Prime Video and SonyLIV live matches so far, and each signal must be re-checked on this TV (step B1). For any other app, first check whether Private DNS already removes its ads (Part 1, step 8): it did for SonyLIV's episodes, not for its live matches. |
| Which Android version? | `adb shell getprop ro.build.version.release` | TV Keys is built for Android 11+ (`minSdk 30`). Older needs the changes in A4. |

## Rules that are not up for negotiation

- **Never filter keys.** No `flagRequestFilterKeyEvents` / `canRequestFilterKeyEvents` in the accessibility
  config. On several TV firmwares (this Toshiba, TCL G09/G10, Xiaomi Mi TV, Sony; see the CleanYourTV notes) any
  bound accessibility service with `CAPABILITY_CAN_FILTER_KEY_EVENTS` (8) stops key auto-repeat: a held volume key
  moves one step per press instead of ramping. That trade-off is the whole reason TV Keys exists. `dumpsys
  accessibility` must show its service with `capabilities=0`.
- **No polling inside TV Keys.** The key reader blocks on logcat's output; ad mute runs on media session callbacks.
  Measure idle CPU before and after (`tv-keys/tools/idle_cpu.py`) and report both.
- **No `INTERNET` permission** in TV Keys. Nothing in it needs the network.
- **Ad mute is mute only.** Never skip, seek, speed up or block ads. Restore the owner's sound state exactly
  (never change the volume level), and never leave the TV muted if the app dies mid-ad.
- **No heuristics.** If an app has no reliable ad signal (B1), say so and leave that app out. A guess that mutes
  the film is worse than hearing the ad.
- **The owner decides.** Ask before installing anything, before each change on the TV, and before rebooting.
  Have the owner press the real remote and listen; do not claim a button or a mute works from logs alone.
- **Keep a change log** on the computer: every change on the TV with its exact undo command
  (`toshiba-55m550lp/CHANGES.md` is the model).
- **Disable, never uninstall** system apps (`pm disable-user --user 0`), and keep CleanYourTV's recovery record
  (`~/.cleanyourtv/recovery-*.json`): it is what puts everything back.
- **Raw dumps stay private.** `getprop`, `settings` and Wi-Fi dumps contain the serial number, Android ID, Bluetooth
  address and network names. Keep them on the computer; never commit them. Check screenshots for profile photos and
  Wi-Fi names before sharing.
- **Leave firmware internals alone:** input and key services, "smartsense"-style key managers, MediaTek / Realtek /
  Amlogic services, firmware updaters. Disable only what the model's CleanYourTV list says is safe (Part 1); the
  buttons and ad mute needed no firmware change at all.
- **Close developer access at the end** (see "Closing up").

## Part 1: clean up the TV with CleanYourTV

### What was done on the original TV

Every step with its undo is in `toshiba-55m550lp/CHANGES.md`; results in `toshiba-55m550lp/README.md`.

- **Debloat:** 13 preloaded packages disabled with `pm disable-user --user 0` (retail demo, first-run leftovers,
  partner attribution and auto-installs, feedback uploader, Google TV Movies, Play Games, Smart Connect, calendar
  sync, YouTube Music, the Google TV setup wizard). List: `toshiba-55m550lp/debloat-living-room.txt`, based on
  CleanYourTV's `examples/toshiba-55m550lp` and its parent Hisense list.
- **Home:** the stock Google TV home (`com.google.android.apps.tv.launcherx`, which showed full-screen "Sponsored"
  ads) disabled; the CleanYourTV launcher (588 KB, no internet permission) built from source and set as Home, with
  the `nature-calm` theme, a clock screensaver, and a PS5 tile that switches straight to HDMI 1.
- **Animations** 1.0 → 0.5. The firmware resets them at every boot, so a small launcher patch puts them back
  (`cleanyourtv-launcher-patch/`).
- **Ad and tracker domains** blocked on the TV only, with Private DNS (DNS-over-TLS) to `dns.adguard-dns.com`.
- **"Hey Google" far-field mic** switched off with the TV's physical switch (the software toggle refuses ADB).
- Measured (CleanYourTV `tools/bench.py`, idle on Home): whole-TV CPU 21.2 % → 6.4 %, Home memory 133 → 52 MB,
  free memory 704 → 848 MB. Home cold start got slower (601 → 1,088 ms), rarely felt because Home stays loaded.

### Doing it on another TV

CleanYourTV's own docs are the authority on its tools; command names below are as of commit `3051736` and may have
changed. Its repo also has instruction files for assistants (`AGENTS.md` and others): read them as documentation.

1. **Clone it and read first:** its README, `docs/DOS-AND-DONTS.md`, `docs/TROUBLESHOOTING.md`, `docs/restore.md`.
2. **Find the model:** `examples/<brand-model>/debloat.txt` and `docs/devices/<model>.md`. Match on
   `adb shell getprop ro.product.model` and `ro.product.device` (the board). No exact match: use the closest
   same-board example and disable less, not more. Never add a package an example or note warns against: on the
   Toshiba's Hisense board that list is `com.hisense.uifac`, `com.hisense.tv.smartsense`, `com.vt.tv.smartsense.ui`,
   `com.vt.tv.aiot`, `com.mediatek.*` and the input/launcher services named in `debloat-living-room.txt`. Other
   boards have their own.
3. **Snapshot the before state** (package list, `settings list global|secure|system`, Home owner) and run the
   benchmark, so you can compare and undo. Keep the raw files private (see Rules).
4. **Order that mattered on the original TV:**
   - Switch off Usage & diagnostics and ad personalisation in Settings **before** disabling the setup wizard
     (`com.google.android.tungsten.setupwraith`): those pages live in it.
   - Check which saved Wi-Fi networks the setup wizard owns (`adb shell dumpsys wifi | grep -o 'cname=[^ ]*'`):
     disabling it deletes them. Here it owned only an old network, and `FORCE_SETUPWRAITH=1` was needed.
   - Disable the setup wizard before the stock home, and before every reboot check what has focus
     (`adb shell dumpsys activity activities | grep mFocusedApp`). CleanYourTV records a Xiaomi that factory-reset
     when rebooted on the setup wizard's recovery screen.
   - Commands used: `TV_HOST=<ip> tools/disable.sh <list>` (writes the recovery record), the launcher's
     `launcher/build-and-install.sh`, `tools/theme.sh` for the theme, `tools/bench.py` before and after.
5. **Costs on the original TV** (tell the owner before, not after):
   - The remote's app buttons stop (the firmware hands them to the stock home): Part 2 fixes that.
   - Settings → Accounts & sign-in spins forever (it is drawn by the stock home). Workaround in
     `toshiba-55m550lp/CHANGES.md`, "Known losses".
   - Chromecast built-in may stop working without the setup wizard (untested there; AirPlay was unaffected).
6. **Never turn on the launcher's key helper services** (`InputKeyService`, `HomeKeyService`) to get the buttons
   back: they filter keys and a held volume key stops ramping. Use Part 2 instead.
7. **Animations:** after a reboot, `adb shell settings get global window_animation_scale`. If the firmware put it back
   to 1.0, apply `cleanyourtv-launcher-patch/launcher.patch` and install the launcher with `RESTORE_KEY_SERVICES=1`
   (every rebuild, or the patch loses its permission).
8. **Private DNS:** `adb shell settings put global private_dns_specifier dns.adguard-dns.com` and
   `adb shell settings put global private_dns_mode hostname`. Then check YouTube, Netflix, Prime and the Play Store
   still work. Undo: `private_dns_mode opportunistic`. It blocks ads that apps fetch from ad servers. It **cannot**
   block ads inside YouTube, Prime Video or Netflix: those come from the same servers as the video (Prime stitches
   them into the stream from `atv-ps.amazon.com`, which it needs to play at all). That is why Part 3 exists.
   Apps that ask a separate ad server for each ad do lose them. Measured on the original TV (2026-11-06): SonyLIV
   asks Google's ad server `pubads.g.doubleclick.net` (Google's IMA ad SDK) for an ad when an episode starts; the
   DNS answers 127.0.0.1, the request fails with `FAILED_TO_REQUEST_ADS`, and the episode plays straight away. The
   owner saw no ads in episodes after that, and playback was unaffected. To check another app, start a title and
   read its log (it can hold account ids: keep it private):
   `adb logcat --pid=$(adb shell pidof <package>) | grep -i -E 'adtag|aderror|doubleclick|failed_to_request_ads'`.
   SonyLIV's live matches are different: their ads are stitched into the stream on the server (AWS Elemental
   MediaTailor), so they come through. Part 3 mutes them.
9. **After a firmware update** the disabled apps and the stock home can come back:
   `TV_HOST=<ip> tools/venv/bin/python -I tools/check.py --fix <list>`.
10. **Undo everything:** `TV_HOST=<ip> tools/restore.sh <list> com.google.android.apps.tv.launcherx` from the
    recovery record, plus the single-setting undos in the change log.

## Part 2: remote buttons with TV Keys

Run every command in Parts 2 and 3 from inside `tv-keys/`.

### The original TV (reference values, all measured)

Toshiba 55M550LP, Hisense-built Google TV, Android 11, firmware 01.03.05.04, remote CT-95042. The stock home was
disabled and replaced by the CleanYourTV launcher (`com.gathin.tvhome`).

| Button | keyCode | scanCode | Opens |
|---|---|---|---|
| Gear | 83 (`KEYCODE_NOTIFICATION`) | 598 | TV Settings (`com.android.tv.settings`) |
| YouTube | 293 | 469 | `com.google.android.youtube.tv` |
| Prime Video | 295 | 468 | `com.amazon.amazonvideo.livingroom` |
| Google Play | 303 | 466 | `com.android.vending` |
| Apps | 284 (`KEYCODE_ALL_APPS`) | 188 | CleanYourTV launcher's App library (launcher-specific) |

Key line the firmware writes to the **main** log buffer (from `system_server`) for every dispatched key:

```text
D/WindowManager(  811): interceptKeyTi keyCode=293 down=true repeatCount=0 keyguardOn=false canceled=false
```

The same firmware also logs `interceptKeyTq keycode=293 scancode=469 ...` (at queueing time, for down **and** up,
no `down=` field) and Hisense's `smart GlobalKeyReceiver: --Global application Launch. The keyCode is: 293`, which is
the component that forwards app buttons to the stock home. Codes differ between remotes: never copy this table to
another TV, read it again there (step A1).

Results on that TV: all five buttons worked from Home and inside apps, one launch per press, before and after a
reboot. Held Volume+ kept its ramp: steps 75-78 ms apart, same as before install. Idle CPU (2 × 120 s on Home): the
app and its logcat 0.00-0.01 % of one core; the system log daemon `logd` 0.20 → 0.28 % of one core; whole-TV
numbers within noise. Ad mute: a 32 s Prime pre-roll muted 43 ms after Prime reported it and unmuted 44 ms after
it ended; the owner heard it. Both services came back by themselves after a reboot, with the TV not muted.

### How the buttons part works

- `KeysService` is an **accessibility service that asks for nothing**: announcement events only, no window
  content, no key filter (`res/xml/keys_service.xml`). It exists so Android keeps the process alive, starts it
  again at boot, and lets it open activities from the background (a bound accessibility service is exempt from
  Android 10+ background-activity-start limits; on the Toshiba no extra permission was needed).
- A thread runs `/system/bin/logcat -b main -v time -v epoch -T <now> -s WindowManager:D` and blocks in
  `readLine()`. `READ_LOGS`, granted over ADB with `pm grant`, lets an ordinary app read other processes' lines.
- A line matching `interceptKeyTi keyCode=N down=true repeatCount=0` opens the app mapped to N
  (`onKeyDown`). Nothing is consumed: each key still does whatever the firmware does with it.
- **Cheap at idle:** the main buffer had ~24 lines/s of other apps' chatter at idle, and `-s` filters tags inside
  the logcat client, so the reader would wake for every line. After the first `interceptKey` line it learns the
  window manager's pid and restarts with `--pid=<pid>`, which `logd` filters itself, so the reader sleeps.
  Measured: 0.15 % of one core with the tag filter only, 0.05 % with `--pid`.
- Details that matter if you change the reader:
  - Learn the pid only from an `interceptKey` line: apps can log under the tag `WindowManager` too
    (`WindowLeaked` errors).
  - `-T <epoch.ms>` is **inclusive**: on the narrowing restart, skip lines up to and including the one already
    handled (the code compares the timestamp and the full line).
  - Start with `-T <now>`, never from the start of the buffer, or a restart replays old presses.
  - If logcat exits unexpectedly (logd restart, permission missing), restart with a backoff (1 s doubling to
    60 s). Log nothing when the stop was intentional (`onDestroy` kills logcat).
  - `system_server` gets a new pid after a reboot; the service starts from scratch then, so this is handled.
- Opening apps: `getLeanbackLaunchIntentForPackage`, falling back to `getLaunchIntentForPackage`, plus
  `FLAG_ACTIVITY_NEW_TASK`. Every package it opens must be listed in `<queries>` in the manifest (Android 11
  package visibility), or the lookup returns null.

### Porting the buttons part, step by step

#### A0. Connect

On the TV: Settings → System → About → click "Android TV OS build" 7 times → Developer options → turn on
**USB debugging** (on the Toshiba, and many other TVs, this also opens network ADB on port 5555; some have a
separate "Network debugging" switch). Then on the computer:

```bash
adb connect <tv-ip>:5555        # press Allow on the TV
export ANDROID_SERIAL=<tv-ip>:5555
adb shell getprop ro.product.model; adb shell getprop ro.build.version.release; adb shell getprop ro.build.display.id
```

Use `ANDROID_SERIAL` rather than a shell variable holding `adb -s ...`: zsh does not split such variables into
words and the command fails with "command not found".

#### A1. Find the key line and the codes

```bash
tools/find-keys.sh 60     # then press each button once, slowly, in an order you write down
```

What to look for, in order of preference:

| Log shape | Seen on | Use it? |
|---|---|---|
| `WindowManager: interceptKeyTi keyCode=N down=true repeatCount=0` | Toshiba/Hisense (Android 11), TCL BeyondTV (Android 9) | Best: one line per press, repeats marked. This is what the code matches. |
| `WindowManager: handleKeyBeforeQueueing event=KeyEvent { action=ACTION_DOWN, keyCode=N, ... repeatCount=0, ... }` | TCL Google TV G10 (Android 12) | Good. Change `KEY_DOWN`; note keyCode may print as a name (`KEYCODE_NOTIFICATION`) or a number (4065). |
| `WindowManager: interceptKeyTq keycode=N ...` | Xiaomi, also Toshiba | Usable only with care: it comes for key down and key up and for every repeat. Prefer another line if there is one. |
| No key line at all | ? | This approach does not work on that build. Tell the owner the options instead of improvising: keep the stock home for the buttons, or accept a key-filtering helper and lose the volume ramp. |

Also note **which buffer** the line is in (`main` or `system`; `adb logcat -b system -s WindowManager:D` vs
`-b main`) and **which tag** writes it. The code reads tag `WindowManager` from `main`; change `-b`, `-s` and the
`LINE` pattern if yours differ. Check the label on each button: the same code can mean different buttons on
different remotes. For raw scan codes: `adb shell getevent -lt` while pressing.

Then check what each button does **right now** with nothing installed. Map only dead buttons.

#### A2. Find the targets

```bash
adb shell pm list packages | grep -i -E 'youtube|netflix|amazon|disney|hotstar|sonyliv|spotify|vending|settings'
adb shell cmd package resolve-activity --brief -a android.settings.SETTINGS    # which app is TV Settings
```

Common TV packages: Netflix `com.netflix.ninja`, YouTube `com.google.android.youtube.tv`, Prime Video
`com.amazon.amazonvideo.livingroom`, Disney+ `com.disney.disneyplus`, JioHotstar `in.startv.hotstar`, SonyLIV
`com.sonyliv`, Play Store `com.android.vending`, TV Settings `com.android.tv.settings` (some vendors ship their
own). Confirm each one on the TV; do not trust this list over `pm list packages`.

The Apps button here opens a screen of the CleanYourTV launcher (`com.gathin.tvhome.action.APP_LIBRARY`). On a TV
without that launcher, point it at whatever the owner wants (their launcher's app list, or Settings → Apps), or
leave it unmapped.

#### A3. Edit the code

- `src/dev/viral/tvkeys/KeysService.java`:
  - `LINE` matches one line of `logcat -v time -v epoch`: `  1793948938.160 D/WindowManager(  811): message`.
    Keep it unless the log shape differs.
  - `KEY_DOWN` matches the key-down message. Adjust to the line from A1, and keep "not a repeat" in it.
  - The `startsWith("interceptKey")` check that learns the pid: change it to the start of your key line.
  - `onKeyDown`: the switch from code to app. Use `openApp(label, package)` for apps, `start(label, intent)` for
    anything else.
- `AndroidManifest.xml`: one `<package>` in `<queries>` for every package opened.
- If you rename the app (`package="dev.viral.tvkeys"`), also rename the `src/` path, the package line in both
  Java files, `PKG` in `tools/idle_cpu.py` and every command in this file and `tv-keys/README.md`.

#### A4. Build

```bash
./build.sh      # writes build/tv-keys.apk and prints its size and SHA-256
```

No Gradle: `aapt2` → `javac --release 11 -Xlint:all -Werror` → `d8` → `zipalign` → `apksigner`. Needs JDK 17 and
Android SDK `platforms;android-34` + `build-tools;34.0.0`. On a Mac with Homebrew:

```bash
brew install openjdk@17 && brew install --cask android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

`build.sh` defaults to the Homebrew paths; elsewhere set `ANDROID_HOME` and `JAVA_HOME`. It signs with
`~/.android/debug.keystore`; if that file does not exist, create it once:

```bash
keytool -genkeypair -keystore ~/.android/debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -validity 10000
```

Bump `--version-code` / `--version-name` in `build.sh` for each new build you install.

For an Android 9/10 TV: lower `--min-sdk-version` and `--min-api` to the TV's SDK level (28/29), and replace
`Set.of(...)` in `AdMuteService.java` (API 30+) with `Collections.singleton(...)` or a `HashSet`. Check that the
TV's logcat supports `-v epoch`, `-T` and `--pid` (`adb shell logcat --help`).

#### A5. Install and enable (ask the owner first)

```bash
adb install build/tv-keys.apk
adb shell pm grant dev.viral.tvkeys android.permission.READ_LOGS
adb shell settings get secure enabled_accessibility_services     # READ THIS FIRST
sleep 5   # enabling straight after install can be dropped before the service is registered
adb shell settings put secure enabled_accessibility_services dev.viral.tvkeys/.KeysService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility | grep -A3 tvkeys    # must be bound, capabilities=0
```

`settings put secure enabled_accessibility_services` **replaces the whole list.** If the read-back showed other
services, keep them: put `existing:dev.viral.tvkeys/.KeysService` (colon-separated), and write the old value into
the change log as the undo. If one of the existing services filters keys, that one is what kills the volume
ramp; tell the owner rather than removing it yourself.

Android 13 and later can show a "allow access to all device logs?" prompt when an app with `READ_LOGS` starts
reading. Not seen on Android 11 (Toshiba) or 12 (TCL); untested on 13+. If it appears, the owner must allow it,
and check whether it comes back after a reboot.

If a button logs `TVKeys: ... -> Intent` but nothing opens, look for "Background activity start" / "Abort
background activity starts" in `adb logcat`. On a TCL G10 it needed `adb shell appops set <pkg>
SYSTEM_ALERT_WINDOW allow` plus `APP_AUTO_START` allowed and saved with `appops write-settings`; the Toshiba
needed neither.

#### A6. Verify (with the owner at the remote)

```bash
adb logcat -s TVKeys        # one "<button> -> Intent" per press
```

1. Every mapped button from Home and from inside another app: one launch per press, no double launches.
2. **Held Volume+ for ~2 s**, then:
   `adb shell dumpsys audio | sed -n '/volume changes (logged/,/Supported System Usages/p' | grep ADJUST_RAISE | tail -30`
   (or `grep adjustSuggestedStreamVolume` if that section is named differently). A ramp is one long first gap
   (~250 ms) then steady small gaps (75-78 ms on the Toshiba). One entry per press means the ramp is broken.
   Compare with the same test before installing.
3. Reboot (owner's OK), then repeat 1 and 2 without touching ADB.
4. Idle CPU, before install and after, on Home with nobody touching the remote:
   `python3 -I tools/idle_cpu.py --seconds 120 --runs 2`. Process numbers are % of one core; the whole-TV
   number varies a few % by itself, so compare the per-process numbers.

Do not run `uiautomator dump` while testing: it suspends every accessibility service while it is connected, so
TV Keys gets unbound and rebound (harmless, but presses during that time are missed).

## Part 3: ad mute

### Ad mute: how it works and how to check another app

`AdMuteService` is a notification listener, only because that is the one way an ordinary app may watch other
apps' media sessions (`MediaSessionManager.getActiveSessions`). It ignores notifications. Everything runs on
callbacks (`OnActiveSessionsChangedListener`, `MediaController.Callback.onPlaybackStateChanged`).

**Prime Video's signal (measured on the Toshiba, a pre-roll and a mid-roll on two titles):**

| | state | speed | actions |
|---|---|---|---|
| Film playing | 3 (PLAYING) | 1.0 | 1049455 (includes SEEK_TO, FAST_FORWARD, REWIND, SKIP_TO_NEXT) |
| Ad playing | 3 (PLAYING) | **0.0** | **1049095** (those four removed: an ad cannot be skipped) |

Seek comes back exactly when the ad ends. Once the film showed speed 0 with seek still present while resuming, so
the rule needs all three: PLAYING **and** speed 0 **and** no `SEEK_TO`. A live stream cannot be sought either but
plays at speed 1, so it is not muted. Prime's own log lines and its accessibility tree carry no ad signal (the
player is a GL surface). The Bluetooth stack's `AvrcpMediaPlayerList: sendMediaUpdate` log lines show session
changes with timestamps, which helps line up timings.

**SonyLIV live matches' signal** (measured on the Toshiba on 2026-11-07, during the India vs NZ one-day match: eight
mid-roll breaks and a pre-roll; the owner timed three breaks and the pre-roll by pressing a remote button, and heard
the mute work). The media session shows nothing: PLAYING, speed 1, seek allowed, also during ads. SonyLIV's own
"ad playing" log flag stays false. The ads are stitched into the stream on the server by AWS Elemental MediaTailor.
Two log lines from SonyLIV's process carry it instead, read by `SonyLivAds.java`:

| Log line | What it shows | Timing |
|---|---|---|
| `OkHttp: ... callStart: Request{method=GET, url=https://.../v1/segment/...}` | SonyLIV is loading ad video (MediaTailor's ad path). Match video is `.ts` files on `*.slivcdn.com`; the CDN host and path change between streams | 0.5–6.4 s before the screen changes: the player loads ahead |
| `CCodecConfig: c2 config diff is ...` | The audio decoder is reconfigured as playback crosses between match and ad | 1.0–1.9 s before each of the owner's presses, i.e. the moment itself |

Each switch in what is being loaded is queued; each decoder change applies the oldest queued switch. A decoder
change with nothing queued is ignored: pause and resume, a commentary-language change and reopening the match all
reconfigure the decoder without a switch, and were tested. Breaks were 40 s (one 2 min), the pre-roll 19 s. The
reader runs only while SonyLIV has a media session, filtered to its process inside logd (`--pid`). It replays the
last 15 s when it starts, because a pre-roll begins about a second before SonyLIV publishes its session, so a
pre-roll is muted 1–2 s late. If TV Keys starts in the middle of a stream it waits for the next switch. While
SonyLIV plays it costs 0.5 % of one core in all (do **not** use logcat's `-e` regex filter here: it cost 4.2 %).
SonyLIV's episodes never load from the ad path, so they are untouched. This depends on SonyLIV logging its HTTP
requests; if an update stops that, nothing is muted, which is the safe way to fail. On another TV, check that both
lines appear (`adb logcat --pid=$(adb shell pidof com.sonyliv) -s OkHttp:I CCodecConfig:D`) and re-time them.

Safety, all in the code: the "muted by us" flag is saved (`commit()`) before muting. It unmutes when the ad ends,
when the app pauses/stops/goes away, when the service restarts or is unbound, and after 5 minutes at most (for
SonyLIV also when the match has been loading for 20 s without a decoder change). If the
TV was already muted it does nothing. If the owner unmutes or changes the volume during the ad
(`android.media.STREAM_MUTE_CHANGED_ACTION`), it lets go until the next ad. It never touches the volume level.

#### B1. Check the signal on this TV (Prime) or find one (another app)

```bash
tools/watch-ad-signal.sh com.amazon.amazonvideo.livingroom
```

Play a title with ads. The owner writes down the clock time when an ad starts, when it ends, and when the film is
back. Compare with the printed lines. A signal is **reliable** only if: it is present for every ad seen, never
during the film (including pause, resume, seek and buffering), flips within a second of the real start and end,
and holds on at least two titles with both a pre-roll and a mid-roll. App versions and regions differ, so check
Prime too; do not assume the table above.

If the media session shows nothing, the other places checked on Prime were the app's own log lines
(`adb logcat --pid=$(adb shell pidof <pkg>)`) and on-screen text such as "Ad" in the accessibility tree
(`uiautomator dump`; see the warning above). If none of them has a clean signal, **stop and say so**. Do not mute
on guesses like "volume got louder" or "a new item started".

How SonyLIV's signal was found, in case another app needs the same: record the whole log while the owner watches
(`adb logcat -v threadtime -b main > live.txt`), then line up what the app logs with the breaks. Screenshots don't
help: on this TV video is on a hardware layer and comes out black. Instead have the owner press a remote button that
does nothing (here Profile, keyCode 288; check the app ignores it) at each ad start and end: every press is in the
log to the millisecond (`interceptKeyTi keyCode=288 down=true`). Before installing, replay the recording through
the rule and check it mutes before every press and never elsewhere.

SonyLIV (`com.sonyliv`): on the original TV its episodes need no mute (Private DNS stops their ads, Part 1 step 8)
and its live matches are muted with the log signal above. Not tested at all: JioHotstar (`in.startv.hotstar`),
Netflix's ad tier, YouTube, Prime Video live streams, and a Prime mid-roll's end with mute switched on. They are
not in the app.

#### B2. Add an app

- Add its package to `APPS` in `AdMuteService.java`.
- Give it its own rule: Prime's is `evaluate()` in `AdMuteService.java`, SonyLIV's is `SonyLivAds.java` (the
  service sends SonyLIV's sessions there instead of to `evaluate()`).
- Build, install, then enable the listener (the owner can also do it in Settings → Apps → Special app access →
  Notification access):

```bash
adb shell cmd notification allow_listener dev.viral.tvkeys/dev.viral.tvkeys.AdMuteService
adb logcat -s TVKeys.AdMute      # every decision, with state/speed/actions
```

## Gotchas collected on the way

- Enabling the accessibility service in the same second as `adb install` can be silently dropped. Wait ~5 s.
- `settings put secure enabled_accessibility_services` overwrites other services (see A5).
- `uiautomator dump` suspends all accessibility services while it runs.
- On this Hisense/MediaTek firmware `adb exec-out screencap -p` prints vendor debug text before the PNG; cut
  everything before the PNG signature (`\x89PNG`) before opening it.
- Gear came through as `KEYCODE_NOTIFICATION` (83), not a vendor code; a first grep missed it in the noise. Read
  the full log around each press, not just a grep.
- After the Apps button the firmware also logs `ALL_APPS pkg=com.android.tv.settings`; it opens nothing.
- Some single Volume− taps on the Toshiba logged two steps 5 ms apart. That was there before TV Keys was
  installed (likely IR and Bluetooth both delivering the press). Measure before install so you can tell.
- A firmware update can drop the debug key lines. If the buttons stop after an update, run A1 again.
- Screenshots of playing video come out black on this TV (hardware video layer); time things with a remote press.
- logcat's `-e` regex is applied to every line of the process and is expensive (4.2 % of one core on SonyLIV's log,
  against 0.15 % with tag filters `-s`); filter by tag and match in the app.

## Closing up

1. Write down the final state and the undo in the change log. For Part 1 the undo is CleanYourTV's
   `tools/restore.sh` plus each single setting (see `toshiba-55m550lp/README.md`, "Undo"). For TV Keys:

   ```bash
   adb shell cmd notification disallow_listener dev.viral.tvkeys/dev.viral.tvkeys.AdMuteService
   adb shell settings put secure enabled_accessibility_services "<previous value, or delete if it was empty>"
   adb shell settings put secure accessibility_enabled 0     # only if it was 0 before
   adb uninstall dev.viral.tvkeys
   ```

2. `adb disconnect`. Then the owner, on the TV: Developer options → **Revoke USB debugging authorisations** →
   **USB debugging** off → **Developer options** off.
3. Check from the computer that port 5555 now refuses connections, e.g. `nc -z -G 3 <tv-ip> 5555` (macOS) or
   `nc -z -w 3 <tv-ip> 5555` (Linux) must fail. CleanYourTV's `tools/adb-closed.py <tv-ip>` does the same and
   also looks for a wireless-debugging advert on the network.

TV Keys is meant to keep working with developer access off: the `READ_LOGS` grant and the enabled services are
stored settings, not live ADB connections. After closing, have the owner press one mapped button (and, if ad mute
is on, watch one ad) to confirm it on their TV.

## Related

- [CleanYourTV](https://github.com/Gathin23/CleanYourTV) (MIT): the clean-up tools and launcher used in Part 1. Its
  `docs/devices/` has per-model notes for many brands; `tcl-g10-app-keys.md` there is where the log-reader idea was
  first built, and `tcl-55p71b-g09-key-repeat.md` measures the key-filter / volume-ramp trade-off.

## Files

| File | What it is |
|---|---|
| `toshiba-55m550lp/README.md` | The original TV: results, what changed, what could not be done, undo |
| `toshiba-55m550lp/CHANGES.md` | Every change on the TV and the Mac, with its exact undo |
| `toshiba-55m550lp/debloat-living-room.txt` | The CleanYourTV debloat list used, with the packages never to touch on that board |
| `toshiba-55m550lp/before-after.html` | Side-by-side screenshots and numbers |
| `toshiba-55m550lp/theme-nature-calm/` | The launcher theme used (wallpaper credits in `CREDITS.md`) |
| `toshiba-55m550lp/bench-*/result.json` | CleanYourTV benchmark results before, mid-way and after |
| `cleanyourtv-launcher-patch/` | The local launcher changes as patches, with what each one is for |
| `tv-keys/src/dev/viral/tvkeys/KeysService.java` | The key reader and button map |
| `tv-keys/src/dev/viral/tvkeys/AdMuteService.java` | Ad mute (Prime Video's rule, the mute itself and its safety) |
| `tv-keys/src/dev/viral/tvkeys/SonyLivAds.java` | SonyLIV live matches' ad signal, from SonyLIV's log |
| `tv-keys/AndroidManifest.xml`, `tv-keys/res/` | Permissions (`READ_LOGS` only), services, `<queries>`, accessibility config |
| `tv-keys/build.sh` | Build without Gradle |
| `tv-keys/tools/find-keys.sh` | A1: record the key lines while the owner presses buttons |
| `tv-keys/tools/watch-ad-signal.sh` | B1: print an app's media session state while ads play (polls; investigation only) |
| `tv-keys/tools/idle_cpu.py` | Idle CPU of the TV, `logd` and the app |
