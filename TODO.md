# TODO

## Next version

Nothing ships per change (README, "Publishing a signed release"): these items accumulate and go out
together, when the queue is worth asking somebody to download.

* **CrossCode mods — the two gaps left by the 0.9.0 library** (`FINDINGS.md` §26.6, §27.4).
  * The **up-front compatibility gate**: a mod CCLoader itself will refuse still installs today, and
    the reason appears only in CCLoader's own console. It should be refused at install time, with the
    reason (§26.6's CCLoader-3 manifest / `ccloader`-version rules).
  * A **mod catalogue**: browse CCDirectLink's CCModDB and install by id, instead of by picked file.
* **The reporters' next records**.
  * **Issue #4 (Mali-G720)**: the water should draw now (§22.16). What is wanted back: a screenshot, and the
    shader self-test's summary (`analog-filter~lifted compile=1`, `8 cases, 8 compile`).
  * **Issue #5 (Mali-G76, WebView 153)**: the boot stalled with three shaders pending because the probe
    accepted declarations the engine refused. The lift is served regardless now, and the record pairs a
    refused shader with what the port served — so the next report says whether his game copy's bytes match
    the known ones (`NOT lifted (0/1)`) and, with the self-test, which spelling his front end takes.
  * **Issue #3 (the grid)**: answered — `solid.frag`'s grey-mode halftone, invisible at 1920x1080, plus the
    water that was missing on those spots. Worth a confirmation from him at his Resolution rung.
* **Still unverified anywhere**: the gated fragment-precision repair on a Mali device — the S22 cannot show it
  (Adreno accepts the game's declarations, so the gate stays off there), which is why #4's record closes it.
  The 0.9.0 features themselves were smoke-tested with a **debug** build (throwaway package suffix), so a
  signed release install-over-previous has not been run since 0.8.0.

Nothing else outstanding. **v0.9.0** is the localisation + CrossCode-mods release — see *Shipped in 0.9.0*
below. **v0.7.6** serves the named ramp — the spelling the reporter's driver measured as accepted — and
trims the self-test to the cases that still answer something (**v0.7.5** fixed the spelling test's
line-ending and `null` defects, both found on a phone). **v0.7.4** carries the shader self-test, the
`driver=` field and the OpenGL-driver button (§22.11). **v0.7.3** made the lift report itself per file and
added the post pass's own declaration pair to the gate (§22.9). **v0.7.2** replaced the Mali gate with the
evidence-based one, and the device-farm shader probe measured four Mali generations (§22.8, §22.10).
**v0.7.1** carried the frame-rate slider (20/30/45/60 under the battery switch, §20.2 — verified on the S22
Ultra) and the Mali array lift (§22). **v0.7.0** carried the shader/GPU pass (GUI precision, the uniform
*link* probe instead of the reported count, the dither served on the render grid), the Reset-resolution
button, the 30 fps switch, the exit that keeps the task in recents and the shader-error record
(README "What works today", §16-§21). **v0.8.0** is the first two-game release.

## CrossCode on a phone (open after 0.9.0)

* **Spanish translation for CrossCode** — **shipped in 0.9.0**: the on-device download-and-install route
  (community *CrossCode-Esp* v1.1.0 via CCLoader, nothing shipped, the game folder never touched;
  `FINDINGS.md` §26, design in [`CROSSCODE_SPANISH_PLAN.md`](CROSSCODE_SPANISH_PLAN.md)).
* **The CCLoader mod library** — **shipped in 0.9.0** (`FINDINGS.md` §27). What is left is the
  compatibility gate and the catalogue, both listed under *Next version*.

What a phone still has not shown is **play**:

* **Title screen is verified on a phone** (2026-10-04, Galaxy Z Fold 7, Android 17): 60 fps,
  canvas 1136×640, `window=475x751@2.625`, `platform=Desktop`, and `Default/cc.save` written into the
  picked saves folder. What is left is *play*: the heavier scenes' GPU cost at 1136×640 (§9.5 is the
  recipe), audio, the pad in-game, and a save carried back to a desktop install.
* **Audio.** The engine's `AudioContext` starts suspended under automation; the app sets
  `mediaPlaybackRequiresUserGesture=false` and resumes on tap, but no CrossCode sound has been heard.
* **Fullscreen and scale.** The engine's own scale/fullscreen option vs the port's immersive mode, and
  how the 1136×640 canvas sits on a 20:9 phone. The picture-position control applies to CrossCode as of
  0.8.0 (harness-verified on a 412×915 viewport: `offsetTop` 245 centered → 0 Top → 490 Bottom, with
  `ig.system.screenHeight` unchanged); not yet re-run on the phone.
* **Pad mapping** in-game.
* **Saves.** `/saves/Default` for the engine's path list; either layout is read (`SaveLayout`, both
  shapes unit-tested). Untested: a save made on the phone, copied to a desktop install, and back.

Decided against for now: a shared shim core (`ada-shim.js` and `cc-shim.js` duplicate the
gamepad/frame-rate/stats/error plumbing; the two engines need opposite platform answers and are the
only two Radical Fish NW.js games — extract a core only if a third appears).

## Shipped in 0.9.0

**The port's own UI is localised.** Every user-facing string moved into Android resources, with Spanish
and Brazilian Portuguese beside the English (93 keys each, same order and placeholder arity, enforced by
`android/tools/check-locales.mjs`); the phone's language selects it, with nothing to set. **Spanish for
CrossCode in one tap**: the CrossCode card's ⋮ downloads the community CrossCode-Esp pack (CC BY 4.0,
nothing bundled), checks it against a pinned SHA-256, installs it into app storage and boots the game
through CCLoader in Spanish; the same item removes it. **A CCLoader mod library**: the card's **Mods…**
installs any `.ccmod`/`.zip`, switches a mod off and on, and removes it, with the loader's version shown —
the loader lives in `ccloader/`, mods in `assets/mods/<id>/` and `disabled/<id>/`, all in app storage, so
"installed" and "enabled" are the disk, not an index. The app now requests **INTERNET**, only for that
optional download. Also in 0.9.0: `cc-shim.js` provides the `util` module CCLoader's console needs, and
the docs record that game build `0.2.0-8` ships the same bytes the port's rewrites key on (§29).

## Shipped in 0.8.0

**Both games, one app** (the display name is RadicalFish Ports; package and key unchanged), and the entry
screen rebuilt as **one row card per game**: its own title art in a tile, the game and saves paths, a
play badge and a **⋮** carrying **Help: what to copy** and **Create a home-screen link** (the launcher
lists both games on a long-press). Each game keeps its own saves folder, log file and pad layout; a folder
that has been moved or deleted is marked rather than silently claimed; the folder walk is cached between
launches (`GameIndexCache`, keyed to the folder and the game's version file). Also in 0.8.0: saves read in
either shape (`SaveLayout` — the game's subfolder whole, or its contents flat), the legacy `keyCode` for
CrossCode's keyboard, the picture-position control for both engines, **dynamic sticks** on the on-screen
pad, and the new circuit-fish launcher mark.
