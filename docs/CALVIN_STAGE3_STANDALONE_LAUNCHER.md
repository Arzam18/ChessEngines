# Calvin Stage 3 — Self-contained standalone OEX launcher

## Why the launcher embeds its runtime

The OEX resolver obtains the engine from the provider's `nativeLibraryDir`, but
DroidFish's OpenExchangeEngine implementation copies that executable into the
GUI's own private engine directory before calling `ProcessBuilder`. The process
therefore cannot assume its sibling files or the provider APK are still next to
`/proc/self/exe`. The registered engine must carry everything needed to start.

## Process layout

```text
DroidFish / another OEX GUI
  └── copies and launches libcalvin.so from its private engine directory
        └── libcalvin.so (ARM64 Android PIE executable)
              ├── extracts classes.dex
              ├── extracts calvin1024_8b.nnue
              ├── extracts libcalvinneon.so
              └── execs /system/bin/app_process64
                    └── CalvinUciMain (ART)
                          └── Calvin Java UCI + search
                                └── JNI ARM64 NEON NNUE kernels
```

`libcalvin.so` is intentionally a PIE executable with an ELF `ET_DYN` header
and interpreter, despite the `.so` filename. OEX treats the filename as an
engine executable. It is not loaded by `System.loadLibrary`. The `libcalvinneon.so`
file is a genuine JNI shared library, embedded inside the launcher payload and
extracted only when the GUI starts Calvin.

## Embedded payload format

The Gradle task first obtains the AGP project and external DEX outputs (including
core-library-desugaring classes), merges them with Build Tools D8, then appends
three binary segments to the raw PIE launcher: `classes.dex`, the original NNUE
file, and the ARM64 NEON JNI library. A 32-byte little-endian footer (`CVRT001`
plus the three segment lengths) is appended at EOF. The C launcher validates
the footer and component bounds before extracting each segment atomically.

The extracted files live in `.calvin-runtime-6.1.1/` beside the executable in
the GUI-owned engine directory. The Java entrypoint reads absolute paths from
`CALVIN_NNUE_PATH` and `CALVIN_NEON_LIBRARY`, sets the Calvin network properties,
loads the JNI library, and starts UCI. Standard input/output/error are inherited
from the launched process; there is no socket/Binder handoff.

## APK contents and repository preservation

The Calvin OEX APK contains one native payload: `lib/arm64-v8a/libcalvin.so`.
That executable embeds the runtime DEX, NNUE network and JNI accelerator. The
original chess-engine source folders remain in the repository but are excluded
from this Calvin-only APK. Stage 1/2 provider-service and socket-relay source
files remain as historical reference only and are not registered or called.

## Lifecycle expectation

The engine process is started by the GUI, under the GUI process UID, as a child
process. Closing the Chess Engines provider Activity or stopping the provider
package should not terminate the already-running process. Closing or force-stopping
the GUI itself terminates its engine child, as expected.

## Android compatibility caveat

`app_process64` is a platform executable rather than a public SDK API. A
production SELinux policy or OEM customization may prevent an ordinary app
process from executing it. A `CLASSPATH` DEX launch is a known Android mechanism,
but the exact OEX-child-process arrangement must be verified on the target
Galaxy A16. GitHub Actions can prove cross-compilation and payload structure;
it cannot prove SELinux permissions or successful ART startup on the phone.
If the device blocks this route, the robust fallback is a native Calvin UCI port.

## Performance validation

The `uci` handshake reports `NNUE backend: ARM64 NEON` or a scalar fallback reason.
Do not compare NPS unless ARM64 NEON is confirmed. The `bench` command runs two
discarded warm-up passes and one measured pass with monotonic nanosecond timing.
Timing precision does not itself make the search faster; any later optimization
needs same-device benchmarks and evaluation/playing-strength parity tests.
