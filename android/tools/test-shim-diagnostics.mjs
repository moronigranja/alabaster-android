#!/usr/bin/env node
/*
 * Smoke tests for the diagnostics half of android/app/src/main/assets/ada-shim.js, which is the file
 * the port actually serves into the game.
 *
 * The shim reports the engine's boot state through the bridge (`reportDiag`), and those reports are
 * what turn "the loading bar froze on a device I do not own" into a named failure: which resources
 * were still pending, grouped by kind, with the audio-decode counters. That logic is hard to reach in
 * the game (the boot has to stall, on hardware with the right driver), so it is driven here against a
 * stub engine and a recording bridge.
 *
 *   node android/tools/test-shim-diagnostics.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const shimPath = join(dirname(fileURLToPath(import.meta.url)), "..", "app", "src", "main", "assets", "ada-shim.js");
const source = readFileSync(shimPath, "utf8");

/** Installs the globals the shim expects and returns the recorder of what it reported. */
function bootShim({ stallMs = 30, reportEveryMs = 1000, pollMs = 5 } = {}) {
  const reports = [];
  const errors = [];
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
    querySelector: () => null,
    createElement: () => ({ style: {}, appendChild() {} }),
    body: null,
  });
  define("addEventListener", () => {});
  define("removeEventListener", () => {});
  window.AdaBridge = {
    reportDiag: (kind, payload) => reports.push({ kind, payload }),
    reportJsError: (message) => errors.push(message),
    getGamepadJson: () => "",
    getViewAlign: () => "center",
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
  return { reports, errors, tracker: window.g.resource.bootTracker };
}

const results = [];
function check(name, condition, detail = "") {
  results.push({ name, ok: !!condition, detail });
  console.log(`${condition ? "ok  " : "FAIL"} ${name}${condition ? "" : "  <- " + detail}`);
}

async function main() {
  const { reports, errors, tracker } = bootShim();

  // The shim loads without an engine; facts go out once the engine has a GL context.
  await new Promise((r) => setTimeout(r, 40));
  const facts = reports.filter((r) => r.kind === "facts");
  check("reports the facts once", facts.length === 1, JSON.stringify(reports));
  check("the facts name the GL backend and the audio state",
    facts[0]?.payload.includes("gl=test-gl") && facts[0]?.payload.includes("audio=suspended"),
    facts[0]?.payload);
  check("reports nothing but the shim's own load line",
    errors.length === 1 && errors[0] === "shim: loaded", JSON.stringify(errors));

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

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main();
