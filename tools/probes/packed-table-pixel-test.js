(async () => {
  /* Is the packed table sound? Upload the atlas's own slot coords the way the engine does for each
     declaration - uniform2fv into `vec2[S]`, uniform4fv into `vec4[S/2]` - render the value the
     shader would read for a slot, and read the pixels back. Both must equal the atlas's pair. */
  const c = document.createElement('canvas');
  c.width = 8; c.height = 8;
  const gl = c.getContext('webgl2');
  const atlas = window.g.renderer.groups.map.atlasses[0];
  const data = Array.prototype.slice.call(atlas.texSlotCoords);   // 2 * slots floats
  const slots = data.length / 2;
  const S = slots;                                   // what the shader declares: 2S floats uploaded
  const out = { slots: S, floats: data.length, samples: {} };

  const build = (src) => {
    const p = gl.createProgram();
    const vs = gl.createShader(gl.VERTEX_SHADER);
    gl.shaderSource(vs, '#version 300 es\nprecision highp float;\nvoid main(){ gl_Position = vec4(0.0,0.0,0.0,1.0); gl_PointSize = 1.0; }');
    gl.compileShader(vs);
    const fs = gl.createShader(gl.FRAGMENT_SHADER);
    gl.shaderSource(fs, src);
    gl.compileShader(fs);
    if (!gl.getShaderParameter(fs, gl.COMPILE_STATUS)) return { error: gl.getShaderInfoLog(fs) };
    gl.attachShader(p, vs); gl.attachShader(p, fs); gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) return { error: gl.getProgramInfoLog(p) };
    return { p };
  };
  const fragFor = (decl, expr, indexUni) => '#version 300 es\nprecision highp float;\n' + decl +
    'uniform float u_i;\nout vec4 o;\nvoid main(){ int i = int(u_i); o = vec4(' + expr + ', 0.0, 1.0); }\n';

  const read = (prog, upload, i) => {
    gl.useProgram(prog.p);
    const locU = gl.getUniformLocation(prog.p, 'u_i');
    gl.uniform1f(locU, i);
    upload(prog);
    gl.viewport(0, 0, 4, 4);
    gl.drawArrays(gl.POINTS, 0, 1);
    const px = new Uint8Array(4);
    gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
    return px;
  };

  const unpacked = build(fragFor('uniform vec2 t[' + S + '];', 't[i]', 'u_i'));
  const packed = build(fragFor('uniform vec4 t[' + (S / 2) + '];\nvec2 ada(uint i){ vec4 v = t[i >> 1u]; return ((i & 1u) == 0u) ? v.xy : v.zw; }', 'ada(uint(i))', 'u_i'));
  if (unpacked.error || packed.error) return JSON.stringify({ unpacked: unpacked.error, packed: packed.error });

  const upload2 = (prog) => { gl.useProgram(prog.p); gl.uniform2fv(gl.getUniformLocation(prog.p, 't'), new Float32Array(data)); };
  const upload4 = (prog) => { gl.useProgram(prog.p); gl.uniform4fv(gl.getUniformLocation(prog.p, 't'), new Float32Array(data)); };

  for (const i of [0, 1, 2, 5, 37, S - 2, S - 1]) {
    const rows = { expectedXY: [data[2 * i], data[2 * i + 1]] };
    for (const [name, prog, up] of [['unpacked2fv', unpacked, upload2], ['packed4fv', packed, upload4]]) {
      gl.useProgram(prog.p);
      up(prog);
      rows[name] = Array.prototype.slice.call(read(prog, () => { }, i));
    }
    out.samples[i] = rows;
  }
  out.glError = gl.getError();
  return JSON.stringify(out, null, 1);
})()
