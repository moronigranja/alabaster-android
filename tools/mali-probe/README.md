# Mali shader probe

Answers one question on real hardware: **what does a device's GL front end do with the game's own
shader declarations?** It is the port's document-start gate (`FINDINGS.md` §22.8) asked from a plain
app instead of from inside a WebView — an offscreen EGL context is served by the platform's *native*
GL driver unless the package is opted into ANGLE, which is the path the Mali `S0032` reports are
about — and it runs unattended on a device farm, so a Mali generation we do not own can answer in
five minutes.

It is a **second, standalone Gradle project**: nothing here is part of the port's build, and nothing
here ships.

## Use

```bash
# 1. cases: the game's own shaders, expanded the engine's way, plus the port's edits and the
#    candidate edits still in question. They are game files: `cases/` is git-ignored.
python3 tools/mali-probe/make-cases.py \
  --shader-root /path/to/terra/data/shader \
  --reportershader /path/to/a/player's/analog-filter.frag   # optional

# 2. build (the port's own Gradle wrapper, pointed at this project)
ANDROID_HOME=$HOME/Android/Sdk android/gradlew -p tools/mali-probe :app:assembleDebug :app:assembleDebugAndroidTest

# 3. run it somewhere. On a device farm, use an instrumentation run and read `logcat`:
gcloud firebase test android run --type instrumentation \
  --app tools/mali-probe/app/build/outputs/apk/debug/app-debug.apk \
  --test tools/mali-probe/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --device model=akita,version=34,locale=en,orientation=portrait --timeout 5m
```

Every case logs one line under the tag `MaliProbe`:

```
I MaliProbe: gl renderer=ANGLE (ARM, Vulkan 1.3.278 (Mali-G720 MC7 (0xC8700010)), Mali-G720 MC7-49.1.0)
I MaliProbe: PROBE files/analog-filter/original.frag compile=0 link=- log=0:62: S0032: no default precision defined for variable 'vec3[5]'
I MaliProbe: PROBE files/analog-filter/lifted.frag compile=1 link=- log=-
I MaliProbe: SUMMARY cases=26 compiled=25 refused=1 unlinked=0
```

`link` is asked only of the `shapes/` cases, which are the port's own gate (one of them declares a
varying and gets a matching vertex shader). The `files/` and `variants/` cases are whole fragment
shaders: the engine links them with their real `.vert`, which a probe without the game cannot, and the
Mali failure this project fixes is a *compile* failure.

## What the cases are

| group | what it is | why |
|---|---|---|
| `shapes/` | the nine declaration shapes the port's gate compiles | tells whether a device refuses what the gate says it refuses |
| `files/<shader>/original.frag` | the game's own bytes, expanded | the baseline: what the engine compiles without the port |
| `files/<shader>/lifted.frag` | the same bytes with `ShaderArrays`' edits applied | whether the port's spelling is accepted |
| `variants/analog-filter/*` | the post pass with each half of its declaration changed, and each candidate repair | which half the driver refuses, and which repair it takes |

`make-cases.py` mirrors the port's edit table and **fails loudly** if a find-string is not in the
game's bytes, so the probe cannot silently drift from what the port serves.

## Reading a run

* `gl renderer=…` with `ANGLE` in it means the platform gave the probe ANGLE (a workaround, and not
  the path under test); a bare `Mali-G…` means the native driver, which is the path the port's lift
  exists for.
* `compile=0` on a `files/*/original.frag` and `compile=1` on the matching `lifted.frag` is the port's
  fix confirmed on that device, by file.
* A `variants/` result says which edit a device actually wants — measured, not guessed.
