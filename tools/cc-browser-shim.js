/*
 * Minimal Node/NW.js shim that makes CrossCode 1.0 boot in a plain browser (no Node, no NW.js).
 * Verified 2026-09-26 (FINDINGS.md section 13): loading screen -> title screen -> main menu,
 * 0 page errors, platform detected as "Desktop". ~6 KB, one pass.
 *
 * Load it BEFORE the page's own scripts (a <script> tag in assets/node-webkit.html, or the port's
 * document-start script).
 *
 * Why each piece is needed (measured, not guessed):
 *  - window.require AND an object window.process together select the DESKTOP path:
 *      ig.platform = window.require && typeof window.process === "object" ? DESKTOP : ...
 *    Leaving both undefined does NOT give BROWSER on a phone: the WebView UA contains "Android", so
 *    the engine would pick MOBILE (loadExtensionsPHP, TrackDefault). DESKTOP is the intended target.
 *  - ig.platform == DESKTOP then reads process.versions["node-webkit"] with /(\d+)\.(\d+)\.(\d+)/
 *    and process.arch, so both must look real.
 *  - require("nw.gui") is the OLD Node-WebKit API (Window.get(), Shell, Clipboard, App, Menu, Screen),
 *    not the nw.* namespace Alabaster Dawn uses; CrossCode's entry HTML calls it for its uncaught
 *    exception hook.
 *  - ig.Extensions.loadExtensionsNWJS wants the callback form fs.readdir(dir, cb), fs.lstatSync(p)
 *    .isDirectory() and fs.existsSync(p + name + ".json"); without readdir the boot throws
 *    "a.readdir is not a function" after the loader is up.
 *  - the four greenworks variants are picked by nwjsVersion; a stub that reports inactive is enough
 *    (no Steam inside a WebView).
 *
 * The fs shim here is in-memory and empty: extensions come back empty on purpose and nothing
 * persists. A real port backs it with the read-only game tree (for assets/extension) and leaves
 * saves to localStorage, which is where CrossCode keeps them.
 */
(function () {
  var mem = Object.create(null);
  function noop() { return undefined; }

  var fs = {
    existsSync: function (p) { return p in mem; },
    statSync: function (p) {
      if (!(p in mem)) { throw new Error("ENOENT " + p); }
      return { mtime: 0, mtimeMs: 0, size: String(mem[p]).length,
        isDirectory: function () { return false; } };
    },
    readFileSync: function (p) { return mem[p] || ""; },
    writeFileSync: function (p, d) { mem[p] = d; },
    mkdirSync: function (p) { mem[p + "/@dir"] = true; },
    readdirSync: function (p) { return mem[p + "/@dir"] || []; },
    /* ig.Extensions.loadExtensionsNWJS wants the callback form plus lstatSync. */
    readdir: function (p, cb) { if (typeof cb === "function") { cb(null, mem[p + "/@dir"] || []); } },
    lstatSync: function (p) { return { isDirectory: function () { return !!(mem[p + "/@dir"]); } }; },
    unlinkSync: function (p) { delete mem[p]; },
    rmSync: function (p) { delete mem[p]; },
    copyFileSync: function (a, b) { mem[b] = mem[a]; },
    readFile: function (p, o, cb) { if (typeof o === "function") { cb = o; } try { cb(null, mem[p] || ""); } catch (e) {} },
    writeFile: function (p, d, o, cb) { mem[p] = d; if (typeof o === "function") o(null); else if (cb) cb(null); },
    watch: function () { return { close: noop, on: noop }; },
    promises: {
      readFile: function (p) { return Promise.resolve(mem[p] || ""); },
      writeFile: function (p, d) { mem[p] = d; return Promise.resolve(); },
      stat: function (p) { return Promise.resolve({ isDirectory: function () { return false; }, size: 0 }); },
      mkdir: function () { return Promise.resolve(); },
      unlink: function (p) { delete mem[p]; return Promise.resolve(); },
      rename: function (a, b) { mem[b] = mem[a]; delete mem[a]; return Promise.resolve(); }
    }
  };

  var path = {
    join: function () { return Array.prototype.join.call(arguments, "/").replace(/\/+/g, "/"); },
    resolve: function () { return Array.prototype.join.call(arguments, "/").replace(/\/+/g, "/"); },
    dirname: function (p) { return String(p).split("/").slice(0, -1).join("/"); },
    basename: function (p) { return String(p).split("/").pop(); },
    extname: function (p) { var b = path.basename(p); var i = b.lastIndexOf("."); return i <= 0 ? "" : b.substring(i); },
    normalize: function (p) { return String(p).replace(/\/+/g, "/"); },
    sep: "/", delimiter: ":", posix: null
  };
  path.posix = path;

  function windowStub() {
    return {
      close: noop, on: noop, once: noop, off: noop, show: noop, hide: noop, focus: noop,
      minimize: noop, maximize: noop, unmaximize: noop, restore: noop, resizeTo: noop, moveTo: noop,
      enterFullscreen: noop, leaveFullscreen: noop, isFullscreen: false, setAlwaysOnTop: noop,
      setZoomLevel: noop, reload: noop, isDevToolsOpen: function () { return false; },
      showDevTools: noop, closeDevTools: noop, window: window,
      width: window.innerWidth, height: window.innerHeight
    };
  }

  var nwGui = {
    App: {
      argv: [], dataPath: "/cc-data", quit: noop, clearCache: noop,
      on: noop, once: noop, removeAllListeners: noop,
      openDevTools: noop, closeAllWindows: noop, registerGlobalHotKey: noop,
      getProxyForURL: function () { return ""; }
    },
    Window: { get: windowStub, open: function () { return windowStub(); }, getAll: function () { return []; } },
    Shell: { openExternal: noop, openItem: noop, showItemInFolder: noop },
    Clipboard: { get: function () { return { set: noop, get: function () { return ""; }, clear: noop }; } },
    Menu: { get: function () { return { items: [], popup: noop, remove: noop, append: noop, insert: noop }; } },
    Tray: { get: function () { return { remove: noop, setTitle: noop }; } },
    Screen: { screens: [{ bounds: { x: 0, y: 0, width: window.innerWidth, height: window.innerHeight },
      work_area: { x: 0, y: 0, width: window.innerWidth, height: window.innerHeight }, scaleFactor: 1 }] }
  };

  var greenworks = {
    init: function () { return false; }, initAPI: function () { return false; },
    isActive: function () { return false; }, activateAchievement: noop, clearAchievement: noop,
    activateGameOverlayToStore: noop
  };

  window.require = function (name) {
    if (name === "fs") return fs;
    if (name === "path") return path;
    if (name === "nw.gui") return nwGui;
    if (name === "os") return { platform: function () { return "linux"; }, arch: function () { return "x64"; }, tmpdir: function () { return "/tmp"; } };
    if (name.indexOf("./modules/") === 0 || name.indexOf("greenworks") === 0) return greenworks;
    if (name === "vm") return { createContext: function () { return {}; }, runInContext: noop, isContext: function () { return true; } };
    return {};
  };
  window.nw = { App: nwGui.App, Window: nwGui.Window, Screen: nwGui.Screen,
    Clipboard: nwGui.Clipboard, Shell: nwGui.Shell, Menu: nwGui.Menu, Tray: nwGui.Tray };

  /* Object on purpose: this is what makes ig.platform DESKTOP. */
  window.process = {
    platform: "linux", arch: "x64", argv: ["crosscode"],
    versions: { "node-webkit": "0.86.0", node: "21.1.0", chrome: "123.0.6312.87", nw: "0.86.0" },
    env: { HOME: "/cc-data" },
    once: noop, on: noop, removeListener: noop, removeAllListeners: noop, emit: noop,
    nextTick: function (fn) { setTimeout(fn, 0); },
    exit: noop, cwd: function () { return "/cc-data"; }
  };
  window.process.versions["nwjs"] = "0.86.0";
})();
