# On-screen gamepad for the Alabaster Dawn Android port

## Context

The port (`android/`) currently requires a physical controller: `PortActivity` routes
gamepad-sourced `KeyEvent`/`MotionEvent`s into `Gamepad` → `GamepadState` → the volatile JSON string
that `AdaBridge.getGamepadJson()` hands to `assets/ada-shim.js`, which turns non-empty JSON into a
connected W3C-standard pad for the engine. Add an **on-screen pad** so the game is playable by touch
alone: a native overlay that feeds the *same* gamepad state, so the engine sees one standard pad and
needs no change (its `INPUT_DEVICES` enum has no touch device, so touch→gamepad is the only route
that reuses the engine's existing, already-verified input path).

End state: launching the game shows a translucent console-style pad (both sticks, D-pad, A/B/X/Y,
L1/R1, L2/R2, Select/Start) with a small always-tappable toggle; the left stick moves the game's
cursor, A activates the highlighted entry, touching the pad drives the same JSON the physical pad
drives, and the pad hides itself while a real controller is being used. Chosen layout: **full console
layout**; chosen visibility: **on by default, auto-hides 60 s after the last physical-controller
event**.

No change to `assets/ada-shim.js`, `AndroidManifest.xml`, or `app/build.gradle.kts`: the shim already
treats non-empty JSON as a connected pad, JUnit4 is already a `testImplementation`, and the overlay
uses only framework widgets.

## Approach

**Prior art examined: CrossAndroid** (`https://gitlab.com/Namnodorel/crossandroid`, the CrossCode
port on the same engine) — read from its source, which declares **no license**, so nothing is copied;
the findings below only decide choices. What it does:

* One always-connected virtual pad: `GamepadJsonBridge` builds a single JSON with
  `id: "Virtual Controller"`, `index: 0`, `connected: true`, `mapping: "standard"` and typed handles
  (`leftStick` = axes 0/1 + click 10, `rightStick` = 2/3 + 11, `leftCross` = 12/13/14/15,
  `rightCross` = **3, 0, 2, 1** = Y top / A bottom / X left / B right, bumpers 4/5, triggers 6/7,
  select 8, start 9, center 16). The page side converts that into W3C button objects — the same
  division of labour our shim already has. Its face-button ordering matches the diamond geometry this
  plan specifies; its `Axis` setter clamps to ±1, which this plan also does.
* Overlay = **one `View` per control, inflated from XML layout resources** (`layoutResId`),
  drawing a PNG per control (`app:image`), with three presets (Combat / Menu / Direct) and
  user-adjustable scaling/transparency. This plan deliberately uses **one Canvas-drawn View plus a
  pure-Kotlin geometry model** instead: the geometry math becomes JVM-testable (their maths needs a
  View), no art assets are needed (Alabaster Dawn's own glyphs are copyrighted anyway), and no
  `androidx.core` dependency is added (`our` dependency surface is exactly `androidx.webkit`).
* Pointer handling: each control view keeps all active pointers and recomputes a single "main
  pointer" per event, **capturing pointers that slide in from outside** it, and `onTouchEvent`
  returns `true` unconditionally (their comment: so it is notified when a pointer enters). This plan
  claims a pointer only when the DOWN landed inside the control and releases on leave — sliding off
  the stick towards a button must not fire that button; and it consumes the gesture only when a
  control (or the toggle) claimed it.
* Layouts block page touches by default (`open val allowWebViewInteraction = false`), which is the
  same conclusion this plan reaches for a page with no touch UI.
* Sticks emit `(x - center) / radius` with **no dead zone**; this plan keeps a small 12 % dead zone
  because a finger never returns to a mechanical centre the way a sprung stick does, and the engine's
  own `menuDeadzone`/movement dead zones were tuned for physical pads.
* Not adopted: its `fakeClick(button)` 40 ms synthetic press (only needed for programmatic taps, of
  which this plan has none), its per-control art assets, and its multi-preset layout switching (the
  engine drives menus with the same pad, so one layout covers both).

### S1 — `GamepadState`: merge an overlay source into the published JSON

File: `android/app/src/main/java/io/github/moronigranja/alabasterdawn/GamepadState.kt`.

Keep every existing member and the existing single-argument usage; add overlay state that the same
`publish()` merges, so there is exactly one JSON producer and the pure-JVM test surface stays here.

New members (exact signatures):

```kotlin
fun setOverlayEnabled(enabled: Boolean)          // clears overlay state when disabled, then publish()
fun overlayButton(button: Int, down: Boolean)    // index must be 0..BUTTONS-1; ignores others
fun overlayAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float)
```

- Add `private val overlayKeyDown = BooleanArray(BUTTONS)`, `private val overlayAxes = FloatArray(AXES)`,
  `private var overlayEnabled = false`.
- `setOverlayEnabled(true)`: set the flag, `publish()`. `setOverlayEnabled(false)`: set the flag false,
  fill `overlayKeyDown` with `false` and `overlayAxes` with `0f`, then `publish()` — a disabled overlay
  must never leave a stuck button or a displaced stick behind.
- `overlayButton`/`overlayAxes` write their slot and `publish()` (no-op when `!overlayEnabled`, so a
  stray event after a toggle cannot resurrect state).
- In `publish()`:
  - merge axes: for each of the 4 indices keep whichever of `axes[i]` / `overlayAxes[i]` has the
    larger absolute value (a neutral overlay must not mask a held physical stick, and vice versa);
  - merge buttons: `digital = keyDown[b] || hatPressed(b) || overlayKeyDown[b]`, `analog = trigger[b]`;
    `pressed = digital || analog > 0.5f`; `value = if (digital) 1f else analog`;
  - publish the merged axes/buttons into `payload`. `seen = true` moved out of `publish()` into the
    four hardware setters (`setKey`/`setAxes`/`setTrigger`/`setHat`): it means "a physical controller
    has been seen", so an overlay publish must not set it, or disabling the overlay would leave the
    shim reporting a pad that no hardware ever produced.
- `json()` becomes `if (seen || overlayEnabled) payload else ""` — with the overlay on, the engine must
  see a connected pad with all-neutral values (that is what switches it into its pad input device).
- Threading note to keep in the class doc: the overlay setters are called from the View's
  `onTouchEvent` on the main thread, i.e. the same thread as `Gamepad.onKey`/`onMotion`, so the
  existing "arrays are only touched by the input thread, the payload is published through a volatile
  field" contract is unchanged.

### S2 — `Gamepad`: expose the overlay

File: `.../Gamepad.kt` (`object Gamepad`). Add three delegating functions; change nothing else:

```kotlin
fun setOverlayEnabled(enabled: Boolean) = state.setOverlayEnabled(enabled)
fun overlayButton(index: Int, down: Boolean) = state.overlayButton(index, down)
fun overlayAxes(leftX: Float, leftY: Float, rightX: Float, rightY: Float) =
    state.overlayAxes(leftX, leftY, rightX, rightY)
```

`json()`, `onKey`, `onMotion` and the private `state` stay as they are (hardware input keeps priority
in the merge by construction, not by ordering).

### S3 — `OnScreenPadModel` (new, pure Kotlin, no Android types)

File: `.../OnScreenPadModel.kt`. Holds geometry + pointer assignment and exposes snapshots the View
diffs. Deliberately free of Android types so the layout and hit rules are JVM-testable, matching the
`GamepadState` convention.

```kotlin
class OnScreenPadModel {
    fun resize(width: Float, height: Float)
    val axes: FloatArray        // size 4, overlay axes in the GamepadState order
    val buttons: BooleanArray   // size 17, W3C standard indices
    fun down(id: Int, x: Float, y: Float): Boolean   // true when a control claimed this pointer
    fun move(id: Int, x: Float, y: Float): Boolean
    fun up(id: Int): Boolean
    fun toggleHit(x: Float, y: Float): Boolean
}
```

Controls and indices (W3C standard, the same set the engine's `GAMEPAD_MAPPING_STANDARD` registers):
left stick → axes 0/1, right stick → axes 2/3, A=0, B=1, X=2, Y=3, L1=4, R1=5, L2=6, R2=7, Select=8,
Start=9, D-pad up/down/left/right = 12/13/14/15, `PAD_HOME`=16. **Not exposed in v1**: stick clicks
(10/11 — a tap on the stick is indistinguishable from a drag start, and nothing verified binds them)
and `PAD_EXTRA` (17, which the shim's 17-button pool cannot carry). `PAD_HOME` is included because it
is one pill of extra screen budget and prior art exposes it as a first-class control (`Button(16)`),
so the cost of not having it if the game binds it is a player who cannot reach that action.

Geometry, computed in `resize(width, height)` — `u` is the touch unit, `pad` a fixed edge inset. The
original draft's numbers are corrected here (see "Corrections found while implementing" below); these
are the implemented values:

```
u   = min(width / 18f, min(width, height) * 0.115f)     // width/18 keeps every control disjoint (below)
pad = u * 0.35f
left stick   centre (1.5u + pad, height - 1.5u - pad)   outer radius 1.5u, knob 0.62u
D-pad        centre (4.8u + pad, height - 1.6u - pad)   4 keys, radius 0.6u, offset ±0.9u per axis
right stick  centre (width - 5.2u - pad, height - 1.7u - pad)   radii as the left stick
face buttons centre (width - 1.9u - pad, height - 1.9u - pad)   4 keys, radius 0.6u, offset ±0.9u,
                                                                 A bottom / B right / X left / Y top
L1  centre (4.8u + pad, height - 3.6u - pad)      L2  centre (4.8u + pad, height - 4.5u - pad)
R1  centre (width - 1.9u - pad, height - 3.85u - pad)  R2  centre (width - 1.9u - pad, height - 4.75u - pad)
    shoulders and triggers are pills 1.5u x 0.7u
Select centre (width / 2 - 1.0u, height - 0.9u - pad)  Start centre (width / 2 + 1.0u, ...)
    pills 1.2u x 0.62u
HOME         centre (width / 2, height - 1.6u - pad), pill 1.2u x 0.62u
toggle       centre (width / 2, 0.8u + pad), pill 2.6u x 0.7u
```

The model also exposes the geometry the View draws with: `fun shape(control): Shape` (a mutable
`x`/`y`/`radius`/`halfWidth`/`halfHeight`/`circular` holder, one per control, filled by `resize`),
`val toggle: Shape`, `fun buttonIndex(control): Int` (the W3C index a button/pill drives, -1 for the
sticks) and `val unit: Float` for stroke widths and text sizes. No allocation happens per frame or per
event: `resize` mutates the shapes in place.

Hit and state rules:

- A pointer's `down` claims the control whose hit area contains the point (circles: distance ≤ radius;
  pills: rounded-rect bounds test), returns true, and records `id → control`. A control holds **one**
  pointer: a second `down` on a control that already has one returns false and changes nothing, so two
  fingers can never fight over one axis. `move`/`up` ignore unknown pointer ids and return false.
- Stick controls: on `down`/`move`, `dx = x - cx`, `dy = y - cy`; `r = hypot(dx, dy)`;
  magnitude `m = min(max((r - 0.12f * 1.5f * u) / (1.5f * u - 0.12f * 1.5f * u), 0f), 1f)` (12 %
  radial dead zone, rescaled so full deflection is still 1), axis value = `m * dx / r` (and `dy / r`
  for Y), `0f` when `r == 0`. Left stick → axes 0/1, right stick → axes 2/3. Unlike a button, a stick
  does **not** release when the pointer leaves its ring (`m` is clamped to 1 instead): a flick
  overshoots the ring by nature, and dropping the stick there would make the character walk only as
  long as the thumb stays inside a circle. What leaves-ring-does-not-fire is still guaranteed by the
  claim rule — only a `down` can claim a control, so a pointer cannot be re-claimed by a second one.
- Button/pill controls: `buttons[index] = true` on claim, `false` on that pointer's `up` **or when the
  pointer moves outside the hit area** (so a drag off a button cannot leave it held).
- `up(id)`: clears the assignment; sticks return their axes to `0f`, buttons to `false`.
- Multiple pointers are independent (`id → control` map) — a stick and a button held at once both stay
  in effect; that is the core gameplay case.
- `toggleHit` tests the toggle pill only.

### S4 — `OnScreenPadView` (new): drawing and event translation

File: `.../OnScreenPadView.kt` — `class OnScreenPadView(context: Context) : View(context)`.

- Fields: `private val model = OnScreenPadModel()`, `private val lastAxes = FloatArray(4)`,
  `private val lastButtons = BooleanArray(17)`, `var padEnabled = true` (set from prefs by the
  Activity), `var controllerInUse = false`, plus callbacks set by the Activity:
  `var onToggle: ((Boolean) -> Unit)? = null`, `var onFirstTouch: (() -> Unit)? = null`.
- `isFocusable = false`, `isFocusableInTouchMode = false` — the WebView must keep focus
  (`document.hasFocus()` gates the engine's input).
- `onSizeChanged` → `model.resize(w.toFloat(), h.toFloat())`.
- **The overlay contribution rule**, applied in one place: the View calls
  `Gamepad.setOverlayEnabled(padEnabled && !controllerInUse)` whenever either input changes (pref load,
  toggle tap, controller activity, auto-show). When that expression is false the overlay state is
  cleared by `GamepadState`, so a finger held down while a controller appears cannot leave a stuck
  button. Two consequences the implementation has to handle: (a) `padEnabled` is a property whose
  setter re-applies the rule, so an Activity that loads the pref *after* constructing the view still
  never publishes state for a hidden pad; (b) when the rule turns *on*, the View republishes its whole
  state (`pushState(force = true)`) rather than diffing — `GamepadState` cleared its copy while the
  overlay was off, so a control held across a toggle would otherwise never re-press.
- `onTouchEvent`: annotate `@SuppressLint("ClickableViewAccessibility")` (it is a control surface, not a
  clickable widget; the release build runs `lintVitalRelease`). Return value per event: `true` when a
  control claimed a pointer or the toggle was hit; when the controls are not drawn
  (`!padEnabled || controllerInUse`) only the toggle can be claimed and a DOWN anywhere else returns
  `false`, so the WebView keeps receiving touches; when the controls are drawn, a DOWN on empty space
  returns `true` (the gesture is consumed — the page has no touch UI).
  - `ACTION_DOWN`/`ACTION_POINTER_DOWN`: if `model.toggleHit(x, y)` → flip `padEnabled`, call `onToggle`,
    `invalidate()`, consume that pointer; else `model.down(id, x, y)`; on `ACTION_DOWN` (the first
    pointer of a gesture) call `onFirstTouch`, which nudges the game's suspended `AudioContext` — the
    WebView's own touch listener no longer sees the gesture.
  - `ACTION_MOVE`: `model.move(id, x, y)` for every pointer in the event.
  - `ACTION_UP`/`ACTION_POINTER_UP`/`ACTION_CANCEL`: `model.up(id)` (CANCEL → all pointers).
  - After every event: push diffs to `Gamepad` — `Gamepad.overlayAxes(...)` when any axis changed by
    more than `1e-4f`, `Gamepad.overlayButton(i, down)` for each changed button — then `invalidate()`.
- Colour constants (one block at the top of the class, so they are tunable in one place), matching the
  launcher icon's palette: idle fill `0x66101820` (dark slate, 40 %), stroke `0x99E8DCC8` (alabaster,
  60 %), pressed fill `0xCC2F9E9B` (the port's teal), label text `0xFFE8DCC8`, stroke width `0.06u`.
- `onDraw` order: left stick, D-pad, right stick, face buttons, shoulders/triggers, Select/Start/HOME,
  then the toggle. Sticks: stroked outer circle + filled knob at the current deflection. D-pad keys:
  filled circle plus a small filled triangle pointing outward. Buttons/pills: rounded rect or circle
  with the label centred (`A B X Y L1 L2 R1 R2 SEL START HOME`, `Canvas.drawText`, `Paint.Align.CENTER`,
  text size `0.42u`). When `padEnabled && !controllerInUse` draw the controls; otherwise draw the
  toggle only, labelled `PAD` (pad off) / `HIDE` (pad on) — so the toggle is always reachable.
- `noteControllerActivity()`: sets `controllerInUse = true`, re-applies the overlay contribution rule
  (which now evaluates false, clearing any overlay state), `invalidate()`, and (re)posts
  `removeCallbacks(showAgain); postDelayed(showAgain, CONTROLLER_IDLE_MS)`; `showAgain` clears
  `controllerInUse`, re-applies the rule and invalidates. `onDetachedFromWindow` →
  `removeCallbacks(showAgain)`.
- `companion object { const val CONTROLLER_IDLE_MS = 60_000L }`.

### S5 — `PortActivity`: host the overlay, persist the toggle, report controller activity

File: `.../PortActivity.kt`.

- Add to the existing `companion object` next to `KEY_GAME`/`KEY_SAVES`:
  `private const val KEY_PAD = "on_screen_pad"`.
- New field `private var padView: OnScreenPadView? = null`.
- In `launchWebView()`, replace `setContentView(view)` with a `FrameLayout` host (framework class, no
  new dependency): `val frame = FrameLayout(this)`, `frame.addView(view)`, create the pad view with
  `padEnabled = prefs.getBoolean(KEY_PAD, true)`, wire `onToggle = { prefs.edit().putBoolean(KEY_PAD, it).apply() }`
  and `onFirstTouch = { resumeAudio() }`, then `frame.addView(padView)`, `setContentView(frame)`, and
  keep the existing `view.requestFocus()`. The pad is added **after** the WebView so it is the topmost
  child and gets touches first; the View itself enables/disables the overlay contribution (S4), so the
  Activity never calls `Gamepad.setOverlayEnabled`.
- In `onDestroy`, null the `padView` field after destroying the WebView; the pending show-again
  callback is dropped by the View's own `onDetachedFromWindow`, so no extra `stop()` API is needed.
- In `dispatchKeyEvent`/`dispatchGenericMotionEvent`, inside the existing `isGamepad(event.source)`
  branches, call `padView?.noteControllerActivity()` before feeding `Gamepad`.
- `rebuildWebView()` needs no change: it calls `launchWebView()`, which rebuilds the FrameLayout, the
  WebView and a fresh pad view (whose `controllerInUse` correctly starts false).

### S6 — Tests

File: `.../GamepadStateTest.kt` — keep all 7 existing tests unchanged (no existing signature is
removed). Add, in the same style (`org.json` assertions on `state.json()`):

- `an enabled overlay alone reports a connected pad`: `setOverlayEnabled(true)` → `json()` is non-empty
  and every button reads `pressed == false`, `value == 0`, all axes `0`.
- `an overlay press is a pressed button with value one`, and releasing clears it.
- `the overlay merges with a physical pad`: hardware axis `0.4` + overlay axis `-0.9` → published axis
  `-0.9`; overlay `0f` + hardware `0.4` → `0.4`; a hardware press and an overlay press of *different*
  buttons both read pressed.
- `a disabled overlay leaves no stuck state`: enable, press 0, move a stick, disable → `json()` is `""`
  (hardware never seen) and re-enabling republishes all-neutral values.

New file `.../OnScreenPadModelTest.kt` (JVM, JUnit4) — 16 tests, all of them passing:

- **the layout guard**: at 1920x1080, 2520x1080 and the narrow 800x600, every control and the toggle lie
  inside the viewport, and **no two controls intersect** (exact circle/circle, rect/rect and
  circle/rect tests, all 136 pairs). This is the test that caught the three geometry bugs listed under
  "Corrections found while implementing": anything that moves a cluster one row too far fails it.
- the left stick's ring is tangent to the screen edge, so full deflection towards the corner is
  reachable at all.
- left-stick centre is neutral; a full-deflection drag right publishes `1` on axis 0 and `0` on axis 1
  (and `-1` on axis 1 for a drag up); a 45° drag publishes `≈0.707` on both (normalised, not `±1`); a
  drag inside the 12 % dead zone publishes `0`; a thumb dragged past the ring keeps tracking, clamped
  to `1`; lifting centres the stick; the right stick drives axes 2/3.
- every exposed control maps to its W3C index: tapping the centre of each of the 15 button/pill
  controls sets exactly the expected index (12/13/14/15 and 0/1/2/3/4/6/5/7/8/9/16) and leaves every
  other index false — the same one-at-a-time discipline `GamepadStateTest` uses for the physical
  bounds bug.
- claiming a face button sets `buttons[0]`; `up` clears it; a drag outside its hit area clears it
  without `up`, and coming back presses it again.
- sliding from the stick onto a button does not press that button.
- a control holds one pointer: a second `down` on it returns false and changes nothing; after `up` it
  can be claimed again.
- two pointers: pointer 1 holds the left stick at full right while pointer 2 presses A → axis 0 stays
  `1` and `buttons[0]` is `true` at the same time; releasing pointer 1 leaves `buttons[0]` set.
- stray pointers (`move`/`up`/`down` on an id the model never claimed) change nothing.
- `toggleHit` is true only inside the toggle pill.

## Corrections found while implementing

The draft's geometry was self-inconsistent — its own layout claims ("every hit area lies inside the
viewport", "the two clusters cannot overlap") fail with its own numbers. Four changes, all in
`OnScreenPadModel`, each now covered by the layout test in S6:

1. **Left/right stick centres** `0.9u + pad` → `1.5u + pad` (`= 1.85u`). At `1.25u` from the edge with
   `radius = 1.5u` the ring is clipped by the screen, so a finger could reach only `m ≈ 0.81` towards
   that corner: a stick that cannot be pushed fully one way.
2. **The `u` width clamp** `width / 12` → `width / 18`. The layout needs `width >= 17.3u` to keep the
   right stick off Start (`width - 7.05u` vs `width / 2 + 1.6u`) and `>= 15.84u` to keep the D-pad off
   Select. At `width / 12` the clamp never bound where it was claimed to: at 800x600 the D-pad's right
   key (centre `x = 380`, `r = 44`) sat *inside* the right stick's circle (centre `430`, `r = 100`) —
   two controls fighting over one pointer.
3. **Both crosses** `radius 0.62–0.66u at ±0.75–0.8u` → `radius 0.6u at ±0.9u`. The draft's offsets were
   smaller than twice the radius, so D-pad keys — and face keys — overlapped each other and a tap
   between two of them was resolved by declaration order rather than by aim.
4. **R1/R2** `3.7u/4.6u` → `3.85u/4.75u`: the draft's R1 pill bottom (`h - 3.35u - pad`) was `0.01u`
   *below* the Y key's top (`h - 3.36u - pad`), i.e. tangent-to-overlapping at every size.

The on-device acceptance steps are unchanged in kind; only the numbers their coordinates are derived
from changed (steps 4 and 5).

## Status (2026-09-24)

Implemented (S1–S6), JVM-verified, and now **verified end-to-end on a device** — an Android 14
emulator (`ayvu34`, x86_64) because the Fold 7 dropped off USB, with the game served from the
workstation's own Steam copy.

What was run, and what it showed:

| Check | Evidence |
|---|---|
| 28 JVM tests | `:app:testDebugUnitTest` passes: 7 pre-existing `GamepadStateTest` cases unchanged, 5 added there, 16 in `OnScreenPadModelTest` |
| Builds | `:app:assembleDebug :app:assembleRelease`, `lintVitalRelease` included |
| Pad draws | screencaps: full console layout over the live title screen, portrait *and* landscape |
| The engine sees one standard pad | in-page CDP read: `navigator.getGamepads()` → one entry, `connected:true`, `mapping:"standard"`, axes/buttons all neutral, with **no hardware controller attached** |
| Stick → engine | holding the stick writes `axes[0] = 0.6214` at 1 `u` (the model's own predicted value, 4 dp) and `1.0` at full deflection; at full deflection the title menu steps (Options → Exit) |
| Buttons → engine | holding the on-screen D-pad-down writes `buttons[13].pressed = true` and steps the menu; A opens the highlighted entry (title menu → **Options** opened); B goes back |
| Toggle | tapping it hides the pad, `getGamepadJson()` becomes `""` and `getGamepads().length` drops to 0 (engine falls back to keyboard/mouse); tapping again brings it back |
| Auto-hide | one gamepad-sourced key (`adb shell input gamepad keyevent 96`) hides the controls within a second — only the `PAD` toggle stays drawn |
| Hardware path still drives the game | with the controls hidden, a held gamepad D-pad-up (`input gamepad keycombination -t 900 19 19`) steps the title menu (Exit → New Game), and `AdaPort: key KEYCODE_DPAD_UP -> pad` confirms the same `Gamepad.onKey` entry point |
| Auto-return | after `CONTROLLER_IDLE_MS` with no controller events the controls come back on their own |

Still to do, deliberately:

- **Multi-touch on a real device.** The model's two-pointer rules have JVM tests, and single-pointer
  routing is verified above per control, but the *View's* `ACTION_POINTER_DOWN`/`UP` translation is
  only exercised by two real fingers. The emulator cannot produce that: `input motionevent` has no
  multi-pointer mode, `keycombination` refuses a single keycode, CDP `Input.dispatchTouchEvent` never
  reaches the app's View, and writing the multi-touch protocol straight to `/dev/input/event2` (as
  root, both panel- and logical-sized coordinates, all four rotation transforms) is ignored by the
  InputReader in `mode - DIRECT`. Manual check on a phone, as the plan already said.
- **A real controller** (Switch Pro) for pairing/`dumpsys` verification; the injected
  `SOURCE_GAMEPAD` key above is the closest substitute available here.

Method notes worth keeping for the next run: the AVD presents a **2272x1080** window at x=128 (not
2400x1080 — `getevent`'s `deviceSize=[2400,1080]` and the WebView's own `innerWidth` both show the
content area), so pad coordinates are `view + (128, 0)`; `input [<source>] …` accepts `gamepad`, which
is how the controller-driven paths are injected; and `adb forward tcp:9222
localabstract:webview_devtools_remote_<pid>` gives a CDP page target for in-game ground truth
(`Runtime.evaluate` on `AdaBridge.getGamepadJson()` and `navigator.getGamepads()`).

Docs updated with the feature: `README.md` (feature bullet, requirements, limitations, verification
paragraph, a pad screenshot at `docs/on-screen-pad.png`, and the architecture list) and
`FINDINGS.md`'s repo map. **Not updated on purpose**: `docs/release-notes-0.1.md` describes 0.1 as it
shipped (its "no on-screen touch controls" bullet is true *for 0.1*), and the version is still
`versionCode 1` — the next release's notes need a pad bullet when one is cut with
`android/tools/release.sh`.

## Critical files & anchors

- `android/app/src/main/java/io/github/moronigranja/alabasterdawn/GamepadState.kt` — `publish()` and
  `json()` are the single JSON producer; the merge goes in there, not in a second producer.
- `.../OnScreenPadModel.kt` (new) — the geometry, hit rules and pointer bookkeeping; the only place the
  layout is defined, and the only part covered by unit tests.
- `.../OnScreenPadView.kt` (new) — drawing (`onDraw`) and the `MotionEvent` → model translation; the
  overlay contribution rule lives here (`applyOverlay`), never in the Activity.
- `.../PortActivity.kt` — `launchWebView()` ends in `setContentView(frame)` + `view.requestFocus()`
  (the `FrameLayout` host holds the WebView and then the pad); the `companion object` holds the prefs
  keys; the `isGamepad(event.source)` branches in `dispatchKeyEvent`/`dispatchGenericMotionEvent` are
  where controller activity is reported.
- `.../android/app/src/test/java/io/github/moronigranja/alabasterdawn/GamepadStateTest.kt` — the test
  conventions to copy (JUnit4, `org.json`, backticked names, no Android types).
- `android/app/src/main/assets/ada-shim.js` — read only, to confirm nothing is needed there:
  `pollGamepads()` treats non-empty `getGamepadJson()` as a connected standard pad and uses
  `GP_BUTTONS = 17`.
- `app/src/main/java/.../Gamepad.kt` — `onKey`/`onMotion` and `keyIndex` show the exact W3C indices the
  overlay must match (0..3 face, 4/5 shoulders, 6/7 triggers, 8 select, 9 start, 12..15 D-pad).

## Verification

Prerequisites: `export ANDROID_HOME=/home/moroni/Android/Sdk`; the **Fold 7** attached
(`adb devices` → `RQGL805FXRY`, package `io.github.moronigranja.alabasterdawn` already installed with
both folders picked, game files in `/sdcard/Download/AlabasterDawn` and saves in
`/sdcard/Download/Dsves`). The S22 (`R5CT119ZTMX`) also works but its folder picks are cleared.

1. **JVM tests** — `cd android && ./gradlew :app:testDebugUnitTest` → all tests pass, including the 7
   existing ones (the merge must not change hardware behaviour).
2. **Build + install** — `cd android && ./gradlew :app:assembleDebug :app:assembleRelease`
   (`assembleRelease` runs `lintVitalRelease`, which is what turns the `@SuppressLint` on
   `onTouchEvent` from an assumption into a verified decision) and
   `adb -s RQGL805FXRY install -r app/build/outputs/apk/debug/app-debug.apk`.
3. **Pad renders** — `adb -s RQGL805FXRY shell am start -n io.github.moronigranja.alabasterdawn/.PortActivity`,
   tap **START** (folders are already set), wait for the title screen, then
   `adb -s RQGL805FXRY exec-out screencap -p > /tmp/pad-title.png` → the title screen with the pad over
   it: two stick rings bottom-left/bottom-centre-right, D-pad, four labelled face buttons, L1/L2/R1/R2,
   SEL/START/HOME, and the `HIDE` toggle top-centre. `adb logcat -s AdaPort:*` shows no JS errors.
4. **The stick drives the game** — compute the coordinates from the model's own formulas rather than
   guessing: `adb -s <serial> shell wm size` gives `width`/`height`; then
   `u = min(width / 18, min(width, height) * 0.115)`, the left-stick centre is
   `(1.85u, height - 1.85u)`, and the swipe is from there to `(centre.x + u, centre.y)`:
   `adb -s <serial> shell input swipe <cx> <cy> <cx+u> <cy> 600` → the game's cursor appears and
   moves; a swipe back the other way drives it back. Prove it with `screencap` before and after (the
   cursor/highlight position differs).
5. **A activates a menu entry** — move the highlight onto **Options** with stick swipes, then tap A:
   the face-cluster centre is `(width - 2.25u, height - 2.25u)` and A is its bottom key, so
   `adb -s <serial> shell input tap <cx> <cy + 0.9u>` → the Options menu opens in the screencap.
   This is the same acceptance the physical pad passed.
6. **Toggle persists** — tap the toggle (its pill centre is `(width / 2, 1.15u)`): the pad disappears
   and the JSON goes empty (no hardware pad is connected, so `GamepadState.json()` is `""` and the
   engine falls back to keyboard/mouse). The observable is a swipe over where the stick was: nothing
   moves any more. Force-stop, relaunch → the pad is still off; tap the toggle again → it is back and
   the swipe moves the cursor again. (Toggle state is the `on_screen_pad` pref, default `true`.)
7. **Auto-hide with a real controller** — pair the Switch Pro and press any button → within a second
   the pad draws only its toggle (`controllerInUse`); stop touching the controller and wait 60 s →
   the pad returns on its own, verified with `screencap` before/after. Pressing a pad button again
   re-hides it immediately.
8. **Multi-touch (manual, device)** — `adb shell input` cannot produce two simultaneous pointers: with
   the game running, hold the left stick with one thumb (character keeps moving), tap A with the other
   (action fires while the character is still moving). The pointer-level guarantee is covered
   automatically by the two-pointer model test in step 1.
9. **Physical pad unchanged** — with the pad hidden by step 7, the Switch Pro still moves the cursor and
   activates menus; `GamepadStateTest` plus the unchanged `Gamepad.onKey`/`onMotion` paths are the
   regression guard.

## Assumptions & contingencies

- **Auto-hide window** is `OnScreenPadView.CONTROLLER_IDLE_MS = 60_000L`; change the constant to tune it.
- **The pad consumes the whole gesture while visible**, with no per-pointer pass-through to the WebView.
  Rationale: the engine has no touch input device and the page has no touch UI, so nothing is lost,
  while the WebView's own audio-nudge touch listener is replaced by the pad's `onFirstTouch` →
  `resumeAudio()`. If a future page-side touch UI appears, the pass-through has to be added per pointer.
- **Default on** (`prefs.getBoolean(KEY_PAD, true)`); a user who wants it gone taps the toggle once and
  the choice persists.
- **Narrow/portrait windows**: `u` is clamped by `width / 12f` so the two clusters cannot overlap; if
  that ever yields sub-40 px controls the correct answer is to run the game in landscape, not to add a
  second layout.
- **No version bump or release step here**: `android/tools/release.sh` already builds, verifies and
  publishes the signed APK when a release is wanted.
