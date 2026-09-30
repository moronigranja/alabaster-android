# TODO

## Next version

Nothing ships per change (README, "Publishing a signed release"): these items accumulate and go out
together, when the queue is worth asking somebody to download.

* **The reporter's next record** (issue #4, FINDINGS §22.12). His device answered the spelling question:
  what driver **49.1.0** refuses is an array *temporary* — `colorRamp(noise.r, vec3[5](…))` — in every
  spelling of its brackets, and naming the ramp is the one repair it took. v0.7.6 serves that, so his next
  run should show `analog-filter~lifted compile=1` and the boot past 94 % on the **native** driver (today it
  stalls there on that one shader). The self-test now asks 8 cases instead of 14 (each lifted file, the
  game's bytes and the port's).
* **The water under ANGLE, with the game's original files** (§22.9, §22.12): his screenshot shows the river
  rendering, but it followed *his hand patch*, so the file set behind it is not settled. One run with the
  originals plus a screenshot closes it — if the water is there, invisibility was the patched files; if not,
  it is the ANGLE-on-Mali water pass and worth its own chase.

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
