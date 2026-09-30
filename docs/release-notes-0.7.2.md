# Alabaster Dawn Android port v0.7.2 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are in the
[README](../README.md); the terse changelog is [docs/release-0.7.2.md](release-0.7.2.md); the technical
record is `FINDINGS.md` §22, and §22.8 is this release.

This release is one change: a correction to v0.7.1's fix for the Mali phone whose boot froze.

## What v0.7.1 got wrong

v0.7.1 served the game's array declarations in the spelling some Mali drivers accept — but only to a phone
whose compiler refused them, decided by asking that compiler one question. It asked with a **test** shader
of its own, and the phone answered "accepts" for the test shader while refusing the game's real ones. So the
fix was skipped, and that boot froze exactly as before:

| the phone's record (`logs/mali/log alabaster 0.7.1.txt`) | |
|---|---|
| `shader arrays: the page's compiler accepts the game's declarations` | at +12.8 s |
| `S0032 … 'vec3[5]'`, `'vec4[4]'`, `'vec4[4]'` on three shaders | at +19.6 s and +21.4 s |
| `boot stall … at 97.2 % … pending 49 (shader=3 …)` | at +29.9 s |

The player also sent the fix he had made by hand, and it boots on that phone — which is what confirms the
rule on real hardware, and confirms that his driver is the only one that disagrees with ours.

## What this release changes

The question is now asked with **the game's own shader declarations** instead of a test stand-in, one case
at a time, and the answer is checked harder than a plain "did it compile" (a refused case, a compiler the
port cannot ask, or a check that fails all mean "use the accepted spelling"). Every case's outcome goes
into the phone's record, so a device that disagrees names what it refused:

```
shader arrays: Mali-G720 MC7: 2 of 8 shapes refused: … -> lifting them
array declarations lifted in terra/data/shader/lib/water.glsl (9/9)
```

Nothing else changes: the same fix for the Mali water and post-process shaders as v0.7.1, the same frame
rate slider and battery switch, the same Resolution handling. The game's own files are never touched — all
of this is served in memory, per device.

## If you are on a Mali phone

Install this build. If the boot still freezes at the loading bar, the record now says what is left: open
**Troubleshoot** in the side menu (or on the setup screen), press **Share**, and send it. That report is
what closes the issue.

## What is verified, and what is not

| | state |
|---|---|
| everything v0.7.1 was verified for (the lift's equivalence to the game's bytes, the frame-rate slider, the resolution reset) | **verified**, unchanged |
| the new check: that it asks with the game's declarations, and that a compiler refusing them is reported | unit tests, the shim test harness, and the real shim run in a browser — including the case of a compiler that refuses them |
| the new check **on Mali** | **not verified** — this project has no Mali hardware, and that phone is the only device that ever disagreed. If it still reports acceptance while the compile fails, the next step is to ask on the engine's own GL context rather than one the port makes itself (`FINDINGS.md` §22.8) |

## Upgrading

Install over the previous build: your game and saves folders are kept, as are the side-menu switches and
your in-game Resolution.
