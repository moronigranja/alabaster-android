/* Does a uniform block or a vertex texture free the slot table when the default vertex-uniform budget
   is already spent? This is the question the game port's TEX_SLOT_COUNT ladder answers with a smaller
   table; the alternatives are what three.js (boneTexture) and Godot (skeleton_texture, texelFetch)
   ship. Every declaration here is *used* by the shader body - an unused one is dead-code-eliminated
   and the case would pass for the wrong reason.

   Budget arithmetic: a mat4 costs 4 vertex uniform vectors, a vec4/vec2 costs 1 (GLES3 packs by
   vec4). `fill80` declares exactly 80: 4 mat4 (16) + vec4[16] (16) + vec2[16] (16) + vec4[32] (32).
   So `fill80_plain192` = 272 vectors > the GLES3 minimum 256 and must fail, while `fill80_plain` (80)
   and `plain192_only` (192) must link. If block members do not count against the budget (the spec:
   they are backed by a buffer), `fill80_ubo256_sep` links too.

   Usage: `cdp.py --file slot-budget-cases.js` inside a page with a WebGL2 context, or open
   slot-budget-probe.html in a browser (the page shows the same JSON). */
(() => {
  const out = { caps: {}, cases: [] };
  const canvas = document.createElement('canvas');
  canvas.width = 8; canvas.height = 8;
  const c = canvas.getContext('webgl2');
  if (!c) return JSON.stringify({ error: 'no webgl2' });
  const P = (n) => { try { return c.getParameter(c[n]); } catch (e) { return null; } };
  const dbg = c.getExtension('WEBGL_debug_renderer_info');
  out.caps = {
    renderer: dbg ? (() => { try { return c.getParameter(dbg.UNMASKED_RENDERER_WEBGL); } catch (e) { return null; } })() : null,
    vertUniforms: P('MAX_VERTEX_UNIFORM_VECTORS'),
    fragUniforms: P('MAX_FRAGMENT_UNIFORM_VECTORS'),
    varyings: P('MAX_VARYING_VECTORS'),
    vertUniformBlocks: P('MAX_VERTEX_UNIFORM_BLOCKS'),
    uniformBlockSize: P('MAX_UNIFORM_BLOCK_SIZE'),
    uniformBufferBindings: P('MAX_UNIFORM_BUFFER_BINDINGS'),
    vertTextureUnits: P('MAX_VERTEX_TEXTURE_IMAGE_UNITS')
  };

  const VS = '#version 300 es\nprecision highp float;\n';
  const FS = '#version 300 es\nprecision mediump float;\nout vec4 o;\nvoid main(){ o = vec4(1.0); }\n';
  const HEAD = 'layout(location=0) in vec2 a_p;\nuniform int u_i;\n';
  /* Every FILL declaration feeds gl_Position, so none of them is eliminated. */
  const FILL = 'uniform mat4 u_m0;\nuniform mat4 u_m1;\nuniform mat4 u_m2;\nuniform mat4 u_m3;\n' +
    'uniform vec4 u_f[16];\nuniform vec2 u_p[16];\nuniform vec4 u_g[32];\n';
  const FILL_USE = 'vec4 base = u_m0 * u_m1 * u_m2 * u_m3 * vec4(a_p, 0.0, 1.0);\n' +
    'base.xy += u_f[u_i & 15].xy + u_p[u_i & 15] + u_g[u_i & 31].xy;\n';
  const PLAIN_USE = 'vec4 base = vec4(a_p, 0.0, 1.0);\n';

  const run = (name, tableDecl, tableUse, withFill) => {
    const fill = withFill === false ? '' : FILL;
    const vs = VS + HEAD + (tableDecl ? tableDecl + '\n' : '') + fill +
      'void main() {\n' + (fill ? FILL_USE : PLAIN_USE) + (tableUse || '') + 'gl_Position = base;\n}\n';
    const v = c.createShader(c.VERTEX_SHADER);
    c.shaderSource(v, vs); c.compileShader(v);
    const compiled = !!c.getShaderParameter(v, c.COMPILE_STATUS);
    const clog = (c.getShaderInfoLog(v) || '').replace(/\u0000/g, '').slice(0, 140);
    const f = c.createShader(c.FRAGMENT_SHADER);
    c.shaderSource(f, FS); c.compileShader(f);
    const p = c.createProgram();
    c.attachShader(p, v); c.attachShader(p, f);
    c.linkProgram(p);
    const ok = compiled && !!c.getProgramParameter(p, c.LINK_STATUS);
    const r = {
      name, compiled, ok,
      compileLog: clog,
      log: (c.getProgramInfoLog(p) || '').replace(/\u0000/g, '').slice(0, 160),
      activeUniforms: compiled ? c.getProgramParameter(p, c.ACTIVE_UNIFORMS) : null,
      activeBlocks: compiled ? c.getProgramParameter(p, c.ACTIVE_UNIFORM_BLOCKS) : null
    };
    if (r.ok && r.activeBlocks) {
      r.blockSizes = [];
      for (let i = 0; i < r.activeBlocks; i++) {
        r.blockSizes.push(c.getActiveUniformBlockParameter(p, i, c.UNIFORM_BLOCK_DATA_SIZE));
      }
    }
    out.cases.push(r);
  };

  run('fill80_plain', '', '');
  run('fill80_plain192', 'uniform vec2 u_slots[192];', 'base.xy += u_slots[u_i & 191];\n');
  run('fill80_plain256', 'uniform vec2 u_slots[256];', 'base.xy += u_slots[u_i & 255];\n');
  run('plain192_only', 'uniform vec2 u_slots[192];', 'base.xy += u_slots[u_i & 191];\n', false);
  run('fill80_ubo256_sep', 'layout(std140) uniform AdaSlots { vec2 u_slots[256]; };', 'base.xy += u_slots[u_i & 255];\n');
  run('fill80_ubo128x2', 'layout(std140) uniform AdaSlots { vec4 u_slots[128]; vec4 u_font[128]; };',
    'base.xy += u_slots[u_i & 127].xy + u_font[u_i & 127].zw;\n');
  run('fill80_fetch256', 'uniform highp sampler2D u_slots;', 'base.xy += texelFetch(u_slots, ivec2(u_i & 255, 0), 0).xy;\n');
  run('fetch256_only', 'uniform highp sampler2D u_slots;', 'base.xy += texelFetch(u_slots, ivec2(u_i & 255, 0), 0).xy;\n', false);

  const json = JSON.stringify(out, null, 1);
  window.__adaSlotBudget = json;
  return json;
})()
