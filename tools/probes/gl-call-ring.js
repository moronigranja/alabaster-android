(() => {
  /* Which GL call raises the pending INVALID_OPERATION? Wrap every method the engine can call on the
     context, keep the last calls in a ring, and pair a failed getError() with the calls before it. */
  if (window.__adaGlWrap) return JSON.stringify({ already: true, errs: window.__adaErrs });
  const gl = window.g.gl;
  const ring = [];
  window.__adaErrs = [];
  const proto = Object.getPrototypeOf(gl);
  const names = Object.getOwnPropertyNames(proto).filter((k) => {
    try { return typeof gl[k] === 'function' && k !== 'getError'; } catch (e) { return false; }
  });
  window.__adaGlWrap = names.length;
  for (const name of names) {
    const orig = gl[name];
    gl[name] = function () {
      try {
        if (ring.length > 40) ring.shift();
        const a = arguments;
        ring.push(name + (name.indexOf('uniform') === 0 && a.length > 1 && a[1] && a[1].length !== undefined ? '(' + a[1].length + ')' : ''));
      } catch (e) { /* ignore */ }
      return orig.apply(gl, arguments);
    };
  }
  const origGetError = gl.getError;
  window.__adaErrPoll = setInterval(() => {
    try {
      const e = origGetError.call(gl);
      if (e !== 0 && window.__adaErrs.length < 12) {
        window.__adaErrs.push({ error: e, calls: ring.slice(-25) });
      }
    } catch (err) { /* ignore */ }
  }, 2000);
  return JSON.stringify({ wrapped: names.length, note: 'read window.__adaErrs after ~20s of rendering' });
})()
