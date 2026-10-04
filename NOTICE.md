# Notices

## This project

RadicalFish Port — an unofficial Android port for Radical Fish Games' NW.js titles (Alabaster Dawn and
CrossCode), plus a desktop controller fix for the same games' PC builds. MIT licensed, © 2026 Moroni
Granja. See `LICENSE`.

This file is copied into the released APK as `assets/NOTICE.md` (with `assets/LICENSE`), so a
distributed binary carries the notices it is distributed under.

## The games are not part of this project

**Alabaster Dawn** — its artwork, audio, text, fonts, data, code and the `terra` engine — and
**CrossCode** — its artwork, audio, text, data, code, the Cubic Impact engine and the jQuery it ships
— are © Radical Fish Games.

**None of it is included in this repository or in the released APK.** The Android app reads the game
from a folder you pick on your own device, serves it to a WebView from the APK, and never modifies it;
the desktop fix (`fix/`) patches a copy inside your own installation and can revert itself. You need
your own legitimately purchased copy of a game to use either.

The compatibility shims in `android/app/src/main/assets/` (`ada-shim.js`, `cc-shim.js`) are this
project's own code; they exist so the unmodified game finds the Node/NW.js surface it expects on a
desktop. No game file is patched on disk, and no game code is redistributed.

"Alabaster Dawn", "CrossCode" and the associated logos are trademarks of their owners, used here
descriptively. This is an unofficial, non-commercial interoperability project — not affiliated with,
sponsored by, or endorsed by Radical Fish Games.

## Third-party components

* **AndroidX WebKit** (`androidx.webkit:webkit`) — Apache License 2.0,
  © The Android Open Source Project. https://developer.android.com/jetpack/androidx/releases/webkit
* **Material Symbols** (the side menu's icons, `android/app/src/main/res/drawable/ic_menu_*.xml`) —
  Apache License 2.0, © Google LLC. https://fonts.google.com/icons
* **Gradle wrapper** (`android/gradle/wrapper/`, used to build) — Apache License 2.0,
  © Gradle, Inc. https://gradle.org
* **CrossAndroid** (https://gitlab.com/Namnodorel/crossandroid) — prior art for running CrossCode on
  Android. That project declares no license (all rights reserved), so **no code was taken from it**;
  the credit is for the architectural precedent only, and this port is an independent implementation.
* **greenworks** (https://github.com/greenheartgames/greenworks) — the Steamworks binding the
  CrossCode build loads. `cc-shim.js` stubs its interface (there is no Steam inside a WebView); no
  code from it is used or redistributed.
* **nwjs/nw.js issue #7006** — an independent report of the same controller-mapping bug class in
  CrossCode, referenced in the research notes.
* **GameNative** / **Winlator** / **Proton** — the Wine containers the desktop controller fix targets;
  named for compatibility documentation only.
* **Best-README-Template** (https://github.com/othneildrew/Best-README-Template) — the document
  structure `README.md` follows. Nothing from it is in the APK.

Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0
