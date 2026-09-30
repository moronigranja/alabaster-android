# TODO

## Next version

* **A Mali run of v0.7.1** (issue #4, FINDINGS §22). The array lift ships in v0.7.1 and is verified
  everywhere but Mali — the one place it exists for. The record's `shader arrays: …` line plus one
  `array declarations lifted in …` per file decide it in a single run: it either boots, or the next
  `S0032` names the type to look at. This project has no Mali hardware, so the run is a reporter's.
* **The reporter's `weather-drops.frag`**, which his own workaround ZIP stubbed and which carries no
  array type specifier at all: no record lists it among the failing shaders. If it does fail on that
  build it fails for a second reason, and the reason is not in any record yet (§22.7).

Nothing else outstanding. **v0.7.1** carries the frame-rate slider (20/30/45/60 under the battery
switch, FINDINGS §20.2 — verified on the S22 Ultra) and the Mali array lift (§22). **v0.7.0** carried
the shader/GPU pass (GUI precision, the uniform *link* probe instead of the reported count, the dither
served on the render grid), the Reset-resolution button, the 30 fps switch, the exit that keeps the
task in recents and the shader-error record (README "What works today", FINDINGS §16-§21).
