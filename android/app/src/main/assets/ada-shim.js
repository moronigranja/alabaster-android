/*
 * Alabaster Dawn — Android WebView shim.
 *
 * Injected at document start (before terra/dist/bundle.js) by PortActivity.
 * It is the ported, device-backed version of tools/ada-browser-shim.js, which
 * was proven to boot the unmodified bundle to its title screen in a plain
 * browser. The in-memory fs of that prototype is replaced by synchronous calls
 * into the app's @JavascriptInterface bridge (window.PortBridge), which serves
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

    var HAS_BRIDGE = typeof window.PortBridge == "object" && window.PortBridge !== null;

    function report(what, err) {
        try {
            if (HAS_BRIDGE) {
                window.PortBridge.reportJsError(what + ": " + (err && err.message ? err.message : String(err)));
            }
        } catch (e) { /* nothing left to do */ }
    }

    /* Every bridge call is guarded: a failure must never reach page code as an
     * exception, or it would look like a game crash. */
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

    /* The engine's only two ways out: System.quit() calls nw.App.quit(), and the title screen's EXIT
     * button calls nw.Window.get().close() 300 ms later (bundle `closeGame`). Both were noops, so the
     * engine tore its menu down waiting for a process exit that never came and the picture stopped
     * responding. They now ask the app to leave the game. */
    function quitApp() {
        call(function (bridge) { return bridge.quit(); });
    }

    function parseJson(str) {
        if (!str) return null;
        try { return JSON.parse(str); } catch (e) { report("bridge JSON", e); return null; }
    }

    /* The game's vertex shaders each declare `uniform vec2 u_texSlotCoords[TEX_SLOT_COUNT]` (256
     * elements), which costs one uniform *vector* per element on the drivers seen here; a device
     * whose MAX_VERTEX_UNIFORM_VECTORS is the GLES3 minimum of 256 cannot compile them, and the boot
     * then freezes forever with `too many uniforms` in a console nobody can read. The app decides how
     * much of the table to serve (ShaderSlots), so it needs this number *before* the first shader
     * request - this runs at document start, i.e. before the bundle asks for anything. One throwaway
     * context, closed immediately; a device without WebGL2 answers 0 and gets the safe default. */
    var glLimits = (function () {
        /* What the driver *reports* is not what a shader can *carry*. Adreno's compiler packs a vec2
         * array two slots to a vec4 (the GLSL ES 3.0 default-block rules allow it), so the game's own
         * 256-slot table links on a device stuck at the GLES3 minimum of 256 vectors - while
         * SwiftShader packs one slot per vector and genuinely fails with `too many uniforms` (issue
         * #1). Deciding from the number alone therefore rewrites the shaders on devices that never
         * needed it, which shrinks the engine's atlas and packs `gui.vert` for nothing. So the port is
         * told the outcome of a *link*, for the two shapes the game's shaders have: one table of 256
         * (every `.vert`) and two of them (`gui.vert`), each with the reserve the real shaders carry
         * besides (48 and 32 vectors, see ShaderSlots). */
        var GAME_SLOTS = 256;
        function links(tables) {
            var reserve = tables === 2 ? 32 : 48;
            var source = [
                "#version 300 es",
                "precision mediump float;",
                "uniform vec2 u_slot[" + GAME_SLOTS + "];",
                tables === 2 ? "uniform vec2 u_font[" + GAME_SLOTS + "];" : "",
                "uniform vec4 u_reserve[" + reserve + "];",
                "uniform mat4 u_m;",
                "uniform float u_index;",
                "void main() {",
                "    vec4 r = u_reserve[0];",
                "    for (int i = 0; i < " + reserve + "; i++) r += u_reserve[i];",
                "    vec2 s = u_slot[int(u_index)]" +
                    (tables === 2 ? " + u_font[int(u_index)]" : "") + ";",
                "    gl_Position = u_m * vec4(s, 0.0, 1.0) + r;",
                "}"
            ].join("\n");
            var fragment = "#version 300 es\nprecision mediump float;\nout vec4 c;\n" +
                "void main() { c = vec4(1.0); }";
            var program = gl.createProgram();
            var vertex = gl.createShader(gl.VERTEX_SHADER);
            var pixel = gl.createShader(gl.FRAGMENT_SHADER);
            gl.shaderSource(vertex, source);
            gl.compileShader(vertex);
            gl.shaderSource(pixel, fragment);
            gl.compileShader(pixel);
            gl.attachShader(program, vertex);
            gl.attachShader(program, pixel);
            gl.linkProgram(program);
            var ok = gl.getShaderParameter(vertex, gl.COMPILE_STATUS) === true &&
                gl.getShaderParameter(pixel, gl.COMPILE_STATUS) === true &&
                gl.getProgramParameter(program, gl.LINK_STATUS) === true;
            gl.deleteShader(vertex);
            gl.deleteShader(pixel);
            gl.deleteProgram(program);
            return ok;
        }
        try {
            var canvas = document.createElement("canvas");
            var gl = canvas.getContext("webgl2");
            if (!gl) {
                return {
                    vertexUniforms: 0, fragmentUniforms: 0, varyingVectors: 0,
                    oneTableLinks: false, twoTablesLinks: false
                };
            }
            var limits = {
                vertexUniforms: gl.getParameter(gl.MAX_VERTEX_UNIFORM_VECTORS),
                fragmentUniforms: gl.getParameter(gl.MAX_FRAGMENT_UNIFORM_VECTORS),
                varyingVectors: gl.getParameter(gl.MAX_VARYING_VECTORS),
                oneTableLinks: links(1),
                twoTablesLinks: links(2)
            };
            var lose = gl.getExtension("WEBGL_lose_context");
            if (lose) lose.loseContext();
            return limits;
        } catch (e) {
            report("gl limits", e);
            return {
                vertexUniforms: 0, fragmentUniforms: 0, varyingVectors: 0,
                oneTableLinks: false, twoTablesLinks: false
            };
        }
    })();
    call(function (bridge) { return bridge.setVertexUniformVectors(glLimits.vertexUniforms); });
    call(function (bridge) { return bridge.setShaderTables(glLimits.oneTableLinks, glLimits.twoTablesLinks); });

    /* A second document-start question, for a different quirk of the same front end: some ES 3.0
     * compilers do not carry a shader's declared *default* precision onto an array type written as
     * `type[size] name`, and refuse the shader with
     * `S0032: no default precision defined for variable 'vec4[4]'` - though the shader does declare
     * one (`precision mediump float;`). That is what froze the boot on a Mali-G720 device with three
     * fragment shaders pending (FINDINGS 22). The app serves those declarations lifted into the
     * declarator spelling (`type name[size]`) only when the page's own compiler refuses them.
     *
     * The first version of this probe compiled **one synthetic shader** of the same shapes, and on
     * that device the driver accepted that text while refusing the game's own bytes, so the app
     * served them and the boot froze again (FINDINGS 22.5). This one compiles **the declarations the
     * game's shaders are written with**, one program per shape, on a context made with the engine's
     * own attributes, and it **links** every program: the front end that refuses them was answering a
     * compile-only question differently from the engine's own compiles.
     *
     * The answer is deliberately one-sided: any shape refused, or no context to ask, means "lift".
     * Lifting is the side that compiles everywhere - the same bytes, spelled the way the game's own
     * vertex shaders already spell arrays - while the game's own spelling is exactly what the probe
     * doubts, so doubting wrongly costs a frozen boot and lifting wrongly costs nothing. What each
     * shape did goes to the record, so the next device that disagrees says so in one line. */
    var shaderArrays = (function () {
        var VERTEX = [
            "#version 300 es",
            "precision highp float;",
            "void main() { gl_Position = vec4(0.0); }"
        ].join("\n");

        /* `flat in vec2[4] v_flowDirs;` is in the fragment shaders of `water-plane` and
         * `plane-depth`, whose vertex shaders write the array on the name - so the probe's vertex
         * side is the spelling the game's own `.vert` already uses. */
        var VERTEX_FLOW = [
            "#version 300 es",
            "precision highp float;",
            "flat out vec2 v_flowDirs[4];",
            "void main() { for (int i = 0; i < 4; i++) v_flowDirs[i] = vec2(0.0);",
            "    gl_Position = vec4(0.0); }"
        ].join("\n");

        var HEAD = ["#version 300 es", "precision mediump float;", "out vec4 o;"];

        /* One shape per declaration the game writes, copied from the files the Mali report named:
         * `lib/water.glsl` (the global const, the parameter, the return type, the two parameter
         * arrays and the local), `lib/color-utils.glsl` (the parameter sized by a macro),
         * `fragment/water-plane.frag` (the varying) and `fragment/post/analog-filter.frag` (the
         * constructor in an argument). Only the code around a declaration is written to be small. */
        function entry(name, lines, vertex) {
            return {
                name: name,
                vertex: vertex || VERTEX,
                source: HEAD.concat(lines).join("\n")
            };
        }
        var SHAPES = [
            entry("in/out", [
                "flat in vec2[4] v_flowDirs;",
                "void main() { o = vec4(v_flowDirs[0], 0.0, 1.0); }"
            ], VERTEX_FLOW),
            entry("const-global", [
                "const vec2[12] DIRECTIONS = vec2[](",
                "    vec2(0.0, -1.0), vec2(0.0, -1.0), vec2(0.0, -1.0),",
                "    vec2(1.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 0.0),",
                "    vec2(0.0, 1.0), vec2(0.0, 1.0), vec2(0.0, 1.0),",
                "    vec2(-1.0, 0.0), vec2(-1.0, 0.0), vec2(-1.0, 0.0));",
                "void main() { o = vec4(DIRECTIONS[0], 0.0, 1.0); }"
            ]),
            entry("param", [
                "vec2 bilinear(vec2[4] v, float t0, float t1) {" +
                    " return mix(v[0], v[1], clamp(t0, 0.0, 1.0)) + vec2(t1); }",
                "void main() { o = vec4(bilinear(vec2[](vec2(0.0), vec2(1.0), vec2(2.0), vec2(3.0))," +
                    " 0.0, 1.0), 0.0, 1.0); }"
            ]),
            entry("return", [
                "uniform vec2 u_flow;",
                "vec4[4] computeWaveFactors(out vec2 globalFlow, vec2 flowDir) {",
                "    globalFlow = normalize(flowDir);",
                "    return vec4[](",
                "        vec4(globalFlow, 1.0, 0.5),",
                "        vec4(globalFlow, 0.5, 0.25),",
                "        vec4(globalFlow, 0.25, 0.125),",
                "        vec4(globalFlow, 0.125, 0.125));",
                "}",
                "void main() { vec2 g; vec4 w[4] = computeWaveFactors(g, u_flow);" +
                    " o = vec4(w[0].xy + g, 0.0, 1.0); }"
            ]),
            entry("params", [
                "vec3 computeGerstnerOffset(vec3 pos, vec4[4] waves, out vec3 tangent," +
                    " out vec3 binormal, float zScale, float wShift, float speed," +
                    " float[4] amps, float[4] phases) {",
                "    tangent = vec3(1.0, 0.0, 0.0); binormal = vec3(0.0, 0.0, 1.0);",
                "    return pos + waves[0].xyz * amps[0] + vec3(phases[0]) * zScale" +
                    " + vec3(wShift + speed);",
                "}",
                "void main() { vec3 t, b; vec4 w[4]; float a[4]; float p[4];",
                "    o = vec4(computeGerstnerOffset(vec3(0.0), w, t, b, 1.0, 0.0, 1.0, a, p), 1.0); }"
            ]),
            entry("local", [
                "void main() { vec4[4] waves; o = vec4(waves[0].xy, 0.0, 1.0); }"
            ]),
            entry("macro-size", [
                "#ifndef COLOR_RAMP_COUNT",
                "#define COLOR_RAMP_COUNT 5",
                "#endif",
                "vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) { return colors[1]; }",
                "void main() { o = vec4(colorRamp(0.5," +
                    " vec3[](vec3(0.0), vec3(1.0), vec3(2.0), vec3(3.0), vec3(4.0))), 1.0); }"
            ]),
            entry("ctor-arg", [
                "vec3 colorRamp(float t, vec3 colors[5]) { return colors[1]; }",
                "void main() { o = vec4(colorRamp(0.5," +
                    " vec3[5](vec3(0.0), vec3(1.0), vec3(2.0), vec3(3.0), vec3(4.0))), 1.0); }"
            ]),
            /* The post pass's own pair, exactly as `lib/color-utils.glsl` and
             * `fragment/post/analog-filter.frag` write it: a parameter sized by a macro, called with a
             * sized constructor. Each half is above; this is the combination the file is, and the Mali
             * run in §22.9 is what showed the halves are not the whole. */
            entry("ramp-call", [
                "#ifndef COLOR_RAMP_COUNT",
                "#define COLOR_RAMP_COUNT 5",
                "#endif",
                "vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors) { return colors[1]; }",
                "void main() { o = vec4(colorRamp(0.5," +
                    " vec3[5](vec3(0.0), vec3(1.0), vec3(2.0), vec3(3.0), vec3(4.0))), 1.0); }"
            ])
        ];

        function firstLine(log) {
            var lines = String(log || "").split("\n");
            for (var i = 0; i < lines.length; i++) {
                if (lines[i].replace(/^\s+|\s+$/g, "").length > 0) return lines[i].slice(0, 80);
            }
            return "";
        }

        function compileOne(gl, type, source) {
            var shader = gl.createShader(type);
            gl.shaderSource(shader, source);
            gl.compileShader(shader);
            var ok = gl.getShaderParameter(shader, gl.COMPILE_STATUS) === true;
            return { shader: shader, ok: ok, log: ok ? "" : firstLine(gl.getShaderInfoLog(shader)) };
        }

        function measure(gl, shape) {
            var vertex = compileOne(gl, gl.VERTEX_SHADER, shape.vertex);
            var pixel = compileOne(gl, gl.FRAGMENT_SHADER, shape.source);
            var program = gl.createProgram();
            gl.attachShader(program, vertex.shader);
            gl.attachShader(program, pixel.shader);
            gl.linkProgram(program);
            var linked = gl.getProgramParameter(program, gl.LINK_STATUS) === true;
            var log = pixel.log || (linked ? "" : firstLine(gl.getProgramInfoLog(program)));
            gl.deleteProgram(program);
            gl.deleteShader(vertex.shader);
            gl.deleteShader(pixel.shader);
            return { ok: vertex.ok && pixel.ok && linked, log: log };
        }

        try {
            var canvas = document.createElement("canvas");
            /* The engine's own attributes: a context made some other way may not be the stack the
             * engine's shaders are compiled on. */
            var gl = canvas.getContext("webgl2",
                { antialias: false, powerPreference: "high-performance" });
            if (!gl) return { needed: true, detail: "no webgl2 context to ask" };
            var renderer = glDescription(gl);
            var refused = [];
            for (var i = 0; i < SHAPES.length; i++) {
                var result = measure(gl, SHAPES[i]);
                if (!result.ok) refused.push(SHAPES[i].name + (result.log ? " (" + result.log + ")" : ""));
            }
            var lose = gl.getExtension("WEBGL_lose_context");
            if (lose) lose.loseContext();
            var named = refused.slice(0, 2).join(", ") +
                (refused.length > 2 ? " and " + (refused.length - 2) + " more" : "");
            return {
                needed: refused.length > 0,
                detail: renderer + ": " + (refused.length > 0
                    ? refused.length + " of " + SHAPES.length + " shapes refused: " + named +
                        " -> lifting them"
                    : "all " + SHAPES.length + " shapes compile and link -> the game's bytes")
            };
        } catch (e) {
            report("shader arrays", e);
            /* Nothing could be measured: answer "not accepted", because lifting is the side that
             * compiles everywhere and the game's own spelling is exactly what this probe doubts. */
            return { needed: true, detail: "the probe failed: " + String(e).slice(0, 60) };
        }
    })();
    call(function (bridge) { return bridge.setShaderArrays(!shaderArrays.needed, shaderArrays.detail); });

    /* The shader self-test: what *this* device's front end does with the game's declarations, asked
     * from the page instead of guessed from another device (FINDINGS §22.10). The app serves the same
     * file in every spelling it could serve (`ShaderVariants`, over a reserved path), each one is
     * compiled here, and the verdicts go into the record - one tap, and the phone says which spelling
     * it takes. On the reporting Mali driver that is the difference between guessing at candidate
     * edits and reading the answer off the device.
     *
     * It compiles on the engine's own context when there is one (the exact stack the engine's shaders
     * are compiled on) and on a throwaway context otherwise. Nothing is asserted: it is an instrument,
     * and a device that refuses everything still reports. */
    var SELF_TEST_INDEX = "ada-variants";
    var selfTestRunning = false;

    function shaderSelfTest() {
        if (selfTestRunning) return "running";
        if (!HAS_BRIDGE) return "no bridge";
        selfTestRunning = true;
        reportDiag("shader self-test", "asking on " +
            (window.g && window.g.gl ? "the engine's context" : "a throwaway context"));
        fetch("/game/" + SELF_TEST_INDEX)
            .then(function (response) { return response.text(); })
            .then(function (body) { return runSelfTest(selfTestNames(body)); })
            .then(function (summary) {
                reportDiag("shader self-test", summary);
                call(function (bridge) { return bridge.shaderSelfTestDone(summary); });
            })
            .catch(function (e) {
                var message = "failed: " + String(e);
                report("shader self-test", e);
                reportDiag("shader self-test", message);
                call(function (bridge) { return bridge.shaderSelfTestDone(message); });
            })
            .then(function () { selfTestRunning = false; });
        return "started";
    }

    function selfTestNames(body) {
        return String(body).split("\n")
            .map(function (line) { return line.replace(/^\s+|\s+$/g, ""); })
            .filter(function (line) { return line.length > 0; });
    }

    function runSelfTest(names) {
        var engine = window.g && window.g.gl;
        var gl = engine || document.createElement("canvas").getContext("webgl2",
            { antialias: false, powerPreference: "high-performance" });
        if (!gl) return Promise.resolve("no webgl2 context to ask");
        var refused = [];
        var unavailable = [];
        var sequence = Promise.resolve();
        names.forEach(function (name) {
            sequence = sequence.then(function () {
                /* A case the app cannot build is served as nothing (its bytes did not match), and the
                 * record already carries the app's own line saying so: it must not be reported as a
                 * driver refusal of an empty shader. A missing response must not stop the rest. */
                return fetch("/game/" + SELF_TEST_INDEX + "/" + encodeURIComponent(name))
                    .then(function (response) {
                        return response.text().then(function (text) {
                            return { ok: response.ok !== false, text: text };
                        });
                    })
                    .catch(function () { return { ok: false, text: "" }; })
                    .then(function (served) {
                        if (!served.ok || served.text.length === 0) {
                            unavailable.push(name);
                            reportDiag("shader self-test",
                                name + " unavailable (the app serves no text for it)");
                            return;
                        }
                        var result = compileOnce(gl, served.text);
                        if (!result.ok) refused.push(name);
                        reportDiag("shader self-test", name + " compile=" + (result.ok ? 1 : 0) +
                            " log=" + (result.log || "-"));
                    });
            });
        });
        return sequence.then(function () {
            if (!engine) {
                var lose = gl.getExtension("WEBGL_lose_context");
                if (lose) lose.loseContext();
            }
            return names.length + " cases, " + (names.length - refused.length - unavailable.length) +
                " compile" +
                (refused.length ? ", refused=" + refused.join(",") : "") +
                (unavailable.length ? ", unavailable=" + unavailable.join(",") : "");
        });
    }

    function compileOnce(gl, source) {
        var shader = gl.createShader(gl.FRAGMENT_SHADER);
        gl.shaderSource(shader, source);
        gl.compileShader(shader);
        var ok = gl.getShaderParameter(shader, gl.COMPILE_STATUS) === true;
        var log = ok ? "" : firstLineOf(gl.getShaderInfoLog(shader));
        gl.deleteShader(shader);
        return { ok: ok, log: log };
    }

    /* The first non-empty line of a driver's log, bounded: a record line, not a transcript. */
    function firstLineOf(log) {
        var lines = String(log || "").split("\n");
        for (var i = 0; i < lines.length; i++) {
            if (lines[i].replace(/^\s+|\s+$/g, "").length > 0) return lines[i].slice(0, 140);
        }
        return "";
    }

    /* The app asks the page to run it when the Troubleshoot panel's own button is tapped. */
    window.adaShaderSelfTest = shaderSelfTest;

    /* The engine reports its real trouble through the console - a shader that will not compile, a
     * resource that will not load, an atlas that ran out of slots - and on a release build none of
     * that reaches a log anyone can read. Forward the first line of each (bounded, and the app
     * collapses repeats) so a device-only failure is in the diagnostics panel.
     *
     * The *group titles* are forwarded too, and they are what a shader failure is actually made of:
     * the engine logs `console.groupCollapsed("Shader Errors: <path>")`, then one group per
     * `ERROR: 0:<line>: <message>` the driver returned, and only the offending *source* lines under
     * them go through `console.error` (bundle, `ShaderResource.loadShader`). A record that took
     * error/warn alone therefore kept a line of shader code and lost both the file it came from and
     * the compiler's own message - exactly the two things a report about "the water shader will not
     * compile" needs. `console.log` is deliberately not forwarded: the engine dumps the whole shader
     * source through it (~1 200 lines), which would spend the budget before the next useful line. */
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
                if (HAS_BRIDGE) {
                    window.PortBridge.reportDiag("console." + level, text.slice(0, 300));
                }
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

    /* Node's contract, and it is not a harmless superset: `readdir` yields *names*, and dirents only
     * when `withFileTypes` asks for them. Handing back objects to a caller that concatenates the entry
     * onto a path gives `assets/extension/[object Object]`, and the loader dies on the ENOENT it then
     * throws - which is exactly how CrossCode stalled on its loading bar (measured 2026-10-04, and the
     * page's own debugger named it: node-webkit.html:191). */
    function readdirRaw(p, opts) {
        var list = parseJson(call(function (b) { return b.fsReaddir(String(p)); }, null));
        if (!list) return null;
        var dirents = !!(opts && typeof opts === "object" && opts.withFileTypes);
        var out = [];
        for (var i = 0; i < list.length; i++) {
            out.push(dirents ? makeDirent(list[i].n, list[i].d) : String(list[i].n));
        }
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
        readdirSync: function (p, opts) {
            var l = readdirRaw(p, opts);
            if (!l) throw enoent(p);
            return l;
        },
        readdir: function (p, opts, cb) {
            if (typeof opts === "function") { cb = opts; opts = null; }
            if (typeof cb !== "function") return;
            var l = readdirRaw(p, opts);
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
        App: { argv: [], quit: quitApp, dataPath: "/saves", clearCache: noop },
        Window: {
            get: function () {
                return {
                    close: quitApp, on: noop, once: noop, off: noop, show: noop, hide: noop,
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
        applyFpsLimit();
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

    /* ---- frame-rate limit -------------------------------------------------
     * The side menu's frame-rate switch and the rate slider it reveals (20/30/40/45/60, see FpsLimit).
     * The engine drives *everything* it draws from `requestAnimationFrame` - `System.run` re-arms
     * itself at the end of every frame, and the GUI's own canvases do the same - so gating rAF gates
     * the frames, and half the frames is half the GPU time (the port is GPU-bound, see README
     * "Performance"). Nothing in the game's files is touched and the engine's clock is not fooled:
     * `Timer.step` reads `performance.now()` itself, so the game logic keeps advancing by the real
     * elapsed time and still runs at its 60 Hz fixed step; only the presents drop.
     *
     * The display can only present on a vsync, so the gate serves one frame on the first vsync at
     * least one frame interval after the last one served, and every callback that arrived in the
     * meantime rides that frame (the engine re-requests inside its own callback, so a batch is
     * normally one). The interval is shortened by a tenth of itself because a 60 Hz vsync is 16.7 ms
     * and 30 fps is 33.3 ms: without the tolerance the deadline falls 0.3 ms after the second vsync
     * and every frame slips to the third, i.e. 20 fps instead of 30. The same tenth keeps the other
     * rates honest - 20 fps is a 50 ms interval against 60 Hz vsyncs at 50 ms, and 60 fps is 16.7 ms
     * against 15 ms - and it is what a rate that is not a whole division of the panel's refresh
     * resolves to: 45 fps (22.2 ms, 20 ms shortened) is 40 on a 120 Hz panel and 30 on a 60 Hz one,
     * because the next vsync up is what is available. */
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
            /* Only the ids this gate handed out exist while it is on; with it off the calls go
             * straight through to the browser, exactly as they did before. */
            if (!fpsLimited && typeof cancel === "function") return cancel.call(window, id);
            fpsCancelled[id] = true;
        };
    })();

    /** Read on the same once-per-frame poll as the overlays; the change lands on the next frame. */
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
     * Both are driven by the Kotlin side and both are applied on the gamepad poll, which the engine
     * performs exactly once per frame (System.runInner -> g_input.update -> updateGamepads). */

    var portCanvas = null;

    /* The render canvas, cached: re-queried only when the engine replaces it. Shared by the picture
     * alignment and the stats readout, which reports its drawing buffer as the resolution. */
    function gameCanvas() {
        if (!portCanvas || !portCanvas.isConnected) portCanvas = document.querySelector(".xgCanvas");
        return portCanvas;
    }

    /* Picture alignment. The element's *layout box* is moved - `top` on the absolutely positioned
     * canvas, whose inset `0` + `margin: auto` leaves it centred until we pin it - and the picture
     * stays centred inside that box. This is not interchangeable with moving the picture alone
     * (`object-position`, or a CSS transform), because the engine's own mouse mapping reads the
     * element's layout position and assumes the picture is centred inside it:
     *
     *   Input.getMouseCoordsC:  mouse = pageX/Y - sum(offsetLeft/offsetTop up the offsetParent chain)
     *   DISPLAY_SCALE.*.transformMouse:  game = (mouse - delta/2) * 1/scale1/scale
     *
     * with `deltaY = canvas.clientHeight - scale1 * canvas.height` and `screenSizeY =
     * canvas.clientHeight` (the element's client box, not the window). `deltaY/2` is the letterbox
     * the engine assumes sits *above* the picture; the `object-position` this used to write moved
     * the picture by exactly that much without moving `offsetTop`, so every click landed deltaY/2
     * away from what it hit - a whole screenful at 640x360 on a phone (measured: the picture is
     * ~600 px tall in a ~2340 px window, so the miss is ~870 px). Moving the box instead keeps both
     * sides agreeing: the picture's visual top and its layout top differ by the same deltaY/2 the
     * engine subtracts, in **both** display scales (with `sharp-pixels` on the element is pinned to
     * an integer multiple of the buffer, so deltaY is 0 and the same formula degenerates to pinning
     * the box to the alignment).
     *
     * `top` is rounded to whole pixels so it agrees with `offsetTop`, which reads as an integer.
     * "" restores the stylesheet's centred default. */
    var alignApplied = null;
    var alignElement = null;
    var alignTop = null;

    function applyViewAlign() {
        var mode = call(function (b) { return b.getViewAlign(); }, "center");
        var canvas = gameCanvas();
        if (!canvas) return;
        var top = null;
        if (mode === "top" || mode === "bottom") top = alignTopFor(canvas, mode);
        if (mode === alignApplied && canvas === alignElement && top === alignTop) return;
        /* Measurements are all zero before the first layout pass; retry on the next frame. */
        if (mode !== "center" && top === null) return;
        alignApplied = mode;
        alignElement = canvas;
        alignTop = top;
        canvas.style.top = top === null ? "" : top;
        canvas.style.bottom = top === null ? "" : "auto";
    }

    /* The element's `top` that puts the picture where the menu asked for it. `top = desired -
     * deltaY/2`, where `desired` is the picture's screen position within the window and `deltaY/2`
     * is the offset between the picture and the box the engine measures from. */
    function alignTopFor(canvas, mode) {
        var parent = canvas.parentElement;
        var boxH = parent ? parent.clientHeight : 0;
        if (!boxH) boxH = window.innerHeight;
        var bufW = canvas.width, bufH = canvas.height;
        var clientW = canvas.clientWidth, clientH = canvas.clientHeight;
        if (!boxH || !bufW || !bufH || !clientW || !clientH) return null;
        var scale1 = Math.min(clientW / bufW, clientH / bufH);
        var pictureH = scale1 * bufH;
        var deltaY = clientH - pictureH;
        var desired = mode === "top" ? 0 : boxH - pictureH;
        return Math.round(desired - deltaY / 2) + "px";
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

    /* ---- engine-side diagnostics ------------------------------------------
     * A device-only boot failure leaves the boot screen's bar frozen at a low percentage with no
     * exception anywhere, so the port reports the engine's own state instead of guessing:
     *
     *  - The bar is `1 - pending/total` over `g_resource.bootTracker` (see
     *    TriRenderer.renderBooting and LoadTracker.progress), and it only completes when *every*
     *    staged resource finishes. `watchBoot` samples that tracker on a timer - not on a frame,
     *    because during BOOTING the engine renders but does not run the game loop - and reports
     *    what is still pending once the number stops moving.
     *  - Only one load path in this engine can stay unfinished forever *without* an error: a sound's
     *    `decodeAudioData(data, ok, fail)` (bundle, SoundRes.onLoad) finalizes the resource from the
     *    success callback alone. If neither callback is delivered - a device whose WebView cannot
     *    start its audio thread, say - the resource never finalizes, the tracker never completes and
     *    the bar freezes while the app itself stays alive. `trackDecode` counts the callbacks so the
     *    report can tell that apart from an asset read that simply never returns. */
    var DECODE = { started: 0, done: 0, failed: 0 };

    (function trackDecode() {
        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC || !AC.prototype.decodeAudioData) return;
        var original = AC.prototype.decodeAudioData;
        AC.prototype.decodeAudioData = function (data, ok, fail) {
            DECODE.started++;
            if (typeof ok !== "function" && typeof fail !== "function") {
                var promise = original.call(this, data);
                if (promise && typeof promise.then === "function") {
                    promise.then(function () { DECODE.done++; }, function () { DECODE.failed++; });
                }
                return promise;
            }
            return original.call(this, data,
                function (buffer) { DECODE.done++; if (typeof ok === "function") ok(buffer); },
                function (error) { DECODE.failed++; if (typeof fail === "function") fail(error); });
        };
    })();

    function reportDiag(kind, text) {
        try {
            if (HAS_BRIDGE) window.PortBridge.reportDiag(kind, text);
        } catch (e) { /* nothing left to do */ }
    }

    function idOf(resource) {
        try { return String(resource.identification()); } catch (e) { return "?"; }
    }

    function audioState() {
        try {
            var audio = window.g && window.g.audio;
            return audio && audio.context ? String(audio.context.state) : "?";
        } catch (e) { return "?"; }
    }

    /* The thresholds, in ms. `__adaBootDiag` is the test hook (android/tools/test-shim-diagnostics.mjs)
     * that lets the diagnostic logic be exercised in a second instead of eight. */
    var diagConf = window.__adaBootDiag || {};
    var BOOT_STALL_MS = diagConf.stallMs || 8000;
    var BOOT_STALL_REPORT_MS = diagConf.reportEveryMs || 15000;
    var BOOT_POLL_MS = diagConf.pollMs || 500;
    var boot = {
        lastProgress: -1, lastChangeAt: 0, reportedAt: -1e9, done: false,
        factsSent: false, factsHadGl: false, startedAt: now(),
    };

    function pendingSummary(pending) {
        var classes = {};
        for (var i = 0; i < pending.length; i++) {
            var s = pending[i];
            var cls = s.indexOf("media/audio/") === 0 ? "audio"
                : s.indexOf("SpriteSheet[") === 0 ? "spritesheet"
                    : s.indexOf("SHADER") === 0 ? "shader"
                        : s.indexOf("Effect[") === 0 ? "effect"
                            : s.indexOf("Figure[") === 0 ? "figure"
                                : s.indexOf("FrameAnim[") === 0 ? "frameAnim" : "data";
            classes[cls] = (classes[cls] || 0) + 1;
        }
        var parts = [];
        for (var key in classes) parts.push(key + "=" + classes[key]);
        return parts.join(" ");
    }

    /* The device facts the app cannot see from Kotlin: which GL backend the WebView picked (if any),
     * and what the AudioContext did at boot. Sent once the engine exists - and deliberately *not*
     * gated on `g.gl`, because "the page never got a GL context" is itself the answer on a device
     * that cannot run this game; a later line adds the backend's name if it appears afterwards. */
    var FACTS_WAIT_MS = 5000;

    function glDescription(gl) {
        if (!gl) return "none";
        var info = gl.getExtension("WEBGL_debug_renderer_info");
        return info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.VERSION);
    }

    /* What the engine is actually rendering at: the Resolution option times SCREEN (640x360). That is
     * the number behind "the game is too slow" and behind how large the dither's dots look, and it is
     * the one device fact the record did not carry. */
    function resolutionText() {
        var canvas = gameCanvas();
        return (canvas && canvas.width) ? canvas.width + "x" + canvas.height : "?";
    }

    /* How big the picture actually is on the screen. The engine owns the canvas layout (`sharp-pixels`
     * decides between `object-fit: contain` and a CSS size pinned to an integer multiple of the buffer),
     * so "640x360 does not fill the screen like the other resolutions" is answered by these numbers and
     * nothing else: the window in CSS pixels, and the canvas box the engine gave it. */
    function windowText() {
        var w = window.innerWidth || 0, h = window.innerHeight || 0;
        return w + "x" + h + "@" + (window.devicePixelRatio || 1);
    }

    function canvasBoxText() {
        var canvas = gameCanvas();
        if (!canvas || !canvas.getBoundingClientRect) return "?";
        var r = canvas.getBoundingClientRect();
        return Math.round(r.width) + "x" + Math.round(r.height);
    }

    function sendFacts(gl) {
        var glText = glDescription(gl);
        var parts = ["webgl2=" + !!window.WebGL2RenderingContext, "gl=" + glText,
            /* Which GL driver this page got is the whole story of the Mali reports: the native driver
             * refuses the game's array declarations, ANGLE's own front end never sees them. An app
             * cannot set that choice (it is a privileged `Settings.Global` entry), so the record must
             * at least say which one it got. */
            "driver=" + (/angle/i.test(glText) ? "ANGLE" : "native"),
            "uniforms=" + glLimits.vertexUniforms + "/" + glLimits.fragmentUniforms,
            "varyings=" + glLimits.varyingVectors,
            "resolution=" + resolutionText(),
            "window=" + windowText(),
            "canvas=" + canvasBoxText(),
            "audio=" + audioState()];
        reportDiag("facts", parts.join("; "));
    }

    function maybeSendFacts() {
        var gl = window.g && window.g.gl;
        var engineUp = !!(window.g && window.g.resource);
        if (!engineUp && now() - boot.startedAt < FACTS_WAIT_MS) return;
        if (!boot.factsSent) {
            boot.factsSent = true;
            boot.factsHadGl = !!gl;
            sendFacts(gl);
            return;
        }
        if (gl && !boot.factsHadGl) {
            boot.factsHadGl = true;
            sendFacts(gl);
        }
    }

    function watchBoot() {
        if (boot.done) return;
        /* First, and before any engine state is required: a page that never got an engine at all is
         * exactly the case the facts are for. */
        maybeSendFacts();
        var resource = window.g && window.g.resource;
        var tracker = resource && resource.bootTracker;
        if (!tracker) return;
        var t = now();
        var progress = tracker.progress;
        if (progress !== boot.lastProgress) {
            boot.lastProgress = progress;
            boot.lastChangeAt = t;
        }
        if (tracker.state === 2 || progress >= 0.999) {
            boot.done = true;
            reportDiag("boot", "complete in " + Math.round(t - boot.startedAt) + "ms, " +
                tracker.maxResource + " resources; resolution=" + resolutionText() +
                "; audio=" + audioState() +
                "; decodes started=" + DECODE.started + " done=" + DECODE.done + " failed=" + DECODE.failed);
            return;
        }
        var still = t - boot.lastChangeAt;
        if (still < BOOT_STALL_MS || t - boot.reportedAt < BOOT_STALL_REPORT_MS) return;
        boot.reportedAt = t;
        var pending = (tracker.resources || []).map(idOf);
        reportDiag("boot stall", "no progress for " + Math.round(still) + "ms at " +
            (progress * 100).toFixed(1) + "% of " + tracker.maxResource + " resources; pending " +
            pending.length + " (" + pendingSummary(pending) + "); audio ctx=" + audioState() +
            "; decodes started=" + DECODE.started + " done=" + DECODE.done + " failed=" + DECODE.failed +
            "; first pending: " + pending.slice(0, 12).join(", "));
    }

    setInterval(watchBoot, BOOT_POLL_MS);

    /* ---- error surfacing -------------------------------------------------
     * A device-only failure is otherwise a black screen: forward everything to
     * logcat (tag RfPort) through the bridge. */
    window.addEventListener("error", function (e) {
        try {
            /* A subresource that will not load fires on the element, not on window. */
            var resource = e && e.target;
            if (resource && resource !== window && (resource.src || resource.href)) {
                report("resource", (resource.tagName || "?") + " " + (resource.src || resource.href) + " failed");
                return;
            }
            if (e && e.message) {
                /* A shader the engine's compiler refused, paired with the port's own decision about that
                 * file. On the 0.7.2 report (issue #5, Mali-G76) the probe accepted the declarations and
                 * the engine then refused three shaders, and nothing in the record connected the two: the
                 * per-file lift lines only exist when the lift was attempted at all. This line says which
                 * side of the decision that file was on, so the next report of this class is readable. */
                var refused = /compiling the shader "([^"\s]+)/.exec(e.message);
                if (refused) {
                    report("shader refused", refused[1] + ": the engine's compiler refused it, and the port served " +
                        (shaderArrays && shaderArrays.needed
                            ? "the lifted bytes (the probe refused those declarations too)"
                            : "the game's own bytes (the probe accepted those declarations)") +
                        (shaderArrays && shaderArrays.detail ? " [" + shaderArrays.detail + "]" : ""));
                }
                if (!e.error && e.message === "Script error.") {
                    report("window.onerror", (e.filename || "?") + ":" + (e.lineno || 0) +
                        " masked script error (injected script or worker)");
                    return;
                }
                var stack = e.error && e.error.stack ? " | " + String(e.error.stack).split("\n")[0] : "";
                report("window.onerror", (e.filename || "?") + ":" + (e.lineno || 0) + " " + e.message + stack);
            }
        } catch (err) { /* ignore */ }
    }, true);
    window.addEventListener("unhandledrejection", function (e) {
        try {
            var r = e && e.reason;
            report("unhandledrejection", (r && r.message) ? r.message : String(r));
        } catch (err) { /* ignore */ }
    }, true);

    /* ---- the port's video reset ------------------------------------------
     * The engine keeps its device-local options as one JSON object under `xg_local_options`
     * (`const LOCAL_STORAGE_KEY`, bundle 24828) — `pixel-size`, the Resolution option, among them,
     * because that option is registered `local: true` — and reads it when it boots. A rung this
     * device cannot drive (2560x1440 on a phone is eight times the pixels of 960x540) makes the game
     * slow enough that its own Options menu is painful to reach, so the port can open the game with
     * `?adaResetVideo=1` (PortActivity.RESET_VIDEO_PARAM) and the stored value is dropped here, at
     * document start, before the engine reads it: the option then holds its own default, which the
     * phone ladder's default rung is. Only that one key goes and every other stored option survives;
     * the game's files are never touched (FINDINGS §19). */
    (function resetStoredResolution() {
        try {
            var search = (window.location && window.location.search) || "";
            if (String(search).indexOf("adaResetVideo") < 0) return;
            var store = window.localStorage;
            var raw = store && store.getItem("xg_local_options");
            if (!raw) {
                reportDiag("video", "asked to reset the Resolution; nothing stored");
                return;
            }
            var data = JSON.parse(raw);
            if (data && data["pixel-size"] !== undefined) {
                delete data["pixel-size"];
                store.setItem("xg_local_options", JSON.stringify(data));
                reportDiag("video", "stored Resolution dropped; the game boots at its default");
            } else {
                reportDiag("video", "asked to reset the Resolution; it was not stored");
            }
        } catch (e) {
            /* A malformed blob is the engine's to repair; never break the boot over this. */
            report("reset the stored Resolution", e);
        }
    })();

    report("shim", "loaded" + (HAS_BRIDGE ? "" : " (no PortBridge!)"));
})();
