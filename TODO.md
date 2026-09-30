# TODO

## Next version

* **A native-driver run of v0.7.3, with the game's original shader files** (issue #4, FINDINGS §22.9). On
  the native driver v0.7.2's check fired, the lift ran, the two water shaders now compile, and
  `analog-filter.frag` still fails with `S0032 … 'vec3[5]'`. What the record could not say is *which files
  were lifted* — that was logcat-only — so v0.7.3 reports it per file
  (`array declarations lifted in … (9/9)` / `… NOT lifted … (0/1)`) and asks the pair the post pass is as
  one shape (`ramp-call`), because both of its halves compile while the pair is what the file writes. The
  next report either boots or names the file and the type. This project has no Mali hardware, so the run is
  a reporter's — with the *original* files, since hand-patched ones hide the result.
* **The water is invisible on ANGLE** (§22.9): that driver boots and plays (ANGLE has its own shader front
  end, so the driver bug never happens), and no shader fails in that record. Candidates: the reporter's
  hand-patched water files — a different alpha floor, two border `discard`s commented out, a wave amplitude
  — still being in his game folder, or an ANGLE-on-Mali rendering difference in the water pass. A run with
  the original files, plus a screenshot, separates them. Not reproducible here (no Mali hardware).

**v0.7.2** replaced the Mali gate with the evidence-based one (§22.8). **v0.7.1** carried the frame-rate
slider (20/30/45/60 under the battery switch, FINDINGS §20.2 — verified on the S22 Ultra) and the Mali
array lift (§22). **v0.7.0** carried the shader/GPU pass (GUI precision, the uniform *link* probe instead of
the reported count, the dither served on the render grid), the Reset-resolution button, the 30 fps switch,
the exit that keeps the task in recents and the shader-error record (README "What works today",
FINDINGS §16-§21).
