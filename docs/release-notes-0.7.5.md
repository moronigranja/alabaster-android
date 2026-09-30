# Alabaster Dawn Android port v0.7.5 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are in the
[README](../README.md); the terse changelog is [docs/release-0.7.5.md](release-0.7.5.md); the technical
record is `FINDINGS.md` §22, and §22.11 covers this feature end to end.

This release fixes v0.7.4's new spelling test. Both defects were found by running it on a real phone, which
is the point of the feature — and both would have made a report from a player misleading.

## What the hardware run found

The test compiles the game's own shaders in every spelling the port could serve, on the phone's own driver,
and writes one line per case into the diagnostics record. On the maintainer's S22 Ultra it ran the whole
chain — the panel button, the case building, the compiles on the engine's own context, the record lines,
the summary — and produced this:

```
shader self-test: analog-filter~local-ramp compile=0 log=ERROR: 1:1: '' : syntax error
shader self-test: water-plane~lifted compile=0 log=ERROR: 0:610: 'null' : syntax error
```

Two different bugs behind those two lines:

* **Line endings.** That phone's game copy has CRLF shader files (a copy made on, or moved through,
  Windows). The port's own rewrite has normalised CRLF since it was written, so the *fix* is unaffected —
  but the test's multi-line edits did not match, so the cases built from them were served as nothing. The
  test now normalises line endings the same way the port does.
* **A `null` spliced into a shader.** When a *nested* file could not be expanded, Kotlin's
  `StringBuilder.append(String?)` appended the text `null`, and the driver's complaint about it read like a
  driver refusal of a real shader. A case that cannot be built now fails as a whole, is served as nothing,
  and the app says why in the record — `shader self-test case …: the game's bytes carry none of its text` —
  while the page reports it as **unavailable** rather than refused.

With both fixed, the same tap on the same phone reports:

```
shader self-test: 14 cases, 14 compile
```

## Using the test

1. Start the game (the test needs the running page — the setup screen's **Troubleshoot** shows the record
   but has no page to ask).
2. **Back** opens the port's side menu → **Troubleshoot**.
3. Tap **Test shader spellings**, wait for the panel to come back with the verdicts.
4. **Share**.

Two lines worth knowing:

* a case reported `unavailable` means the *app* could not build it from your game files (a different build,
  or a file you edited) — not that your driver refused it;
* `driver=native` vs `driver=ANGLE` in the record is which graphics driver the page got; on Mali phones
  that is the difference between the game starting and the boot freezing.

## What is verified, and what is not

| | state |
|---|---|
| the two fixes | `ShaderVariantsTest` grew a CRLF case and a nested-failure case (126 unit tests, 0 failures) |
| the page half, including an unbuildable case | the shim harness, now 59 checks |
| the whole chain on a phone | **verified**: the panel button → the case building → the compiles on the engine's own context → the record lines → the summary, `14 cases, 14 compile` |
| the test on a **Mali** phone whose driver refuses the game's shaders | still the reporter's next record; that answer is what the next build serves |

## Upgrading

Install over the previous build: your game and saves folders are kept, as are the side-menu switches and
your in-game Resolution. If you hand-patched shader files in your game folder earlier, please put the
game's originals back before testing — the port's own fix is what the record then measures.
