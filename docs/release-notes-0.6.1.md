# Alabaster Dawn Android port v0.6.1 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are
in the [README](../README.md); the terse changelog is [docs/release-0.6.1.md](release-0.6.1.md).

This is a one-bug fix. Reported against v0.6.0: **with the picture aligned Top, mouse clicks do
nothing.** The picture did move to the top — that part always worked — but the game was reading every
click as if the mouse were somewhere else, about half a screen away. Bottom was broken the same way,
mirrored; Centre was fine.

## The engine assumes a centred picture, and the port moved the picture without telling it

The engine maps a mouse event to game coordinates in two steps (`bundle.js`):

```js
static getMouseCoordsC(dest, mouseX, mouseY, dom) {     // dom = g_system.canvas
  let el = dom; c_input.setC(0, 0);
  while (el != null) { c_input.x += el.offsetLeft; c_input.y += el.offsetTop; el = el.offsetParent; }
  dest.x = (mouseX - c_input.x); dest.y = (mouseY - c_input.y);       // page coords -> element box
}
```

and then `g_system.displayScale.transformMouse(this.mouse)`, where every display scale computes

```js
const scale1 = Math.min(screenSizeX / canvasSize.x, screenSizeY / canvasSize.y);
const deltaY = screenSizeY - scale1 * canvasSize.y;
mouse.y = (mouse.y - deltaY / 2) * 1 / scale1 / scale;
```

with `screenSizeX/Y = canvas.clientWidth`/`clientHeight` — the **canvas element's client box**, not
the window. In other words the engine has exactly one model of the canvas: *the picture is centred
inside the element*, by `deltaY/2` (half the black band) in each axis. The `offsetTop` walk is what
makes any move of the element box self-correcting — the engine measures from wherever the element is.

The port's **Game position** setting moved the picture with `object-position: 50% 0%` (Top) /
`50% 100%` (Bottom). That changes where the buffer is drawn inside the element and nothing else: the
element's `offsetTop` stayed 0, so the engine still subtracted `deltaY/2` and read every click
`deltaY/2` below the picture.

How far off that is depends only on the device's black bands — half of them:

| | viewport / buffer | deltaY/2, as the engine reads it |
|---|---|---|
| test harness below | 400x800, buffer 1280x720, scale 2 | 287.5 screen px = **460.8 game px** |
| phone, default 640x360 rung | 1080x2340, picture 1080x607.5 | **866 screen px** |

Centre is correct by construction (`deltaY/2` *is* the centre), which is why only Top and Bottom
were ever wrong and why the device pass that verified the three positions — it measured where the
picture was **drawn** (`236…1612` / `0…1376` / `471…1847`) — did not catch it.

## The fix: move the element's layout box, leave the picture centred inside it

`assets/ada-shim.js`, `applyViewAlign`/`alignTopFor`. The canvas is `position: absolute; inset: 0;
margin: auto`, so writing `top` pins it, and the picture stays at the element's own centre
(`object-position` is not touched at all any more):

```
top = desired - deltaY/2            desired = 0 (Top) | boxH - pictureH (Bottom)
deltaY = canvas.clientHeight - scale1 * canvas.height       // measured from the element itself
```

The box's top and the picture's visual top then differ by exactly the `deltaY/2` the engine
subtracts, so the engine's model of the picture is the picture. The formula is mode-agnostic: with
`sharp-pixels` on the element is pinned to an integer multiple of the buffer, `deltaY` is 0, and it
degenerates to pinning the box to Top/Bottom — which is what the `sharp-pixels`-on path already did
correctly. No picture size, resolution or rendering path changes; only `top` is written, a property
the engine never touches itself (it writes `width`, `height`, `display`, `imageRendering`, `cursor`).
Non-centre positions are re-measured each frame, and `top` is rounded to whole pixels because
`offsetTop` reads as an integer. Before the first layout pass every measurement is 0 and nothing is
written — a `0px` there would have moved the picture to the top of the window.

## Verification

The real engine was booted in a headless Chromium against a local static server, with the game's own
`engine.css`, the shim asset as it ships, and its own input pipeline driven by real (CDP) mouse
events:

| alignment | canvas `top` | picture on screen | `g_input.mouse` at the picture's centre / at 25 % of its height |
|---|---|---|---|
| Centre | `""` | 287.5–512.5 | `(320, 180)` / `(320, 88.8)` |
| Top | `-287px` | 0.5–225.5 | `(320, 180)` / `(320, 88.8)` |
| Bottom | `288px` | 575.5–800.5 | `(320, 180)` / `(320, 88.8)` |

Same point on the picture, same game coordinate, in every alignment — exact to the sub-pixel
(viewport 400x800, buffer 1280x720, scale 2; 0.625 screen px per game px). The **old** code on the
same live engine maps the picture's centre to `(320, -280.8)` instead of `(320, 180)`: the 460.8 px
miss, reproduced end-to-end rather than argued.

`node android/tools/test-shim-diagnostics.mjs` now covers the arithmetic with no device and no
browser — three positions in both display scales, the engine's own model of the picture's top, that
`object-position` is left alone, and the not-yet-measured case. 24/24 checks pass; 7 of the 11 new
ones **fail** against the pre-fix shim. The same probe was also run against the engine's mapping
formulas for the `sharp-pixels`-on geometry and for a viewport resize while aligned.

Honest limits of this verification: no phone was attached, so the Android mouse path itself (a real
`input mouse tap` at a title-screen entry with Top selected) was **not** re-run for this release. The
harness above exercises the engine's own event listeners, its `offsetParent` walk and its live
`g_system` values, which is the code the click depends on; the Android side of that path — a mouse
click reaching the page — is unchanged from v0.6.0. See `FINDINGS.md` §15.

## Everything else is v0.6.0

Nothing else changed: the mouse and keyboard work, the Back fix, the pad, the side menu, the
diagnostics record. See the [v0.6.0 notes](release-notes-0.6.0.md).
