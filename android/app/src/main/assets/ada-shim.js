/*
 * Alabaster Dawn — Android WebView shim.
 *
 * Injected at document start (before terra/dist/bundle.js) by PortActivity.
 * It is the ported, device-backed version of tools/ada-browser-shim.js, which
 * was proven to boot the unmodified bundle to its title screen in a plain
 * browser. The in-memory fs of that prototype is replaced by synchronous calls
 * into the app's @JavascriptInterface bridge (window.AdaBridge), which serves
 * the user's picked game tree (read-only) and saves tree (read-write).
 *
 * Why each piece is needed (measured against this bundle, see FINDINGS.md 4.3):
 *  - window.require must be truthy and return an `fs`: the bundle's only webpack
 *    external is module 79896 = `require("fs")`, and the file/storage modules do
 *    `const fs = window.require && __webpack_require__(79896)` followed by a
 *    module-scope read of `fs.promises`. Without it the boot dies silently.
 *  - existsSync is load-bearing beyond storage: every sound resource probes
 *    `fs.existsSync(media/audio/...<ext>)` (bundle ~2047) to pick between
 *    ogg/wave/eab/flac. Answering truthfully makes the engine request the .ogg
 *    that actually ships instead of a .flac that does not.
 *  - window.nw is read by Storage.preparePaths (nw.App.dataPath) and by addons
 *    (nw.Window.get().on("close", ...)) during boot.
 *  - window.process MUST stay undefined: Engine.getPlatform() returns NWJS when
 *    `window.require && typeof window.process == "object"`, and the engine has a
 *    working BROWSER path we want.
 *
 * Async fs methods (fs.promises.*) resolve/reject per Node semantics: the
 * storage paths deliberately rely on rejected reads (StorageTools.loadFile tries
 * three paths and only then throws its own ENOENT) and on successful deletes
 * (StorageTools.deleteFile counts them), so absence must reject.
 */
(function () {
    "use strict";

    var HAS_BRIDGE = typeof window.AdaBridge == "object" && window.AdaBridge !== null;

    function report(what, err) {
        try {
            if (HAS_BRIDGE) {
                window.AdaBridge.reportJsError(what + ": " + (err && err.message ? err.message : String(err)));
            }
        } catch (e) { /* nothing left to do */ }
    }

    /* Every bridge call is guarded: a failure must never reach page code as an
     * exception, or it would look like a game crash. */
    function call(fn, fallback) {
        if (!HAS_BRIDGE) return fallback;
        try {
            var v = fn(window.AdaBridge);
            return v === undefined ? fallback : v;
        } catch (e) {
            report("AdaBridge call", e);
            return fallback;
        }
    }

    function parseJson(str) {
        if (!str) return null;
        try { return JSON.parse(str); } catch (e) { report("bridge JSON", e); return null; }
    }

    function enoent(p) {
        var e = new Error("ENOENT: no such file or directory, open '" + p + "'");
        e.code = "ENOENT";
        e.errno = -2;
        return e;
    }

    /* Status: 0 exists, 1 missing, 2 unresolvable (game tree, read-only space) */
    function fsExistsRaw(p) { return call(function (b) { return b.fsExists(String(p)); }, false); }

    function makeStats(st) {
        var ms = typeof st.mtime == "number" ? st.mtime : Date.parse(st.mtime || 0) || 0;
        var mtime = new Date(ms);
        return {
            size: typeof st.size == "number" ? st.size : 0,
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

    function rmRaw(p) {
        return call(function (b) { return b.fsRm(String(p)); }, false) === true;
    }

    function mkdirRaw(p) {
        return call(function (b) { return b.fsMkdir(String(p)); }, false) === true;
    }

    function copyRaw(a, b) {
        return call(function (br) { return br.fsCopyFile(String(a), String(b)); }, false) === true;
    }

    function renameRaw(a, b) {
        return call(function (br) { return br.fsRename(String(a), String(b)); }, false) === true;
    }

    function noop() { return undefined; }

    var promisesApi = {
        readFile: function (p) {
            var data = call(function (b) { return b.fsReadFile(String(p)); }, null);
            return (data === null || data === undefined) ? Promise.reject(enoent(p)) : Promise.resolve(data);
        },
        stat: function (p) {
            var st = statRaw(p);
            return st ? Promise.resolve(st) : Promise.reject(enoent(p));
        },
        rm: function (p) {
            return rmRaw(p) ? Promise.resolve() : Promise.reject(enoent(p));
        },
        rename: function (from, to) {
            return renameRaw(from, to) ? Promise.resolve() : Promise.reject(enoent(from));
        },
        writeFile: function (p, data) {
            return writeRaw(p, data) ? Promise.resolve() : Promise.reject(enoent(p));
        },
        unlink: function (p) {
            return rmRaw(p) ? Promise.resolve() : Promise.reject(enoent(p));
        },
        mkdir: function (p) {
            return mkdirRaw(p) ? Promise.resolve() : Promise.reject(enoent(p));
        },
        copyFile: function (a, b) {
            return copyRaw(a, b) ? Promise.resolve() : Promise.reject(enoent(a));
        }
    };

    /* ---- WebGL compatibility --------------------------------------------
     * The engine hard-requires `OES_draw_buffers_indexed` (System.initDom reads
     * it into g_glBufferExt and every pass then calls enableiOES/blendFunciOES/
     * colorMaskiOES). Android WebView picks its GL backend per device: on the
     * SM-S908U1 (Adreno 730) it reports "OpenGL ES 3.0 Chromium", which does not
     * expose that ES 3.2 extension, so g_glBufferExt stays null and the first
     * pass throws. All of the engine's uses pass index 0 - the indexed forms are
     * identical to the plain calls for the single active draw buffer - so a
     * faithful index-0 fallback is installed only when the real extension is
     * absent (desktop Chromium and other devices are untouched). */
    function indexedBufferShim(gl) {
        /* Signatures follow OES_draw_buffers_indexed: enable/disable take the target first,
         * everything else takes the buffer index first. Index 0 is the plain call. */
        return {
            enableiOES: function (target, index) { if (index === 0) gl.enable(target); },
            disableiOES: function (target, index) { if (index === 0) gl.disable(target); },
            blendEquationiOES: function (index, mode) { if (index === 0) gl.blendEquation(mode); },
            blendEquationSeparateiOES: function (index, modeRGB, modeAlpha) {
                if (index === 0) gl.blendEquationSeparate(modeRGB, modeAlpha);
            },
            blendFunciOES: function (index, src, dst) { if (index === 0) gl.blendFunc(src, dst); },
            blendFuncSeparateiOES: function (index, srcRGB, dstRGB, srcAlpha, dstAlpha) {
                if (index === 0) gl.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
            },
            colorMaskiOES: function (index, r, g, b, a) { if (index === 0) gl.colorMask(r, g, b, a); },
            drawBuffersiOES: function (index, buffers) {
                if (index === 0 && gl.drawBuffers) gl.drawBuffers(buffers);
            }
        };
    }

    function installIndexedBufferFallback(proto) {
        if (!proto || !proto.getExtension) return;
        var original = proto.getExtension;
        proto.getExtension = function (name) {
            var ext = original.call(this, name);
            if (ext || name !== "OES_draw_buffers_indexed") return ext;
            if (!this.__adaIndexedBuffers) {
                this.__adaIndexedBuffers = indexedBufferShim(this);
                report("webgl", "OES_draw_buffers_indexed missing; installed index-0 fallback");
            }
            return this.__adaIndexedBuffers;
        };
    }

    installIndexedBufferFallback(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);
    installIndexedBufferFallback(window.WebGLRenderingContext && WebGLRenderingContext.prototype);

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
        writeFileSync: function (p, data) {
            if (!writeRaw(p, data)) throw enoent(p);
        },
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
        mkdirSync: function (p) {
            if (!mkdirRaw(p)) throw enoent(p);
        },
        rmdirSync: function (p) {
            if (!rmRaw(p)) throw enoent(p);
        },
        unlinkSync: function (p) {
            if (!rmRaw(p)) throw enoent(p);
        },
        rmSync: function (p) {
            if (!rmRaw(p)) throw enoent(p);
        },
        rm: function (p, opts, cb) {
            if (typeof opts === "function") { cb = opts; }
            if (typeof cb === "function") cb(rmRaw(p) ? null : enoent(p));
        },
        copyFileSync: function (a, b) {
            if (!copyRaw(a, b)) throw enoent(a);
        },
        renameSync: function (a, b) {
            if (!renameRaw(a, b)) throw enoent(a);
        },
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

    window.require = function (name) {
        switch (name) {
            case "fs": return fs;
            case "path": return path;
            case "vm": return {
                createContext: function () { return {}; },
                runInContext: noop,
                runInNewContext: noop,
                isContext: function () { return true; }
            };
            case "./greenworks/greenworks":
                return {
                    init: function () { return false; },
                    activateGameOverlayToStore: noop,
                    isSteamRunning: function () { return false; }
                };
            default: return {};
        }
    };

    var screen = {
        bounds: { x: 0, y: 0, width: window.innerWidth, height: window.innerHeight },
        work_area: { x: 0, y: 0, width: window.innerWidth, height: window.innerHeight },
        scaleFactor: window.devicePixelRatio || 1
    };
    window.nw = {
        /* Opaque token, not a real path: the bridge maps everything under /saves
         * onto the picked saves tree. It must not contain "/Default", because
         * Storage.preparePaths cuts dataPath at the first "/Default". */
        App: { argv: [], quit: noop, dataPath: "/saves", clearCache: noop },
        Window: {
            get: function () {
                return {
                    close: noop, on: noop, once: noop, off: noop, show: noop, hide: noop,
                    focus: noop, enterFullscreen: noop, leaveFullscreen: noop,
                    isFullscreen: false, setAlwaysOnTop: noop, setZoomLevel: noop,
                    maximize: noop, unmaximize: noop, minimize: noop, restore: noop,
                    resizeTo: noop, moveTo: noop, reload: noop, setMinimumSize: noop,
                    window: window, width: window.innerWidth, height: window.innerHeight
                };
            },
            open: function () { return { on: noop, show: noop, close: noop }; },
            getAll: function () { return []; }
        },
        Screen: { screens: [screen], on: noop },
        Clipboard: { get: function () { return { set: noop, get: noop, clear: noop }; } },
        Shell: { openItem: noop, openExternal: noop, showItemInFolder: noop },
        Menu: { get: function () { return { items: [], popup: noop, remove: noop }; } },
        Tray: { get: function () { return { remove: noop, setTitle: noop }; } }
    };

    /* ---- gamepad ---------------------------------------------------------
     * The Kotlin side reads the physical pad from InputDevice and emits a real
     * W3C standard layout (mapping "standard": axes 0..3 = LX, LY, RX, RY and
     * 17 buttons in the W3C order). The id claims an Xbox vendor id so the game
     * picks Xbox button icons and stays out of its DualShock touchpad paths.
     * One pooled object is rebuilt in place - the game polls every frame. */
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
        applyViewAlign();
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

    /* The game folder may carry a user's own gamepad-fix.js (it rewrites
     * navigator.getGamepads to decode DirectInput pads and read GameNative's
     * shared memory). On Android the native bridge is the only source of pads,
     * so the override is made unassignable: a later script's assignment is
     * swallowed and logged instead of silently replacing the bridge. */
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

    /* ---- port overlays ---------------------------------------------------
     * Both are driven by the Kotlin side and both are applied on the gamepad poll, which the engine
     * performs exactly once per frame (System.runInner -> g_input.update -> updateGamepads). */

    var portCanvas = null;

    /* The render canvas, cached: re-queried only when the engine replaces it. Shared by the picture
     * alignment and the stats readout, which reports its drawing buffer as the resolution. */
    function gameCanvas() {
        if (!portCanvas || !portCanvas.isConnected) portCanvas = document.querySelector(".xgCanvas");
        return portCanvas;
    }

    /* Picture alignment. The engine has two display scales, so the position is written with both
     * knobs: with `sharp-pixels` off the canvas element fills the window and `object-fit: contain`
     * letterboxes the render buffer inside it, so the picture moves with `object-position`; with it
     * on the element box is pinned to an integer multiple of the buffer and centred by
     * `margin: auto`, so the box itself moves with `top`/`bottom`. Each knob is a no-op in the mode
     * that does not use it, and "" restores the stylesheet's centred default. */
    var alignApplied = null;
    var alignElement = null;

    function applyViewAlign() {
        var mode = call(function (b) { return b.getViewAlign(); }, "center");
        var canvas = gameCanvas();
        if (!canvas) return;
        if (mode === alignApplied && canvas === alignElement) return;
        alignApplied = mode;
        alignElement = canvas;
        canvas.style.objectPosition = mode === "top" ? "50% 0%" : (mode === "bottom" ? "50% 100%" : "");
        canvas.style.top = mode === "top" ? "0px" : (mode === "bottom" ? "auto" : "");
        canvas.style.bottom = mode === "bottom" ? "0px" : (mode === "top" ? "auto" : "");
    }

    /* Frame-rate/resolution/battery readout, drawn as its own DOM layer so it costs the renderer
     * nothing and survives any resolution change. `statsFrames` counts gamepad polls = engine
     * frames; the battery and thermal numbers are asked for only when the text is repainted (twice
     * a second), never per frame. */
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

    /* ---- canvas geometry -------------------------------------------------
     * The engine owns the canvas layout: with `sharp-pixels` off it keeps
     * `width/height: 100%` and `object-fit: contain`, so the render buffer is scaled to fill the
     * window (its "Resolution" option documents exactly that), and with it on it pins the CSS size
     * to an integer multiple of the buffer. The port writes the picture's *position* only (below,
     * for the side menu's Top/Centre/Bottom). Never pin the CSS size to `canvas.width` from here:
     * at Resolution 640x360 that shrank the game to a small picture in the middle of the screen
     * instead of filling it. */

    /* ---- error surfacing -------------------------------------------------
     * A device-only failure is otherwise a black screen: forward everything to
     * logcat (tag AdaPort) through the bridge. */
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

    report("shim", "loaded" + (HAS_BRIDGE ? "" : " (no AdaBridge!)"));
})();
