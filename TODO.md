# TODO

## Next version

- **Put the version where the user can see it.** Today the version exists only in the APK's own
  metadata (`versionName 0.4.2`), so anyone reporting a problem has to dig through Android's
  app-info screen to find which build they are on — and a bug report without it costs a round trip.
  Show it on the setup screen and in the side menu (both already render text: the pre-game screen's
  diagnostics paragraph and the menu's status lines), and add it to the diagnostics header lines, so
  every screenshot or shared log says which build it came from. `BuildConfig.VERSION_NAME` is
  available in the release build the moment `buildConfig` is enabled for the release variant.

- **Make the side menu's rows uniform and full width** (an Eden / Azahar style settings list). The panel
  currently mixes three looks: `Diagnostics` and `Exit` are full-width platform `Button`s, the three
  switches are bare `Switch` widgets whose touch target is only the label (and one label wraps to two
  lines, so those rows are not even the same height), and the three radio rows are small, indented, with
  no row background at all (`SideMenuView.buildPanel` and `addRadio`). The buttons already span the
  panel, so everything *else* has to catch up to them: one row builder — `MATCH_PARENT`, 48 dp minimum
  height, `dp(12)` horizontal padding, label left on a single line with ellipsis, control (switch or
  radio) pinned to the right edge, a thin divider between rows, and
  `?android:attr/selectableItemBackground` so the whole row ripples and the whole row is the hit target —
  used by every entry, action buttons included. Section headers ("Game position") and the status/engine
  lines keep their current smaller, dimmer style: they are not actions.
