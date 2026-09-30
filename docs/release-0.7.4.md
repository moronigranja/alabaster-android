## v0.7.4

Diagnostics, plus the one thing a Mali phone needed: a way to tell us which shader spelling *it* takes.

* **The port now asks your phone instead of guessing.** Troubleshoot has a **Test shader spellings**
  button: it compiles the game's own shaders in every spelling the port could serve — on your device's own
  graphics driver — and writes one line per spelling into the record. If your game stops on the loading
  bar, that button plus **Share** is now a complete report of what your driver accepts.
* **The record says which GL driver you got**: `driver=native` or `driver=ANGLE`. On Mali phones that is
  the difference between the game starting and freezing, and until now it had to be read out of a
  renderer string.
* **Troubleshoot has an "OpenGL driver" button** that opens ANGLE Preferences (or Developer options),
  next to a sentence saying what to set it to. An app cannot change that setting itself — Android keeps it
  for privileged apps — and shipping ANGLE inside the port could not help, because the WebView renders
  with its own graphics stack.
* **Everything else is the same as v0.7.3.**

Measured from four Mali generations on a device farm (Pixel 8a, Pixel 7, Pixel 6, Galaxy A35): **all of
them accept the game's own shaders**, so this refusal is a property of newer driver revisions, not of a
GPU generation — which is exactly why the port decides per device and why your phone's own answer is the
one that matters. Detail:
[FINDINGS §22.10-§22.11](https://github.com/moronigranja/alabaster-android/blob/main/FINDINGS.md#2210-measured-on-four-mali-generations-none-of-them-refuse-it-2026-09-30),
the [fuller notes](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.7.4.md),
and [issue #4](https://github.com/moronigranja/alabaster-android/issues/4).
