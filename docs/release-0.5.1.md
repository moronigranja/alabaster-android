## v0.5.1

Fixes for running the game's **Steam demo** through the port, found by running it.

* **The demo's Resolution menu named resolutions it does not render.** The port rewrites the game's
  resolution ladder to add a 960x540 rung; the demo's bundle has no such ladder (its own is left
  alone, correctly), but the option *labels* were rewritten anyway — `1280x720` → `960x540` and
  `1920x1080` → `1280x720`. The two rewrites now move together: a build that does not use the
  release's ladder keeps its own truthful labels. The release build is unchanged.
* **The demo no longer litters your saves folder.** The demo-era engine builds its save paths
  Windows-style (`\Saves\Default\`); the bridge treated those as save-relative and SAF rewrote the
  illegal characters, creating `_Saves_`, `_Saves_Default_`, `_Saves_Backups_`, `_Saves_Backups2_` in
  the folder you picked while the demo's own saves went nowhere. Both separators now resolve onto the
  same `Saves/` layout, and `mkdir` is refused outside the save root like every other file call.
* Removed a dead branch in the resolution-option rewrite (it computed a default and returned the old
  text, so it never did anything).

Full release notes: [docs/release-notes-0.5.1.md](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.5.1.md)
Install, requirements and known limitations: [README](https://github.com/moronigranja/alabaster-android#readme)
