# TODO

## Next version

- **Put the version where the user can see it.** Today the version exists only in the APK's own
  metadata (`versionName 0.4.2`), so anyone reporting a problem has to dig through Android's
  app-info screen to find which build they are on — and a bug report without it costs a round trip.
  Show it on the setup screen and in the side menu (both already render text: the pre-game screen's
  diagnostics paragraph and the menu's status lines), and add it to the diagnostics header lines, so
  every screenshot or shared log says which build it came from. `BuildConfig.VERSION_NAME` is
  available in the release build the moment `buildConfig` is enabled for the release variant.
