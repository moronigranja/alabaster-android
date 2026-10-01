# Alabaster Dawn Android port v0.7.7 - release notes

An **unofficial**, non-commercial Android port of [Alabaster Dawn](https://store.steampowered.com/app/3110760/)
(Radical Fish Games, Early Access). Install, requirements and the legal notes are in the [README](../README.md);
the changelog is [docs/release-0.7.7.md](release-0.7.7.md); the technical record is `FINDINGS.md`
§22.13–§22.18.

**Update if you have water in view.** The water has been invisible on every device since an early build: the
port's keep-alive for `v_barycentric` used a sentinel (`-1e30`) that `mediump` cannot hold, and in the water
fragments — whose program is the one that loses that attribute — it fired, so the fragment returned before
drawing. The water shaders are now served at `highp` in both stages, where that sentinel means what it says.

**If your device refused the game's shader declarations** (the Mali phones that froze at boot), that repair is
still gated to you: on a device that never needed it, it breaks Adreno's link instead. The array lift, by
contrast, is now served on **every** device — asking the probe first cost one device a frozen boot (issue #5)
and gains nothing.

**Reporting a shader problem**: install, start the game, `Back` → `Troubleshoot` → **Test shader spellings**,
then `Share`. It compiles your own game files on the engine's own context and names the spelling your device
takes; and for any shader the engine refuses, the record now says whether the port served it lifted.

**Also**: the 40 fps rung on the battery slider (20/30/40/45/60), and `window=`/`canvas=` in the facts line.

**Verified**: water drawing and a clean boot on an S22 Ultra, and the lift accepted by Adreno (every file
matched, no link failure) — 123 unit tests, 62 harness checks. Not verified here: a Mali device for the gated
repair, which is what the reporter's next record closes.
