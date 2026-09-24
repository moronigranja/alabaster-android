# Alabaster Dawn Android port v0.2 — release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access): the game runs in an Android WebView with no Wine, no Box64 and
no NW.js, reading **your own** game files from a folder you pick, playing through a physical
controller read natively from Android's `InputDevice` **or the built-in on-screen pad**, and saving
to a second folder you pick — in the Steam build's own save layout.

**No game files are included in this APK or in this repository.** You need your own legitimately
purchased copy of the game.

## What's new since v0.1

* **The on-screen pad.** The game is now playable by touch with no controller at all: both sticks,
  D-pad, A/B/X/Y, L1/R1, L2/R2, Select/Start/HOME, drawn over the game and fed into the *same*
  W3C-standard pad state the hardware controller drives, so the engine still sees exactly one
  standard pad. A small always-tappable pill in the top row hides and shows it (the choice is kept),
  and the controls hide themselves while a real controller is in use.
* **A layout editor for the pad.** An `EDIT` pill next to the toggle opens it: drag any control to
  move it, drag the selected control's corner handle to resize just that control, use `-`/`+` to
  size the whole pad, `RESET` to restore the stock layout (`UNDO` while that reset is still
  unsaved), and `DONE` to save and leave. The layout is kept in the app and, when you have picked a
  saves folder, also written to `pad-layout.json` at its root — that copy wins on load, so the
  layout travels with the saves folder.
* **Leaving the app now pauses the game.** An Android WebView never dispatches the page's
  `blur`/`focus`, so the port dispatches them itself on `onPause`/`onResume`: the music stops and
  the loop stops burning CPU when you switch away, and both come back when you return.

## What's in this release

* **Boots the unmodified game to its title screen.** Nothing in your game folder is ever edited:
  the port injects its shim at document start and serves the files in-process.
* **Native controller input.** Left/right stick, D-pad, face buttons, shoulders, analog and digital
  triggers, start/select — the pad is emitted to the engine in W3C-standard order, so the
  mapping bug the desktop build has on Linux/Wine cannot occur here.
* **Saves in the Steam layout.** `Saves/Default/Save_ID_0000.save`, `Saves/Default/System.save`,
  `Saves/Backups/`, `Saves/Backups2/` — so a desktop save folder copies in, and saves copy back out
  and load in the Steam build.
* **No permissions.** Both folders are reached through Android's Storage Access Framework grants,
  which you give by picking them; nothing is requested in the manifest.
* **Resolution ladder** in the in-game Options → GRAPHICS (this port relabels the desktop option):
  `640x360 / 960x540 / 1280x720 / 1920x1080 / 2560x1440`.
* Immersive fullscreen, screen kept awake, and a WebView renderer crash is recovered by rebuilding
  the view instead of closing the app.

## Install

* **Android 8.0+ (API 26).** The APK contains no native libraries, so any ABI works — although it
  has only been *tested* on arm64 (Snapdragon 8 Gen 1 / Adreno 730) and on x86_64 emulators.
* Unminified signed release build, **≈1.0 MB**. Allow "install unknown apps" for your browser or
  file manager, then open the downloaded APK.
* Installing over a **debug** build of this app needs an uninstall first — the debug key differs
  from the release key by design. Installing over v0.1 or v0.2 upgrades in place.
* There is no in-app update check: watch this repository's Releases page.

## First run

1. Put the game on the phone (the folder you pick must contain a `terra/` child), e.g.
   `/sdcard/Download/AlabasterDawn/terra/…`.
2. **CHOOSE GAME FILES** → that folder. **CHOOSE SAVES FOLDER** → any folder you like (a desktop
   `Saves/` folder copied in works as-is).
3. **START**. The first launch indexes the tree (~2 650 files) and takes a few seconds; afterwards
   both folders are remembered and it is one tap.

The pad is drawn over the game as soon as it starts. Tap `EDIT` to move and resize its controls;
tap `HIDE`/`PAD` to keep it out of the way; either choice is remembered.

## Known limitations in this build

* The on-screen pad's stick **clicks** (L3/R3) are not exposed, and its multi-finger handling has
  unit tests but no real two-thumb pass on a phone yet.
* **Performance is GPU-bound.** Measured on a Galaxy S22 Ultra: 640×360 holds 60 fps at ~54 % GPU,
  960×540 holds 60 fps at ~91 %, 1280×720 saturates the GPU (38–42 fps cool, 19–21 fps once the
  phone is hot). `640×360` is the safe default; heat, not CPU, is the limit — don't charge while
  playing.
* **The in-game Load list has not been eyeballed** (it needs a manual save made on the device).
  Everything underneath it is verified: file naming, the `Default → Backups → Backups2` rotation,
  save metadata and timestamps.
* **One device tested.** The shim carries workarounds for the WebView GL stack it met there
  (missing `OES_draw_buffers_indexed`, an Adreno-rejected dead shader attribute). Other GPUs may
  need their own; if the port misbehaves, `adb logcat -s AdaPort:*` shows what the shim did.
* **Built against the current game build** ("0.1.0-10 Early Access"). The port's patches are
  anchored to specific strings in the game's bundle: if a game update changes them, the shim logs
  `unmodified` and serves the original file rather than breaking the game — but the port may then
  need updating.
* **One layout for every window and orientation.** Offsets are stored relative to the pad's own
  unit, so the same layout is re-derived in portrait and landscape; there is no separate per-
  orientation layout.
* **The desktop controller fix is not part of this APK** — it is for the Linux/Wine desktop build
  and lives in `fix/` in the repository.

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
