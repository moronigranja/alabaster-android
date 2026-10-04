#!/usr/bin/env python3
"""Render the port's own look to images, without a device.

The point is a fast look-at-it loop for UI and icon work: change a colour, a path or a
dp constant, run this, open the PNG. Everything is read from the *shipped* sources, so a
preview cannot drift from what the app draws:

  * every ``app/src/main/res/drawable/*.xml`` vector is turned back into an SVG and drawn
    on the panel and on the light card, so a "can't see the glyph" problem shows here;
  * the launcher mark is composited over its background at the three adaptive-icon masks
    (square, circle, squircle) with the 66dp safe circle drawn on top, so a mark that
    would be clipped by a launcher is obvious before it ships;
  * ``PortStyle.kt``'s palette drives the swatch sheet, and ``PortActivity.kt``'s ``*_DP``
    constants drive the screen mock-ups;
  * the entry screen (populated and empty) and the side menu are laid out as HTML at the
    device's own dp viewport and screenshotted with headless Chromium.

Nothing game-derived is read or written: the mock-ups stand in for the games' art with a
plain labelled tile, so this repo still ships no Alabaster Dawn or CrossCode art
(see NOTICE.md). The mock-ups are deliberately *approximations* of the real views (the
Android widgets are not reproduced) - they are for judging layout, palette and contrast,
not for pixel diffing.

Usage:
    tools/design/render.sh                 # -> tools/design/out/*.png
    python3 tools/design/preview.py --keep-html
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from string import Template
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
MAIN = REPO / "android/app/src/main"
RES = MAIN / "res"
DRAWABLES = RES / "drawable"
PORT_STYLE = MAIN / "java/io/github/moronigranja/alabasterdawn/PortStyle.kt"
PORT_ACTIVITY = MAIN / "java/io/github/moronigranja/alabasterdawn/PortActivity.kt"

ANDROID = "{http://schemas.android.com/apk/res/android}"

# The device the port is developed against (an S22): its own dp window, from the boot log.
# The entry screen is shown in landscape on this device (the games are landscape), so its mock is
# rendered at both: portrait clips the longer folder paths, landscape is what actually ships.
WINDOW_W, WINDOW_H = 384, 823
WIDE_W, WIDE_H = 823, 384

# The launcher's mask-safe circle: the 108dp adaptive-icon viewport's inner 66dp.
SAFE_RADIUS_DP = 33.0
ICON_VIEWPORT = 108.0


# ------------------------------------------------------------------ source reading ----


def load_palette() -> dict[str, str]:
    """PortStyle.kt's colour constants, as CSS colours (the single source of truth)."""
    text = PORT_STYLE.read_text(encoding="utf-8")
    palette: dict[str, str] = {}
    for name, hexval in re.findall(
        r"const val ([A-Z][A-Z0-9_]*)\s*=\s*0x([0-9A-Fa-f]{6,8})\s*(?:\.toInt\(\))?", text
    ):
        palette[name] = _css(hexval)
    if not palette:
        sys.exit(f"no colours found in {PORT_STYLE}")
    return palette


def load_geometry() -> dict[str, int]:
    """PortActivity.kt's ``*_DP`` layout constants, so the mock-ups use the real sizes."""
    text = PORT_ACTIVITY.read_text(encoding="utf-8")
    geom: dict[str, int] = {}
    for name, value in re.findall(r"private const val ([A-Z][A-Z0-9_]*_DP)\s*=\s*(\d+)", text):
        geom[name] = int(value)
    return geom


def _css(hexval: str) -> str:
    """Android ``#AARRGGBB``/``#RRGGBB`` to a CSS colour (alpha folded in as rgba)."""
    h = hexval.lstrip("#")
    if len(h) == 6:
        return "#" + h
    a = int(h[0:2], 16)
    r, g, b = int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16)
    return f"rgba({r},{g},{b},{a / 255:.3f})"


# ------------------------------------------------------------------ vector drawing ----


class Vector:
    """An Android vector drawable, kept as its SVG body plus the viewport it is drawn in."""

    def __init__(self, name: str, vw: float, vh: float, body: str):
        self.name = name
        self.vw = vw
        self.vh = vh
        self.body = body

    def svg(self, extra: str = "", tint: str | None = None) -> str:
        body = re.sub(r'fill="[^"]*"', f'fill="{tint}"', self.body) if tint else self.body
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.vw:g} {self.vh:g}">'
            f"{body}{extra}</svg>"
        )


def parse_vector(path: Path) -> Vector | None:
    try:
        root = ET.fromstring(path.read_text(encoding="utf-8"))
    except ET.ParseError as e:
        print(f"  ! {path.name}: {e}", file=sys.stderr)
        return None
    if root.tag != "vector":
        return None
    vw = float(root.get(ANDROID + "viewportWidth", "0"))
    vh = float(root.get(ANDROID + "viewportHeight", "0"))
    body = "".join(_element_svg(el) for el in root)
    return Vector(path.stem, vw, vh, body)


def _element_svg(el: ET.Element) -> str:
    if el.tag == "path":
        fill = el.get(ANDROID + "fillColor") or el.get(ANDROID + "strokeColor") or "#FFFFFF"
        alpha = el.get(ANDROID + "fillAlpha")
        extra = f' fill-opacity="{alpha}"' if alpha else ""
        return f'<path fill="{_css(fill)}"{extra} d="{el.get(ANDROID + "pathData", "")}"/>'
    if el.tag == "group":
        transform = _group_transform(el)
        inner = "".join(_element_svg(child) for child in el)
        if transform:
            return f'<g transform="{transform}">{inner}</g>'
        return inner
    return ""


def _group_transform(el: ET.Element) -> str:
    px, py = el.get(ANDROID + "pivotX"), el.get(ANDROID + "pivotY")
    sx = float(el.get(ANDROID + "scaleX", "1"))
    sy = float(el.get(ANDROID + "scaleY", "1"))
    tx = float(el.get(ANDROID + "translateX", "0"))
    ty = float(el.get(ANDROID + "translateY", "0"))
    parts = []
    if tx or ty:
        parts.append(f"translate({tx:g} {ty:g})")
    if (sx != 1 or sy != 1) and px is not None and py is not None:
        parts.append(f"translate({px} {py}) scale({sx:g} {sy:g}) translate({-float(px):g} {-float(py):g})")
    return " ".join(parts)


def load_vectors() -> dict[str, Vector]:
    vectors: dict[str, Vector] = {}
    for path in sorted(DRAWABLES.glob("*.xml")):
        vector = parse_vector(path)
        if vector is not None:
            vectors[vector.name] = vector
    return vectors


# ------------------------------------------------------------------ the sheets -------

SHEET = Template(
    """<!doctype html><meta charset="utf-8"><style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:$black;color:$text;font-family:'DejaVu Sans',system-ui,sans-serif}
.sheet{padding:20px}
h2{font-size:13px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:$dim;margin-bottom:12px}
.grid{display:flex;flex-wrap:wrap;gap:14px}
.cell{width:118px;text-align:center}
.tile{height:56px;border-radius:14px;display:flex;align-items:center;justify-content:center}
.dark{background:$panel;border:1px solid $hairline}
.light{background:$card}
.dark svg{width:26px;height:26px}
.light svg{width:26px;height:26px}
.cap{font-size:9.5px;color:$dim;margin-top:5px;word-break:break-all;line-height:1.25}
</style><body><div class="sheet"><h2>$title</h2>$rows</div></body>"""
)


def cell(vector: Vector, palette: dict[str, str]) -> str:
    # On the panel as shipped, and on the card tinted the way a row would tint it: a glyph
    # that vanishes on one of the two is the kind of thing this sheet exists to catch.
    return (
        '<div class="cell">'
        f'<div class="tile dark">{vector.svg()}</div>'
        f'<div class="tile light" style="margin-top:6px">{vector.svg(tint=palette["INK"])}</div>'
        f'<div class="cap">{vector.name}</div></div>'
    )


def icons_html(vectors: dict[str, Vector], palette: dict[str, str]) -> str:
    glyphs = [v for n, v in vectors.items() if not n.startswith("ic_launcher")]
    rows = '<div class="grid">' + "".join(cell(v, palette) for v in glyphs) + "</div>"
    return SHEET.substitute(
        black=palette["PANEL"], text=palette["TEXT"], dim=palette["DIM"], panel=palette["PANEL"],
        card=palette["TILE"], hairline=palette["HAIRLINE"], title="Menu glyphs - panel and tile",
        rows=rows,
    )


LAUNCHER = Template(
    """<!doctype html><meta charset="utf-8"><style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:$black;color:$text;font-family:'DejaVu Sans',system-ui,sans-serif}
.sheet{padding:20px}
h2{font-size:13px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:$dim;margin-bottom:14px}
.row{display:flex;gap:22px;align-items:flex-end}
.col{text-align:center}
.mask{position:relative;overflow:hidden;background:$card;display:flex;align-items:center;justify-content:center}
.mask svg{width:100%;height:100%}
.sq{border-radius:0}.ci{border-radius:50%}.rq{border-radius:22.5%}
.safe::after{content:"";position:absolute;left:50%;top:50%;width:$safed;height:$safed;margin-left:${safem};margin-top:${safem};
  border:1px dashed $accent;border-radius:50%;opacity:.55}
.cap{font-size:11px;color:$dim;margin-top:6px}
</style><body><div class="sheet"><h2>Launcher mark - masks and the 66dp safe circle</h2>
<div class="row">$cols</div></div></body>"""
)


def launcher_html(vectors: dict[str, Vector], palette: dict[str, str]) -> str:
    composed = composed_mark(vectors)
    cols = []
    for label, cls, size in [("square", "sq", 132), ("circle", "ci", 132), ("squircle", "rq", 132),
                             ("48dp", "ci", 48), ("24dp", "ci", 24)]:
        safe = " safe" if label in ("square", "circle", "squircle") else ""
        cols.append(
            f'<div class="col"><div class="mask {cls}{safe}" style="width:{size}px;height:{size}px">'
            f'{composed}</div><div class="cap">{label}</div></div>'
        )
    safe_d = SAFE_RADIUS_DP * 2 / ICON_VIEWPORT * 132
    return LAUNCHER.substitute(
        black=palette["PANEL"], text=palette["TEXT"], dim=palette["DIM"], card=palette["TILE"],
        accent=palette["ACCENT"], cols="".join(cols), safed=f"{safe_d:.1f}px",
        safem=f"{-safe_d / 2:.1f}px",
    )


PALETTE = Template(
    """<!doctype html><meta charset="utf-8"><style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:$black;color:$text;font-family:'DejaVu Sans',system-ui,sans-serif;padding:20px}
h2{font-size:13px;font-weight:600;letter-spacing:.08em;text-transform:uppercase;color:$dim;margin-bottom:14px}
.row{display:flex;flex-wrap:wrap;gap:12px}
.sw{width:150px}
.chip{height:52px;border-radius:12px;border:1px solid $hairline}
.cap{font-size:11px;margin-top:5px}.cap b{display:block;color:$dim;font-weight:400}
</style><body><h2>PortStyle palette</h2><div class="row">$chips</div></body>"""
)


def palette_html(palette: dict[str, str]) -> str:
    chips = ""
    for name, css in palette.items():
        chips += (
            f'<div class="sw"><div class="chip" style="background:{css}"></div>'
            f'<div class="cap">{name}<b>{css}</b></div></div>'
        )
    return PALETTE.substitute(black=palette["PANEL"], text=palette["TEXT"], dim=palette["DIM"],
                              hairline=palette["HAIRLINE"], chips=chips)


SCREEN = Template(
    """<!doctype html><meta charset="utf-8"><style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:$wpx;height:$hpx}
body{background:#000;color:$text;font-family:'DejaVu Sans',system-ui,sans-serif}
.screen{width:$wpx;height:$hpx;padding:20px;display:flex;flex-direction:column}
.header{display:flex;align-items:center;justify-content:space-between}
.title{font-size:18px}
.btn{border:1px solid $hairline;border-radius:12px;padding:11px 16px;font-size:15px}
.card{display:flex;align-items:center;background:$panel;border:1px solid $hairline;border-radius:20px;
  padding:8px;margin-top:10px}
.tile{width:${tile}px;height:${tile}px;border-radius:12px;background:$tilecolor;flex:0 0 ${tile}px;
  overflow:hidden}
.tile svg{width:100%;height:100%}
.meta{flex:1;margin-left:16px;min-width:0}
.nm{font-size:16px}
.files{font-size:11px;color:$dim;margin-top:4px;line-height:1.15}
.acts{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;flex:0 0 auto}
.play{width:${play}px;height:${play}px;border-radius:50%;background:$accent;display:flex;
  align-items:center;justify-content:center}
.play svg{width:18px;height:18px}
.dots{width:${dots}px;text-align:center;font-size:24px}
.check{display:flex;align-items:center;gap:10px;margin-top:10px;font-size:14px}
.box{width:18px;height:18px;border:2px solid $text;border-radius:3px}
.box.on{background:$text;position:relative}
.box.on::after{content:"";position:absolute;left:4px;top:1px;width:5px;height:10px;border:solid #000;
  border-width:0 2px 2px 0;transform:rotate(45deg)}
.status{margin-top:10px;font-size:13px;color:$dim}
.version{margin-top:10px;font-size:12px;color:$dim}
.add{margin-top:10px;border:1px solid $hairline;border-radius:12px;padding:12px 16px;font-size:15px}
.plus{font-weight:700;margin-right:10px}
</style><body><div class="screen">$body</div></body>"""
)

MENU = Template(
    """<!doctype html><meta charset="utf-8"><style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:$wpx;height:$hpx}
body{background:#0b1016;color:$text;font-family:'DejaVu Sans',system-ui,sans-serif;position:relative}
.game{position:absolute;inset:0;background:#1d2a38}
.scrim{position:absolute;inset:0;background:$scrim}
.panel{position:absolute;top:0;right:0;width:340px;height:$hpx;background:$panel;padding:16px;
  display:flex;flex-direction:column}
h1{font-size:18px;font-weight:400}
.row{display:flex;align-items:center;gap:16px;padding:11px 0;border-bottom:1px solid $hairline;font-size:14px}
.glyph{width:24px;height:24px;flex:0 0 24px;display:flex;align-items:center;justify-content:center}
.glyph svg{width:24px;height:24px}
.sw{margin-left:auto;width:40px;height:22px;border-radius:11px;background:$pill;position:relative;flex:0 0 40px}
.sw i{position:absolute;top:2px;left:2px;width:18px;height:18px;border-radius:50%;background:#cfc9bd}
.sw.on{background:$accent}.sw.on i{left:20px;background:#eef3f2}
.spacer{flex:1}
.meta{font-size:12px;color:$dim;line-height:1.5}
</style><body>
<div class="game"></div><div class="scrim"></div>
<div class="panel"><h1>Menu</h1>$rows<div class="spacer"></div>
<div class="meta">port $ver &#183; CrossCode<br>External input: idle<br>Saves: Dsves<br>
Engine: fps: limited to 30 fps<br>(see Diagnostics)</div>$tail</div></body>"""
)


def _row(glyph_svg: str, label: str, switch: str = "") -> str:
    gly = f'<div class="glyph">{glyph_svg}</div>' if glyph_svg else '<div class="glyph"></div>'
    return gly + f'<div>{label}</div>' + switch


def menu_html(vectors: dict[str, Vector], palette: dict[str, str]) -> str:
    def svg(name: str) -> str:
        v = vectors.get(name)
        return v.svg() if v else ""

    rows = (
        _row(svg("ic_menu_pad"), "Hide pad with external input", '<div class="sw on"><i></i></div>')
        + _row(svg("ic_menu_stats"), "Show FPS / battery / temp", '<div class="sw on"><i></i></div>')
        + _row(svg("ic_menu_fps"), "Limit the frame rate (battery)", '<div class="sw on"><i></i></div>')
        + _row("", "20 &nbsp; 30 &nbsp; 40 &nbsp; 45 &nbsp; 60 &nbsp; <b>30 fps</b>")
        + _row(svg("ic_menu_log"), "Keep a log with the saves", '<div class="sw"><i></i></div>')
    )
    tail = (
        _row(svg("ic_menu_games"), "Game selection")
        + _row(svg("ic_menu_diagnostics"), "Troubleshoot")
        + _row(svg("ic_menu_exit"), "Exit")
    )
    return MENU.substitute(
        wpx=WINDOW_W, hpx=WINDOW_H, text=palette["TEXT"], dim=palette["DIM"],
        panel=palette["PANEL"], scrim=palette["SCRIM"], hairline=palette["HAIRLINE"],
        pill=palette["PILL"], accent=palette["ACCENT"], rows=rows, tail=tail, ver="0.8.0 (19)",
    )


def composed_mark(vectors: dict[str, Vector]) -> str:
    """The launcher mark (field + glyph) as one SVG: the tile's stand-in when a game has no art."""
    fg = vectors.get("ic_launcher_foreground")
    bg = vectors.get("ic_launcher_background")
    if fg is None or bg is None:
        sys.exit("ic_launcher_foreground/ic_launcher_background not found")
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {ICON_VIEWPORT:g} {ICON_VIEWPORT:g}">'
        f"{bg.body}{fg.body}</svg>"
    )


PLAY_GLYPH = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 960"><path fill="{ink}" d="M340,220l360,260l-360,260z"/></svg>'


def entry_html(vectors: dict[str, Vector], palette: dict[str, str], geometry: dict[str, int],
               empty: bool) -> str:
    tile = geometry.get("TILE_DP", 64)
    play = geometry.get("PLAY_DP", 40)
    dots = geometry.get("DOTS_DP", 32)
    mark = composed_mark(vectors)

    def card(name: str, info: str) -> str:
        # The tile stands in for the game's title art with the port's own mark, which is exactly what
        # the app shows when the user's copy has no art at that path.
        return (
            f'<div class="card"><div class="tile">{mark}</div>'
            f'<div class="meta"><div class="nm">{name}</div><div class="files">{info}</div></div>'
            f'<div class="acts"><div class="dots">&#8942;</div>'
            f'<div class="play">{PLAY_GLYPH.format(ink=palette["INK"])}</div></div></div>'
        )

    def add(name: str) -> str:
        return f'<div class="add"><span class="plus">+</span>Add {name}</div>'

    if empty:
        cards = add("Alabaster Dawn") + add("CrossCode")
    else:
        cards = (
            card("Alabaster Dawn", "files: Download/AlabasterDawn<br>saves: Download/Dsves-steam")
            + card("CrossCode", "files: Download/CrossCode<br>saves: Download/Dsves")
        )

    status = ("Pick a game's install folder \u2014 the one holding Alabaster Dawn's terra/ "
              "or CrossCode's assets/.") if empty else "Ready."
    body = (
        '<div class="header"><div class="title">RadicalFish Port</div><div class="btn">Troubleshoot</div></div>'
        + cards
        + '<div class="check"><div class="box"></div>Start last game directly</div>'
        + f'<div class="status">{status}</div>'
        + '<div class="version">port 0.8.0 (19)</div>'
    )
    return SCREEN.substitute(
        wpx=WINDOW_W, hpx=WINDOW_H, text=palette["TEXT"], dim=palette["DIM"], panel=palette["PANEL"],
        tilecolor=palette["TILE"], accent=palette["ACCENT"], hairline=palette["HAIRLINE"],
        tile=tile, play=play, dots=dots, body=body,
    )


# ------------------------------------------------------------------ driving it --------


def screenshot(html_path: Path, out_png: Path, w: int, h: int) -> None:
    subprocess.run(
        ["chromium", "--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
         "--force-device-scale-factor=1", f"--window-size={w},{h}", f"--screenshot={out_png}",
         html_path.resolve().as_uri()],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    print(f"  {out_png.relative_to(REPO)}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=HERE / "out", help="output directory (default: out/)")
    ap.add_argument("--keep-html", action="store_true", help="keep the generated .html next to the PNGs")
    args = ap.parse_args()

    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    (out / "svg").mkdir(exist_ok=True)

    palette = load_palette()
    geometry = load_geometry()
    vectors = load_vectors()
    print(f"read {len(vectors)} vectors, {len(palette)} colours, "
          f"{len(geometry)} dp constants from the app sources")

    for name, vector in vectors.items():
        (out / "svg" / f"{name}.svg").write_text(vector.svg(), encoding="utf-8")

    sheets = [
        ("icons", icons_html(vectors, palette), 980, 440),
        ("launcher", launcher_html(vectors, palette), 780, 300),
        ("palette", palette_html(palette), 780, 400),
        ("entry", entry_html(vectors, palette, geometry, empty=False), WINDOW_W, WINDOW_H),
        ("entry-wide", entry_html(vectors, palette, geometry, empty=False), WIDE_W, WIDE_H),
        ("entry-empty", entry_html(vectors, palette, geometry, empty=True), WINDOW_W, WINDOW_H),
        ("menu", menu_html(vectors, palette), WINDOW_W, WINDOW_H),
    ]
    print("rendering:")
    for name, html, w, h in sheets:
        page = out / f"{name}.html"
        page.write_text(html, encoding="utf-8")
        screenshot(page, out / f"{name}.png", w, h)
        if not args.keep_html:
            page.unlink()

    print(f"done -> {out}")


if __name__ == "__main__":
    main()
