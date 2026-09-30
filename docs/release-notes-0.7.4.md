# Alabaster Dawn Android port v0.7.4 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are in the
[README](../README.md); the terse changelog is [docs/release-0.7.4.md](release-0.7.4.md); the technical
record is `FINDINGS.md` §22, and §22.10-§22.11 are this release.

This release is diagnostics and one new capability, for one stubborn problem: a Mali phone whose driver
refuses some of the game's shader declarations, so the game stops on its loading bar.

## What a device farm established first

A shader probe (a small app, `tools/mali-probe/`, that compiles the game's own shaders on a device's
*native* graphics driver) ran on four Mali generations at Firebase Test Lab:

| device | front end | driver | cases refused |
|---|---|---|---|
| Pixel 8a | Mali-G715 | r44 | **0 of 26** |
| Pixel 7 | Mali-G710 | r38 | **0 of 28** |
| Pixel 6 | Mali-G78 | r38 | **0 of 28** |
| Galaxy A35 5G | Mali-G68 | r38 | **0 of 28** |

Every case compiled — the game's own bytes, the spelling the port lifts them to, the post pass with each
half of its declaration changed alone, and the two shapes that other projects report this driver family
refusing. Together with the reporting phone being a **Mali-G720 on driver r49**, that points at the *driver
revision* rather than the GPU generation: a property that can arrive with a system update, on a device
somebody already owns. It is also the argument for keeping the port's decision per device: a phone whose
compiler accepts the game's bytes is served them, and one whose compiler refuses them is served the
spelling it takes. (We cannot reproduce the refusal here at all — which is why this release asks your
phone.)

## What this release adds

**A shader self-test, one tap, in Troubleshoot.** *Test shader spellings* compiles the game's own fragment
shaders — expanded the way the engine expands them, built on your device from your game files — in every
spelling the port could serve: the post-processing pass as the game writes it, with the port's fix, with
each half of that fix alone, and with each candidate repair; the water shaders in both spellings. Each one
is compiled on **your** graphics driver, one line each into the diagnostics record, then a summary:

```
shader self-test: analog-filter~original compile=0 log=0:62: S0032: no default precision defined for variable 'vec3[5]'
shader self-test: analog-filter~lifted compile=1 log=-
...
shader self-test: 14 cases, 13 compile, refused=analog-filter~original
```

So a report from a phone that still freezes no longer needs six rounds of "try this spelling": the record
says which one that phone accepts, and the next build can serve it. Nothing leaves your device; the panel
reopens with the verdicts in it, ready for **Share**.

**The record now says which graphics driver the page got**: `driver=native` or `driver=ANGLE`. On Mali
phones that single field is the difference between the game starting and the boot freezing — the native
driver is the one that can refuse the game's declarations, while ANGLE has its own shader front end and
never shows them to the driver.

**Troubleshoot has an "OpenGL driver" button**, which opens ANGLE Preferences — the screen under *Settings
→ Developer options* where a per-app driver can be chosen — or Developer options if the phone has no such
app. Next to it is the sentence that says what to set: **ANGLE** for this app. The port cannot do it for
you: that setting is a privileged one (`Settings.Global`), and shipping ANGLE inside the port would not
help either, because the WebView renders with its own graphics stack, not the app's.

## What is verified, and what is not

| | state |
|---|---|
| the self-test's cases (expansion, every spelling, the engine's `#define`s, an unreadable file) | `ShaderVariantsTest` |
| the page half (fetch each case, compile it, report the verdicts, tell the app it is done) | the shim harness, now 58 checks, and the real shim run in Chromium against the fourteen generated cases — all fourteen compiled, one line each, summary delivered |
| the four-generation measurement | the device-farm run above; its logs are in `logs/mali/probe/` |
| the panel button's click-through on a phone | **not exercised**: the only phone in reach was behind a secure lock screen, and Android hands out black frames from `screencap` for a locked one, so the UI cannot be driven over adb. Everything behind the button is covered above; the button is three lines of `evaluateJavascript` |
| the Mali fix on a refusing phone | still the reporter's next record — now with the self-test in it |

## Upgrading

Install over the previous build: your game and saves folders are kept, as are the side-menu switches and
your in-game Resolution. If you hand-patched shader files in your game folder earlier, please put the
game's originals back before testing — the port's own fix is what the record then measures.
