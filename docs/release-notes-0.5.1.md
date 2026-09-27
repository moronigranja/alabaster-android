# Alabaster Dawn Android port v0.5.1 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements, known limitations and the legal notes are
in the [README](../README.md); the terse changelog is [docs/release-0.5.1.md](release-0.5.1.md).

This release comes from running the game's **Steam demo** through the port for the first time. The
demo (`terra/` 226 MB / 2 327 files) boots (`ENGINE boot: complete in 6655ms, 1641 resources`, 0
failed decodes), draws its own title screen and intro, and now saves where it should. Two defects
only the demo exposed, both fixed:

## The Resolution option named the wrong rungs

The port rewrites the game's resolution ladder so a phone has a 960x540 rung between 640x360 and
1280x720, and renames the option's rungs to match. The demo's bundle does not contain that ladder at
all, so the ladder rewrite was correctly skipped — but the *relabel* has its own anchor, which the
demo shares, and ran anyway:

| | demo's own | what the port served |
|---|---|---|
| langID 172 | `1280x720` | `960x540` |
| langID 173 | `1920x1080` | `1280x720` |

So the demo's Options menu would have offered resolutions it does not render. The two rewrites are
gated together now: a build whose ladder was not rewritten keeps its own labels. Verified by reading
back what the WebView was served over CDP — demo `{"en_US":"1280x720","langID":172}` untouched and
its `bundle.js` byte-identical to the file on disk; release `{"en_US":"960x540","langID":172}` with
the rewritten ladder present, exactly as before.

Also removed there: a branch that computed a new option default and then returned the old text, so
the change it described never reached the served bytes. The rewritten ladder already lands a fresh
profile on the 60 fps rung, so nothing observable changes.

## The demo littered the saves folder

The demo-era engine builds its save root as `nw.App.dataPath + "\Saves\Default\"`. The port sets
`dataPath` to the literal `/saves`, so the bridge receives `/saves\Saves\Default\`. Every file call
gates on "is this under the save root?" — except `mkdir`, which passed the path straight through. A
path that was not a save path was therefore treated as one and handed to Android's file layer, whose
`buildValidFatFilename` rewrites the illegal `\` characters:

```
_Saves_   _Saves_Default_   _Saves_Backups_   _Saves_Backups2_     (and " (1)" copies on a re-run)
```

…in the folder you picked, while the demo's real save reads and writes resolved to nothing. (The
released engine never showed this: 0.1.0 uses `/Saves/`. It even ships its own storage fix that
repairs `"/Default_Saves_Default_"` for desktop players who hit the same thing.)

Two changes: `mkdir` is refused outside the save root like every other call, and the save-path
mapper treats `\` as a separator, so the demo's paths now land on the same `Saves/Default` Steam
layout as the released engine — meaning a demo save and a release save live side by side and survive
switching between the two installs.

Verified against a fresh saves folder: the demo creates exactly `Saves/`, `Saves/Default/`,
`Saves/Backups/`, `Saves/Backups2/` and nothing else, and the release build still boots, rewrites its
ladder, relabels its options and writes the same layout. `FsBridgePathTest` (6 tests) covers the
namespace rule and the mapping, including the `..` refusal.

## Everything else is v0.5

Nothing else changed. See the [v0.5 notes](release-notes-0.5.md) for the game's own Exit, the
fresh-process launches, the port/game versions in the record, and the icon-led side menu.
