# TODO

## Next version

Nothing ships per change (README, "Publishing a signed release"): these items accumulate and go out
together, when the queue is worth asking somebody to download.

* **The reporters' next records** — the only open items.
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

Nothing else outstanding. **v0.7.6** serves the named ramp — the spelling the reporter's driver measured as
accepted — and trims the self-test to the cases that still answer something (**v0.7.5** fixed the spelling
test's line-ending and `null` defects, both found on a phone). **v0.7.4** carries the shader self-test, the
`driver=` field and the OpenGL-driver button (§22.11). **v0.7.3** made the lift report itself per file and
added the post pass's own declaration pair to the gate (§22.9). **v0.7.2** replaced the Mali gate with the
evidence-based one, and the device-farm shader probe measured four Mali generations (§22.8, §22.10).
**v0.7.1** carried the frame-rate slider (20/30/45/60 under the battery switch, §20.2 — verified on the S22
Ultra) and the Mali array lift (§22). **v0.7.0** carried the shader/GPU pass (GUI precision, the uniform
*link* probe instead of the reported count, the dither served on the render grid), the Reset-resolution
button, the 30 fps switch, the exit that keeps the task in recents and the shader-error record
(README "What works today", §16-§21).

## CrossCode (0.8.0, new)

The host now runs CrossCode as well: `GameProfile` selects it from the picked folder, and
`cc-shim.js` boots the game to its title screen (`ig.platform == Desktop`, Cubic Impact 0.5, 0 page
errors — harness-verified 2026-10-04 with `tools/game-harness.py`, screenshot in
`docs/crosscode-title.png`). What a phone adds is **not measured**:

* **GPU cost at 1136×640.** Headless SwiftShader drew the title screen fine, which is encouraging but
  is not a measurement; §9.5 is the recipe.
* **Audio.** The engine's `AudioContext` starts suspended under automation; the app sets
  `mediaPlaybackRequiresUserGesture=false` and resumes on tap, but no CrossCode sound has been heard.
* **Fullscreen and scale.** The engine's own scale/fullscreen option vs the port's immersive mode, and
  how the 1136×640 canvas sits on a 20:9 phone. The picture-position control is off for this profile
  (it was measured against the `terra` engine's mouse mapping).
* **Pad mapping** in-game.
* **Saves.** The engine's own save path list is built from `nw.gui.App.dataPath` (the port makes it
  `/saves`), so save files should land in the picked folder and travel with it. Untested: a save made
  on the phone, copied to a desktop install, and back.

Decided against for now: a shared shim core (`ada-shim.js` and `cc-shim.js` duplicate the
gamepad/frame-rate/stats/error plumbing; the two engines need opposite platform answers and are the
only two Radical Fish NW.js games — extract a core only if a third appears), and CrossCode extensions
(`assets/extension`; the loader runs and reports an empty list).

## Entry screen (0.8.0, new)

The entry screen is one row card per game (its own title art in a tile, the game and saves paths, a
play badge and a **⋮**), each game keeps its own saves folder, and a folder that has been moved or
deleted is marked rather than silently claimed. The cards' **⋮** now carries **Help: what to copy**
(the install folder, the desktop save paths and the save file names) and **Create a home-screen
link** (a pinned icon opening straight into that game), the launcher lists both games on a
long-press, and the folder walk is kept between launches (`GameIndexCache`, keyed to the folder and
the game's version file).

