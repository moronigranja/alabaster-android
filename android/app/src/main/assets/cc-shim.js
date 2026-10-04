/*
 * CrossCode — Android WebView shim (this host's CrossCode profile).
 *
 * Injected at document start (before assets/js/game.compiled.js) by PortActivity. It is the
 * device-backed form of tools/cc-browser-shim.js, which was proven to boot the unmodified Steam
 * build (1.0.0, v1.4.2-4) to its title screen and main menu in a plain browser, with no game file
 * touched (FINDINGS.md §13).
 *
 * Why each piece is needed (measured against this build, not guessed):
 *  - window.require must be truthy AND window.process must be an object: that pair is what selects
 *    the engine's DESKTOP path
 *      ig.platform = window.require && typeof window.process === "object" ? DESKTOP : ...
 *    DESKTOP is the path the game is built around (loadExtensionsNWJS, the nw.gui Storage paths).
 *    Leaving both undefined would not give BROWSER on a phone: the WebView UA contains "Android", so
 *    the engine would pick MOBILE.
 *  - ig.platform == DESKTOP reads process.versions["node-webkit"] with /(\d+)\.(\d+)\.(\d+)/ and
 *    process.arch, so both must look real; the version string also picks which bundled greenworks
 *    module the extension loader asks for.
 *  - require("nw.gui") is the OLD Node-WebKit API (Window.get(), Shell, Clipboard, App, Menu,
 *    Screen), not the nw.* namespace Alabaster Dawn uses. The entry page calls it for its uncaught
 *    exception hook, and the game's own save path list reads nw.gui.App.dataPath.
 *  - ig.Extensions.loadExtensionsNWJS wants the callback form fs.readdir(dir, cb), and then
 *    fs.lstatSync(p).isDirectory() and fs.existsSync(p + name + ".json"); without readdir the boot
 *    throws "a.readdir is not a function" after the loader is up.
 *  - the four greenworks variants are picked by nwjsVersion; a stub that reports inactive is enough
 *    (there is no Steam inside a WebView).
 *
 * The Node surface is backed by the app's @JavascriptInterface bridge (window.PortBridge): the
 * picked game tree read-only, and everything under /saves in the picked saves folder. CrossCode keeps
 * its *saves* in that folder too - its own save path list is built from nw.gui.App.dataPath, which is
 * the literal /saves here - so a save folder can be copied in and back out exactly like Alabaster
 * Dawn's. (Its options and a fallback copy of a save live in localStorage, which the WebView keeps.)
 */
(function () {
    "use strict";

    var HAS_BRIDGE = typeof window.PortBridge == "object" && window.PortBridge !== null;

    function report(what, err) {
        try {
            if (HAS_BRIDGE) {
                window.PortBridge.reportJsError(what + ": " + (err && err.message ? err.message : String(err)));
            }
        } catch (e) { /* nothing left to do */ }
    }

    /* Every bridge call is guarded: a failure must never reach page code as an exception, or it would
     * look like a game crash. */
    function call(fn, fallback) {
        if (!HAS_BRIDGE) return fallback;
        try {
            var v = fn(window.PortBridge);
            return v === undefined ? fallback : v;
        } catch (e) {
            report("PortBridge call", e);
            return fallback;
        }
    }

    function parseJson(str) {
        if (!str) return null;
        try { return JSON.parse(str); } catch (e) { report("bridge JSON", e); return null; }
    }

    function noop() { return undefined; }

    /* The game's own Exit (the title menu's, and ig.system stop) reaches nw.gui: nw.App.quit() or
     * Window.get().close(). Both go to the app, which ends the process like the port's own Exit. */
    function quitApp() {
        call(function (bridge) { return bridge.quit(); });
    }

    /* ---- engine diagnostics ------------------------------------------------
     * The engine reports its own trouble through the console, and on a release build none of it
     * reaches a log anyone can read. Forward the first line of each (bounded; the app collapses
     * repeats) so a device-only failure is in the diagnostics panel. */
    (function () {
        var forwarded = 0;
        function forward(level, args) {
            try {
                if (forwarded >= 200) return;
                forwarded++;
                var text = Array.prototype.map.call(args, function (a) {
                    if (typeof a === "string") return a;
                    try { return JSON.stringify(a); } catch (e) { return String(a); }
                }).join(" ").split("\n")[0];
                if (HAS_BRIDGE) window.PortBridge.reportDiag("console." + level, text.slice(0, 300));
            } catch (e) { /* the console must never break */ }
        }
        ["error", "warn", "group", "groupCollapsed"].forEach(function (level) {
            var original = console[level];
            if (typeof original !== "function") return;
            console[level] = function () {
                forward(level, arguments);
                return original.apply(console, arguments);
            };
        });
    })();

    /* ---- fs, backed by the bridge ----------------------------------------- */

    function enoent(p) {
        var e = new Error("ENOENT: no such file or directory, open '" + p + "'");
        e.code = "ENOENT";
        e.errno = -2;
        return e;
    }

    function fsExistsRaw(p) { return call(function (b) { return b.fsExists(String(p)); }, false); }

    function makeStats(st) {
        var ms = typeof st.mtime === "number" ? st.mtime : Date.parse(st.mtime || 0) || 0;
        var mtime = new Date(ms);
        return {
            size: typeof st.size === "number" ? st.size : 0,
            mtime: mtime, mtimeMs: ms,
            atime: mtime, atimeMs: ms,
            ctime: mtime, ctimeMs: ms,
            birthtime: mtime, birthtimeMs: ms,
            isDirectory: function () { return !!st.dir; },
            isFile: function () { return !st.dir; },
            isSymbolicLink: function () { return false; },
            isBlockDevice: function () { return false; },
            isCharacterDevice: function () { return false; },
            isFIFO: function () { return false; },
            isSocket: function () { return false; }
        };
    }

    function statRaw(p) {
        var st = parseJson(call(function (b) { return b.fsStatSync(String(p)); }, null));
        return st ? makeStats(st) : null;
    }

    function makeDirent(name, dir) {
        return {
            name: name,
            isDirectory: function () { return !!dir; },
            isFile: function () { return !dir; },
            isSymbolicLink: function () { return false; },
            isBlockDevice: function () { return false; },
            isCharacterDevice: function () { return false; },
            isFIFO: function () { return false; },
            isSocket: function () { return false; }
        };
    }

    function readdirRaw(p) {
        var list = parseJson(call(function (b) { return b.fsReaddir(String(p)); }, null));
        if (!list) return null;
        var out = [];
        for (var i = 0; i < list.length; i++) out.push(makeDirent(list[i].n, list[i].d));
        return out;
    }

    function writeRaw(p, data) {
        return call(function (b) { return b.fsWriteFile(String(p), String(data)); }, false) === true;
    }

    function rmRaw(p) { return call(function (b) { return b.fsRm(String(p)); }, false) === true; }
    function mkdirRaw(p) { return call(function (b) { return b.fsMkdir(String(p)); }, false) === true; }
    function copyRaw(a, b) { return call(function (br) { return br.fsCopyFile(String(a), String(b)); }, false) === true; }
    function renameRaw(a, b) { return call(function (br) { return br.fsRename(String(a), String(b)); }, false) === true; }

    var promisesApi = {
        readFile: function (p) {
            var data = call(function (b) { return b.fsReadFile(String(p)); }, null);
            return (data === null || data === undefined) ? Promise.reject(enoent(p)) : Promise.resolve(data);
        },
        stat: function (p) {
            var st = statRaw(p);
            return st ? Promise.resolve(st) : Promise.reject(enoent(p));
        },
        rm: function (p) { return rmRaw(p) ? Promise.resolve() : Promise.reject(enoent(p)); },
        rename: function (from, to) {
            return renameRaw(from, to) ? Promise.resolve() : Promise.reject(enoent(from));
        },
        writeFile: function (p, data) {
            return writeRaw(p, data) ? Promise.resolve() : Promise.reject(enoent(p));
        },
        unlink: function (p) { return rmRaw(p) ? Promise.resolve() : Promise.reject(enoent(p)); },
        mkdir: function (p) { return mkdirRaw(p) ? Promise.resolve() : Promise.reject(enoent(p)); },
        copyFile: function (a, b) { return copyRaw(a, b) ? Promise.resolve() : Promise.reject(enoent(a)); }
    };

    /* ig.Storage's file path list uses the callback forms (readFile/writeFile/rename); the extension
     * loader uses the synchronous ones. Absence rejects / throws, exactly like Node. */
    var fs = {
        existsSync: function (p) { return fsExistsRaw(p) === true; },
        statSync: function (p) {
            var st = statRaw(p);
            if (!st) throw enoent(p);
            return st;
        },
        lstatSync: function (p) { return fs.statSync(p); },
        readdirSync: function (p) {
            var l = readdirRaw(p);
            if (!l) throw enoent(p);
            return l;
        },
        readdir: function (p, opts, cb) {
            if (typeof opts === "function") { cb = opts; }
            if (typeof cb !== "function") return;
            var l = readdirRaw(p);
            if (l) cb(null, l); else cb(enoent(p));
        },
        readFileSync: function (p) {
            var data = call(function (b) { return b.fsReadFile(String(p)); }, null);
            if (data === null || data === undefined) throw enoent(p);
            return data;
        },
        readFile: function (p, opts, cb) {
            if (typeof opts === "function") { cb = opts; }
            if (typeof cb !== "function") return;
            var data = call(function (b) { return b.fsReadFile(String(p)); }, null);
            if (data === null || data === undefined) cb(enoent(p));
            else cb(null, data);
        },
        writeFileSync: function (p, data) { if (!writeRaw(p, data)) throw enoent(p); },
        writeFile: function (p, data, opts, cb) {
            if (typeof opts === "function") { cb = opts; }
            if (typeof cb !== "function") return;
            cb(writeRaw(p, data) ? null : enoent(p));
        },
        appendFile: function (p, data, opts, cb) {
            if (typeof opts === "function") { cb = opts; }
            var prev = call(function (b) { return b.fsReadFile(String(p)); }, null);
            var ok = writeRaw(p, (prev === null || prev === undefined ? "" : prev) + data);
            if (typeof cb === "function") cb(ok ? null : enoent(p));
        },
        mkdirSync: function (p) { if (!mkdirRaw(p)) throw enoent(p); },
        rmdirSync: function (p) { if (!rmRaw(p)) throw enoent(p); },
        unlinkSync: function (p) { if (!rmRaw(p)) throw enoent(p); },
        rmSync: function (p) { if (!rmRaw(p)) throw enoent(p); },
        rm: function (p, opts, cb) {
            if (typeof opts === "function") { cb = opts; }
            if (typeof cb === "function") cb(rmRaw(p) ? null : enoent(p));
        },
        copyFileSync: function (a, b) { if (!copyRaw(a, b)) throw enoent(a); },
        renameSync: function (a, b) { if (!renameRaw(a, b)) throw enoent(a); },
        rename: function (a, b, cb) {
            if (typeof cb === "function") cb(renameRaw(a, b) ? null : enoent(a));
        },
        exists: function (p, cb) { if (typeof cb === "function") cb(fsExistsRaw(p) === true); },
        watch: function () { return { close: noop, on: noop, addListener: noop, unwatch: noop }; },
        createReadStream: function () { return { on: noop, close: noop, destroy: noop, pipe: noop }; },
        createWriteStream: function () { return { write: noop, end: noop, on: noop, close: noop }; },
        constants: { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1 },
        promises: promisesApi
    };

    var path = {
        join: function () {
            var parts = [];
            for (var i = 0; i < arguments.length; i++) {
                var a = arguments[i];
                if (a === undefined || a === null || a === "") continue;
                parts.push(String(a));
            }
            return parts.join("/").replace(/\/+/g, "/");
        },
        resolve: function () { return path.join.apply(null, arguments); },
        normalize: function (p) { return String(p).replace(/\/+/g, "/"); },
        dirname: function (p) {
            var s = String(p).replace(/\/+$/, "");
            var i = s.lastIndexOf("/");
            return i <= 0 ? (i === 0 ? "/" : ".") : s.substring(0, i);
        },
        basename: function (p, ext) {
            var s = String(p).replace(/\/+$/, "");
            var b = s.substring(s.lastIndexOf("/") + 1);
            return (ext && b.slice(-ext.length) === ext) ? b.slice(0, -ext.length) : b;
        },
        extname: function (p) {
            var b = path.basename(p);
            var i = b.lastIndexOf(".");
            return i <= 0 ? "" : b.substring(i);
        },
        sep: "/",
        delimiter: ":",
        posix: null
    };
    path.posix = path;
    path.win32 = path;

    /* ---- nw.gui: the OLD Node-WebKit API, on purpose ---------------------- */

    function windowStub() {
        return {
            close: quitApp, on: noop, once: noop, off: noop, show: noop, hide: noop, focus: noop,
            minimize: noop, maximize: noop, unmaximize: noop, restore: noop, resizeTo: noop, moveTo: noop,
            enterFullscreen: noop, leaveFullscreen: noop, isFullscreen: false, setAlwaysOnTop: noop,
            setZoomLevel: noop, reload: noop, isDevToolsOpen: function () { return false; },
            showDevTools: noop, closeDevTools: noop, window: window,
            width: window.innerWidth, height: window.innerHeight
        };
    }

    var nwGui = {
        App: {
            /* The literal /saves: the bridge maps everything under it onto the picked saves folder, so
             * CrossCode's own App.dataPath-based save files land there and travel with the folder. */
            argv: [], dataPath: "/saves", quit: quitApp, clearCache: noop,
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
        isActive: function () { return false; }, isSteamRunning: function () { return false; },
        activateAchievement: noop, clearAchievement: noop, activateGameOverlayToStore: noop
    };

    window.require = function (name) {
        switch (name) {
            case "fs": return fs;
            case "path": return path;
            case "os": return {
                platform: function () { return "linux"; }, arch: function () { return "x64"; },
                tmpdir: function () { return "/tmp"; }, homedir: function () { return "/saves"; }
            };
            case "vm": return {
                createContext: function () { return {}; },
                runInContext: noop, runInNewContext: noop, isContext: function () { return true; }
            };
            case "nw.gui": return nwGui;
            default:
                /* The extension loader asks for "./modules/greenworks-<nwjsVersion>/greenworks". */
                if (name.indexOf("./modules/") === 0 || name.indexOf("greenworks") === 0) return greenworks;
                return {};
        }
    };

    window.nw = {
        App: nwGui.App, Window: nwGui.Window, Screen: nwGui.Screen,
        Clipboard: nwGui.Clipboard, Shell: nwGui.Shell, Menu: nwGui.Menu, Tray: nwGui.Tray
    };

    /* Object on purpose: this is what makes ig.platform DESKTOP. The version string must parse as
     * X.Y.Z - the engine reads it with a regex and uses it to pick a greenworks build. */
    window.process = {
        platform: "linux", arch: "x64", argv: ["crosscode"],
        versions: { "node-webkit": "0.86.0", nwjs: "0.86.0", node: "21.1.0", chrome: "123.0.6312.87", nw: "0.86.0" },
        env: { HOME: "/saves", TMPDIR: "/tmp" },
        once: noop, on: noop, removeListener: noop, removeAllListeners: noop, emit: noop,
        nextTick: function (fn) { setTimeout(fn, 0); },
        exit: noop, cwd: function () { return ""; },
        pid: 1, title: "node", browser: false
    };

    /* ---- gamepad ---------------------------------------------------------
     * The Kotlin side reads the physical pad from InputDevice and emits a real W3C standard layout
     * (mapping "standard": axes 0..3 = LX, LY, RX, RY and 17 buttons in the W3C order). One pooled
     * object is rebuilt in place - the engine polls every frame. */
    var GP_BUTTONS = 17;
    var pad = {
        id: "Android Gamepad (STANDARD GAMEPAD Vendor: 045e Product: 028e)",
        index: 0,
        connected: false,
        mapping: "standard",
        timestamp: 0,
        axes: [0, 0, 0, 0],
        buttons: []
    };
    for (var bi = 0; bi < GP_BUTTONS; bi++) pad.buttons.push({ pressed: false, value: 0 });
    var padPool = [pad];
    var noPads = [];

    function now() {
        try { return window.performance && performance.now ? performance.now() : Date.now(); }
        catch (e) { return Date.now(); }
    }

    function pollGamepads() {
        applyFpsLimit();
        updateStats();
        var st = parseJson(call(function (b) { return b.getGamepadJson(); }, ""));
        if (!st) {
            pad.connected = false;
            return noPads;
        }
        pad.connected = true;
        pad.timestamp = now();
        var axes = st.axes || [];
        for (var i = 0; i < 4; i++) {
            var v = axes[i];
            pad.axes[i] = (typeof v === "number" && isFinite(v)) ? v : 0;
        }
        var buttons = st.buttons || [];
        for (var j = 0; j < GP_BUTTONS; j++) {
            var src = buttons[j], dst = pad.buttons[j];
            if (src) {
                var val = typeof src.value === "number" && isFinite(src.value) ? src.value : 0;
                dst.value = val;
                dst.pressed = !!src.pressed;
                dst.touched = dst.pressed;
            } else {
                dst.value = 0;
                dst.pressed = false;
                dst.touched = false;
            }
        }
        return padPool;
    }

    (function installGamepads() {
        try {
            Object.defineProperty(navigator, "getGamepads", {
                get: function () { return pollGamepads; },
                set: function () { report("gamepad", "kept the native bridge; ignored another navigator.getGamepads override"); },
                configurable: false
            });
        } catch (e) {
            try {
                navigator.getGamepads = pollGamepads;
            } catch (e2) {
                report("gamepad install", e2);
            }
        }
    })();

    /* ---- frame-rate limit -------------------------------------------------
     * The side menu's frame-rate switch and the rate slider it reveals (20/30/40/45/60, see FpsLimit).
     * The engine drives everything it draws from requestAnimationFrame, so gating rAF gates the
     * frames, and half the frames is half the GPU time. Nothing in the game's files is touched and its
     * clock is not fooled: only the presents drop. */
    var fpsLimit = 0;
    var fpsFrameMs = 0;
    var fpsLimited = false;
    var fpsLastFrame = 0;
    var fpsScheduled = false;
    var fpsQueue = [];
    var fpsCancelled = {};
    var fpsNextId = 1;

    (function installFpsGate() {
        var original = window.requestAnimationFrame;
        var cancel = window.cancelAnimationFrame;
        if (typeof original !== "function") return;

        function schedule() {
            if (fpsScheduled) return;
            fpsScheduled = true;
            original.call(window, tick);
        }

        function tick(t) {
            fpsScheduled = false;
            if (fpsLimited && t - fpsLastFrame < fpsFrameMs - fpsFrameMs / 10) {
                schedule();
                return;
            }
            fpsLastFrame = t;
            var batch = fpsQueue;
            fpsQueue = [];
            for (var i = 0; i < batch.length; i++) {
                var job = batch[i];
                if (fpsCancelled[job.id]) {
                    delete fpsCancelled[job.id];
                    continue;
                }
                job.cb(t);
            }
        }

        window.requestAnimationFrame = function (cb) {
            if (!fpsLimited) return original.call(window, cb);
            var id = fpsNextId++;
            fpsQueue.push({ id: id, cb: cb });
            schedule();
            return id;
        };
        window.cancelAnimationFrame = function (id) {
            if (!fpsLimited && typeof cancel === "function") return cancel.call(window, id);
            fpsCancelled[id] = true;
        };
    })();

    function applyFpsLimit() {
        var fps = call(function (b) { return b.getFpsLimit(); }, 0) | 0;
        if (fps < 0) fps = 0;
        if (fps === fpsLimit) return;
        fpsLimit = fps;
        fpsFrameMs = fps > 0 ? 1000 / fps : 0;
        fpsLimited = fps > 0;
        fpsLastFrame = 0;
        reportDiag("fps", fpsLimited ? "limited to " + fps + " fps" : "unlimited");
    }

    /* ---- port overlays ---------------------------------------------------
     * Both are driven by the Kotlin side and applied on the gamepad poll, which the engine performs
     * once per frame. The canvas is the game's own `#canvas` (its CSS lays it out; the port reads it
     * only to report the resolution). */
    var portCanvas = null;

    function gameCanvas() {
        if (!portCanvas || !portCanvas.isConnected) portCanvas = document.getElementById("canvas");
        return portCanvas;
    }

    var FPS_WINDOW_MS = 500;
    var statsDiv = null;
    var statsOn = false;
    var statsFrames = 0;
    var statsStart = 0;

    function statsLayer() {
        if (statsDiv || !document.body) return statsDiv;
        statsDiv = document.createElement("div");
        statsDiv.style.cssText = "position:fixed;left:8px;top:8px;z-index:2147483647;" +
            "padding:2px 6px;border-radius:4px;background:rgba(0,0,0,0.55);" +
            "color:#e8dcc8;font:12px/1.4 monospace;pointer-events:none";
        document.body.appendChild(statsDiv);
        return statsDiv;
    }

    function updateStats() {
        var on = call(function (b) { return b.getStatsEnabled(); }, false) === true;
        if (on !== statsOn) {
            statsOn = on;
            statsFrames = 0;
            statsStart = 0;
            var fresh = statsLayer();
            if (fresh) fresh.style.display = on ? "" : "none";
        }
        if (!statsOn) return;
        var div = statsLayer();
        if (!div) return;
        var t = now();
        if (!statsStart) {
            statsStart = t;
            statsFrames = 0;
            return;
        }
        statsFrames++;
        if (t - statsStart < FPS_WINDOW_MS) return;
        var canvas = gameCanvas();
        var parts = [
            Math.round(statsFrames * 1000 / (t - statsStart)) + " fps",
            canvas ? canvas.width + "x" + canvas.height : "?"
        ];
        var phone = parseJson(call(function (b) { return b.getTelemetry(); }, ""));
        if (phone) {
            var battery = phone.level === null || phone.level === undefined
                ? "" : "bat " + phone.level + "%";
            if (phone.temp !== null && phone.temp !== undefined) {
                battery += (battery ? " " : "bat ") + (phone.temp / 10).toFixed(1) + "\u00b0C";
            }
            if (battery) parts.push(battery);
            if (phone.thermal) parts.push("therm " + phone.thermal);
        }
        div.textContent = parts.join(" \u00b7 ");
        statsStart = t;
        statsFrames = 0;
    }

    function reportDiag(kind, text) {
        try {
            if (HAS_BRIDGE) window.PortBridge.reportDiag(kind, text);
        } catch (e) { /* nothing left to do */ }
    }

    /* ---- engine-side diagnostics ------------------------------------------
     * The device facts the app cannot see from Kotlin: which GL backend the WebView picked (if any)
     * and what the page is rendering at. Sent once the game's canvas exists, and again if the GL
     * context appears later. */
    var FACTS_WAIT_MS = 5000;
    var facts = { sent: false, hadGl: false, startedAt: now() };

    function glDescription(gl) {
        if (!gl) return "none";
        var info = gl.getExtension("WEBGL_debug_renderer_info");
        return info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.VERSION);
    }

    function sendFacts() {
        var canvas = gameCanvas();
        var gl = null;
        try {
            gl = canvas && (canvas.getContext("webgl2") || canvas.getContext("webgl"));
        } catch (e) { gl = null; }
        reportDiag("facts", [
            "game=CrossCode",
            "webgl2=" + !!window.WebGL2RenderingContext,
            "gl=" + glDescription(gl),
            "driver=" + (/angle/i.test(glDescription(gl)) ? "ANGLE" : "native"),
            "canvas=" + (canvas ? canvas.width + "x" + canvas.height : "?"),
            "window=" + (window.innerWidth || 0) + "x" + (window.innerHeight || 0) + "@" + (window.devicePixelRatio || 1),
            "platform=" + (window.ig && window.ig.getPlatformName ? window.ig.getPlatformName() : "?")
        ].join("; "));
    }

    setInterval(function () {
        if (facts.sent && facts.hadGl) return;
        var canvas = gameCanvas();
        var gl = null;
        try {
            gl = canvas && (canvas.getContext("webgl2") || canvas.getContext("webgl"));
        } catch (e) { gl = null; }
        if (!facts.sent && (canvas || now() - facts.startedAt >= FACTS_WAIT_MS)) {
            facts.sent = true;
            facts.hadGl = !!gl;
            sendFacts();
        } else if (gl && !facts.hadGl) {
            facts.hadGl = true;
            sendFacts();
        }
    }, 500);

    /* ---- error surfacing -------------------------------------------------
     * A device-only failure is otherwise a black screen: forward everything to logcat (tag RfPort)
     * through the bridge. */
    window.addEventListener("error", function (e) {
        try {
            if (e && e.message) report("window.onerror", (e.filename || "?") + ":" + (e.lineno || 0) + " " + e.message);
        } catch (err) { /* ignore */ }
    }, true);
    window.addEventListener("unhandledrejection", function (e) {
        try {
            var r = e && e.reason;
            report("unhandledrejection", (r && r.message) ? r.message : String(r));
        } catch (err) { /* ignore */ }
    }, true);

    report("shim", "loaded" + (HAS_BRIDGE ? "" : " (no PortBridge!)") + " for CrossCode");
})();
