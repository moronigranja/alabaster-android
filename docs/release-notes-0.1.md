# Alabaster Dawn Android port v0.1 — release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access): the game runs in an Android WebView with no Wine, no Box64 and
no NW.js, reading **your own** game files from a folder you pick, playing through a physical
controller read natively from Android's `InputDevice`, and saving to a second folder you pick — in
the Steam build's own save layout.

**No game files are included in this APK or in this repository.** You need your own legitimately
purchased copy of the game.

## What's in this first release

* **Boots the unmodified game to its title screen.** Nothing in your game folder is ever edited:
  the port injects its shim at document start and serves the files in-process.
* **Native controller input.** Left/right stick, d-pad, face buttons, shoulders, analog and digital
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
  has only been *tested* on arm64 (Snapdragon 8 Gen 1 / Adreno 730).
* Unminified signed release build, **≈1.0 MB**. Allow "install unknown apps" for your browser or
  file manager, then open the downloaded APK.
* Installing over a **debug** build of this app needs an uninstall first — the debug key differs
  from the release key by design. Installing over v0.1 itself upgrades in place.
* There is no in-app update check: watch this repository's Releases page.

### Verify the download

The APK attached to this release:

```
sha256  c7d18f3afd5e9534da9bff2122ab898d5c335228a522ec956209b8d9b5528885
```

Check it with `sha256sum AlabasterDawn-Android-0.1.apk`, and confirm the signer is this project's
own release certificate (never the debug key, never another app's key):

```
Signer #1 certificate DN: CN=Alabaster Dawn Android port, O=moronigranja, C=BR
Signer #1 certificate SHA-256 digest: ab31dd8874bf28fb06c783c8df0d3f6abeec719240165605ce27070e676b9604
```

`apksigner verify --print-certs AlabasterDawn-Android-0.1.apk` prints the same fingerprint, and
`keytool -printcert -jarfile AlabasterDawn-Android-0.1.apk` works without the Android SDK.

## First run

1. Put the game on the phone (the folder you pick must contain a `terra/` child), e.g.
   `/sdcard/Download/AlabasterDawn/terra/…`.
2. **CHOOSE GAME FILES** → that folder. **CHOOSE SAVES FOLDER** → any folder you like (a desktop
   `Saves/` folder copied in works as-is).
3. **START**. The first launch indexes the tree (~2 650 files) and takes a few seconds; afterwards
   both folders are remembered and it is one tap.

## Known limitations in this build

* **No on-screen touch controls.** A controller is required in this milestone; touch input is the
  next piece of work.
* **Performance is GPU-bound.** Measured on a Galaxy S22 Ultra: 640×360 holds 60 fps at ~54 % GPU,
  960×540 holds 60 fps at ~91 %, 1280×720 saturates the GPU (38–42 fps cool, 19–21 fps once the
  phone is hot). `640×360` is the safe default; heat, not CPU, is the limit — don't charge while
  playing.
* **The in-game Load list has not been eyeballed** (it needs a manual save made on the device).
  Everything underneath it is verified: file naming, the `Default → Backups → Backups2` rotation,
  save metadata and timestamps.
* **No launcher icon yet** — the app uses the system default.
* **One device tested.** The shim carries workarounds for the WebView GL stack it met there
  (missing `OES_draw_buffers_indexed`, an Adreno-rejected dead shader attribute). Other GPUs may
  need their own; if the port misbehaves, `adb logcat -s AdaPort:*` shows what the shim did.
* **Built against the current game build** ("0.1.0-10 Early Access"). The port's patches are
  anchored to specific strings in the game's bundle: if a game update changes them, the shim logs
  `unmodified` and serves the original file rather than breaking the game — but the port may then
  need updating.
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
