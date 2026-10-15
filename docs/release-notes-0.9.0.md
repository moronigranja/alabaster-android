# RadicalFish Ports v0.9.0 - release notes

An **unofficial**, non-commercial Android port of two Radical Fish Games NW.js titles, run from the copy
you already own — [Alabaster Dawn](https://store.steampowered.com/app/3110760/) (Early Access) and
[CrossCode](https://store.steampowered.com/app/368340/). Install, requirements and the legal notes are in
the [README](../README.md); the terse changelog is [docs/release-0.9.0.md](release-0.9.0.md); the
technical record is `FINDINGS.md` §25 (the localised UI), §26 (the Spanish translation), §27 (the mod
library) and §28 (Brazilian Portuguese).

0.9.0 is about the port's own chrome, not the games: the UI now follows the phone's language, and
CrossCode gains an optional translation and a general mod library. The games themselves are still read
from your folder and never edited.

**The port speaks your phone's language.** Every string the port shows — the entry screen and its cards,
the side menu, the diagnostics panel, the mods dialog, the status and share messages — moved out of the
Kotlin and into Android string resources, with **Spanish** (`values-es`) and **Brazilian Portuguese**
(`values-pt`) translations beside the English. Android selects them from the phone's own language: there
is no picker, no setting and no app restart button — change the phone's language and the port follows.
The two translation files mirror the English one key for key (93 keys each, same order, same placeholder
arity), and `android/tools/check-locales.mjs` fails on any drift, because a mismatched placeholder is a
runtime crash rather than a build error. The port's diagnostics record, its config keys and its wire
values stay in English on purpose — a report is more useful when it reads the same for everyone. The
translations are authored for this port (no upstream source); corrections from native speakers are
welcome.

**Spanish for CrossCode, in one tap.** The CrossCode card's **⋮** now offers **Add Spanish translation
(~2.4 MB)**. The tap downloads the community [CrossCode-Esp](https://github.com/tralph3/CrossCode-Esp)
v1.1.0 pack — a CCLoader modpack (the loader plus `CrossCode-Esp`, `Localize-me` and their dependencies)
— verifies it against a pinned SHA-256, unpacks it into the app's own storage and starts the game through
the pack's CCLoader entry page in Spanish. The item then reads **Remove Spanish translation**. The pack
is community work by tralph3 and contributors, **CC BY 4.0**, credited in the card's **Help**; it is not
in the APK, and your picked game folder is neither read for it nor written to. The port forces the `es`
locale only while this pack is installed, so the game's own Options language switch still works
otherwise.

**A mod library for CrossCode.** The same **⋮** offers **Mods…**: a dialog listing what is installed —
title, version, on/off, **Remove** — with the loader's version, or a plain statement that none is
installed. **Add mod (.ccmod)** opens the system file picker and installs a `.ccmod` (or `.zip`) the same
way the translation pack is installed: a single mod manifest at the archive root lands as one mod, a bare
CCLoader build lands as the loader, and the pack's own shape lands as it is; a wrapper directory is
stripped, a `..` segment or an unknown shape is refused with a message. Installed mods live in one
game-root tree inside the app's storage — `ccloader/` for the loader, `assets/mods/<id>/` for each
enabled mod, `disabled/<id>/` for a mod switched off — so "installed" is a directory and "enabled" is
which of the two it is in; there is no index file to go stale, and the dialog re-reads the disk after
every action. **Your copy of the game is never written to.** Removing the Spanish translation drops the
loader and the pack's own mods and leaves mods you added yourself in place. One thing is not built yet: a
mod that CCLoader itself will refuse still installs, and the reason is reported in CCLoader's own console
rather than at install time.

**One new permission.** The Android app now requests **INTERNET**, and only so the optional translation
download can happen; nothing else is fetched or sent, and every other feature works offline. No game
content is ever uploaded or downloaded by the port itself.

**Verified.** 172 unit tests and the shim harnesses (`test-shim-diagnostics.mjs` 62/62,
`test-cc-shim.mjs` 8/8) pass; `check-locales.mjs` reports all three locales agreeing on 93 keys. On a
Galaxy Z Fold 7 (Android 17), with a throwaway debug build driven through the real UI: the Spanish pack
downloaded and installed in about a second, CrossCode booted through CCLoader with
`ig.currentLang = es_ES` and the pack's own Spanish fonts loading over the game's (four clean repeats),
a user-picked `.ccmod` installed, switched off and removed, and removing the translation left the
game opening its plain English entry page. The UI was then read back with a per-app locale override —
the entry screen, side menu, diagnostics and mods dialogs in **pt-BR** and in **es** — with zero
`IllegalFormatException`/`MissingFormatArgument` lines in logcat across every launch. **Not verified**:
the release-signed build itself, on a device (the smoke runs used a debug build with a throwaway package
suffix), and the glyph rendering of the Spanish accents by eye rather than by font metrics. CrossCode
*play* remains open as before: audio, the in-game pad mapping, the heavier scenes' GPU cost and the save
round-trip back to a desktop.

**Upgrading**: the same package and key as every release so far, so install over the old build; your
picked folders, saves and settings are kept, and nothing needs re-picking. Installing over a *debug*
build still needs an uninstall first (the debug key differs by design).

**Also in this release, no code change**: the game's own Early Access build `0.2.0-8` — Steam's update
past the `0.2.0-6` this port's shader work was measured on — ships the same bytes the port's rewrites
key on, and a boot with it in the picked folder lifted every shader at its full count with no
`NOT lifted (0/x)`. Recorded in `FINDINGS.md` §29 with the screenshot `docs/ada-0.2.0-8-title.png`.
