## v0.7.2

A correction to v0.7.1, for Mali phones. Nothing about the game or your files changes.

* **v0.7.1's fix for the Mali boot freeze did not run on the phone it was written for.** It asked that
  phone's shader compiler one question, using a *test* shader of its own. The phone accepted the test shader
  and refused the game's real ones, so the fix was skipped and its boot still froze. The question is now
  asked with the game's own shader declarations and the answer is checked harder, so a phone that refuses
  them gets the spelling it accepts. The phone's own record now names the shader and the reason if anything
  is still wrong, instead of leaving a loading bar that never finishes.
* **Everything else is the same as v0.7.1**: that release's fix for the Mali water and post-process
  shaders, the frame-rate slider and battery switch, and the Resolution handling.

**If you are on a Mali device and v0.7.1 froze at the loading bar:** install this build. If it still stops,
open **Troubleshoot** in the side menu (or on the setup screen) and press **Share** — the record says what
is left, and that report is what closes it. This project has no Mali hardware, so your device is the test.

Verified: everything v0.7.1 was verified for, unchanged. This release's change is covered by unit tests,
the shim test harness and a real browser boot; **not yet on a Mali phone**, which is what the next report
from one decides.

Detail, for the curious: [what happened on that device and what the new check does](https://github.com/moronigranja/alabaster-android/blob/main/FINDINGS.md#228-the-device-run-the-old-gate-answered-accepts-2026-09-30-issue-4)
and the [fuller notes](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.7.2.md).
