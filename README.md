# Alabaster Dawn on Android — unofficial port + controller fix

Two independent pieces of work for [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access):

1. **A native Android port** (`android/`) — runs the game in an Android WebView with no Wine, no
   Box64 and no NW.js, reading your own game files from a folder you pick, playing through a
   physical controller read natively from `InputDevice`, **the built-in on-screen pad, or a mouse and
   keyboard**, and keeping saves in a second folder you pick, in the Steam build's own layout.
2. **A controller fix for the desktop build** (`fix/`) — repairs how the game decodes gamepads on
   Linux and inside Wine containers (GameNative / Winlator / Proton).

Plus the research notes (`FINDINGS.md`) and the test harness (`tools/`, `logs/`) that produced them.

| Setup screen | Running under the port | On-screen pad | Pad layout editor |
|---|---|---|---|
| ![Game files and saves pickers](docs/setup.png) | ![Title screen](docs/title-screen.png) | ![On-screen pad over the title screen](docs/on-screen-pad.png) | ![Moving and resizing a pad control](docs/pad-editor.png) |

![The side menu, opened with Back: one uniform icon-led list — the switches, the battery one with its rate slider under it, the picture position, the status block with the port version, Troubleshoot and Exit — with the FPS/battery/temperature readout on](docs/side-menu.png)

![The diagnostics record while the engine's boot was stuck: the port's and the game's versions, the device, WebView, GL backend, asset counters, and the log naming the resources still pending](docs/diagnostics.png)

*Screenshots contain Alabaster Dawn artwork and text, © Radical Fish Games, shown for documentation.*

---

## Android port

### What works today

* Boots the unmodified game to its title screen — **no game file is ever edited**; everything is
  injected at document start and served from the APK.
* Game files are read through the **Storage Access Framework** from a folder you pick (read-only).
  The APK requests **no permissions at all**.
* Saves are written to a **second SAF folder** you pick, using the Steam layout
  (`Saves/Default/Save_ID_0000.save`, `Saves/Default/System.save`, `Saves/Backups/`, `Saves/Backups2/`),
  so a save folder can be copied in from the desktop and copied back out.
* Gamepad: Android `InputDevice` → W3C-standard gamepad for the engine. Left/right stick, d-pad,
  face buttons, shoulders, analog and digital triggers, start/select.
* **Mouse and keyboard**: a mouse click falls through the on-screen pad to the WebView (Android
  classifies a mouse click as a *touch* event, and the pad used to claim every DOWN while it was
  drawn), hover and scroll reach the page as they always did, and a keyboard plays the game —
  WASD/arrows move, Enter activates. Keys hid the pad too. The engine matches its bindings on the
  DOM's `event.code`, which Chromium derives from the *scan code*, so a key that arrives with no scan
  code (`code: ""`) would otherwise play nothing; the port dispatches the DOM event the page should
  have got, with a real `code`/`key`, for every key it can name — letters, digits, arrows,
  Enter/NumpadEnter, Escape, Space, Tab, Shift/Ctrl/Alt and F1-F12 — and leaves keys that do carry a
  scan code to the WebView's own path. Using the mouse or the keyboard hides the overlay exactly like
  a controller does, under the same switch (see the side menu below).
* **On-screen pad**: the same standard gamepad drawn over the game, for playing with no controller —
  both sticks, d-pad, A/B/X/Y, L1/R1, L2/R2, Select/Start/HOME, with a small toggle pill that hides
  the pad (the choice is kept). It feeds the *same* pad state as the hardware one, so the engine sees
  one standard pad; while external input is in use — a controller, a mouse or a keyboard — the whole
  overlay hides itself (controls *and* that pill row) and comes back after a minute of no input.
* **Pad layout editor**: an `EDIT` pill in the same row opens an editor — drag any control to move
  it, drag the selected control's corner handle to resize it, `-`/`+` to size the whole pad, `RESET`
  to restore the stock layout (`UNDO` while that reset is still unsaved) and `DONE` to save. The
  layout is kept in the app's prefs and, when a saves folder is picked, also written to
  `pad-layout.json` at its root, which wins on load so the layout travels with the saves folder.
  While the editor is open the pad publishes no gamepad at all, so the game falls back to keyboard.
* **A side menu on Back**: Back while the game runs opens a panel over it (the game keeps running
  behind) drawn as one uniform, full-width, icon-led list — an Eden / Azahar style menu: every entry
  is the same height with an icon in a fixed left gutter, the label on a single line with an ellipsis,
  the control at the right edge, hairlines between rows, and the whole row (icon, label and empty
  space) is the hit target. It carries a switch for whether external input in use hides the overlay
  ("Hide pad with external input" — a controller, a mouse or a keyboard),
  a **Game position** choice (Top / Center / Bottom) for where the picture sits inside the black
  letterbox bands (the current choice is a filled pill), a switch for an **FPS / battery /
  temperature** readout, a switch to **limit the frame rate** with the rate slider it reveals
  underneath (**20 / 30 / 40 / 45 / 60 fps**, for battery: the port is GPU-bound, and half the frames is
  half the GPU time — the game logic keeps its 60 Hz fixed step, because the engine's clock reads
  `performance.now()` itself), a switch to **keep a log file with
  the saves**, the port version, three
  status lines (external input active/idle, where saves go, the last thing the engine reported) and
  **Exit**. Back again, a tap on the dimmed area or Exit closes it (from Android 13 the registered
  predictive-back callback owns Back and the key is only handled below it — handling both ran the
  toggle twice and opened the menu only to close it); all five settings live in the app's prefs,
  and nothing is written into the game folder — the saves tree only ever gains the Steam `Saves/`
  layout, `pad-layout.json` and that log, all at its root. **Exit ends the app process** (the game's
  own in-menu Exit does too — see below), so the next launch is a fresh process with a fresh WebView
  renderer, and the saved record is flushed before the process goes. The task itself is deliberately
  **not** finished, so the app stays in the launcher's task list (recents) and can be reopened from
  there — ending the process alone is what makes the next launch clean, and finishing the task was
  what used to drop it out of recents.
* **The game's own Exit works**: the title screen's **Exit** button (and `System.quit`) now leave the
  app. The engine's only two ways out — `nw.Window.get().close()` and `nw.App.quit()` — used to be
  shim no-ops, so the engine tore its own menu down and waited for a process exit that never came, and
  the picture stopped responding. Both now reach the same path as the side menu's Exit.
* **Diagnostics without a PC**: the same panel opens the record — device, **port version** and the
  **game's own build version**, WebView version and capabilities, GL backend, folders, asset counters
  and the app's own log — readably on screen (a
  screenshot is already a usable report), shareable as text, and written to a file next to the app.
  The record is built for the failure this port actually meets on hardware nobody here owns: when the
  engine's loading bar stops, it names the resources still unfinished, grouped by kind, with the
  audio-decode callbacks counted; and when the page's own thread stops instead, it says so and names
  the file-system call it is stuck in. A line the engine repeats every frame while it is stuck
  collapses in place (`… There are unwrapped loadTrackers (x412)`), so the lines that name the cause
  are still in the record when the user opens the panel. A shader the device's own compiler rejects is
  reported the way the engine reports it — the file's path and the compiler's message are the titles
  of the console groups the engine logs a failed shader with, so they are in the record too (the
  engine's other console output is forwarded as before; the whole-source dump it logs under them is
  not). With the log switch on, the same text is
  also kept as `ada-diagnostics.log` in the saves folder — the record survives a force-stop — and the
  switch turns itself off after the first boot that *completes*, so the default is a file for boots
  that fail and nothing for the ones that work. A frozen boot usually forces a restart, so the app
  does not start from an empty record: the newest lines of that file are carried back in behind a
  `--- previous session, carried from ada-diagnostics.log ---` marker, and the file also starts
  being written the moment **START** is tapped — a hang while the game's files are being read leaves
  the same evidence as a hang while they load. There is also a **Troubleshoot** button on the setup
  screen, before the game starts.
* Resolution can be raised in-game: `640x360 / 960x540 / 1280x720 / 1920x1080 / 2560x1440`.
* Leaving the app pauses the game: the music stops and the loop stops burning CPU. An Android
  WebView never dispatches the page's `blur`/`focus` (which is how the engine knows it lost the
  foreground), so the port dispatches them on `onPause`/`onResume`.
* Immersive fullscreen, screen kept awake, renderer crashes recovered by rebuilding the WebView.

Verified end-to-end on **Samsung Galaxy S22 Ultra (SM-S908U1), Android 16 (API 36)**, with a
Switch Pro Controller: title screen, in-game input, save rotation (`Default` → `Backups` →
`Backups2`), `System.save` round-trip, and options persisted. The on-screen pad was verified on an
**Android 14 emulator**: it draws over the running game, the engine reports one connected standard
pad with *no* controller attached, a held stick and every button reach the engine with the expected
values, the d-pad steps the title menu and A opens the highlighted entry, the toggle hides/shows the
pad (persisted across a restart) and a controller event hides the controls until it goes quiet. The
later change that hides the pill row too (the whole overlay disappears while a controller is in use)
was verified on a **Galaxy Z Fold 7 (SM-F971B)**: the row stays hidden while controller events keep
arriving, a tap where it used to be is not swallowed, and the controls and the row both come back a
minute after the last event. The side menu was verified on the same Fold 7 (Android 17, API 37):
Back opens it through the predictive-back dispatcher (and `onBackPressed` covers older devices),
Back and a scrim tap close it with the WebView keeping focus (a gamepad event still reaches the port
afterwards), the overlay switch was driven both ways, the three positions measured
(`236…1612` Center / `0…1376` Top / `471…1847` Bottom — where the picture was *drawn*; the click
mapping that follows the picture was only fixed later, in v0.6.1, see below), the readout's battery
level, temperature and thermal word matched `dumpsys battery` and `dumpsys thermalservice`, the engine
was confirmed to keep polling behind the open panel, and Exit plus a relaunch kept both switches and
the position. The emulator
pass also covered the layout editor: the pad publishes nothing while editing, a dragged
control and a resized key reach the engine at their new geometry, the layout survives a restart, the
saves-folder `pad-layout.json` wins over the prefs copy, and `RESET`/`UNDO` flip as described.

The diagnostics hardening (2026-09-25) was verified on the same Android 14 emulator, back when
SwiftShader could not compile the engine's vertex shaders and the boot froze at 12 % of 1 755
resources — exactly the state these features exist for. The log file appeared at
`Download/AdaSaves/ada-diagnostics.log` and kept growing while the boot stayed stuck: it carried the
engine facts line, the `boot stall … (effect=301 shader=20 data=1082 …)` line, the ten shader
`JS ERROR` lines, and the engine's per-frame `There are unwrapped loadTrackers` line collapsed to
`(x10)` with the newest timestamp. Its header ended `; rewrite cached=40 hits=0 12861435/33554432
bytes`, and a CDP `location.reload()` moved that to `hits=40` with `served` growing 328 → 651 — the
12.7 MB bundle and the 38 other rewritten bodies came back from memory. Driving the engine's boot
tracker to complete over the same CDP connection produced `ENGINE boot: complete in 35000ms …`
followed, within the next 2 s tick, by `log file off: the game completed its first boot`, with
`log_to_saves=false` and no `log_to_saves_user_set` in the prefs and that line last in the file;
after tapping the switch on (setting `log_to_saves_user_set=true`), a second completed boot printed no
auto-off line and the switch stayed on across a force-stop and relaunch. The **shipped release
artifact** was then exercised the same way: `android/tools/release.sh`'s signed
`AlabasterDawn-Android-0.4.apk` was installed on that emulator over a debug uninstall (the keys
differ), granted the two folders from scratch through the picker, started, and it booted to the same
12 % stall while writing `app 0.4 (4) …` and `rewrite cached=40 hits=0 …` to the log file, with
`(x12)` visible in the collapsed repeat.

Two follow-ups came out of the first two reports from real devices, and both were measured the same
way on that emulator. Both reporters' panels turned out to be **fresh launches** — `game files: …
(not indexed)`, `assets: none served yet`, a two-line log — because a frozen boot forces a restart
before the panel can be read. So the record is now carried across restarts: on the setup screen with
the game never started, the panel read `--- log (202 lines, oldest first) ---`, the launch line, then
`--- previous session, carried from ada-diagnostics.log ---` and the newest 199 lines of the previous
file; a `force-stop` and relaunch reproduced the same, and a record carried over repeatedly keeps
**one** marker (counted in the file, `grep -c "previous session"` = 1). And the file now starts being
written when **START** is tapped: indexing a 3 000-entry tree took 2 119 ms, and the 2 s flush held
this session's `+3922ms indexing …` with `indexed 3000 entries` appearing only at `+5996ms` — i.e. a
hang while the game's files are being read now leaves a record on disk, which it previously did not.

The frozen boot of issue #1 (2026-09-26) turned out to be the game's own texture-slot table, and it is
fixed: each vertex shader declares `uniform vec2 u_texSlotCoords[TEX_SLOT_COUNT]` with 256 slots, which
costs a device whose `MAX_VERTEX_UNIFORM_VECTORS` is the GLES3 minimum of 256 **every** uniform vector
it has — so the shaders never compiled, `checkTrackers()` threw every frame and the loading bar stopped
for good. The shim now measures that budget before the first shader is requested, the port serves the
table the device can actually take (the game's own 256 wherever there is room, 192 at the floor, with
`gui.vert`'s second table packed into `vec4`s so the atlas keeps 192 slots instead of 96), and both the
shaders and the engine's own constant move together. On the same emulator that could not boot at all:
`0` shader errors and `ENGINE boot: complete in 6161ms` instead of `no progress for 8917499ms at 12.0%`.
The panel and the log now also carry the device's uniform budget and forward the engine's own
`console.error` lines — so a device that still cannot compile, or one that runs out of atlas slots,
says so in the record. See `FINDINGS.md` §10.9 for the measurements, including what the fix costs (64
fewer atlas slots per atlas on a floored device, out of the game's 256).

**The reported count turned out to be the wrong question** (2026-09-29). The Fold 7 reports the same
256 — yet it booted the game fine on port 0.4, before the rewrite existed, which is the tell. What a
driver *reports* is not what a shader *carries*: Adreno's compiler packs a `vec2` array two slots per
`vec4` (the GLSL ES 3.0 default-block packing), so the game's own 256-slot table fits a 256-vector
budget there, while SwiftShader packs one slot per vector and genuinely does not. The port now decides
from a **link**: before the first shader is requested the shim compiles the two shapes the game's
shaders have — a 256-slot table with the real 48-vector reserve, and `gui.vert`'s two with 32 — and the
rewrite only happens where the device's own table does not link. See `FINDINGS.md` §17.

The exit/version/side-menu work (2026-09-26) was verified on the same Android 14 emulator (`-gpu
host`). Driving the engine's own exit over CDP — `python3 tools/probes/cdp.py
'window.nw.Window.get().close()'`, the exact call the title screen's EXIT button makes — and the side
menu's Exit both printed `exit requested`, left `pidof` empty and `dumpsys activity activities` with
no task (that build used `finishAndRemoveTask`; since 2026-09-30 the exit leaves the task in recents,
see below), so the next launch is a fresh process: three consecutive launches each reached `ENGINE boot:
complete` with the title screen drawn and a new pid. The setup screen showed `port 0.5 (7)`, the
side menu's status block the same line, and the in-game Diagnostics header `game 0.1.0-10 Early
Access` — the game's own build, read from `bundle.js` (`class VersionManager { … }`, hotfix 10, suffix
"Early Access"); the changelog at `terra/data/database/changelog.json` is the fallback (`0.1.0`)
before the bundle is served. The side menu renders every entry at one width and height with its icon
in the same left gutter and the current position as a filled pill; tapping a row's *blank left edge*
toggled that row's switch, and with the menu left open for 12 s the watchdog logged no `engine silent`
line, i.e. the WebView never lost focus.

The **shipped release artifact** was then exercised the same way: `android/tools/release.sh`'s signed
`AlabasterDawn-Android-0.5.apk` was installed on that emulator over a debug uninstall (the keys
differ), granted the two folders from scratch through the picker, and it booted to the title screen and
reported `port 0.5 (7)` / `game 0.1.0-10 Early Access`. Both exits were driven through the UI on it:
the side menu's **Exit** and the game's own title-screen **Exit** (highlighted with the on-screen pad's
D-pad and confirmed with **A** — the menu is pad-driven, a plain tap does nothing), each leaving no pid
and no task, and a relaunch reached `ENGINE boot: complete` in a new process. The shim inside the
release APK is byte-identical to `assets/ada-shim.js` (`sha256` matched), which is what makes the
debug-build CDP evidence above carry over to the release build.

The **task list and the 30 fps switch** (2026-09-30) were verified on the maintainer's Fold 7
(SM-F971B, Android 17) with the signed release build, installed over the previous one so the two
folder grants stayed. The game booted (`ENGINE boot: complete in 3131ms, 1757 resources;
resolution=960x540`), Back opened the side menu, and its dump shows the new row — `Limit to 30 FPS
(battery)` with its own switch, between the readout switch and the log switch. Toggling it live in
the running game gave `frame limit on: 30 fps` in the port's record and `ENGINE fps: limited to 30
fps` from the shim, with the on-screen readout going `30 fps · 960x540` → (off) `60 fps · 960x540` →
(on) `30 fps · 960x540`, in one process with no reload; the setting survives a relaunch with the
other switches. The side menu's **Exit** then printed `exit requested`, left `pidof` empty — and
`dumpsys activity recents` still lists the task (`Recent #1`, `A=10655`), which is the fix: tapping
that card in the overview started a **new** pid on the setup screen, so the app is reopenable from
the task list while the next launch is still a fresh process with a fresh WebView renderer.

The **frame-rate slider** (2026-09-30) was verified on the maintainer's S22 Ultra (SM-S908U1, Android
16) with the signed release build, installed over an older one so both folder grants stayed. The side
menu shows the switch, and the slider under it only while the switch is on: the bar, its thumb and the
`20 30 45 60` labels — each label centred under the position its thumb reaches — with the chosen rate
as a readout beside it. Moving it changed the running game, live, in one process with no reload:
`20` → the readout and the shim's own count both `20 fps`, `30` → `30 fps`, `45` → `30 fps` (a 60 Hz
panel cannot present 45; the next vsync up from 22.2 ms is 33.3 ms), `60` → `60 fps`, switch off →
`60 fps`. The row appears and disappears with the switch, and the rate survives a switch off/on cycle
and a relaunch (`frame limit on: 20 fps`, `ENGINE fps: limited to 20 fps` from the new pid). Behind
it: `FpsLimitTest`, the shim harness at 53/53 (which drives the gate at every rate, including 45 on a
60 Hz panel), and the `20 30 45 60` assertions in `FINDINGS.md` §20.2.

The **Steam demo** was run through the port on the same emulator (`terra/` 226 MB / 2 327 files):
`ENGINE boot: complete in 6655ms, 1641 resources`, 0 failed decodes, its own title screen and intro
drawing, and the record naming it `game 0.0.5-3 Alpha`. It exposed two defects, both since fixed and
re-verified — the option relabel applying the release ladder's resolution names to the demo's own
ladder, and `FsBridge.mkdir` creating `_Saves_*` junk in the picked saves folder from the demo's
Windows-style save paths instead of resolving them onto `Saves/Default`. See `FINDINGS.md` §12.

The mouse/keyboard/Back work (2026-09-29) was verified on the **S22 Ultra (SM-S908U1, Android 16,
API 36)** with the release APK installed in place, driven over `adb` from the host. A mouse click
(`input mouse tap`, source `MOUSE`, tool type `3`) on the title screen's **New Game** highlighted it
and then opened it, where the pad had been swallowing the DOWN; the same click hid the controls *and*
the pill row, a touch on `HIDE` hid the controls and kept the row (a finger never counts as external
input), the overlay came back after ~60 s untouched, and `input mouse scroll` hid it through the
generic-motion path. A key press hid the pad and logged `key KEYCODE_DPAD_DOWN -> page (scan 0)`;
holding `D` in-game walked the character visibly across the map, and an instrumented build showed the
page receiving the port's event with a real `code` and the engine taking it (`code=ArrowRight
prevented=true` — a match on its binding). With the switch off, the mouse and the keyboard left the
pad and the pill row drawn. The same session found and fixed the Android 13+ Back double-dispatch
(the record showed `back: dispatcher (game)` *and* `back: key (game)` for one press). Two probes
established the environment facts the notes rest on: the page receives every key (`window` listener
fired) but sees an empty `code` for a scan-code-less event, and `navigator.keyboard` *is* present in
this WebView, so the engine's `initKeyboard()` — which calls `getLayoutMap()` before registering its
listeners — does not throw. Hover itself could not be injected (`input motionevent` takes only
`DOWN|UP|MOVE|CANCEL`, and a lone `MOVE` is dropped by the input dispatcher), and no `adb` injection
produces a scan code, so the `scan != 0` path (a real USB/Bluetooth keyboard) is the unchanged
WebView path. See `FINDINGS.md` §14.

The picture-position fix (2026-09-29, v0.6.1) was verified **without a device**: with the picture
aligned Top or Bottom, the port moved the picture where the engine's mouse mapping could not see it
(half the black band, 866 px on a 1080x2340 phone at 640x360), so clicks landed nowhere near what was
clicked. The engine was booted in a browser and driven through its own input pipeline with real mouse
events; the same point on the picture now maps to the same game coordinate in Top, Centre and Bottom,
where the old code read the picture's centre as `y = -280.8` instead of `y = 180`.
`node android/tools/test-shim-diagnostics.mjs` covers the arithmetic in both display scales (11 new
checks, 7 of which fail against the pre-fix shim). The Android mouse path itself is unchanged from
v0.6.0, and no phone was attached. See `FINDINGS.md` §15.

The **GPU/shader pass** (2026-09-30, v0.7.0) was verified on the Fold 7 with the release build
installed (both folder grants kept, so the launch is the real one). The GUI-precision fix was
measured at the element the reporter's screenshot carried the comb on: std 8.1 across the selected
menu row with no peak at any lag, where the reported build measured std 19.7 with a 16-pixel
periodicity. The uniform-link decision reports `256-slot links, gui two-table links -> TEX_SLOT_COUNT
256` and rewrites no shader on that device at all (its compiler packs two `vec2` slots per `vec4`),
while the probe still reports `does not link` for a 4096-slot table, so it is not a rubber stamp. The
same build took the 30 fps switch live (`30 fps · 960x540` → `60 fps · 960x540` → `30 fps · 960x540`,
no reload, setting kept), kept the app in the task list across an Exit (`dumpsys activity recents`
still listed the task, and tapping the card started a new pid on the setup screen), and booted clean
(`ENGINE boot: complete in 3013ms, 1757 resources`) with no console lines in the record. The shader
work has unit tests behind it (`ShaderPrecisionTest`, `ShaderSlotsTest`, `ShaderDitherTest`) and the
harness has 60 checks; the dither change was measured in a harness that renders the engine's own
dither lines verbatim. See `FINDINGS.md` §16-§20.

The **Mali shader report** (2026-09-30) was read back from the same device (a Poco X7 Pro, Mali-G720), and
the driver's own message names the construct: `S0032: no default precision defined for variable 'vec3[5]'`
/ `'vec4[4]'` — that front end does not carry a shader's declared default precision onto an array written
`type[size] name`, though the bytes are valid ES 3.0 (a strict front end and ANGLE accept them; the same
bug is reported for other Mali generations). The port serves those declarations in the declarator spelling
that driver accepts, and only to a device whose own compiler refuses them. Two constructs cannot move their
brackets and are served differently (§22.12, §22.13): the post pass's colour ramp, written as an array
*constructor* passed as an argument, is **named** instead — a temporary inherits no element precision in any
spelling — and the two water fragment shaders get the vertex stage's default float precision, because their
programs otherwise fail to **link** on that driver (`Uniforms with the same name but different
type/precision: u_waveHeight`).

**Confirmed on that device, in two steps.** v0.7.1's gate answered "accepts" wrongly, so the lift never ran
(`logs/mali/log alabaster 0.7.1.txt`); v0.7.2's gate compiles the game's own declarations — one program per
shape, on a context made with the engine's own attributes, compiled **and linked** — and on that phone it
found the refusal and the lift ran, after which the two water shaders compile (native run:
`logs/mali/log alabaster 0.7.2 native.txt`). One post-processing shader still fails there, and v0.7.3 makes
the record say, per file, whether the lift applied — `array declarations lifted in … (9/9)`, or
`… NOT lifted … (0/1): its bytes carry none`.

**Measured on four other Mali generations, and asked of yours.** `tools/mali-probe/` — a small app that
compiles the game's own shaders on a device's *native* driver — ran at Firebase Test Lab on a Pixel 8a
(Mali-G715), Pixel 7 (G710), Pixel 6 (G78) and a Galaxy A35 (G68): **every case compiled on all four**,
including the shapes other projects report this family refusing (`logs/mali/probe/`, `FINDINGS.md` §22.10).
The reporting phone is a **Mali-G720 on driver r49**, so the refusal looks like a *driver revision*, not a
GPU generation — a property that can arrive with a system update, which is why the port decides per device.
Since v0.7.4 it can also ask the device directly: **Troubleshoot → "Test shader spellings"** compiles the
game's own shaders in every spelling the port could serve, on your driver, and puts one verdict line each
into the record (§22.11). The facts line carries `driver=native|ANGLE` too — on Mali phones that field is
the difference between the game starting and the boot freezing — and **Troubleshoot → "OpenGL driver"**
opens the screen where that choice is made, since an app can neither read nor write it.

The lift's bytes are verified by unit tests, `glslangValidator`, the shim harness (60 checks) and a boot
A/B through the port's own shim in Chromium (identical active uniforms and attributes); this project has no
Mali hardware, so that phone's own reports remain the test. See `FINDINGS.md` §21-§22.

### Download

Grab `AlabasterDawn-Android-<version>.apk` from
[Releases](https://github.com/moronigranja/alabaster-android/releases) (allow "install unknown
apps" for your browser or file manager). Every release is signed by **this project's own release
certificate** — `CN=Alabaster Dawn Android port, O=moronigranja, C=BR`, SHA-256
`ab31dd8874bf28fb06c783c8df0d3f6abeec719240165605ce27070e676b9604` — so you can confirm what you
install:

```bash
apksigner verify --print-certs AlabasterDawn-Android-0.7.5.apk   # no SDK? keytool -printcert -jarfile …
```

The APK carries `assets/LICENSE` + `assets/NOTICE.md` inside, so the binary ships the notices it is
distributed under. Installing it over a build signed with a different key (a local debug build,
say) needs an uninstall first.

### Not in this milestone

* The on-screen pad's stick **clicks** (L3/R3) are not exposed, and its multi-finger handling has
  unit tests but no real two-thumb pass on a phone yet.
* The in-game **Load** list was not eyeballed (needs a manual save); everything underneath it —
  file naming, rotation, metadata, `mtime` — is verified.
* **Pointer lock is not used.** The engine's `requestPointerLock` path is left alone; the game's menus
  and its own cursor use absolute `pageX/pageY`, which is what Android delivers to a WebView.
* **A real USB/Bluetooth keyboard could not be driven over `adb`** — no injection path sets a scan
  code, and the port's own conversion is exactly what handles a missing one. A keyboard's keys carry
  their scan code and take the WebView's path untouched, which is the pre-0.6 behaviour for every key.
* Performance is GPU-bound; see the ledger below before expecting 1080p.
* **A shader some Mali drivers reject is handled, and the port can now ask the device itself.** Reported
  on **Mali** (a Poco X7 Pro, Mali-G720): the device's compiler refuses a shader the port serves with
  `S0032: no default precision defined for variable 'vec3[5]'` / `'vec4[4]'` — an array written
  `type[size] name`, which is valid ES 3.0 and works on Adreno, SwiftShader, desktop, and on four other
  Mali generations measured through a device farm (§22.10). The port serves six shader files — the five
  fragment-stage ones and their shared library — as the declarator spelling, a named ramp and the water
  fragments' default precision, for a device whose compiler refuses the game's own; and **Troubleshoot →
  "Test shader spellings"** compiles every spelling it could serve on the device's own driver, one verdict
  line each, so a phone that still refuses can say which spelling it wants (§22.11). With the WebView's **ANGLE**
  driver the refusal does not happen at all — and the record now says which driver the page got
  (`driver=native|ANGLE`), with a button that opens the screen where that choice is made. No Mali device is
  available here beyond a reporter's, so that phone's reports are the test — `FINDINGS.md` §22.
* **The emulator's software GL stack draws the in-game map wrong.** SwiftShader
  (`-gpu swiftshader_indirect`) renders black tiles with purple/pink fragments where the *same build*
  is correct on real hardware and on the same emulator with the host GPU (`-gpu host`). The port
  serves identical files and shaders either way, so it is the software renderer, not the port; the
  title screen, cutscenes and menus draw correctly under it.

### Requirements

* JDK 17, Android SDK with **platform 36** and **build-tools 36.0.0**.
* Phone or tablet on **Android 8.0+** (`minSdk 26`).
* A Bluetooth/USB controller, a mouse and a keyboard, or the on-screen pad (no controller required).
* Your own copy of the game (Steam) — the full game or the **demo**; neither is distributed here.
  The demo (build `0.0.5-3 Alpha`) boots, plays and writes the same `Saves/` layout.

### Build and install

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties      # or point it at your SDK
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests (gamepad state and overlay merge, pad layout and hit rules, the diagnostics ring and its
line collapsing, the asset-read accounting, the rewrite cache's byte budget, the game-version parsing
from the changelog and the bundle, the save-path namespace rule, and the log-file rules):

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

The injected shim (`assets/ada-shim.js`) is driven against a stub engine by a Node test — the boot
diagnostics and the picture alignment's arithmetic (against both display scales) — no device, no
browser, under a second:

```bash
node android/tools/test-shim-diagnostics.mjs
```

Publishing a signed release (maintainers): the release keystore lives **outside** the repo and is
wired through the gitignored `android/keystore.properties`; clones without that file still build,
but the release variant comes out unsigned.

**Releases are batched.** A version bump and a published APK belong to a set of changes worth asking
somebody to download — not to each commit, and not to each fix that a reporter's next record could
still change. Changes accumulate on `main`, `TODO.md`'s *Next version* section is the queue, and the
release is cut when that queue is worth a download (a fix a reporter is waiting on, a feature, or a set
of small things that have stopped moving). The 0.7.2 → 0.7.5 run in one day was a testing loop with one
player; do not repeat it.

```bash
keytool -genkeypair -keystore ~/.android/alabasterdawn-release.jks -alias alabasterdawn \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Alabaster Dawn Android port, O=moronigranja, C=BR"
# then android/keystore.properties: storeFile / storePassword / keyAlias / keyPassword (chmod 600)

android/tools/release.sh                       # signed build + digest + signature check
android/tools/release.sh --upload --publish --notes docs/release-0.7.5.md
```

`--notes` takes the **release body**: since v0.5 that is a terse changelog plus links to the full
`docs/release-notes-<version>.md` and the README, so the GitHub release page stays short and the
install/requirements/limitations text lives in the README only. Keep that body **user-facing and plain** —
what changed, what to do, what is verified, upgrading — and leave the mechanism, logs and measurements to
`docs/release-notes-<version>.md` and `FINDINGS.md`. v0.7.2 was first published with the long notes as the
body; do not repeat that.

`tools/release.sh` renames the shipped artifact to `AlabasterDawn-Android-<version>.apk` (never
`app-release-unsigned.apk`, never `app-debug.apk`) and runs the digest and signature checks on that
exact file. Back the keystore and `keystore.properties` up off-machine: losing them locks updates
on every device that installed a build signed with them.

### First run

Copy the game to the phone — the picked folder must contain a `terra/` child:

```bash
adb shell mkdir -p /sdcard/Download/AlabasterDawn
adb push "<steam>/steamapps/common/Alabaster Dawn/terra" /sdcard/Download/AlabasterDawn/terra
```

Optionally copy your desktop saves in (this is the import path):

```bash
adb shell mkdir -p /sdcard/Documents/AdaSaves
adb push "$HOME/.config/Alabaster Dawn/Saves" /sdcard/Documents/AdaSaves/
```

Then: **CHOOSE GAME FILES** → `Download/AlabasterDawn`, **CHOOSE SAVES FOLDER** →
`Documents/AdaSaves`, **START**. The first launch indexes the tree (~2 650 files, a few seconds);
afterwards both folders are remembered and it is one tap. The selected saves folder gains
`Saves/{Default,Backups,Backups2}` on first boot.

Exporting a save is a plain file copy, and the files are drop-in portable back to Steam.

### If the game does not boot

A device-only failure is silent by nature: no crash, no log, usually just the engine's loading bar
stopped at a few percent. The port therefore keeps its own record of what happened, and the record
can be read on the device itself:

* **Troubleshoot** on the setup screen (before the game starts), or **Back → Troubleshoot** while the
  game runs — including while the loading bar is stuck, which is exactly when it matters.
* **Reset resolution**, next to Share and Close in that panel. The engine never caps the Resolution
  option, and 2560x1440 is eight times the pixels of the phone ladder's 960x540 default — slow enough
  that the game's own Options menu, the only in-game way back, is painful to use. This drops the
  game's stored Resolution option (it lives in the WebView's storage, not in the game's files) and the
  next start boots at the default rung.
* The header carries the device, the Android version, the **WebView package and version**, whether
  the WebView supports document-start scripts, which GL backend the page got, the picked folders, the
  asset-path counters (`served / missed / inFlight / slowest`) and how long the engine has been quiet.
* The log below it names the failure: the shader that would not compile, the resources still
  unfinished when the bar stopped (grouped by kind, with the audio-decode callback counts), or the
  file-system call the page's own thread is stuck in.
* **Share** hands the whole record to any installed app as text and writes the same text to
  `Android/data/io.github.moronigranja.alabasterdawn/files/diagnostics-<epoch>.txt`. The side menu
  shows the last engine report inline, so a stuck boot announces itself without opening anything.
* **The record is also kept as a file** in the picked saves folder, `ada-diagnostics.log` (a whole
  snapshot, overwritten every 2 s while it is dirty), so a failure survives a force-stop, a reboot
  and a `adb`-less device — send that file instead of a screenshot. The side menu's **Keep a log
  file with the saves** switch controls it, on by default, and it switches itself off after the
  first boot that *completes*: the file is for the boots that fail. One tap on the switch, either
  way, ends that behaviour for good. Nothing deletes an existing log.
* **Restarting does not lose it.** The record is per-launch, and a freeze usually forces a restart
  before you can read the panel — so the newest lines of that file are read back at launch and shown
  above this session's, behind a `--- previous session, carried from ada-diagnostics.log ---`
  marker. The carried lines are 199 of them (half the ring) and this session's lines eventually
  scroll them out; send the file (or a screenshot) soon after the failure.
* **A hang while the game's files are being read is recorded too.** The file starts being written as
  soon as **START** is tapped, not when the game's page begins loading, so a port that stalls on a
  big or slow folder leaves the evidence on disk — `indexing …` and nothing after it.
* Everything in the record is also on logcat, tag `AdaPort`, if you do have a PC:
  `adb logcat -s AdaPort:I` (the raw lines: the on-screen/on-file record collapses a line the engine
  repeats every frame, logcat keeps every occurrence).

The panel is the shortest way to report a problem: open it and send the screenshot.

### Resolution and performance

Measured on the S22 Ultra (Snapdragon 8 Gen 1, Adreno 730), `gpubusy` sampling:

| In-game resolution | GPU busy | Frame rate |
|---|---|---|
| 640×360 | 54 % (52.9–54.3) | 59.9 fps sustained (p50 16.7 ms) |
| 960×540 | 91 % | 60 fps sustained |
| 1280×720 | 99.9 % (saturated) | 38–42 fps cool, 19–21 fps hot |

Thermals, not CPU, are the limit: the same 720p scene measured 42 fps cool and 19–21 fps with
`Thermal Status: 3` / 45 °C skin. Don't charge while playing; `640×360` is the safe default,
`960×540` the sharpest resolution that still holds 60.

The renderer is the GPU cost, so the side menu's **Limit the frame rate** switch — and the rate
slider under it (`20 / 30 / 45 / 60 fps`) — is the battery lever that costs nothing in game speed:
the shim gates the page's `requestAnimationFrame` (the engine's only loop) to one frame per frame
interval, which on the S22 Ultra's measurements is roughly half the GPU time of the rung in use —
measured on the Fold 7 below, the readout went `60 fps → 30 fps` and back with the switch, in the
same session, without a reload. A display presents only on a vsync, so a rate that is not a whole
division of the panel's refresh lands on the next one up: on a 60 Hz panel `45` is 30, on a 120 Hz
one it is 40.

### How it works (short)

* `WebViewAssetLoader` serves the picked tree in-process from `https://appassets.androidplatform.net/game/`;
  a tree index built once makes the game's ~1 240 per-boot `.flac` probes instant 404s. The few
  responses that are rewritten (the 12.7 MB bundle's resolution ladder, the option labels, the
  patched `.frag` shaders, the injected index) are then served from a byte-bounded `RewriteCache`
  instead of being read and rewritten again, and a fragment shader's paired `.vert` is read once per
  process rather than once per request; the diagnostics header reports both as
  `; rewrite cached=N hits=N bytes/max`.
* `WebViewCompat.addDocumentStartJavaScript` injects `assets/ada-shim.js` before the page's own
  scripts: a `require`/`nw` shim plus the device-specific workarounds the game needs on Android's
  WebView GL stack (no `OES_draw_buffers_indexed`, a dead shader attribute the Adreno driver
  rejects) and the resolution ladder.
* `AdaBridge` exposes the pad (`getGamepadJson()`) and the file calls used for saves; `FsBridge`
  maps the engine's `/saves` namespace onto the picked saves tree and keeps the Steam file names
  exactly (create-or-overwrite, never a deduplicated `Save_ID_0000 (1).save`). Both engine
  generations are accepted: the released one appends `/Saves/Default/` to `nw.App.dataPath` and the
  demo-era one appends `\Saves\Default\`, and both land on the same `Saves/` layout inside the
  picked folder. Anything outside that namespace — including the `\Default\Saves\Default\` paths the
  game probes in its own storage fix — is refused rather than created.
* `SideMenuView` is the Back-opened panel (a scrim plus a right-edge panel whose descendants are
  made unfocusable, so the WebView keeps focus and the engine's loop is never blurred). It only
  reports taps: the Activity owns the four settings (`ViewAlign` is the picture position and its
  wire format, pure Kotlin and unit-tested) and the shim reads them once per engine frame from
  `getViewAlign()`/`getStatsEnabled()`, moving the canvas element's layout box for the position
  (never `object-position`: the engine's mouse mapping reads the element's `offsetTop` and assumes
  the picture is centred inside it, see FINDINGS 15) and painting the readout.
  `Telemetry` is the only thing that reads the phone: the sticky battery broadcast plus
  `PowerManager.getCurrentThermalStatus()`, cached for 5 s and only sampled while the readout is on.
* `GamepadState` is the single JSON producer: the physical pad (`Gamepad`, from `KeyEvent`/
  `MotionEvent`) and the on-screen pad both write into it, and it publishes one merged W3C standard
  pad. `OnScreenPadModel` holds the pad's layout and pointer rules (pure Kotlin, unit-tested) and
  `OnScreenPadView` draws it and turns touches into model calls. `PadLayout` is the persisted
  override set (its JSON, pure Kotlin) and `PadLayoutStore` keeps the two copies — the app prefs and
  `pad-layout.json` in the picked saves folder.
* `Diag` is the app's own bounded log and its frame clock: `getGamepadJson()` is called exactly once
  per engine frame, so its silence means the page's JS thread stopped, which is a *different* failure
  from a page waiting for an asset read that never returns. A line that repeats immediately — what a
  fault thrown once per frame looks like — is collapsed in place to `(xN)` with the newest timestamp,
  so the ring keeps the context around a fault instead of only its last seconds, while an attached
  sink still sees every raw occurrence (logcat keeps full fidelity). A 2 s watchdog on the main thread
  reports that silence together with the bridge call in flight, `AssetTracker` (pure Kotlin,
  unit-tested) reports asset reads that have been unfinished for seconds, and the shim's own
  `watchBoot` samples the engine's boot tracker on a timer — not on a frame, because during BOOTING
  the engine renders without running the game loop — naming the resources still pending, grouped by
  kind, plus the `decodeAudioData` callback counts (the one load path in this engine that can stay
  unfinished without an error: a sound finalizes from its success callback alone).
* `ShaderSlots` (pure Kotlin, unit-tested) is the rule behind the served shader text: how big a
  `TEX_SLOT_COUNT` the device can carry, and when `gui.vert`'s two tables have to be packed into
  `vec4`s — the fix for the frozen boot of issue #1. It is told what the page's own compiler *linked*
  (`AdaBridge.setShaderTables`), not what the driver reports, so a device whose count sits at the
  GLES3 floor but whose compiler packs the table keeps the game's own bytes and atlas size; a page that
  never answers keeps the count-based fallback.
* `ShaderPrecision` (pure Kotlin, unit-tested) raises the GUI shaders' `precision mediump float` to
  `highp`, which ES 3.0 guarantees in both stages. On a mobile GPU the GUI's screen-space and tiled
  maths otherwise quantises into a visible stripe pattern on the shaded fills (the selected side-menu
  row, the slider track) — invisible on the desktop build, where GL promotes mediump. See
  `FINDINGS.md` §16.
* `ShaderDither` (pure Kotlin, unit-tested) serves the five world shaders that index the engine's
  ordered dither by the *art* pixel (`gl_FragCoord.xy / (u_screenScale * u_ditherScale)`) on the
  *render* pixel instead — the convention the engine's own `solid.frag` and the water's radial dither
  already use. The dither is a binary discard, so at one art pixel per cell its dots are as large as
  the art and read as a grid over the terrain on a phone panel (issue #3, `FINDINGS.md` §18). Nothing
  else in those bytes changes.
* `DiagnosticsDialog` renders that record and hands it to any text share target; `LogFile` (pure
  Kotlin, unit-tested) is the name and the rules behind the saves-folder copy — the same `snapshot`
  text, written on its own thread at most every watchdog tick, switched off by itself after a boot
  completes unless the user has touched the switch, and read back at the next launch (its stamped
  lines, and only those) so a restart carries the previous record instead of showing an empty panel.
* `FINDINGS.md` documents the measurements, the dead ends and the reason for every patch.

---

## Desktop controller fix

The game decodes a gamepad that Chromium reports with `mapping != "standard"` using a raw
DirectInput/hat layout **only when `os == LINUX`**; elsewhere (i.e. Wine: GameNative, Winlator,
Proton) it uses the standard layout for a pad that is not in standard order. Either way some pads
end up mis-mapped — the same class of bug as
[nwjs#7006](https://github.com/nwjs/nw.js/issues/7006) for CrossCode.

`fix/gamepad-fix.js` hands the engine pads that really are in W3C standard order, so its standard
table is always correct. Where Chromium sees no pad at all — Windows Chromium reads gamepads only
through XInput, which GameNative's virtual pad never reaches — it synthesizes a standard pad from
GameNative's 64-byte shared-memory struct.

```bash
fix/install.sh                        # auto-detect the Steam install
fix/install.sh --game-dir "/path/to/Alabaster Dawn"
fix/install.sh --mapping standard     # leave Chromium's pads untouched
fix/install.sh --mapping dinput       # always re-decode raw DirectInput pads
fix/install.sh --revert               # undo (restores index.html)
node fix/test-gamepad-fix.mjs         # smoke tests, no game needed
```

The installer backs up `terra/index.html` and copies `gamepad-fix.js` + `gamepad-fix.json` next to
it; `--revert` (or verifying files in Steam) removes the patch. Diagnostics: set `"debug": true`
in `gamepad-fix.json`, or use `fix/gpf-diagnose.js` and `tools/inpage-probe.js`.

> The Android port does **not** need this fix: it feeds the engine a real W3C-standard pad, so the
> engine's standard table is used (this is why the port has no mapping bug).

---

## Repo layout

```
android/    the port (Gradle root + :app; Kotlin, no permissions, one runtime dependency)
            tools/release.sh builds, verifies and publishes the signed APK
docs/       screenshots used by this README, the release notes for each version
            (`release-notes-<v>.md`, the detailed document) and its terse release-page
            body (`release-<v>.md`, the changelog plus links)
fix/        controller fix for the desktop / Wine build (installer, config, tests, diagnosis)
tools/      measurement harness: uinput virtual gamepad, in-page probe, fast static server,
            the Node/NW.js browser shim that proved the port feasible
logs/       raw diagnostic logs captured inside GameNative (evidence for FINDINGS.md)
FINDINGS.md research notes: the controller bug, the boot requirements, the port design,
            the performance ledger, and the dead ends not worth retrying
NOTICE.md   attribution: what the game is, what ships inside the APK, what was not copied
LICENSE     MIT (this repository's own code and documentation only)
```

## Legal

* **Alabaster Dawn is © Radical Fish Games.** This repository is an unofficial, non-commercial
  interoperability project. It is not affiliated with or endorsed by Radical Fish Games.
* **No game files, assets or code are distributed here.** You need your own legitimately purchased
  copy; the port reads it from a folder you choose and never modifies it. The desktop installer
  patches a copy in your own installation and can revert itself.
* "Alabaster Dawn", "CrossCode" and the associated logos are trademarks of their owners, used here
  descriptively. Screenshots in `docs/` show the game running for documentation purposes.
* Saves remain yours, in a folder you control.

## License

The code and documentation in this repository are **MIT** licensed — see [LICENSE](LICENSE), and
[NOTICE.md](NOTICE.md) for the game/third-party attribution. MIT requires its notice to travel with
copies of the software, so the released APK carries both files inside itself as `assets/LICENSE`
and `assets/NOTICE.md` (copied at build time from the repo root, one source of truth).
This covers this repository's own source only, not the game and not the third-party components
below. If you would rather have a copyleft or patent-granting license, swapping `LICENSE` is the
only change needed (at which point the sub-projects here follow automatically).

## Credits and third-party components

* **Radical Fish Games** — Alabaster Dawn, and the engine lineage (`terra`) it shares with CrossCode,
  whose explicit browser platform path is what makes this port possible.
* **[AndroidX WebKit](https://developer.android.com/jetpack/androidx/releases/webkit)** — Apache-2.0
  (`WebViewAssetLoader`, `addDocumentStartJavaScript`).
* **[Material Symbols](https://fonts.google.com/icons)** — Apache-2.0, © Google LLC; the side menu's
  icons, converted to vector drawables.
* **[Gradle](https://gradle.org/)** — the wrapper (`android/gradle/wrapper/`) is Apache-2.0,
  © Gradle, Inc.
* **[CrossAndroid](https://gitlab.com/Namnodorel/crossandroid)** — prior art for running this engine
  on Android; that project ships **no license** (all rights reserved), so nothing was copied from
  it. It informed the architecture only, and this port is an independent implementation.
* **[nwjs#7006](https://github.com/nwjs/nw.js/issues/7006)** — independent report of the same
  controller-mapping bug class in CrossCode.
* GameNative / Winlator / Proton — the Wine containers the desktop fix targets.
