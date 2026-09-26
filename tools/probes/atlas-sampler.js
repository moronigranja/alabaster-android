(() => {
  /* Sampler for the atlas question: how many sheets each atlas actually packs while the game draws.
     The port serves a smaller TEX_SLOT_COUNT on devices at the GLES3 uniform floor, so the game's own
     256-sheet ceiling is lower there; this measures the real usage against it. */
  if (window.__adaAtlasSampler) return JSON.stringify({ already: true, max: window.__adaAtlasMax });
  window.__adaAtlasMax = {};
  window.__adaConsoleErrs = window.__adaConsoleErrs || [];
  const orig = console.error;
  console.error = function () {
    try {
      const t = Array.prototype.map.call(arguments, (a) => (typeof a === 'string' ? a : String(a))).join(' ').slice(0, 160);
      if (window.__adaConsoleErrs.length < 200) window.__adaConsoleErrs.push(t);
    } catch (e) { /* ignore */ }
    return orig.apply(console, arguments);
  };
  const seen = new Set();
  const collect = (o, path, depth, out) => {
    if (!o || typeof o !== 'object' || depth > 4 || seen.has(o)) return;
    seen.add(o);
    if (Array.isArray(o.sheets) && Array.isArray(o.texSlotCoords)) {
      out.push({ path, sheets: o.sheets.filter(Boolean).length });
      return;
    }
    let keys = [];
    try { keys = Object.keys(o).slice(0, 40); } catch (e) { return; }
    for (const k of keys) {
      let v;
      try { v = o[k]; } catch (e) { continue; }
      if (v && typeof v === 'object') collect(v, path + '.' + k, depth + 1, out);
    }
  };
  window.__adaAtlasSampler = setInterval(() => {
    seen.clear();
    const out = [];
    collect(window.g && window.g.renderer, 'renderer', 0, out);
    for (const a of out) {
      const cur = window.__adaAtlasMax[a.path] || 0;
      if (a.sheets > cur) window.__adaAtlasMax[a.path] = a.sheets;
    }
  }, 100);
  return JSON.stringify({ installed: true });
})()
