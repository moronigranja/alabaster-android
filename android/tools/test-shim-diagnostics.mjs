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
 * The video reset drops the engine's stored Resolution option when the port opens the game with
 * `?adaResetVideo=1` (FINDINGS §19). The engine's option blob is a stub here, so what the shim does
 * to it — one key removed, a malformed blob reported rather than thrown — is asserted directly.
 *
 * The frame-rate switch wraps `requestAnimationFrame` (the engine's whole loop is a chain of those), so
 * the vsync queue is a drivable stub here and the served frames are counted against it.
 *
 * The console forwarding is what a device-only failure is read from: the engine logs a shader it
 * cannot compile as console groups (the file's path and the driver's message are the group titles),
 * so those titles are asserted here along with the rule that `console.log` stays unforwarded.
 *
 * The two document-start probes are asserted too, because each decides what the app serves: the slot
 * table's link (FINDINGS §17) and, for the array declarations a Mali front end refuses, whether the
 * page's own compiler accepts them (FINDINGS §22). The stubbed compiler answers each probe separately,
 * so a device that refuses the arrays is exercised as a device.
 *
 *   node android/tools/test-shim-diagnostics.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const shimPath = join(dirname(fileURLToPath(import.meta.url)), "..", "app", "src", "main", "assets", "ada-shim.js");
const source = readFileSync(shimPath, "utf8");

/** Installs the globals the shim expects and returns the recorder of what it reported. */
function bootShim({ stallMs = 30, reportEveryMs = 1000, pollMs = 5, canvas = null, viewAlign = "center", tablesLink = true, arraysCompile = true, search = "", store = {}, fpsLimit = 0, variants = {} } = {}) {
  const reports = [];
  const errors = [];
  const vertexUniforms = [];
  const shaderTables = [];
  const shaderArrays = [];
  const shaderSources = [];
  const selfTestDone = [];
  const resource = (name) => ({ identification: () => name });

  globalThis.window = globalThis;
  window.__adaBootDiag = { stallMs, reportEveryMs, pollMs };
  window.__reports = reports;
  /* Node 24 defines some of these as getter-only globals, so they are (re)defined rather than set. */
  const define = (name, value) =>
    Object.defineProperty(globalThis, name, { value, configurable: true, writable: true });
  /* The GL stub the throwaway canvas and the engine's own `g.gl` both hand out: the shim's slot/array
   * probes and the shader self-test compile through these calls, and each probe's question is
   * answered from the source it is handed. The link probe: `tablesLink: false` stands for a compiler
   * that cannot carry the game's own 256-slot table (SwiftShader, issue #1). The array probe and the
   * self-test: a compiler that does not carry a shader's declared default precision onto an array type
   * written `type[size]` refuses exactly those shaders - and nothing else. */
  const fakeGl = () => ({
    MAX_VERTEX_UNIFORM_VECTORS: 256,
    MAX_FRAGMENT_UNIFORM_VECTORS: 896,
    MAX_VARYING_VECTORS: 31,
    VERTEX_SHADER: 0x8b31,
    FRAGMENT_SHADER: 0x8b30,
    COMPILE_STATUS: 0x8b81,
    LINK_STATUS: 0x8b82,
    /* The probes read the renderer of their own context, the way the facts line reads the engine's. */
    getParameter: (which) => (which === 0x9246 ? "test-gl" : which),
    getExtension: (name) =>
      name === "WEBGL_debug_renderer_info" ? { UNMASKED_RENDERER_WEBGL: 0x9246 } : null,
    createShader: (type) => ({ type, source: "", ok: true, log: "" }),
    shaderSource: (shader, source) => {
      shader.source = source;
      shaderSources.push(source);
    },
    compileShader: (shader) => {
      const sized = /\b(vec[234]|float|int)\s*\[\s*[0-9A-Za-z_]/.test(shader.source);
      shader.ok = arraysCompile || !sized;
      shader.log = shader.ok
        ? ""
        : "0:62: S0032: no default precision defined for variable 'vec4[4]'";
    },
    getShaderParameter: (shader, which) => (which === 0x8b81 ? shader.ok : false),
    getShaderInfoLog: (shader) => shader.log,
    createProgram: () => ({ shaders: [], ok: true, log: "" }),
    attachShader: (program, shader) => program.shaders.push(shader),
    linkProgram: (program) => {
      const slots = program.shaders.some((s) => s.source.includes("u_slot"));
      program.ok = (tablesLink || !slots) && program.shaders.every((s) => s.ok);
      program.log = program.ok ? "" : "ERROR: link failed";
    },
    getProgramParameter: (program, which) => (which === 0x8b82 ? program.ok : 0),
    getProgramInfoLog: (program) => program.log,
    deleteShader: () => {},
    deleteProgram: () => {},
  });

  define("performance", performance);
  define("navigator", {});
  define("document", {
    querySelector: (selector) => (selector === ".xgCanvas" ? canvas : null),
    /* The shim probes a throwaway canvas for the device's vertex-uniform budget before the engine
     * asks for its first shader; without `getContext` it reports a `gl limits` error instead. */
    createElement: (tag) =>
      tag === "canvas"
        ? { style: {}, getContext: (kind) => (kind === "webgl2" ? fakeGl() : null) }
        : { style: {}, appendChild() {} },
    body: null,
  });
  define("addEventListener", () => {});
  define("removeEventListener", () => {});
  /* The frame-rate limit gate wraps `requestAnimationFrame`, so it is a real, drivable queue here:
   * `pump(t)` is one display vsync at timestamp `t`, which is exactly the shape the shim sees. */
  const rafQueue = [];
  let rafHandle = 1;
  define("requestAnimationFrame", (cb) => rafQueue.push({ handle: rafHandle, cb }) && rafHandle++);
  define("cancelAnimationFrame", (handle) => {
    const i = rafQueue.findIndex((f) => f.handle === handle);
    if (i >= 0) rafQueue.splice(i, 1);
  });
  const pump = (t) => {
    const batch = rafQueue.splice(0);
    for (const frame of batch) frame.cb(t);
  };
  /* The video reset (FINDINGS §19) reads `location.search` and rewrites the engine's stored options,
   * which is where a Resolution the device cannot drive otherwise stays. */
  define("location", { search });
  define("localStorage", {
    getItem: (key) => (Object.prototype.hasOwnProperty.call(store, key) ? store[key] : null),
    setItem: (key, value) => { store[key] = String(value); },
    removeItem: (key) => { delete store[key]; },
  });
  const limitFlag = { value: fpsLimit };
  /* The shader self-test asks the app for its cases over the reserved path `ada-variants`; the harness
   * serves whatever a case is given here, so both a refusing and an accepting verdict are exercised. */
  const bodies = Object.assign({}, variants);
  define("fetch", (url) => Promise.resolve({ text: () => Promise.resolve(bodies[url] ?? "") }));
  window.AdaBridge = {
    reportDiag: (kind, payload) => reports.push({ kind, payload }),
    reportJsError: (message) => errors.push(message),
    setVertexUniformVectors: (vectors) => vertexUniforms.push(vectors),
    setShaderTables: (oneTable, twoTables) => shaderTables.push({ oneTable, twoTables }),
    setShaderArrays: (compiled, detail) => shaderArrays.push({ compiled, detail }),
    shaderSelfTestDone: (summary) => selfTestDone.push(summary),
    getGamepadJson: () => "",
    getViewAlign: () => viewAlign,
    getStatsEnabled: () => false,
    getFpsLimit: () => limitFlag.value,
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
    gl: fakeGl(),
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
  return { reports, errors, vertexUniforms, shaderTables, shaderArrays, selfTestDone, shaderSources, tracker: window.g.resource.bootTracker, canvas, store, pump, limitFlag };
}

const results = [];
function check(name, condition, detail = "") {
  results.push({ name, ok: !!condition, detail });
  console.log(`${condition ? "ok  " : "FAIL"} ${name}${condition ? "" : "  <- " + detail}`);
}

async function main() {
  const { reports, errors, vertexUniforms, shaderTables, shaderArrays, shaderSources, tracker } = bootShim();

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
  check("the page reports whether the game's own table shapes link",
    shaderTables.length === 1 && shaderTables[0].oneTable === true && shaderTables[0].twoTables === true,
    JSON.stringify(shaderTables));
  /* The probe must ask the question the shaders ask: a 256-slot table with the reserve the real
   * shaders carry besides (48 vectors, or 32 for gui.vert's two), used so the compiler cannot drop
   * them. */
  const probe = shaderSources.filter((s) => s.includes("u_slot"));
  check("the probe compiles a 256-slot table with a 48-vector reserve",
    probe.length === 2 && probe.some((s) => s.includes("u_slot[256]") && s.includes("u_reserve[48]")),
    JSON.stringify(probe.map((s) => s.length)));
  check("the probe compiles gui.vert's shape: two tables, 32-vector reserve",
    probe.some((s) => s.includes("u_font[256]") && s.includes("u_reserve[32]")),
    JSON.stringify(probe.map((s) => s.length)));
  /* The second document-start question, for the driver quirk a Mali-G720 report named (FINDINGS §22):
   * an ES 3.0 front end that does not carry a shader's declared default precision onto an array type
   * written `type[size] name` / `type[size](…)`. The probe has to speak the game's own declarations -
   * one program per shape, on the engine's own kind of context, compiled **and linked** - because the
   * device that made this necessary answered a compile-only question with "accepts" while refusing
   * the game's own bytes (FINDINGS §22.5). */
  const arrayProbe = shaderSources.filter((s) => s.includes("out vec4 o;"));
  check("the array probe is one program per declaration the game's shaders write",
    arrayProbe.length === 9, JSON.stringify(arrayProbe.map((s) => s.split("\n")[2])));
  check("the array probe compiles every declaration the Mali report named",
    ["flat in vec2[4] v_flowDirs;",
      "const vec2[12] DIRECTIONS = vec2[](",
      "vec2 bilinear(vec2[4] v, float t0, float t1) {",
      "vec4[4] computeWaveFactors(out vec2 globalFlow, vec2 flowDir)",
      "float[4] amps, float[4] phases) {",
      "vec4[4] waves;",
      "vec3[COLOR_RAMP_COUNT] colors",
      "vec3[5](vec3(0.0)"].every((f) => arrayProbe.some((s) => s.includes(f))),
    JSON.stringify(arrayProbe.map((s) => s.length)));
  /* The post pass is *one* shape, not two: a parameter sized by a macro, called with a sized
   * constructor. Both halves can compile while the pair does not (FINDINGS §22.9). */
  check("the array probe compiles the post pass's own pair, not only its halves",
    arrayProbe.some((s) => s.includes("vec3[COLOR_RAMP_COUNT] colors") && s.includes("vec3[5](vec3(0.0)")),
    JSON.stringify(arrayProbe.filter((s) => s.includes("COLOR_RAMP_COUNT")).map((s) => s.length)));
  check("the page reports what its compiler did with the game's array declarations",
    shaderArrays.length === 1 && shaderArrays[0].compiled === true &&
      shaderArrays[0].detail === "test-gl: all 9 shapes compile and link -> the game's bytes",
    JSON.stringify(shaderArrays));

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

  /* A compiler that cannot carry the game's own table (SwiftShader, issue #1) answers false, and the
   * count-based path stays in charge. Last, because bootShim installs fresh globals. */
  const noTables = bootShim({ tablesLink: false });
  check("a device whose table does not link reports that",
    noTables.shaderTables.length === 1 && noTables.shaderTables[0].oneTable === false &&
    noTables.shaderTables[0].twoTables === false, JSON.stringify(noTables.shaderTables));
  /* A compiler that refuses the array declarations (the reporting Mali device) must be reported as
   * such, and the line must name what it refused: that answer is what makes the app serve the lifted
   * spelling, and the names are what the next device to disagree is read from. */
  const noArrays = bootShim({ arraysCompile: false });
  const refusal = noArrays.shaderArrays[0]?.detail || "";
  check("a compiler that refuses the game's array declarations reports that, with the shapes named",
    noArrays.shaderArrays.length === 1 && noArrays.shaderArrays[0].compiled === false &&
    refusal.startsWith("test-gl: 9 of 9 shapes refused: in/out (0:62: S0032") &&
    refusal.includes(", const-global (0:62: S0032") &&
    refusal.endsWith("and 7 more -> lifting them"),
    JSON.stringify(noArrays.shaderArrays));

  /* The shader self-test (FINDINGS §22.11): the page asks the app for every spelling it could serve,
   * compiles each on this device's own front end and puts the verdicts in the record. The harness's
   * compiler refuses sized array types, so one case compiles and one does not — the shape the
   * reporting Mali driver produces, read from the device instead of guessed at. */
  const selfTest = bootShim({
    arraysCompile: false,
    variants: {
      "/game/ada-variants": "post~original\npost~lifted\n",
      "/game/ada-variants/post~original": "vec3[5](",
      "/game/ada-variants/post~lifted": "vec3[](",
    },
  });
  window.adaShaderSelfTest();
  await new Promise((r) => setTimeout(r, 20));
  const selfLines = selfTest.reports.filter((r) => r.kind === "shader self-test").map((r) => r.payload);
  check("the shader self-test asks on the engine's own context",
    selfLines.some((l) => l.includes("asking on the engine's context")), JSON.stringify(selfLines));
  check("the shader self-test reports each case's verdict",
    selfLines.includes(
      "post~original compile=0 log=0:62: S0032: no default precision defined for variable 'vec4[4]'") &&
    selfLines.includes("post~lifted compile=1 log=-"), JSON.stringify(selfLines));
  check("the shader self-test summarises what this device takes, and the app is told",
    selfLines.includes("2 cases, 1 compile, refused=post~original") &&
    selfTest.selfTestDone[0] === "2 cases, 1 compile, refused=post~original",
    JSON.stringify([selfLines, selfTest.selfTestDone]));

  /* The facts carry the resolution the engine renders at — the Resolution option times SCREEN
   * (640x360). It is the number behind "the game is too slow" and behind how large the dither's dots
   * look, and the record did not carry it before. */
  /* A size no other boot here uses, and `some` rather than a single line: every earlier boot's shim
   * is still polling and reports through whatever bridge is current, so the newest recorder can carry
   * lines from older boots (their per-shim facts are once-only, but a boot given no canvas re-queries
   * every time). What is under test is the field, read from the element the shim is looking at. */
  const sized = bootShim({ canvas: canvasStub({ bufW: 1024, bufH: 576, clientW: 1024, clientH: 576, boxH: 576 }) });
  await new Promise((r) => setTimeout(r, 40));
  check("the facts carry the resolution the engine renders at",
    sized.reports.some((r) => r.kind === "facts" && r.payload.includes("resolution=1024x576")),
    JSON.stringify(sized.reports.filter((r) => r.kind === "facts").map((r) => r.payload)));

  /* The video reset (FINDINGS §19). The port opens the game with `?adaResetVideo=1` and the shim
   * drops the engine's stored Resolution before the engine reads it — the one value a phone user can
   * set that leaves the game too slow to reach its own Options menu. Every other option stays. */
  const options = { "pixel-size": 4, "language": "en_US", "skip-confirm": true };
  const reset = bootShim({ search: "?adaResetVideo=1", store: { xg_local_options: JSON.stringify(options) } });
  const after = JSON.parse(reset.store.xg_local_options);
  check("the video reset drops the stored Resolution and nothing else",
    after["pixel-size"] === undefined && after["language"] === "en_US" && after["skip-confirm"] === true,
    reset.store.xg_local_options);
  check("the video reset says what it did",
    reset.reports.some((r) => r.kind === "video" && /dropped/.test(r.payload)),
    JSON.stringify(reset.reports));

  const plain = bootShim({ store: { xg_local_options: JSON.stringify(options) } });
  check("without the parameter the stored options are untouched",
    plain.store.xg_local_options === JSON.stringify(options), plain.store.xg_local_options);
  check("and nothing is reported about it",
    !plain.reports.some((r) => r.kind === "video") && !plain.errors.some((e) => /Resolution/.test(e)),
    JSON.stringify([plain.reports, plain.errors]));

  const broken = bootShim({ search: "?adaResetVideo=1", store: { xg_local_options: "{not json" } });
  check("a malformed blob is reported, not thrown",
    broken.errors.some((e) => /reset the stored Resolution/.test(e)), JSON.stringify(broken.errors));
  check("and the blob is left exactly as it was",
    broken.store.xg_local_options === "{not json", broken.store.xg_local_options);

  const empty = bootShim({ search: "?adaResetVideo=1" });
  check("with nothing stored the reset reports that and breaks nothing",
    empty.reports.some((r) => r.kind === "video" && /nothing stored/.test(r.payload)) &&
    !empty.errors.some((e) => /reset/.test(e)) && !("xg_local_options" in empty.store),
    JSON.stringify([empty.reports, empty.errors]));

  /* The frame-rate switch and its slider. The engine drives its loop from `requestAnimationFrame`,
   * re-requesting at the end of every frame (System.run), so a chain of self-re-requesting callbacks
   * is exactly the shape the gate sees. Unlimited serves every vsync; a rate serves one frame on the
   * first vsync at least one (shortened) interval after the last, and rides the callbacks that arrived
   * in between on that one frame. */
  const unlimited = bootShim();
  let unlimitedFrames = 0;
  const unlimitedLoop = () => { unlimitedFrames++; window.requestAnimationFrame(unlimitedLoop); };
  window.requestAnimationFrame(unlimitedLoop);
  for (let i = 0; i < 120; i++) unlimited.pump(i * 8.33);
  check("with the limit off every display vsync is served",
    unlimitedFrames === 120, String(unlimitedFrames));

  const limited = bootShim({ fpsLimit: 30 });
  /* The engine polls the app once per frame; that poll is what carries the switch to the gate. */
  navigator.getGamepads();
  let limitedFrames = 0;
  const limitedLoop = () => { limitedFrames++; window.requestAnimationFrame(limitedLoop); };
  window.requestAnimationFrame(limitedLoop);
  for (let i = 0; i < 120; i++) limited.pump(i * 8.33); // 1 s of a 120 Hz display
  check("the limit serves about 30 frames in a second of 120 Hz vsyncs",
    limitedFrames >= 29 && limitedFrames <= 31, String(limitedFrames));
  check("the limit is reported once it is applied",
    limited.reports.some((r) => r.kind === "fps" && /limited to 30/.test(r.payload)),
    JSON.stringify(limited.reports.filter((r) => r.kind === "fps").map((r) => r.payload)));

  /* A 60 Hz device is the case the tolerance exists for: every second vsync is one frame, and
   * without it the 33.3 ms deadline lands just after that vsync and every frame slips to the third
   * one (20 fps). */
  const hz60 = bootShim({ fpsLimit: 30 });
  navigator.getGamepads();
  let frames60 = 0;
  const loop60 = () => { frames60++; window.requestAnimationFrame(loop60); };
  window.requestAnimationFrame(loop60);
  for (let i = 0; i < 60; i++) hz60.pump(i * 16.67); // 1 s of a 60 Hz display
  check("a 60 Hz display is served 30 frames a second, not 20",
    frames60 >= 29 && frames60 <= 30, String(frames60));

  /* The switch is live: the same shim follows the app's answer without a reload. */
  hz60.limitFlag.value = 0;
  navigator.getGamepads();
  const before = frames60;
  for (let i = 0; i < 60; i++) hz60.pump(1000 + i * 16.67);
  check("turning the limit off restores every vsync, live",
    frames60 - before === 60, String(frames60 - before));

  /* Every rate the slider offers, against a 120 Hz panel: 20 fps is one frame per 50 ms (a whole
   * sixth of the vsyncs), 45 is 40 - the interval is 22.2 ms and shortened to 20, and the next vsync
   * up is 25 ms - and 60 is every second vsync. */
  for (const [fps, expected] of [[20, 20], [45, 40], [60, 60]]) {
    const rate = bootShim({ fpsLimit: fps });
    navigator.getGamepads();
    let frames = 0;
    const loop = () => { frames++; window.requestAnimationFrame(loop); };
    window.requestAnimationFrame(loop);
    for (let i = 0; i < 120; i++) rate.pump(i * 8.33); // 1 s of a 120 Hz display
    check(`${fps} fps serves about ${expected} frames in that second`,
      frames >= expected - 1 && frames <= expected + 1, `${fps}: ${frames}`);
  }

  /* A 60 Hz panel cannot present 45: the next vsync up from a 22.2 ms interval is 33.3 ms, which is
   * 30 fps. The gate says so by counting, not by pretending. */
  const at45 = bootShim({ fpsLimit: 45 });
  navigator.getGamepads();
  let frames45 = 0;
  const loop45 = () => { frames45++; window.requestAnimationFrame(loop45); };
  window.requestAnimationFrame(loop45);
  for (let i = 0; i < 60; i++) at45.pump(i * 16.67); // 1 s of a 60 Hz display
  check("45 fps on a 60 Hz panel is 30, the next vsync up",
    frames45 >= 29 && frames45 <= 30, String(frames45));

  /* The rate is live, exactly as the switch is: the same shim follows the slider without a reload. */
  const live = bootShim({ fpsLimit: 0 });
  navigator.getGamepads();
  let liveFrames = 0;
  const liveLoop = () => { liveFrames++; window.requestAnimationFrame(liveLoop); };
  window.requestAnimationFrame(liveLoop);
  for (let i = 0; i < 60; i++) live.pump(i * 16.67);
  const beforeLive = liveFrames;
  live.limitFlag.value = 20;
  navigator.getGamepads();
  for (let i = 0; i < 60; i++) live.pump(1000 + i * 16.67);
  check("turning the cap on mid-run follows the slider's rate",
    liveFrames - beforeLive >= 19 && liveFrames - beforeLive <= 21, String(liveFrames - beforeLive));
  check("...and the record names the rate",
    live.reports.some((r) => r.kind === "fps" && /limited to 20/.test(r.payload)),
    JSON.stringify(live.reports.filter((r) => r.kind === "fps").map((r) => r.payload)));

  /* A shader that will not compile reaches the console as groups: the file's path, then one group per
   * driver message, with only the offending source lines under them as console.error (bundle,
   * ShaderResource.loadShader). The record must carry the first two - they are the only place the file
   * and the compiler's own words appear - and must not be flooded by the whole-source dump. */
  const shaderLog = bootShim();
  console.groupCollapsed("Shader Errors: data/shader/fragment/water-plane.frag");
  console.groupCollapsed("123: error: illegal use of reserved word 'sample'");
  console.error("121:     float sample = 1.0;");
  console.groupCollapsed("Shader Code");
  console.log("1200: ... the whole shader source would follow ...");
  console.groupEnd();
  console.groupEnd();
  console.groupEnd();
  const logged = shaderLog.reports.filter((r) => r.kind.indexOf("console.") === 0);
  check("a shader failure's group titles are reported, with the file and the driver's message",
    logged.some((r) => r.payload === "Shader Errors: data/shader/fragment/water-plane.frag") &&
    logged.some((r) => r.payload === "123: error: illegal use of reserved word 'sample'"),
    JSON.stringify(logged));
  check("the offending source line is still reported",
    logged.some((r) => r.kind === "console.error" && r.payload === "121:     float sample = 1.0;"),
    JSON.stringify(logged));
  check("console.log is not forwarded (the engine dumps the whole source through it)",
    !logged.some((r) => r.kind === "console.log"), JSON.stringify(logged));

  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
  process.exit(failed.length === 0 ? 0 : 1);
}

main();
