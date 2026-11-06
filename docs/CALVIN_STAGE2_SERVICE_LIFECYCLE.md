# Stage 2 service lifecycle — superseded

This document describes a historical approach in which a UCI bridge connected to a Calvin Java foreground service inside the Chess Engines provider package.

That design was superseded in Stage 3. The Stage 3 OEX executable now starts the Java runtime in the GUI-launched child process and does not use this foreground service, Binder, or loopback TCP relay. The old service source files remain in the repository as historical reference only and are no longer declared in `AndroidManifest.xml`.

See `docs/CALVIN_STAGE3_STANDALONE_LAUNCHER.md` for the current architecture and its Android compatibility caveat.
