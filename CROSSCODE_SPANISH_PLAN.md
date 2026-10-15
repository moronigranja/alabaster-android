# CrossCode Spanish translation (download-and-install, no game files touched)

## Context

Add an on-device Spanish (`es_ES`) translation for CrossCode: the user taps one entry-screen
action, the app downloads the community **CrossCode-Esp v1.1.0** release (a CCLoader modpack: the
CCLoader ES-module loader + the `crosscode-Esp`, `localize-me` and `ultimate-localized-ui` mods),
verifies it, unpacks it into app storage, and thereafter boots CrossCode *through that CCLoader*
with the Spanish locale selected. The user's picked game folder is never read for the pack and
never written; the APK carries no game files, no CCLoader and no translation. Measured facts this
plan relies on (this repo's own copy of the pinned build, `…/steamapps/common/CrossCode`, 1.0.0 /
`v1.4.2-4`): CCLoader selects its DESKTOP file backend when `window.nw` is defined, and
`cc-shim.js` already defines `window.nw`; the game's own default language is `en_US` and the
options menu is not required (we force the locale).

## Approach

Ordered so the tree builds and the existing suites stay green after each step. Steps 1–2 are
independent of 3–6; do them first (they are the whole feature's data source).

### 1. `TranslationPack` — download, verify, unpack, read (new file `android/app/src/main/java/io/github/moronigranja/alabasterdawn/TranslationPack.kt`)

Package-level object; no Android types beyond `Context`/`filesDir`.

- Constants (exact, load-bearing):
  - `URL = "https://github.com/tralph3/CrossCode-Esp/releases/download/v1.1.0/CrossCode-Esp_v1.1.0.zip"`
  - `SHA256 = "d541a543e437f8ec6986b74c3df9a1fc982bd9daff6ee72a0ea044cba7edc14d"`
  - `SIZE = 2537080L`, `VERSION = "1.1.0"`
- `fun dir(ctx: Context) = File(ctx.filesDir, "translations/cc-es")`.
- `fun isInstalled(ctx: Context): Boolean` — `File(dir, "pack.json")` exists.
- `fun install(ctx: Context)` — `HttpURLConnection` on `URL` (`instanceFollowRedirects = true`,
  `connectTimeout`/`readTimeout` 15 s); stream to `File(dir.parentFile, "pack.tmp")`; on parse,
  SHA-256 the temp file; if it differs from `SHA256`, delete the temp file and throw
  `IOException("translation pack checksum mismatch")`. Open the temp file with
  `java.util.zip.ZipInputStream`; for each entry whose name starts with `ccloader/` or
  `assets/mods/` and does **not** end in `.xcf`, write it under `dir/<entry-name>` (creating parent
  dirs; reject any entry whose normalized path escapes `dir`). Write `dir/pack.json` =
  `{"version":"1.1.0","sha256":"<SHA256>","size":2537080}`. Delete the temp file in a `finally`.
  Skip everything else in the zip (notably `package.json`).
- `fun remove(ctx: Context)` — recursive delete of `dir`.
- `fun read(ctx: Context, rel: String): ByteArray?` — `File(dir, rel).takeIf { it.isFile }?.readBytes()`.
- `fun exists(ctx: Context, rel: String): Boolean`.
- `fun list(ctx: Context, rel: String): List<String>?` — child names of `File(dir, rel)` if a dir.

Rationale for the zip's top level: `dir` mirrors the game root, so the game-root-relative paths
`ccloader/…` and `assets/mods/…` map 1:1 to `dir/…` (verified against the released zip layout).

### 2. `AndroidManifest.xml` — allow the download

Add, before `<application>` in `android/app/src/main/AndroidManifest.xml`:
`<uses-permission android:name="android.permission.INTERNET"/>`.

### 3. Serve the pack at the WebView origin — `GameAssetHandler.serve(rel)`

In `GameAssetHandler.kt` `serve(rel)`, immediately before `val entry = index.find(rel) ?: return miss(rel)`
(the shader/`ShaderVariants` branches stay above it), add:

```
val virtual = rel.startsWith("ccloader/") || rel.startsWith("assets/mods/")
if (virtual) {
    val bytes = TranslationPack.read(ctx, rel) ?: return miss(rel)
    val mime = mimeOf(rel); return ok(mime, encodingOf(mime), bytes)
}
```
`GameAssetHandler` needs the `Context` (add a `context: Context` constructor parameter; the
existing call site in `PortActivity` passes `this`). `mimeOf`/`encodingOf` already handle `.js`,
`.json`, `.png`; if an extension is unmapped, `mimeOf` already falls back — confirm it returns
`application/javascript` for `.js`, or add `.js` there. This is what makes CCLoader's ES modules
and `mod://` assets fetchable over `https://appassets.androidplatform.net/game/…`.

### 4. Serve the pack to the page's Node `fs` — `FsBridge` + `PortBridge`

`FsBridge` backs `require('fs')`, which CCLoader uses to read its metadata, config, runtime mod and
every mod's `ccmod.json`. In `FsBridge`'s non-`/saves` branch (the `GameIndex` path, `FsBridge.kt`
~411–541), before the `GameIndex` lookup, add: if `TranslationPack.isInstalled(ctx)` and the
normalized game path starts with `ccloader/` or `assets/mods/`, serve it from `TranslationPack`
(`read` / `exists` / `list` / `stat` from the file on disk) instead of `GameIndex`. Keep every
existing `..`/escape guard (reuse `normalize`). Add `context` to `FsBridge` if it does not already
hold it.

`PortBridge.kt`: add
`@JavascriptInterface fun translationInstalled(): Boolean = TranslationPack.isInstalled(context)`
and pass `context` into `PortBridge` (add a constructor parameter; the `PortActivity` construction
site supplies `this`).

### 5. Extend the shim's `fs.promises` — `android/app/src/main/assets/cc-shim.js`

CCLoader's `files.desktop.js` calls `fs.promises.readFile`, `fs.promises.access`, `fs.promises.readdir`,
`fs.promises.stat`. `promisesApi` already has `readFile`/`stat`; add:

- `access: function (p, mode) { return fsExistsRaw(p) ? Promise.resolve() : Promise.reject(enoent(p)); }`
- `readdir: function (p) { var l = readdirRaw(p, null); return l ? Promise.resolve(l) : Promise.reject(enoent(p)); }`

(`enoent`, `fsExistsRaw`, `readdirRaw` already exist in the shim; `fs.constants` already provides
`R_OK`.)

### 6. Boot CrossCode through CCLoader when installed

- `GameProfile.kt`: add three fields to the enum — `translationEntryHtml: String?`,
  `translationLabel: String?`, and `translationUrl: String?`/`translationSha256: String?` (or fold
  the URL/sha into `TranslationPack`'s constants and keep only the presence flag here). CrossCode:
  `translationEntryHtml = "ccloader/main.html"`, `translationLabel = "Spanish (Español)"`; Alabaster
  Dawn: all `null`.
- `PortActivity.gameUrl()`/`entryUrl` use: when the started profile is CrossCode **and**
  `TranslationPack.isInstalled(this)`, load `"$ORIGIN/game/ccloader/main.html"` instead of
  `profile.entryUrl`. Implement as `fun gameUrl(profile) = if (profile.translationEntryHtml != null &&
  TranslationPack.isInstalled(this)) "$ORIGIN/game/${profile.translationEntryHtml}" else profile.entryUrl`,
  and use it at the existing `view.loadUrl(gameUrl(...))` call sites (`PortActivity.kt` ~1015, ~1198).
- Shim fallback tag injection (only for WebViews without `DOCUMENT_START_SCRIPT`): in
  `GameAssetHandler.serve`, the `injectShim && rel == profile.indexHtml` splice must also fire for
  `rel == profile.translationEntryHtml` when the overlay served it, and the injected tag there must
  use an absolute `src` (`/game/assets/cc-shim.js`) since the page lives under `ccloader/`.
- Document-start scripting (the normal case) already covers the whole origin, so `ccloader/main.html`
  gets the shim before CCLoader's module runs; no change needed there.

### 7. Entry-screen action + download flow — `PortActivity.kt`

- Companion: add `MENU_TRANSLATION = 5`.
- `showGameMenu(anchor, profile)`: when `profile.translationEntryHtml != null`, add
  `menu.menu.add(0, MENU_TRANSLATION, 4, if (TranslationPack.isInstalled(this)) "Remove Spanish translation" else "Add Spanish translation (~2.4 MB)")`
  and handle it in the `when`:
  - installed → `TranslationPack.remove(this)`; `refreshUi()`; `setStatus("Spanish translation removed.")`.
  - not installed → `installTranslation(profile)`.
- `installTranslation(profile)`: `setStatus("Downloading the Spanish translation…")`; a
  `Thread("cc-es-pack") { run { TranslationPack.install(this); runOnUiThread { refreshUi();
  setStatus("Spanish translation installed — restart CrossCode.") } } }` with a catch that posts
  `setStatus("Translation download failed: <message>")` and logs `diag.line(...)`.
- Attribution: append to `helpText(profile)` (or show in the status): `"Spanish translation by
  tralph3 and contributors — CC BY 4.0 (github.com/tralph3/CrossCode-Esp)."`
- Reuse: the background-thread + `runOnUiThread` + `setStatus` pattern already used by `loadGameArt`
  (`PortActivity.kt` ~613); do not add a new prefs key — `TranslationPack.isInstalled` is the state.

### 8. Do not touch

No other game-side behavior changes. Do **not** add a language entry to the game's own Options menu
(the locale is forced by CCLoader's mod pack); do not modify `assets/cc-shim.js`'s platform stubs,
the FPS/view-align/telemetry paths, or the Alabaster Dawn profile.

## Critical files & anchors

- `android/app/src/main/java/io/github/moronigranja/alabasterdawn/GameAssetHandler.kt` — `serve(rel)`
  (the single request funnel); insert the virtual-pack branch before `index.find` (~line 118).
- `android/app/src/main/java/io/github/moronigranja/alabasterdawn/FsBridge.kt` — the non-`/saves`
  branch (~411–541) where the game tree is read; add the pack overlay after `normalize`.
- `android/app/src/main/java/io/github/moronigranja/alabasterdawn/PortActivity.kt` — `showGameMenu`
  (~575–594), `helpText`/`showHelp` (~283–328), `gameUrl`/`loadUrl` (~1015, ~1198), companion
  constants (~1857–1902).
- `android/app/src/main/assets/cc-shim.js` — `promisesApi` (the `fs` shim) and `HAS_BRIDGE`/`call`.
- `android/app/src/main/java/io/github/moronigranja/alabasterdawn/GameProfile.kt` — the enum and its
  two instances (add the translation fields).

## Verification

1. **Host suites** (from `android/`): `./gradlew :app:testDebugUnitTest` must stay green, with new
   `TranslationPackTest`:
   - `install` rejects a stream whose SHA-256 ≠ `SHA256` (feed a small in-memory zip + a mismatching
     constant via an injectable `install(stream, expectedSha)` overload; assert `IOException` and that
     no `pack.json` is written).
   - extraction of a fixture zip containing `package.json`, `ccloader/x.js`, `assets/mods/m/ccmod.json`,
     `assets/mods/m/icon.xcf` puts exactly the two `.js`/`.json` entries under `dir/…`, drops
     `package.json` and the `.xcf`, and `isInstalled` is then true.
   - `read`/`exists`/`list` resolve `ccloader/…` and `assets/mods/…` and return null/empty-off for
     `assets/data/database.json` (never reads the game tree).
   Add to `GameProfileTest`: CrossCode's `translationEntryHtml == "ccloader/main.html"`, Alabaster
   Dawn's is null. Add to `FsBridgePathTest`: a `ccloader/x.js` path routes to the pack and a
   `data/database.json` path does not.
2. **Shim smoke**: `cd .. && node tools/test-shim-diagnostics.mjs` must stay green; extend it (or a
   sibling `tools/test-cc-shim.mjs`) to eval `assets/cc-shim.js` against a recording `PortBridge` and
   assert `fs.promises.access` rejects for a missing path and resolves for `assets/mods/x/ccmod.json`,
   and that `fs.promises.readdir('assets/mods')` returns the entries the bridge reports.
3. **Device (owner connects the phone; prerequisite: `TranslationPack` constants match the released
   asset — re-check the sha if the release is re-cut)**:
   - `cd android && ./gradlew :app:assembleDebug`
   - `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`
   - Launch the app → CrossCode card ⋮ → "Add Spanish translation" → wait for "installed".
   - Launch CrossCode: expected observable results — the title screen and the main menu read in
     Spanish, a dialog/scene line reads in Spanish, and `á é í ó ú ñ ¿ ¡` render as glyphs (not
     blanks/boxes); the diagnostics log shows CCLoader's `CCLoader 3.0.0-alpha`,
     `crosscode 1.4.2-…`, `… mods will be loaded: …`, `stage 'prestart' reached!`.
   - ⋮ → "Remove Spanish translation" → relaunch → English again.
   - Record the command, the observed lines and any failure in `FINDINGS.md`.

## Assumptions & contingencies

- **CCLoader runs in the WebView** (the whole route). Falls flat if a stub gap surfaces: the device
  smoke must show CCLoader's boot lines. If it fails, read its first error from the forwarded
  `console.*` diagnostics and fix the *stub* — likely candidates are a missing `fs.promises` method
  (step 5) or a missing virtual path (steps 3/4). If CCLoader cannot be made to boot, fall back to
  the rejected alternative (a purpose-built in-page runner importing the three mods directly); do
  not proceed to step 7's UI until CCLoader boots.
- **The pack's URL/sha are the released `v1.1.0` bytes.** If GitHub re-cuts the asset, re-download,
  re-hash, and update `SHA256`/`SIZE`; the label's "~2.4 MB" is `SIZE` rounded up.
- **Locale is forced, not offered.** CCLoader's modpack adds `Español` via localize-me; we rely on it
  selecting Spanish from the pack's own configuration rather than adding an Options-menu entry. If
  the smoke shows English, the fix is to make the pack's locale selection explicit (the modpack's
  CCLoader user-config, served alongside the pack), not to patch the game.
- **No transport dependency is added**: the download uses `java.net.HttpURLConnection` (the app has
  only `androidx.webkit` today). TLS to GitHub and `objects.githubusercontent.com` is the platform
  stack; no pinning.
