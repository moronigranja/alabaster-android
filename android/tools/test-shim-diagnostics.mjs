#!/usr/bin/env node
/*
 * Smoke tests for android/app/src/main/assets/ada-shim.js, which is the file the port actually
 * serves into the game. Two halves:
 *
 * The shim reports the engine's boot state through the bridge (`reportDiag`), and those reports are
 * what turn "the loading bar froze on a device I do not own" into a named failure: which resources
 * were still pending, grouped by kind, with the audio-decode counters. That logic is hard to reach in
 * the game (the boot has to stall, on hardware with the right driver), so it is driven here against a
 * stub engine and a recording bridge.
 *
 * The picture alignment writes the canvas element's layout box, and the value it writes has to agree
 * with the engine's own mouse mapping (`offsetTop` minus a centred letterbox; FINDINGS 15). A real
 * WebView is not needed to check the arithmetic, so the three positions are asserted here against
 * both display scales.
 *
 *   node android/tools/test-shim-diagnostics.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const shimPath = join(dirname(fileURLToPath(import.meta.url)), "..", "app", "src", "main", "assets", "ada-shim.js");
const source = readFileSync(shimPath, "utf8");

/** Installs the globals the shim expects and returns the recorder of what it reported. */
function bootShim({ stallMs = 30, reportEveryMs = 1000, pollMs = 5, canvas = null, viewAlign = "center" } = {}) {
  const reports = [];
  const errors = [];
  const vertexUniforms = [];
  const resource = (name) => ({ identification: () => name });

  globalThis.window = globalThis;
  window.__adaBootDiag = { stallMs, reportEveryMs, pollMs };
  window.__reports = reports;
  /* Node 24 defines some of these as getter-only globals, so they are (re)defined rather than set. */
  const define = (name, value) =>
    Object.defineProperty(globalThis, name, { value, configurable: true, writable: true });
  define("performance", performance);
  define("navigator", {});
  define("document", {
    querySelector: (selector) => (selector === ".xgCanvas" ? canvas : null),
    /* The shim probes a throwaway canvas for the device's vertex-uniform budget before the engine
     * asks for its first shader; without `getContext` it reports a `gl limits` error instead. */
    createElement: (tag) =>
      tag === "canvas"
        ? {
            style: {},
            getContext: (kind) =>
              kind === "webgl2"
                ? {
                    MAX_VERTEX_UNIFORM_VECTORS: 256,
                    MAX_FRAGMENT_UNIFORM_VECTORS: 896,
                    MAX_VARYING_VECTORS: 31,
                    getParameter: (which) => which,
                    getExtension: () => null,
                  }
                : null,
          }
        : { style: {}, appendChild() {} },
    body: null,
  });
  define("addEventListener", () => {});
  define("removeEventListener", () => {});
  window.AdaBridge = {
    reportDiag: (kind, payload) => reports.push({ kind, payload }),
    reportJsError: (message) => errors.push(message),
    setVertexUniformVectors: (vectors) => vertexUniforms.push(vectors),
    getGamepadJson: () => "",
    getViewAlign: () => viewAlign,
    getStatsEnabled: () => false,
    getTelemetry: () => "",
    fsExists: () => false,
    fsMkdir: () => false,
    fsReaddir: () => "",
    fsStatSync: () => null,
    fsReadFile: () => null,
    fsWriteFile: () => false,
    fsRename: () => false,
    fsRm: () => false,
    fsCopyFile: () => false,
  };
  window.g = {
    gl: { getExtension: () => null, getParameter: () => "test-gl" },
    audio: { context: { state: "suspended" } },
    resource: {
      bootTracker: {
        progress: 0.12,
        maxResource: 100,
        state: 1,
        resources: [
          resource("media/audio/sfx/x.wav"),
          resource("media/audio/sfx/y.wav"),
          resource("SpriteSheet[ gui::media/gui/menu.png|0|0 ]"),
          resource("Effect[ FX:a#b ]"),
          resource("data/database/options.json"),
        ],
      },
      loading: [],
      staged: [],
      errors: [],
    },
  };
  /* eslint-disable-next-line no-eval */
  (0, eval)(source);
  return { reports, errors, vertexUniforms, tracker: window.g.resource.bootTracker, canvas };
}

const results = [];
function check(name, condition, detail = "") {
  results.push({ name, ok: !!condition, detail });
  console.log(`${condition ? "ok  " : "FAIL"} ${name}${condition ? "" : "  <- " + detail}`);
}

async function main() {
  const { reports, errors, vertexUniforms, tracker } = bootShim();

  // The shim loads without an engine; facts go out once the engine has a GL context.
  await new Promise((r) => setTimeout(r, 40));
  const facts = reports.filter((r) => r.kind === "facts");
  check("reports the facts once", facts.length === 1, JSON.stringify(reports));
  check("the facts name the GL backend and the audio state",
    facts[0]?.payload.includes("gl=test-gl") && facts[0]?.payload.includes("audio=suspended"),
    facts[0]?.payload);
  check("reports nothing but the shim's own load line",
    errors.length === 1 && errors[0] === "shim: loaded", JSON.stringify(errors));
  check("the device's vertex-uniform budget reaches the bridge",
    vertexUniforms.length === 1 && vertexUniforms[0] === 256, JSON.stringify(vertexUniforms));

  // Progress frozen past the threshold: exactly one stall report, naming what is pending by kind.
  await new Promise((r) => setTimeout(r, 120));
  const stalls = reports.filter((r) => r.kind === "boot stall");
  check("reports the stalled boot", stalls.length === 1, JSON.stringify(reports.map((r) => r.kind)));
  const stall = stalls[0]?.payload || "";
  check("the stall names the percentage and the resource total",
    stall.includes("at 12.0% of 100 resources"), stall);
  check("the stall groups the pending resources by kind",
    stall.includes("audio=2") && stall.includes("spritesheet=1") && stall.includes("effect=1") &&
    stall.includes("data=1"), stall);
  check("the stall names the first pending resources",
    stall.includes("first pending: media/audio/sfx/x.wav"), stall);
  check("the stall reports the audio-decode counters",
    stall.includes("decodes started=0 done=0 failed=0") && stall.includes("audio ctx=suspended"), stall);

  // A finished boot reports once, with its duration and resource count.
  tracker.progress = 1;
  tracker.state = 2;
  tracker.resources = [];
  await new Promise((r) => setTimeout(r, 40));
  const complete = reports.filter((r) => r.kind === "boot");
  check("reports the completed boot", complete.length === 1, JSON.stringify(complete));
  check("the completion names the resource count",
    (complete[0]?.payload || "").includes("100 resources"), complete[0]?.payload);
  check("nothing is reported after completion",
    reports.filter((r) => r.kind === "boot stall" || r.kind === "boot").length === 2,
    JSON.stringify(reports.map((r) => r.kind)));

  // A boot that keeps advancing is never reported as stalled. The threshold here is deliberately
  // generous: a 30 ms one made this case flaky, because a scheduling hiccup between two polls looks
  // exactly like a stall at that scale.
  const moving = bootShim({ stallMs: 250 });
  for (let i = 1; i <= 8; i++) {
    moving.tracker.progress = 0.12 + i * 0.01;
    await new Promise((r) => setTimeout(r, 20));
  }
  check("a progressing boot is not reported as stalled",
    moving.reports.every((r) => r.kind !== "boot stall"),
    JSON.stringify(moving.reports.map((r) => r.kind)));

  /* Picture alignment. The engine's mouse mapping is `(page - offsetTop) - deltaY/2` (FINDINGS 15),
   * so the shim must move the canvas element's *layout box* and leave the picture centred inside it:
   * with `top = desired - deltaY/2` the picture's visual top is `desired` and the engine's model is
   * exact. Writing `object-position` instead (the shipped 0.6.0 behaviour) leaves the model deltaY/2
   * away from the picture - the bug this guards. */
  const canvasStub = ({ bufW, bufH, clientW, clientH, boxH }) => ({
    width: bufW, height: bufH, clientWidth: clientW, clientHeight: clientH,
    style: {}, isConnected: true, parentElement: { clientHeight: boxH },
  });
  /* offsetTop agrees with the integer `top` the shim writes, so the engine's model of the picture's
   * top is `top + deltaY/2`; it must land on the requested position within that rounding. */
  const enginePictureTop = (canvas, scale1, bufH) =>
    parseFloat(canvas.style.top) + (canvas.clientHeight - scale1 * bufH) / 2;

  const geometry = [
    { name: "sharp-pixels off (buffer 1280x720 in a 400x800 box)",
      c: canvasStub({ bufW: 1280, bufH: 720, clientW: 400, clientH: 800, boxH: 800 }),
      scale1: 0.3125, pictureH: 225, top: "-287px", bottom: "288px" },
    { name: "sharp-pixels on (buffer 640x360 pinned to 1280x720 in a 800 px box)",
      c: canvasStub({ bufW: 640, bufH: 360, clientW: 1280, clientH: 720, boxH: 800 }),
      scale1: 2, pictureH: 720, top: "0px", bottom: "80px" },
  ];
  for (const g of geometry) {
    const { canvas } = bootShim({ canvas: g.c, viewAlign: "top" });
    navigator.getGamepads();
    check(`${g.name}: Top pins the box so the picture's top is 0`,
      canvas.style.top === g.top, `top=${canvas.style.top}`);
    check(`${g.name}: Top keeps the engine's model of the picture at 0`,
      Math.abs(enginePictureTop(canvas, g.scale1, g.c.height) - 0) <= 1,
      `engine picture top=${enginePictureTop(canvas, g.scale1, g.c.height)}`);
    check(`${g.name}: the picture is left centred inside the box`,
      canvas.style.objectPosition === undefined, `object-position=${canvas.style.objectPosition}`);

    const lower = bootShim({ canvas: g.c, viewAlign: "bottom" });
    navigator.getGamepads();
    const desired = g.c.parentElement.clientHeight - g.pictureH;
    check(`${g.name}: Bottom puts the picture's top at boxH - pictureH (${desired})`,
      lower.canvas.style.top === g.bottom &&
      Math.abs(enginePictureTop(lower.canvas, g.scale1, g.c.height) - desired) <= 1,
      `top=${lower.canvas.style.top} engine picture top=${enginePictureTop(lower.canvas, g.scale1, g.c.height)}`);

    const centred = bootShim({ canvas: g.c, viewAlign: "center" });
    navigator.getGamepads();
    check(`${g.name}: Centre restores the stylesheet's own layout`,
      centred.canvas.style.top === "" && centred.canvas.style.bottom === "",
      `top=${centred.canvas.style.top} bottom=${centred.canvas.style.bottom}`);
  }

  /* Before the first layout pass every measurement is 0: nothing must be written (a `0px` here would
   * move the picture to the top of the window on a device where the list was not drawn yet). */
  const unmeasured = canvasStub({ bufW: 1280, bufH: 720, clientW: 0, clientH: 0, boxH: 0 });
  const late = bootShim({ canvas: unmeasured, viewAlign: "top" });
  navigator.getGamepads();
  unmeasured.clientWidth = 400;
  unmeasured.clientHeight = 800;
  unmeasured.parentElement.clientHeight = 800;
  navigator.getGamepads();
  check("an unmeasured canvas is not written, then applied once it has been laid out",
    late.canvas.style.top === "-287px", `top=${late.canvas.style.top}`);

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main();
