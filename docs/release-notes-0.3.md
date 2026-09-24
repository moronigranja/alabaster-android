# Alabaster Dawn Android port v0.3 — release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access): the game runs in an Android WebView with no Wine, no Box64 and
no NW.js, reading **your own** game files from a folder you pick, playing through a physical
controller read natively from Android's `InputDevice` **or the built-in on-screen pad**, and saving
to a second folder you pick — in the Steam build's own save layout.

**No game files are included in this APK or in this repository.** You need your own legitimately
purchased copy of the game.

## What's new since v0.2

* **A side menu, opened with Back.** Pressing Back while the game runs used to leave the app; it now
  opens a panel over the picture — the game keeps running behind it — carrying everything you
  previously had to relaunch to change:
  * **Hide controls with a controller** — the switch for the behaviour that shipped in v0.2: while a
    controller is in use the whole on-screen overlay (controls *and* the `PAD`/`EDIT` pill row) hides
    itself and comes back a minute after the last controller event. Turn the switch off and the
    overlay stays put for a controller player.
  * **Game position — Top / Center / Bottom.** Where the game picture sits inside the black
    letterbox bands on a screen that is not 16:9. On the Fold 7 (2448×1848) the measured picture rows
    are `236…1612` for Center (the default), `0…1376` for Top and `471…1847` for Bottom.
  * **Show FPS / battery / temperature.** A small readout at the picture's top-left:
    `60 fps · 1280x720 · bat 68% 35.5°C · therm moderate` — the engine's frame rate, the render
    resolution (the same number the game's Resolution option sets), the battery level and its
    temperature, and the platform's thermal status. The battery numbers are checked against the
    system's own (`dumpsys battery` level and temperature agree; the thermal word is
    `PowerManager.getCurrentThermalStatus()`).
  * **Two status lines**: whether a controller is being used right now, and where saves are actually
    going (your saves folder's name, or `app storage (not exportable)` when none is picked).
  * **Exit**, so you can leave the game without killing the app from the recents screen.
  * Back again, a tap on the dimmed area, or **Exit** closes it. All three settings are stored in the
    app's own preferences: nothing is written into the game folder or into the saves tree, and the
    pad's layout is untouched.
* **The pill row hides with the controls.** In v0.2 the on-screen pad's controls hid while a
  controller was in use but its two pills stayed; now the whole overlay goes (and a tap where the
  pills were is no longer swallowed).
* **Three new unit tests** (46 total): the menu's position value and its wire format.

## What's in this release

* **Boots the unmodified game to its title screen.** Nothing in your game folder is ever edited:
  the port injects its shim at document start and serves the files in-process.
* **Native controller input.** Left/right stick, D-pad, face buttons, shoulders, analog and digital
  triggers, start/select — the pad is emitted to the engine in W3C-standard order, so the
  mapping bug the desktop build has on Linux/Wine cannot occur here.
* **On-screen pad and layout editor** (v0.2): every control drawn over the game, fed into the same
  standard pad state, with a `PAD`/`HIDE` pill, an `EDIT` pill that opens the drag-and-resize editor,
  and the layout kept in the app and in `pad-layout.json` at the root of your saves folder.
* **Saves in the Steam layout.** `Saves/Default/Save_ID_0000.save`, `Saves/Default/System.save`,
  `Saves/Backups/`, `Saves/Backups2/` — so a desktop save folder copies in, and saves copy back out
  and load in the Steam build.
* **No permissions.** Both folders are reached through Android's Storage Access Framework grants,
  which you give by picking them; nothing is requested in the manifest.
* **Resolution ladder** in the in-game Options → GRAPHICS (this port relabels the desktop option):
  `640x360 / 960x540 / 1280x720 / 1920x1080 / 2560x1440`.
* **Leaving the app pauses the game** (v0.2): music stops and the loop stops burning CPU, and both
  come back when you return.
* Immersive fullscreen, screen kept awake, and a WebView renderer crash is recovered by rebuilding
  the view instead of closing the app.

## Install

* **Android 8.0+ (API 26).** The APK contains no native libraries, so any ABI works — although it
  has only been *tested* on arm64 (Snapdragon 8 Gen 1 / Adreno 730, and Snapdragon 8 Elite /
  Adreno 830 on the Fold 7) and on x86_64 emulators.
* Unminified signed release build, **≈1.1 MB**. Allow "install unknown apps" for your browser or
  file manager, then open the downloaded APK.
* Installing over a **debug** build of this app needs an uninstall first — the debug key differs
  from the release key by design. Installing over v0.1, v0.2 or v0.3 upgrades in place.
* There is no in-app update check: watch this repository's Releases page.

## First run

1. Put the game on the phone (the folder you pick must contain a `terra/` child), e.g.
   `/sdcard/Download/AlabasterDawn/terra/…`.
2. **CHOOSE GAME FILES** → that folder. **CHOOSE SAVES FOLDER** → any folder you like (a desktop
   `Saves/` folder copied in works as-is).
3. **START**. The first launch indexes the tree (~2 650 files) and takes a few seconds; afterwards
   both folders are remembered and it is one tap.

The pad is drawn over the game as soon as it starts. Tap `EDIT` to move and resize its controls;
tap `HIDE`/`PAD` to keep it out of the way; either choice is remembered. **Press Back** for the side
menu (position, the two switches, the readout, Exit).

## Known limitations in this build

* **Starting the game twice in the same app process can come up black.** If you leave the game
  (Back at the side menu, or Exit) and start it again *without* clearing the app from the recents
  screen, the second boot can stop before the title screen with an unresponsive picture. This
  predates this release — it reproduces on the v0.2 build as well — and the workaround is to swipe
  the app away from recents (or force-stop it) before starting again, which always boots. Tracked as
  a WebView-recreation issue, not a game or save issue: your files are untouched either way.
* The on-screen pad's stick **clicks** (L3/R3) are not exposed, and its multi-finger handling has
  unit tests but no real two-thumb pass on a phone yet.
* **Performance is GPU-bound.** Measured on a Galaxy S22 Ultra: 640×360 holds 60 fps at ~54 % GPU,
  960×540 holds 60 fps at ~91 %, 1280×720 saturates the GPU (38–42 fps cool, 19–21 fps once the
  phone is hot). `640×360` is the safe default; heat, not CPU, is the limit — don't charge while
  playing.
* **The readout's temperature is the battery's**, which is the only one an unprivileged app can
  read; the SoC zones (`AP`, `PA`) the system caches are not readable from an app. The throttle
  signal is therefore the platform's own thermal status word, and the numbers are sampled at most
  every 5 s (the frame rate and the resolution are per-frame and per-paint). The frame rate is the
  engine's own loop rate, which is display-paced: 60 on a 60 Hz mode, and it drops with the GPU load.
* **The in-game Load list has not been eyeballed** (it needs a manual save made on the device).
  Everything underneath it is verified: file naming, the `Default → Backups → Backups2` rotation,
  save metadata and timestamps.
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
