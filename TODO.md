# TODO

## Next version

* **Report the GL driver the app actually got** (small, no permission needed). The facts line already
  separates the two — `gl=Mali-G720 MC7` (native OpenGL ES) from
  `gl=ANGLE (ARM, Vulkan 1.3.278 (Mali-G720 MC7…))` — and that difference is the whole story of the last two
  Mali reports (native: the driver refuses the declarations; ANGLE: it never sees them). A plain
  `driver: native|ANGLE` line, read from the renderer string (an app cannot read or write
  `Settings.Global.angle_gl_driver_selection_*` without privileges), makes every future report say which
  path it was taken on. *Cheap; fold into the next build rather than ship alone.*
* **Guide the user to the ANGLE switch** (small). A **Troubleshoot** row that says a Mali device's boot can
  be fixed by opting this app into ANGLE — *Developer options → ANGLE Preferences → pick `angle`* — with a
  button that opens the developer-options screen (best-effort intent). Why it must be guidance and not a
  switch of our own: opting in or out is `Settings.Global` (`angle_gl_driver_selection_pkgs` /
  `…_values`), writable only with `WRITE_SECURE_SETTINGS` (privileged/signature), and **bundling ANGLE
  would change nothing** — the WebView has its own GL stack, and the platform's ANGLE is the system's apk
  plus that per-package opt-in. Document the adb one-liners next to it (that is also how we set it on any
  device we can shell into).
* **A Firebase Test Lab shader probe** — **built and run** (`tools/mali-probe/`, FINDINGS §22.10). Four
  Mali devices (Pixel 8a/G715, Pixel 7/G710, Pixel 6/G78, Galaxy A35/G68) accept **every** case: the
  game's own bytes, the port's lifted bytes, the post-pass variants, and the shapes other projects report
  refusing. The reporter's **G720 on driver r49** is the only refusing front end reachable, and Test Lab
  has no G720 — so the remaining question (is the lift's spelling enough for r49?) is his next record's.
  If it is not, the probe's `variants/` set is what to try next; the natural follow-up is to serve the
  same matrix **from the port itself** (Troubleshoot → a shader spelling self-test over the user's own
  game files, compiled on the WebView's own context, verdicts into the record), which would let his phone
  pick the fix instead of us guessing. Queued, not built.
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
