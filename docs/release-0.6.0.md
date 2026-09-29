## v0.6.0

Mouse and keyboard now work in the game, and the port fixes a Back-press bug that made the side
menu unusable on Android 13+.

* **A mouse works.** A click on the game was being swallowed by the on-screen pad — a mouse click is
  a touch event for Android's dispatch (`MotionEvent.isTouchEvent()`, `libs/input/Input.cpp`), and
  the pad claimed every DOWN while it was drawn. The pad now recognises a pointer tool
  (`TOOL_TYPE_MOUSE`) and returns `false` for it, so the click falls through to the WebView exactly
  like a finger on empty space. Hover and scroll already reached the page; they now also count as
  input.
* **A keyboard works.** Keys already reached the page, but the engine matches its bindings on
  `event.code` alone and Chromium derives `code` from the scan code — a key event with no scan code
  arrives as `code: ""` and plays nothing. The port now dispatches the DOM event the page should have
  got, with a proper `code`/`key`, for every key it can name (letters, digits, arrows,
  Enter/NumpadEnter, Escape, Space, Tab, Shift/Ctrl/Alt, F1-F12); a key that carries a scan code keeps
  the WebView's own path. WASD/arrows move the character and Enter activates menus.
* **The overlay hides for both**, not just for a controller: the side menu's switch is now
  "Hide pad with external input" and its status line "External input: active|idle". The stored
  preference key is unchanged, so existing installs keep their setting.
* **Back works again on Android 13+.** The port registers an `OnBackInvokedCallback` for the gesture
  and also handled the Back key; on Android 13+ the framework delivers a Back key to *both*, so one
  press opened the side menu and closed it again in the same dispatch. The callback now owns Back
  from API 33 up, and the key is only acted on below 33. This is the "the physical Back button does
  not open the menu" report from an AYN Odin 3.

Verified on a Galaxy S22 Ultra (Android 16 / API 36): a mouse click on the title screen's `New Game`
highlights and opens it, a click and a key both hide the controls and the pill row, a held `D` walks
the character, touch still drives the pad, and turning the switch off leaves the pad drawn while the
mouse and keyboard are used. `./gradlew :app:testDebugUnitTest` and
`node android/tools/test-shim-diagnostics.mjs` are green.

Full release notes: [docs/release-notes-0.6.0.md](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.6.0.md)
Install, requirements and known limitations: [README](https://github.com/moronigranja/alabaster-android#readme)
