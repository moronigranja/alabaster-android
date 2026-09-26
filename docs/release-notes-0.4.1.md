# Alabaster Dawn Android port v0.4.1 — release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access): the game runs in an Android WebView with no Wine, no Box64 and
no NW.js, reading **your own** game files from a folder you pick, playing through a physical
controller read natively from Android's `InputDevice` **or the built-in on-screen pad**, and saving
to a second folder you pick — in the Steam build's own save layout.

**No game files are included in this APK or in this repository.** You need your own legitimately
purchased copy of the game.

## What's new since v0.4

A fix for the diagnostics, not for the game — v0.4 shipped the record, and the first two reports from
real devices showed why a record is not enough on its own. Both panels arrived **empty of the
failure**: `assets: none served yet` and a two-line log, because the panel only covers the launch it
was opened in, and a frozen boot forces you to restart before you can read it. That is now fixed:

* **Restarting no longer loses the record.** `ada-diagnostics.log` in your saves folder is read back
  when the app starts, and its newest lines are shown above this launch's, behind
  `--- previous session, carried from ada-diagnostics.log ---`. So: freeze, force-stop, reopen,
  **Diagnostics** — the panel now shows what the frozen run was doing, and the file keeps it too.
* **A stall while the game's files are being read is recorded as well.** The log file used to start
  being written only once the game's page began loading; it now starts the moment you tap **START**.
  If the port hangs while reading your game folder ("freezes when trying to put gamefiles"), the file
  will hold `indexing …` and nothing after it — which names the problem instead of leaving a blank.
* **The record stays bounded.** The carried part is 199 of the 400 lines (half the ring), so a long
  session's own lines eventually scroll the older ones out; send the file or screenshot soon after
  the failure.

If a boot still freezes, the most useful thing you can send is that `ada-diagnostics.log` file (from
the **saves** folder you picked — rename it to `.txt` if a `.log` attachment is awkward), or a
screenshot of **Back → Diagnostics** / the setup screen's **Diagnostics** right after it happens.

## Everything else is v0.4

* **Diagnostics without a PC**: device, WebView version and capabilities, GL backend, folders, asset
  counters, and a log that names the failure — the shader that would not compile, the resources still
  unfinished when the bar stopped (grouped by kind, with the audio-decode counts), or the file-system
  call the page's thread is stuck in. Repeated per-frame lines collapse to `(xN)` so the cause is
  still in the record; **Share** hands the whole text to any app; everything is on logcat too.
* **A switch to keep the log file**, on by default and off by itself after the first boot that
  completes (your choice, once made, is kept).
* **Side menu on Back** (v0.3/v0.4): overlay hiding with a controller, picture position
  (Top / Center / Bottom), the FPS/battery/temperature readout, the log switch, status lines, Exit.
* **On-screen pad and layout editor** (v0.2), **native controller input**, **saves in the Steam
  layout**, **resolution ladder** `640x360 / 960x540 / 1280x720 / 1920x1080 / 2560x1440`, **no
  permissions**, and leaving the app pauses the game (v0.2).
* Rewritten assets (the 12.7 MB bundle's ladder, the option labels, the patched shaders) are served
  from memory after the first request, so a boot that reloads does not re-read them.

## Install

* **Android 8.0+ (API 26).** No native libraries, so any ABI works; tested on arm64 hardware
  (Snapdragon 8 Gen 1 / 8 Elite) and on x86_64 emulators.
* Unminified signed release build, **≈1.1 MB**, signed with the same certificate as v0.1–v0.4 —
  upgrading from any of them is an in-place install (a *debug* build needs an uninstall first).
* There is no in-app update check: watch this repository's Releases page.

## First run

1. Put the game on the phone (the folder you pick must contain a `terra/` child).
2. **CHOOSE GAME FILES** → that folder. **CHOOSE SAVES FOLDER** → any folder you like (a desktop
   `Saves/` folder copied in works as-is).
3. **START**. The first launch indexes the tree (~2 650 files, a few seconds); afterwards both folders
   are remembered and it is one tap.

## Known limitations

Unchanged from [v0.4](release-notes-0.4.md): starting the game twice in the same app process can come
up black (swipe the app away from recents first), the pad's L3/R3 clicks are not exposed,
performance is GPU-bound (`640×360` is the safe default), the readout's temperature is the battery's,
the in-game Load list has not been eyeballed, and the port's patches are anchored to strings in the
game's bundle (a game update makes the shim log `unmodified` and serve the original file rather than
break the game). The desktop controller fix in `fix/` is not part of this APK.

Two new caveats about the record itself:

* The on-disk log is flushed at most every 2 seconds, so the last couple of seconds before a
  force-stop can be missing; the panel's **Share** is the exact, complete copy.
* The carried (previous-session) part is the newest 199 lines and is trimmed away as the new session
  fills the ring — it is there to make a restart useful, not to keep history.

## Licenses and attribution

The port's own code is **MIT** — see [`LICENSE`](../LICENSE) and [`NOTICE.md`](../NOTICE.md) in the
repository. Both files also ship **inside this APK** as `assets/LICENSE` and `assets/NOTICE.md`, so
the binary carries the notices it is distributed under.

Alabaster Dawn, its artwork, audio, text, fonts, data, code and the `terra` engine are
© Radical Fish Games; "Alabaster Dawn" and "CrossCode" are trademarks of their owners, used here
descriptively. This project is not affiliated with, sponsored by, or endorsed by Radical Fish
Games, and ships no part of the game. AndroidX WebKit and the Gradle wrapper (used to build) are
Apache-2.0; CrossAndroid is credited as prior art for running this engine on Android — that project
declares no license, so no code was taken from it.
