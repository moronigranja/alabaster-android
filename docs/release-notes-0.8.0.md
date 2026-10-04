# RadicalFish Ports v0.8.0 - release notes

An **unofficial**, non-commercial Android port of two Radical Fish Games NW.js titles, run from the copy
you already own — [Alabaster Dawn](https://store.steampowered.com/app/3110760/) (Early Access) and
[CrossCode](https://store.steampowered.com/app/368340/). Install, requirements and the legal notes are in
the [README](../README.md); the terse changelog is [docs/release-0.8.0.md](release-0.8.0.md); the
technical record is `FINDINGS.md` §13 (the CrossCode probe), §15 (picture alignment) and §23 (the
CrossCode loader and saves).

**One app, two games.** The port is no longer Alabaster Dawn only. `GameProfile` picks the game from the
folder you point at — the engine's entry page is the fingerprint, nothing is stored — and each game gets
its own compatibility shim (`ada-shim.js`, `cc-shim.js`). The display name is now **RadicalFish Ports**;
the application id (`io.github.moronigranja.alabasterdawn`) and signing key are unchanged, so a build of
an earlier release updates in place and its folders and settings are kept.

**CrossCode runs.** The second game is Cubic Impact 0.5, a different engine from Alabaster Dawn's `terra`
— canvas 2D, the old `nw.gui` API, and a save path list built from `nw.gui.App.dataPath`. It boots to
its title screen in the desktop Chromium harness, and **on a phone**: a Galaxy Z Fold 7 (Android 17)
drew it at 60 fps with the port's facts line reading `game=CrossCode; canvas=1136x640;
window=475x751@2.625; platform=Desktop`, and the engine wrote `Default/cc.save` into the picked saves
folder. Still open, and said so by the port's own record: audio, the pad mapping in-game, the heavier
scenes' GPU cost, and a save carried back to a desktop install.

**The entry screen is two cards.** Both games are on the screen at once, each a row card holding its own
title art (read from your copy at runtime, never bundled), both folder paths, a play badge and a **⋮**
menu. Each game keeps **its own saves folder** and its own log file and pad layout, so switching games
switches all of it. A folder that has been moved or deleted is marked (dimmed, `(missing)`, a tap
re-opens the picker) instead of being silently claimed. The card's **⋮** now offers **Help: what to
copy** (where the game and its saves live on your PC, and the file names to search for) and **Create a
home-screen link**; holding the app icon lists both games as launcher shortcuts. The folder walk is
cached between launches, keyed to the folder and the game's own version file.

**Saves travel both ways for both games.** The save folder keeps the desktop layout, and the port now
reads **either shape** you copy in: the game's own subfolder whole (`Saves/…` for Alabaster Dawn,
`Default/…` for CrossCode), or that subfolder's contents dropped flat into the picked folder — the port
serves the game's own paths at the root for you. Copying a saves folder back to the PC needs no renaming.
CrossCode keeps its options in the WebView's `localStorage`, as before.

**Keyboard for the Impact engine.** CrossCode's input layer indexes its bindings by the **legacy
`keyCode`/`which`**, which a synthetic `KeyboardEvent` cannot carry. The port now defines those on the
event it dispatches (while still carrying `code`/`key` for `terra`), so a Bluetooth keyboard drives
CrossCode.

**The picture-position control works for both engines.** The side menu's Top/Centre/Bottom moves the
canvas's *layout box*, and both engines' mouse maps read the canvas's own offset, so a click stays on the
picture-relative point it hit in every position.

**Dynamic sticks on the on-screen pad** (on by default, a switch in the side menu): each stick starts
where its screen half is touched — the ring and knob appear under the thumb and vanish when it lifts.
Off restores the fixed rings.

**A new launcher icon.** The mark is a circuit fish, drawn for this port in the adaptive-icon pipeline
(108dp, fills only) with the field in slate; it sits inside the 66dp safe circle so no launcher mask
crops it. Original work — no game art is bundled, as the `NOTICE.md` in the APK says.

**Verified**: 162 unit tests and 62 shim-harness checks pass; CrossCode boots to its title screen on a
**phone** (Galaxy Z Fold 7, Android 17 — 60 fps, canvas 1136×640, `platform=Desktop`) as well as in the
desktop harness, and the picture-position geometry holds on a 412×915 viewport in headless Chromium; the
launcher mark renders clean at the square/circle/squircle masks and at 48/24dp. **Not verified**: CrossCode
audio, the pad in-game, the heavier scenes' GPU cost, and a save carried back to a desktop — that is what
the next reports settle.

**Upgrading**: the same package and key, so install over the old build; your picked folders, saves and
settings are kept. Nothing game-related is downloaded or uploaded.
