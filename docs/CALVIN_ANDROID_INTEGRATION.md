# Calvin Android integration (OEX host)

## Architecture

Calvin is compiled as Java source into the OEX APK and executes in Android ART. It is **not** represented as an OEX executable and is intentionally absent from `res/xml/enginelist.xml`.

A bound Android service (`com.kelseyde.calvin.android.CalvinEngineService`) exposes a small Binder/Messenger contract. Clients send UCI command strings and receive UCI output strings. This keeps the engine independent of DroidFish's source tree, but a chess GUI must implement this service contract (or use the included `CalvinEngineServiceClient` helper). Existing GUIs that only understand the traditional OEX executable-provider format cannot directly invoke this Java service.

## NNUE download

The Gradle task `downloadCalvinNetwork` downloads the network expected by the supplied Calvin source:

- File: `calvin1024_8b.nnue`
- URL: `https://raw.githubusercontent.com/kelseyde/calvin-chess-engine/main/src/main/resources/calvin1024_8b.nnue`
- Generated location: `app/build/generated/calvinAssets/`

The task is a dependency of `preBuild`, so the network is fetched as part of an APK build. It verifies the exact 12,589,058-byte network size before packaging. The network is not committed into this source ZIP. The app copies the bundled asset to its private files directory before the Calvin classes are initialized and sets `calvin.nnue.path` before the NNUE static initializer runs. The Java network loader checks the same architecture-derived file size before parsing it.

To force a fresh download, use `./gradlew :app:downloadCalvinNetwork --rerun-tasks`.

## Service protocol

Bind to the action `ccc.chess.engines.action.BIND_CALVIN_ENGINE` in package `ccc.chess.engines`.

Message types (defined in `CalvinEngineProtocol`):

- `MSG_REGISTER_CLIENT` (1): register `Message.replyTo` as the callback Messenger.
- `MSG_UNREGISTER_CLIENT` (2): unregister the callback Messenger.
- `MSG_COMMAND` (3): send a UCI command in `Bundle` key `command`.
- `MSG_OUTPUT` (4): receive one UCI output line in `Bundle` key `line`.
- `MSG_ERROR` (5): receive a service/initialization error in `Bundle` key `line`.

A GUI can either implement Messenger directly or reuse `CalvinEngineServiceClient.java`. The helper queues commands issued before the service connection is ready and calls `Listener.onConnected()` once registration has been sent. After that, send `uci`, `isready`, and normal UCI commands such as `ucinewgame`, `position startpos`, `go movetime 1000`, and `stop`. Pass each received `MSG_OUTPUT` line to the GUI's UCI parser, and unbind when done.

The service is exported to allow a separate chess GUI app to bind. It accepts only one controlling GUI session at a time because the upstream Calvin `Engine` uses a singleton; a second client receives a busy error. A production GUI should bind explicitly to the OEX package and handle service disconnection and errors.

## Native executable packaging

This build points the app's `jniLibs` source set at no directories and excludes `**/*.so` from APK packaging. The supplied ZIPs did not contain the referenced Rodent/OpenTal/Toga/Gambit/Laser/Andscacs native libraries; their old names have therefore been removed from the advertised OEX engine list rather than leaving broken engine entries. The OEX provider classes remain in the project, but the provider currently advertises no native engines.

## Important limitations / verification status

- The Calvin JDK incubator Vector API (`jdk.incubator.vector`) is not available to ordinary Android ART applications. Its accumulator and SCReLU/CReLU code has been replaced with scalar Java implementations. This is a portability baseline, not a claim of matching the desktop SIMD speed.
- The current Calvin source is compiled into the app; this does not make it visible to unmodified OEX-only chess GUIs. A GUI-side Messenger client is required.
- The Android APK has not been built in this environment because no Android SDK is installed. The project preserves minSdk 19 and enables multidex plus core-library desugaring for the Java time/stream APIs used by Calvin. Core Calvin Java sources compile successfully with host JDK 21 after the compatibility changes. The NNUE is downloaded only during a real Gradle build, so live search/evaluation has not yet been run against Calvin's actual network here.
- Before using tournament results, validate NNUE outputs against the upstream engine, then run perft/UCI smoke tests and an NPS/strength comparison on an Android device.
