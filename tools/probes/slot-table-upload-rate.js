(async () => {
  /* How often the atlas slot table actually crosses the JS->GL bridge while the game plays, and at
     what size. The engine's material path uploads a uniform array when its value is marked modified;
     if that happens per frame per program, a dirty-flag/cached table is worth real time, and if it
     happens a handful of times at boot it is not. Counts arrive through every `uniform*fv` call whose
     value is a slot table (`2 * TEX_SLOT_COUNT` floats, i.e. >= 300). */
  if (window.__adaSlotRate) return JSON.stringify({ already: true, report: window.__adaSlotRate.report });
  const gl = window.g && window.g.gl;
  if (!gl) return JSON.stringify({ error: 'no gl' });

  const state = { calls: [], draws: 0, frames: 0, sizeMin: Infinity, sizeMax: 0, all: {}, started: performance.now() };
  window.__adaSlotRate = state;
  const wrap = (obj, name, fn) => { const orig = obj[name]; obj[name] = function () { return fn(orig, this, arguments); }; };
  for (const name of ['uniform1fv', 'uniform2fv', 'uniform3fv', 'uniform4fv']) {
    wrap(gl, name, (orig, self, args) => {
      const v = args[1];
      const len = v && v.length !== undefined ? v.length : -1;
      state.all[name] = (state.all[name] || 0) + 1;
      state.all[name + ':len'] = state.all[name + ':len'] || {};
      const bucket = len === -1 ? 'scalar' : len;
      state.all[name + ':len'][bucket] = (state.all[name + ':len'][bucket] || 0) + 1;
      if (len >= 300) {
        state.calls.push({ name, len, t: performance.now() });
        state.sizeMin = Math.min(state.sizeMin, len);
        state.sizeMax = Math.max(state.sizeMax, len);
      }
      return orig.apply(self, args);
    });
  }
  for (const name of ['drawElements', 'drawArrays', 'drawElementsInstanced', 'drawArraysInstanced']) {
    if (typeof gl[name] === 'function') wrap(gl, name, (orig, self, args) => { state.draws++; return orig.apply(self, args); });
  }
  const raf = () => { state.frames++; if (state.running) requestAnimationFrame(raf); };
  state.running = true;
  requestAnimationFrame(raf);

  await new Promise((res) => setTimeout(res, 15000));
  state.running = false;
  const now = performance.now();
  const bySecond = [];
  for (const call of state.calls) {
    const s = Math.floor((call.t - state.started) / 1000);
    bySecond[s] = (bySecond[s] || 0) + 1;
  }
  const report = {
    gl: (() => { try { return gl.getParameter(gl.getExtension('WEBGL_debug_renderer_info').UNMASKED_RENDERER_WEBGL); } catch (e) { return null; } })(),
    seconds: Math.round((now - state.started) / 1000),
    slotTableUploads: state.calls.length,
    perSecond: bySecond,
    floatsPerUpload: state.calls.length ? { min: state.sizeMin, max: state.sizeMax } : null,
    draws: state.draws,
    frames: state.frames,
    fps: Math.round(state.frames / ((now - state.started) / 1000)),
    byCall: state.calls.reduce((a, c) => (a[c.name] = (a[c.name] || 0) + 1, a), {}),
    allUniformArrayCalls: state.all
  };
  state.report = report;
  return JSON.stringify(report, null, 1);
})()
