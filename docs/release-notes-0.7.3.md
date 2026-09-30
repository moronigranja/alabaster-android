# Alabaster Dawn Android port v0.7.3 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are in the
[README](../README.md); the terse changelog is [docs/release-0.7.3.md](release-0.7.3.md); the technical
record is `FINDINGS.md` §22, and §22.9 is this release.

This release is a follow-up to v0.7.2, after two more reports from the Mali phone it was written for.

## What those reports said

Both runs are v0.7.2, one per WebView GL driver.

**With the ANGLE driver** the game **boots and plays**: `shader arrays: ANGLE (ARM, … Mali-G720 MC7 …): all
8 shapes compile and link -> the game's bytes`, no lift, `boot: complete`. That is expected and correct —
ANGLE has its own shader front end, so the driver bug this project has been chasing never happens on that
path. The reporter's own words for that run are that it works, **but the water is invisible** (a rendering
question: nothing failed to compile in that record).

**With the native driver** it still stops, but the picture is much narrower than before:

| the native run's record | |
|---|---|
| `shader arrays: Mali-G720 MC7: 1 of 8 shapes refused: return (0:15: S0032 … 'vec4[4]') -> lifting them` | the new check found it, and the fix ran |
| no `Shader Errors` for the two water shaders | **they compile now** |
| `Shader Errors: …/post/analog-filter.frag — 0:62: S0032 … 'vec3[5]'` | one shader is left |
| `boot stall … 99.0 % …, pending 18 (shader=1 …)` | so the boot still stops at one shader |

## What this release changes

Two things, both about *knowing* rather than about the fix itself (the fix is unchanged):

* **The record now says which files were fixed.** Whether a file was lifted was written to the developer
  log only, so the last report could not distinguish "this file was never lifted (its bytes do not match)"
  from "this file was lifted and the driver still refused it" — and those two need different answers. Each
  lifted file now reports itself:
  `array declarations lifted in terra/data/shader/lib/water.glsl (9/9)`, or, when the file's bytes carry
  none of the declarations the port knows:
  `array declarations NOT lifted in … (0/1): its bytes carry none`.
* **The check asks one more question.** The post-processing shader is a *macro-sized parameter called with
  a sized constructor* — two separate things that each compile on that driver, while the pair is what the
  file actually writes. It is now compiled as one shape of its own (nine in all), so a device that refuses
  the pair and accepts the halves is no longer answered wrongly.

## What is verified, and what is not

| | state |
|---|---|
| the check's nine shapes, and that a compiler refusing them is reported with the shape named | the shim test harness (55 checks) and a real browser run of the port's own shim — every shape compiles and links on a stack that accepts the game's bytes |
| the lift's bytes | unchanged from v0.7.1/0.7.2: unit tests, `glslangValidator`, and a boot A/B in Chromium with identical active uniforms and attributes |
| the per-file record line | unit-tested at the same seam as the lift itself |
| all of it **on Mali** | **not verified here** — this project has no Mali hardware. The phone's own reports are the test, and with v0.7.2's check now confirmed on it, the next one either boots or names the file that is still wrong |

## Upgrading

Install over the previous build: your game and saves folders are kept, as are the side-menu switches and
your in-game Resolution. If you hand-patched shader files in your game folder earlier, please put the
game's originals back before testing this build — the record is only readable when the port's own fix is
what is being served.
