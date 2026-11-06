# Calvin Android integration

The current supported source layout is documented in [Stage 3 — Standalone OEX launcher](CALVIN_STAGE3_STANDALONE_LAUNCHER.md).

The OEX list registers `libcalvin.so`. It is a PIE executable, not a conventional JNI library. The GUI launches it as its UCI child process. The launcher starts Calvin's Java entrypoint through Android ART, inheriting the GUI's stdin/stdout. The provider Activity and a provider-owned Android Service are not part of this engine session.

`libcalvinneon.so` is a separate JNI shared library containing the NNUE ARM64 NEON kernels. The Java runtime loads it by absolute path before initializing the engine. The NNUE source is read from the uncompressed APK asset entry via `ZipFile`.

The old Stage 1/2 Binder/socket service classes remain in source history but are not in the Manifest and are not used by the current OEX engine. See `docs/CALVIN_STAGE2_SERVICE_LIFECYCLE.md` for the superseded approach.

**Runtime limitation:** app_process is an Android platform mechanism rather than a public SDK API for application-owned UCI runtimes. It requires real-device validation because SELinux/OEM policy may block it. The CI checks do not claim this behavior is confirmed on the Galaxy A16.
