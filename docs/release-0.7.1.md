## v0.7.1

Two things: the battery switch grew the rates a phone actually needs, and the shader whose compilation
freezes the boot on Mali is served the spelling that driver accepts.

* **The frame-rate switch now has a rate slider** — `20 / 30 / 45 / 60 fps`, shown under the switch only
  while it is on, with the labels on a strip under the bar. The shim's `requestAnimationFrame` gate takes
  the number rather than a fixed 30, and because a display presents only on a vsync it takes the **next
  vsync up**: on a 60 Hz panel `45` is 30 (22.2 ms → the next vsync is 33.3 ms), on a 120 Hz one it is 40.
  Verified on the **S22 Ultra** (SM-S908U1, Android 16, release build over an older one, both folder
  grants kept): moving the slider changed the running game live, in one process, with no reload —
  `20` → the on-screen readout and the shim's own count both `20 fps`, `30` → `30 fps`, `45` → `30 fps`
  (that panel), `60` → `60 fps`, switch off → `60 fps`; the row appears and disappears with the switch,
  and the rate survives a switch off/on cycle and a relaunch (`frame limit on: 20 fps`,
  `ENGINE fps: limited to 20 fps` in the new pid). Behind it: `FpsLimitTest` and the shim harness'
  six cap checks among its 53.
* **A Mali front end that refuses the game's array declarations is served the spelling it accepts**
  (issue #4). The device's own record named it: `S0032: no default precision defined for variable
  'vec3[5]'` / `'vec4[4]'` — this driver does not carry a shader's declared default precision onto an
  array written `type[size] name`, which is valid ES 3.0 (a strict front end and ANGLE accept all the
  served shaders, and the same bug is reported for other Mali generations in Godot and elsewhere). The
  port now serves the five fragment-stage files with the declarator spelling — `vec4[4] waves` →
  `vec4 waves[4]`, the one `vec3[5](…)` constructor without its size, and the one array return type
  through an `out` parameter — and only to a device whose own compiler refuses them, decided by a
  document-start probe (`shader arrays: the page's compiler rejects them -> lifting them`, then one
  `array declarations lifted in …` per file). Verified by unit tests, `glslangValidator` on the expanded
  originals *and* the lifted ones, and a boot A/B in Chromium through the port's own shim (both boot
  complete, 1 757 resources, 154 shader compiles, 0 compile and 0 link failures, identical active
  uniforms and attributes for the four affected programs). **Not confirmed on Mali hardware** — this
  project has none, and the next record from that device is what closes it.
