## v0.6.1

A bugfix for the side menu's picture position: with the picture aligned **Top** or **Bottom**, mouse
clicks landed nowhere near the thing clicked. Centre was unaffected.

* **The port moved the picture where the engine could not see it.** The engine's own mouse mapping
  (`Input.getMouseCoordsC` + `DISPLAY_SCALE.transformMouse`) reads the canvas element's `offsetTop`
  and then subtracts a *centred* letterbox (`deltaY/2`) from it — the engine believes the picture sits
  in the middle of the canvas element. The port changed only `object-position`, which moves the
  picture inside the element without moving the element, so every click was interpreted `deltaY/2`
  below the picture: half the black band. On a 1080x2340 phone at the default 640x360 rung that is
  **866 px** — the click lands on another part of the screen.
* **Now the element's layout box moves** (`top = desired − deltaY/2`) and the picture stays centred
  inside it, which is the box the engine measures from, so both sides agree again. Works in both
  display scales (`sharp-pixels` off and on), is recomputed every frame, and changes nothing about
  the picture's size or where it is drawn — only where the input thinks it is.
* Verified by booting the real engine in a browser and driving its own input pipeline with real mouse
  events: the same point on the picture now maps to the same game coordinate in Top, Centre **and**
  Bottom. The old code mapped the picture's centre to `y = -280.8` instead of `y = 180` in the same
  harness. `node android/tools/test-shim-diagnostics.mjs` grew 11 checks for this (24/24 green; 7 of
  them fail against the pre-fix shim).

Nothing else changed.

Full release notes: [docs/release-notes-0.6.1.md](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.6.1.md)
Install, requirements and known limitations: [README](https://github.com/moronigranja/alabaster-android#readme)
