## v0.7.5

A fix to v0.7.4's new spelling test, found by running it on a real phone — and the same test now answers
cleanly.

* **Two defects in the spelling test, both visible only on hardware.** One: game copies whose shader files
  use Windows line endings (the maintainer's own phone, and quite possibly others) made the multi-line
  cases come back *unavailable* while the single-line ones worked — the test now normalises line endings
  the way the port's own rewrite already did. Two: a case that could not be built put the word `null` into
  the shader it handed the driver, and the resulting syntax error read like a driver refusal. A case that
  cannot be built now fails as a whole and says so, instead of looking like a refusal.
* **Verified on a phone**: the panel button, the case building, the compiles on the engine's own context,
  the record lines and the summary — `shader self-test: 14 cases, 14 compile`.
* **Everything else is the same as v0.7.4.**

**Using it:** start the game first (the test needs the running page — the setup screen's Troubleshoot shows
the record but cannot run the test), then **Back → Troubleshoot → Test shader spellings**, and **Share**
what comes back.

Detail: [FINDINGS §22.11](https://github.com/moronigranja/alabaster-android/blob/main/FINDINGS.md#2211-asking-the-phone-instead-of-guessing-2026-09-30-v074),
[fuller notes](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.7.5.md),
[issue #4](https://github.com/moronigranja/alabaster-android/issues/4).
