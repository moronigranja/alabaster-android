# AGENTS.md

Instructions for AI coding agents working in this repository. The human is the author and the one who
answers for the change; these rules exist so an agent's work fits the project.

## What this is

An unofficial Android **WebView host** for two Radical Fish Games NW.js titles (Alabaster Dawn and
CrossCode), plus a desktop controller fix. The port reads the game from a folder the user picks and
never edits it. One data table (`GameProfile`, `android/app/src/main/java/…/alabasterdawn/GameProfile.kt`)
holds everything per game; each game has one shim under `app/src/main/assets/`.

## Hard rules

* **No game files, ever.** Never add or commit game art, audio, data, code or fonts — not from the
  user's copy, not from the internet. The APK carries none of it. See `NOTICE.md`.
* **Never credit an AI in a commit.** Do not add `Co-authored-by:` for a tool and do not add
  `Signed-off-by:` at all — the DCO is a human attestation. AI-assisted commits get exactly one trailer:

  ```
  Assisted-by: <harness> <model>        # e.g. Assisted-by: oh-my-pi DeepSeek V4.1 Flash
  ```

* **The human owns the change.** Do not push, do not publish a release, do not force-push, and do not
  rewrite published history unless a human has explicitly asked for that specific action in this
  session.
* **No secrets.** The release keystore lives outside the repo (`android/keystore.properties` is
  gitignored); never print or commit its contents.

## Before you call a change done

```sh
cd android
./gradlew :app:testDebugUnitTest
cd .. && node tools/test-shim-diagnostics.mjs
```

Run the thing you changed and observe it; tests alone are not proof. A behavioural change to the port
is smoke-tested on the device when one is attached (`adb install -r` a release build — same signing
key, so it updates in place). Results that claim something about a device, a driver or a measurement go
in `FINDINGS.md` with the command and the numbers.

## Conventions

* UI text, comments and docs are terse and evidence-first; no marketing, no emoji.
* Keep each game's behaviour in `GameProfile` rather than branching on the game id elsewhere.
* The design harness (`tools/design/render.sh`) renders icons, palette and screen mock-ups to PNGs from
  the shipped sources — use it to check visual changes without a device.
* `TODO.md` is the queue; releases are batched and cut from it (see `CONTRIBUTING.md`).
