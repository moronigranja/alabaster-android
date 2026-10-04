# Design preview harness

Renders the port's **own** look to images so UI and icon work has a look-at-it loop that
does not need a device or Android Studio. Change a colour, a path or a `*_DP` constant, run
one command, open the PNG.

```sh
tools/design/render.sh                 # -> tools/design/out/*.png  (gitignored)
tools/design/render.sh --keep-html     # also keep the generated .html next to the PNGs
tools/design/render.sh --out /tmp/pv   # render somewhere else
```

Requires `python3` and `chromium` (headless). No Python packages beyond the standard
library, no npm install.

## What it draws

| output | what it is for |
|---|---|
| `icons.png` | every `res/drawable/*.xml` glyph on the panel **and** on the light card (tinted), so a glyph that only reads on one background is obvious |
| `launcher.png` | the launcher mark over its background at the square / circle / squircle adaptive masks, with the 66dp **safe circle** drawn on top, plus 48/24dp |
| `palette.png` | `PortStyle.kt`'s colours as swatches (name + value) |
| `entry.png` | the entry screen: two row cards, their art tile, ⋮, the folder line, the auto-start switch |
| `entry-wide.png` | the same at the device's landscape window (823×384dp) — the orientation it actually ships in |
| `entry-empty.png` | the entry screen with no folders picked: the **＋ Add &lt;game&gt;** empty state |
| `menu.png` | the in-game side menu (rows, switches, the status block, Game selection / Troubleshoot / Exit) |
| `svg/*.svg` | each vector as plain SVG, for any external tool (Figma, Inkscape, `rsvg-convert`) |

## It reads the shipped sources, so it cannot drift

* `android/app/src/main/res/drawable/*.xml` — parsed back into SVG (paths, `fillColor`,
  `<group>` pivot/scale/translate), so the preview *is* the asset that ships.
* `…/PortStyle.kt` — the palette is scraped from the `const val … = 0x…` lines.
* `…/PortActivity.kt` — `HERO_DP`, `OVERFLOW_DP`, … are scraped for the mock-up geometry.

Nothing game-derived is read or written. The cards stand in for Alabaster Dawn's and
CrossCode's art with a plain labelled tile, so the repo still ships **no game art**
(see `NOTICE.md`).

## What it is not

The screen and menu PNGs are **HTML approximations** of the real Android views — good for
judging layout, palette, spacing and contrast, not for pixel diffs. Layout details that only
exist in the widget tree (ripples, focus, the exact `SeekBar` travel) are not reproduced.
When a mock-up and the device disagree, the device wins.

## Adding a preview

Add a function returning an HTML string and one entry to the `sheets` list in `preview.py`.
Keep reading any value you show from the app sources rather than hard-coding it — that is the
whole point of the harness.

## Related tools (vetted)

Sourced while looking for a better AI design loop. None are wired in; listed so the next
person does not re-search.

* **[vd-tool](https://github.com/stasson/vd-tool)** (packaging of Android Studio's own
  `Svg2Vector`) — converts SVG ⇄ VectorDrawable and *displays* a drawable. The right tool if
  an icon starts life in a vector editor rather than as hand-written path data.
* **[svg2vectordrawable](https://github.com/Ashung/svg2vectordrawable)** (MIT) and the newer
  **[svg-vectordrawable](https://github.com/IBRAHIMDANS/svg-to-vectordrawable)** — SVG → vector
  XML from the command line; the latter handles gradients/`gradientTransform` and fails loud.
  Both need `npm`/`bun`, which this harness deliberately avoids.
* **[android-svg-previewer](https://github.com/SunnyCloudYang/android-svg-previewer)** (VS Code)
  / **[vector-drawable-previewer](https://github.com/jmatsu/vector-drawable-previewer)** (Chrome) —
  live preview of a drawable while editing it.
* **[Material Symbols](https://github.com/google/material-design-icons)** — Apache-2.0, so
  usable unmodified, but the port's icons are **original**; keep it that way for consistency.
* Design-file integration via **MCP**: **Penpot** (open source, self-hostable; `npx @penpot/mcp`)
  and Figma both ship MCP servers that let an agent read/write a design file. Wire one only if
  the design is meant to live in a design file; for a palette this small the harness above is
  faster.
* AI design agents worth knowing about: **[SuperDesign](https://github.com/superdesigndev/superdesign)**
  (open-source IDE design agent, now a web app), **[OpenPencil](https://github.com/CrazyForks/openpencil)**
  (AI-native vector canvas with its own MCP server and Compose/HTML export),
  **[OpenUI](https://github.com/thesysdev/openui)** (a compact streaming UI language for models).
  All target web/native component UIs; none understands Android vector drawables or the port's
  widget tree, which is what this harness covers.
