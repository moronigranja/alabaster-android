#!/usr/bin/env node
/*
 * Smoke test for android/app/src/main/assets/cc-shim.js, the file the port serves into CrossCode.
 *
 * CCLoader's desktop file backend reaches for `fs.promises.access` and `fs.promises.readdir` on its
 * boot path (probe a config file, then enumerate `assets/mods/`). Node's contract is asserted here
 * against a recording bridge: present paths resolve, absent ones reject with ENOENT, and `readdir`
 * yields *names* — the string-list bug the callback form already fixed once is guarded for the
 * promise form too.
 *
 *   node android/tools/test-cc-shim.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const shimPath = join(dirname(fileURLToPath(import.meta.url)), "..", "app", "src", "main", "assets", "cc-shim.js");
const source = readFileSync(shimPath, "utf8");

/** Loads the shim against a recording bridge whose fs answers are fixed below. */
function bootShim(fs = {}) {
  const errors = [];
  const diags = [];
  const define = (name, value) =>
    Object.defineProperty(globalThis, name, { value, configurable: true, writable: true });

  globalThis.window = globalThis;
  define("performance", performance);
  define("navigator", {});
  define("addEventListener", () => {});
  define("removeEventListener", () => {});
  define("requestAnimationFrame", () => 1);
  define("cancelAnimationFrame", () => {});
  /* No timers: the shim's facts tick would otherwise keep the process alive (and it needs a document
   * this harness does not build). */
  define("setInterval", () => 0);
  define("clearInterval", () => {});
  define("document", { getElementById: () => null, body: null, createElement: () => ({ style: {} }) });

  window.PortBridge = {
    reportDiag: (kind, payload) => diags.push({ kind, payload }),
    reportJsError: (message) => errors.push(message),
    getGamepadJson: () => "",
    getViewAlign: () => "center",
    getStatsEnabled: () => false,
    getFpsLimit: () => 0,
    getTelemetry: () => "",
    quit: () => {},
    fsExists: (p) => (fs.exists ?? (() => false))(p),
    fsReaddir: (p) => (fs.readdir ?? (() => ""))(p),
    fsReadFile: (p) => (fs.readFile ?? (() => null))(p),
    fsStatSync: (p) => (fs.stat ?? (() => null))(p),
    fsMkdir: () => false,
    fsWriteFile: () => false,
    fsRename: () => false,
    fsRm: () => false,
    fsCopyFile: () => false,
  };
  /* eslint-disable-next-line no-eval */
  (0, eval)(source);
  return { errors, diags };
}

const results = [];
function check(name, condition, detail = "") {
  results.push({ name, ok: !!condition, detail });
  console.log(`${condition ? "ok  " : "FAIL"} ${name}${condition ? "" : "  <- " + detail}`);
}

async function main() {
  const dir = JSON.stringify([{ n: "ccmod.json", d: false }, { n: "CrossCode-Esp", d: true }]);
  bootShim({
    exists: (p) => p === "assets/mods/x/ccmod.json",
    readdir: () => dir,
  });

  const fs = window.require("fs");

  await fs.promises.access("assets/mods/x/ccmod.json")
    .then(() => check("access resolves for a present file", true))
    .catch((e) => check("access resolves for a present file", false, String(e && e.message)));

  await fs.promises.access("nope")
    .then(() => check("access rejects for a missing path", false, "resolved"))
    .catch((e) => check("access rejects for a missing path with ENOENT", e && e.code === "ENOENT", String(e && e.code)));

  const names = await fs.promises.readdir("assets/mods");
  check("readdir resolves to plain names, not dirents",
    Array.isArray(names) && names.length === 2 && names[0] === "ccmod.json" && names[1] === "CrossCode-Esp",
    JSON.stringify(names));

  /* A directory the bridge cannot list rejects like Node, so a caller's catch runs. */
  const empty = bootShim({ readdir: () => "" });
  await window.require("fs").promises.readdir("missing")
    .then(() => check("readdir rejects for an unlistable path", false, "resolved"))
    .catch((e) => check("readdir rejects for an unlistable path with ENOENT", e && e.code === "ENOENT", String(e && e.code)));
  /* CCLoader 3's console wrapper formats every message with `require('util').format`; without this
   * the wrapper throws inside its own logger and the message is lost. */
  const util = window.require("util");
  check("util.format substitutes %s and %d",
    util.format("%s=%d", "a", 3) === "a=3", String(util.format("%s=%d", "a", 3)));
  check("util.format appends extra arguments, inspected",
    util.format("x", 1, { a: 1 }) === 'x 1 {"a":1}', String(util.format("x", 1, { a: 1 })));
  check("util.format leaves a trailing specifier alone",
    util.format("%s %s", "only") === "only %s", String(util.format("%s %s", "only")));

  /* The shim's own load line is the one report it makes unconditionally; anything else is a real error. */
  const realErrors = empty.errors.filter((e) => !/^shim: loaded/.test(e));
  check("the shim reported no bridge errors beyond its load line",
    realErrors.length === 0, JSON.stringify(realErrors));

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main();
