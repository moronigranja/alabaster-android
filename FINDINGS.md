# Alabaster Dawn — controller/Android research notes

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
