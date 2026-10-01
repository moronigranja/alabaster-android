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
