## v0.8.0

One app, two games: the port now runs **CrossCode** as well as Alabaster Dawn, and it is called
**RadicalFish Ports**. Same package and key as before, so it updates in place.

* **CrossCode.** A second, different engine (Cubic Impact 0.5): boots to its title screen in the desktop
  harness. On a phone its GPU cost, audio and pad mapping are still open.
* **An entry screen with a card per game** — its own title art, both folder paths, its own saves folder,
  and a **⋮** with **Help: what to copy** and **Create a home-screen link**. A folder that has gone is
  marked, not silently claimed.
* **Saves in either shape**: copy the game's save folder whole (`Saves/…`, `Default/…`) or its contents
  flat — the port reads both, and saves travel back to the PC with no renaming.
* **Keyboard for CrossCode**: its bindings are indexed by the legacy `keyCode`, which the port now sets.
* **Picture position (Top/Centre/Bottom) for both engines**, and **dynamic sticks** on the on-screen pad.
* **A new launcher icon** — a circuit fish on a slate field, fit to the adaptive-icon safe circle.

[Issues](https://github.com/moronigranja/alabaster-android/issues) ·
[fuller notes](https://github.com/moronigranja/alabaster-android/blob/main/docs/release-notes-0.8.0.md)
