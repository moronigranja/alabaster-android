(async () => {
  /* What a frame costs in *pixel work*, not in calls: every render pass the engine performs (one
     `clear` = one pass) times the viewport it clears/draws into, summed per frame, against the rung's
     own pixel count and the panel's. If the frame clears several times the rung's area, the cost that
     has to be multiplied when the rung goes up is the pass count, not the canvas - which is the whole
     question behind "1080p at 60 fps". */
  if (window.__adaPassCost) return JSON.stringify({ already: true, report: window.__adaPassCost.report });
  const gl = window.g && window.g.gl;
  if (!gl) return JSON.stringify({ error: 'no gl' });
  const state = {
    started: performance.now(), frames: 0, passes: 0, clearedPx: 0, draws: 0, drawVpPx: 0,
    bindFramebuffer: 0, bySize: {}, vpSizes: {}, fboNullClears: 0, gaps: [], last: 0
  };
  window.__adaPassCost = state;
  let vp = [0, 0];

  const wrap = function (name, fn) {
    const orig = gl[name];
    if (typeof orig !== 'function') return;
    gl[name] = function () { fn.apply(null, arguments); return orig.apply(gl, arguments); };
  };
  wrap('bindFramebuffer', function (target, fb) { state.bindFramebuffer++; state.currentFb = fb; });
  wrap('viewport', function (x, y, w, h) {
    vp = [w, h];
    const k = w + 'x' + h;
    state.vpSizes[k] = (state.vpSizes[k] || 0) + 1;
  });
  wrap('clear', function () {
    state.passes++;
    state.clearedPx += vp[0] * vp[1];
    const k = vp[0] + 'x' + vp[1] + (state.currentFb ? ' fbo' : ' screen');
    state.bySize[k] = (state.bySize[k] || 0) + 1;
    if (!state.currentFb) state.fboNullClears++;
  });
  for (const n of ['drawElements', 'drawArrays', 'drawElementsInstanced', 'drawArraysInstanced']) {
    wrap(n, function () { state.draws++; state.drawVpPx += vp[0] * vp[1]; });
  }
  const raf = function (t) {
    if (state.frames) state.gaps.push(t - state.last);
    state.last = t;
    state.frames++;
    if (state.running) requestAnimationFrame(raf);
  };
  state.running = true;
  requestAnimationFrame(raf);

  await new Promise(function (res) { setTimeout(res, 15000); });
  state.running = false;
  const secs = (performance.now() - state.started) / 1000;
  const perFrame = function (v) { return Math.round(v / state.frames * 100) / 100; };
  const mpix = function (v) { return Math.round(v / 1e6 * 100) / 100; };
  const report = {
    canvas: { width: gl.canvas.width, height: gl.canvas.height, cssW: gl.canvas.clientWidth, cssH: gl.canvas.clientHeight, dpr: window.devicePixelRatio },
    fps: Math.round(state.frames / secs * 10) / 10,
    perFrame: {
      clearsPasses: perFrame(state.passes),
      clearedMPix: mpix(state.clearedPx / state.frames),
      drawCalls: perFrame(state.draws),
      viewportSizesPerFrame: perFrame(Object.values(state.vpSizes).reduce(function (a, b) { return a + b; }, 0)),
      fullScreenClears: perFrame(state.fboNullClears)
    },
    clearedMPixPerSecond: mpix(state.clearedPx / secs),
    passAreas: state.bySize,
    viewports: state.vpSizes
  };
  state.report = report;
  return JSON.stringify(report, null, 1);
})()
