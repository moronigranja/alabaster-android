# Alabaster Dawn Android port v0.7.1 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are
in the [README](../README.md); the terse changelog is [docs/release-0.7.1.md](release-0.7.1.md).

This release is two changes: the battery switch grew the rates a phone actually needs, and the shader
whose compilation froze the boot on a Mali phone is now served the spelling that driver accepts. The
first is verified on hardware; the second is verified everywhere except Mali, which is the one place
that needs it — the section below says why that is the honest state.

## The frame-rate switch now has a rate

v0.7.0's battery switch was a single rate: 30 fps. The slider under it offers **20 / 30 / 45 / 60**, and
it is shown only while the switch is on — the switch says *whether*, the slider says *how much*. The
chosen rate is a readout beside the bar, and the four labels sit on a strip under it, each centred on
the position its thumb reaches.

Nothing about *what* the cap does changed: the shim still wraps the page's `requestAnimationFrame` (the
engine's only frame driver) and serves one frame per interval, so the engine's own 60 Hz fixed step and
its clock are untouched — `Timer.step` reads `performance.now()` itself, and only the presents drop.
What changed is that the interval is a number the app sends, read on the same once-per-frame poll that
already carries the readout.

**A rate is a request, not a promise, because a display presents only on a vsync.** The gate serves the
first vsync at least one (slightly shortened) interval after the last frame, so it lands on the **next
vsync up** — the largest rate the panel can actually present that does not exceed the chosen one:

| chosen | 60 Hz panel | 120 Hz panel |
|---|---|---|
| 20 | 20 | 20 |
| 30 | 30 | 30 |
| 45 | **30** (22.2 ms → 33.3 ms) | **40** (22.2 ms → 25 ms) |
| 60 | 60 | 60 |

The same tenth-of-an-interval shortening that made 30 land on a 60 Hz vsync makes the rest land too; it
is the difference between 30 fps and the 20 fps an exact 33.3 ms comparison would have produced.

Measured on the **S22 Ultra** (SM-S908U1, Android 16) with the signed release build installed over an
older one, so both folder grants stayed. The game booted, Back opened the side menu, and moving the
slider changed the running game **live, in one process, without a reload**:

| slider | the port's record | the on-screen readout (the engine's own measured rate) |
|---|---|---|
| 20 | `frame limit: 20 fps` / `ENGINE fps: limited to 20 fps` | `20 fps` |
| 30 | `limited to 30 fps` | `30 fps` |
| 45 | `limited to 45 fps` | `30 fps` — a 60 Hz panel, per the table above |
| 60 | `limited to 60 fps` | `60 fps` |
| switch off | `unlimited` | `60 fps` |
| switch back on | `frame limit on: 20 fps` | `20 fps`, slider back at 20 |
| relaunch | `limited to 20 fps` (new pid) | `20 fps`, slider present at 20 |

The slider row appears and disappears with the switch, and the rate survives both an off/on cycle and a
relaunch. Behind it: `FpsLimitTest` (the four rates, the slider position of a stored value, the tie
rule, `0` = off) and six new checks in the shim harness — 53/53 total — which drive the vsync queue at
every rate, including **45 on a 60 Hz panel being 30**.

## The shader Mali's front end refuses

The Mali report from v0.7.0 (issue #4) was read back from the same device, and the compiler's own
message named the construct:

```
Shader Errors: data/shader/fragment/post/analog-filter.frag
  0:62:  S0032: no default precision defined for variable 'vec3[5]'
Shader Errors: data/shader/fragment/water-plane.frag
  0:275: S0032: no default precision defined for variable 'vec4[4]'
Shader Errors: data/shader/fragment/water-fx-wall.frag
  0:308: S0032: no default precision defined for variable 'vec4[4]'
boot stall | no progress for 8496ms at 98.7% of 1704 resources; pending 23 (shader=3 …)
```

Those are the game's own array declarations — the post pass's ramp built as `vec3[5](…)`, and the
return type, a parameter and a local of `lib/water.glsl` (`vec4[4]`), which both water fragment shaders
import. Nothing in them is invalid ES 3.0: `glslangValidator` accepts all 39 served fragment shaders
with the port's rewrites applied, ANGLE compiles them, and the same game runs on Adreno, SwiftShader and
desktop. This front end just does not carry a shader's declared default precision onto an array written
`type[size] name` — the same bug is reported for other Mali generations (Godot's #99821 on a Pixel 8a's
Mali-G715 and an Amazon Fire HD 10, a Pixel 6's Mali-G78, and a Stack Overflow case with `float[4]`).

The port now serves five fragment-stage files with the declarator spelling that driver accepts, and
**only to a device whose own compiler refuses the game's declarations** — a document-start probe
compiles the game's spellings on a throwaway context and reports the outcome, so a device that accepts
them (every one measured: Adreno, SwiftShader, desktop) is served the game's own bytes, untouched. The
change is a spelling, not a rewrite: the base types, the sizes, the qualifiers and the evaluation order
stay the game's.

```
shader arrays: the page's compiler rejects them -> lifting them
array declarations lifted in terra/data/shader/lib/water.glsl (9/9)
```

## What is verified, and what is not

| | state |
|---|---|
| the frame-rate slider, end to end | **verified** on the S22 Ultra (the table above), live and across a relaunch |
| the slider's logic (`FpsLimit`) | `FpsLimitTest`; 116 unit tests, 0 failures |
| the shim's gate at every rate | 53/53 harness checks, including 45 on a 60 Hz panel |
| the Mali lift's declarations | `glslangValidator` on the expanded originals **and** the lifted ones |
| the Mali lift through the engine | boot A/B in Chromium via the port's own shim: both boot complete (1 757 resources, 154 shader compiles, **0 compile and 0 link failures**), with identical active uniforms and attributes for `water-plane` (41/41), `water-fx-wall` (48/48), `analog-filter` (15/15) and `gui-blur` (11/11) |
| the Mali lift **on Mali** | **not verified** — this project has no Mali hardware. It is a device-gated change: a phone that refused the shaders will now be served the accepted spelling and, if that is still not enough, its record will name the next type rather than a frozen bar |

The last row is the one to watch. If you are on a Mali device and the port previously froze at the
loading bar, this build either boots or leaves a record that says exactly what is still wrong —
**Troubleshoot** in the side menu (or on the setup screen) shows it.

## Upgrading

Install over the previous build: your game and saves folders are kept, as are the side-menu switches.
The new preference (the chosen rate) defaults to 30 fps, which is what the old switch meant, so an
existing "on" keeps its meaning. Nothing in the game's files is touched by the port — including this
release's shader work, which is served in memory.
