# Calvin 6.1.1 — Standalone Android OEX engine

## Current Stage 3 design

DroidFish resolves `libcalvin.so` through the OEX provider and copies it into its own private engine directory before launching it. The executable is therefore self-contained: its appended payload contains the merged standalone DEX, original Calvin NNUE weights and ARM64 NEON JNI library. The launcher extracts these beside itself, sets `CLASSPATH`, `CALVIN_NNUE_PATH` and `CALVIN_NEON_LIBRARY`, then starts `/system/bin/app_process64`.

The ART entrypoint configures the absolute NNUE and NEON paths before loading Calvin's UCI/Engine classes. The network loader consumes the exact tensor payload and ignores harmless trailing metadata while rejecting truncated files. If the JNI library cannot load, the engine reports the detailed failure and retains the scalar Java fallback. The OEX APK contains only `lib/arm64-v8a/libcalvin.so`; the NEON library is embedded in that executable and extracted at launch.

## Performance diagnostics

Send `uci` and inspect:

```text
info string Runtime: standalone ART process
info string NNUE backend: ARM64 NEON
```

If the backend line reports `Java scalar fallback (...)`, do not treat the run as a NEON performance result. The `bench` UCI command now runs two discarded warm-up passes followed by one measured pass. It uses monotonic nanosecond timing. This improves the measurement but does not claim a speed increase without a phone-side result.

## Historical note

Stage 1/2 used a native UCI-to-loopback relay and a provider-hosted Java foreground service. That design was superseded because the search process depended on the Chess Engines provider package staying active. The old service source files remain as historical reference only; they are no longer registered in the Manifest or called by the OEX provider.

## Compatibility caveat

`app_process64` is a platform executable, not a public SDK process-launch API. Production SELinux policy or OEM customizations may prevent an ordinary GUI-owned app process from executing it or loading an APK as its class path. Build/host checks cannot verify that behavior. The Galaxy A16 must confirm that OEX can execute the copied PIE, that app_process can load the extracted DEX, and that DroidFish can complete `uci`, `isready`, `position`, `go`, `stop`, and `quit` after the Chess Engines provider app is closed. If this bootstrap is blocked, a true independent solution requires porting Calvin's search to native code.
