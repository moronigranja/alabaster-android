# Alabaster Dawn Android port v0.5 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are
in the [README](../README.md); the terse changelog is [docs/release-0.5.md](release-0.5.md).

## What's new since v0.4.2

### Exiting the game actually exits

On the title screen, choosing **Exit** used to stop the picture with the game's own menu already gone.
The engine has only two ways out — the title screen's EXIT calls `nw.Window.get().close()` 300 ms later,
and `System.quit()` calls `nw.App.quit()` — and the port's shim had both stubbed out as no-ops. The
engine tore its own menu down and then waited for a process exit that never came, so the screen just
stopped responding. Both now leave the game, exactly like the port's own **Exit**.

### Every launch is a fresh start

**Exit** — the game's or the port's — now ends the app's process rather than just the screen. That
matters because the WebView's renderer is shared for the life of the app process, and starting the game
a second time in the same process could come up black before the title screen (the workaround used to be
to swipe the app away from recents). With the process ending on Exit, the next launch is a new process,
a new renderer and a clean GL state: **the old "swipe it from recents first" workaround is gone.**

Your diagnostics record is written out before the process ends, so nothing is lost.

### The side menu is a proper settings list

The side menu (Back while the game runs) is now one uniform, full-width, icon-led list — the layout the
Eden / Sudachi / Azahar emulators use: every entry the same width and height, an icon in a fixed left
gutter, the label on a single line, the control at the right edge, thin dividers between rows, and the
**whole row** is the tap target instead of just the label. The current **Game position** is a filled
pill.

### Your versions, where you can see them

* The **port's** version now shows on the setup screen and in the side menu's status block, next to the
  existing diagnostics header line — so a screenshot says which build it came from.
* The record now also says which build of the **game** your files are. The game's own `package.json`
  only carries a placeholder, so the port reads the two real sources: the newest release from
  `terra/data/database/changelog.json` as soon as your folder is indexed (`game 0.1.0`), and the engine's
  own inlined build once `bundle.js` is served, which is the only place the hotfix lives
  (`game 0.1.0-10 Early Access`). An Early Access title ships often, and a moved asset or a changed
  bundle is otherwise indistinguishable from a port bug.

## Verified

On the Android 14 emulator with the host GPU (`-gpu host`), then on the shipped **release** APK:
driving the engine's own exit over CDP (`nw.Window.get().close()`, the exact call the title screen's
EXIT makes) and both UI exits — the side menu's **Exit** and the game's own title-screen **Exit**,
highlighted with the on-screen pad's D-pad and confirmed with **A** — each printed `exit requested`,
left no pid and **no task in recents**, and set up the next launch fresh: three consecutive launches
each reached `ENGINE boot: complete` with the title screen drawn and a new process id. The setup screen
and the side menu showed `port 0.5 (7)`, the diagnostics header read `game 0.1.0-10 Early Access`,
every menu row drew at one width/height with its icon in the same left gutter, tapping a row's *blank
left edge* toggled that row's switch, and with the menu left open the engine kept running (the WebView
never lost focus).

## Known limitations

* The on-screen pad's stick **clicks** (L3/R3) are not exposed, and its multi-finger handling has unit
  tests but no real two-thumb pass on a phone yet.
* Devices at the GLES3 vertex-uniform floor get **192 atlas slots per atlas** where the desktop game
  uses 256 (those are exactly the devices that could not boot at all before v0.4.2). If a scene ever
  needs more, the engine logs `ATLAS ERROR: Exceeded maximum TEX_SLOT_COUNT of …` into the record.
* The Android emulator's software GL stack (SwiftShader, `-gpu swiftshader_indirect`) draws the in-game
  map wrong — black tiles with purple/pink fragments — while the same build is correct on real hardware
  and on the same emulator with the host GPU (`-gpu host`). The port serves identical files and shaders
  either way, so it is the software renderer, not the port.
* Performance is GPU-bound; see the README's ledger before expecting 1080p.

## Everything else is v0.4.2

This release is v0.4.2 plus the changes above: the vertex-uniform fix for the frozen loading bar, the
diagnostics record (device, GL limits, the engine's own console errors, and the log file with the saves),
Back opening the menu, the scrolling side menu, the on-screen pad and its layout editor, controller
hiding the overlay, saves through a second picked folder in the Steam layout, the resolution ladder
`640x360 / 960x540 / 1280x720 / 1920x1080 / 2560x1440`, pausing when the app leaves the foreground,
immersive fullscreen and renderer-crash recovery. Full notes:
[v0.4.2](https://github.com/moronigranja/alabaster-android/releases/tag/v0.4.2).
