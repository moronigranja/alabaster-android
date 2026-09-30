# TODO

## Next version

* **A Mali run of v0.7.4, with the game's original shader files** (issue #4, FINDINGS §22.9-§22.11). The
  state on the native driver: v0.7.2's check fires, the lift runs, the two water shaders compile, and
  `analog-filter.frag` still fails with `S0032 … 'vec3[5]'`. What is missing is *which* spelling that
  driver (Mali-G720, r49) will take — and v0.7.4 now asks the phone itself: **Troubleshoot → "Test shader
  spellings"** compiles the game's own shaders in all fourteen spellings the port could serve, on the
  device's own front end, one verdict line each into the record, then **Share** sends it. The next report
  is either "it boots" (the lift was enough) or the name of the spelling that phone accepts, which the
  next build serves. This project has no Mali hardware, so the run is a reporter's — with the *original*
  files, since hand-patched ones hide the result.
* **The water is invisible on ANGLE** (§22.9): that driver boots and plays (ANGLE has its own shader front
  end, so the driver bug never happens), and no shader fails in that record. Candidates: the reporter's
  hand-patched water files — a different alpha floor, two border `discard`s commented out, a wave amplitude
  — still being in his game folder, or an ANGLE-on-Mali rendering difference in the water pass. A run with
  the original files, plus a screenshot, separates them. Not reproducible here.
* **The panel button's on-device click-through** (§22.11): the only phone in reach was behind a secure lock
  screen, where Android returns black frames from `screencap`, so the UI could not be driven over adb.
  Everything behind the button is covered by tests (cases, the page half, the summary) — worth a tap next
  time a phone is unlocked.

Nothing else outstanding. **v0.7.4** carries the shader self-test, the `driver=` field and the OpenGL-driver
button (§22.11). **v0.7.3** made the lift report itself per file and added the post pass's own declaration
pair to the gate (§22.9). **v0.7.2** replaced the Mali gate with the evidence-based one, and the device-farm
shader probe measured four Mali generations (§22.8, §22.10). **v0.7.1** carried the frame-rate slider
(20/30/45/60 under the battery switch, §20.2 — verified on the S22 Ultra) and the Mali array lift (§22).
**v0.7.0** carried the shader/GPU pass (GUI precision, the uniform *link* probe instead of the reported
count, the dither served on the render grid), the Reset-resolution button, the 30 fps switch, the exit that
keeps the task in recents and the shader-error record (README "What works today", §16-§21).
