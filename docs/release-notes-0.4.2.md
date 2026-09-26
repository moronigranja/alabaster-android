# Alabaster Dawn Android port v0.4.2 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access): the game runs in an Android WebView with no Wine, no Box64 and
no NW.js, reading **your own** copy of the game through Android's folder picker. No game file is ever
modified, the APK asks for no permissions, and it contains no game assets.

## What's new since v0.4.1 — the frozen loading bar is fixed

v0.4 and v0.4.1 could stop at a thin blue bar early in the load and never move again, on devices with a
lower vertex-shader uniform budget than a desktop GPU (`MAX_VERTEX_UNIFORM_VECTORS` = 256, which is
exactly what the GLES3 specification guarantees). The game declares a 256-entry texture-slot table in
every vertex shader, and that table alone used up that whole budget, so **the shaders never compiled**:
the engine logged `ERROR: too many uniforms`, its own `checkTrackers()` threw on every frame, and the
bar froze — with no crash and no message on screen, which is why this needed a diagnostics record to
find.

The port now measures the device's budget **before** the first shader is requested and serves a table
the device can actually compile:

* the game's own 256 slots wherever the device has the room (nothing is rewritten at all);
* otherwise 192 slots, with `gui.vert`'s second table (the font atlas) packed into `vec4`s so the atlas
  keeps 192 slots instead of the 96 the simplest fix would have allowed. 192 is the game's own desktop
  ceiling minus 64, the price of a smaller uniform budget.

Both the shaders and the engine's own constant are served with the same number, so nothing else in the
engine needs to know. Game files on your disk are never touched.

Verified on an Android 14 emulator whose GL stack is *hostile* to these shaders (SwiftShader): before the
change, 22 `An error occurred compiling the shader` lines and `no progress for 8917499ms at 12.0% of
1755 resources`; after it, zero shader errors and `ENGINE boot: complete in 6161ms, 1757 resources`, with
the title screen, menus, cutscene and in-game HUD drawing. Verified again on real hardware, the Galaxy
S22 Ultra (Android 16, WebView 153, Adreno 730): `gl limits: vertex uniforms 256 -> TEX_SLOT_COUNT 192`
and `ENGINE boot: complete in 9359ms, 1757 resources` — the same 256-vector floor as the affected
devices. Details and measurements: `FINDINGS.md` §10.9.

## Also new

* The diagnostics record now carries the device's own limits (`uniforms=256/261; varyings=32` in the
  facts line) and `gl limits: vertex uniforms … -> TEX_SLOT_COUNT …`, so a report says what the device
  had to work with.
* The engine's own `console.error` / `console.warn` lines are forwarded into the record. If a scene ever
  packs more spritesheets into one atlas than the served table holds, the engine's
  `ATLAS ERROR: Exceeded maximum TEX_SLOT_COUNT of …` will now be in your panel and log instead of
  silently dropping sprites.

## If it still does not boot

* Open **Diagnostics** (on the setup screen, or Back → Diagnostics in-game) and send the screenshot or
  the shared text; with the log switch on, `ada-diagnostics.log` inside your saves folder is the file to
  send. It now includes the shader/console errors above, the device's GL limits and the boot stage.
* The `uniforms=` value in the facts line tells whether your device is at the GLES3 floor.

## Everything else is v0.4

This release is v0.4 (and 0.4.1) plus the fix described above: on-screen pad and layout editor, pad
visible over the game, controller hiding the overlay, the Back side menu (game position, FPS/battery/
temperature readout, log switch, Exit), saves through a second picked folder in the Steam layout, the
resolution ladder `640x360 / 960x540 / 1280x720 / 1920x1080 / 2560x1440`, pausing when the app leaves
the foreground, immersive fullscreen and renderer-crash recovery. Full notes:
[v0.4](https://github.com/moronigranja/alabaster-android/releases/tag/v0.4).

## Install

1. Grab `AlabasterDawn-Android-0.4.2.apk` from
   [Releases](https://github.com/moronigranja/alabaster-android/releases) (allow "install unknown apps"
   for your browser or file manager).
2. Install it over any earlier version — every release is signed by the same certificate, so it upgrades
   in place and your picked folders, pad layout and settings are kept.
3. Start it, then pick your **game folder** (the one containing `terra/`) and a **saves folder** (any
   folder you own, e.g. `Download/AlabasterDawn-saves`), and press **START**.

Verify the download if you want to:

```bash
apksigner verify --print-certs AlabasterDawn-Android-0.4.2.apk   # no SDK? keytool -printcert -jarfile …
```

The release certificate is `CN=Alabaster Dawn Android port, O=moronigranja, C=BR`, SHA-256
`ab31dd8874bf28fb06c783c8df0d3f6abeec719240165605ce27070e676b9604` — the same one as 0.1 through 0.4.1.

## Known limitations

Same as v0.4, plus one introduced by the fix on devices at the GLES3 uniform floor: those devices get
**192 atlas slots per atlas** where the desktop game uses 256. If a scene ever needs more, the engine
logs the `ATLAS ERROR` line named above and drops sprites for that frame; the affected devices are
exactly the ones that could not boot at all before.

## Licenses and attribution

This port is **unofficial** and non-commercial, and ships **no game code or assets** — it serves the
folder you pick at runtime. Alabaster Dawn (game, art, audio, code) is © Radical Fish Games. The port's
own code is MIT (see `LICENSE`); the shim, the on-screen pad and the Gradle wrapper (used to build) are
Apache-2.0; CrossAndroid is credited as prior art for running this engine on Android — that project
declares no license, so no code was taken from it.
