(() => {
  const out = {};
  const gl = window.g.gl;
  const errors = [];
  for (let i = 0; i < 6; i++) errors.push(gl.getError());
  out.glErrors = errors;   // 0 = NO_ERROR, 1282 = INVALID_OPERATION, 1281 = INVALID_VALUE
  const tri = window.g.renderer.shaders['solid'];
  out.triKeys = Object.keys(tri);
  const uv = tri.uniformValues;
  out.uniformValuesType = typeof uv;
  if (uv) {
    const keys = Object.keys(uv);
    out.uniformValueKeys = keys.slice(0, 12);
    const e = uv['u_texSlotCoords'];
    if (e) {
      out.entry = {
        type: e.type, isArray: e.isArray,
        valueIsArray: Array.isArray(e.value), valueLength: e.value && e.value.length,
        valueHead: e.value && e.value.length ? Array.prototype.slice.call(e.value, 0, 6) : null,
        info: e.info ? { name: e.info.name, type: e.info.type, size: e.info.size } : null,
      };
    } else {
      out.entry = 'no u_texSlotCoords in uniformValues';
    }
    const atlas = window.g.renderer.groups.map.atlasses[0];
    out.atlas = { sheets: atlas.sheets.filter(Boolean).length, coordsLen: atlas.texSlotCoords.length, coordsHead: atlas.texSlotCoords.slice(0, 6), uniform: atlas.uTexSlotCoords, slots: atlas.texSlotCoords.length / 2 };
  }
  return JSON.stringify(out, null, 1);
})()
