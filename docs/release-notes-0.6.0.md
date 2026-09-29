# Alabaster Dawn Android port v0.6.0 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are
in the [README](../README.md); the terse changelog is [docs/release-0.6.0.md](release-0.6.0.md).

This release is about the *other* two input devices. Until now the port played through a physical
controller or the on-screen pad; a mouse and a keyboard were not part of the design. Both now work,
they hide the overlay exactly like a controller does, and the side menu names the setting honestly.
A third fix — the one that made the side menu impossible to open on Android 13+ — was found while
verifying the first two.

## A mouse click was being eaten by the on-screen pad

`MotionEvent.isTouchEvent()` is `isFromSource(source, CLASS_POINTER) && action ∈ {DOWN, MOVE, UP,
POINTER_*, CANCEL, OUTSIDE}` (`frameworks/native/libs/input/Input.cpp`), so to Android's dispatcher a
mouse click **is** a touch event: `View.dispatchPointerEvent` sends it to `dispatchTouchEvent`, and a
`ViewGroup` tries its children in reverse draw order. The pad is drawn above the WebView and claimed
every DOWN while the controls were drawn — the click never reached the page. Hover
(`ACTION_HOVER_MOVE`) is *not* a touch event, which is why the game's cursor moved under a mouse but
clicking did nothing.

The pad now declines anything that is not a finger:

```kotlin
/** A mouse is a pointer device, not a finger: the pad never claims its events. */
fun isPointerInput(event: MotionEvent): Boolean =
    event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
```

`getToolType(0)` is the value Android derives from the source for exactly this question, and it is
what a `source`-bit test would get wrong: `SOURCE_MOUSE_RELATIVE` carries no `SOURCE_MOUSE` bit, while
its tool type is still `MOUSE`. `TOOL_TYPE_TRACKBALL` does not exist in `MotionEvent`, and `input`
sends no trackball motion events, so only the mouse is tested.

## A keyboard did not play, even though it reached the page

Hardware keys already reached the page: `DecorView.dispatchKeyEvent` → `PortActivity` → the focused
WebView → the renderer → `keydown` on `window`. A probe injected at document start proved that
(a `window` listener fired for every key), so the port's first job was only to *hide* the pad on a
key. It then turned out the game did not react to any of them.

The engine's input class matches its bindings on `event.code` alone
(`const code = event.code; … this.bindings.inputs.get(code)`), and Chromium derives `code` from the
**scan code**. An event with no scan code — `adb`'s injected keys, and any source that sends none —
reaches the page as `code: ""`, matches nothing, and plays nothing. The probe showed it directly:

```
kb=object code= prevented=false            # the Android key, as the page saw it
code=ArrowRight prevented=true             # the same key re-dispatched with its DOM name
```

`kb=object` matters: the engine's `initKeyboard()` calls `navigator.keyboard.getLayoutMap()` before
registering its listeners, so a WebView without the Keyboard API would have thrown there and had no
keyboard at all. It does have it, so only the empty `code` was missing.

The port therefore dispatches the DOM event the page should have received, for every key it can name,
and consumes the original so the page does not also get the empty-code copy:

```kotlin
if (event.scanCode == 0) {
    val keyEvent = domKeyEvent(event)
    if (keyEvent != null) {
        webView?.evaluateJavascript(keyEvent, null)
        return true
    }
}
return super.dispatchKeyEvent(event)
```

`domKeyEvent` covers letters (`KEYCODE_A..KEYCODE_Z` → `KeyA..KeyZ`), digits
(`KEYCODE_0..KEYCODE_9` → `Digit0..Digit9`), the four arrows, `Enter` / `NumpadEnter`, `Escape`,
`Space`, `Tab`, both `Shift`/`Control`/`Alt` pairs and `F1..F12`, and returns null for anything else
— which is then left to the WebView exactly as before, so the volume keys and the system's own keys
keep working. A key that *does* carry a scan code takes the `super` path untouched: the WebView's own
mapping is the right one there.

The log line the port writes for delivered keys keeps the scan code, because it is the field that
tells the two failures apart:

```
D AdaPort : key KEYCODE_DPAD_DOWN -> page (scan 0)
```

## Back opened and closed the side menu in one press

While verifying the menu (the plan's own check reads the overlay switch and its status line), Back did
nothing at all on the S22 Ultra. It was not a missing event: the record showed *both* handlers firing
for one press.

```
back: dispatcher (game)
back: key (game)
```

On Android 13+ the port registers an `OnBackInvokedCallback` (for the gesture) *and* handled the Back
key in `dispatchKeyEvent` (for every version) — and the framework delivers a Back key to the callback
as well, so one press ran `handleBack()` twice: open the menu, close the menu. The two paths are now
mutually exclusive by API level: from 33 up the callback owns Back and the key is only swallowed,
below 33 there is no callback and the key is all there is. This is the same shape as the report from
an AYN Odin 3, "the physical Back button does not open the menu", and it is what that report looks
like from the inside.

## The overlay switch now says what it does

The switch hid the pad for a controller only. It now hides it for a controller, a mouse **or** a
keyboard, so the row reads "Hide pad with external input" and the status line "External input:
active|idle". The stored preference key is deliberately still `hide_with_controller`, so an existing
install keeps its setting across the update. Internally `controllerInUse` → `externalInputInUse`,
`noteControllerActivity()` → `noteExternalInput()`, `hideWithController` → `hideWithExternalInput`,
`CONTROLLER_IDLE_MS` → `EXTERNAL_INPUT_IDLE_MS` (still 60 s).

A mouse notes the input in both dispatch paths, because neither alone covers it: hover and scroll are
not touch events and only reach `dispatchGenericMotionEvent`, while a click with no preceding
movement is a touch event and only reaches `dispatchTouchEvent`. Neither call consumes the event.

## Verified on hardware

All of it on **Samsung Galaxy S22 Ultra (SM-S908U1), Android 16 / API 36**, the release APK installed
in place, with a real game folder and saves folder, driven from the host over `adb`:

* A mouse click (source `MOUSE`, tool type `3`) on the title screen's `New Game` highlighted it and
  then opened it — where the pad used to swallow the DOWN.
* The same click hid the controls **and** the pill row within a frame; a touch on `HIDE` hid the
  controls and kept the pill row, and a finger never counted as external input.
* The overlay came back after ~60 s untouched (measured twice), and the menu read `External input:
  active|idle` accordingly.
* A key press hid the pad and logged `key KEYCODE_DPAD_DOWN -> page (scan 0)`; holding `D` in-game
  walked the character visibly across the map, and the probe build showed the engine accepting the
  event (`code=ArrowRight prevented=true`, i.e. it matched a binding and took it).
* With the switch off, the mouse and the keyboard left the pad and the pill row drawn, and it came
  back on.
* `./gradlew :app:testDebugUnitTest` and `node android/tools/test-shim-diagnostics.mjs` (13/13) are
  green — the JVM tests do not cover these classes and are the regression gate; the Node test drives
  the injected shim, whose stub engine had drifted behind it (below).

Hover proper could not be injected — `input motionevent` accepts only `DOWN|UP|MOVE|CANCEL`, and the
input dispatcher drops a `MOVE` with no pointer down, so no event reached the Activity at all. The
branch it shares with hover (`dispatchGenericMotionEvent` → `isPointerInput` → `noteExternalInput`)
was exercised with `input mouse scroll` instead, which hid the pad. The `scan != 0` path (a real USB
or Bluetooth keyboard) is the unchanged native one and has no `adb` injection that can produce a scan
code; it is the path the port takes before this release either way.

## The shim's Node test was failing before this release

`node android/tools/test-shim-diagnostics.mjs` drives the injected shim against a stub engine, and
that stub had drifted behind the shim: it served no `canvas.getContext` and no
`AdaBridge.setVertexUniformVectors`, so the shim's load-time GL-budget probe (the 256-slot uniform
table of `FINDINGS.md` §10.9) reported two errors and its *reports nothing but the shim's own load
line* check failed — 11/12, exit 1, and it was already like that before this release. The stub now
models what the shim calls (a WebGL2 limit object and the budget bridge, plus a check that the 256 it
reports reaches the bridge): 13/13. No product code changed for it.

## Everything else is v0.5.1

No other behaviour changed. See [v0.5.1](release-notes-0.5.1.md) for the demo fixes and
[v0.5](release-notes-0.5.md) for the side menu, the game's own Exit and the fresh-process launches.
