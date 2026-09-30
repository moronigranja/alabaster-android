# Alabaster Dawn Android port v0.7.2 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are
in the [README](../README.md); the terse changelog is [docs/release-0.7.2.md](release-0.7.2.md).

This release is one change, and it is a correction to v0.7.1. That release served the game's array
declarations in the spelling a Mali front end accepts — but only to a device whose compiler refused them,
decided by a probe. The device it was written for ran it and **the probe answered wrong**: it said
"accepts", the lift never ran, and that phone's boot froze exactly as before. This release replaces that
probe with one that compiles the game's own declarations, links them, and answers one-sidedly. Nothing
about the lift itself changed.

## A gate answered from a stand-in, and a stand-in is not the thing

v0.7.1's probe compiled **one synthetic shader** built from the same shapes the game writes, on a
throwaway context, and sent one bit to the app. On the reporting device the driver accepted that text and
refused the game's own bytes, which the reporter's own record now shows in order:

| t | the port's record (`logs/mali/log alabaster 0.7.1.txt`) |
|---|---|
| +12845 ms | `shader arrays: the page's compiler accepts the game's declarations` |
| +19565 ms | `Shader Errors: …/post/analog-filter.frag` — `0:62: S0032 … 'vec3[5]'` |
| +21425 ms | `Shader Errors: …/water-plane.frag` — `0:275: S0032 … 'vec4[4]'` |
| +21469 ms | `Shader Errors: …/water-fx-wall.frag` — `0:308: S0032 … 'vec4[4]'` |
| +29877 ms | `boot stall … at 97.2 % of 1763 resources; pending 49 (shader=3 …)` |

Every other stack measured — this project's Chromium (ANGLE), SwiftShader, Adreno, desktop — accepts both
the synthetic text and the game's own, so the "accepts" branch had never been exercised by a device that
disagrees, and there was nothing in the record to notice it with.

## What the probe asks now

* **The declarations the game's shaders are written with**, copied from the files the rule names, one
  program per shape: the varying (`flat in vec2[4] v_flowDirs;`), the global
  `const vec2[12] DIRECTIONS = vec2[](…)`, the `vec2[4]` parameter, the array **return type** with its
  `out` parameter, the `vec4[4]`/`float[4]` parameter list, the sized local, the parameter sized by a
  macro (`vec3[COLOR_RAMP_COUNT]`), and the sized constructor in an argument (`vec3[5](…)`).
* **On a context made with the engine's own attributes** (`{antialias:false,
  powerPreference:"high-performance"}`), and it reports that context's own renderer — a stack the page
  opened some other way is not the stack the engine's shaders are compiled on.
* **Linked, not only compiled.** The engine's own failure was a compile, but a front end that translates
  lazily answers a compile-only question differently, and that is the answer that failed.
* **One-sidedly.** Any shape refused, a context that cannot be made, or a probe that throws all mean
  "lift". Lifting is the side that compiles everywhere — the same bytes, spelled the way the game's own
  vertex shaders already spell arrays, with identical active uniforms and attributes — while the game's
  own spelling is exactly what the probe doubts. Doubting wrongly costs a frozen boot; lifting wrongly
  costs nothing.
* **With evidence in the record**, so the next device that disagrees names it:

```
shader arrays: Mali-G720 MC7: 2 of 8 shapes refused: in/out (0:62: S0032 …), return (…) -> lifting them
shader arrays: ANGLE (AMD, … OpenGL ES 3.2): all 8 shapes compile and link -> the game's bytes
```

## The reporter's own fix, which confirms the rule on the hardware

He also sent the ZIP of his hand fix with the log in it. His log is kept in the repo beside the port's own
run (`logs/mali/log alabaster fixmali.txt`, `logs/mali/log alabaster 0.7.1.txt`); the ZIP and the patched
shader files stay in the working tree as `logs/mali/fixmali.zip` and `logs/mali/fixmali/`, git-ignored —
they are game files, and this repository carries none. His sessions boot (`boot: complete in 8782ms,
1757 resources`, no `S0032`), so the rule is confirmed on a Mali-G720 — through the *other* door into it.
He spelled the **precision** out instead of respelling the declaration:

```glsl
highp vec4[4] computeWaveFactors(out vec2 globalFlow, vec2 flowDir)   // the sized spelling, precision given
mediump vec4 waves[4] = computeWaveFactors(globalFlow, flowDir);
flat in mediump vec2[4] v_flowDirs;
```

That is the sharpest datum in the file: it says the rule is exactly "a sized array type does not take the
shader's declared *default* precision", which is what the lift answers by spelling the array on the name.
He also changed art, which the port does **not** copy: a floor of `max(0.65, …)` on the water's alpha, two
`discard`s commented out (the water edge and the foam borders), the second wave's amplitude `0.5 → 0.6`,
and the analog-filter ramp's fourth colour `132 → 152`.

His log also carried a failure class the record had never seen. Raising the **default** precision in one
stage only leaves a shared uniform with two precisions, and this driver refuses the program:
`Unable to initialize the shader program …: Uniforms with the same name but different type/precision:
u_waveHeight` (and `u_cameraProjM` for the other water pair). The port's own GUI precision raise
(`ShaderPrecision`) touches `gui.vert` as well as every GUI fragment, and every GUI fragment in the game
pairs with `gui.vert` — the only GUI vertex shader there is — so no GUI program is mismatched. Checked
against the sources, not assumed; the hazard is recorded in `FINDINGS.md` §22.8 for the next precision
edit.

## What is verified, and what is not

| | state |
|---|---|
| the probe's shapes | the shim harness, 54 checks, three of them this gate's own: eight programs, every declaration the rule names present, and a refusing compiler's line naming the shapes |
| the probe, end to end | the **real shim in Chromium on a working stack**: `ANGLE (AMD, … OpenGL ES 3.2): all 8 shapes compile and link -> the game's bytes`, verdict "accepted"; the three real expanded shaders still compile on a fresh context afterwards, so the probe does not poison what compiles next |
| the lift itself | unchanged from v0.7.1: unit tests, `glslangValidator` on the expanded originals and the lifted ones, and a boot A/B through the port's own shim (both complete, 1 757 resources, 154 shader compiles, 0 compile and 0 link failures, identical active uniforms and attributes) |
| the probe **on Mali** | **not verified** — this project has no Mali hardware, and that device is the only one that ever disagreed. If its next record says `all 8 shapes compile and link` while the compile still fails, the remaining difference is the *context* the probe asks on (it makes its own, before the engine has one); the next lever is to run the same shapes on `window.g.gl` itself at first-shader time, through the await the gate already uses |

If you are on a Mali device whose boot froze at the loading bar: this build either boots, or leaves a
record that names the shape that was refused and the type that is still wrong. **Troubleshoot** in the
side menu (or on the setup screen) shows it, and **Share** sends it.

## Upgrading

Install over the previous build: your game and saves folders are kept, as are the side-menu switches and
your in-game Resolution. Nothing in the game's files is touched by the port — both the shader lift and the
probe are served in memory, per device.
