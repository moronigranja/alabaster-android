## v0.7.3

A follow-up to v0.7.2, from the Mali phone that is testing it — and it carries the good news from that
device as well.

* **The Mali check works on that phone.** Its record says the compiler refused the declaration the check
  asks about, the port's fix ran, and the two water shaders now compile. This release adds the one case the
  check was still missing — the post-processing shader's own declaration pair — and makes the phone's
  record say **which shader files were actually fixed** and which were left alone. That line was in the
  developer log only, so the last report could not tell a file that was never fixed from one that was
  fixed wrongly.
* **If the WebView's ANGLE driver makes the game work on your phone, that is a real workaround** and it
  needs nothing from this release: ANGLE's own shader front end takes those declarations, so the driver
  bug never happens. On the driver called *native*, the port's own fix is what has to carry it.
* **Everything else is the same as v0.7.2.**

**On the Mali phone:** with the ANGLE driver the game boots and plays; with the native driver the water
shaders compile and one post-processing shader still fails. To help close it: use the game's **original**
shader files (not the hand-patched ones), start it on the native driver, and send the record —
**Troubleshoot** in the side menu, then **Share**. The record now names each file it did or did not fix.
If the water is missing under ANGLE, a screenshot of that spot helps too.

Detail, for the curious:
[FINDINGS §22.9](https://github.com/moronigranja/alabaster-android/blob/main/FINDINGS.md#229-the-second-device-run-the-probe-works-the-water-compiles-and-the-lifts-own-line-was-missing-2026-09-30),
the [fuller notes](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.7.3.md),
and [issue #4](https://github.com/moronigranja/alabaster-android/issues/4).
