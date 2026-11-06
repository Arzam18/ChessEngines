CALVIN 6.1.1 — STAGE 3: SELF-CONTAINED STANDALONE OEX LAUNCHER

The OEX engine name is Calvin 6.1.1. The selected file is libcalvin.so, an
ARM64 Android PIE executable (ELF ET_DYN with an interpreter). The .so suffix
is retained for the OEX engine manifest; this file is executed as a process,
not loaded as a shared library.

Important OEX behavior: DroidFish copies the selected provider executable into
its own private engine directory before starting it. Therefore the engine
launcher cannot depend on finding the provider APK next to /proc/self/exe.

The launcher is now self-contained. Its appended payload contains:
  1. A merged standalone classes.dex with Calvin Java and required desugared classes
  2. calvin1024_8b.nnue (the original upstream NNUE weights)
  3. libcalvinneon.so (the JNI ARM64 NEON kernel library)

When the GUI launches the copied executable, the launcher extracts those three
components into a private runtime directory beside the copied executable, sets
CLASSPATH and the NNUE/NEON paths, then execs Android app_process64. The ART
Java entrypoint runs Calvin UCI using the GUI's inherited stdin/stdout pipes.
No provider Activity, provider Service, Binder connection, or TCP relay is used.

Calvin search and NNUE orchestration remain Java. The embedded NEON library
accelerates NNUE SCReLU and accumulator kernels. The OEX APK contains only
lib/arm64-v8a/libcalvin.so; its embedded runtime includes the JNI library.
Original native engine folders remain in the source repository and are not
packaged into this Calvin-only APK.

Performance diagnostics:
  uci   -> reports "NNUE backend: ARM64 NEON" or a detailed scalar fallback reason
  bench -> two warm-up passes and one measured pass, using monotonic nanosecond timing

Host checks:
  python3 scripts/verify_calvin_standalone_launcher.py
  ./scripts/test_network_zip_loader.sh
  cc -std=c11 -O2 -Wall -Wextra -Werror -fsyntax-only app/src/main/cpp/calvin_bridge.c

ON-DEVICE LIMITATION:
  app_process is an Android platform executable, not a public SDK launcher API.
  SELinux/OEM policy may block an ordinary app process from executing it or
  loading a standalone DEX. The NDK/Gradle build and host checks cannot prove
  this works on a Galaxy A16. The device must verify uci/isready/position/go/stop/quit
  and that Calvin remains alive after the Chess Engines provider app is closed.
  If app_process is blocked, the next robust option is a native Calvin UCI port.
