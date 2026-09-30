# Alabaster Dawn Android port v0.7.0 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are
in the [README](../README.md); the terse changelog is [docs/release-0.7.0.md](release-0.7.0.md).

This release is the device pass that issue #3 started — a "grid texture" in the terrain on an Adreno
phone — and what that pass turned up along the way: the way the port serves the game's shaders, a way
back from a Resolution that can leave the port unusable, a 30 fps switch, an exit that leaves the app
in the task list, and a shader failure that finally names itself in the record.

## The face of the game's GUI was quantised

The GUI shaders (`gui.frag`, `gui.vert`, and the panel's background/blur pair) declare `precision
mediump float;`; every world shader is `highp`. Desktop GL has no real mediump and promotes it — which
is why the desktop build of the same screen shows a flat lighter grey — while a mobile GPU honours it,
and the GUI's screen-space `floor()`/`mod()` (the shaded fills) and `fract()` tiling quantise into a
regular comb: a bright line every 8 *render* pixels, which at 640x360 with Integer Scaling is a very
visible stripe. The port now raises those four shaders to `highp`; ES 3.0 guarantees it in both stages,
and wherever mediump already meant highp only the declaration changes.

Measured on the **Galaxy Z Fold 7** with the build installed: the selected row of the game's own menu —
the exact element that showed the comb — now has a flat fill (std 8.1 across it, no peak at any lag),
where the reported build measured std 19.7 with a strong 16-pixel periodicity at the same element.

## The shaders were being rewritten where they did not need to be

`ShaderSlots` decided from the driver's reported `MAX_VERTEX_UNIFORM_VECTORS`. The Fold 7 reports the
GLES3 minimum of 256, so every vertex shader was served with a 192-slot table and `gui.vert` packed —
yet the *same device* had booted the game fine on port 0.4, before that rewrite existed. The reported
number is not what a shader carries: Adreno's compiler packs two `vec2` array slots into one `vec4`
(the GLSL ES 3.0 default-block packing), so the game's own 256-slot table fits its 256-vector budget;
SwiftShader packs one slot per vector and genuinely fails with `too many uniforms` (that is issue #1,
and that device keeps the rewrite).

The decision is now a **link**: before the first shader is requested the shim compiles the two shapes
the game's shaders have — a 256-slot table with the real 48-vector reserve, and `gui.vert`'s two with
32 — and the port follows what actually links. The answer is latched when `bundle.js` is served, so the
engine's own constant and the shaders can never disagree with each other, and a page that never answers
keeps the count-based plan. On the Fold 7 the record now reads:

```
+12598ms gl limits: vertex uniforms 256
+12599ms shader tables: 256-slot links, gui two-table links -> TEX_SLOT_COUNT 256
+16404ms ENGINE boot: complete in 3804ms, 1757 resources; audio=running; decodes started=608 done=608 failed=0
```

with no shader rewritten at all — the game's own table, its own atlas size and its own constant — and
60 fps at 1280x720.

## The terrain's dot grid is the engine's own dither, served finer

Issue #3 reports a regular dot grid over the terrain, with the reporter's own guess attached — "Maybe
that was caused due to lowering the res so the game could run?" It is not the resolution: the dots are
the engine's ordered dither, and nothing about their size on screen changes with the Resolution option.

`terra/data/shader/lib/dithering.glsl` is a 4x4 threshold table, and five world shaders index it by
`gl_FragCoord.xy / (u_screenScale * u_ditherScale)`. `u_ditherScale` is 1 and `u_screenScale` is the
resolution option's scale, so one *cell* is one art pixel — and the pass is a **binary** discard: at a
fade of 0.5 half the fragments are thrown away and the unlit layer behind shows. On a phone panel,
where an art pixel is two or three device pixels, a 4-cell repeat reads as a grid of dots over the lit
terrain. The engine's own `solid.frag` (and the water's radial dither) already index by the *render*
pixel — raw `gl_FragCoord.xy` — where the same table is a per-pixel texture and invisible.

The port now serves those five shaders (`solid-simple-light`, `solid-back`, `solid-overlap`,
`solid-simple`, `shadow-map`) on the render grid, which is the engine's own convention. Measured in a
harness that renders the engine's own dither lines verbatim at the default 960x540 rung: the cell goes
from 1.5 buffer px — an uneven 44% stipple, the beat visible in the reporter's screenshot — to 1
buffer px, a uniform 50% stipple with a 4-pixel repeat instead of 6. Nothing else in those shaders
changes: the same thresholds, the same `discard`, the same varyings.

The Resolution option is still worth knowing about, for a different reason: the phone ladder's default
(960x540) is the only rung that both fills a 1080p panel exactly *and* renders crisp, because the
engine sets `image-rendering: pixelated` whenever the canvas's CSS width divides the panel's exactly
(`bundle.js` 34782). Every other rung is a fractional fit, and the engine then hands the whole picture
to `image-rendering: auto`, which softens the dots and the art with them.

## A way back from a Resolution the device cannot drive

The Resolution option is the one value a user can set that can leave the port unusable *by being slow*:
the engine never caps it (`const maxScale = 1000 || 0`), and 2560x1440 is 8.3x the pixels of the phone
ladder's 960x540 default. The picture still fits (the engine downscales), but the frame cost is eight
times, and the game's own Options menu — the only in-game way to change it back — is then the slowest
thing on the screen.

`Reset resolution` now lives in the port's **Troubleshoot** panel, next to Share and Close. That panel
opens from the setup screen's Troubleshoot button (always reachable after a relaunch) and from the side
menu's own entry while the game runs; it is the port's own UI, so it stays responsive when the page
does not. Either way the button drops the game's stored Resolution option and the next start boots at
the default rung.

The option is device-local, so the engine keeps it in the WebView's `localStorage` — as one JSON object
under `xg_local_options` — rather than in the game's files. The port therefore carries the reset in the
URL it loads the game with (`?adaResetVideo=1`), and the injected shim removes that one key at document
start, before the engine reads it; every other stored option is left alone, and a malformed blob is
reported rather than thrown. The game's own files are never touched.

The record also gained the number behind "too slow": the facts and boot lines now carry
`resolution=WxH`, read from the canvas the engine renders into. See `FINDINGS.md` §19.

## A 30 fps switch, for battery

The port is GPU-bound, and the GPU cost is the frames it draws. The engine drives **everything** it
draws from `requestAnimationFrame` — `System.run` re-arms itself at the end of every frame, and the
GUI's own canvases do the same — so the shim can gate rAF and gate the frames. The side menu now has a
**Limit to 30 FPS (battery)** switch; with it on, one frame is served on the first vsync at least one
frame interval after the last one served, and every callback that arrived in the meantime rides that
frame.

Nothing about the game is fooled: `Timer.step` reads `performance.now()` itself, so the game logic
keeps advancing by the real elapsed time and still runs at its 60 Hz fixed step — only the presents
drop, so the game does not slow down, it draws less. No game file is touched, and the engine has no
path of its own for this: its `force30fps` debug option is unreachable without `window.XG_GAME_DEBUG`,
which gates 116 sites in the bundle (debug menus, cheat paths, physics overlays).

The one subtlety is vsync quantisation. A 60 Hz vsync is 16.7 ms and 30 fps is 33.3 ms; waiting for
exactly 33.33 ms would put the deadline a fraction *after* the second vsync and slip every frame to the
third — 20 fps, not 30. The interval is therefore shortened by a tenth of itself, which is one vsync at
60 Hz and four at 120 Hz, and is 30 fps on both.

Verified live on the Fold 7 with the release build: the game's own readout went `30 fps · 960x540` →
(off) `60 fps · 960x540` → (on) `30 fps · 960x540` as the row was toggled, in one process with no
reload, with `frame limit on: 30 fps` and `ENGINE fps: limited to 30 fps` in the record, and the setting
survives a relaunch with the other switches. `node android/tools/test-shim-diagnostics.mjs` drives the
vsync queue directly: every vsync served with the limit off, ~30 frames per second of 120 Hz vsyncs
with it on, and 30 (not 20) per second of 60 Hz vsyncs.

## Exit leaves the app in the task list

The exit — the side menu's **Exit** and the game's own title-screen Exit — ends the app process on
purpose: the WebView renderer is shared for the process's life, and starting the game a second time in
the same process is the documented way to a black picture. It used to finish the task as well
(`finishAndRemoveTask`), and on Android a task leaves recents when its last activity finishes, which is
why the app disappeared from the launcher's task list after an Exit. Ending the process alone was never
what dropped it.

The exit now sends the task to the back and kills the process, and leaves the task alone. Verified on
the Fold 7: Exit printed `exit requested`, `pidof` was empty, `dumpsys activity recents` still showed
`Recent #1: Task{… io.github.moronigranja.alabasterdawn}`, and tapping that card in the overview brought
the setup screen up in a **new** pid — so the app is reopenable from the task list while the next
launch is still a fresh process with a fresh renderer.

## A shader the device's compiler rejects now names itself in the record

A player on the game's community reported that the port runs on his **Mali** device (a Poco X7 Pro) but
that he "had to use Gemini and logs to fix the game", naming a compilation error in some water files
and post-processing issues. What he shared was a set of shader files replaced by stubs that draw
nothing — water surfaces, rain and the analogue-film post pass switched off — plus an `index.html` that
swallows a runtime `TypeError: … reading 'set'`, which is what a program that did not link looks like
from the JS side. No log came with it.

There is no fix in this release for that report, because the one thing that decides it — the compiler's
own message — was not in his log. What the port could do about that, it now does: the engine logs a
failed compile as console **groups** (`Shader Errors: <path>`, then one group per `ERROR: 0:<line>:
<message>` the driver returned, with only the offending source lines going through `console.error`),
and the shim forwarded `console.error`/`console.warn` alone — so a device-only shader failure arrived
in the record as a line of shader code with both the file and the reason missing. The group titles are
forwarded too now; `console.log` deliberately is not, because the engine dumps the whole expanded
source through it under the `Shader Code` group (~1 200 lines).

Reproduced end to end without a device: a copy of the game tree with one deliberately broken line in
`water-plane.frag`, served over HTTP, with the port's own `ada-shim.js` in front of the real bundle in
Chromium and a recording bridge. The boot freezes at 99.9 % with `pending 2 (shader=1 data=1)` and
`fragmentShader.hasError = true` — the reported symptom exactly. The record it leaves now reads:

```
console.groupCollapsed | Shader Errors: data/shader/fragment/water-plane.frag
console.groupCollapsed | 1310: 'this' : Illegal use of reserved word
console.error          | 1309: <the offending source line>
jsError                | … An error occurred compiling the shader "…/water-plane.frag"
boot stall             | no progress for 8001ms at 99.9% of 1753 resources; pending 2 …
```

A healthy boot logs no console lines at all, so this adds nothing to a record that has nothing to say.
`node android/tools/test-shim-diagnostics.mjs` grew three checks for it (44/44).

## Verification, and what is not

The Fold 7 pass covered the GUI precision (the comb measured away), the uniform link
(`256-slot links, gui two-table links -> TEX_SLOT_COUNT 256`, nothing rewritten), the 30 fps switch
live with the readout, the recents entry and a fresh pid from it, and the exit's record. The shader
work has unit tests behind it in all three cases (`ShaderPrecisionTest`, `ShaderSlotsTest`,
`ShaderDitherTest`), and the link probe was exercised against a real GL — it reports `links` for the
game's own table and `does not link` for a 4096-slot one, so it is not a rubber stamp.

Honest limits:

* The **Mali failure has no fix and no reproduction on Mali hardware**: the port's shader bytes were
  checked with a strict ES 3.0 front end (`glslangValidator`) and with ANGLE — the four water/post
  shaders, expanded the engine's way, and all 39 served fragment shaders including the port's own
  rewrites — and nothing in them is invalid. Whatever the Mali driver rejects (or does not render) is
  in the message the record now captures, and the next report will say which of the candidate causes it
  is. `FINDINGS.md` §21.5 lists them.
* The `Reset resolution` button was confirmed on the Fold 7 by hand, in both doors (the panel from the
  setup screen and from the side menu); the reload path it shares with the shim's `?adaResetVideo=1` is
  covered by the harness.

## Everything else is v0.6.1

Nothing else changed: the picture-position fix, the mouse and keyboard work, the Back fix, the pad, the
side menu, the Steam-layout saves, the resolution ladder and the diagnostics record. See the
[v0.6.1 notes](release-notes-0.6.1.md).
