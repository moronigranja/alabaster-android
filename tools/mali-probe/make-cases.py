#!/usr/bin/env python3
"""Generate the Mali shader probe's cases from a local install of the game.

The cases are the game's own fragment shaders, expanded the engine's way (`#import "lib/x";` ->
`data/shader/lib/x.glsl`, recursively), plus the port's edits to them and the candidate edits this
project is still deciding between. They are game files: this writes them into `cases/` (git-ignored)
for `app/src/androidTest/assets`, and nothing here is ever committed.

    python3 tools/mali-probe/make-cases.py \
        --shader-root /path/to/terra/data/shader \
        --reportershader /path/to/his/analog-filter.frag   # optional

The edit table below MIRRORS `ShaderArrays.kt` (the port's own table is the source of truth); the
script asserts that every find-string matches the game's bytes exactly, so the probe cannot silently
drift from what the port serves.
"""

import argparse
import os
import re
import sys

# --- mirrors android/app/src/main/java/io/github/moronigranja/alabasterdawn/ShaderArrays.kt ---
# keyed by the path below `terra/data/shader/`
LIFT = {
    "fragment/post/analog-filter.frag": [
        ("colorRamp(noise.r, vec3[5](", "colorRamp(noise.r, vec3[]("),
    ],
    "fragment/water-plane.frag": [
        ("flat in vec2[4] v_flowDirs;", "flat in vec2 v_flowDirs[4];"),
    ],
    "fragment/plane-depth.frag": [
        ("flat in vec2[4] v_flowDirs;", "flat in vec2 v_flowDirs[4];"),
    ],
    "lib/color-utils.glsl": [
        ("vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) {",
         "vec3 colorRamp(float t, vec3 colors[COLOR_RAMP_COUNT]) {"),
    ],
    "lib/water.glsl": [
        ("vec2 bilinear(vec2[4] v, float t0, float t1) {",
         "vec2 bilinear(vec2 v[4], float t0, float t1) {"),
        ("const vec2[12] DIRECTIONS = vec2[](",
         "const vec2 DIRECTIONS[12] = vec2[]("),
        ("const vec2[12] DIRECTIONS_DIAG = vec2[](",
         "const vec2 DIRECTIONS_DIAG[12] = vec2[]("),
        ("vec3 computeGerstnerOffset(vec3 pos, vec4[4] waves, out vec3 tangent, out vec3 binormal,"
         " float zScale, float wShift, float speed, float[4] amps, float[4] phases) {",
         "const float adaAmpsWave[4] = float[](1.0, 0.3, 0.1, 0.0);\n"
         "const float adaPhasesWave[4] = float[](0.6, 1.1, 2.3, 3.7);\n"
         "const float adaAmpsFoam[4] = float[](0.5, 0.25, 0.125, 0.125);\n"
         "const float adaPhasesFoam[4] = float[](0.0, 1.1, 2.5, 3.9);\n\n"
         "vec3 computeGerstnerOffset(vec3 pos, vec4 waves[4], out vec3 tangent, out vec3 binormal,"
         " float zScale, float wShift, float speed, float amps[4], float phases[4]) {"),
        ("float[](1.0, 0.3, 0.1, 0.0), float[](0.6, 1.1, 2.3, 3.7)", "adaAmpsWave, adaPhasesWave"),
        ("float[](0.5, 0.25, 0.125, 0.125), float[](0.0, 1.1, 2.5, 3.9)",
         "adaAmpsFoam, adaPhasesFoam"),
        ("vec4[4] computeWaveFactors(out vec2 globalFlow, vec2 flowDir) {",
         "void computeWaveFactors(out vec2 globalFlow, vec2 flowDir, out vec4 waves[4]) {"),
        ("    return vec4[](\n"
         "        vec4(globalFlow, 1.0, 0.5),\n"
         "        vec4(rotateV2(globalFlow, 0.5), 0.5, 0.25),\n"
         "        vec4(rotateV2(globalFlow, -0.5), 0.25, 0.125),\n"
         "        vec4(rotateV2(globalFlow, 2.0), 0.125, 0.125)\n"
         "    );",
         "    waves[0] = vec4(globalFlow, 1.0, 0.5);\n"
         "    waves[1] = vec4(rotateV2(globalFlow, 0.5), 0.5, 0.25);\n"
         "    waves[2] = vec4(rotateV2(globalFlow, -0.5), 0.25, 0.125);\n"
         "    waves[3] = vec4(rotateV2(globalFlow, 2.0), 0.125, 0.125);"),
        ("    vec4[4] waves = computeWaveFactors(globalFlow, flowDir);",
         "    vec4 waves[4];\n    computeWaveFactors(globalFlow, flowDir, waves);"),
    ],
}

IMPORT = re.compile(r'^\s*#import\s+"([^"]+)"\s*;?\s*$')

# --- the declaration shapes the port's gate asks about (FINDINGS §22.8/§22.9) ---
HEAD = ["#version 300 es", "precision mediump float;", "out vec4 o;"]
SHAPES = {
    "in-out": [
        "flat in vec2[4] v_flowDirs;",
        "void main() { o = vec4(v_flowDirs[0], 0.0, 1.0); }",
    ],
    "const-global": [
        "const vec2[12] DIRECTIONS = vec2[](",
        "    vec2(0.0, -1.0), vec2(0.0, -1.0), vec2(0.0, -1.0),",
        "    vec2(1.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 0.0),",
        "    vec2(0.0, 1.0), vec2(0.0, 1.0), vec2(0.0, 1.0),",
        "    vec2(-1.0, 0.0), vec2(-1.0, 0.0), vec2(-1.0, 0.0));",
        "void main() { o = vec4(DIRECTIONS[0], 0.0, 1.0); }",
    ],
    "param": [
        "vec2 bilinear(vec2[4] v, float t0, float t1) {"
        " return mix(v[0], v[1], clamp(t0, 0.0, 1.0)) + vec2(t1); }",
        "void main() { o = vec4(bilinear(vec2[](vec2(0.0), vec2(1.0), vec2(2.0), vec2(3.0)),"
        " 0.0, 1.0), 0.0, 1.0); }",
    ],
    "return": [
        "uniform vec2 u_flow;",
        "vec4[4] computeWaveFactors(out vec2 globalFlow, vec2 flowDir) {",
        "    globalFlow = normalize(flowDir);",
        "    return vec4[](",
        "        vec4(globalFlow, 1.0, 0.5),",
        "        vec4(globalFlow, 0.5, 0.25),",
        "        vec4(globalFlow, 0.25, 0.125),",
        "        vec4(globalFlow, 0.125, 0.125));",
        "}",
        "void main() { vec2 g; vec4 w[4] = computeWaveFactors(g, u_flow);"
        " o = vec4(w[0].xy + g, 0.0, 1.0); }",
    ],
    "params": [
        "vec3 computeGerstnerOffset(vec3 pos, vec4[4] waves, out vec3 tangent, out vec3 binormal,"
        " float zScale, float wShift, float speed, float[4] amps, float[4] phases) {",
        "    tangent = vec3(1.0, 0.0, 0.0); binormal = vec3(0.0, 0.0, 1.0);",
        "    return pos + waves[0].xyz * amps[0] + vec3(phases[0]) * zScale + vec3(wShift + speed);",
        "}",
        "void main() { vec3 t, b; vec4 w[4]; float a[4]; float p[4];",
        "    o = vec4(computeGerstnerOffset(vec3(0.0), w, t, b, 1.0, 0.0, 1.0, a, p), 1.0); }",
    ],
    "local": [
        "void main() { vec4[4] waves; o = vec4(waves[0].xy, 0.0, 1.0); }",
    ],
    "macro-size": [
        "#ifndef COLOR_RAMP_COUNT", "#define COLOR_RAMP_COUNT 5", "#endif",
        "vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) { return colors[1]; }",
        "void main() { o = vec4(colorRamp(0.5,"
        " vec3[](vec3(0.0), vec3(1.0), vec3(2.0), vec3(3.0), vec3(4.0))), 1.0); }",
    ],
    "ctor-arg": [
        "vec3 colorRamp(float t, vec3 colors[5]) { return colors[1]; }",
        "void main() { o = vec4(colorRamp(0.5,"
        " vec3[5](vec3(0.0), vec3(1.0), vec3(2.0), vec3(3.0), vec3(4.0))), 1.0); }",
    ],
    "ramp-call": [
        "#ifndef COLOR_RAMP_COUNT", "#define COLOR_RAMP_COUNT 5", "#endif",
        "vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) { return colors[1]; }",
        "void main() { o = vec4(colorRamp(0.5,"
        " vec3[5](vec3(0.0), vec3(1.0), vec3(2.0), vec3(3.0), vec3(4.0))), 1.0); }",
    ],
}

# --- the post pass's own call, as the file writes it, and the edits still in question ---
RAMP_PARAM = "vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) {"
RAMP_CALL = "colorRamp(noise.r, vec3[5]("
RAMP_BLOCK = (
    "    vec3 color = colorRamp(noise.r, vec3[5](\n"
    "    rgb(0.0, 0.0, 20.),\n"
    "    rgb(37., 40., 50.),\n"
    "    midColor, //rgb(122., 101., 78.),\n"
    "    rgb(184., 170., 132.),\n"
    "    rgb(255., 255., 255.)\n"
    "    ));"
)
RAMP_LOCAL = (
    "    vec3 adaRamp[5];\n"
    "    adaRamp[0] = rgb(0.0, 0.0, 20.);\n"
    "    adaRamp[1] = rgb(37., 40., 50.);\n"
    "    adaRamp[2] = midColor;\n"
    "    adaRamp[3] = rgb(184., 170., 132.);\n"
    "    adaRamp[4] = rgb(255., 255., 255.);\n"
    "    vec3 color = colorRamp(noise.r, adaRamp);"
)
RAMP_GLOBAL = (
    "const vec3 adaRamp[5] = vec3[]("
    "vec3(0.0, 0.0, 0.07843137255), vec3(0.14509803922, 0.15686274510, 0.0),"
    " vec3(0.47843137255, 0.39607843137, 0.30588235294),"
    " vec3(0.72156862745, 0.66666666667, 0.51764705882), vec3(1.0, 1.0, 1.0));\n"
)
ANALOG_VARIANTS = {
    "lifted-both": [(RAMP_CALL, "colorRamp(noise.r, vec3[]("),
                    (RAMP_PARAM, "vec3 colorRamp(float t, vec3 colors[COLOR_RAMP_COUNT]) {")],
    "lifted-param-only": [(RAMP_PARAM, "vec3 colorRamp(float t, vec3 colors[COLOR_RAMP_COUNT]) {")],
    "lifted-ctor-only": [(RAMP_CALL, "colorRamp(noise.r, vec3[](")],
    "mediump-param": [(RAMP_PARAM, "vec3 colorRamp(float t, mediump vec3[COLOR_RAMP_COUNT] colors) {")],
    "highp-param": [(RAMP_PARAM, "vec3 colorRamp(float t, highp vec3[COLOR_RAMP_COUNT] colors) {")],
    "local-ramp": [(RAMP_BLOCK, RAMP_LOCAL)],
    "global-ramp": [(RAMP_BLOCK, "    vec3 color = colorRamp(noise.r, adaRamp);")],
}


def expand(shader_root, rel, lift, seen=None, depth=0, first_text=None):
    """The engine's own `#import` expansion, with the port's edits applied to each file when `lift`."""
    seen = set(seen or ())
    out = []
    text = first_text if first_text is not None else open(os.path.join(shader_root, rel)).read()
    if lift:
        for find, replace in LIFT.get(rel, []):
            if find not in text:
                sys.exit(f"ERROR: {rel}: the port's find-string is not in the game's bytes:\n  {find[:90]}")
            text = text.replace(find, replace)
    for line in text.split("\n"):
        match = IMPORT.match(line)
        if match and depth < 12:
            for candidate in (match.group(1), match.group(1) + ".glsl"):
                path = os.path.join(shader_root, candidate)
                if os.path.exists(path):
                    if path in seen:
                        break
                    seen.add(path)
                    out.append(expand(shader_root, candidate, lift, seen, depth + 1))
                    break
            else:
                out.append("// MISSING IMPORT " + match.group(1))
            continue
        out.append(line)
    return "\n".join(out)


def insert_define(text, name):
    """The engine's `insertDefinitions`: the `#define` goes before anything that can read it."""
    lines = text.split("\n")
    for i, line in enumerate(lines):
        if line.strip().startswith("#version"):
            lines.insert(i + 1, "#define " + name)
            return "\n".join(lines)
    return "#define " + name + "\n" + text


def insert_after_version(text, snippet):
    """A global declaration, after the shader's own `#version`/`precision` preamble (a float literal
     before the default precision is an error, which the local dry run caught)."""
    lines = text.split("\n")
    at = None
    for i, line in enumerate(lines):
        if line.strip().startswith("#version"):
            at = i + 1
        elif line.strip().startswith("precision"):
            at = i + 1
            break
    if at is None:
        return snippet + text
    lines.insert(at, snippet)
    return "\n".join(lines)


def apply_edits(text, edits, where):
    for find, replace in edits:
        if find not in text:
            sys.exit(f"ERROR: {where}: the edit's text is not in the expanded case:\n  {find[:90]}")
        text = text.replace(find, replace, 1)
    return text


def write(out, path, text):
    full = os.path.join(out, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as handle:
        handle.write(text if text.endswith("\n") else text + "\n")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shader-root", required=True,
                        help="the game's terra/data/shader directory")
    parser.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "cases"),
                        help="where the cases go (default: tools/mali-probe/cases)")
    parser.add_argument("--reportershader", default=None,
                        help="the reporter's hand-fixed analog-filter.frag, as a case of its own")
    args = parser.parse_args()

    cases = []
    out = args.out
    if os.path.isdir(out):
        for root, _dirs, files in os.walk(out):
            for name in files:
                os.remove(os.path.join(root, name))

    # 1. the declaration shapes the port's gate asks about
    for name, body in SHAPES.items():
        cases.append(write(out, f"shapes/{name}.frag", "\n".join(HEAD + body)))

    # 2. a control that must compile, so "the probe never ran" is distinguishable
    cases.append(write(out, "control/ok.frag",
                       "\n".join(HEAD + ["void main() { o = vec4(1.0); }"])))

    # 3. the real files, as the game writes them and as the port serves them
    for name, rel, defines in [
        ("water-plane", "fragment/water-plane.frag", []),
        ("water-fx-wall", "fragment/water-fx-wall.frag", ["WATERFALL_SHADER"]),
        ("plane-depth", "fragment/plane-depth.frag", []),
        ("analog-filter", "fragment/post/analog-filter.frag", []),
    ]:
        for lifted in (False, True):
            text = expand(args.shader_root, rel, lifted)
            for flag in defines:
                text = insert_define(text, flag)
            leaf = "lifted" if lifted else "original"
            cases.append(write(out, f"files/{name}/{leaf}.frag", text))

    # 4. the edits still in question, each on the post pass's own expanded bytes
    original = expand(args.shader_root, "fragment/post/analog-filter.frag", False)
    for name, edits in ANALOG_VARIANTS.items():
        text = apply_edits(original, edits, name)
        if name == "global-ramp":
            text = insert_after_version(text, RAMP_GLOBAL)
        cases.append(write(out, f"variants/analog-filter/{name}.frag", text))

    # 5. the reporter's own fix, which boots on his device: his file, expanded with the game's libs
    if args.reportershader and os.path.exists(args.reportershader):
        text = expand(args.shader_root, "fragment/post/analog-filter.frag", False,
                      first_text=open(args.reportershader).read())
        cases.append(write(out, "variants/analog-filter/reporters-fix.frag", text))

    with open(os.path.join(out, "index.txt"), "w") as handle:
        handle.write("# every case is one fragment shader: compile + link, one PROBE line each\n")
        handle.write("# generated from a local game install by make-cases.py; never committed\n")
        for path in sorted(cases):
            handle.write(path + "\n")
    print(f"{len(cases)} cases -> {out}")
    for path in sorted(cases):
        print("  " + path)


if __name__ == "__main__":
    main()
