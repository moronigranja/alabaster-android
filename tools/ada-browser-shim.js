/*
 * Minimal Node/NW.js shim that makes Alabaster Dawn boot in a plain browser
 * (no Node, no NW.js). Verified 2026-09-23: game reaches the title screen,
 * 0 uncaught exceptions, platform detected as "browser".
 *
 * Load it BEFORE terra/dist/bundle.js (e.g. a <script> tag in terra/index.html).
 *
 * Why each piece is needed (measured, not guessed):
 *  - window.require must be truthy: the bundle's only webpack external is
 *    `module.exports = require("fs")` (module 79896), and the file/storage
 *    modules do `const fs = window.require && __webpack_require__(79896)`.
 *    With window.require undefined, `fs` is undefined and the module-level
 *    `fs.promises` throws -> boot dies silently. That is the whole reason the
 *    game does not start in a browser today.
 *  - the fs shim needs existsSync + mkdirSync at boot (Storage.preparePaths),
 *    plus statSync/copyFileSync/rmSync/unlinkSync for the save-migration path.
 *  - window.nw is needed because Storage reads nw.App.dataPath and addons call
 *    nw.Window.get().on("close", ...) during boot.
 *  - window.process must NOT be defined as an object, otherwise Engine.getPlatform()
 *    returns PLATFORM.NWJS; left undefined the engine picks PLATFORM.BROWSER.
 *
 * The fs shim is in-memory (nothing persists). For a real port, back it with
 * the Android app files dir / localStorage; see FINDINGS.md section 4.2.
 */
(function () {
  var mem = Object.create(null);
  function noop() { return undefined; }
  var fs = {
    existsSync: function (p) { return p in mem; },
    mkdirSync: function (p) { mem[p + "/@dir"] = true; },
    rmSync: function (p) { delete mem[p]; },
    rmdirSync: noop,
    unlinkSync: function (p) { delete mem[p]; },
    statSync: function (p) { return { mtime: 0, mtimeMs: 0, size: (mem[p] || "").length, isDirectory: function () { return !!mem[p + "/@dir"]; } }; },
    copyFileSync: function (a, b) { mem[b] = mem[a]; },
    readdir: function (p, o, cb) { if (typeof o === "function") { cb = o; } try { cb(null, mem[p + "/@dir"] || []); } catch (e) { } },
    readdirSync: function (p) { return mem[p + "/@dir"] || []; },
    readFileSync: function (p) { return mem[p] || ""; },
    writeFileSync: function (p, d) { mem[p] = d; },
    writeFile: function (p, d, o, cb) { mem[p] = d; if (typeof o === "function") o(null); else if (cb) cb(null); },
    appendFile: function (p, d, o, cb) { mem[p] = (mem[p] || "") + d; if (typeof o === "function") o(null); else if (cb) cb(null); },
    watch: function () { return { close: noop, on: noop }; },
    createWriteStream: function () { return { write: noop, end: noop }; },
    promises: {
      readFile: function (p) { return Promise.resolve(mem[p] || ""); },
      stat: function (p) { return Promise.resolve({ isDirectory: function () { return false; }, size: (mem[p] || "").length }); },
      rename: function (a, b) { mem[b] = mem[a]; delete mem[a]; return Promise.resolve(); },
      writeFile: function (p, d) { mem[p] = d; return Promise.resolve(); },
      unlink: function (p) { delete mem[p]; return Promise.resolve(); },
      mkdir: function () { return Promise.resolve(); }
    }
  };
  var path = {
    join: function () { return Array.prototype.join.call(arguments, "/"); },
    resolve: function () { return Array.prototype.join.call(arguments, "/"); },
    dirname: function (p) { return p.split("/").slice(0, -1).join("/"); },
    basename: function (p) { return p.split("/").pop(); },
    sep: "/", posix: null
  };
  path.posix = path;
  window.require = function (name) {
    switch (name) {
      case "fs": return fs;
      case "path": return path;
      case "vm": return { createContext: function () { return {}; }, runInContext: noop, isContext: function () { return true; } };
      case "./greenworks/greenworks": return { init: function () { return false; }, activateGameOverlayToStore: noop };
      default: return {};
    }
  };
  window.nw = {
    App: { argv: [], quit: noop, dataPath: "/data/ada" },
    Window: {
      get: function () { return { close: noop, on: noop, enterFullscreen: noop, leaveFullscreen: noop, isFullscreen: false, show: noop }; },
      open: function () { return { on: noop, show: noop }; },
      getAll: function () { return []; }
    },
    Screen: { screens: [{ bounds: { x: 0, y: 0, width: 1280, height: 720 }, work_area: { x: 0, y: 0, width: 1280, height: 720 }, scaleFactor: 1 }] },
    Clipboard: { get: function () { return { set: noop }; } },
    Shell: { openItem: noop }
  };
})();
