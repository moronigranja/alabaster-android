# Alabaster Dawn Android port v0.4 — release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access): the game runs in an Android WebView with no Wine, no Box64 and
no NW.js, reading **your own** game files from a folder you pick, playing through a physical
controller read natively from Android's `InputDevice` **or the built-in on-screen pad**, and saving
to a second folder you pick — in the Steam build's own save layout.

**No game files are included in this APK or in this repository.** You need your own legitimately
purchased copy of the game.

## What's new since v0.3

This is the release for a boot that freezes. A device-only failure in a port like this is silent by
nature — no crash, no log, just the engine's loading bar stopped at a few percent, on a device
nobody here owns (the report that started this was a frozen bar on an **AYN Odin 3**, issue #1). So
the port now keeps its own record of the boot, and v0.4 is mostly that record and the work of making
it survive the situation it exists for.

* **Diagnostics, without a PC.** **Diagnostics** on the setup screen, or **Back → Diagnostics** while
  the game runs — including while the bar is stuck, which is exactly when it matters. The panel
  shows:
  * a header with the device and Android version, the **WebView package and version**, whether it
    supports document-start scripts, which **GL backend** the page actually got, the picked folders,
    the asset-path counters (`served / missed / inFlight / slowest`) and how long the engine has been
    quiet;
  * the log below it, which names the failure instead of leaving you guessing: the shader that would
    not compile, the resources still **unfinished when the bar stopped** (grouped by kind — effect /
    shader / data / audio … — with the audio-decode callback counts), or the **file-system call the
    page's own thread is stuck in** when the page, not the assets, is what died;
  * **Share**, which hands the whole record to any installed app as text and also writes it next to
    the app as `diagnostics-<epoch>.txt`. A screenshot of the panel is already a usable report.
  * The side menu shows the last thing the engine reported inline, so a stuck boot announces itself
    without opening anything, and everything in the record is on logcat too (`adb logcat -s AdaPort:I`).
* **A line the engine repeats every frame is collapsed.** While it is stuck, the engine throws the
  same error once per frame, and the record is bounded — so the lines that name the cause used to be
  pushed out by the noise before anyone could open the panel. Now the repeated line becomes
  `… There are unwrapped loadTrackers (x412)` in place, with the newest timestamp, and the cause is
  still in the record. logcat keeps every raw line.
* **The record is kept as a file, so it survives a force-stop.**
  `ada-diagnostics.log` at the root of your saves folder is the same text the panel shows,
  overwritten every 2 seconds while it is dirty. The side menu's new switch, **Keep a log file with
  the saves**, controls it: **on by default**, and it switches itself off after the first boot that
  *completes* — the file is for the boots that fail — unless you touch the switch, in which case your
  choice is kept for good. Nothing ever deletes an existing log, and nothing is written into your
  game folder.
* **Re-served assets cost nothing the second time.** The 12.7 MB `bundle.js`, the option labels and
  the patched shaders are rewritten once and then served from memory instead of being read from
  storage and rewritten again; a fragment shader's paired vertex shader is read once per app run
  rather than once per request. Measured on a stuck boot, a page reload went from a 510 ms storage
  read of the bundle to a memory hit (`; rewrite cached=40 hits=40` in the header).
* **Twenty-one new unit tests (67 total)** and a Node test for the shim's diagnostic half
  (`node android/tools/test-shim-diagnostics.mjs`, 12 checks, no device needed).

## What's in this release

* **Boots the unmodified game to its title screen.** Nothing in your game folder is ever edited: the
  port injects its shim at document start and serves the files in-process.
* **Native controller input.** Left/right stick, D-pad, face buttons, shoulders, analog and digital
  triggers, start/select — the pad is emitted to the engine in W3C-standard order, so the mapping bug
  the desktop build has on Linux/Wine cannot occur here.
* **On-screen pad and layout editor** (v0.2): every control drawn over the game, fed into the same
  standard pad state, with a `PAD`/`HIDE` pill, an `EDIT` pill that opens the drag-and-resize editor,
  and the layout kept in the app and in `pad-layout.json` at the root of your saves folder.
* **Side menu on Back** (v0.3): whether a controller in use hides the overlay, the picture position
  (Top / Center / Bottom) inside the black letterbox bands, an FPS / battery / temperature readout,
  the controller and saves status lines, the last engine report, and Exit — plus v0.4's log switch.
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

* **Android 8.0+ (API 26).** The APK contains no native libraries, so any ABI works — although it has
  only been *tested* on arm64 (Snapdragon 8 Gen 1 / Adreno 730, and Snapdragon 8 Elite / Adreno 830
  on the Fold 7) and on x86_64 emulators.
* Unminified signed release build, **≈1.1 MB**. Allow "install unknown apps" for your browser or
  file manager, then open the downloaded APK.
* Installing over a **debug** build of this app needs an uninstall first — the debug key differs from
  the release key by design. Installing over v0.1, v0.2 or v0.3 upgrades in place.
* There is no in-app update check: watch this repository's Releases page.

## First run

1. Put the game on the phone (the folder you pick must contain a `terra/` child), e.g.
   `/sdcard/Download/AlabasterDawn/terra/…`.
2. **CHOOSE GAME FILES** → that folder. **CHOOSE SAVES FOLDER** → any folder you like (a desktop
   `Saves/` folder copied in works as-is).
3. **START**. The first launch indexes the tree (~2 650 files) and takes a few seconds; afterwards
   both folders are remembered and it is one tap.

The pad is drawn over the game as soon as it starts. Tap `EDIT` to move and resize its controls; tap
`HIDE`/`PAD` to keep it out of the way; either choice is remembered. **Press Back** for the side menu
(the overlay switch, the picture position, the readout, the log switch, Exit).

If the loading bar ever stops, **Back → Diagnostics** is the report to send — or just send the
`ada-diagnostics.log` that will be sitting in your saves folder.

## Known limitations in this build

* **The on-disk log is a best-effort record, flushed at most every 2 seconds.** The last couple of
  seconds before a force-stop, a crash or a battery pull can be missing from the file. The panel's
  **Share** is the exact, complete copy: use it when the device is still alive.
* **Starting the game twice in the same app process can come up black.** If you leave the game
  (Back at the side menu, or Exit) and start it again *without* clearing the app from the recents
  screen, the second boot can stop before the title screen with an unresponsive picture. This predates
  this release — it reproduces on the v0.2 build as well — and the workaround is to swipe the app away
  from recents (or force-stop it) before starting again, which always boots. Tracked as a
  WebView-recreation issue, not a game or save issue: your files are untouched either way.
* The on-screen pad's stick **clicks** (L3/R3) are not exposed, and its multi-finger handling has unit
  tests but no real two-thumb pass on a phone yet.
* **Performance is GPU-bound.** Measured on a Galaxy S22 Ultra: 640×360 holds 60 fps at ~54 % GPU,
  960×540 holds 60 fps at ~91 %, 1280×720 saturates the GPU (38–42 fps cool, 19–21 fps once the phone
  is hot). `640×360` is the safe default; heat, not CPU, is the limit — don't charge while playing.
* **The readout's temperature is the battery's**, which is the only one an unprivileged app can read;
  the SoC zones (`AP`, `PA`) the system caches are not readable from an app. The throttle signal is
  therefore the platform's own thermal status word, and the numbers are sampled at most every 5 s (the
  frame rate and the resolution are per-frame and per-paint). The frame rate is the engine's own loop
  rate, which is display-paced: 60 on a 60 Hz mode, and it drops with the GPU load.
* **The in-game Load list has not been eyeballed** (it needs a manual save made on the device).
  Everything underneath it is verified: file naming, the `Default → Backups → Backups2` rotation, save
  metadata and timestamps.
* **Built against the current game build** ("0.1.0-10 Early Access"). The port's patches are anchored
  to specific strings in the game's bundle: if a game update changes them, the shim logs `unmodified`
  and serves the original file rather than breaking the game — but the port may then need updating.
* **The diagnostics panel names failures; it does not fix them.** A stall whose resources are still
  pending is a report worth sending, not something this build can work around.
* **One layout for every window and orientation.** Offsets are stored relative to the pad's own unit,
  so the same layout is re-derived in portrait and landscape; there is no separate per-orientation
  layout.
* **The desktop controller fix is not part of this APK** — it is for the Linux/Wine desktop build and
  lives in `fix/` in the repository.

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
