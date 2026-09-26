# Probes: reading the running engine over CDP

Six throwaway instruments used to find and verify the frozen boot of issue #1 (FINDINGS §10.9). They
are kept because re-deriving them costs an hour each time, and because the same instruments answer the
next engine question.

How they run: the **debug** build enables WebView devtools (`setWebContentsDebuggingEnabled(true)` is
gated on `BuildConfig.DEBUG`), so

```bash
adb -s <device> shell pidof io.github.moronigranja.alabasterdawn          # pid
adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
curl -s http://127.0.0.1:9222/json/list                                    # the page target
```

`cdp.py '<expression>'` or `cdp.py --file probe.js` evaluates in the game page, awaits a returned
promise, prints the value and leaves the raw reply in `/tmp/cdp-out.json`. A **release** build has no
devtools socket; there the app's own log (`adb logcat -s AdaPort:I`) is the channel.

| probe | what it answered |
|---|---|
| `gl-errors-and-uniforms.js` | the pending `gl.getError()` and the GL limits (`MAX_VERTEX_UNIFORM_VECTORS`, `varyings`, `MAX_UNIFORM_BLOCK_SIZE`) the WebView actually reports |
| `shader-budget.js` | link every real vertex+fragment pair in `g.renderer.shaders` with only the slot-table size substituted: which `TEX_SLOT_COUNT` each program needs (this is what proved `gui.vert` links at 96 and not at 128, and every one-table program at 192) |
| `atlas-sampler.js` | peak sheets per atlas group over time, plus the engine's `console.error`/`warn` lines, to see an atlas ceiling being hit while playing |
| `gl-call-ring.js` | wraps every method on the live GL context, keeps the last 40 calls in a ring and pairs a failed `getError()` with them: names the call that raises an error instead of guessing |
| `packed-table-pixel-test.js` | uploads real atlas slot coords into an unpacked (`uniform2fv` -> `vec2[S]`) and a packed (`uniform4fv` -> `vec4[S/2]`) program and reads the pixels back, to check the packing outside the engine |
| `cdp.py` | the client for all of the above |

Useful engine handles found while doing this (the bundle publishes almost nothing):

| handle | meaning |
|---|---|
| `g.resource.bootTracker` | `progress` (the loading bar's number), `maxResource`, `resources[]` with `identification()` |
| `g.renderer.shaders` | every `TriShader` by name (`gui`, `solid`, `cloud`, `weather-drops`, …); `.vertexShader.source` is the **served** (post-`#import`, post-rewrite) GLSL, `.definitions` is the engine's `#define` set |
| `g.renderer.groups.<group>.atlasses[i]` | the `TriTextureAtlas` instances: `sheets` (used), `texSlotCoords` (`2 * slots` floats), `uTexSlotCoords` (which uniform it feeds) |
| `g.gl` | the WebGL2 context the engine uses (wrappable to observe its calls) |
| `g.renderer.isPaused`, `.cineCamera`, `.viewType`, `.tags` | whether it is a cutscene, paused, etc. |

Dead end worth remembering: driving a *browser* (Chrome) on the emulator with a local host server and
`adb reverse` looked like the cheap way to compile test shaders, but Chrome's first-run/sign-in flow
blocks the page and the harness was never reached; the WebView devtools socket on the debug build is
the working route.
