# OEX + Calvin Java Engine (Option A)

This project integrates Calvin's Java source directly into the OEX Android application. Calvin runs inside the app's Android ART process and is **not** represented as an ELF executable or advertised in OEX's `enginelist.xml`.

The integration adds an exported Binder/Messenger service carrying ordinary UCI command and output lines. This keeps the engine independent of DroidFish, but a chess GUI must implement the included service contract (or adapt `CalvinEngineServiceClient.java`). Existing GUIs that only understand OEX's traditional executable-provider format cannot use this Java engine without a GUI-side adapter.

## Build

Requirements: JDK 17, Android SDK platform 35, and Android Build Tools. The project keeps the original minSdk 19 and enables multidex plus core-library desugaring for the Java APIs used by Calvin. From this directory:

```bash
./gradlew :app:assembleDebug
```

Gradle downloads the official `calvin1024_8b.nnue` file during `preBuild`, checks its expected architecture-derived size, and packages it as an app asset. The NNUE is not stored in this source ZIP.

## Important

- The supplied OEX source ZIP did not contain the referenced native engine `.so` binaries. The final build configuration also excludes `jniLibs` and all `**/*.so` packaging, and the OEX engine list is empty so it does not advertise missing native engines.
- Calvin's desktop JDK Vector API calls were replaced with scalar Java compatibility code for Android ART. This is a portability baseline; NPS and numerical equivalence must be validated on a real Android device against the upstream desktop engine.
- The Android APK could not be built in the current environment because the Android SDK is unavailable. The Calvin Java core and the Android service/client classes were compiled with `javac` (the Android classes against minimal API stubs); a UCI smoke test with a synthetic network returned `uciok`, `readyok`, and `bestmove`. This does not validate the official NNUE or an Android device build.

See [`docs/CALVIN_ANDROID_INTEGRATION.md`](docs/CALVIN_ANDROID_INTEGRATION.md) for architecture, service protocol, and verification details.
