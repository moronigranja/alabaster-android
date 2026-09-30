# Alabaster Dawn Android port v0.7.6 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are in the
[README](../README.md); the terse changelog is [docs/release-0.7.6.md](release-0.7.6.md); the technical
record is `FINDINGS.md` §22.12, which carries this release's evidence.

This release answers the question the reporting phone was asked: which spelling of the game's array
declarations *its* compiler takes. It takes one the port was not serving.

## What the phone answered

The record from issue #4 (Xiaomi 2412DPC0AG, Android 16, WebView beta 155, `Mali-G720 MC7`,
driver **49.1.0**) showed the gate firing, the lift running on four shader files, and then **one** shader
still refusing:

```
Shader Errors: data/shader/fragment/post/analog-filter.frag
0:62: S0032: no default precision defined for variable 'vec3[5]'
boot stall … at 94.1% of 1765 resources; pending 105 (shader=1 …)
  first pending: SHADER: texturedpost/analog-filter
```

Its own shader self-test then compiled that file in eight spellings, and the verdicts separate cleanly:

| spelling | verdict |
|---|---|
| the game's `colorRamp(noise.r, vec3[5](…))` | refused |
| the port's then-edit, `vec3[](` | refused |
| the parameter's brackets moved instead | refused |
| the parameter given `mediump` / `highp` | refused |
| **the ramp named, assigned element by element** | **compiles** |
| **a global `const` ramp, named** | **compiles** |

So what that front end refuses is the array **temporary**: a constructor passed as an argument has no
element precision to inherit, and no spelling of its own brackets can give it one. The repair is to stop
passing a temporary — which is what this release serves, keeping the game's own five ramp values in the
game's own order.

## What changed

* `ShaderArrays`' edit for `post/analog-filter.frag` is now the named ramp; the lift's contract is unchanged
  otherwise (only the served bytes, only on a device whose own compiler refused the game's declarations).
* The self-test's exploratory cases are gone — the device answered them. It now asks **8 cases**: each shader
  the port lifts, the game's bytes and the port's, so a future driver that starts refusing either spelling is
  still caught in one line of the record.
* `FINDINGS.md` §22.12 records the whole exchange, including two things the same run settled: the water
  family is already lifted correctly on that driver, and the parameter move in `lib/color-utils.glsl` is
  needed by nothing measured.

## How to check it

Start the game on the driver that stalls (**native**), then `Back` → `Troubleshoot` → **Test shader
spellings**. What to look for in the panel:

* `analog-filter~lifted compile=1` — the port's bytes, accepted by that front end;
* `shader self-test: 8 cases, 8 compile` if everything the port serves compiles;
* and the boot itself should pass 94 % instead of stalling there.

If a case reads `unavailable`, the app could not build it from your game files (a different build, or a
file you edited) — that is not your driver refusing it.

**If you hand-patched shader files in your game folder earlier, please put the game's originals back** —
otherwise the record measures your edits, not the port's. (The reporter's own patch, `fixmali.zip`, was the
same repair this release makes, but it also raised the water fragment shader to `highp` and typed one ramp
entry's blue as `152.` where the game has `132.` — neither is needed, and the second changes the post pass's
palette.)

Under **ANGLE** the port serves the game's own bytes untouched and the game runs — that is unchanged by this
release.

## Verified

* `ShaderArraysTest`, `ShaderVariantsTest` — the named ramp (both halves, the game's own values), CRLF game
  copies, a nested file that cannot be lifted, the 8-case list.
* The shim harness: **60 checks** in Chromium, including the self-test's page half.
* `glslangValidator` on the game's **real** `analog-filter.frag` (imports expanded) before and after the
  edit: the find-string matches its bytes exactly once, and both texts compile with no output.

Not verified here: the reporter's driver, which is the one that refuses. Which is why the run above is what
the next record is for.
