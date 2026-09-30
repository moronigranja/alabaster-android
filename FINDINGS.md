# Alabaster Dawn — controller/Android research notes

> The in-container installer that grew out of §3.2-3.4 now lives in its own repo:
> `~/repos/wine-chromium-gamepad` (README + docs/FINDINGS.md + docs/STATUS.md, host test 15/15).
> This file stays the record of the desktop/container investigation itself.

Handoff doc. Everything below was measured/verified on the user's machine
(CachyOS, KDE Wayland, RTX 4070 Max-Q) and on their phones — **SM-F971B** (Galaxy Z Fold 7) for the
GameNative diagnostics in §2–§3, **SM-S908U1** (Galaxy S22 Ultra, Android 16 / API 36, Adreno 730)
for the native port in §9 — unless explicitly marked **[unverified]** or **[inference]**.

Repo layout:

```
README.md / LICENSE            project overview; MIT (this repo's own code only)
FINDINGS.md                    this document
ON_SCREEN_GAMEPAD_PLAN.md      design + status of the on-screen pad (geometry, hit rules, on-device
                               verification method and results)
android/  app/                 the native Android port (§9): Kotlin, WebView + SAF, no permissions
          app/src/main/.../GamepadState.kt     single JSON producer (physical + on-screen pad)
          app/src/main/.../OnScreenPadModel.kt pad layout + pointer rules, pure Kotlin (unit-tested)
          app/src/main/.../OnScreenPadView.kt  draws the pad, touches -> model calls; layout editor
          app/src/main/.../PadLayout.kt        the persisted layout override set + its JSON (pure Kotlin)
          app/src/main/.../PadLayoutStore.kt   prefs copy + pad-layout.json in the saves folder
          app/src/main/assets/ada-shim.js    document-start shim (ported from tools/)
          app/src/test/.../GamepadStateTest.kt   12 JVM tests (7 mapping/state + 5 overlay merge)
          app/src/test/.../OnScreenPadModelTest.kt  25 JVM tests (layout, hit rules, dead zone, editor)
          app/src/test/.../PadLayoutTest.kt     6 JVM tests (wire format, precedence)
docs/     setup.png            screenshots for README.md (Alabaster Dawn art (c) Radical Fish Games)
          title-screen.png
          on-screen-pad.png
          pad-editor.png
          release-notes-0.1.md  the 0.1 release notes (no on-screen pad yet; see ON_SCREEN_GAMEPAD_PLAN.md)
          release-notes-0.2.md  the 0.2 release notes (on-screen pad + layout editor)
fix/    gamepad-fix.js         controller fix for the desktop build (drop-in, tested)
        gamepad-fix.json        its config
        install.sh              installer / revert for a game directory
        test-gamepad-fix.mjs    smoke tests (node test-gamepad-fix.mjs) - all pass
        gpf-diagnose.js         instrumented build (logs what the game sees to a file)
        terra-index.html.orig   copy of the game's terra/index.html (patch reference). A game file:
                                gitignored, not distributed - regenerate with
                                `cp "<game dir>/terra/index.html" fix/terra-index.html.orig`
tools/  vpad.py                 uinput virtual gamepad (Linux test harness)
        create_pad.py           first version of the same idea
        inpage-probe.js         drop-in probe: raw vs. shimmed getGamepads -> JSONL file
        ada-browser-shim.js     Node/NW.js shim that boots the game in a plain browser (§4.3)
        serve-fast.py           HTTP/1.1 keep-alive static server for the boot experiment (§8)
logs/   phone-gpf-log.txt       raw diagnostic log from inside GameNative (Windows/Wine)
        phone-gpf-log-run7.txt  earlier diagnostic log (different container variant)
```

---

## 0. TL;DR

1. **The game has a real controller bug**: `terra/dist/bundle.js` decodes a pad that Chromium
   reports with `mapping != "standard"` using the *raw DirectInput/hat* layout **only on Linux**;
   on Windows (i.e. Wine: GameNative / Winlator / Proton-on-Android) it decodes the same pad with
   the *W3C standard* table, which is wrong for it (right stick ↔ triggers, dead d-pad).
   `fix/gamepad-fix.js` fixes this by handing the game pads that really are in W3C order.
2. **On Linux that bug does not currently bite the user's pad**: with the 8BitDo Ultimate
   (USB, DInput mode, `2dc8:3106`) Chromium 123 reports `mapping:"standard"` with a correct
   layout, and the game reacts to it (verified). The fix is a no-op there (pass-through).
3. **On GameNative the game gets no gamepad at all** — Chromium on Windows reads gamepads
   only through XInput (`xinput1_4.dll`), GameNative feeds Wine through SDL/evshim, and
   Chromium never uses SDL. Verified in three container variants, with arch-matched DLLs,
   DirectInput on/off and `WINEDLLOVERRIDES=xinput1_4=n,b`. **No JS-level fix can help**:
   Chromium reports zero pads.
4. The pad state *is* reachable on the phone via GameNative's **UDP pad server on 127.0.0.1:7947**
   (protocol documented below) — but doing that from inside Chromium's renderer **crashes the
   game** (verified twice). It must be moved out of the renderer (NW.js browser process /
   `node-main`, or a helper process).
5. **Best long-term direction: a CrossAndroid-style native Android WebView port.** Alabaster Dawn
   uses the same engine lineage (`terra`) as CrossCode, the engine has an explicit
   `PLATFORM.BROWSER` mode, and the Node/Steam/NW.js API surface the game actually uses is small
   and enumerable (§5). That removes Wine/Box64 entirely and makes controller input native —
   the exact problem this whole investigation has been about.
   **Feasibility is now proven (§4.3): the game boots to the title screen in stock Chromium with a
   3 KB `window.require`/`nw` shim and zero uncaught exceptions, and a synthetic standard-mapped
   pad injected through `navigator.getGamepads` drives the menus (cursor + button activation).**

---

## 1. The game

* Steam appid **3110760** ("Alabaster Dawn", Radical Fish Games, Early Access).
* Linux install: `~/.local/share/Steam/steamapps/common/Alabaster Dawn`
  (`alabaster_dawn` + `lib/libnw.so` + `nw_*.pak` … = **NW.js** app).
* Window title reports `NW.js: 0.86.0`; in-page `process.versions.node = 21.1.0`;
  `lib/libnw.so` contains `Chrome/123.0.6312.87`. Treat Chromium as ~116–123 class.
* `package.json`: `main = terra/index.html`, window 1280x720, chromium-args:
  `--ignore-gpu-blacklist --force-device-scale-factor=1 --force_high_performance_gpu
   --disable-direct-composition --disable-background-networking
   --enable-experimental-web-platform-features --disable-features=Vulkan`
* Entry chain: `terra/index.html` → `terra/dist/bundle.js` (12.7 MB minified webpack bundle).
  Nothing else in the page (engine/game scripts are commented out in the HTML).
* Also present: `terra/greenworks/greenworks.js` + `terra/greenworks/lib/libsteam_api.so`
  (Steamworks; on Linux the log shows `[S_API] SteamAPI_Init(): Loaded .../steamclient.so OK`).
* User config dir (Linux): `~/.config/Alabaster Dawn` (Chromium profile; contains
  `Saves/`, `Default/Local Storage`, etc.) — i.e. NW.js profile + file saves.

### 1.1 Input code (bundle, ~lines 17600–17690 and 18185–18230)

```js
initGamepad() { this.isUsingGamepad = navigator.getGamepads != undefined; … }

updateGamepads() {                      // called every frame
    const rawPads = navigator.getGamepads();
    // prefers pads with mapping == "standard"; if any exists, only those are used
    // filter = g_options.get("gamepad-index")  → explicit pad index wins
    // sets leftAxisPad/rightAxisPad, pushes into allGamepads
}

getGamepadMapping(gamepad) {
    if (gamepad.mapping != "standard" && g_engine.os == OS.LINUX)
        return GAMEPAD_MAPPING_LINUX_WEIRD;   // raw DirectInput/hat layout
    return GAMEPAD_MAPPING_STANDARD;          // W3C standard layout
}
```

Mapping tables (verbatim structure):

* `GAMEPAD_MAPPING_STANDARD` — W3C: face 0–3, shoulders 4–5, triggers 6–7, Select 8, Start 9,
  L3 10, R3 11, D-pad 12–15, Home 16, Extra 17; axes 0–3 = LX, LY, RX, RY.
* `GAMEPAD_MAPPING_LINUX_WEIRD` — raw DirectInput/hat: shoulders 4–5, **triggers = axes 2/5**
  (`(v+1)/2`), Select 6, Start 7, L3 9, R3 10, **d-pad = hat axes 6/7** (neg/pos as buttons),
  axes 0/1 = LX/LY, **axes 3/4 = RX/RY**.
* Both tables are exactly what Chromium's `MapperXInputStyleGamepad` produces for an XInput pad,
  which is why the Linux fallback works for DInput-mode 8BitDo pads.

Other input notes: `PS_VENDOR_ID = "054c"` is only used for PS-touchpad features
(`hasGamepadTouch`, `isGamepadTouchClicked`). `GAMEPAD_ICON_STYLE` (XBOX/PS…) is chosen from
`gamepad.id`. Menus are **cursor-driven** (the game draws a mouse-like pointer and moves it with
the stick/d-pad), so "does the gamepad work" is visually observable as that cursor appearing.

### 1.2 Platform detection / boot (bundle ~lines 4760, 4774, 4882)

* `g_engine.platform`: `if (this.platform == PLATFORM.NWJS) return PLATFORM.NWJS; … return PLATFORM.BROWSER;`
  → **there is a BROWSER platform path**, with browser-specific asset roots / cache suffixes
  (`path = (nwjs ? ENGINE_CONF.NWJS_ROOT : ENGINE_CONF.FILE_ROOT) + path + (nwjs ? "" : getCacheSuffix())`)
  and `if (platform != PLATFORM.BROWSER)` branches elsewhere.
* Boot: `finalizeBootLoading()` → `g_resource.boot(listener)` → `g_system.startRunLoop()`.
  Gating on `window.XG_GAME_DEBUG || window.XG_WELTMEISTER` (extra WM modules) vs `window.XG_AUTO_START`.

### 1.3 Node / Steam / NW.js API surface actually used (measured from the bundle)

| API | Hits | Notes |
|---|---|---|
| `require('fs')` | 2 | real uses: `fs.existsSync`, `fs.readdir`, `fs.writeFile`, `fs.unlinkSync`, `fs.watch`, `fs.promises.{readFile,stat,rename,writeFile}` |
| `require('path')` | 3 | path joins for asset/save dirs |
| `require('vm')` | 1 | single use |
| `require('./greenworks/greenworks')` | 1 | only `greenworks.init` + `greenworks.activateGameOverlayToStore` |
| `localStorage` | 25 | 19 `getItem`, 8 `setItem` (engine has a browser storage path) |
| `nw.*` | 12 | `nw.App.argv`, `nw.App.quit`, `nw.App.dataPath`, `nw.Window.get/open`, `nw.Screen.screens`, `nw.Clipboard.get` |
| `process.*` | 2 | `process.versions['node'/'nw']` for platform detection |

Save-file paths seen in the bundle: `/Saves/`, `/Saves/Default/`, `/Saves/Backups/`,
`/Saves/Backups2/` with `fsProm.rename(...)` = backup rotation, `writeFile(... JSON.stringify …)`.
Also `"/Default\\Saves\\Default\\"` style strings (Windows path joins).

---

## 2. Bug #1 — wrong decode table on non-Linux (the "controller doesn't work" bug)

**Mechanism.** Chromium only guarantees the W3C layout when it sets `mapping:"standard"`.
For any pad it reports with `mapping:""` (unknown vendor/product, or its Linux mapping DB has no
entry) the axes/buttons are raw. The game handles that on Linux only. On Windows/Wine it uses the
standard table for a raw pad → mis-mapped controls. This is the same class of bug reported for
CrossCode in [nwjs#7006](https://github.com/nwjs/nw.js/issues/7006) ("right axis swapped with R2/L2").

**Chromium landmine worth knowing** (source-verified, M123
`device/gamepad/gamepad_standard_mappings_linux.cc`): when no mapper is found by name/product,
Chromium falls back to `MapperXInputStyleGamepad` for anything in its *XInput vendor list*
(`gamepad_id_list.cc`, e.g. `{{0x2dc8, 0x3106}, kXInputTypeXbox360}` = 8BitDo Ultimate).
That mapper maps triggers from axes 2/5, right stick from axes 3/4, d-pad from hat axes 6/7 —
correct for the user's DInput-mode pad, but it means Chromium can *claim* `standard` for a device
whose raw layout is DInput-ish. Both cases are handled by the fix below.

**Fix** — `fix/gamepad-fix.js`, injected via one `<script>` tag in `terra/index.html`:

* pads Chromium already reports as `standard` pass through untouched;
* non-standard pads are re-decoded into the W3C layout (byte-for-byte what Chromium's
  `MapperXInputStyleGamepad` would produce), so the game's standard table is always right;
* pads are returned from the same pooled objects (no per-frame allocation);
* extra sources for GameNative/Wine are included (shm file, UDP pad server) — see §3/§4;
* the whole file is wrapped in try/catch so it can never take the page down;
* `install.sh` patches `terra/index.html` (backup `index.html.gpf-backup`, `--revert` verified to
  restore the file byte-identically), `--game-dir`, `--mapping auto|standard|dinput`.

**Verification performed (Linux desktop):**

* virtual pad (uinput, `tools/vpad.py`) with the *same identity and raw layout* as the user's pad
  → Chromium reported it, the game switched to gamepad mode and drew its pointer (screenshots).
* the real 8BitDo connected → Chromium reports
  `8BitDo Ultimate Wireless Controller (STANDARD GAMEPAD Vendor: 2dc8 Product: 3106)`,
  `mapping:"standard"`, 4 axes / 17 buttons.
* `node fix/test-gamepad-fix.mjs` — re-decode table, pass-through, shm decode, UDP protocol,
  config pins: all pass.
* appending the script tag *after* `bundle.js` also works (the game looks `navigator.getGamepads`
  up every frame) — useful for installing inside a container without editing HTML precisely.

**Observation not fully explained:** with only the synthetic pad present (no physical pad),
Chromium exposed *nothing* to the page; once the physical pad was connected, both appeared.
Reproduced, cause unknown. Do not rely on uinput-only setups for conclusions.

---

## 3. GameNative (Android + Wine) — what was measured

App **GameNative 1.2.1** (`app.gamenative`, fork of Winlator) on **SM-F971B** (Galaxy Z Fold7);
also Winlator `com.winlator` 11.0 on an SM-S908U1. Game install inside the app:
`/data/user/0/app.gamenative/Steam/steamapps/common/Alabaster Dawn` (Windows build,
`alabaster_dawn.exe`), 672 MB, "Known config works on your GPU".

### 3.1 Instrumentation method (reusable!)

`fix/gpf-diagnose.js` is dropped in as `terra/gamepad-fix.js` **inside the container**; it appends
JSON lines to `D:\alabaster-dawn-fix\gpf-log.txt`, and `D:` is the phone's Downloads folder, so
the log is readable from the host with `adb shell cat /sdcard/Download/alabaster-dawn-fix/gpf-log.txt`.
File placement on the phone is done by the user via *game page → Options → **Open container***,
copying from `D:\alabaster-dawn-fix\` into `…\Steam\steamapps\common\Alabaster Dawn\terra\`.

### 3.2 Container environment (from inside the game)

```
WINEPREFIX            = /data/user/0/app.gamenative/files/imagefs/home/xuser/.wine
EVSHIM_WINE           = 1
EVSHIM_SHM_NAME       = controller-shm0
EVSHIM_SHM_ID         = 1
EVSHIM_MAX_PLAYERS    = 4
SDL_XINPUT_ENABLED    = 1
SDL_DIRECTINPUT_ENABLED = 0
process.arch          = x64        (game runs emulated under FEXCore/Box64)
Wine drives: A: = game dir, C: = prefix drive_c, D: = /storage/emulated/0/Download
             E: = /data/data/app.gamenative/storage, Z: = <imagefs> root (Wine clamps Z:\..)
```

Measured `sys32` DLLs by PE machine type: arm64ec set (167 KB `xinput1_4.dll`) on
`proton-9.0-arm64ec`; game's own provisioned set; x86_64 set (188 KB) on `proton-10.0-4-x86_64-1`.
All are **Wine builtins** (strings `../dlls/xinput1_3/main.c`, imports `hid.dll` + `setupapi.dll`),
not custom bridges — same for `windows.gaming.input.dll` (`../dlls/windows.gaming.input/gamepad.c`).

### 3.3 Result: `navigator.getGamepads()` is **always empty**

Runs (each with the physical pad connected and pressed), 100+ one-second samples:

| container | result |
|---|---|
| `proton-9.0-arm64ec` (+ GameNative's arm64ec input DLLs) | 0 pads |
| `proton-10.0-arm64ec-2` (Dredge's, which *does* get pads) | 0 pads |
| `proton-10.0-4-x86_64-1` (arch-matched x86_64 DLLs) | 0 pads |
| + `WINEDLLOVERRIDES=xinput1_4=n,b` | 0 pads |
| DirectInput API on or off, XInput on | 0 pads |

Why: Chromium on Windows has exactly three sources — **XInput** (`kXInputDllFileName =
"xinput1_4.dll"` hardcoded), **RawInput** (needs a real HID device), **WGI**
(`windows.gaming.input.dll`; Wine's is a shim over the same HID stack). GameNative feeds Wine via
**SDL/evshim**: `libevshim.so` (LD_PRELOAD) attaches an SDL *virtual* joystick in-process
(VID `0x045E`, PID `0x028E`, name "Xbox 360 Controller") from a 64-byte shared-memory struct.
Games that use SDL/DirectInput see it; Chromium never touches SDL, and the virtual pad has no HID
device, so XInput/RawInput/WGI all come up empty. Other games work because they are not Chromium.

### 3.4 Transports (documented from GameNative sources)

**(a) Shared memory (host → evshim).** Host (`WinHandler.java`) maps
`<app files>/gamepad_shm/gamepad.mem`; evshim (inside the Wine process) opens
`$EVSHIM_BASE_PATH/gamepad_shm/gamepad.mem` (default base `/data/data/app.gamenative/files`).
64 bytes, little-endian:

```
 0 u32 seq | 4 i16 LX | 6 i16 LY | 8 i16 RX | 10 i16 RY | 12 i16 LT | 14 i16 RT
16 15 x u8 SDL button states: 0 A 1 B 2 X 3 Y 4 Back 5 Guide 6 Start 7 L3 8 R3
                             9 LB 10 RB 11 DPAD_UP 12 DPAD_DOWN 13 DPAD_LEFT 14 DPAD_RIGHT
31 u8 POV hat | 32 i16 rumbleLo | 34 i16 rumbleHi | 40 i32 connected
```

The launcher *also* pre-creates `<imagefs>/tmp/gamepad{,1,2,3}.mem` — these are **static**
(`seq` never moves, `connected` always 0) and are a red herring.

**(b) UDP pad server (host → Wine-side clients).** Binds UDP **7947** (client port 7946);
little-endian, max 64-byte datagrams:

```
-> [u8 8][u8 isXInput][u8 notify][i32 processId]                  GET_GAMEPAD
<- [u8 8][i32 gamepadId][u8 dinputMapperType][i32 nameLen][name]
-> [u8 9][i32 gamepadId]                                          GET_GAMEPAD_STATE
<- [u8 9][u8 enabled][i32 gamepadId][i16 buttons][u8 povHat]
      [i16 LX][i16 LY][i16 RX][i16 RY][u8 LT][u8 RT]
```

`buttons` bitfield: 0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 Back, 7 Start, 8 L3, 9 R3.
`povHat`: 0 up, 2 right, 4 down, 6 left, −1 none. Sticks are −32767..32767, triggers 0..255.
`GET_GAMEPAD` is gated by the container's `PreferredInputApi` (AUTO/DINPUT/XINPUT/BOTH — note the
odd logic: `XINPUT` rejects `isXInput=1` clients, `BOTH` rejects `isXInput=0`), while
`GET_GAMEPAD_STATE` is not gated (it only needs a live controller).

**File-based access is impossible from the game**: the live shm file is on the Android side
(`/data/user/0/app.gamenative/files/gamepad_shm/…`), outside every Wine drive, and Wine clamps
`Z:\..`. Android's own file picker (GameNative Drives → `+`) also cannot see app-internal storage,
so a drive mapping cannot be added either. **[verified]** The phone's shell *can* talk to the UDP
server (`nc -u` exists) — that was the test in progress when this session ended.

### 3.5 What failed on the phone, and why

* `fix/gamepad-fix.js` with **UDP inside the renderer** → the game **crashed on load**, twice.
  Log evidence: `load`(stage `shim-loaded`) → `tick1` → **stop** (no further frames). The same
  code runs fine on Linux NW.js, so socket creation in Chromium's Wine renderer is the suspect.
  A run with `gnUdp:"off"` also died — but it is unverified whether that config was actually the
  one in effect (two similarly-named JSONs were involved). **Re-test with the config
  double-checked before drawing conclusions.**
* Everything else on the phone is "no pads", per §3.3.

### 3.6 Container state left behind (revert on request)

| setting | original | current |
|---|---|---|
| Wine Version | `proton-9.0-arm64ec` | `proton-10.0-4-x86_64-1` |
| Controller → Enable DirectInput API | on | off |
| Environment | (defaults) | `WINEDLLOVERRIDES=xinput1_4=n,b` added |

The game is currently back to **vanilla** (all `gamepad-fix.*` removed from `terra/`; user
confirmed it boots fine).

### 3.7 Next steps for the Android/Wine route

1. **One-toggle test never tried: Controller tab → `Use Steam Input` = ON** (GameNative ships
   `assets/steaminput/*.vdf`, e.g. `"button_a" → "xinput_button A"`). It is a different route into
   Wine and matches the user's original hearsay that Steam Input made it work.
2. Move the UDP query **out of the renderer**: NW.js `"node-main"` script (browser process,
   unsandboxed) that polls 7947 and exposes state to the page (e.g.
   `nw.Window.getAll()[0].window.__gnPad = state`), with the renderer-side shim only *reading*
   it. Same protocol as §3.4b.
3. Verify the protocol from the host first: with a container running,
   `adb shell "printf '\x08\x01\x00\xd2\x04\x00\x00' | nc -u -w 3 -q 2 127.0.0.1 7947 | xxd -p"`
   (request `GET_GAMEPAD`, `isXInput=1`, `notify=0`, pid 1234). If the reply carries a
   `gamepadId > 0`, then `nc -u` can also fetch state: `printf '\x09<id LE>' | nc -u … 7947`.

---

## 4. CrossAndroid — assessed as the model for a native port

`https://gitlab.com/Namnodorel/crossandroid` — "port/wrapper app to run CrossCode on Android",
Kotlin, last activity 2024-11, **no license declared** (treat as all-rights-reserved: ask the
author or reimplement; the small classes are easy either way).

Architecture (from source):

* plain **Android WebView**, game served from a **virtual `https://appassets.androidplatform.net`
  origin** via `shouldInterceptRequest` (correct MIME types are required for ES module imports /
  `fetch`; also enables service workers). Google documents the technique.
* `GameWrapper` injects a JS interface named `CrossAndroid`; `GameWebViewClient.onPageFinished`
  → `onPageLoaded()` → features run; if no mod loader, `doStartCrossCodePlz()` is called.
* **Controller glue is 5 lines of JS**:
  ```js
  navigator.getGamepads = function(){ return JS_GAMEPADS_VAR; };
  ```
  fed by `GamepadJsonBridge` (Kotlin) which emits a **W3C-standard gamepad JSON**
  (`id/index/connected/timestamp/mapping:"standard"/axes/buttons`, standard indices per the W3C
  diagram: left stick 0/1/L3=10, right stick 2/3/R3=11, d-pad 12–15, face 3/0/2/1, bumpers 4/5,
  triggers 6/7, select/start 8/9, center 16). On-screen layouts (combat/menu/direct) drive the
  same bridge; physical pads go through Android `InputDevice`.
* Extras: save import/export as "Save Strings" (F10 dialog on PC), haptics on screen shake,
  overlay scaling/transparency, cutscene auto-disable (needs game-internal hooks → they use
  CrossCode's mod loader CCLoader; the repo requires CCLoader + `cc-font-fix`).

### 4.1 Why Alabaster Dawn is a good fit

* Same engine lineage (`terra`), same NW.js app shape, and the engine **has a BROWSER platform**
  (§1.2) with localStorage storage paths — so Node emulation may be minimal.
* The API surface to shim is small (§1.3): `require('fs'|'path'|'vm'|'./greenworks/greenworks')`,
  `nw.*` (6 members), `process.versions`, localStorage.
* No mod loader needed for the core port: our glue can be injected by a single `<script>` tag in
  `terra/index.html` (the mechanism `fix/gamepad-fix.js` already uses, verified working).
* Payoffs: no Wine/Box64 (native ARM64 WebView — far better perf than GameNative), and controller
  input becomes native (Android pad → JS gamepad), which is exactly the problem unsolved so far.

### 4.2 Glue design sketch

```
terra/index.html  (+ <script src="ada-shim.js"></script>)
   ada-shim.js
     - define window.require(name) for 'fs' | 'path' | 'vm' | './greenworks/greenworks'
       fs  -> bridge-backed file API (Android app files dir) or localStorage VFS
       vm  -> minimal stub
       greenworks -> stub {init:()=>false, activateGameOverlayToStore:()=>{}}
     - define window.nw = { App:{argv:[],quit(),dataPath:"Z:\\" }, Window:{get:()=>({…}),open:()=>…},
                            Screen:{screens:[{…}]}, Clipboard:{get:()=>({…})} }
     - define window.process = { versions:{ node:'21.1.0', nw:'0.86.0' }, platform:'browser' }
       (engine platform detection reads these; check which combination makes it choose BROWSER)
     - navigator.getGamepads = () => [ synthPadFromBridgeJSON(CrossAndroid.getGamepadJson()) ]
       (reuse the W3C mapping logic from fix/gamepad-fix.js)
Android side (Kotlin): WebView + asset loader/virtual origin + JS bridge
     getGamepadJson(): InputDevice (physical pad, incl. DInput/HID gamepads) and/or on-screen
     layout -> the same standard-index JSON; save-file API for the fs shim; clipboard; quit.
```

### 4.3 De-risking experiment — **DONE, POSITIVE** (2026-09-23)

Serve the game over HTTP into a plain browser (same environment a WebView gives):

```bash
cd "/home/moroni/.local/share/Steam/steamapps/common/Alabaster Dawn"
python3 -m http.server 8099 --bind 127.0.0.1
# then load http://127.0.0.1:8099/terra/index.html
```

**Result: the game boots to the title screen in stock Chromium, with a 3 KB shim and no build
changes.** `tools/ada-browser-shim.js` is injected via `page.evaluateOnNewDocument` *before*
`dist/bundle.js`; then the game runs its full splash sequence (Radical Fish → HTML5/BMVI →
Early Access dialog → title menu with New Game / Load / Options / Exit), all fonts correct,
`window.manifest.engine.platform == "browser"`, `os == "Linux"`, one 1280×720 WebGL canvas,
**0 uncaught exceptions and 0 console output** for the whole boot.

Why it failed silently before (now explained, not a mystery): the bundle has exactly **one**
webpack external, module `79896` = `module.exports = require("fs")`. The file and storage modules
do `const fs = window.require && __webpack_require__(79896)` and then read **`fs.promises` at
module scope** (bundle line 48174/48175). In a plain browser `window.require` is undefined, so
`fs` is `undefined` and that module-level read throws `TypeError: Cannot read properties of
undefined (reading 'promises')` — an *uncaught* error during bundle evaluation. It was invisible
to the earlier probe because that probe ran in an **isolated JS world** (Puppeteer
`evaluateOnNewDocument` markers are invisible there, `window.g` reads as empty); capture
exceptions with **CDP `Runtime.exceptionThrown`**, not with a page-level `window.onerror`
installed from the automation context.

Minimal requirements (all measured):

| shim piece | needed for |
|---|---|
| `window.require` truthy, returning `fs` \| `path` \| `vm` \| `./greenworks/greenworks` | unblocks the `fs.promises` throw; only `fs` is load-bearing at boot |
| `fs.existsSync`, `fs.mkdirSync` | `Storage.preparePaths` |
| `fs.statSync`, `copyFileSync`, `rmSync`, `unlinkSync` | save-migration path (`checkIncorrectSaves`) |
| `window.nw` (`App.dataPath`, `Window.get().on/close`, `Screen.screens`, `Clipboard`) | `Storage` reads `nw.App.dataPath`; addons call `nw.Window.get().on("close", …)` at boot |
| `window.process` left **undefined** | `Engine.getPlatform()` returns NWJS if `window.require && typeof window.process == "object"`; undefined ⇒ `PLATFORM.BROWSER` |

Reproduction (headless Chromium via the harness' browser tool):

```js
await page.evaluateOnNewDocument(shimSource);            // tools/ada-browser-shim.js
await page.goto("http://127.0.0.1:8099/terra/index.html");
// ~10 s to finish the asset burst -> then the game's own intro sequence -> title screen
```

Measured load path (2026-09-23, see §8 for the full breakdown): the boot pulls **1734 requests**
cold (494 real files + **1240 `.flac` 404s**), and `python3 -m http.server` serves all of them in
**~2.5 s** (843–854 req/s). Swapping in an HTTP/1.1 keep-alive server changed nothing measurable
(10.1 s vs 12.4 s to quiet on identical cold caches — noise). **Asset loading is not a
bottleneck and the harness server is not "single-threaded"** (it has been `ThreadingHTTPServer`
since Python 3.7). What takes the time afterwards is the game's fixed intro sequence; see §8.

**Gamepad half of the test also passes.** Overriding `navigator.getGamepads` at runtime with a
single W3C-standard pad (`mapping:"standard"`, 4 axes, 17 buttons) makes the game switch to
gamepad mode and draw its mouse-like cursor; deflecting the left stick moves it, and pressing
button 0 (A) *activated the highlighted button* (closed the Early Access dialog). So the exact
CrossAndroid-style glue (§4.2) works on this game: a synthetic standard-mapped pad fed from the
Android side is indistinguishable from a real one to the game.

Notes / non-blockers:

* The only 404s are `media/audio/sfx/**.flac`: the engine probes `.flac` first for **every** sound
  but the build ships **0 `.flac` and 1075 `.ogg`**. That is **1240 of the 1734 boot requests
  (71 %)** — harmless over `http.server` (2.5 s total) but the dominant *repeat-launch* network
  cost, because 404s are not cacheable. Fix is a shim/port concern, not a game-file change: §8 item 1.
* `AudioContext.state` stays `"suspended"` under the automation browser. Not a blocker for boot
  (the splash sequence and menus run fine), but the Android WebView must set
  `mediaPlaybackRequiresUserGesture=false` (or resume the context on first touch) or the game
  will be silent.
* No NW.js API beyond the list above was touched during boot, so the §4.2 shim surface is
  confirmed sufficient for boot — save persistence is the remaining unknown.

### 4.4 Effort estimate

Prototype in **days**: WebView skeleton ~1 day; `require`/`nw`/`process`/`greenworks` shim +
saves 1–2 days (iterate against the in-page error/console collector); gamepad bridge +
`getGamepads` override hours (logic already written); then packaging, save import/export, optional
overlay. Caveats: no Steam (achievements/cloud/overlay gone), fonts may need a CrossCode-style
font fix, and the on-screen layout must be designed for this game.

---

## 5. Test tooling built (reusable)

* **Virtual gamepad (Linux)** — `tools/vpad.py <xbox|dinput|unknown> [name]` creates a uinput pad;
  states are driven by writing JSON to `/tmp/gp/cmd_<mode>.json`, e.g.
  `echo '{"axes":{"7":32767}}' > /tmp/gp/cmd_xbox.json` (axes order X,Y,Z,RX,RY,RZ,HAT0X,HAT0Y;
  buttons 0..10). Run it under the harness' process supervisor so it survives the tool call.
  `xbox` = `Microsoft X-Box 360 pad` (045e:028e), `dinput` = 8BitDo identity (2dc8:3106),
  `unknown` = 1234:5678 (Chromium reports `mapping:""`).
* **In-page probe** — a `<script>` in `terra/index.html` that writes JSON lines (raw vs shimmed
  `navigator.getGamepads()`, `document.hasFocus()`, internals) to `/tmp/gp_out.json` via
  `require('fs')`. This is how the desktop behaviour was measured.
* **Instrumented build** — `fix/gpf-diagnose.js`: same as the fix plus staged logging
  (`shim-loaded`, `tick1`, `after-Nms`) and a dump of the GameNative sources (shm candidates, UDP
  registration/state, env vars, drive listings). Writes to `D:\alabaster-dawn-fix\gpf-log.txt`.
* **strace** (`strace -f -qq -e trace=openat,ioctl,read -y ./alabaster_dawn`) proved Chromium
  opens `/dev/input/js*` and polls it — useful for "does Chromium even see the device" questions.
* **uinput/joydev sanity check**: `python3` with `fcntl.ioctl(fd, JSIOCGNAME/… )` on
  `/dev/input/js*` (permissions: `/dev/uinput` is root:input but ACL grants the user; devices need
  `ID_INPUT_JOYSTICK=1`, which udev sets automatically for uinput gamepads).
* **Screenshots on the phone**: `adb exec-out screencap -p` prepends a warning line, so slice from
  `\x89PNG` before writing the file; then `magick … -resize 700x` to view. Compare frames with
  `compare -metric AE a.png b.png null:`.
* **Driving the GameNative UI from adb** (used a lot): `adb shell input tap X Y` with coordinates
  scaled from a 700-px-wide screenshot by ×3.497 (screen 2448×1848); typing with
  `input text`; `BACK`/`keyevent` quirks; `dumpsys window | grep mCurrentFocus` to know the
  foreground app. Caution: a tap intended for a dialog can land in the game/other apps.
* **Reading the phone log**: `adb shell cat /sdcard/Download/alabaster-dawn-fix/gpf-log.txt`
  (JSON lines; run ids group a launch).

---

## 6. Open questions / unknowns

1. Why Chromium exposed no pads when only the synthetic pad existed (desktop) — and why the
   physical pad made both visible.
2. Was the phone crash caused by the renderer socket, or was the `gnUdp:"off"` config never
   applied? (Re-test with the log printing the effective config.)
3. Does the game tolerate a failing `greenworks.init` (port blocker)? Test by stubbing greenworks
   in the Linux build first.
4. ~~Which `process`/`nw`/`require` combination makes the engine choose `PLATFORM.BROWSER` and
   boot (§4.3)?~~ **Answered:** `window.require` truthy (returning an `fs` shim) + `window.process`
   left undefined + a `window.nw` stub. The engine then picks `PLATFORM.BROWSER` and boots to the
   title screen. Remaining unknown in this area: whether save persistence works over the fs shim.
5. Do Alabaster Dawn's fonts render acceptably in Android WebView (CrossCode needed `cc-font-fix`)?
6. Is CrossAndroid's author open to relicensing / collaborating? (No license file in the repo.)

---

## 7. Environment facts (for the next session)

* Desktop: CachyOS, KDE Wayland, RTX 4070 Max-Q, Steam at `~/.local/share/Steam`.
  Game dir as in §1. My fix is **installed in the desktop build** (`terra/gamepad-fix.js` +
  `gamepad-fix.json` + tag in `terra/index.html`); `fix/install.sh --revert` undoes it.
* Controller: 8BitDo Ultimate Wireless / Pro 2, USB, DInput mode — `2dc8:3106`, 8 axes
  (X,Y,Z,RX,RY,RZ,HAT0X,HAT0Y), 11 buttons, vendor/product also present in Chromium's XInput list.
* Phone: SM-F971B (Z Fold7), GameNative 1.2.1, game installed, controller = Switch Pro (Bluetooth)
  or the 8BitDo. adb across USB, `transport_id` changes per session.
* The §4.3 experiment was run with `python3 -m http.server 8099 --bind 127.0.0.1` in the game dir
  (stopped again afterwards; restart it to re-run). The shim lives in `tools/ada-browser-shim.js`.
* Steam Input is enabled for appid 3110760 in Steam's controller config (mask 0x1000).

---

## 8. Roadmap — load path / port backlog (added 2026-09-23)

Background: "single-threaded loading" was raised as a suspect for the slow boot. **Measured — it
is not real.** Cold-cache boot against `python3 -m http.server` (Python 3.11, which *is*
`ThreadingHTTPServer`): **1734 requests in ~2.5 s**, 843–854 req/s, 494 × 200 + 1240 × 404. A/B
against `tools/serve-fast.py` (identical handler, `HTTP/1.1` keep-alive, cache disabled in both)
gave **10.1 s vs 12.4 s** to the asset stream going quiet — i.e. no difference. The remaining
~90 s+ before the title screen is the game's **own intro sequence**, not I/O. So: spend nothing on
the harness server; the real items are below.

Ordered by payoff, all of them **outside the game files** (no `terra/**` edits).

1. **Make the `.flac` probes free (highest payoff, highest certainty).** Per boot the engine
   requests 1240 `.flac` URLs that do not exist (`find terra/media/audio -name '*.flac'` = 0;
   `.ogg` = 1075) and falls back to `.ogg`. Over a local socket that is free; in the port it is
   not, because `WebViewAssetLoader`/`shouldInterceptRequest` only intercepts what it is told to —
   an unanswered virtual-origin path falls through toward the network. Two options, both shim-side:
   * **conservative:** answer `*.flac` with an immediate in-process 404 (no network, no decode);
   * **aggressive:** rewrite `*.flac` → `*.ogg` in a `fetch`/`XHR`/`Audio.src` wrapper. That
     wrapper shape is already written and exercised by the measurement recipe at the bottom of
     this section (the `count/done/idle` counters patch exactly those three APIs), so it only has
     to be turned into a rewrite. Needs an on-device check that the decoder accepts OGG bytes
     under a `.flac` URL (headless Chromium can't verify it — `AudioContext` is suspended there).
   Same wrapper is the cheapest place to do it: no game-file change, and it also covers the
   GameNative/WebView path.
2. **Port-side asset serving (do this instead of any server tuning).** Serve from
   `WebViewAssetLoader` (in-process; no sockets at all), keep the WebView HTTP cache on
   (`setCacheMode` + a persistent cache dir), and send `Cache-Control: immutable` for
   `terra/dist/bundle.js` (12.7 MB) and the atlases. Observed behaviour worth leaning on: with a
   warm Chromium cache the *only* requests that still hit the wire were the uncacheable `.flac`
   404s, so item 1 is what repeat launches actually pay for.
3. **Time-to-title: out of scope as long as we don't touch game files.** The asset burst is done
   at ~10 s; everything after that is the intro sequence (Radical Fish logo → HTML5/BMVI logos →
   Early Access dialog → title). Unverified hypothesis: the sequence may be paced by splash audio,
   and `AudioContext` is `suspended` under automation; if so, resuming audio early would shorten
   it. Cheap test for whoever picks this up: `Runtime.evaluate` with `userGesture: true` calling
   `g.audio.context.resume()` right after load, then compare time-to-title. Otherwise skipping the
   intro is a game-side change (violates the no-game-files constraint) — leave it.
4. **Harness convenience only (no roadmap weight).** `tools/serve-fast.py` = `SimpleHTTPRequestHandler`
   with `protocol_version="HTTP/1.1"` and quiet logs. Keep it for cleaner re-runs; it is *not* a
   performance fix (see the A/B above). Do not "fix" the loader by adding `?nocache=` — that is
   `ENGINE_CONF.NOCACHE` and it makes caching worse.

Measurement recipe (reusable, and the reason the numbers above are trustworthy):
`python3 tools/serve-fast.py 8100 "<game dir>"`, inject `tools/ada-browser-shim.js`, and count
loads **page-side** with the `fetch`/`XHR`/`Image.src`/`Media.src` wrappers (the counters in the
shape used for `count/done/idle`). Prefer **CDP `Runtime.exceptionThrown`** for errors and
**the server's own request log for ground truth timings** — a page-side counter undercounts
(1107 vs 1734) and an isolated-world probe sees nothing at all (§4.3).

---

## 9. Native Android port — milestone 1 built, and the performance ledger (2026-09-23)

**Read §9.4 before optimising anything.** It lists the dead ends with the numbers that killed
them, so they are not retried.

### 9.1 What exists now

Test device for everything in §9: **SM-S908U1** (Galaxy S22 Ultra, Android 16 / API 36, Adreno 730),
controller = Switch Pro over Bluetooth. The desktop-shim checkpoint (§3 of the plan) ran in stock
Chromium on the host.

`android/` (Gradle root + `:app`, package `io.github.moronigranja.alabasterdawn`, one runtime dependency
`androidx.webkit:webkit:1.17.1`, `minSdk 26`, **no permissions**). Sources: `PortActivity.kt`
(pre-game screen with two SAF pickers, index thread, WebView, gamepad dispatch, renderer-crash
rebuild, immersive fullscreen), `GameFiles.kt` (tree index: 286 dirs / 2652 files in 7–9 s),
`GameAssetHandler.kt` (serves the picked tree at `https://appassets.androidplatform.net/game/`,
in-process 404s, in-memory byte rewrites), `FsBridge.kt` (`/saves` → picked saves tree, Steam
layout; everything else → game tree read-only), `Gamepad.kt` + `GamepadState.kt`, `AdaBridge.kt`,
`assets/ada-shim.js`, and `app/src/test/.../GamepadStateTest.kt` (7 JVM tests, all green).

Device state during the session: `Download/AlabasterDawn` (game files), `Download/Dsves` (saves,
Steam layout preserved, `Continue` on the title screen from the auto-save).

### 9.2 Device-only fixes that are load-bearing — do not remove

| symptom | cause (measured) | fix |
|---|---|---|
| Black screen, crash reading `enableiOES` | Android WebView's **native GLES** backend (Adreno 730, `WebGL 2.0 (OpenGL ES 3.0 Chromium)`, only 13 extensions) has no `OES_draw_buffers_indexed`, which `System.initDom` reads into `g_glBufferExt`; every pass then calls `enableiOES`/`blendFunciOES` | shim installs an index-0-exact fallback in `getExtension`. **Argument order matters**: `enable/disableiOES(target, index)` take the *target* first, the others take the index first — getting this wrong silently disables blending (title screen renders with black boxes) |
| `Attribute does not exist!` while building billboard geometry | Adreno drops the declared-but-dead `a_barycentricIdx` (the fragment shaders that declare `v_barycentric` only mention it in commented-out code) | one unreachable, data-dependent use of the varying injected into `solid`/`solid-back`/`water-plane` `.frag`, **only when the same-named vertex shader declares `out vec3 v_barycentric;`** (`plane-depth`/`fx-decal` do not — patching them breaks the link). Uses `return`, not `discard`, to keep early-Z |
| `navigator.getGamepads` replaced by the user's `gamepad-fix.js` | that script rewrites it for DirectInput/GameNative pads | shim installs its override as a non-configurable accessor whose setter logs and ignores later assignments |
| Resolution option too coarse (integer scales 1/2/3/4/6) | 1280×720 saturates the GPU; 640×360 leaves it at ~55 % | `RESOLUTION_MAP` rewritten in the served bundle to `[1, 1.5, 2, 3, 4]` + the option's labels relabelled in `data/database/options.json` (`640x360/960x540/1280x720/1920x1080/2560x1440`) + that block's default set to 0. Rung 0 keeps its old meaning, so saved choices are unaffected |

Every served-byte rewrite is **anchored**: if an anchor is missing (game update) the handler logs
`unmodified` / `could not relabel` and serves the original bytes. Game files are never edited.

### 9.3 Bugs that only real hardware could find

* `GamepadState.publish()` read the 4-entry hat array with index ≥ 4 → `ArrayIndexOutOfBounds` on
  the **first** gamepad event (app died, Samsung "Clear cache" dialog). Fixed with a bounds-checked
  helper; `GamepadStateTest` now presses/releases all 17 indices (fails on the old code). The
  Android event layer had been unreachable before a pad existed.
* The Switch Pro Controller reports its right stick on **RX/RY**, not Z/RZ (`dumpsys input`:
  `ABS_X`, `ABS_Y`, `ABS_RX`, `ABS_RY`) → the axis lookup prefers `Z/RZ` and falls back to `RX/RY`.
* Digital triggers (Switch Pro's ZL/ZR = `KEYCODE_BUTTON_L2/R2`) map onto W3C trigger indices 6/7.
* Y sign confirmed on hardware: pushing the stick up gives a negative Android Y and the character
  moves the right way, so `Y_SIGN = 1` is correct.
* **The game kept playing behind the launcher** — music audible, renderer at ~19–20 % CPU
  (`top` on the app's `webview:sandboxed_process`) and its AudioTrack still `(active)` in
  `dumpsys media.audio_flinger` 12 s after HOME. Cause: the engine pauses itself only from the
  page's `blur` (`g_system.setWindowFocus(true)` → `AudioContext.suspend()` + `runInner` returns
  before any update/draw), and an Android WebView dispatches neither `blur` nor `focus` when the
  app leaves the foreground. `PortActivity` now dispatches them itself
  (`window.dispatchEvent(new Event('blur'|'focus'))`) on `onPause`/`onResume`, blur first so the
  suspension is not queued behind the `pauseTimers()` that follows.

  A/B on the same build pair, same menu scene, launcher on screen (pre-fix APK rebuilt from the
  stashed source, then restored):

  | | pre-fix fg | pre-fix bg | fixed fg | fixed bg |
  |---|---|---|---|---|
  | GPU busy (`gpubusy`) | 75.9 % | 0 (counter reads `0 0`, clock parked at 222 MHz) | 76.0–76.2 % | 0 (same) |
  | CPU app / renderer | — | 2–3 % / 5–7 % (19–20 % renderer while in-gameplay) | 42–45 % / 27–32 % | **0.0 % / 0.0 %** |
  | AudioTrack | `(active)` | `(active)`, session only ended when the process was replaced | `(active)` | `AT::remove` ~0.4–1 s after HOME, `(idle)` |

  **GPU was never the background cost**: a hidden WebView stops producing frames either way, so
  `gpubusy` reads 0 in both builds — the fix's win is the CPU + the audio pipeline (what you hear).
  Independent confirmation that pre-fix audio really outlived the foreground: `dumpsys
  media.audio_flinger` logged `frozen-while-active: 1 last: 23:50:58` for the app's uid — the audio
  framework freezing a still-active track when the process was later replaced. Post-fix sessions
  show `AT::add` (boot) → `AT::remove` (HOME) and no freeze.

### 9.4 Dead ends — measured, do not retry

| attempt | measurement | verdict |
|---|---|---|
| Compositor upscale filtering (`image-rendering: auto` vs `pixelated`, live time-aligned A/B) | 720p: 99.9 % vs 99.9 % GPU; 360p: 53.9 % vs 54.1 % | compositor work is free — leave the upscale path alone |
| Pad "revision counter" to skip the per-frame JSON marshal | `getGamepadRevision()` int = **0.31 ms**, `getGamepadJson()` 576 B = 0.23 ms, pure JS = 0.0002 ms | the JS↔Java **crossing** costs ~0.25 ms whatever the payload; a revision poll adds a call without removing one (two crossings per frame while input is active) |
| Opaque canvas (`alpha:false` via a `getContext` wrapper) | could not be A/B'd cleanly (the app's document-start script wraps *after* an injected override), and back-to-back boots of one build at fixed 960×540 gave 51.3 fps/92.9 % vs 58.8 fps/99.9 % | ±7 fps / ±7 pp **boot-to-boot noise** dwarfs the expected sub-1 % effect; reverted rather than shipped on theory |
| Pinning canvas CSS size to the drawing buffer plus page zoom | shrank Resolution 640×360 into a small centred picture; the diagnosis behind it was wrong | the engine owns canvas layout; reverted (comment kept in `ada-shim.js`) |
| Shader keep-alive `discard` vs `return` | 720p: 99.9 % GPU both, equal fps | no performance difference; `return` kept on principle (early-Z) |
| Vulkan | WebGL2 runs on **native GLES 3.0** (`WebGL 2.0 (OpenGL ES 3.0 Chromium)`, Adreno 730, 13 extensions); `/data/local/tmp/webview-command-line` with `--use-angle=vulkan` changed nothing; `GraphicsEnvironment: … is not listed in ANGLE allowlist or settings, returning default` | not selectable for a shipped app; only the *compositor* uses Vulkan (`AdrenoVK-0: /vendor/lib64/hw/vulkan.adreno.so`) |
| Offloading work to the CPU | CPU 0.5–0.7 of 8 cores while the GPU is at 91–99.9 % | rasterisation cannot move to the CPU. The only CPU-side move that cuts GPU work is **overdraw sorting** (untried; means patching the engine's draw order from outside — needs explicit approval) |
| 120 Hz | engine is a 60 Hz fixed step (`SYSTEM_CONF.FPS = 60`, `frameSkip`) | a faster vsync is *more* GPU work per game frame, not more game frames |
| "Black square" over the menus | fixed position, canvas-drawn per `elementsFromPoint`, absent once a pad is used | **not a port bug** — it was another app's floating window. `adb screencap` composites other apps' windows, and a system overlay never appears in the page DOM |

### 9.5 Performance ledger

| resolution (game option) | internal | GPU busy | fps (character walking) |
|---|---|---|---|
| 640×360 | 640×360 | 53.8 % (52.9–54.3) | **59.9** (p50 16.7 ms, p95 19.8) |
| 960×540 (new rung) | 960×540 | 91 % | **60.0** sustained over 5 × 12 s |
| 1280×720 | 1280×720 | **99.9 % (flat)** | 38–42 cool / 19–21 throttled |

CPU never limits (≈0.5–0.7 core of 8; `Chrome_InProcGp` ~16 % is command translation). Thermals
dominate sustained play: `Thermal Status: 3`, `SKIN 44.8 °C`, `BAT 42 °C` *while charging*; the same
binary at 720p measured 42 fps cool vs 19–21 fps hot. Practical: don't charge while playing, Game
Booster performance mode, prefer 640×360 (54 % GPU) unless the sharper 960×540 is wanted.

### 9.6 Measurement recipes (these work — reuse them)

* **GPU busy** = `/sys/class/kgsl/kgsl-3d0/gpubusy`, two numbers, `busy` and `total`, µs **inside a
  rolling ~1 s window** (`total` hovers around 1 004 000 and is *not* monotonic) — so the ratio
  comes from **one read**: `busy/total * 100`, sampled ≥ 10× at ~0.4 s, reported as median + min/max.
  **Never diff two reads** (the window slides and wraps: deltas produce nonsense like 455 189 %).
  When nothing is drawing the file reads literally `0 0` (ratio undefined → report 0) and
  `clock_mhz` parks at its minimum (222 MHz; 282 MHz is the working point at 640×360). It is
  **system-wide** — there is no per-process GPU attribution on this driver. This is the sensitive
  metric; it moves within a second.
* **Who owns the screen** = `dumpsys activity activities | grep -m1 ResumedActivity`; needed before
  any "background" GPU/CPU number, because a launcher that is itself animating is not a baseline.
  A *hidden* WebView stops producing frames, so `gpubusy` reads 0 with the app backgrounded whether
  or not the game is still simulating — judge background cost by CPU + the AudioTrack, not by GPU.
* **Per-thread CPU** = `/proc/<pid>/task/*/stat` `utime+stime` deltas, `CLK_TCK = 100`. The app is a
  single process here (in-process GPU threads: `Chrome_InProcGp`, `RenderThread`, `VizWebView`).
* **Is it still running when backgrounded?** = `dumpsys media.audio_flinger`, grepped for the app's
  uid: WebAudio's AudioTrack is listed with its `AT::add`/`AT::remove` history and a live
  `(active)`/`(idle)` state. `dumpsys audio`'s `players:` list is MediaPlayer/`AudioPlaybackConfiguration`
  only and shows **nothing** for WebAudio — don't trust it for "is the game silent". Pair it with
  `top -b -n 3 -d 1 -o PID,%CPU,ARGS` on the app + its `webview:sandboxed_process` (foreground
  41 %/29 %, background 0.0 %/0.0 % after the blur/focus fix in §9.3).
* **Frame rate** = `requestAnimationFrame` deltas inside the page (p50/p95), never an average over a
  boot (the boot is GPU-heavy and skews it).
* **Noise floor**: ±7 fps and ±7 pp GPU between boots at a fixed resolution → only chase changes that
  move GPU busy by >10 pp.
* **Hold the scene constant**: after each reload the game resumes a different state (title vs
  in-game). Drive gameplay from the console with `Input.dispatchKeyEvent` (`KeyD`/`KeyW`; the engine
  reads `event.code`) so both phases see the same load.
* **adb input limits**: `adb shell input gamepad keyevent …` never reached
  `PortActivity.dispatchKeyEvent`, and `input` cannot inject joystick axes at all — no synthetic pad
  without real hardware.
* **WebView devtools**: `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` must be
  re-created after every app restart, and it is a **host-side** command (`adb shell forward` is a
  silent no-op). CDP `Page.captureScreenshot` returns black on this WebView → use
  `adb exec-out screencap -p` (slice from `\x89PNG`).
* **Harness gotchas**: `page.evaluate` runs in an isolated world (no `window.g`, no shim globals) —
  use CDP `Runtime.evaluate` with the *default* context and re-discover context ids after a reload.
  In Python, `tab.run(code)` never invokes an arrow-function string (it returns the source) — wrap
  in an IIFE or use the JS runtime.
* **Where options live**: the engine's *local* options (`language`, `liveLocale`, `font-bitmap`,
  `pixel-size`, `fullscreen`) are in WebView `localStorage["xg_local_options"]` and mirrored into
  `System.save`; editing the save file alone does **not** change the booted value.
* **Saves**: verified with the real rotation chain on device — `Save_ID_auto.save` at
  38902/38900/38864 B across `Default`/`Backups`/`Backups2`, plus `System.save` revisions, names
  intact (no `(1)` suffixes). The in-game *Load* list was never eyeballed (needs a manual save);
  everything underneath it is verified.

### 9.7 What 1080p would cost, measured per frame (2026-09-26)

The question behind "can the S22 do 1080p at 60": 720p is already flat at 99.9 % GPU (38-42 fps cool)
and the panel's own mode is 1080x2316 at 60 Hz, so the only way up is a multiple of GPU work per
second. §9.5's curve gives the *device* answer for a scene; `tools/probes/frame-pass-cost.js` gives the
*engine* answer for the work: one `clear` is one render pass, so summing each pass's viewport area over
a frame measures the pixel work that grows with the rung, independent of the GPU.

Menu/title scene, host-GPU emulator, rung set through `localStorage["xg_local_options"]` (§9.6), 60 fps:

| rung (option) | canvas | passes/frame | cleared MPix/frame | draws/frame |
|---|---|---|---|---|
| 640x360 (scale 1) | 640x360 | 14 | 3.91 | 27 |
| 1280x720 (scale 2) | 1280x720 | 14 | 11.51 | 27 |
| 1920x1080 (scale 3) | 1920x1080 | 14 | 24.19 | 27 |

The arithmetic is exact: **11 rung-sized passes** (10 framebuffer targets + the screen clear) plus
1.38 MPix of rung-independent targets (1024², 512², 256²), and every measured row fits it to the
hundredth. So the work ratio is not the pixel ratio: 720p costs 2.9x of 640x360 (not 4x), and 1080p
costs **6.2x** - 2.10x the work of 720p rather than its 2.25x pixel ratio. Fitting §9.5's device curve
(640x360 = 54 %, 960x540 = 91 %, 720p saturated at 38-42 fps cool) onto this work model puts 1080p at
roughly **20-26 fps cool and ~10-12 fps throttled**, i.e. **2.5-4x short of 60**, and the rung-sized
passes are the dominating term (22.8 of the 24.2 MPix/frame).

Two more things the same instrument settled:

* **No upload pathology.** `frame-budget.js` on the same scene counts **zero** texture uploads in 20 s,
  27 draws/frame, 14 program switches, 32 texture binds, 14 clears - the engine redraws its 4096²
  sprite atlases only when they change. The GPU time is pass fill, not bandwidth from the CPU.
* **The output is already 1080p; the rung is shading.** At both 640x360 and 960x540 the canvas is
  presented at CSS 866x412 with `devicePixelRatio` 2.625 (i.e. the WebView scales it to the window),
  and the phone's active display mode is 1080x2316 @ 60 Hz. Choosing a rung changes how many pixels
  the engine *shades*; the picture the panel shows is the panel's resolution either way.

So the remaining levers, in order of size, and what each costs:

| lever | expected | cost / status |
|---|---|---|
| fewer full-canvas passes (the ten; fog/weather/light/post layers) | the only multiple-sized term: 1 pass of 10 is ~10 % of the frame | no quality option exists in the game to turn one off (`options.json` has only `pixel-size`/`sharp-pixels`/`fullscreen`); it means patching the engine's pass list - changes the game's look, and it is not a bug |
| overdraw / draw-order sorting | unknown, plausibly 10-40 % of fill | still untried (§9.4); means reordering the engine's draws from outside - explicitly needs approval |
| pin the window to 60 Hz (`preferredRefreshRate`, or `Surface.setFrameRate(60, FIXED_SOURCE)` on API 30+) | no gain in today's FHD+/60 Hz mode; on the phone's 120 Hz mode a faster vsync is *more* GPU work per game frame, not more game frames (§9.4, not quantified) | a small, defensible port change; only worth it if 120 Hz is used |
| lower rung + the WebView's upscale (what rung 1 already is) | the practical "1080p picture" - 60 fps at 960x540 shading | already shipped and measured (91 % GPU) |
| anything measured dead: compositor filtering, opaque canvas, Vulkan, CPU offload, canvas CSS pinning, `discard` vs `return` | - | §9.4, do not retry |

Thermals decide sustained anything: 720p is 19-21 fps hot (status 3, 45 °C skin), so no proposal here is
real until it is measured hot, and 1080p60 is out of reach on this SoC regardless of how the passes are
trimmed.

---

## 10. The frozen boot bar (2026-09-25) — issue #1, an AYN Odin 3

Symptom: "the game freezes loading the game files, it does not boot, forcing a shut down", with a
screenshot of a thin blue bar at ~9-12% and no other UI. The reporter's one hardware fact was that
the on-screen pad's pills still hide when the controller is touched — which is the clue that matters:
**the app is alive**, so this is not a frozen process.

### 10.1 What that bar is

`TriRenderer.renderBooting(bootingProgress)` draws it: the canvas is cleared to black, a 16 px-high
bar centred and `size.x / 2` wide, framed `rgb(0, 64, 128)`, and the fill `rgb(128, 192, 255)` grows
from the left by `bootingProgress` (screenshot: frame + a light fill starting at the left end =
exactly this, at ~0.1). And:

```
bootingProgress = LoadTracker.progress = 1 - resources.length / maxResource
```

over `g_resource.bootTracker` — `boot()` wraps the listener, `onLoadingUpdate` copies
`tracker.progress` into `g_system.bootingProgress`, and the tracker only empties when **every**
staged resource finalizes. So the bar stops at N% when the *boot resource set* stops completing; the
port's process, main thread and input are untouched, which is precisely what the reporter saw.

### 10.2 The boot set, stage by stage (measured, not guessed)

Desktop Chromium against the real game tree (local logging server + an in-page sampler reading
`g.resource.bootTracker`: requests, `progress`, `maxResource`, pending `identification()`s — recipe in
§10.6): **1743 resources**, complete in ~11.5 s; the asset burst is over at ~10 s and the rest of the
way to the title screen is the intro sequence (§8 item 3).

| progress | what is still pending |
|---|---|
| 2-7% | `data/database/*.json` (global-vars, tilesets, prefabs, changelog, …) + the first Spritesheets |
| 8-14% | GUI Spritesheets (`media/gui/menu.png`, `hud.png`, …) **and ~100 `media/audio/sfx/**.wav`** |
| 40-60% | half audio, half map/effect data |
| 87-95% | the Spritesheet tail (this is the slow part — 5.3 s to 8.8 s of the 11.5 s) |
| 99.5% | a handful of `Effect[ FX:… ]` / `Figure[ FIG:… ]` |

The audio batch sits in the critically-early 8-14% band, i.e. **inside the reporter's frozen range**.
On the A14 emulator the same tracker read 1755 / 2633 / 2938 resources depending on which save was in
place (map effects are staged from the save), so the total is save-state dependent, the ordering is not.

### 10.3 Which load path can hang

* Images (`ImageRes.loadImpl` → `new Image()` + `src`): `onload`/`onerror` always fire; a failure ends
  in `g_resource.reportError`.
* Data (XHR/`fetch` via `AjaxUtils`): promises/rejections settle.
* **`SoundRes.onLoad` (bundle ~2049-2090) is the exception**: it calls
  `context.decodeAudioData(data, ok, fail)` and `finalizeBuild(true)` lives **only in the success
  callback**. If a device's WebView delivers neither callback — an audio thread that never starts —
  the resource never finalizes, the tracker never drains and the bar freezes with **no exception
  anywhere**.
* Ruled out: a *suspended* `AudioContext`. Re-running the boot with
  `--autoplay-policy=document-user-activation-required` still completed (Chromium decodes while
  suspended), so that sub-case is not the mechanism.
* Therefore the port now counts the decode callbacks (`started/done/failed`) and reports the context
  state whenever a boot stalls: a stall whose pending set is audio with `started > done` is this
  mechanism, a stall with `inFlight` asset reads is the next one.

### 10.4 The same symptom, reproduced — a GL stack that rejects the shaders

On the A14 emulator (`-gpu swiftshader_indirect`; page reports
`gl=Android Emulator OpenGL ES Translator (Google SwiftShader)`) the boot dies at 8-12% and the log
repeats, every frame:

```
Uncaught Error: An error occurred compiling the shader "data/shader/vertex/clouds.vert"
   (also gui.vert, fx-mesh.vert, fx-decal.vert, weather-drops.vert)
Uncaught Error: There are unwrapped loadTrackers
```

`checkTrackers()` throws when the tracker list is non-empty at loop end, so the boot can never
complete and the bar freezes at the same low percentage as the report.

**A/B'd against stock 0.3** (built from `a57699d` in a worktree, same AVD, same app data): 11 shader
errors and the same `unwrapped loadTrackers` — identical. The port rewrites `.frag` bytes only and the
failing files are `.vert`, served verbatim, so this is the device's GL/driver stack, not the port.
The emulator was started with the guest GL as SwiftShader; a host-GPU or `-gpu host` run is the way to
get a *booting* emulator for other work.

### 10.5 What the app now reports, and why each field exists

`Diag` (bounded in-memory log + frame clock, pure Kotlin, unit-tested), `AssetTracker` (unfinished
asset reads, pure Kotlin, unit-tested), `DiagnosticsDialog` (renders and shares the record), the
shim's `watchBoot`, and two native watchdogs:

| field | what it decides |
|---|---|
| app/device/Android/ABI | which build, which hardware |
| WebView package + version, document-start support, UA | the implementation actually running (a device without Play can be far behind) |
| `gl=` (from the page: `g.gl` + `WEBGL_debug_renderer_info`) | which GL backend the WebView picked — the shader-failure class above is backend-specific |
| `audio=` (`g.audio.context.state`) + `decodes started/done/failed` | §10.3, told apart from a hung read |
| `boot stall … % of N resources; pending M (kind=count …)` + first pending names | the frozen bar, named |
| `engine silent for Xms; last bridge call: …` | the *other* failure: the page's JS thread stopped — `getGamepadJson()` is called once per frame, and `AdaBridge` records the fs call in flight, so a hung SAF read is named instead of guessed at |
| `asset read stuck: path (Ns)` | `shouldInterceptRequest` reads that never returned (a slow/hung DocumentsProvider) |
| `slow asset <ms> path` (>1.5 s) | a struggling tree before it fails outright |
| `assets served/missed/inFlight/slowest` | throughput and backlog at a glance |

The watchdog reports silence only while the page is *meant* to be running (`engineActive`: resumed and
not blurred by the dialog) — verified: a stalled-but-alive boot produced 0 silence reports over 30 s.

### 10.6 Recipes used here (reusable)

* **Boot-stage mapping**: serve the tree with a request-logging server that injects the shim, and
  sample page-side in `setInterval` — `{progress, maxResource, pending: bootTracker.resources.map(r =>
  r.identification())}` — then join samples to the request log by timestamp. `window.g.system` is
  **not** published by this bundle; `window.g.resource.bootTracker` is the way in (and its `progress`
  is the same number `renderBooting` receives).
* **Driving a page on the device**: debug builds enable WebView devtools —
  `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`, then CDP
  `Runtime.evaluate` (default context) on the page target. `Runtime.evaluate("while(true){}")` blocks
  the page's JS thread on purpose, which is how the silence watchdog was verified end to end
  (it logged `engine silent for 6601ms; last bridge call: -` within 2 s of the threshold).
* **Screenshots on the emulator**: `adb exec-out screencap -p`, and read button geometry off the
  pixels (the panel's buttons are `rgb(90,89,91)` bands) before tapping — two taps one band off
  exited the app during this session.

### 10.7 Hardening the record, and what it cost to read the boot path again (2026-09-25)

Three changes, each measured on the A14/SwiftShader emulator (§10.4's frozen boot is the test state;
the emulator was started with `-avd ayvu34 -no-window -no-audio -gpu swiftshader_indirect -no-boot-anim
-no-snapshot` and left running for the whole pass). Two facts about that AVD cost time here: it is
configured `disk.dataPartition.path = <temp>`, and after the emulator was killed by the shell that
started it, the app was no longer installed while `/sdcard` and DocumentsUI's remembered location
were — so the package had to be installed again and both SAF folders re-picked through the picker
(Download → the folder → USE THIS FOLDER → ALLOW; the Download root itself is not selectable).

| change | measurement |
|---|---|
| `Diag.line` collapses an immediately repeated message in place (`… loadTrackers (x412)`), sink keeps every raw line | the stuck boot's per-frame error, previously 1 line/frame against a 400-line ring (~6 s of history for 60 fps), now occupies one slot: the shader names, the `facts` line and the `boot stall` line were all still present in a 36-line record |
| rewritten bodies cached (`RewriteCache`, 32 MB budget) + a fragment shader's paired `.vert` read once per process | first boot: `rewrite cached=40 hits=0 12861435/33554432 bytes` with `slowest=510ms terra/dist/bundle.js`; after a CDP `location.reload()` on the same process: `hits=40`, `served` 328 → 651, `slowest` unchanged at 510 ms — the 12.7 MB read and all 39 other rewrites were skipped |
| the record kept as `ada-diagnostics.log` in the saves folder, on by default, off by itself after the first completed boot unless the user touched the switch | the file grew while the boot was stuck (5 780 bytes, 29 lines, holding the `facts`, shader and `boot stall` lines); forcing `g.resource.bootTracker` to `progress=1, state=2` over CDP produced `ENGINE boot: complete in 35000ms …` and then `log file off: the game completed its first boot` on the next 2 s tick, with the file's last line that one and the prefs holding `log_to_saves=false` (`log_to_saves_user_set` absent, i.e. still untouched); after a tap set `log_to_saves_user_set=true`, a second completed boot printed no auto-off line and the switch stayed on across force-stop + relaunch |

Budget note for a future change: only four response kinds are cached (the bundle, the option
database, `.frag` files, and `index.html` when the shim is injected) and an entry is refused rather
than evicted once `maxBytes` would be exceeded — the bundle alone is 12 861 435 of the 33 554 432
bytes, so the cache is sized for "the bundle plus the shader set", not for the tree. Caching every
response would cost tens of MB for assets that are read exactly once per boot.

Release check for v0.4 (`android/tools/release.sh`): the shipped `AlabasterDawn-Android-0.4.apk` is
1.1 MB and signed by the same key as 0.1–0.3 (cert SHA-256 `ab31dd88…`), so it upgrades in place.
That exact artifact was installed on the emulator — over a *debug* uninstall, since the keys differ —
and driven from scratch through the picker and START: it indexed the same 2 937 entries, booted to the
same 12 % stall, and wrote `app 0.4 (4) …` with `rewrite cached=40 hits=0 12861435/33554432 bytes`
and `(x12)` in the collapsed repeat, so the release build behaves as the debug one does. One
difference on purpose: the release build is not debuggable, so WebView devtools is off and the CDP
driving of the page from §10.6 is unavailable there — `adb logcat -s AdaPort:I` is the raw-line
channel on a shipped build.

### 10.8 What the first two device reports looked like, and the two fixes they forced (2026-09-25)

Both reporters answered issue #1's request for a record with a screenshot of the panel — and both
screenshots were **fresh launches**, not the frozen boot. They are worth keeping as the baseline
shape of a useless report:

| | AYN Thor (Jherben) | AYN Odin 3 (JayElDragon) |
|---|---|---|
| `app …` | `0.4 (4) on AYN AYN Thor, Android 13 (SDK 33, arm64-v8a)` | `0.4 (4) on AYN Odin3, Android 15 (SDK 35, arm64-v8a)` |
| `webView …` | `com.android.webview 109.0.5414.123` | `com.android.webview 124.0.6367.219` |
| `game files:` | `Alabaster Dawn (not indexed)` | `Alabaster Dawn (not indexed)` |
| `saves:` | `Saves` | `Saves` |
| `assets …` | `none served yet` | `none served yet` |
| `--- log` | `(2 lines…)`: the launch line + `+1089ms diagnostics opened` | the same, `+1089ms diagnostics opened` |
| reported symptom | "loads the first progress bar and then the second one it barely moves, less than a quarter, left for more than 5 minutes" | "game freezes when trying to put gamefiles … forcing a shut down" |

Zero assets served and a two-line log in both: the app was healthy at `+1 s` and nothing had been
read from the game folder yet. The panel is per-launch and a frozen boot forces a restart (issue #1
says it needs a force-stop), so `Diagnostics` opened after the restart can only ever show the launch
after the failure — the two reporters did the reasonable thing and got nothing for it. Fixes:

* **The record is carried across restarts.** At launch the app reads `ada-diagnostics.log` back and
  carries the newest 199 of its stamped lines (half the 400-line ring) into the ring behind a
  `--- previous session, carried from ada-diagnostics.log ---` marker. Half, not all, because the
  carried lines sit at the front of the ring and a carry that filled it lost its marker — and then
  its oldest lines — to the very next log line, this session's own `diagnostics opened` included;
  that failure was seen on the device (the panel showed 400 carried lines and no marker) before the
  rule became half a ring. Only lines starting with `+` are carried, so the file's header, an older
  marker, and anything else outside the log section are dropped and markers cannot accumulate — a
  re-carried record keeps exactly one (`grep -c "previous session"` = 1 in the file after two
  restart cycles).
* **The file starts being written when START is tapped**, not when the game's page begins loading:
  `watchdogTick` (whose top is the flush, every 2 s) is posted from `startGame`, and `launchWebView`
  clears and re-posts it as before. So a stall inside `GameFiles.indexTree` — "freezes when trying to
  put gamefiles", the Odin 3's symptom — now leaves `+Nms indexing …` on disk with nothing after it,
  instead of nothing at all.

Enabling that last one meant opening the saves store in `onCreate` rather than after indexing, which
broke the case it has to serve (a fresh install has no store yet at launch, and a saves folder picked
seconds later would have been ignored for that whole session): `onActivityResult` now follows the
picked tree, re-opening the store and reading *that* folder's record before a flush can overwrite it.

Device verification of the two fixes (A14/SwiftShader emulator, debug build, real game tree unless
noted; the emulator's `/data` is a temp image, so a *package* change needs the 6-tap picker flow —
Download → the folder → USE THIS FOLDER → ALLOW — and the grant taps shift with DocumentsUI's
remembered location):

* carry-over, on the setup screen with the game never started: `--- log (202 lines, oldest first) ---`,
  the launch line, `--- previous session, carried from ada-diagnostics.log ---`, then the previous
  file's newest lines (`+8296405ms ENGINE boot stall: …`, i.e. 138 minutes into the session that had
  frozen) — and after `am force-stop` + relaunch, the same record again.
* the file keeps what the panel shows: header = this session's facts, then the launch line, one
  marker, the carried lines, this session's lines.
* the indexing flush: a synthetic 3 000-entry tree (`mkdir`+`touch` on the device, 21 s to create)
  indexed in 2 119–2 424 ms; the flush 2 s after START held this session's
  `+3922ms indexing content://…/BigTree` and the next line in the file was still a carried one —
  `indexed 3000 entries in 2119ms` reached the file only in the `+6104ms` write that also held
  `loading https://appassets.androidplatform.net/game/terra/index.html`. That tree was deleted from
  `/sdcard/Download` afterwards; the emulator was left with the real game folder picked.
* unchanged paths still work: a normal start indexes 2 937 entries, loads, stalls at 12 %, collapses
  repeats and writes the record; `log_to_saves`' auto-off was verified in §10.7 and its code is not
  touched here.

### 10.9 The frozen bar, solved: the game's 256-slot uniform table (2026-09-26)

Issue #1's symptom is a boot bar that stops at 7.9-12 % forever. §10.4 blamed "a GL stack that rejects
the engine's vertex shaders" - which was true and *not* SwiftShader-only: both reporters, the AYN Thor
(Android 13, WebView 109, Adreno 740) and the AYN Odin 3 (Android 15, WebView 124, ANGLE on Adreno 830),
freeze on the **same 11 vertex shaders** with the **same boot set** the emulator did (`shader=20`
pending, 1755/2938 resources, 12.0 %/7.9 %). The panel that ships now names them, so the three devices
can be compared side by side; what the panel could *not* say is what the GL compiler objected to,
because the engine only prints the shader's *path* in its `window.onerror` message and writes the real
info log to `console.error`.

What the compiler said, read out of the live engine over CDP (the debug build's devtools socket): every
one of the 12 `.vert` files fails `compileShader` with `ERROR: too many uniforms`. The cause is the
texture-slot table the game declares in each of them:

```
#define TEX_SLOT_COUNT 256
uniform vec2 u_texSlotCoords[TEX_SLOT_COUNT];
```

and one `vec2` array element costs one whole uniform *vector* on the drivers seen here
(`MAX_VERTEX_UNIFORM_VECTORS = 256` on the emulator, which is the GLES3 guaranteed minimum), so the
table alone consumes the entire budget before the matrices, the wind uniforms and the imported
`lib` glsl files are counted. `gui.vert` is worse: it declares **two** tables, the gui atlas and the
font atlas (`uniform vec2 u_fontSlotCoords[TEX_SLOT_COUNT]`), so it needs `2 * TEX_SLOT_COUNT` vectors.

Measured against the engine's own live sources (link the real vertex+fragment pairs, substituting only
the table size): with the tables left as the game wrote them, `gui` links at 96 and **fails at 128**,
and every one-table program links at 192 (`weather-drops.vert`, the heaviest, needs 48 vectors
besides its table). The engine's `const TEX_SLOT_COUNT = 256` sizes both the atlas and the upload, so
the two constants have to move together.

The fix, in three parts:

* the shim reads `MAX_VERTEX_UNIFORM_VECTORS` from a throwaway context at document start - before the
  bundle asks for a single shader - and reports it through a new `AdaBridge.setVertexUniformVectors`,
  which logs `gl limits: vertex uniforms 256 -> TEX_SLOT_COUNT 192`; the same number and
  `MAX_FRAGMENT_UNIFORM_VECTORS` / `MAX_VARYING_VECTORS` now travel in the `facts` line, so a field
  report says what the device actually had.
* `ShaderSlots` turns that budget into a table size: the game's own 256 when the device has room for
  two of them, otherwise `budget - 64` - 192 at the floor. The served `.vert` bytes get the new
  `#define`, and `terra/dist/bundle.js` gets `const TEX_SLOT_COUNT = 192;` so the atlas and the
  `uniform2fv` upload agree with what the shaders declare.
* where the device cannot afford `gui.vert`'s two unpacked tables (below `2 * 256 + 32` vectors), that
  one file is served with each table packed into `vec4`s - `uniform vec4 u_texSlotCoords[TEX_SLOT_COUNT
  / 2]` plus a two-overload helper (`int` and `uint`, because the shaders index with both) that
  returns `v.xy` or `v.zw` - which costs one vector per *two* slots and keeps the slot count. This is
  sound because the engine picks its upload call from the program's own uniform type
  (`getActiveUniform` -> `FLOAT_VEC4` -> `uniform4fv`), and `2 * S` floats into `vec4[S / 2]` validates
  exactly; slot `i` then lives in element `i >> 1`, which is where the engine's existing
  `texSlotCoords[2i], [2i+1]` layout already puts it. Without this, the only table `gui` can take is 96
  - a quarter of the game's own atlas ceiling - and one measured map scene already needed 85 of those
  96 slots. A device with room for the game's own tables is served **unmodified bytes**; the port
  rewrites nothing at all unless the device is at the floor. `.frag` files are untouched by this,
  `gui.vert` is the only file with two tables, and only the bytes the WebView sees are rewritten.

Verified on the A14/SwiftShader emulator, which is a *known bad* shader host (§10.4) and therefore a
fair torture test:

| before | after |
|---|---|
| 22 `An error occurred compiling the shader …` lines, `shader=20` pending | **0** shader errors, no `exceed GL_MAX_VERTEX_UNIFORM_VECTORS` |
| `boot stall: no progress for 8917499ms at 12.0% of 1755 resources` (recorded across a 2.5 h session) | `ENGINE boot: complete in 6161ms, 1757 resources` |
| the loading bar, forever | the title screen, menus, cutscene and in-game HUD drawing |

The same floor exists on real Adreno hardware - which is what the reporters have. The shipped
`AlabasterDawn-Android-0.4.2.apk` installed over 0.1 on the **SM-S908U1 (Galaxy S22 Ultra, Android 16,
WebView 153, Adreno 730)**, kept its picked folders and booted the game from its own setup screen:

```
+23180ms indexed 2938 entries in 6725ms
+23487ms gl limits: vertex uniforms 256 -> TEX_SLOT_COUNT 192
+25835ms ENGINE facts: webgl2=true; gl=Adreno (TM) 730; uniforms=256/256; varyings=31; audio=running
+32850ms ENGINE boot: complete in 9359ms, 1757 resources; decodes started=608 done=608 failed=0
```

`MAX_VERTEX_UNIFORM_VECTORS = 256` there too, so the 740/830 in the two reports are the same case, and
the fix is the one that applies to them.

The emulator's own log now reads `gl limits: vertex uniforms 256 -> TEX_SLOT_COUNT 192`, the served
bytes read `TEX_SLOT_COUNT 192 in terra/dist/bundle.js` and `TEX_SLOT_COUNT 192 packed in
terra/data/shader/vertex/gui.vert`, and over CDP the running engine confirmed the forms it compiled:
`gui` `uniform vec4 u_texSlotCoords[…]` + `ada_u_fontSlotCoords(`, `solid` `uniform vec2
u_texSlotCoords[…]` with `#define TEX_SLOT_COUNT 192`. Every atlas group then reported
`texSlotCoords.length / 2 = 192` slots with 58 sheets in use. The packed table is not just theory: the
title illustration, the menu text and the in-game HUD are drawn from the packed gui and font tables and
render correctly - a wrong slot coordinate would show up as scrambled glyphs. `gl.getError()` in steady
state is 0 (the one `INVALID_OPERATION` seen in a first read happens at boot in *both* configurations,
packed and unpacked, and is unrelated to the tables).

Two things to keep honest about it. First, the emulator still mis-renders the *map* geometry - both at
96 unpacked and at 192 packed - so SwiftShader is no judge of the picture; what it does prove is that
the shaders compile, the boot completes and the UI draws. The reporters' Adreno devices are the ones
that can say whether the map looks right, and their next panel now carries everything needed to judge
it. Second, a scene that packs more sheets into one atlas than the served table allows would log
`ATLAS ERROR: Exceeded maximum TEX_SLOT_COUNT of 192` from the engine; that message goes to
`console.error`, which the port now forwards (`console.error …` in the log and the panel), so the
failure mode is named instead of guessed at. The desktop game's own ceiling is 256, so the phone pays
for its smaller uniform budget with 64 fewer slots per atlas - the price of a table the device can
actually compile.

Also tried and *not* shipped: packing **every** table (it links too, at 208 with the same sources).
Only `gui.vert` needs it, and rewriting one file is the smaller change; the rest of the shaders are
served with nothing but a smaller `#define`.

### 10.10 Instruments for a running engine, and where to look in it

Everything in §10.9 came out of the engine's *live* objects over CDP, because the bundle publishes
almost nothing (`window.g.system` does not exist; `window.g.resource.bootTracker` does). The probes are
kept in `tools/probes/` with the handles they read:

* `shader-budget.js` links every real vertex+fragment pair in `g.renderer.shaders` with only the slot
  table substituted - the measurement that found the ceiling (`gui.vert` at 96, not 128; one-table
  programs at 192).
* `gl-errors-and-uniforms.js` - the pending `gl.getError()` and `MAX_VERTEX_UNIFORM_VECTORS` /
  `varyings` as the WebView reports them (`256/261/32` on SwiftShader, `256/256/31` on the S22's
  Adreno 730).
* `atlas-sampler.js` - peak `sheets` per atlas group plus the engine's `console.error`s, for judging the
  slot ceiling while playing.
* `gl-call-ring.js` - wraps the live GL context to name the call behind an `INVALID_OPERATION` instead
  of guessing.
* `packed-table-pixel-test.js` - uploads real slot coords into a `vec2[S]` and a `vec4[S/2]` program and
  reads the pixels back.
* `slot-table-alternatives.js` / `slot-budget-cases.js` - the table carried five ways (unpacked, packed,
  std140 block, `texelFetch`, as served) against the real `gui` pair and against a synthetic shader whose
  every declaration is used; `slot-budget-probe.html` wraps the latter for a device whose WebView has no
  devtools (§10.11, including the Chrome + `adb reverse` + `serve-fast.py` route for the S22).
* `fetch-table-pixel-test.js` - the slot table as an RG32F vertex texture, compared slot by slot with the
  atlas floats.
* `slot-table-upload-rate.js` - counts uniform-array uploads against draws and frames while playing.
* `cdp.py` - the client (debug builds only: devtools is gated on `BuildConfig.DEBUG`; on a release build
  `adb logcat -s AdaPort:I` is the channel, or Chrome's devtools socket for a page probe).

Handles that matter: `g.resource.bootTracker` (`progress`, `resources[]` with `identification()`),
`g.renderer.shaders` (each `TriShader`; `.vertexShader.source` is the **served** GLSL, `.definitions`
the engine's `#define` set), `g.renderer.groups.<group>.atlasses[i]` (`sheets`, `texSlotCoords`,
`uTexSlotCoords`), `g.gl`, and `g.renderer.isPaused/.cineCamera/.viewType/.tags` to tell a cutscene from
gameplay. A dead end worth remembering: driving Chrome on the *emulator* through a host server and
`adb reverse` never got past Chrome's first-run flow - the WebView devtools socket is the route. On a
real device with a release build, that browser route *does* work and is how §10.11's device column was
taken (Chrome + `adb reverse` + `tools/serve-fast.py`, then `adb forward` to
`localabstract:chrome_devtools_remote`).

### 10.11 The uniform-budget fix against the SOTA, and where caching actually pays (2026-09-26)

§10.9's fix serves a smaller `TEX_SLOT_COUNT` than the game's own 256. Spike question: how do other
engines carry a table that cannot fit the vertex-uniform budget, is there a better fix than the
ladder, and does caching buy anything. Three families exist in the wild:

* **shrink the table to the device's budget** - what 0.4.2 does, and the mainstream answer: three.js
  derives its bone ceiling from the reported limits (`getMaxBones`: float vertex textures -> 1024,
  otherwise the vertex-uniform budget), and Godot's bone ceiling is likewise budget-derived.
* **carry it in a texture and fetch per vertex** - three.js's `Skeleton.boneTexture` exists precisely
  for this ("a texture holding the bone data for use in the vertex shader", `src/objects/Skeleton.js`);
  Godot 4's GLES3 backend does it in `drivers/gles3/shaders/skeleton.glsl`:
  `uniform highp sampler2D skeleton_texture; #define TEX(m) texelFetch(skeleton_texture, ivec2(m % 256u,
  m / 256u), 0)`. Both index a texel that holds 2-4 floats per element, so the table costs no uniform
  vectors at all.
* **carry it in a uniform block** - three.js's WebGL2 node backend puts `skeleton.boneMatrices` and
  instance matrices in blocks; its failure mode is `GL_MAX_UNIFORM_BLOCK_SIZE` (16 KB on Chrome/ANGLE
  macOS = 256 `mat4`, issues #33009/#34196), *not* `MAX_VERTEX_UNIFORM_VECTORS` - the spec answer:
  block members are backed by a buffer and are not part of the default uniform block.

Measured on this port's two stacks with `tools/probes/slot-budget-cases.js` (every declaration is used
by the shader body - an unused uniform is dead-code-eliminated and the case then passes for the wrong
reason; `fill80` is 80 vectors: 4 `mat4`, `vec4[16]`, `vec2[16]`, `vec4[32]`):

| stack | max vertex uniforms | blocks / block size | 80 + `vec2[192]` | 80 + `vec2[256]` in a block | 256 via `texelFetch` |
|---|---|---|---|---|---|
| S22 UL (Adreno 730, Chrome/ANGLE 153, GLES 3.2) | 256 (frag 256, varyings 31) | 14 / **65536** | fails (272 > 256) | **links**, block 4096 B | links |
| emulator, SwiftShader-backed WebView | 256 | 12 / 16384 | fails | **fails**: `Vertex shader active uniforms exceed GL_MAX_VERTEX_UNIFORM_VECTORS (256)` | links |
| emulator, host radeonsi | 4096 | 15 / huge | links | links | links |

* The ladder is the only family that needs no engine change, and 192 *is* the arithmetic ceiling for
  `gui.vert` inside it: packing both of its tables at 256 would cost 128 + 128 vectors before the ~30
  the shader needs, and measured `packed_256` does not even compile.
* On the real device **both** SOTA alternatives work and would serve the game's own 256 slots. The
  UBO rejection is the *emulator's* GL layer, not Android's: that string is absent from ANGLE's sources
  (`main` and `chromium/5672`, both grep'd), and the same cases link on the same WebView as soon as the
  host GPU is a real driver. That is a test-bed problem with teeth: the port's only floor device
  (SwiftShader) cannot verify a UBO fix, while it *does* verify the texture one.
* The texture route also fits this engine better than the UBO. The table is **per material**
  (`SpriteAtlas.assignToMaterial` -> `material.setArray`), and the engine already has a per-material
  texture path (`material.setTexOrRenderTarget`, sampler indices assigned in `TriShader`), so a texture
  drops in exactly where the array is; a block is a per-draw binding (`bindBufferBase`) with no
  per-material mechanism in the engine, std140 would need the coords repacked (a `vec2` array strides
  16 bytes, the interleaved `texSlotCoords` does not), and the engine's uniform machinery would still
  see block members through `getActiveUniform`/`getUniformLocation` (null) and try to `glUniform` them.
* The round trip is exact and cheap: an RG32F 192x1 texture built from the engine's own interleaved
  `texSlotCoords` (no repack) read back bit-identical for all 31 placed slots in a vertex shader, max
  coord 3572 - so RG16F (half float, exact integers only to 2048) is *not* enough. Both stacks report
  `MAX_VERTEX_TEXTURE_IMAGE_UNITS = 16`, so two more samplers per program is nothing.
* Price of the better fix: rewrite the served `gui`/table shaders to fetch instead of index, plus a
  patch where the atlas assigns and refreshes the table (`SpriteAtlas.assignToMaterial`/`updateRedraw`,
  the font atlas at bundle ~267093). Price of the shipped fix: nothing; both stay inside `ShaderSlots`.
* Nothing observed needs it yet. The game says so itself when the served table is too small
  (`ATLAS ERROR: Exceeded maximum TEX_SLOT_COUNT of 192`, forwarded to the record since 0.4.2); §10.9
  measured a map scene at 85 of 96, and the map loaded here uses 31 of 192. Until a report shows that
  line, the texture patch has no defect to fix - it is an option to keep in the drawer, written down
  here with its measurements.

**Caching.** The table is *not* re-uploaded per frame: `slot-table-upload-rate.js` wraps the live
context and counted **0** uniform-array uploads (any length >= 300) over 15 s of play - 140 frames,
3 780 draws. The engine's `Material.apply()` uploads only when the value is marked modified, so
"caching the table" has nothing to save, and the 0.4.2 fix costs nothing per frame either (the packed
accessor is a shift + select against a 96-entry table instead of indexing a 192-entry one). The Kotlin
side of the fix is not worth caching: one GL-limits call per launch, a 12-file regex rewrite in
microseconds, a budget that is stable per device.

The start path has exactly one cacheable multi-second cost, and it is not the shaders: `GameFiles.
indexTree` re-walks the whole picked tree on every launch - `indexed 2938 entries in 6725ms` on the S22
inside a 9.4 s start-to-title, 2 589 ms cold and 449 ms warm on the emulator - because SAF offers no
cheap diff and the port rebuilds the full docId map. Persisting that map and refreshing it against the
root's own metadata would take seconds off every launch, and it is the only lever of that size. (The
other one is already shipped: `RewriteCache` holds the 12.7 MB `bundle.js` read+rewrite in memory.)

## 11. The game's own Exit, the game's version, and the side menu as a list (2026-09-26)

### 11.1 The frozen picture after the title screen's EXIT — the engine's exits were shim no-ops

Issue #1's acknowledged tail: on the title screen, choosing **Exit** stopped the picture with the
engine's own menu gone. The bundle makes two distinct exits and both were stubbed `noop` in
`assets/ada-shim.js`:

* `nw.Window.get().close()` — the title screen's EXIT runs `closeGame()` (bundle offset ~12 430 458:
  `setTimeout(() => { const win = nw.Window.get(); win.leaveFullscreen(); win.close(); }, 300)` then
  `this.hide()`), so the engine hides its menu and waits for a window close that never happened.
* `nw.App.quit()` — `System.quit()` (bundle offset ~1 495 156).

Fix: one shim helper `quitApp()` calls `bridge.quit()`, wired to both. `AdaBridge.quit()` calls the
activity's `onQuit`, which posts to the UI thread because the JavaBridge thread must return
immediately. `close(true)` (the `XG_GAME_DEBUG` path) is deliberately ignored: `quitApp` takes no
arguments.

### 11.2 Exit ends the process, so the "second launch is black" workaround is gone

`Process.killProcess` after `finishAndRemoveTask()` and a WebView teardown: the WebView renderer is
shared for the app process's life, and the second in-process start was the documented way to reach a
black picture (previously worked around by swiping from recents). The same `teardownWebView()` now
serves `onDestroy` and `onRenderProcessGone`; it removes the view from the tree *before* `destroy()`
(the old `onDestroy` destroyed a WebView still installed as the content view). The record is flushed
synchronously (`flushLogBlocking`) before the kill — the async writer's thread would not survive it.

Measured on the Android 14 emulator (`-gpu host`): `exit requested`, empty `pidof`, no task in
`dumpsys activity activities`, and three consecutive fresh launches each reaching
`ENGINE boot: complete` with the title screen drawn and a new pid (5 411 → 5 787 → 6 029).

The task-removal half of that path is gone as of §20: `finishAndRemoveTask` is what dropped the app
out of recents, and the maintainer asked for it back, so the exit now sends the task to the back and
kills the process instead. The rest of the section still holds.

### 11.3 Where the game's build version actually lives

* `package.json` carries only the placeholder `"0.0.0.0.0.1"` — useless.
* The engine inlines the real build in `bundle.js` as `class VersionManager { … }`
  (`this.major = 0; this.minor = 1; this.patch = 0; this.hotfix = 10; this.suffix = "Early Access";`
  → `0.1.0-10 Early Access`). `getVersionString()`/`toString()` assemble exactly that, and the block is
  1 399 chars to the first `getVersionString`. This is the only source carrying the hotfix.
* `terra/data/database/changelog.json` (750 bytes) has the newest *release* under `entries[0].version`
  (`"0.1.0"`), available as soon as the tree is indexed.

`GameVersion.fromBundle` parses the block by regex (guarded: > 4 000 chars to `getVersionString`, or a
missing number → null, so a game update degrades to the changelog or `unknown`, never a wrong value);
`GameVersion.fromChangelog` reads the JSON. `GameAssetHandler.phoneResolutionLadder` already decodes
`bundle.js`, so `gameBuild` costs no extra read and no extra decode; `GameFiles.readText` reads the
changelog once after `indexTree`. The record line is
`game ${assetHandler?.gameBuild() ?: gameReleaseVersion ?: "unknown"}`.

### 11.4 The side menu, after Eden / Sudachi / Azahar

One row builder: icon in a fixed left gutter (24 dp, 16 dp margin), label on one line (14 sp,
ellipsis), control at the right edge, `MATCH_PARENT` width, 48 dp minimum height, `dp(12)` horizontal
padding, a 1 px divider between rows, and the whole row is the hit target (`RippleDrawable` over the
row; the control is left non-clickable). The active Game-position row is a filled pill. A `RadioGroup`
cannot host a child that another view already parents, so mutual exclusion is explicit
(`radioRows`/`radios`), and `sync()` fills the current row. The icons are Material Symbols Outlined
(Apache-2.0) downloaded from `fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/…`;
their `0 -960 960 960` viewBox has no origin translation in a VectorDrawable, so each path sits in a
`<group android:translateY="960">` and the ImageView tints it. Panel width went `dp(300)` → `dp(340)`
so a label keeps one line beside icon + gutter + switch.

Measured: the `port 0.4.2 (6)` line shows on the setup screen and in the menu's status block; the
in-game Diagnostics header reads `game 0.1.0-10 Early Access`; tapping a row's *blank left edge*
toggled that row's switch (uiautomator `checked` true → false); and with the menu open 12 s the
watchdog logged no `engine silent` line, i.e. `FOCUS_BLOCK_DESCENDANTS` still kept the WebView's
document focus so the engine's loop never stopped.

One Kotlin trap worth recording: `rippleColor`/`pillColor` were first declared *after* the `init { }`
block that builds the panel, so at construction they were still null and `RippleDrawable` threw
`IllegalArgumentException: RippleDrawable requires a non-null color` — property initializers run in
textual order, so anything `init` uses must be declared above it.

## 12. The Steam demo, run through the port (2026-09-26)

The demo install (`Alabaster Dawn Demo`, changelog newest entry `0.0.4` "Steam Demo Sep 2025",
`terra/` 226 MB / 2 327 files) boots and plays on the port: `ENGINE boot: complete in 6655ms, 1641
resources`, 0 failed decodes, its own title screen ("Demo Version", New Game / Load / Options / Exit)
and its intro both draw. Its build reads as **`game 0.0.5-3 Alpha`** from the bundle - newer than the
changelog's newest public entry, which is exactly the divergence the two version sources exist for.

Two defects only the demo exposed, both fixed:

### 12.1 The option relabel assumed the release build's ladder

`GameAssetHandler.phoneResolutionLadder` rewrites `const RESOLUTION_MAP = [1, 2, 3, 4, 6];` and
`relabelResolutions` renames the option's rungs to 640x360 / 960x540 / 1280x720 / 1920x1080 /
2560x1440. The demo's bundle has no such literal (the rewrite was correctly skipped, leaving the
demo's own ladder), but the relabel has its *own* anchor (`{"en_US":"640x360","langID":171}`, which
the demo shares) and ran anyway: langID 172 `1280x720` -> `960x540` and 173 `1920x1080` ->
`1280x720`, so the demo's Resolution menu would have named the wrong rungs. The two rewrites are
gated together now (`ladderRewritten`, null until the bundle has been served, so the release's own
order - index.html's script tag before the options database - is unchanged).

While in there: the function computed `val defaulted = text.replace(RESOLUTION_DEFAULT_ORIGINAL, …)`
and then returned `text`, so the "a fresh profile should land on the 60 fps rung" change never
reached the served bytes; the rewritten ladder already makes value 1 the 960x540 rung, so the dead
computation and its two constants are gone.

Verified over CDP on the emulator (debug build), reading what the WebView was actually served:
demo `{"en_US":"1280x720","langID":172}` (untouched) and the bundle byte-identical to the file;
release `{"en_US":"960x540","langID":172}` with the phone ladder present.

### 12.2 Windows-style save paths created garbage folders

The demo-era engine builds its save root as `nw.App.dataPath + "\\Saves\\Default\\"`. The port sets
`dataPath` to the literal `/saves`, so the bridge receives `/saves\Saves\Default\`. `FsBridge` gates
every namespace decision on `isSavePath` ("`/saves` or under `/saves/`") - except **`mkdir`**, which
went straight to `saveRel`. A path that was not a save path was therefore split as one and handed to
SAF, whose `buildValidFatFilename` rewrites the illegal `\` characters: the picked saves folder grew
`_Saves_`, `_Saves_Default_`, `_Saves_Backups_`, `_Saves_Backups2_` (and `_Saves_ (1)` on a second
run), while the demo's actual save reads/writes went nowhere. The released engine never showed this
because 0.1.0 uses forward slashes (`/Saves/Default/`) - and it even carries a `STORAGE_FIX` table
repairing `"/Default_Saves_Default_"` for players who hit this on the desktop.

Fixed in two parts: `mkdir` now gates on `isSavePath` like every other method, and the save-relative
mapper treats `\` as a separator, so the demo's paths resolve to the same `Saves/Default` Steam layout
instead of missing. `FsBridgePathTest` covers the rule (`/saves\Saves\Default\` -> `Saves/Default`,
`/Default\Saves\Default\` and `/savesx` rejected, `..` refused).

Verified on the emulator against a fresh saves folder: the demo now creates exactly
`Saves/`, `Saves/Default/`, `Saves/Backups/`, `Saves/Backups2/` and nothing else, and the release
build still boots, rewrites its ladder, relabels its options and writes the same layout.

## 13. CrossCode, run through the same idea (2026-09-26) — and how far a browser shim gets it

Asked whether this project could run CrossCode as well. The question was answered with the same probe
§4.3 used for Alabaster Dawn: serve the game's own install over HTTP, inject a shim with
`init_scripts`, load the entry page in Chromium, and read what breaks. CrossCode was not reverse
engineered for this — one pass, one shim, then the game.

### 13.1 The specimen

Steam, `steamapps/common/CrossCode`, **1.0.0** (`package.json`), 1.2 GB, **4 865 files / 447 dirs**.
An NW.js app like Alabaster Dawn, but a *different engine*: `main = assets/node-webkit.html` →
`assets/js/game.compiled.js` (3.8 MB, plain obfuscated JS) on **Cubic Impact 0.5** (`impact/page/js/*`,
jQuery 1.11 + jQuery UI). `IG_WIDTH 568 × IG_HEIGHT 320`, `IG_GAME_SCALE 2` → 1136x640. The window
title reports the game's own version, `v1.4.2-4`. No webpack, no bundle patching: the entry is an
ordinary HTML page with `<script>` tags.

### 13.2 Result: it boots to its title screen and its menus

With a **6 KB first-cut shim** (`tools/cc-browser-shim.js`) injected before the page's scripts:

* `ig` initialises; `ig.getPlatformName()` = **Desktop**; `ig.engineName` = `Cubic Impact (0.5)`
* the game's own **loading screen** draws, then the **title screen** ("Press to start", `v1.4.2-4`)
* **Enter** advances to the game's **main menu** (New Game / Load Game / Options / Exit / Enter Bonus
  Code) with its HTML "Changelog" button
* `ig.system.running = true`, canvas 1136x640, **0 page errors**, 2 console lines
  (`INIT FULLSCREEN VALUE true`, `EXTENSIONS:` with an empty list)

The single failure of the first attempt was the shim, not the game: `TypeError: a.readdir is not a
function` in `ig.Extensions.loadExtensionsNWJS`. That function wants `fs.readdir(dir, cb)` (callback
form), `fs.lstatSync(p).isDirectory()` and `fs.existsSync(p + name + ".json")`. Adding them produced
the run above.

Caveats, measured not assumed: this was headless Chromium (software GL) at 1136x640, so it says
nothing yet about phone GPU cost; no audio was verified; nothing past the main menu was played; saves
were not exercised.

### 13.3 Why it is this close: the platform switch is explicit

```js
ig.platform = window.require && typeof window.process === "object" ? DESKTOP
  : window.nwf ? WIIU
  : (dataOS == "Android" || dataOS == "iOS") ? MOBILE
  : (ig.browser != "Unknown") ? BROWSER : UNKNOWN;
```

Defining `window.require` **and** a `process` object selects **DESKTOP**, the NW.js path the game is
built around — which is exactly the shape this port already implements for Alabaster Dawn. Note the
trap: leaving both undefined does *not* give BROWSER on a phone, because the WebView UA contains
"Android", so the engine would pick **MOBILE** (`loadExtensionsPHP`, `TrackDefault`, …). DESKTOP is
the intended target, not a workaround.

`ig.isPlatform`/`PLATFORM_TYPES` also carry per-setting `browser` overrides (`c.browser &&
ig.platform == BROWSER ? c.browser : c.init`), and `loadInternal` splits
`loadExtensionsNWJS()` (DESKTOP) from `loadExtensionsPHP()` (everything else) — so DESKTOP gets the
real code paths.

### 13.4 The API surface to shim, counted

| what | where | count |
|---|---|---|
| `require("fs")` | `game.compiled.js` | 7 |
| `require("nw.gui")` | `game.compiled.js` / `game-base.js` | 5 / 2 |
| `require("./modules/greenworks-{0.4.0,0.5.3,0.13.0,nw-0.35}/greenworks")` | picked by `nwjsVersion` | 4 variants, used: `init`, `initAPI`, `isActive`, `activateAchievement`, `clearAchievement` |
| `nw.Clipboard.get` | `game.compiled.js` | 1 |
| `process.platform` / `versions` / `env` / `arch` | `game.compiled.js` (+`versions` in `game-base.js`) | 2 / 1 / 1 / 1 |
| `localStorage` | saves and every option | 31 (18 `setItem`, 13 `getItem`), keys `IG_*`, `cc.*`, `options.*` |
| `window.startCrossCode` | the handshake `doStartCrossCodePlz()` polls for | 1 |

NW-specific paths are already guarded in the game's own code (`if(window.require) … else <a
target=_blank>`), which is the same "browser platform" pattern Alabaster Dawn has.

**Saves are `localStorage`** (`cc.save*`), not files — so a WebView persists them with no SAF mapping
at all, and moving saves between devices is the game's own Save-String dialog (`window.SHOW_SAVE_DIALOG`,
`sc.submitSaveImport`), the mechanism CrossAndroid also uses. `fs` is needed only for the extension
list (`assets/extension`), i.e. for mods.

### 13.5 What that implies for the port

Reusable untouched (~2.5k of the 4.7k Kotlin lines): the WebView host (immersive, page blur/focus,
pause/resume, renderer-crash rebuild), SAF serving of the user's picked folder + tree index, the
on-screen pad and its editor, the W3C gamepad bridge (CrossCode reads standard pads), the side menu,
the diagnostics record/log, the release pipeline.

Left to do:

* a **per-game profile**: this port's `terra/` paths, entry URL, rewrite set, save layout and version
  source are 37 literals across 6 files;
* **shim v2**: the old `nw.gui` alias, `fs` backed by the Kotlin bridge over the read-only game tree,
  greenworks stubs, `process`, the `startCrossCode` handshake;
* **entry injection**: the document-start script alone is enough (no bundle patching);
* **scale/fit**: the engine's own 568x320 x2 canvas vs a phone screen;
* **none of Alabaster Dawn's asset rewrites** apply (no resolution ladder, no 256-slot uniform table,
  no barycentric shader patch) — different engine, different problems;
* a **version source** for the record (the game's own `v1.4.2-4`, `ig.engineName`).

Estimate, calibrated on this repo's own history (0 → 0.5.1 in 31 commits over four days, building the
host *and* discovering the engine): **1–2 sessions** to boot to the title screen on a phone, **1–2
more** for saves, audio, fullscreen/scale, pad mapping and the side menu, **1** for device
verification and packaging — call it **4–6 focused sessions** to a playable CrossCode, against ~4 days
from zero.

Open and unanswered: phone-GPU cost at 1136x640 (headless SwiftShader rendered it fine, which is
encouraging but not a measurement); audio; the game's own fullscreen/scale option vs the port owning
immersive mode; whether mods (`assets/extension`) are wanted; and the product shape — one app with two
games, or a shared core with a second thin APK.

---

## 14. Mouse, keyboard, and the Back key that ran twice (2026-09-29)

The port played through a controller or the on-screen pad; a mouse and a keyboard were outside the
design. Making them work turned up three facts worth keeping: a mouse click **is** a touch event, the
engine's keyboard bindings match on `event.code` and nothing else, and on API 33+ a Back key is
delivered twice.

### 14.1 A mouse click is a touch event, and the pad claimed every one

`MotionEvent.isTouchEvent()` is `isFromSource(source, CLASS_POINTER) && action ∈ {DOWN, MOVE, UP,
POINTER_*, CANCEL, OUTSIDE}` (`frameworks/native/libs/input/Input.cpp`), so `View.dispatchPointerEvent`
sends a mouse click to `dispatchTouchEvent`, and `ViewGroup.dispatchTouchEvent` walks children in
reverse draw order. The pad is added after the WebView and consumed any DOWN while the controls were
drawn — the click never reached the page. Hover is `ACTION_HOVER_MOVE`, *not* in that action set: it
goes through `dispatchGenericMotionEvent`, the pad overrides neither it nor `onHoverEvent`, and that is
why the game's cursor moved under a mouse while clicking did nothing.

Fix, in `OnScreenPadView.onTouchEvent`, before any claim:

```kotlin
if (isPointerInput(event)) return false   // getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
```

* Tool type, not source bits: `SOURCE_MOUSE_RELATIVE` has no `SOURCE_MOUSE` bit but is still a mouse.
* `MotionEvent.TOOL_TYPE_TRACKBALL` **does not exist** (it fails to compile); and a trackball produces
  no `MotionEvent` here to test against, since `input` sends none.
* Instrumented build, `input mouse tap x y`: `DBG touch a=0 tool=3 src=8194` (DOWN, `TOOL_TYPE_MOUSE`,
  `SOURCE_MOUSE`) then `a=1` — so an injected click takes exactly the path a real mouse takes.
* Hiding is noted in **both** dispatch paths, because neither covers the mouse alone: hover/scroll only
  in `dispatchGenericMotionEvent`, a click with no preceding movement only in `dispatchTouchEvent`.
  Neither call consumes the event.

### 14.2 The keyboard reached the page but played nothing: `code: ""` without a scan code

Delivery was never the problem. `DecorView.dispatchKeyEvent` → `Activity.dispatchKeyEvent` →
`window.superDispatchKeyEvent` → the focused WebView (`view.requestFocus()` at launch) → renderer →
`keydown` on `window`. A probe injected at document start proved it — a `window` listener fired for
every key — so the only port-side work was to hide the pad. The game still did not react.

The engine (bundle, input class) binds on `event.code` alone:

```js
initKeyboard() { if (this.isUsingKeyboard) return; this.isUsingKeyboard = true;
                 this.updateKeyboardMap();
                 window.addEventListener('keydown', this.keydown.bind(this), false); … }
updateKeyboardMap() { const keyboard = navigator.keyboard; keyboard.getLayoutMap().then(…); }
keydown(event) { … const code = event.code; if (this.startActions(code)) { event.stopPropagation(); event.preventDefault(); } this.swapToKeyboardMouse(code); }
startActions(key) { … const actions = this.bindings.inputs.get(key); … }
```

Chromium derives `code` from the **scan code**. An event with no scan code arrives as `code: ""`,
matches no binding, and plays nothing. The probe printed the two states side by side:

```
kb=object code= prevented=false          # the Android key, as the page saw it
code=ArrowRight prevented=true           # the same key re-dispatched with its DOM name
```

`kb=object` matters twice over: the engine calls `navigator.keyboard.getLayoutMap()` *before* it
registers its two key listeners, so a WebView without the Keyboard API would throw inside
`initKeyboard()` and have **no keyboard at all** — Android WebView here does implement it, so the only
missing piece was the empty `code`.

Fix, in `PortActivity.dispatchKeyEvent`, after the pad/volume bookkeeping:

```kotlin
if (event.scanCode == 0) {
    val keyEvent = domKeyEvent(event)          // null when the Android code has no DOM name
    if (keyEvent != null) { webView?.evaluateJavascript(keyEvent, null); return true }
}
return super.dispatchKeyEvent(event)
```

`domKeyEvent` names `KeyA..KeyZ`, `Digit0..Digit9`, the four arrows, `Enter`/`NumpadEnter`,
`Escape`, `Space`, `Tab`, both `Shift`/`Control`/`Alt` pairs and `F1..F12`, and dispatches
`window.dispatchEvent(new KeyboardEvent('keydown'|'keyup', {code, key, bubbles, cancelable, repeat}))`.
Rules that fall out of this:

* **The rule is per event, not per device** (`scanCode == 0`): any source that sends a scan-code-less
  key — `input`, some remotes and shortcut buttons — gets the synthesized event; a keyboard's keys
  carry their scan code and take `super`, i.e. the WebView's own mapping, untouched.
* Unmapped code → `null` → the WebView sees the event as before, so the volume keys and everything the
  port does not name keep working.
* Consuming the original avoids the page seeing the empty-code copy as well.
* `key` is carried because the engine reads it for its never-ignored keys (`Control`, `c`/`v`/`x`).
* Measured: `key KEYCODE_DPAD_DOWN -> page (scan 0)` in the record, holding `D` walked the character
  across the map, and `prevented=true` for `ArrowRight` and `KeyD` (the engine matched a binding).

The log line keeps the scan code on purpose: it is the field that separates "the port never saw the
key" from "the page got an unusable `code`".

### 14.3 Back ran `handleBack()` twice on API 33+

The port takes Back itself — the key in `dispatchKeyEvent` on every version, and the gesture via an
`OnBackInvokedCallback` registered in `onCreate`. On Android 13+ the framework routes the Back **key**
to the registered callback too, so one press ran both:

```
back: dispatcher (game)     # opens the menu
back: key (game)            # closes it again, same dispatch
```

Net effect: Back did nothing at all on the S22 Ultra (API 36) — a screenshot cannot show a menu that
opened and closed inside one dispatch, which is why the device reports read as "the Back button does
not open the menu" (the AYN Odin 3 report is the same shape). The record's two lines per press are
the tell. Fix: the callback owns Back from API 33 up (the key is swallowed there), and the key path
acts only below 33, where no callback exists.

### 14.4 One switch, and the preference key that must not move

Mouse and keyboard hide the overlay under the controller's own switch, not unconditionally, so the
preference keeps its stored key `hide_with_controller` while the UI text and the `CONTROLLER_IDLE_MS`
constant (still 60 s) move to "external input". Renaming the stored key would silently reset the
choice of every existing install.

### 14.5 Recipes

* Click as a mouse: `adb shell input mouse tap X Y`; hover cannot be injected at all — `input
  motionevent` takes only `DOWN|UP|MOVE|CANCEL`, and the input dispatcher drops a `MOVE` with no
  pointer down (the Activity saw no event), so use `input mouse scroll X Y --axis VSCROLL,2` for the
  generic-motion path.
* Key as a keyboard: `adb shell input keyboard keyevent <code>`, `--duration <ms>` to hold it (that is
  how a held `D` is produced); every event has `scanCode = 0`, which is the whole problem above.
* Probe the page without a debug build: temporarily add a second
  `WebViewCompat.addDocumentStartJavaScript` beside the shim one — it runs before the page's own
  scripts and can render its findings into a fixed `<div>` that `screencap` captures.
* Confirm the game is *animating* (not frozen) before concluding an input is ignored: two screencaps
  three seconds apart, `PIL` pixel diff (`~700k` changed pixels on a live map).


---

## 15. Picture alignment moved the picture, not the click target (2026-09-29)

Reported against the shipped side menu: **with the picture aligned Top, mouse clicks do nothing**.
The picture really had moved to the top, so the shim's `object-position` write did what it said; the
clicks were landing ~460 game pixels away from the cursor.

### 15.1 The engine's own mouse mapping assumes a centred letterbox

`bundle.js`, `Input`:

```js
static getMouseCoordsC(dest, mouseX, mouseY, dom) {   // dom = g_system.canvas
  let el = dom; c_input.setC(0, 0);
  while (el != null) { c_input.x += el.offsetLeft; c_input.y += el.offsetTop; el = el.offsetParent; }
  dest.x = (mouseX - c_input.x); dest.y = (mouseY - c_input.y);
}
```

`mousemove` then applies `g_system.displayScale.transformMouse(this.mouse)`; every
`DISPLAY_SCALE` variant (`CONTAIN`, `FULL_PIXEL`, `ORIGINAL`) computes

```js
const scale1 = Math.min(screenSizeX / canvasSize.x, screenSizeY / canvasSize.y);
const deltaY = screenSizeY - scale1 * canvasSize.y;      // the letterbox the *engine* believes in
mouse.y = (mouse.y - deltaY / 2) * 1 / scale1 / scale;   // …and it believes it sits ABOVE the picture
```

and `screenSizeX/Y` are `canvas.clientWidth`/`clientHeight` — the **element's client box**, not the
window. So the engine has exactly one model of the canvas: the picture is centred inside that box,
by `deltaY/2` in each axis. `getMouseCoordsC` picking up `offsetTop` is what makes any *element-box*
move of the canvas self-correcting.

The shipped alignment moved the picture inside the box with `object-position: 50% 0%` (Top) /
`50% 100%` (Bottom). Layout never moved, `offsetTop` stayed 0, and every click was then interpreted
`deltaY/2` below the picture. The miss is half the black letterbox band, i.e. it scales with the
device:

* the probe geometry below (viewport 400x800, buffer 1280x720, game scale 2): deltaY/2 is **287.5
  screen px**, and the engine reads it as **460.8 game px** (0.625 screen px per game px);
* the port's default 640x360 rung on a 1080x2340 phone: the picture is 1080x607.5 in a 2340 px tall
  window, so the miss is **866 screen px** — a whole screenful.

Centre worked by construction (deltaY/2 *is* the centre); Bottom was wrong by the same amount,
mirrored.

### 15.2 Fix: move the element's layout box, leave the picture centred inside it

`assets/ada-shim.js`, `applyViewAlign`/`alignTopFor`. The canvas is `position: absolute; inset: 0;
margin: auto` (engine.css), so writing `top` pins it; the picture stays at the element's own centre
(`object-position` untouched), and the two sides agree because the box's top and the picture's
visual top differ by the same `deltaY/2` the engine subtracts:

```
top = desired - deltaY/2        desired = 0 (Top) | boxH - pictureH (Bottom)
```

`deltaY = canvas.clientHeight - scale1 * canvas.height` is computed from the element itself, so the
formula is mode-agnostic: with `sharp-pixels` on the element is pinned to an integer multiple of the
buffer, `deltaY` is 0, and it degenerates to pinning the box to Top/Bottom (which is what the
FULL_PIXEL path already did). Non-centre modes re-measure each frame, and `top` is rounded to whole
pixels so it agrees with `offsetTop`, which reads as an integer. The engine never writes
`canvas.style.top`/`bottom` (it writes `width`/`height`/`display`/`imageRendering` only), so there is
no fight over the property.

### 15.3 Measured

Verification was a headless Chromium with the game's real `engine.css`, the real injected shim asset,
and the game **booted** (local static server, `XG_GAME_DEBUG` forced true — `index.html` sets it
false and `AddDocumentStart`-style init can only re-set it *after* that line, so the probe defines the
global as a getter that swallows the write). Real CDP mouse events, pointer paths, engine listeners:

| alignment | canvas `top` | picture on screen | `g_input.mouse` at the picture's centre / at 25 % of its height |
|---|---|---|---|
| Centre | `""` | 287.5–512.5 | `(320, 180)` / `(320, 88.8)` |
| Top | `-287px` | 0.5–225.5 | `(320, 180)` / `(320, 88.8)` |
| Bottom | `288px` | 575.5–800.5 | `(320, 180)` / `(320, 88.8)` |

Same picture-relative point, same game coordinate, in every alignment (viewport 400x800, buffer
1280x720, scale 2: 0.625 screen px per game px, so the numbers are exact to the sub-pixel). The
**old** code on the same live engine maps the picture's centre to `(320, -280.8)` instead of
`(320, 180)` — the 460.8 px miss above, reproduced end-to-end.

Also exercised: an element that is `display: none` (measurements 0 -> the write is skipped, not
applied as `0px`), a viewport resize while aligned (the offset is recomputed the next frame), and
mode switching on a live element. The same probe against the engine's mapping formulas covers the
`sharp-pixels` ON case (`deltaY` 0: box pinned to the alignment) and both phone buffers.

Not verified on a device: no phone was attached, so the Android mouse path itself was not re-run —
`input mouse tap` at a title-screen menu entry is the check for whoever has the phone.

---

## 16. The GUI's shaded fills quantise on a mobile GPU (2026-09-29)

Reported with two screenshots of the same Options screen, the port's and Steam's: the **selected side
menu row** (and the slider track beside it) renders as a regular stripe pattern in the port while the
Steam build renders a flat lighter grey.

Measured on the two images:

* the *base* colour is identical (83–84 of 255) — the port adds a bright vertical line (peak ~147),
  2 px wide, every 16 screen px, identical in every row;
* that is 8 render px at the reporter's setup (`640x360`, Integer Scaling On ⇒ exactly 2x), i.e. the
  pattern is one *render* pixel wide on an 8-pixel grid;
* only the *shaded* GUI fills carry it: the selected row and the slider track. The panel gradient, the
  text, the icons, the tooltip and the world behind the dialog are clean, and the black bars outside
  the picture are pure 0 — so it is inside the canvas, not the compositor or the screenshot.

What it is not: the engine's ordered dither (§15's terrain grid is a 4x4 `gl_FragCoord` dither in the
world shaders, 4 render px period, and the GUI shader has no dither at all). Nor is it the port's own
slot-table rewrite: served the port's rewritten bytes (`TEX_SLOT_COUNT 192` + packed `gui.vert`) to a
Chromium harness, the same fills render **flat** (std 0.00), exactly as with the game's own bytes.

What it is: the *only* shaders in this path that declare `precision mediump float` are the GUI's
(`gui.frag`, `gui.vert`, plus `gui-bg.frag`/`gui-blur.frag`); every world shader is `highp`. Desktop
GL has no real mediump and promotes it, which is why the same bundle at the same settings is flat on
Steam; Adreno honours fp16, and the styles that produce these fills are exactly the ones doing
screen-space `floor()`/`mod()` (the checker shade) and `fract()` tiling — the maths an fp16
coordinate turns into a regular comb.

Fix: `ShaderPrecision` serves those four shaders with `precision highp float;` — ES 3.0 guarantees
highp in both stages, and where mediump already means highp the declaration is the only byte that
changes.

Verified on the Fold 7 (`SM-F971B`, Adreno 840, WebView 153) with the built APK installed: the same
element that carried the comb — the selected row of the game's own menu — now measures flat
(mean 68.9, std 8.1 over its fill, no peak at any lag up to 30), where the reporter's build measured
std 19.7 with `ac[16] = 0.79` at the same element. The panel, text and icons are unchanged.

## 17. The uniform budget was the wrong question (2026-09-29)

The same device's diagnostics record carries two sessions: `port 0.4 (4)` booting 1 757 resources with
`gl=Adreno (TM) 840` — i.e. **before** `ShaderSlots` existed — and the current build, which reports
`gl limits: vertex uniforms 256 -> TEX_SLOT_COUNT 192`. So the Fold 7 never needed the rewrite, while
its `MAX_VERTEX_UNIFORM_VECTORS` is the GLES3 floor of 256.

That is not a contradiction: the count is what the *driver reports*, not what a *shader carries*.
GLSL ES 3.0's default-block packing lets a compiler put two `vec2` array elements in one `vec4`, so
Adreno fits the game's 256-slot table into ~128 vectors of its 256 — the table links. SwiftShader packs
one element per vector, needs 256 plus the shaders' own ~48, and really does fail with `too many
uniforms` (§10.9, issue #1). Deciding from the number therefore rewrote every shader on a device that
never needed it — shrinking the engine's atlas and packing `gui.vert` for nothing, which is a
rendering path no desktop player ever runs.

Fix: the decision is now a **link**. At document start the shim compiles two throwaway programs — one
`vec2[256]` with a 48-vector reserve (every `.vert`'s shape), and two of them with 32 (`gui.vert`) —
with the arrays indexed dynamically so the compiler cannot drop them, and reports the outcomes through
`AdaBridge.setShaderTables(oneTable, twoTables)`. `ShaderSlots.slots()`/`packs()`/`rewrites()` then
keep the game's own 256 and leave the tables unpacked when the page linked them, and fall back to the
count-based plan for a page that answered `false` or never answered at all (still the GLES3 minimum,
so the failure mode is unchanged).

Verified: unit tests cover linked / not-linked / unanswered; the shim harness checks that the probe
asks for 256 slots with the real reserves and that a compiler which fails the link reports `false`;
and the probe itself was run against a real GL — `links` at 256 slots, `does not link` at 4096 and
8192, so it is genuinely sensitive rather than a rubber stamp.

Verified on the Fold 7, same build: the record now reads

```
+12598ms gl limits: vertex uniforms 256
+12599ms shader tables: 256-slot links, gui two-table links -> TEX_SLOT_COUNT 256
+12672ms rewriting the resolution ladder in terra/dist/bundle.js
+16404ms ENGINE boot: complete in 3804ms, 1757 resources; audio=running; decodes started=608 done=608 failed=0
```

with **zero** `TEX_SLOT_COUNT … in terra/data/shader/…` lines — the game's own shaders, its own atlas
size and its own `bundle.js` constant, where the previous build rewrote all twelve vertex shaders and
packed `gui.vert`.

The Fold 7 is not the only device this applies to: `docs/release-notes-0.4.2.md` records the S22
Ultra's Adreno 730 logging the same `vertex uniforms 256 -> TEX_SLOT_COUNT 192`, so every Adreno
device seen so far has been served the rewritten shaders whether or not its compiler needed them.
The link is what says so, and it is now the only thing that decides.

---

## 18. The grid in the terrain is the engine's own dither (2026-09-30)

Issue #3 (Jherben) reports "a grid texture in the terrain" over the flat ground and hills, with the
reporter's own guess attached: "Maybe that was caused due to lowering the res so the game could run?"
The guess is half right, and the half that is wrong is the one that matters: the dots are the engine's
ordered dither, on a path the port serves byte-for-byte, and the grid's period on screen is the same at
every resolution option. What the option changes is how big each dot is.

### 18.1 What makes the dots

`terra/data/shader/lib/dithering.glsl` is a 4x4 ordered threshold table in 17ths, indexed by
`uint(uv) % 4`. The light pass, `solid-simple-light.frag`, ends with

```glsl
vec2 screenCoord = gl_FragCoord.xy / (u_screenScale * u_ditherScale);
float ditherValue = dither(1.0 - v_fade.x, screenCoord, uint(v_fade.y));
if(v_fade.x > 0.0 && ditherValue < 0.0) discard;
```

so a light's fade is a **binary** stipple: a fragment is discarded and the unlit layer behind it shows.
At a fade of 0.5 the 4x4 table passes half its cells and the pattern is the checkerboard in the report.
Fourteen fragment shaders import the lib (`solid`, `solid-back`, `solid-overlap`, `solid-simple`,
`solid-simple-light`, `decal`, `color-tex`, `parallax`, `shadow-map`, `fx-billboard`, `fx-decal`,
`fx-mesh`, `water-plane`, `water-fx-wall`) and four vertex shaders import it for the varyings.

`u_ditherScale` is 1 (`solidInitSetup`), `u_screenScale` is `g_system.scale`, and the render target is
`SCREEN (640x360) * scale` (`canvasSize.x = width * scale`, bundle 34757) — so on this path **one dither
cell is one art pixel**, which is why the dots are the size of an art pixel on screen.

The engine is not uniform about this. Five shaders divide by `u_screenScale * u_ditherScale` (the art
grid): `solid-simple-light`, `solid-back`, `solid-overlap`, `solid-simple`, `shadow-map` — and the
reporter's dots sit in the light pass's fade band [INFERENCE: a uniform stipple over a flat lit area is a
fade value, not the radial dither `solid-back` computes]. `solid.frag` divides by `u_ditherScale` alone, and
its radial dither uses raw `gl_FragCoord.xy` — the render grid, where the dither is a per-pixel texture
and invisible. The engine's own precedent for the finer grid is therefore in the file next door.

### 18.2 The port does not touch it

`ShaderSlots` rewrites exactly two strings, `#define TEX_SLOT_COUNT 256` and `const TEX_SLOT_COUNT = 256;`.
Neither appears in the fragment shaders or in the dithering lib (`grep -c`: 0 in
`solid-simple-light.frag`, 0 in `lib/dithering.glsl`, 2 in `solid.vert`), so this path is served
byte-identical. `ShaderPrecision` (§16) serves only the four GUI shaders.

### 18.3 Measured: the period does not depend on the resolution option

Harness: the engine's two sources above, verbatim, rendered at `canvasSize = 640s x 360s` with
`u_screenScale = s`, `u_ditherScale = 1`, and the canvas laid out at 1920 CSS px — which is what the
engine's own fit does (`scale1 = min(screenSize/canvasSize)`, bundle 34530). Row autocorrelation of the
frame at fade 0.5:

| ladder rung | canvas | CSS scale | stipple | period, buffer px | **period, screen px** |
|---|---|---|---|---|---|
| 640x360 (scale 1) | 640x360 | 3 | 50 % | 2 | **6** |
| 960x540 (1.5 — the default) | 960x540 | 2 | 44 % | 3 | **6** |
| 1280x720 (2) | 1280x720 | 1.5 | 50 % | 4 | **6** |
| 1920x1080 (3) | 1920x1080 | 1 | 50 % | 6 | **6** |
| 2560x1440 (4) | 2560x1440 | 0.75 | 50 % | 8 | **6** |

Two cells is the checkerboard's own row period; the 4x4 pattern repeats every 4 cells, i.e. 12 screen px
at 1920. `s` cancels: the period on screen is `4 * display width / 640`, a fixed fraction of the screen,
at every option — and so is the cell, one art pixel (3 CSS px in this harness, 1.5 CSS px on a 1080p
phone panel). The picture always fills the display, so **nothing in this artifact changes size with the
resolution option**; what changes is only whether the dots are rendered crisp or smoothed (§18.5). (The
stipple column is the fraction of pixels the pass discarded, so the artifact is not a 1-LSB dither: at
the reporter's fade it throws away half the light.)

### 18.4 The reporter's screenshot

260x260 of the flat hill inside the red marking: the dark dots measure 4.0 px (median blob area) and
their nearest-neighbour spacing 11.3 px — the 4-cell repeat of this dither at the ladder's default
960x540 canvas, i.e. cells of 1.5 buffer px, 6 CSS px per repeat, 12 physical px at devicePixelRatio 2.
The device's density is not in the report, so the exact factor is [INFERENCE]; the dot size and the
lattice's order of magnitude are not. 1.5 buffer px is also why that lattice is not perfectly uniform —
the row FFT carries close peaks (15.06/16/17) rather than one, the beat of a fractional cell.

### 18.5 What the option does change

Crispness. The engine sets `canvas.style.imageRendering = width % cWidth == 0 ? "pixelated" : "auto"`
(bundle 34782), where `width` is the canvas's **CSS layout width** (`clientWidth`) and `cWidth` its
buffer width: an exact integer fit is crisp, anything else is smoothed toward a tint. The phone ladder
is `[1, 1.5, 2, 3, 4]` (`GameAssetHandler.RESOLUTION_MAP_PHONE`) and its default is 960x540
(`relabelResolutions`'s note). Measured in the harness against a 960x540 CSS viewport — a 1920x1080 panel
at devicePixelRatio 2, i.e. the reporter's panel — with the engine's own fit and rule:

| rung | buffer | canvas CSS | fit | `image-rendering` | rendered |
|---|---|---|---|---|---|
| 640x360 (1) | 640x360 | 960x540 | 1.5x | `auto` | dots wash into a tint |
| **960x540 (1.5, the default)** | 960x540 | 960x540 | **1x** | **`pixelated`** | **hard checkerboard** |
| 1280x720 (2) | 1280x720 | 960x540 | 0.75x | `auto` | dots wash into a tint |
| 1920x1080 (3) | 1920x1080 | 960x540 | 0.5x | `auto` | dots wash into a tint |

So the default is the only rung that both fills the panel exactly *and* renders crisp — which is why the
artifact is at its most visible exactly where the game lands by default. It is also the ladder's only
non-integer rung, so it is the one rung where the dither's own cells are aliased (1.5 buffer px) rather
than uniform. Every other rung softens the whole picture, art included, to `auto`. The picture cannot be
made "smaller but still an even multiple": at the default the fit is already exactly 1 (one buffer px =
one CSS px = two device px), and the next integer fit is 2x — a canvas twice the panel.

### 18.6 Fixed: served at the render grid

`ShaderDither` serves those five shaders with `gl_FragCoord.xy / u_ditherScale` — the engine's own
render-grid convention, the one `solid.frag` and the water's radial dither already use. Nothing else
changes: the same thresholds, the same `discard`, the same varyings; `u_screenScale` stays declared
and set. Measured in the same harness, at the default rung (960x540, the reporter's):

| served divisor | cell | stipple | row period (buffer px) | on screen |
|---|---|---|---|---|
| `u_screenScale * u_ditherScale` (the game's) | 1.5 buffer px, aliased | 44 % | 3 | 6 CSS px, uneven |
| `u_ditherScale` (the port's) | 1 buffer px, uniform | 50 % | 2 | 4 CSS px, uniform |

At scale 1 the two are identical (the divisor is `1 * 1` either way), so the change costs nothing at
the ladder's lowest rung and makes the dither as fine as the buffer allows everywhere else. Verified
on the emulator: five `dither at the render grid in terra/data/shader/fragment/…` lines, `ENGINE boot:
complete in 6978ms, 1757 resources`, `608/608` decodes, no shader error, and the frame renders. Unit
tests: the five are rewritten, a shader already on the render grid (`solid.frag`, the water) and a
non-shader are returned as they came, and the rewrite is idempotent. Confirmed on the Fold 7 with the
build below: the terrain's grid is gone and the shading reads even where the dots were.

Rejected, with the reason:

* **An all-integer ladder** (dropping the 1.5 rung): it removes the aliasing, but §18.5's table shows
  every remaining rung is a fractional fit on a 1080p panel, so the engine would hand the *whole*
  picture to `image-rendering: auto` — soft art for a soft dither. 1.5 also exists to fill that panel
  exactly, and `relabelResolutions`'s note says the default was chosen to hold 60 fps.
* **Making the picture smaller so it is an even multiple**: at the default the fit is already exactly
  1 (one buffer px = one CSS px = two device px on that panel) and the next integer fit is 2x — a
  canvas twice the panel. The fractional part is the engine's `u_screenScale`, not the fit.

---

## 19. The one setting with no way back (2026-09-30)

The Resolution option is the only value a user can set that can leave the port unusable *by being
slow*: at 2560x1440 the engine renders 8.3x the pixels of the ladder's 960x540 default, and the game's
own Options menu — the only in-game way to change it back — is then the slowest thing on the screen.
Asked for by the maintainer after issue #3's thread ("setting the resolution too high could make it
hard to change it back"), and it is real: nothing in the engine caps the option.

### 19.1 Nothing caps it

`updateGraphicSettings` (bundle 99830) reads the option and applies it verbatim:

```js
let resolution = Fetch.val(g_options.get("pixel-size"), 1);
const maxScale = 1000 || 0;
const scale = Math.min(maxScale, RESOLUTION_MAP[resolution]);
g_system.setScale(scale);
```

`maxScale` is a literal `1000`, so the ladder is the only limit. The canvas then becomes
`SCREEN (640x360) * scale` (34757) — 2560x1440 at scale 4 — which the engine fits to the panel by
*downscaling* it (`scale1 = min(screenSize/canvasSize)`, 34530). The picture is the right size; the
frame cost is eight times the pixels: on SwiftShader that is the difference between a playable port and
a slideshow — the option's own text asks the user to lower it for performance ("Reduce to improve
performance"), and nothing else does.

### 19.2 Where it is stored, and why the app can help

The option is registered `local: true` (`terra/data/database/options.json`), and the engine keeps every
such option as one JSON object in `localStorage` under `xg_local_options` (bundle 24828, written by
`OptionsManager.writeLocalStorage` 25036):

```js
const data = {};
for (const key in this.settings) {
    const option = this.settings[key];
    if (option.local) data[key] = Fetch.val(this.values[key], option.default);
}
localStorage.setItem(LOCAL_STORAGE_KEY, JSON.stringify(data));
```

`readLocalStorage` fills `this.values` from it at boot. So the value is not in the game's files, it is
in the WebView's storage for the port's origin — the port can ask the page to drop it, and the game
folder stays untouched.

### 19.3 The reset, and how it is carried

`Reset resolution` lives in the port's own **Troubleshoot** panel, next to Share and Close — the panel
that opens from the setup screen's Troubleshoot button (always reachable after a relaunch) and from the
side menu's own entry in-game. Both doors lead to the same button, and the panel is the port's own, so
it stays usable when the page does not. The button calls `PortActivity.resetVideoOptions`: with a live
WebView it reloads the game immediately, and before the game is up it arms a one-shot preference for
the next start.

The carrier is the URL: the game is loaded as `.../index.html?adaResetVideo=1`, and the shim — which
runs at document start, before the engine reads the option — drops the key
(`ada-shim.js`, `resetStoredResolution`):

```js
var data = JSON.parse(store.getItem("xg_local_options"));
delete data["pixel-size"];
store.setItem("xg_local_options", JSON.stringify(data));
```

The engine then holds the option's own default, which the phone ladder's default rung (960x540) is. Only
that one key goes, every other stored option survives, and a malformed blob is reported rather than
thrown. The one-shot is cleared as the URL is built, so a kill in between cannot leave it half applied;
the parameter is named in both files and in `PortActivity.RESET_VIDEO_PARAM` so the two sides cannot
drift.

Verified: `node android/tools/test-shim-diagnostics.mjs` grew 7 checks (36/36) for the key-removal, the
untouched case, the malformed blob and the nothing-stored case; on the emulator the shim reports
`ENGINE video: stored Resolution dropped; the game boots at its default` and the game boots at
`resolution=960x540`, and the held-back key is gone; and on the Fold 7 with the build below the
maintainer confirmed the button does what it says. The blob is re-written by the engine at each boot,
so a second press legitimately finds a Resolution again — that is the engine persisting the default the
reset restored, not the reset failing.

### 19.4 Two things found on the way

* The record had no way to see the rung: the diagnostics line now carries `resolution=WxH`, read from
  the canvas the engine renders into (the same element the stats readout reports). A report about "too
  slow" or "the dots are large" needs that number and it was previously only on screen, in the stats
  overlay, which needs the side menu.
* **Back**: on the emulator's API 34, `input keyevent 4` produced neither the `back: dispatcher` line nor
  a panel. The framework is expected to route the Back *key* to the registered
  `OnBackInvokedCallback` on API 33+ — it did on the API 36 device in §14.3 — so the *key* path looks
  emulator-specific. The *gesture* is the callback's own path and is unaffected: the maintainer
  confirmed on the Fold 7 that Back still opens the side menu. Either way the setup screen's
  Troubleshoot panel is the door that never depends on the page.

## 20. The recents entry, and a frame-rate cap (2026-09-30)

Two requests from the maintainer, both about the way the game *ends* rather than how it runs: the app
disappeared from the launcher's task list after an Exit, and playing on the go burns battery that a
frame-rate cap would halve. The cap is a switch with a rate slider under it (§20.2).

### 20.1 Why the app left the task list

The exit path (§11) was `teardownWebView()` → `finishAndRemoveTask()` → `Process.killProcess()`. The
middle call is the whole cause: on Android, **a task leaves recents when its last activity finishes**,
and `finishAndRemoveTask` is the explicit form of that (`finish()` does it too, one activity at a
time). Nothing about ending the process removes the task record — a killed process keeps its task,
which is why a crashed app is still in recents — so the process kill that makes the next launch clean
was never what dropped the entry.

The fix is to stop finishing: `moveTaskToBack(true)` (so the task is backgrounded deliberately, with
the normal pause path run) followed by the same `Process.killProcess`. The task stays listed, and
because the process is gone the system starts a **new** process for it when the card is tapped — the
renderer-freshness the old comment was protecting is untouched.

Verified on the Fold 7 with the signed release build (granted folders kept, so the launch is the real
one): Exit printed `exit requested`, `pidof` was empty, `dumpsys activity recents` still showed
`Recent #1: Task{… A=10655:io.github.moronigranja.alabasterdawn}`, and tapping that card in the
overview brought up the setup screen in a new pid.

### 20.2 The frame-rate cap, and why it is a `requestAnimationFrame` gate

The engine has a `force30fps` debug option that does exactly this — `runInner` returns before any
update or draw when `this.clock.previewTick() < 1/30` (bundle 34851) — but it is unreachable: every
`addDebugOption` returns immediately unless `window.XG_GAME_DEBUG` is set (24914), and that flag gates
116 sites in the bundle (debug menus, cheat paths, physics overlays). Turning it on to reach one
option would change the game.

The engine's *only* frame driver is `requestAnimationFrame`, though: `fps` is `SYSTEM_CONF.FPS = 60`
(34459), and both `startBooting` (34795) and the end of every `run()` (34833) re-request
`window.requestAnimationFrame(this.runCallback)` when `fps >= 60`. So the shim wraps rAF and serves one
frame on the first vsync at least one frame interval after the last one served, batching every callback
that arrived in between (the engine re-requests inside its own callback, so a batch is normally one).
No game file is touched, and no clock is fooled: `Timer.step` reads `performance.now()` itself
(51937), so the game logic keeps its 60 Hz fixed step and only the presents drop.

The tolerance is the one subtlety, and it is why the interval is shortened by a tenth of itself rather
than compared exactly. A 60 Hz vsync is 16.7 ms and 30 fps is 33.3 ms; waiting for `t - last >= 33.33`
would put the deadline a fraction *after* the second vsync, so every frame would slip to the third one
— 20 fps, not 30. Shortened to 30 ms, that deadline is one vsync away at 60 Hz and four at 120 Hz: 30
fps on both. The same tenth is what makes the other rates land: 20 fps is a 50 ms interval against
60 Hz vsyncs at 50 ms, and 60 fps is 16.7 ms against 15 ms.

A display can only present on a vsync, so the rates the menu offers are not all reachable on every
panel, and the gate takes **the next vsync up** — the largest achievable rate that does not exceed the
chosen one. On a 60 Hz panel `20` and `30` and `60` are exact and `45` is 30 (22.2 ms shortened to 20,
and the next vsync after that is 33.3 ms); on a 120 Hz panel `45` is 40 (25 ms). That is a property of
the display, not of the cap: the engine's own fixed step is untouched either way.

The Kotlin side is a switch, a rate and two preferences (`KEY_LIMIT_FPS`, `KEY_FPS_LIMIT`) exactly like
the readout and the log switches, and `FpsLimit` owns the four rates, the slider position of a stored
one (nearest choice; a tie goes to the lower rate, and `OFF` reads as unchosen) and the bridge's value
(`0` = no cap). The rate is the slider the switch reveals under its row — a `SeekBar` with the four
labels on a strip beneath it, each centred on the position its thumb reaches (the strip is given the
bar's own paddings, plus the readout's width, so the arithmetic is the same in both places) — and the
shim reads the number on the same once-per-frame gamepad poll that already carries the picture
alignment and the readout, so a change lands on the next frame with no reload.

Verified: `node android/tools/test-shim-diagnostics.mjs` drives the vsync queue directly at every rate
(53/53, six of them for the cap) — every vsync served with it off, ~30 per second of 120 Hz vsyncs at
30, 20 and 60 at their own rates, 40 at 45, 30 for 45 on a 60 Hz panel, and the same shim following
both a rate change and a switch-off live. On the S22 Ultra's own 60 Hz panel the running game's readout
followed the slider: `20` → `20 fps`, `30` → `30 fps`, `45` → `30 fps`, `60` → `60 fps`, switch off →
`60 fps`, with `frame limit: 45 fps` / `ENGINE fps: limited to 45 fps` in the record and no reload; the
row appears and disappears with the switch, and the rate survives a relaunch.

## 21. The Mali report (2026-09-30) — the compiler's own words, now in the record

A player on a **Poco X7 Pro** (MediaTek Dimensity 8400 → **Mali-G720**, Immortalis 5th gen) reported on
the game's community that the port runs, but that he "had to use Gemini and logs to fix the game",
naming *a compilation error in some water files and post-processing issues*. He shared what he changed
as a ZIP: four shader files and his `index.html`.

### 21.1 What the ZIP actually is

The three water-family shaders are replaced by stubs that draw nothing, and the post pass by a
passthrough:

```glsl
/* water-plane.frag, water-fx-wall.frag, weather-drops.frag */   /* post/analog-filter.frag */
void main() { fragColor = vec4(0.0); }                          fragColor = texture(u_texture, v_texCoord);
```

so water surfaces, rain and the analogue-film post pass are switched *off* — a workaround, not a fix.
The `index.html` adds an `error` listener that swallows a runtime `TypeError: … reading 'set'`, which
is what a *program that did not link* looks like from the JS side (no active uniforms, so the engine's
uniform setters are read off `undefined`). His game is `0.1.0-7`; the copy this repository is developed
against is `0.1.0-10`. No log was posted — the compiler's message is the one datum missing, and it is
the one that decides what to do.

**This is the *first* ZIP** (the stub workaround). The reporter later sent a **second** one — his real
hand fix plus the log that names the driver's messages — and that is the one in the working tree here
(`logs/mali/fixmali.zip`, the files under `logs/mali/fixmali/`, git-ignored as game files); it is read in
§22.8. It contains no `weather-drops.frag` at all, which is the file the first ZIP had stubbed.

### 21.2 Nothing invalid in the bytes the port serves

The four shaders, expanded the engine's own way (`#import "lib/x";` → `data/shader/lib/x.glsl`,
recursively), pass a strict ES 3.0 front end with no diagnostics:

| check | result |
|---|---|
| `glslangValidator` (ES 3.0 fragment stage) on the four expanded shaders | 4/4 accepted, no warnings |
| ANGLE + SwiftShader (the same translator the WebView uses, over the device's GL) on the four | 4/4 compile clean |
| `glslangValidator` on **all 39 served fragment shaders**, with the port's own rewrites applied (precision raise, dither grid, `v_barycentric` keep-alive) | 39/39 accepted |

The four are also not the interface-heavy ones — `weather-drops` declares 9 varyings where `light.frag`
and `decal.frag` declare 18 and were left alone — so a varying or uniform ceiling does not explain the
set either. All three water shaders *are* requested at boot (the port's own log lists
`water-plane.frag` among the boot-time rewrites), which is consistent with a boot that freezes.

### 21.3 What the record was missing (and why a player reached for Gemini)

`ShaderResource.loadShader` (bundle 38652) logs a failed compile as console **groups**: the title of
the first is `Shader Errors: <path>`, then one group per `ERROR: 0:<line>: <message>` the driver
returned, and only the offending *source* lines under them go through `console.error`. It then throws
`An error occurred compiling the shader "<path>"`, and the resource stays pending, so the loading bar
freezes (§10) instead of failing loudly.

The shim forwarded `console.error`/`console.warn` only. A record from a failing device therefore kept
*a line of shader code* and lost both the file it came from and the compiler's message — the two things
the report needed.

Reproduced end to end, no device required: a copy of the game tree with one deliberately broken line in
`water-plane.frag`, served over HTTP, with the **port's own `ada-shim.js`** in front of the real bundle
in Chromium and a recording `window.AdaBridge`. The boot freezes at 99.9 % with
`pending 2 (shader=1 data=1)` and `fragmentShader.hasError = true` — the reported symptom exactly. What
the shim reports for it, before and after the change:

```
before:  console.error | 1309:                          ← the source line only: which file? why?
         jsError       | … An error occurred compiling the shader "…/water-plane.frag"
after:   console.groupCollapsed | Shader Errors: data/shader/fragment/water-plane.frag
         console.groupCollapsed | 1310: 'this' : Illegal use of reserved word     ← the driver's message
         console.error          | 1309: <the offending source line>
         jsError                | … An error occurred compiling the shader "…/water-plane.frag"
         boot stall             | no progress for 8001ms at 99.9% of 1753 resources; pending 2 …
```

### 21.4 The change

`ada-shim.js` now forwards the titles of `console.group`/`console.groupCollapsed` as well, and
`console.log` is deliberately still not forwarded: the engine dumps the whole expanded source under the
`Shader Code` group (`console.log(codeLines.join("\n"))`, ~1 200 lines), which would spend the 200-line
budget before any later useful line. The node harness grew three checks for it (44/44): the path, the
driver's message and the source line are reported, and `console.log` is not.

On the Fold 7 the same build boots as before (`ENGINE boot: complete in 3013ms, 1757 resources`) and a
healthy boot logs **no** console lines at all, so the forwarding adds nothing to a working record.

### 21.5 What is still needed, and the candidates it will settle

The Mali compiler's message, from a failing device: **Back → Troubleshoot** and a screenshot of the
record (always reachable, in-game or from the setup screen), or `ada-diagnostics.log` out of the saves
folder — noting that the log switch turns itself off after the first boot that *completes*, so a player
who has already booted successfully once has that file switched off and the on-screen record is the
door. With the message in hand the candidates are distinguishable in one run, because it names the
stage and the line:

* a construct ARM's front end rejects — the water shaders' `flat in mat4 v_mat` / `v_invMat` pairs and
  `flat in vec2[4] v_flowDirs` are their unusual declarations, and the port's own `v_barycentric`
  keep-alive injects `vec3(-1e30)` into `water-plane.frag`, which is a **mediump** shader (the solid
  ones it also patches are highp). Literals are highp-typed and the spec allows it, but it is the one
  thing in those bytes that is not the game's;
* the post pass's `textureLod(u_texture, uv, 4.)` on a render target with no mip chain — legal but
  driver-defined, and a black or dark film pass on Mali is a *behaviour* difference, not a compile
  error, which would fit "post-processing issues" being fixed by a passthrough;
* a link failure, which the engine throws with both paths and the program log.

---

## 22. The three shaders Mali's front end refuses (2026-09-30)

§21's follow-up, tracked as issue #4: the record it built was read back from the same device, and it names
both the file and the compiler's own message.

### 22.1 The message

From the reporter's Poco X7 Pro (Mali-G720 MC7, Android 16, WebView 155 beta), app 0.6.1 and 0.7.0,
game `0.1.0-7`, with the error-group forwarding of §21.4 in place:

```
+39219ms ENGINE console.groupCollapsed: Shader Errors: data/shader/fragment/post/analog-filter.frag
+39219ms ENGINE console.groupCollapsed: 0:62: S0032: no default precision defined for variable 'vec3[5]'
+39223ms JS ERROR window.onerror: … An error occurred compiling the shader "data/shader/fragment/post/analog-filter.frag
+40571ms ENGINE console.groupCollapsed: Shader Errors: data/shader/fragment/water-plane.frag
+40573ms ENGINE console.groupCollapsed: 0:275: S0032: no default precision defined for variable 'vec4[4]'
+40641ms ENGINE console.groupCollapsed: Shader Errors: data/shader/fragment/water-fx-wall.frag
+40644ms ENGINE console.groupCollapsed: 0:308: S0032: no default precision defined for variable 'vec4[4]'
+49381ms ENGINE boot stall: no progress for 8496ms at 98.7% of 1704 resources; pending 23 (shader=3 …)
```

So §21.5's third candidate (a link failure) is out, and its first is the one: **the driver names a type,
not a variable, and the type is an array type.** The record's own line numbers are not the engine's —
the constructor sits at line 148 of the engine's expansion (232 lines, the port's model of which
reproduces the 1 309/1 310 of §21.3 within three lines) while the driver says 62: ANGLE re-emits the
source before the driver sees it, so "the message names the stage and the line" was half right. The
*type* is what identifies the construct, and it does.

### 22.2 The constructs

Every array type specifier in the fragment stage of build `0.1.0-10` (the same bytes as the full game on
Steam; the demo differs only inside `lib/water.glsl` bodies), from the maintainer's own tree:

| file | line | the game's bytes | named by the driver |
|---|---|---|---|
| `fragment/post/analog-filter.frag` | 42 | `colorRamp(noise.r, vec3[5](` | **`vec3[5]`** |
| `lib/water.glsl` | 182 | `vec4[4] computeWaveFactors(out vec2 globalFlow, vec2 flowDir)` | **`vec4[4]`** |
| `lib/water.glsl` | 187 | `return vec4[](` | — |
| `lib/water.glsl` | 195 | `vec3 computeGerstnerOffset(… vec4[4] waves, … float[4] amps, float[4] phases)` | **`vec4[4]`** |
| `lib/water.glsl` | 342 | `vec4[4] waves = computeWaveFactors(globalFlow, flowDir);` | **`vec4[4]`** |
| `lib/water.glsl` | 135 | `vec2 bilinear(vec2[4] v, float t0, float t1)` | — |
| `lib/water.glsl` | 78, 97 | `const vec2[12] DIRECTIONS = vec2[](` | — |
| `lib/water.glsl` | 346, 350 | `float[](1.0, 0.3, 0.1, 0.0) …` (arguments) | — |
| `lib/color-utils.glsl` | 17 | `vec3 colorRamp(float t, vec3[COLOR_RAMP_COUNT] colors)` | — |
| `fragment/water-plane.frag` | 27 | `flat in vec2[4] v_flowDirs;` | — |
| `fragment/plane-depth.frag` | 13 | `flat in vec2[4] v_flowDirs;` | — |

`lib/water.glsl` is imported by `water-plane.frag` and `water-fx-wall.frag` only — the two water
fragment shaders whose whole wave path is that file. The third failure, `analog-filter.frag`, is the
post pass's ramp: the only `vec3[5]` in it is the constructor in the table's first row.

### 22.3 What it is not

* **Not invalid ES 3.0.** `glslangValidator` accepts the expanded originals and ANGLE compiles them
  (§21.2), and the driver's own complaint is impossible by the book: every one of these shaders declares
  `precision mediump float;`, and the construct the driver names is the one a default precision is
  *supposed* to cover.
* **Not the port's rewrites.** `analog-filter.frag` is served byte-for-byte (it is deliberately not in
  `ShaderPrecision`'s GUI set, and `ShaderPrecisionTest` asserts it is untouched); the construct in
  `lib/water.glsl` is reached by no rewrite at all (no slot table, no dither divisor, no `v_barycentric`
  in that file); and the two water fragments' rewrites (the dither divisor, the keep-alive) are other
  lines entirely.
* **Not a ceiling.** The same record reports `uniforms=4096/4096; varyings=31`, and the failing three
  are not the interface-heavy shaders.
* **Not the `v_barycentric` keep-alive's `vec3(-1e30)` literal** (§21.5's first candidate, which also
  guessed `flat in vec2[4] v_flowDirs`): the driver's message is about array types, and
  `analog-filter.frag`, which carries neither, fails the same way.

### 22.4 The rule

The same driver compiles, in the same boot, on the same device:

* `lib/blur.glsl`: `const float[] BLUR10KERNEL = float[](` (reached by `gui-blur.frag`)
* `fragment/combine.frag` / `shadow.frag` / `lib/shadow.glsl`: `vec2 poissonDisk[] = vec2[](` and
  `float factors[] = float[](…)` (reached by `combine`, `shadow`, `fog-plane`, `fog-plane-pre`)

The engine's boot set is 45 shaders; the failing record lists **exactly three pending**, so every one of
those compiled. The port's own harness can be asked what each of them contains:

| shader (in the boot set) | unsized array constructor | sized array specifier |
|---|---|---|
| `gui-blur`, `combine`, `shadow`, `fog-plane`, `fog-plane-pre` | yes | **no** |
| `water-plane`, `water-fx-wall` | yes | **yes** |

So the front end's rule is: an array written with its size in the *type* — `type[size] name`,
`type[size](…)`, `type[size] f(…)` — loses the declared default precision (the driver's S0032), while the
**declarator** spelling `type name[size]` and the **unsized** constructor `type[]` are fine. That is one
rule for all three failures and for the reports below.

The same bug, elsewhere (found with the web-search tool, 2026-09-30):

| report | device / GPU | construct | works on |
|---|---|---|---|
| [Godot #99821](https://github.com/godotengine/godot/issues/99821) (open, `confirmed`) | Pixel 8a **Mali-G715**, Amazon Fire HD 10 (Mali), a Samsung phone | `vec4 m_pixels[1] = vec4[1](m_pixel);` → `S0032 … 'vec4[1]'` | Adreno 740, iPhone 13, desktop |
| [r/opengl zs60wi](https://www.reddit.com/r/opengl/comments/zs60wi/) | Pixel 6 **Mali-G78** | a `const` array, `precision mediump float;` on line 3 → `S0032 … 'float[9]'` | Linux/Mac/Windows Chrome, Firefox, Safari |
| [SO 72479232](https://stackoverflow.com/questions/72479232/shader-fails-on-mobile) | Android 12 phone | `float w[4] = float[4](…)` → `S0032 … 'float[4]'` | desktop |

Godot's reporter reaches the same conclusion ("completely valid in OpenGL ES 3 … driver bug on certain
devices"), and the accepted answer on Stack Overflow is the declarator spelling — `float w[4];` with
element-wise assignment — which is what the lift below moves declarations to. Two further consequences
from the same sources:

* the construct cannot be repaired by *adding* a precision qualifier: ESSL 3.0's own errata list says
  "precision qualifiers are not allowed on constructors", and `precision mediump vec3;` is invalid (the
  WebGL conformance test `invalid-default-precision.html` is that rule) — the spelling has to change,
  not the qualifier;
* ARM does not document it: its workarounds/errata list (rev 2.0, GX920) has no entry for S0032 or for a
  default-precision-array, though neighbouring array errata exist — `668069` (GLES2: a `varying vec2`
  array loses every odd element; workaround: separate variables or a `vec4` array) and `602375`
  (function overloads differing *only* in array sizes are one signature). `602375` is not in play here:
  `colorRamp`, `bilinear` and `computeGerstnerOffset` are each defined once, and the lift keeps every
  size in the declarator.

### 22.5 The lift

`ShaderArrays` serves the five files with the declarations in the spelling the same device is known to
accept. Nothing else in a shader changes — the base types, the qualifiers, the sizes, the values and the
evaluation order are the game's:

| file | edits | what happens |
|---|---|---|
| `fragment/post/analog-filter.frag` | 1 | `colorRamp(noise.r, vec3[5](` → `… vec3[](`; the size is inferred from the parameter |
| `fragment/water-plane.frag`, `fragment/plane-depth.frag` | 1 each | `flat in vec2[4] v_flowDirs;` → `flat in vec2 v_flowDirs[4];` — the spelling their own `.vert` already uses |
| `lib/color-utils.glsl` | 1 | `vec3[COLOR_RAMP_COUNT] colors` → `vec3 colors[COLOR_RAMP_COUNT]` |
| `lib/water.glsl` | 9 | `vec2[4] v` → `vec2 v[4]`; `const vec2[12] DIRECTIONS` → `const vec2 DIRECTIONS[12]` (twice); the two `float[](…)` argument lists hoisted to global `const float x[4] = float[](…)`; `vec4[4] waves` / `float[4] amps` / `float[4] phases` parameters → declarators; the one array **return type** becomes `void computeWaveFactors(…, out vec4 waves[4])` with four assignments, and its single caller declares `vec4 waves[4];` |

Only the fragment stage is lifted: all twelve `.vert` shaders compile on the reporting device (the
record's pending list names three fragment shaders), and their sized declarations (`float[4]
flowStrengths = float[4](…)` in `water-plane.vert`) are a spelling this driver accepts in the vertex
stage — so they are left alone deliberately rather than by oversight. What the lift does *not* touch
anywhere is the global `type[] name = type[](…)` form, which that device compiles.

The gate is a document-start probe in the shim, in the shape of the slot probe (§17): it compiles the
declarations the game's shaders are written with — one program per shape, on a context made with the
engine's own attributes, **linked** — before the engine asks for anything, and reports through
`AdaBridge.setShaderArrays`. Only a page whose compiler *refuses* one gets the lift, and the record says
which way it went, and since §22.8 what each shape did:

```
+24ms ENGINE shader arrays: Mali-G720 MC7: 2 of 8 shapes refused: in/out (0:62: S0032 …) -> lifting them
+25ms ENGINE array declarations lifted in terra/data/shader/lib/water.glsl (9/9)
```

(A first version of this gate compiled one *synthetic* shader of the same shapes and answered with one bit.
That is what the Mali run in §22.8 falsified: the driver accepted the synthetic text and refused the
game's own bytes, and the lift never ran.)

A file whose bytes do not match (another build) is served as it came with a `Log.w` naming it — a
partial lift would be worse than none. The files on the user's disk are never touched, and each rewritten
body is cached like the others.

### 22.6 Measured

* Unit tests: `ShaderArraysTest` (the lift per file, line endings preserved, idempotent, the device gate,
  a foreign build's bytes served as they came). A throwaway check (not committed — see §22.7) matched
  every literal against a real installation, full game **and** demo: 1/1, 1/1, 1/1, 1/1, 9/9.
* `glslangValidator` on the expanded originals and on the expanded lifted shaders (eight fragment
  shaders, including the two water ones): **0 failures either way**.
* The engine itself, booted in Chromium (ANGLE + SwiftShader) through the port's own `ada-shim.js` and a
  recording bridge, with the game tree over HTTP: the original bytes boot as before, and the lifted bytes
  boot **complete — 1 757 resources, 154 shader compiles, 0 failures, 0 link failures**. The engine's own
  shader objects show the lift arrived (`adaAmpsWave` in `water-plane`, `out vec4 waves[4]`, no `vec4[4]`
  anywhere, `colorRamp(noise.r, vec3[](` in the post pass).
* Interface equivalence, which is what the engine's uniform wiring depends on: for `water-plane`,
  `water-fx-wall`, `analog-filter` and `gui-blur`, the lifted and original boots enumerate **identical**
  active-uniform and active-attribute sets (41/41, 48/48, 15/15, 11/11 uniforms; 4/4, 4/4, 2/2, 13/13
  attributes), all linked.
* The shim harness grew three checks (44 → 47): the array probe carries every spelling the report named,
  the answer reaches the bridge, and a compiler that refuses them reports `false`.

One warning from doing that verification: an earlier, larger version of the `analog-filter` lift — the
Stack Overflow remedy, hoisting the ramp into a local `vec3 adaFilmRamp[5]` filled element-wise — made
the boot fail *after* that shader compiled: SwiftShader's compiler then refused the next 23 compiles with
an empty info log (87 ok, 23 fail, boot stall), on shaders whose bytes are identical in both runs. The
minimal edit (drop the constructor's size) boots clean. A lift of this kind changes what a *driver* must
translate, and the reporting class of driver is exactly the fragile kind; the smallest change that
satisfies the rule is the one to serve.

### 22.7 What the next Mali record decides

* Whether the lift is enough: the record now carries `shader arrays: …` and one `array declarations
  lifted in …` line per file, so a run either boots or names the next type. **Answered by §22.8**: the
  first run's gate said `accepts` and the lift never ran, so the gate was hardened and the run repeats.
* The reporter's **first** ZIP stubbed `weather-drops.frag`, which carries **no** array type specifier at
  all — under this rule it compiles, and his own second ZIP, the one that boots on the device, does not
  touch that file either. The current record does not list it among the three pending. If a later build
  fails on it, it fails for a second reason that no record carries yet.
* The driver named the `lib/water.glsl` `vec4[4]` in `water-plane.frag`, not that file's own
  `flat in vec2[4] v_flowDirs;` — either the driver reports one error per shader, or it resolves an
  interface array's precision from the vertex shader. The lift rewrites both spellings, so it does not
  depend on which.
* Nothing here was measured on Mali hardware: the port has none. The fix is the spelling the driver's own
  rule calls for, verified end to end everywhere else it can be.

---

## 22.8 The device run: the old gate answered "accepts" (2026-09-30, issue #4)

The reporter ran **0.7.1** and sent his own ZIP with the log in it. His log and the port's run are kept in
the repo (`logs/mali/log alabaster fixmali.txt`, `logs/mali/log alabaster 0.7.1.txt`); the ZIP itself and
his patched shaders stay in the working tree beside them as `logs/mali/fixmali.zip` and
`logs/mali/fixmali/` — **git-ignored**, because they are game files and this repository carries none
(`.gitignore`, "Game files"). His record shows the lift **never ran**:

| t | line |
|---|---|
| +12845 ms | `shader arrays: the page's compiler accepts the game's declarations` |
| +19565 ms | `Shader Errors: …/post/analog-filter.frag` — `0:62: S0032 … 'vec3[5]'` |
| +21425 ms | `Shader Errors: …/water-plane.frag` — `0:275: S0032 … 'vec4[4]'` |
| +21469 ms | `Shader Errors: …/water-fx-wall.frag` — `0:308: S0032 … 'vec4[4]'` |
| +29877 / +45377 ms | `boot stall … at 97.2 % of 1763 resources; pending 49 (shader=3 …)` |

So the gate's one bit was **wrong on the only device that ever needed it**: that driver accepted the
synthetic shader's text and refused the game's own bytes, so the port served them and the boot froze. Every
other stack measured (ANGLE, SwiftShader, Adreno, desktop) accepts both, which is why the "accepts" branch
had never met a device that disagrees — a gate that decides from a **synthetic stand-in**, puts no evidence
in the record, and has no way to notice the disagreement.

**His hand patch confirms the rule on the hardware.** He fixed the same three shaders himself and his
sessions boot (`boot: complete in 8782ms, 1757 resources`, no S0032) — through the *other* door into the
same rule: an explicit precision on the array type, `highp vec4[4] computeWaveFactors(…)`,
`mediump vec4 waves[4]`, and `flat in mediump vec2[4] v_flowDirs;`, i.e. the sized spelling **kept** with
the precision written out. That is the sharpest datum in the file: it says the rule is exactly "the sized
array type does not take the shader's declared default precision", which is what the lift answers by
changing the spelling. (He also changed art, which the port does not copy: `max(0.65, …)` as a floor on the
water's alpha, two `discard`s commented out — the water edge and the foam borders — the second wave's
amplitude `0.5 → 0.6`, and the analog-filter ramp's fourth colour `132 → 152`.)

**A second failure class, from his precision edits.** Raising the *default* precision in one stage only
leaves a shared uniform with two precisions, and this driver refuses the program:

```
Unable to initialize the shader program data/shader/vertex/water-plane.vert +data/shader/fragment/water-plane.frag:
  Uniforms with the same name but different type/precision: u_waveHeight
… data/shader/vertex/water-fx-wall.vert +data/shader/fragment/water-fx-wall.frag: … u_cameraProjM
```

Checked against the port's own precision change (§16): `ShaderPrecision` raises `gui.frag`, **`gui.vert`**,
`gui-bg.frag` and `gui-blur.frag`, and every GUI fragment in the game pairs with `gui.vert` — the only GUI
vertex shader there is — so both stages of every GUI program are raised and no uniform is left mismatched.
No action taken; the hazard is recorded here for the next precision edit.

**The gate, hardened.** The rule this section is about is that a *stand-in* must never decide something a
frozen boot depends on. The probe now:

* compiles **the declarations the game's shaders are written with**, copied from the files the rule names,
  one program per shape: the varying; the global `const vec2[12] DIRECTIONS = vec2[](…)`; the `vec2[4]`
  parameter; the array **return type** with its `out` parameter; the `float[4]`/`vec4[4]` parameter list;
  the sized local; the parameter sized by a macro; and the sized constructor in an argument;
* asks on a context made with the **engine's own attributes** (`{antialias:false,
  powerPreference:"high-performance"}`) and reports that context's own renderer — a stack the page opened
  some other way is not the stack the engine's shaders are compiled on;
* **links** every program, not only compiles it: this front end answered a compile-only question
  differently from the engine's own compiles;
* answers one-sidedly — **any** shape refused, a context that cannot be made, or a probe that throws all
  mean "lift". Lifting is the side that compiles everywhere with identical active uniforms and attributes
  (§22.6), and the game's own spelling is exactly what the probe doubts, so doubting wrongly costs a frozen
  boot while lifting wrongly costs nothing;
* puts what each shape did into the record, so the next device that disagrees names it:
  `shader arrays: <renderer>: 2 of 8 shapes refused: in/out (0:62: S0032 …), return (…) -> lifting them`.

Measured: the shim harness (54 checks; three are the gate's own — eight programs, every named declaration
present, and a refusing compiler's line naming the shapes), and the **real shim in Chromium on a working
stack** — `ANGLE (AMD, … OpenGL ES 3.2): all 8 shapes compile and link -> the game's bytes`, verdict
"accepted", with the three real expanded shaders still compiling afterwards on a fresh context of the same
page, which is also the check that the probe does not poison what compiles next (§22.6's warning).

What this still is not: *measured on Mali*. If that device's next record says `all 8 shapes compile and
link` while its compile still fails, the remaining difference is the **context** — the probe asks on one it
makes itself, before the engine has one — and the next lever is to run the same shapes on `window.g.gl`
itself at first-shader time, through the await the gate already uses. If the record says the lift ran and a
shader still fails, the forwarded S0032 names the file and the type.

---

## 22.9 The second device run: the probe works, the water compiles, and the lift's own line was missing (2026-09-30)

Two more records from the same phone, both **0.7.2 (13)**, one per WebView GL driver (kept as
`logs/mali/log alabaster 0.7.2 angle.txt` and `… native.txt`):

**ANGLE** — `gl=ANGLE (ARM, Vulkan 1.3.278 (Mali-G720 MC7 (0xC8700010)), Mali-G720 MC7-49.1.0)`:
`shader arrays: ANGLE (ARM, …): all 8 shapes compile and link -> the game's bytes` (eight at the time),
no lift, `boot: complete in 9240ms, 1757 resources`. **That is why the device runs on ANGLE**: ANGLE's own
front end takes those declarations, the driver's front end never sees them, and the gate is right to serve
the game's bytes. His report of that run — "it works with the angle driver now, but the water is invisible"
— is therefore a *rendering* question, with no shader failure anywhere in the record.

**Native** — `gl=Mali-G720 MC7`:
`shader arrays: Mali-G720 MC7: 1 of 8 shapes refused: return (0:15: S0032: no default precision defined for
variable 'vec4[4]') -> lifting them`. **The hardened gate works on the device it exists for**, and the lift
runs. And the water shaders then compile — they are no longer in the pending list; only
`analog-filter.frag` still fails (`0:62: S0032 … 'vec3[5]'`), so the boot stalled at 99.0 % with
`pending 18 (shader=1 …)`.

What that record could **not** say is what §22.8's gate was only half of: the lift's per-file outcome was
`Log.i` — logcat only. So "the water shaders compile" cannot be attributed: it is either `water.glsl`'s
nine edits or the reporter's own hand-fixed copies still in his game folder (§21.1/§22.8); and
"`analog-filter` still fails" is either a file whose bytes carried none of the one edit the lift has for it
(served as it came) or a driver that refuses the lifted form. **v0.7.3 puts both cases in the record**:

```
array declarations lifted in terra/data/shader/lib/water.glsl (9/9)
array declarations NOT lifted in terra/data/shader/fragment/post/analog-filter.frag (0/1): its bytes carry none
```

And the gate grew the one shape its halves did not add up to. The post pass is a **macro-sized parameter
called with a sized constructor**; both halves (`macro-size`, `ctor-arg`) compile on that driver, and the
pair is what the file is — so `ramp-call` is now probed as one shape, nine programs in all. That is the
second time this rule taught the same lesson: a gate must speak the *whole* construct, not the pieces.

Still open, and now the reporter's question rather than ours: **the water is invisible on ANGLE**, with no
shader failure in the record. Its candidates are the reporter's own patched water files — which change the
water's alpha floor, its two border `discard`s and a wave amplitude (§22.8) — still being in the game
folder, or an ANGLE-on-Mali rendering difference in the water pass itself. A run with the game's original
files, plus a screenshot, is what separates them.

---

## 22.10 Measured on four Mali generations: none of them refuse it (2026-09-30)

`tools/mali-probe/` compiles the game's own shader text on a device's **native** GL front end — the gate
§22.8 asks from inside a WebView, asked from a plain app instead, so a device farm can answer for hardware
this project does not own. Four Firebase Test Lab devices, one instrumentation run (logs kept in
`logs/mali/probe/`):

| device | front end | driver | cases | refused |
|---|---|---|---|---|
| Pixel 8a (Tensor G3) | **Mali-G715** | `v1.r44p0-01eac0.d0969c01…` | 26 | **0** |
| Pixel 7 (Tensor G2) | **Mali-G710** | `v1.r38p1-01eac0.55eb2d40…` | 28 | **0** |
| Pixel 6 (Tensor) | **Mali-G78** | `v1.r38p1-01eac0.1a610aad…` | 28 | **0** |
| Galaxy A35 5G (Exynos 1380) | **Mali-G68** | `v1.r38p1-01eac0-mbs2v41_0…` | 28 | **0** |

(The first run was taken before the last two shapes were added, hence 26 there.) Every case compiled on
every one of them, and every `shapes/` case compiled **and linked**: the game's own bytes (the `vec4[4]`
return type, the `vec3[5](…)` constructor, `flat in vec2[4] v_flowDirs;`, the `const vec2[12]` globals),
the port's lifted bytes, and the seven post-pass variants — each half of that declaration changed alone,
and each candidate repair (`local-ramp` and the reporter's own file among them). The two shapes **other
projects report** this front end refusing — `vec4 m_pixels[1] = vec4[1](u_pixel);` from Godot #99821 and
`const float w[9] = float[9](…)` from r/opengl — also passed on all four.

That is a result about the **bug**, not about the fix:

* **The refusal is not a property of the Mali generations this project can reach.** The reporter's phone
  is a **Mali-G720 (Immortalis, 5th gen)** on driver **r49** (`Mali-G720 MC7-49.1.0`); these four run
  **r38/r44** and take the same bytes. So the trigger is far more likely the **driver revision** than the
  GPU generation — which is also what the Godot report implies, being a *Pixel 8a* (G715) whose driver has
  moved on since it was filed. [INFERENCE: the revisions are the only difference visible here, and a
  newer front end refusing an older declaration is a shape this family has produced before — the report
  itself is on the older generation, not a newer one.]
* **It is the strongest argument for the gate's design.** Driver revisions arrive with system updates —
  and on Android 15+ through the graphics-driver updates the Play Store can now deliver — so this
  property can change on a device already in someone's hand. Deciding per device, from that device's own
  compiler, keeps the port right either way: a G715 that refuses tomorrow is served the lift, and one
  that accepts keeps the game's bytes.
* **What is still unmeasured is the fix against a refusing device**, and Test Lab cannot supply one: its
  catalogue has no Immortalis-G720 phone (its newest Mali devices are the Pixel 8a and the Exynos/Helio
  A-series). The reporter's phone stays the only refusing front end reachable from here, and v0.7.3's
  per-file record is the measurement: `array declarations lifted in … (9/9)` beside the next `S0032` says
  whether that spelling is enough for r49 — and if it is not, the probe's `variants/` set is the list of
  spellings still to try, on the device itself.

---

## 22.11 Asking the phone instead of guessing (2026-09-30, v0.7.4)

§22.10 measured four Mali generations and **none** of them refuse the game's bytes; the only refusing
front end reachable from here is a user's phone (Mali-G720, driver r49). So the port asks *that* phone, in
the right stack, and puts the answer in the record he already knows how to send:

* **A shader self-test in the Troubleshoot panel.** `ShaderVariants` builds the game's own fragment
  shaders — expanded the engine's way (`#import` inlined, the engine's `#define`s in place) — in every
  spelling the port could serve: the post pass as the game writes it, with the port's lift, with each half
  of that lift alone, and with each candidate repair (`local-ramp`, `global-ramp`, `mediump`/`highp` on the
  parameter); the water family in both spellings. The page fetches the texts over a reserved path
  (`ada-variants`, served by `GameAssetHandler` from the game's own bytes **on the device**, never from
  the repo) and compiles each on **this device's front end** — the engine's own context when there is one,
  a throwaway context otherwise — reporting one line per case and a summary:

  ```
  shader self-test: analog-filter~original compile=0 link=- log=0:62: S0032: no default precision defined for variable 'vec3[5]'
  shader self-test: 14 cases, 13 compile, refused=analog-filter~original
  ```

  One tap, and the phone says which spelling its driver takes — the question four cloud runs and six
  reports could only narrow. The texts never leave the device, and nothing about them is cached.
* **The driver, in the record**: `driver=native|ANGLE`, derived from the renderer string. That difference
  is the whole story of the last two Mali reports (the native driver refuses the declarations, ANGLE's own
  front end never sees them), and an app can neither read nor write the choice.
* **The switch, explained where it helps**: an **OpenGL driver** button in the same panel opens ANGLE
  Preferences — `com.android.angle/.MainActivity`, or Google's `com.google.android.angle` — falling back to
  Developer options, next to the sentence that says what to set it to. The port cannot set it itself (a
  privileged `Settings.Global` entry), and **bundling ANGLE would change nothing**: the WebView renders
  with its own GL stack, and the platform's ANGLE is the system apk plus that per-package opt-in.

Measured: `ShaderVariantsTest` (the expansion, every case, the `#define` placement, an unreadable file);
the shim harness at **60 checks**, four of them the self-test's own (plus the 'driver=…' field) (the engine-context path, a
per-case verdict line, the summary reaching the app, and a case the app cannot build being reported as
*unavailable* rather than as a refusal); and the real shim in Chromium against the fourteen generated
cases — all fourteen compiled, one line each, summary delivered.

**Then on hardware** (the maintainer's S22 Ultra, 0.7.4 installed over the existing build, Back → the side
menu's Troubleshoot → the new button): the chain ran end to end on the engine's own context and reported,
and it caught two defects a browser could not:

1. **That phone's game copy has CRLF line endings.** The single-line edits matched and the multi-line ones
   did not, so two cases came back *unavailable* and the water ones produced invalid text. The port's own
   rewrite has normalised CRLF since §22 (`ShaderArrays.rewrite`); the self-test now does too.
2. **A failed nested expansion put the word `null` into the shader text** — Kotlin's
   `StringBuilder.append(String?)` appending `"null"` for a null child. That was the
   `0:610: 'null' : syntax error` in the water cases above. A case that cannot be built now fails as a
   whole, is served as nothing, and says why in the record
   (`shader self-test case …: the game's bytes carry none of its text`), while the page reports it as
   *unavailable* instead of pretending the driver refused an empty source.

With those fixed, the same tap reports **`14 cases, 14 compile`** on that phone (`0.7.5`) — and note what
that datum is worth: a game copy whose line endings differ from the reference's would have made the port's
own lift report `NOT lifted (0/n)` on that device (§22.9), which is exactly the kind of thing the per-file
lines were added for.

The one step still never exercised: the panel button on a phone whose game copy is *not* CRLF and whose
driver is *Mali* — i.e. the reporter's, which is where the answer now goes.
