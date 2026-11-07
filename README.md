# Android TV setup: no ads on Home, a faster UI, working remote buttons, muted Prime ads

What was done to one Toshiba 55M550LP (Hisense-built Google TV, Android 11) in November 2026, written up so friends
can do the same on theirs.

| | Before | After |
|---|---|---|
| Home screen | Google TV with a full-screen "Sponsored" ad | CleanYourTV launcher, nature photo theme, no ads |
| Whole-TV CPU when idle | 21.2 % | 6.4 % |
| Home screen memory | 133 MB | 52 MB |
| Free memory | 704 MB | 848 MB |
| Animations | 1.0× | 0.5×, kept after reboots |
| Remote app buttons (gear, YouTube, Prime, Play, Apps) | worked | dead after removing the stock home, then **back** with TV Keys, and a held volume key still ramps |
| Prime Video ads | loud | **muted** while they play, sound back the moment they end (nothing skipped) |
| SonyLIV ads | shown | episodes: **gone** (the DNS filter blocks the ad request); live matches: **muted** while they play, except breaks filled with the broadcaster's own ads |
| Ad and tracker domains | resolve | blocked (AdGuard Private DNS) |

Screenshots and the per-process numbers: [`toshiba-55m550lp/before-after.html`](toshiba-55m550lp/before-after.html)
(download and open it).

## Doing this on your TV

**Read [CLAUDE.md](CLAUDE.md).** It is the full guide: what applies to which TV, the rules, and step-by-step for
each part, including how to find your own remote's key codes and check the ad signal. Any assistant you use, or you,
can follow it. Your TV must run Android TV / Google TV; Samsung, LG, Roku and VIDAA sets can't run any of this.

| Folder | What |
|---|---|
| [`toshiba-55m550lp/`](toshiba-55m550lp/) | The original TV: every change with its exact undo, results, debloat list, theme, benchmarks |
| [`tv-keys/`](tv-keys/) | TV Keys, a 25 KB Android app (no internet permission): remote buttons without a key filter, and ad mute for Prime Video and SonyLIV live matches |
| [`cleanyourtv-launcher-patch/`](cleanyourtv-launcher-patch/) | Small changes to the CleanYourTV launcher (animation scale fix, PS5 tile) |

## Credits

- [CleanYourTV](https://github.com/Gathin23/CleanYourTV) (MIT) did the clean-up and provides the launcher. The idea
  of reading the window manager's key log comes from its TCL G10 device note.
- The wallpapers in `toshiba-55m550lp/theme-nature-calm/` are from Wikimedia Commons under CC BY / CC BY-SA; authors
  and licences are in its `CREDITS.md`.
