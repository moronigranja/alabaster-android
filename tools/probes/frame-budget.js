(async () => {
  /* Where does a frame's GL work come from? Counts the calls a frame makes through the live context
     - draws, program and state switches, and above all how many bytes of texture/buffer data cross
     the JS->GL bridge each second. The engine's sprite atlases are 4096x4096 2D canvases uploaded
     with texSubImage2D, so a per-frame atlas redraw would show up here as tens of MB/s and explain a
     GPU that is busy at a resolution it should not notice. Counts are device-independent: read them
     on the emulator, then compare with `gpubusy` on the phone.

     Every wrapper is a `function`, never an arrow: an arrow has no `arguments` object, and a throw
     inside a GL call reaches the engine as a broken resize path (measured - the error overlay came up
     at `TriTexture.updateFormat`). */
  if (window.__adaFrameBudget) return JSON.stringify({ already: true, report: window.__adaFrameBudget.report });
  const gl = window.g && window.g.gl;
  if (!gl) return JSON.stringify({ error: 'no gl' });
  const canvas = gl.canvas;
  const state = {
    started: performance.now(), frames: 0, lastFrame: 0, frameGaps: [], drawsIntoFrames: 0,
    counts: {}, bytes: {}, uploads: [], frameDraws: []
  };
  window.__adaFrameBudget = state;

  const bump = function (k, by) { state.counts[k] = (state.counts[k] || 0) + 1; if (by) state.bytes[k] = (state.bytes[k] || 0) + by; };
  const wrap = function (name, fn) {
    const orig = gl[name];
    if (typeof orig !== 'function') return;
    gl[name] = function () { fn.apply(null, arguments); return orig.apply(gl, arguments); };
  };

  for (const n of ['drawElements', 'drawArrays', 'drawElementsInstanced', 'drawArraysInstanced']) {
    wrap(n, function () { bump(n); state.frameDraws[state.frames] = (state.frameDraws[state.frames] || 0) + 1; });
  }
  for (const n of ['useProgram', 'bindTexture', 'blendFunc', 'blendFuncSeparate', 'viewport', 'scissor',
                   'clear', 'pixelStorei', 'bufferSubData', 'bufferData', 'generateMipmap', 'readPixels']) {
    wrap(n, function () { bump(n); });
  }
  wrap('texSubImage2D', function (target, level, x, y, w, h, f, t, data) {
    bump('texSubImage2D');
    const bytes = (data && data.byteLength) ? data.byteLength : (w * h * 4);
    bump('texSubImage2D.bytes', bytes);
    if (state.uploads.length < 400) state.uploads.push({ x, y, w, h, bytes, t: Math.round(performance.now() - state.started) });
  });
  wrap('texImage2D', function (target, level, internalFormat, width, height) {
    bump('texImage2D');
    if (typeof width === 'number' && typeof height === 'number') bump('texImage2D.bytes', width * height * 4);
  });

  /* The engine's own frame clock: rAF deltas give the JS side of the budget. */
  const raf = function (t) {
    if (state.frames) state.frameGaps.push(t - state.lastFrame);
    state.lastFrame = t;
    state.frames++;
    if (state.running) requestAnimationFrame(raf);
  };
  state.running = true;
  requestAnimationFrame(raf);

  await new Promise(function (res) { setTimeout(res, 20000); });
  state.running = false;
  const secs = (performance.now() - state.started) / 1000;
  const gaps = state.frameGaps.slice().sort(function (a, b) { return a - b; });
  const p = function (q) { return gaps.length ? Math.round(gaps[Math.min(gaps.length - 1, Math.floor(q * gaps.length))] * 10) / 10 : null; };
  const mb = function (b) { return Math.round((b || 0) / 104857.6) / 10; };
  const drawnFrames = state.frameDraws.filter(function (d) { return d; }).length;
  const report = {
    canvas: { width: canvas.width, height: canvas.height, cssW: canvas.clientWidth, cssH: canvas.clientHeight, dpr: window.devicePixelRatio },
    seconds: Math.round(secs),
    frames: state.frames,
    jsFrameMs: { p50: p(0.5), p90: p(0.9), p99: p(0.99) },
    framesWithDraws: drawnFrames,
    drawsPerDrawingFrame: drawnFrames ? Math.round(state.frameDraws.reduce(function (a, b) { return a + (b || 0); }, 0) / drawnFrames * 10) / 10 : 0,
    perSecond: Object.fromEntries(Object.entries(state.counts).map(function (kv) { return [kv[0], Math.round(kv[1] / secs)]; })),
    perSecondMB: Object.fromEntries(Object.entries(state.bytes).map(function (kv) { return [kv[0], mb(kv[1] / secs)]; })),
    biggestUploads: state.uploads.slice().sort(function (a, b) { return b.bytes - a.bytes; }).slice(0, 8)
  };
  state.report = report;
  return JSON.stringify(report, null, 1);
})()
