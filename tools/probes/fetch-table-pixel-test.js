(() => {
  /* The texture alternative, checked for the thing a link test cannot see: does a slot table that
     travels as a float vertex texture come back bit-exact? The engine's `texSlotCoords` is already
     interleaved `[x0, y0, x1, y1, ...]`, which is exactly RG32F's texel layout, so it uploads as-is;
     here every slot is compared inside the shader against the atlas's own float and the difference is
     rendered as 0/1 per axis. A non-zero pixel means the round trip cost precision. */
  const c = document.createElement('canvas');
  c.width = 8; c.height = 8;
  const gl = c.getContext('webgl2');
  const out = { ext: {}, samples: {}, allMatch: null, error: 0 };
  out.ext.OES_texture_float_linear = !!gl.getExtension('OES_texture_float_linear');
  out.ext.EXT_color_buffer_float = !!gl.getExtension('EXT_color_buffer_float');
  out.maxVertexTextureUnits = gl.getParameter(gl.MAX_VERTEX_TEXTURE_IMAGE_UNITS);

  const atlas = window.g.renderer.groups.map.atlasses[0];
  const data = new Float32Array(atlas.texSlotCoords);
  const slots = data.length / 2;
  out.slots = slots;
  /* `texSlotCoords` is a JS Array with holes: only the slots an atlas actually placed are filled.
     Test exactly those, and report the holes separately - the engine never indexes them. */
  const finite = (v) => typeof v === 'number' && isFinite(v);
  const used = [];
  for (let i = 0; i < slots; i++) if (finite(data[2 * i]) && finite(data[2 * i + 1])) used.push(i);
  out.filledSlots = used.length;
  out.holes = slots - used.length;
  out.usedSheets = atlas.sheets ? atlas.sheets.filter(Boolean).length : null;
  out.sampleValues = used.length ? [data[2 * used[0]], data[2 * used[0] + 1], data[2 * used[used.length - 1]], data[2 * used[used.length - 1] + 1]] : [];
  out.maxValue = used.reduce((m, i) => Math.max(m, data[2 * i], data[2 * i + 1]), 0);
  out.nonInteger = used.filter((i) => data[2 * i] !== Math.floor(data[2 * i]) || data[2 * i + 1] !== Math.floor(data[2 * i + 1])).length;

  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RG32F, slots, 1);
  gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, slots, 1, gl.RG, gl.FLOAT, data);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  out.texError = gl.getError();

  const build = (vsSrc, fsSrc) => {
    const p = gl.createProgram();
    const vs = gl.createShader(gl.VERTEX_SHADER);
    gl.shaderSource(vs, vsSrc); gl.compileShader(vs);
    const fs = gl.createShader(gl.FRAGMENT_SHADER);
    gl.shaderSource(fs, fsSrc); gl.compileShader(fs);
    if (!gl.getShaderParameter(fs, gl.COMPILE_STATUS)) return { error: gl.getShaderInfoLog(fs) };
    if (!gl.getShaderParameter(vs, gl.COMPILE_STATUS)) return { error: gl.getShaderInfoLog(vs) };
    gl.attachShader(p, vs); gl.attachShader(p, fs); gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) return { error: gl.getProgramInfoLog(p) };
    return { p };
  };

  /* The vertex stage does the fetch and passes it on; the fragment stage compares it with the same
     value read from a uniform array of one element. Both stages must see identical floats. */
  const vs = '#version 300 es\nprecision highp float;\n' +
    'uniform highp sampler2D u_tex;\n' +
    'uniform int u_i;\n' +
    'out vec2 v_fetched;\n' +
    'void main(){ v_fetched = texelFetch(u_tex, ivec2(u_i, 0), 0).xy; gl_Position = vec4(0.0, 0.0, 0.0, 1.0); gl_PointSize = 1.0; }\n';
  const fs = '#version 300 es\nprecision highp float;\n' +
    'uniform vec2 u_expected[1];\n' +
    'in vec2 v_fetched;\n' +
    'out vec4 o;\n' +
    'void main(){ vec2 d = step(vec2(0.0), abs(v_fetched - u_expected[0])); o = vec4(d, 0.0, 1.0); }\n';
  const prog = build(vs, fs);
  if (prog.error) return JSON.stringify({ error: prog.error, ext: out.ext });

  gl.useProgram(prog.p);
  gl.uniform1i(gl.getUniformLocation(prog.p, 'u_tex'), 0);
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, tex);
  for (const name of ['u_vertexStageFetch']) { /* marker only */ }
  const locI = gl.getUniformLocation(prog.p, 'u_i');
  const locE = gl.getUniformLocation(prog.p, 'u_expected');
  gl.viewport(0, 0, 4, 4);
  let bad = 0;
  for (const i of used) {
    gl.uniform1i(locI, i);
    gl.uniform2fv(locE, new Float32Array([data[2 * i], data[2 * i + 1]]));
    gl.drawArrays(gl.POINTS, 0, 1);
    const px = new Uint8Array(4);
    gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
    if (px[0] || px[1]) {
      bad++;
      if (bad <= 3) out.samples[i] = { expected: [data[2 * i], data[2 * i + 1]], pixel: Array.from(px) };
    }
  }
  out.testedSlots = used.length;
  out.mismatches = bad;
  out.allMatch = bad === 0;
  out.error = gl.getError();
  return JSON.stringify(out, null, 1);
})()
