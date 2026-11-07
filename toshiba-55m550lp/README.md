# Living Room TV — optimisation write-up

**TV:** Toshiba 55M550LP ("Living Room TV"), Hisense-built Google TV, Android 11, firmware `01.03.05.04`, 192.168.0.91
**Date:** 2026-11-05 · **Every change, with its exact undo:** [CHANGES.md](CHANGES.md)

## Results

Side by side with screenshots and per-process memory: **[before-after.html](before-after.html)**.

| | Before (18:46, stock) | After (20:40, final) |
|---|---|---|
| Home screen | Google TV, full-screen **"Sponsored"** ad (Hero) | CleanYourTV launcher, *nature-calm* theme, **PS5 tile**, no ads |
| Whole-TV CPU when idle | 21.2 % | **6.4 %** (−70 %) |
| Home screen CPU when idle | 13.9 % | **0.1 %** |
| Home screen memory (PSS) | 133 MB | **52 MB** (−61 %; 40 MB without the photo wallpaper) |
| Free memory on the TV | 704 MB | **848 MB** (+144 MB) |
| Frames the home drew during a 108-key test | 2,683 (98 % janky) | **24** (0 janky; it only draws when you move) |
| "Hey Google" listener (far-field mic) | always recording, ~30 % of a core | **off** at the switch |
| Ad / tracker domains (doubleclick, googlesyndication, app-measurement, Netflix telemetry…) | resolve | **blocked** (AdGuard Private DNS) |
| SonyLIV episode ads | shown | **none**: its ad request to Google's ad server fails at the DNS filter (checked 2026-11-06) |
| SonyLIV live-match ads | loud | **muted** while they play, sound back when the match returns (TV Keys 1.3.1, 2026-11-07) |
| Animations | 1.0× | **0.5×**, kept after reboots |
| Home app cold start | 601 ms | 1,088 ms (slower; it stays loaded as Home, so rarely felt) |

Raw numbers: `bench-before/`, `bench-after/` (mid-way, before the mic switch), `bench-final/`. Screenshots:
`home-before-sponsored.png` (profile photo covered), `home-nature-calm.png`, `home-final.png`.

Not claimed: per-key latency. The benchmark could time 25 of 108 presses on the old home and 1 on the new launcher
(it only sees frames the home draws), so there is no fair latency number.

## What changed

**Smoothness**
- Google TV home (`launcherx`, 173 MB with its services) replaced by the CleanYourTV launcher (588 KB APK, no internet permission, built on this Mac from the repo)
- Animation scales 1.0 → 0.5, re-applied by the launcher after every boot (the firmware resets them; see Reboot check)
- Clock screensaver instead of Google's ambient mode
- **PS5 tile** in the dock: opens HDMI 1 directly (it borrows the unused Screen Mirroring tile)
- 13 preloads disabled (below); far-field "Hey Google" mic switched off at the TV's physical switch

**Remote buttons (2026-11-06)** — **[TV Keys](../tv-keys/)**, a 25 KB app built on this Mac (no internet
permission): gear → Settings, YouTube, Prime Video, Google Play, Apps → App library. It reads the firmware's
key log instead of filtering keys, so held volume still ramps (75 ms steps). Idle cost: ~0 for the app, +0.08 % of one
core in the system log service. Survives reboots. Details and undo: CHANGES.md T12.
**Prime Video ads are muted** by the same app (T13): it watches Prime's media session, mutes when an ad starts and
puts the sound back the moment the ad ends. Mute only, nothing is skipped.
**SonyLIV live-match ads are muted** too (T14, 2026-11-07): it reads SonyLIV's own log, where each ad break shows
up as video loaded from the ad server's path, and mutes the moment the audio switches over. SonyLIV episodes need no
mute (the DNS filter already stops their ads, see Ads below). JioHotstar not covered.

**Decluttering** — all `pm disable-user --user 0`, nothing uninstalled
- Retail/store demo, first-run leftovers, on-screen disclaimer
- Google TV Movies store, Play Games, Smart Connect, calendar sync, YouTube Music (your picks)
- Google TV setup wizard (needed for the launcher swap; it only owned a stale saved Wi-Fi entry)
- Kept on purpose: firmware updaters, AirPlay/HomeKit, Chromecast built-in, Miracast, DLNA, Assistant, Netflix,
  Prime Video, YouTube, Play Store, Gboard, and all 18 apps you installed

**Privacy / tracking**
- Google partner attribution (`partnersetup`) and feedback uploader disabled; silent partner auto-installs disabled
- Usage & diagnostics off and ad personalisation opted out (you, in Settings)
- No ACR / viewing-data app is installed on this unit (`tools/privacy.py` audit)
- Always-on far-field mic off (hardware switch)

**Ads**
- Private DNS (DNS-over-TLS) to `dns.adguard-dns.com`, on the TV only — nothing else in the house changed
- **SonyLIV: no ads** (checked 2026-11-06). When an episode starts, SonyLIV asks Google's ad server
  (`pubads.g.doubleclick.net`) for an ad. On this TV that name now resolves to 127.0.0.1, so the request fails
  (`FAILED_TO_REQUEST_ADS` in its log) and the episode plays at once; you saw no ads in episodes either. Nothing was
  installed or changed for this. If you ever want SonyLIV's ads back (or it starts refusing to play), switch Private
  DNS off: `adb shell settings put global private_dns_mode opportunistic`. Its **live matches** are different: their
  ads are stitched into the stream on the server (AWS MediaTailor), so DNS can't stop them. TV Keys mutes those
  instead (T14).

## What couldn't be done, and why

- **YouTube in-video ads, and ads inside Netflix / Prime / Hotstar ad tiers** — they come from the same servers as
  the video, so DNS can't remove them without breaking playback. Checked for Prime Video: every Amazon ad server
  (`aax`, `c`, `s`, `aax-eu.amazon-adsystem.com`) is already blocked and its ads still play, because they are stitched
  into the stream by Prime Video's playback service (`atv-ps.amazon.com`), which Prime needs to play at all.
  Google's ad-request servers (`pubads`/`googleads.g.doubleclick.net`) are blocked too, which stops ads that apps
  fetch separately before a video. Legitimate fix: the ad-free tier (YouTube Premium or
  Premium Lite, the app's ad-free plan). Third-party clients such as SmartTube do block them but are sideloaded from
  GitHub and break YouTube's Terms of Service, so they were not installed.
- ~~Remote app buttons + smooth volume ramp together.~~ **Done 2026-11-06 with TV Keys** (below): gear, YouTube,
  Prime Video, Google Play and Apps work again and held volume still ramps. Only Guide and Profile do nothing.
- **Settings → Accounts & sign-in** spins forever: it is drawn by the disabled Google TV home. Workaround in CHANGES.md.
- **"Hey Google" can't be switched off in software from ADB** (Google only accepts that toggle from the Settings page
  above), so the TV's physical mic switch was used instead. The remote's mic button needs the remote paired over
  Bluetooth (hold Back + Home) — it never was.
- **Chromecast built-in** may stop working without the setup wizard (untested; you use AirPlay, which is unaffected).
- **Netflix profile screen:** Back doesn't exit from there — Netflix's own behaviour; Back works everywhere else.
- **Network-wide blocking** (router / Pi-hole) not done: blocking is on the TV only.

## Undo

Run from `~/Documents/learning/cleanyourtv` with USB debugging on (Developer options → USB debugging) and the TV at 192.168.0.91:

```bash
TV_HOST=192.168.0.91 tools/theme.sh --reset
TV_HOST=192.168.0.91 tools/restore.sh "../android-tv-setup/toshiba-55m550lp/debloat-living-room.txt" com.google.android.apps.tv.launcherx
adb connect 192.168.0.91:5555 && adb shell settings put global private_dns_mode opportunistic
adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
adb uninstall com.gathin.tvhome
adb shell settings delete secure enabled_accessibility_services && adb uninstall dev.viral.tvkeys
```

`restore.sh` re-enables the 13 packages, puts the Google TV home back as Home and the animation scales back to 1.0,
from the record in `~/.cleanyourtv/recovery-192.168.0.91-2a02ed5b.json` (keep that file). Flip the mic switch back
for "Hey Google". Without a computer: Settings → Apps → See all apps → Show system apps → enable each one.

## After a firmware update

An update can re-enable the disabled apps and give Home back to Google TV. Turn USB debugging on, then:

```bash
cd ~/Documents/learning/cleanyourtv
TV_HOST=192.168.0.91 tools/venv/bin/python -I tools/check.py "../android-tv-setup/toshiba-55m550lp/debloat-living-room.txt"        # report drift
TV_HOST=192.168.0.91 tools/venv/bin/python -I tools/check.py --fix "../android-tv-setup/toshiba-55m550lp/debloat-living-room.txt"  # put it back
```

## Developer access

Closed at the end: authorisations revoked, USB debugging and Developer options off, and port 5555 verified closed
from the Mac (`tools/adb-closed.py`). Every command in "Undo" and "After a firmware update" needs it turned back on
first (Settings → System → About → click "Android TV OS build" 7× if Developer options is hidden → Developer options →
USB debugging), then Allow on the TV.

## Files here

`CHANGES.md` (full log + undo) · `debloat-living-room.txt` (the list) · `theme-nature-calm/` (your theme copy) ·
`bench-before/`, `bench-after/`, `bench-final/` · `remote-keys.log`, `remote-launches.log` (button test) ·
`back-test.log`. Launcher changes: [`../cleanyourtv-launcher-patch/`](../cleanyourtv-launcher-patch/).
Kept on the Mac only, not in the repo (`.gitignore`): `snapshot-before/` (raw dumps: serials, network names),
`home-before.png`, `home-after-launcher.png`, the PS5 logo banner and `theme-gallery.html`.

## Reboot check (20:15, full restart)

Booted in 30 s straight onto the CleanYourTV launcher (not the setup wizard's recovery screen). Survived the restart:
the 14 disabled packages, Home = launcher, Private DNS (validated, `doubleclick.net` blocked), clock screensaver,
theme files, key helper off, mic off, Wi-Fi (same network).

**Animation scales did not survive at first**: the firmware writes them to 1.0 at every boot, and after every app
launch it zeroes window/transition and writes 1.0 back ~3 s later. Fixed with a small local launcher patch
(`AnimationScale.java`): whenever Home appears it sets 0.5×, and again 5 s later, using `WRITE_SECURE_SETTINGS`
granted over ADB. Second reboot test (20:27): back to 0.5 / 0.5 / 0.5 within 10 s of boot with nobody touching it,
so switching off at the wall is fine. Inside apps the firmware keeps window/transition at 1.0; the animator scale
(most in-app motion) stays 0.5.
