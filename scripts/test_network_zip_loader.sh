#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src/com/kelseyde/calvin/evaluation/activation" "$TMP/classes" "$TMP/apk/assets"
cat > "$TMP/src/com/kelseyde/calvin/evaluation/activation/Activation.java" <<'JAVA'
package com.kelseyde.calvin.evaluation.activation;
public enum Activation { SCReLU, CReLU }
JAVA
python3 - "$TMP/apk/base.apk" <<'PY'
import struct, sys, zipfile
payload = b"".join(struct.pack("<h", i) for i in range(1, 14))
# The production NNUE asset has a small trailing trailer; verify that it is ignored.
payload += b"TRAILING-METADATA"
with zipfile.ZipFile(sys.argv[1], "w", compression=zipfile.ZIP_STORED) as z:
    z.writestr("assets/mock.nnue", payload)
PY
javac --release 17 -d "$TMP/classes" \
  "$TMP/src/com/kelseyde/calvin/evaluation/activation/Activation.java" \
  "$ROOT/app/src/main/java/com/kelseyde/calvin/evaluation/Network.java" \
  "$ROOT/scripts/tests/NetworkZipLoaderTest.java"
java -cp "$TMP/classes" com.kelseyde.calvin.evaluation.NetworkZipLoaderTest "$TMP/apk/base.apk"
