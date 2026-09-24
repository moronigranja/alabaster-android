# Notices

## This project

Alabaster Dawn Android port + desktop controller fix — MIT licensed, © 2026 Moroni Granja.
See `LICENSE`. This file is copied into the released APK as `assets/NOTICE.md` (with
`assets/LICENSE`) so a distributed binary carries the notices it is distributed under.

## The game is not part of this project

Alabaster Dawn — its artwork, audio, text, fonts, data, code and the `terra` engine — is
© Radical Fish Games. **None of it is included in this repository or in the released APK.**
The Android app reads the game from a folder you pick on your own device and never modifies it;
the desktop fix patches a copy inside your own installation and can revert itself.
You need your own legitimately purchased copy of the game to use either.

"Alabaster Dawn", "CrossCode" and the associated logos are trademarks of their owners, used here
descriptively. This is an unofficial, non-commercial interoperability project — not affiliated
with, sponsored by, or endorsed by Radical Fish Games.

## Third-party components

* **AndroidX WebKit** (`androidx.webkit:webkit`) — Apache License 2.0,
  © The Android Open Source Project. https://developer.android.com/jetpack/androidx/releases/webkit
* **Gradle wrapper** (`android/gradle/wrapper/`, used to build) — Apache License 2.0,
  © Gradle, Inc. https://gradle.org
* **CrossAndroid** (https://gitlab.com/Namnodorel/crossandroid) — prior art for running this
  engine on Android. That project declares no license (all rights reserved), so **no code was
  taken from it**; the credit is for the architectural precedent only, and this port is an
  independent implementation.
* **nwjs/nw.js issue #7006** — an independent report of the same controller-mapping bug class in
  CrossCode, referenced in the research notes.
* **GameNative** / **Winlator** / **Proton** — the Wine containers the desktop controller fix
  targets; named for compatibility documentation only.

Apache License 2.0 text: https://www.apache.org/licenses/LICENSE-2.0
