## v0.7.0

The device pass that issue #3 started: the shaders are served better on the GPUs phones actually
have, and two things the phone needed came out of it.

* **The GUI's shaded fills are no longer striped.** The game's GUI shaders declare `precision mediump
  float` while every world shader is `highp`; desktop GL promotes mediump, a mobile GPU does not, and
  the GUI's screen-space and tiled maths then quantises into a visible stripe pattern on the shaded
  fills (the selected menu row, the slider track) at the same base colour the desktop build renders
  flat. The port now serves those four GUI shaders as `highp` — ES 3.0 guarantees it in both stages.
* **The terrain's grid of dots is served finer.** The dots are the engine's own ordered dither — a 4x4
  threshold table indexed by the *art* pixel, discarded binary, so at a fade of 0.5 half the fragments
  are thrown away and the unlit layer behind shows. On a phone panel, where an art pixel is two or
  three device pixels, a 4-cell repeat reads as a grid over the terrain (issue #3). The engine's own
  `solid.frag` and the water's radial dither already index by the *render* pixel; those five shaders
  are now served that way too.
* **The port no longer rewrites shaders on devices that never needed it.** `ShaderSlots` decided from
  the driver's reported `MAX_VERTEX_UNIFORM_VECTORS`, so a device at the GLES3 minimum of 256 got every
  vertex shader rewritten — even where its compiler packs two `vec2` slots per `vec4` and would have
  linked the game's own table, which is what Adreno does. The decision is now a **link**: the shim
  compiles the two shapes the game's shaders have and the port follows what actually links. On the
  Fold 7 the record reads `256-slot links, gui two-table links -> TEX_SLOT_COUNT 256` with nothing
  rewritten at all.
* **Reset resolution**, in the port's Troubleshoot panel next to Share and Close. The Resolution
  option is the one setting that can leave the port unusable by being slow — the engine never caps it,
  and 2560x1440 renders 8.3x the pixels of the ladder's 960x540 default — so the button drops the
  game's stored value (it lives in the WebView's `localStorage`, not in the game's files) and the next
  start boots at the default rung. The record also carries the resolution it is running at now.
* **A switch to limit the frame rate to 30 fps**, for battery. The port is GPU-bound, and the shim can
  gate the page's `requestAnimationFrame` — the engine's only frame driver — to one frame per interval;
  the game logic keeps its 60 Hz fixed step, because the engine's clock reads `performance.now()`
  itself. Verified live on the Fold 7: the readout went `30 fps · 960x540` → `60 fps · 960x540` →
  `30 fps · 960x540` as the row was toggled, in one session, with no reload.
* **Exit leaves the app in the task list.** The exit deliberately ends the process (a fresh WebView
  renderer is what makes the next launch clean), but it no longer *finishes* the task, which is what
  used to drop the app out of recents. `dumpsys activity recents` still lists it after an Exit, and
  tapping that card starts a new process on the setup screen.
* **A shader the device's compiler rejects now names itself in the record.** The engine logs a failed
  compile as console *groups* — the file's path and the compiler's own message are the group titles —
  and the port only forwarded `console.error`/`warn`, so a device-only shader failure arrived as a line
  of shader code with the file and the reason missing. Both are in the diagnostics record now, which is
  what a report like the Mali one on the community needs. No fix for that report yet: the message is
  the missing piece, and the port now captures it.

Full release notes: [docs/release-notes-0.7.0.md](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.7.0.md)
Install, requirements and known limitations: [README](https://github.com/moronigranja/alabaster-android#readme)
