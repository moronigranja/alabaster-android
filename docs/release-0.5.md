## v0.5

* **Exiting the game works.** The title screen's **Exit** and `System.quit` were shim no-ops, so the
  engine hid its own menu and waited for a process exit that never came — the frozen picture that
  ends issue #1. Both now leave the app.
* **Exit ends the app process**, so the next launch is a fresh process with a fresh WebView renderer.
  The old "swipe it out of recents first or the second start comes up black" workaround is gone.
* **The side menu** (Back) is now one uniform, full-width, icon-led list — the whole row is the tap
  target, and the current picture position is a filled pill.
* **The port's version** is on the setup screen and in the side menu's status block.
* **The record names the game's build too**, not just the port's: `game 0.1.0-10 Early Access`.

Full release notes: [docs/release-notes-0.5.md](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.5.md)
Install, requirements and known limitations: [README](https://github.com/moronigranja/alabaster-android#readme)
