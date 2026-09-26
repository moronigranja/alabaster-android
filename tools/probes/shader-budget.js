(async () => {
  /* The engine's own shader sources, as the device's compiler sees them: which TEX_SLOT_COUNT each
     real program needs. Sources are post-#import (ShaderResource.source), so only the table size is
     substituted. Compile both stages, then link, exactly like TriShader does. */
  const out = { candidates: [192, 160, 128, 96, 64], slots: {}, failures: {} };
  const c = document.createElement('canvas').getContext('webgl2');
  if (!c) return 'no webgl2';
  const reg = window.g && window.g.renderer && window.g.renderer.shaders;
  if (!reg) return 'no shader registry';
  out.names = Object.keys(reg).slice(0, 60);
  const compile = (type, src) => {
    const s = c.createShader(type);
    c.shaderSource(s, src);
    c.compileShader(s);
    const ok = c.getShaderParameter(s, c.COMPILE_STATUS);
    const log = c.getShaderInfoLog(s) || '';
    return { ok, log, shader: s };
  };
  const tryLink = (tri, slots) => {
    const v = tri.vertexShader, f = tri.fragmentShader;
    if (!v || !v.source || !f || !f.source) return { skip: 'no source' };
    const vsrc = v.source.replace(/#define TEX_SLOT_COUNT \d+/, '#define TEX_SLOT_COUNT ' + slots);
    const vc = compile(c.VERTEX_SHADER, vsrc);
    if (!vc.ok) return { stage: 'vert', log: vc.log.slice(0, 200) };
    const fc = compile(c.FRAGMENT_SHADER, f.source);
    if (!fc.ok) return { stage: 'frag', log: fc.log.slice(0, 200) };
    const p = c.createProgram();
    c.attachShader(p, vc.shader);
    c.attachShader(p, fc.shader);
    c.linkProgram(p);
    const ok = c.getProgramParameter(p, c.LINK_STATUS);
    const log = c.getProgramInfoLog(p) || '';
    const active = c.getProgramParameter(p, c.ACTIVE_UNIFORMS);
    c.deleteProgram(p);
    c.deleteShader(vc.shader);
    c.deleteShader(fc.shader);
    return { ok: !!ok, log: log.slice(0, 220), activeUniforms: active };
  };
  for (const name of out.names) {
    const tri = reg[name];
    if (!tri || !tri.vertexShader) continue;
    let best = null;
    const detail = [];
    for (const n of out.candidates) {
      const r = tryLink(tri, n);
      detail.push({ n, ok: !!r.ok, stage: r.stage, log: r.log, active: r.activeUniforms });
      if (r.ok) { best = n; break; }
    }
    out.slots[name] = best;
    if (best !== out.candidates[0]) {
      out.failures[name] = detail.slice(-1)[0];
    }
  }
  out.vertexHasSlots = {};
  return JSON.stringify(out, null, 1);
})()
