# TODO

## Next version

Nothing ships per change (README, "Publishing a signed release"): these items accumulate and go out
together, when the queue is worth asking somebody to download.

* **The water on that phone — fixed, unreleased** (§22.13). Its records carry the real cause, which is not a
  compile failure but a **link** one: `Unable to initialize the shader program water-plane.vert +
  water-plane.frag: Uniforms with the same name but different type/precision: u_waveHeight` (and
  `u_cameraProjM` for the other pair). Those uniforms come from `lib/water.glsl` unqualified, so the vertex
  language's highp default and the fragment's `precision mediump float;` disagree and the program never
  initializes — the water plane is not drawn while the rest of the scene is. The lift now raises that one
  line in the two water fragments, which is exactly what the phone's owner's own patch did. On `main`,
  **deliberately not released**: it goes out with the next batch, and that run is also what confirms it
  (the river should be drawn).
* **"45 fps runs at 30" on that phone** (§20.2, and the shim's own frame-limit comment). Expected, not a
  driver bug: the gate only serves a frame on a vsync, so a rate that is not a whole division of the
  surface's refresh resolves upwards — `45 fps (22.2 ms, 20 ms shortened) is 40 on a 120 Hz panel and 30 on
  a 60 Hz one`. So that run's WebView surface refreshes at 60 Hz. Under **ANGLE** the swapchain may run at
  the panel's full 120 Hz, in which case 45 reads 40 there — which would explain why it looked
  driver-dependent. If exact 45 ever matters, a 4-vsync/3-frame pattern would give it on 60 Hz, at the cost
  of uneven frame times.
* **A dither experiment is out with the maintainer's phone, awaiting its screenshot** (branch
  `dither-experiment`, commit `5ef27ff`, not pushed, no release). It serves the five world shaders' ordered
  dither off the pixel lattice — the golden ratio instead of the integer divisor — because issue #3's grid
  is the 4x4 table's *period* (four render pixels, about twelve device pixels at 640x360 on a 1080p panel),
  not the cell size v0.7.0 already fixed. The build keeps versionCode 17 / 0.7.6, so its facts line carries
  `dither=off-lattice` to tell its records from the shipped build's. What the answer decides: gone → the
  period was the cause and the fix becomes principled (blue-noise mask, or keep the off-lattice scale);
  still there → the cause is the binary discard's contrast, not the period.
* **Two things the S22 Ultra is needed for**, once it is back on the cable: the five tick labels the 40 fps
  rung added (the panel layout is the one part no test can see), and the dither A/B — the experiment build
  is installed there, a `main` build gives the shipped render-grid look for comparison at the same scene.
  Its display currently renders at 60 Hz (it supports 120), so a 40 fps cap may read 30 there; that is
  itself the datum that explains the reporter's "45 runs at 30".
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
