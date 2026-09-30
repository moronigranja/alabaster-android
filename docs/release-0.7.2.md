# v0.7.2

One change, and it exists because a device falsified v0.7.1's gate: the probe that decides whether the
Mali array lift runs answered **"accepts"** on the one device that needed it, so the lift never ran and
its boot froze again. The probe now compiles the declarations the game's shaders are written with — one
program per shape, on a context made with the engine's own attributes, compiled **and linked** — and it
answers one-sidedly: any shape refused, no context, or a throw all mean "lift". What each shape did goes
into the record, so the next device that disagrees names it.

* **The gate is evidence, not a bit.** v0.7.1 asked one throwaway context to compile one synthetic
  shader of the same shapes and heard `true` from a driver that then refused the game's own bytes
  (`logs/mali/log alabaster 0.7.1.txt`: `shader arrays: … accepts` at +12.8 s, then `S0032 … 'vec3[5]'`
  / `'vec4[4]'` on the same three shaders at +19.6 s / +21.4 s and a boot stall at 97.2 %). The probe
  now compiles eight shapes copied from the files the rule names — the varying, the global
  `const vec2[12] DIRECTIONS = vec2[](…)`, the `vec2[4]` parameter, the array return type with its `out`
  parameter, the `vec4[4]`/`float[4]` parameter list, the sized local, the parameter sized by a macro,
  the sized constructor in an argument — **links** each program, and reports the renderer of the context
  it asked on:
  `shader arrays: Mali-G720 MC7: 2 of 8 shapes refused: in/out (0:62: S0032 …) -> lifting them`.
* **The answer fails safe.** Lifting is the side that compiles everywhere (identical active uniforms and
  attributes), and the game's own spelling is exactly what the probe doubts, so doubting wrongly costs a
  frozen boot while lifting wrongly costs nothing. A context that cannot be made, or a probe that
  throws, now means "lift" instead of "accept".
* **The reporter's own fix confirms the rule on the hardware** (his log is in `logs/mali/`; his patched
  shaders stay in the working tree, git-ignored as game files):
  he made the same three shaders compile by spelling the precision out — `highp vec4[4] …`,
  `mediump vec4 waves[4]`, `flat in mediump vec2[4] v_flowDirs;` — i.e. the sized spelling kept, the
  default precision replaced. That is the rule the lift answers by respelling instead, and it boots
  (`boot: complete in 8782ms, 1757 resources`). See `FINDINGS.md` §22.8.
* His log also carried a failure class the record had never seen: raising the **default** precision in
  one stage only leaves a shared uniform with two precisions, and this driver refuses the link
  (`Uniforms with the same name but different type/precision: u_waveHeight`). The port's own GUI
  precision raise touches `gui.vert` as well as every GUI fragment, so no GUI program is mismatched —
  checked, not assumed, and now recorded.

Verified, everything but the device: the shim harness at 54 checks, three of them this gate's own (eight
programs, every declaration present, a refusing compiler's line naming the shapes), and the real shim in
Chromium on a working stack — `ANGLE (AMD, … OpenGL ES 3.2): all 8 shapes compile and link -> the game's
bytes`, verdict "accepted", with the three real expanded shaders still compiling on a fresh context
afterwards (the probe does not poison what compiles next). Not measured on Mali: the next record from
that device is what closes issue #4, and if it still says `all 8 shapes compile and link` the remaining
difference is the *context* the probe asks on, with the next lever named in §22.8.
