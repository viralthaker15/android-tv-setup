# Local changes to the CleanYourTV launcher

The home screen on this TV is the [CleanYourTV](https://github.com/Gathin23/CleanYourTV) launcher, built from
source with a few local changes. These patches are those changes, against upstream commit
`3051736a5bac7fc5ae38c549bd3b0aabaabd673c` (2026-11-05). Both were checked with `git apply --check` against a clean
copy of that commit. A newer CleanYourTV may already differ; if a patch no longer applies, port it by hand.

```bash
cd cleanyourtv                                   # your clone
git apply ../android-tv-setup/cleanyourtv-launcher-patch/launcher.patch
git apply ../android-tv-setup/cleanyourtv-launcher-patch/bench-focus-fix.patch
```

## `launcher.patch`

| Change | Generic? | What to do on another TV |
|---|---|---|
| **`AnimationScale.java`** (new) + two lines in `MainActivity.java` + `system_animation_scale` in `config.xml` | Yes | Keep if your firmware puts the animation scales back to 1.0 (check after a reboot with `adb shell settings get global window_animation_scale`). This Toshiba writes 1.0 at every boot, and zeroes window/transition during each app launch, then writes 1.0 back ~3 s later. The patch sets 0.5 whenever Home resumes and once more 5 s later. Needs `WRITE_SECURE_SETTINGS`, so install the launcher with `RESTORE_KEY_SERVICES=1` **every time you rebuild**, or it silently does nothing. Empty string = off. |
| **PS5 tile**: three `com.vt.miracast=` items (`launch_actions`, `app_labels`, `app_banners`) | No | Turns the unused Screen Mirroring tile into "PS5" that switches straight to HDMI 1. `com.vt.miracast` and the input id `com.hisense.tv.hitvinput/.hdmi.HdmiTvInputService/HW4` are this TV's; find yours with `adb shell dumpsys tv_input`. The banner image itself (`res/drawable-nodpi/banner_ps5.jpg`, 640×360) is **not included** (it was the PlayStation logo): add your own picture under that name, or delete the `app_banners` line, or the build fails. |
| **`app_keys`** for remote CT-95042 (scan codes 598/469/468/466/188) | No | Only used by the launcher's own key helper services, which are **off** on this TV because any key-filtering helper stops a held volume key from ramping. The buttons are handled by [TV Keys](../tv-keys/) instead. You can drop these lines. |
| **`app_library` = true** | Yes | Turns on the launcher's App library screen, which the remote's Apps button opens through TV Keys. |

## `bench-focus-fix.patch`

One-line fix in `tools/bench.py`: it read the focused window from `dumpsys window`, whose first section on this TV was
an old ANR record with a stale focus, so the benchmark timed the wrong window. `dumpsys window displays` holds only
the live focus. Generic; harmless elsewhere.
