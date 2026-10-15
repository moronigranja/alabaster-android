## v0.9.0

Three additions to the port's own chrome; the games' code is untouched, as always.

* **The port speaks your phone's language.** Every string the port shows — the entry screen, the side
  menu, the diagnostics panel, the mods dialog, the status messages — is a resource now, with
  **Spanish** and **Brazilian Portuguese** beside the English. Android picks it from the phone's
  language: nothing to set.
* **Spanish for CrossCode, one tap away.** The CrossCode card's **⋮** gains **Add Spanish translation
  (~2.4 MB)**: it fetches the community *CrossCode-Esp* pack (CC BY 4.0, credited in the card's
  **Help**), checks it against its SHA-256, installs it into app storage and boots the game through
  CCLoader in Spanish. The same item removes it. Nothing is bundled, and your game folder is never
  read for it or written.
* **A mod library for CrossCode.** The same **⋮** gains **Mods…**: install any `.ccmod`/`.zip`, switch
  a mod off or on, or remove it, with the CCLoader version shown. Mods live in app storage beside the
  game, never in your copy of it.

The app now requests the **INTERNET** permission — for the optional translation download, and nothing
else. Everything else still works offline.

Verified on a Galaxy Z Fold 7 (Android 17): the Spanish pack downloads, installs, boots with
`ig.currentLang = es_ES` and Spanish glyphs, and removes clean; a user-added `.ccmod` installs,
disables and removes; and the whole UI was read back in Portuguese and Spanish under a per-app locale
override, with no format errors in logcat. Full detail in `FINDINGS.md` §25-§28.

[Issues](https://github.com/moronigranja/alabaster-android/issues) ·
[fuller notes](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.9.0.md)
