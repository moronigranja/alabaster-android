(async () => {
  /* How the slot table could be carried instead of a `vec2[256]` uniform array: what the page's GL
     stack supports and whether the real gui vertex shader still links. Variants:
       as_served        the bytes the device is served right now (no surgery)
       unpacked_<n>     uniform vec2   u_texSlotCoords[n]        (the game's own form)
       packed_<n>       uniform vec4   u_texSlotCoords[n/2]      (what 0.4.2 ships at the floor)
       ubo_<form>       layout(std140) uniform block, one per table
       fetch_256        texelFetch from a (float) vertex texture
     Every variant keeps the accessor names (`ada_u_texSlotCoords(i)`), so only the declaration and
     the helper block differ; call sites in the shader body are untouched. */
  const out = { caps: {}, ext: {}, shaders: {} };
  const c = document.createElement('canvas').getContext('webgl2');
  if (!c) return JSON.stringify({ error: 'no webgl2' });
  const P = (n) => { try { return c.getParameter(c[n]); } catch (e) { return null; } };
  out.caps = {
    vertexUniforms: P('MAX_VERTEX_UNIFORM_VECTORS'),
    fragmentUniforms: P('MAX_FRAGMENT_UNIFORM_VECTORS'),
    varyingVectors: P('MAX_VARYING_VECTORS'),
    vertexUniformBlocks: P('MAX_VERTEX_UNIFORM_BLOCKS'),
    fragmentUniformBlocks: P('MAX_FRAGMENT_UNIFORM_BLOCKS'),
    combinedUniformBlocks: P('MAX_COMBINED_UNIFORM_BLOCKS'),
    uniformBlockSize: P('MAX_UNIFORM_BLOCK_SIZE'),
    uniformBufferBindings: P('MAX_UNIFORM_BUFFER_BINDINGS'),
    uniformBufferOffsetAlignment: P('UNIFORM_BUFFER_OFFSET_ALIGNMENT'),
    vertexTextureUnits: P('MAX_VERTEX_TEXTURE_IMAGE_UNITS'),
    fragmentTextureUnits: P('MAX_TEXTURE_IMAGE_UNITS'),
    combinedTextureUnits: P('MAX_COMBINED_TEXTURE_IMAGE_UNITS'),
    vertexAttribs: P('MAX_VERTEX_ATTRIBS')
  };
  for (const e of ['EXT_disjoint_timer_query_webgl2', 'OES_texture_float_linear', 'EXT_color_buffer_float',
                   'OES_draw_buffers_indexed', 'EXT_float_blend', 'WEBGL_debug_renderer_info']) {
    out.ext[e] = !!c.getExtension(e);
  }
  try { out.ext.renderer = c.getParameter(c.getExtension('WEBGL_debug_renderer_info').UNMASKED_RENDERER_WEBGL); } catch (e) { /* ignore */ }

  const tri = window.g && window.g.renderer && window.g.renderer.shaders && window.g.renderer.shaders.gui;
  if (!tri || !tri.vertexShader || !tri.fragmentShader) return JSON.stringify({ caps: out.caps, error: 'no gui shader' });
  const servedVert = tri.vertexShader.source;
  const frag = tri.fragmentShader.source;
  out.defines = tri.vertexShader.definitions || null;

  /* Remove the table declarations and accessor definitions line by line (the served packed source
     has the helpers inline, the game's own source has two plain declarations), keeping every other
     declaration - `u_samplerPixel` sits between the two tables and must survive. */
  const MARK = '@@ADA_DECLS@@';
  const stripTables = (src) => {
    const out = [];
    let marked = false;
    for (const line of src.split('\n')) {
      const l = line.trim();
      if (/^uniform\s+(vec2|vec4)\s+u_(tex|font)SlotCoords\[/.test(l) ||
          /^vec2\s+ada_u_(tex|font)SlotCoords\(/.test(l)) {
        if (!marked) { marked = true; out.push(MARK); }
        continue;
      }
      out.push(line);
    }
    return out.join('\n');
  };

  const decls = {
    unpacked: (n) => `uniform vec2 u_texSlotCoords[TEX_SLOT_COUNT];\n` +
      `uniform vec2 u_fontSlotCoords[TEX_SLOT_COUNT];\n` +
      `vec2 ada_u_texSlotCoords(uint i) { return u_texSlotCoords[i]; }\n` +
      `vec2 ada_u_texSlotCoords(int i) { return ada_u_texSlotCoords(uint(i)); }\n` +
      `vec2 ada_u_fontSlotCoords(uint i) { return u_fontSlotCoords[i]; }\n` +
      `vec2 ada_u_fontSlotCoords(int i) { return ada_u_fontSlotCoords(uint(i)); }`,
    packed: (n) => `uniform vec4 u_texSlotCoords[TEX_SLOT_COUNT / 2];\n` +
      `uniform vec4 u_fontSlotCoords[TEX_SLOT_COUNT / 2];\n` +
      `vec2 ada_u_texSlotCoords(uint i) { vec4 v = u_texSlotCoords[i >> 1u]; return ((i & 1u) == 0u) ? v.xy : v.zw; }\n` +
      `vec2 ada_u_texSlotCoords(int i) { return ada_u_texSlotCoords(uint(i)); }\n` +
      `vec2 ada_u_fontSlotCoords(uint i) { vec4 v = u_fontSlotCoords[i >> 1u]; return ((i & 1u) == 0u) ? v.xy : v.zw; }\n` +
      `vec2 ada_u_fontSlotCoords(int i) { return ada_u_fontSlotCoords(uint(i)); }`,
    ubo_unpacked: (n) => `layout(std140) uniform AdaSlots { vec2 u_texSlotCoords[TEX_SLOT_COUNT]; vec2 u_fontSlotCoords[TEX_SLOT_COUNT]; };\n` +
      `vec2 ada_u_texSlotCoords(uint i) { return u_texSlotCoords[i]; }\n` +
      `vec2 ada_u_texSlotCoords(int i) { return ada_u_texSlotCoords(uint(i)); }\n` +
      `vec2 ada_u_fontSlotCoords(uint i) { return u_fontSlotCoords[i]; }\n` +
      `vec2 ada_u_fontSlotCoords(int i) { return ada_u_fontSlotCoords(uint(i)); }`,
    ubo_packed: (n) => `layout(std140) uniform AdaSlots { vec4 u_texSlotCoords[TEX_SLOT_COUNT / 2]; vec4 u_fontSlotCoords[TEX_SLOT_COUNT / 2]; };\n` +
      `vec2 ada_u_texSlotCoords(uint i) { vec4 v = u_texSlotCoords[i >> 1u]; return ((i & 1u) == 0u) ? v.xy : v.zw; }\n` +
      `vec2 ada_u_texSlotCoords(int i) { return ada_u_texSlotCoords(uint(i)); }\n` +
      `vec2 ada_u_fontSlotCoords(uint i) { vec4 v = u_fontSlotCoords[i >> 1u]; return ((i & 1u) == 0u) ? v.xy : v.zw; }\n` +
      `vec2 ada_u_fontSlotCoords(int i) { return ada_u_fontSlotCoords(uint(i)); }`,
    fetch: (n) => `uniform highp sampler2D u_adaSlotTex;\nuniform highp sampler2D u_adaFontTex;\n` +
      `vec2 ada_u_texSlotCoords(uint i) { return texelFetch(u_adaSlotTex, ivec2(int(i), 0), 0).xy; }\n` +
      `vec2 ada_u_texSlotCoords(int i) { return ada_u_texSlotCoords(uint(i)); }\n` +
      `vec2 ada_u_fontSlotCoords(uint i) { return texelFetch(u_adaFontTex, ivec2(int(i), 0), 0).xy; }\n` +
      `vec2 ada_u_fontSlotCoords(int i) { return ada_u_fontSlotCoords(uint(i)); }`
  };

  const build = (kind, slots) => {
    let src = servedVert.replace(/#define TEX_SLOT_COUNT \d+/, '#define TEX_SLOT_COUNT ' + slots);
    src = stripTables(src).replace(MARK, decls[kind](slots) + '\n');
    return src;
  };

  const compile = (type, src) => {
    const s = c.createShader(type);
    c.shaderSource(s, src);
    c.compileShader(s);
    return {
      ok: !!c.getShaderParameter(s, c.COMPILE_STATUS),
      log: (c.getShaderInfoLog(s) || '').slice(0, 200),
      shader: s
    };
  };

  const link = (name, vert) => {
    const t0 = performance.now();
    const sanity = {
      markerLeft: vert.indexOf(MARK) !== -1,
      accessorCalls: (vert.match(/ada_u_(tex|font)SlotCoords\(/g) || []).length,
      samplers: (vert.match(/sampler2D/g) || []).length
    };
    const vc = compile(c.VERTEX_SHADER, vert);
    if (!vc.ok) return { name, sanity, ok: false, stage: 'vert', log: vc.log, ms: Math.round(performance.now() - t0) };
    const fc = compile(c.FRAGMENT_SHADER, frag);
    if (!fc.ok) return { name, ok: false, stage: 'frag', log: fc.log, ms: Math.round(performance.now() - t0) };
    const p = c.createProgram();
    c.attachShader(p, vc.shader);
    c.attachShader(p, fc.shader);
    c.linkProgram(p);
    const ok = !!c.getProgramParameter(p, c.LINK_STATUS);
    const r = {
      name, sanity, ok, stage: 'link',
      log: (c.getProgramInfoLog(p) || '').slice(0, 200),
      activeUniforms: c.getProgramParameter(p, c.ACTIVE_UNIFORMS),
      activeBlocks: c.getProgramParameter(p, c.ACTIVE_UNIFORM_BLOCKS),
      ms: Math.round(performance.now() - t0)
    };
    if (ok && r.activeBlocks) {
      r.blocks = [];
      for (let i = 0; i < r.activeBlocks; i++) {
        r.blocks.push({
          size: c.getActiveUniformBlockParameter(p, i, c.UNIFORM_BLOCK_DATA_SIZE),
          name: c.getActiveUniformBlockName(p, i)
        });
      }
      /* The engine sets uniforms by name through getUniformLocation: a block member is not reachable
         that way, which is what a UBO would cost the engine's uniform machinery. */
      r.slotUniformLocation = String(c.getUniformLocation(p, 'u_texSlotCoords[0]'));
    } else if (ok) {
      r.slotUniformLocation = String(c.getUniformLocation(p, 'u_texSlotCoords[0]'));
    }
    c.deleteProgram(p);
    c.deleteShader(vc.shader);
    c.deleteShader(fc.shader);
    return r;
  };

  out.shaders.as_served = link('as_served', servedVert);
  out.shaders.unpacked_192 = link('unpacked_192', build('unpacked', 192));
  out.shaders.unpacked_256 = link('unpacked_256', build('unpacked', 256));
  out.shaders.packed_192 = link('packed_192', build('packed', 192));
  out.shaders.packed_256 = link('packed_256', build('packed', 256));
  out.shaders.ubo_unpacked_256 = link('ubo_unpacked_256', build('ubo_unpacked', 256));
  out.shaders.ubo_packed_256 = link('ubo_packed_256', build('ubo_packed', 256));
  out.shaders.fetch_256 = link('fetch_256', build('fetch', 256));
  return JSON.stringify(out, null, 1);
})()
