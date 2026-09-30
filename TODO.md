# TODO

## Next version

* **A Mali run of v0.7.2** (issue #4, FINDINGS §22.8). v0.7.1 shipped the array lift and its gate
  answered wrong on the one device that needed it (`shader arrays: … accepts`, then the same three
  `S0032`s and a boot stall at 97.2 % — `logs/mali/log alabaster 0.7.1.txt`). The gate now compiles the
  game's own declarations, one program per shape, on the engine's own kind of context, **linked**, and it
  lifts unless every shape compiles and links; the record carries each shape's outcome. One run from that
  phone decides it: it either boots, or the line names the shape that was refused and the forwarded
  `S0032` names the type. This project has no Mali hardware, so the run is a reporter's. If it *still*
  reports `all 8 shapes compile and link` while the compile fails, the remaining difference is the
  context the probe asks on (it makes its own, before the engine has one) and the next lever is
  `window.g.gl` itself at first-shader time, through the await the gate already uses (§22.8).
* **The reporter's `weather-drops.frag`**: his *first* ZIP stubbed it and it carries no array type
  specifier at all — his second ZIP, the one that boots, does not touch it, and no record lists it among
  the failing shaders, so it is closed unless a later build disagrees (§22.7).
* **The link-error class his log found** (§22.8): raising the *default* precision in one stage only makes
  a shared uniform's precision differ per stage, and that driver refuses the program
  (`Uniforms with the same name but different type/precision: u_waveHeight`). Checked harmless for the GUI
  precision raise (`gui.vert` is raised with every GUI fragment); it is a hazard for any future precision
  edit, nothing to do now.

Nothing else outstanding. **v0.7.2** carries the evidence-based Mali gate (§22.8). **v0.7.1** carried the
frame-rate slider (20/30/45/60 under the battery switch, FINDINGS §20.2 — verified on the S22 Ultra) and
the Mali array lift (§22). **v0.7.0** carried the shader/GPU pass (GUI precision, the uniform *link* probe
instead of the reported count, the dither served on the render grid), the Reset-resolution button, the
30 fps switch, the exit that keeps the task in recents and the shader-error record (README "What works
today", FINDINGS §16-§21).
