## v0.7.7

The water was not being drawn on **any** device, and the port's own safety net was why.

* **Water, everywhere.** The engine keeps `v_barycentric` alive with `vec3(-1e30)` in fragment shaders; the
  water fragments run at `mediump`, which cannot hold `1e30`, so where the varying is garbage the branch
  fired and the fragment returned before drawing. The water shaders are served at `highp` in both stages now.
* **Water links on Mali again.** Two `lib/water.glsl` uniforms took each stage's default precision; the water
  fragments are served with the vertex stage's — only on devices whose own compiler refused the game's
  declarations, because serving it elsewhere breaks Adreno's link instead.
* **The array lift is served on every device**, instead of asking the document-start probe first: the probe
  can accept declarations the engine then refuses, and that costs a frozen boot (issue #5), while lifting is
  free — the same declarations, spelled the way the game's own vertex shaders spell them.
* **A shader the engine refuses now says which bytes it was served.**
* **40 fps rung** on the battery switch's slider (20/30/40/45/60).
* **`window=` and `canvas=`** in the diagnostics facts, for "the picture doesn't fill the screen" reports.

[Issues](https://github.com/moronigranja/alabaster-android/issues) ·
[fuller notes](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.7.7.md)
