# Contributing

Issues and pull requests are welcome. **Hardware reports are the most valuable contribution there is**:
this port's hard bugs have all been found on devices the maintainer does not own, and the diagnostics
panel exists so that a screenshot of it is a complete report.

## AI-assisted contributions

This project is largely AI-written (see the README's [AI usage](README.md#ai-usage) section) and takes
AI-assisted contributions, on the same terms as anything else: **a human must be the author, understand
the change, and answer for it.**

* **Disclose it in the commit.** An AI-assisted commit carries a trailer naming the harness (the tool
  driving the model) and the model — this repository's own work was written in the **oh-my-pi** harness:

  ```
  Assisted-by: oh-my-pi DeepSeek V4.1 Flash
  ```

  `Generated-by:` is for the case where the patch is substantially AI-generated rather than AI-assisted
  (both are recognised; when in doubt, `Assisted-by:`). If the tool will not say which model it used,
  write the tool and `auto` — never invent a model string.

* **Never** credit an AI as a co-author. `Co-authored-by:` is for people; a model cannot hold
  accountability, so the claim is false the moment it is written.
* **Never** let an AI add `Signed-off-by:`. The Developer Certificate of Origin is a human attestation.
* **Read it before you send it.** A patch you cannot explain — in review, and later in a bug report — is
  not ready. Reviewers will ask questions; "the model wrote it" is not an answer.

## Working on the port

```sh
cd android
./gradlew :app:assembleDebug      # debug APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest  # JVM unit tests
node tools/test-shim-diagnostics.mjs   # the shim's own smoke tests
```

`tools/design/render.sh` renders the port's own icons, palette and screen mock-ups to PNGs without a
device or Android Studio; it reads the shipped vectors and `PortStyle.kt`, so it cannot drift from what
ships.

Two rules that are not negotiable:

* **No game files, ever.** The repository and the APK carry no game art, audio, data or code — the port
  reads the user's own copy at runtime. See `NOTICE.md`.
* **Measure on hardware.** A claim about a device belongs in `FINDINGS.md` with the command and the
  numbers, not in a commit message.

## Reporting a bug

Attach the diagnostics record: in the game, `Back` → `Troubleshoot`, then `Share`. It carries the
device, the port version, the game's own build, the WebView and GL backend, the folder counters, the
app's log and the engine's own console errors — enough for a failure nobody here can reproduce.
