# Calvin 6.1.1 — Android OEX integration

This repository packages Calvin as an Android Open Exchange (OEX) UCI engine for compatible chess GUIs such as DroidFish.

## Current architecture (Stage 3)

- OEX registers `libcalvin.so` as the Calvin 6.1.1 engine.
- `libcalvin.so` is a small ARM64 Android PIE executable, not a normal JNI shared library. The GUI launches it as the UCI child process.
- OEX copies the selected executable into the GUI private engine directory. The launcher extracts its embedded DEX, NNUE weights and NEON JNI library beside itself, then starts `CalvinUciMain` through Android `app_process64`.
- Calvin's chess search, board, move generation and UCI remain Java.
- `libcalvinneon.so` is embedded inside `libcalvin.so`; it supplies JNI NNUE SCReLU and accumulator kernels.
- The original NNUE network is embedded in the launcher and extracted into the GUI-owned runtime directory at engine startup.
- The OEX Activity and provider service are not part of the UCI session lifetime.

See [`docs/CALVIN_STAGE3_STANDALONE_LAUNCHER.md`](docs/CALVIN_STAGE3_STANDALONE_LAUNCHER.md) for the process model and Android compatibility caveats.

## Build

Run the GitHub Actions workflow **Build Calvin OEX Standalone (ARM64)** manually. It uses JDK 17, Android SDK 35, and NDK 27.2.12479018, then builds `:app:assembleDebug`.

The APK is intended to contain exactly one native payload: `lib/arm64-v8a/libcalvin.so`. This is the executable OEX launcher (PIE) and it embeds the standalone DEX, original NNUE network, and NNUE JNI accelerator.

The original native chess-engine source/library directories in a user's fork are left in place but excluded from the generated APK through the isolated generated `jniLibs` source set.

## Local checks

```bash
python3 scripts/verify_calvin_standalone_launcher.py
./scripts/test_network_zip_loader.sh
cc -std=c11 -O2 -Wall -Wextra -Werror -fsyntax-only app/src/main/cpp/calvin_bridge.c
```

## Runtime verification still required

The CI checks validate source wiring, C syntax, NNUE ZIP reading, and ARM64 ELF packaging. They cannot prove that a production Android build permits an ordinary app process to execute `app_process64` and load the extracted standalone DEX. That must be tested on the target phone. If SELinux/OEM policy blocks the bootstrap, the launcher approach must be replaced by a genuine native Calvin UCI port; the old provider-service relay is not a truly independent executable.
