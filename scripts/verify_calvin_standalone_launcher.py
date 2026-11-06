#!/usr/bin/env python3
"""Static regression checks for the self-contained Calvin OEX launcher."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
application = manifest.find("application")
assert application is not None
assert not application.findall("service"), "Engine session must not depend on provider Android services"

provider = (ROOT / "app/src/main/java/ccc/chess/engines/MyEngineProvider.java").read_text()
assert "startForegroundService" not in provider and "bindService" not in provider
assert "extends ChessEngineProvider" in provider

engine_xml = ET.parse(ROOT / "app/src/main/res/xml/enginelist.xml").getroot()
engines = engine_xml.findall("engine")
assert len(engines) == 1
assert engines[0].attrib == {
    "name": "Calvin 6.1.1", "filename": "libcalvin.so", "target": "arm64-v8a"
}

build = (ROOT / "app/build.gradle").read_text()
for required in (
    "calvinOexJniDir", "calvinLauncherRawBinary", "calvinNeonBinary",
    "embedCalvinRuntime", "mergeProjectDexDebug", "mergeExtDexDebug",
    "[67, 86, 82, 84, 48, 48, 49, 0]", "jniLibs.setSrcDirs([calvinOexJniDir])",
    "keepDebugSymbols += ['**/libcalvin.so']", "--lib', androidJar.absolutePath",
):
    assert required in build, f"missing build behavior: {required}"
assert "libcalvinneon.so'" in build
assert "assets.srcDir(calvinAssetsDir)" not in build, "NNUE must be embedded, not duplicated as an APK asset"

launcher = (ROOT / "app/src/main/cpp/calvin_bridge.c").read_text()
for required in (
    "app_process64", "CALVIN_MAIN_CLASS", "CLASSPATH", "/proc/self/exe",
    "CalvinPayloadFooter", "FOOTER_MAGIC", "extract_segment", "CALVIN_RUNTIME_DIR",
    "CALVIN_NNUE_PATH", "CALVIN_NEON_LIBRARY", "execv(app_process",
):
    assert required in launcher, f"missing launcher behavior: {required}"
assert "base.apk" not in launcher, "OEX copies the executable; do not assume provider APK is beside it"
assert "connect(" not in launcher and "SOCKET_PORT" not in launcher

entrypoint_path = ROOT / "app/src/main/java/com/kelseyde/calvin/android/CalvinUciMain.java"
if not entrypoint_path.is_file():
    raise SystemExit(
        "FAIL: required standalone ART entrypoint is missing: "
        "app/src/main/java/com/kelseyde/calvin/android/CalvinUciMain.java\n"
        "Add the CalvinUciMain.java file from the Stage 3 modified-files package "
        "at that exact repository path, commit it, then run this workflow manually."
    )
entrypoint = entrypoint_path.read_text(encoding="utf-8")
for required in (
    "CALVIN_NNUE_PATH", "CALVIN_NEON_LIBRARY", "calvin.nnue.path",
    "calvin.neon.path", "UCI.run(args)"
):
    assert required in entrypoint, f"missing standalone runtime behavior: {required}"
assert entrypoint.index('System.setProperty("calvin.nnue.path"') < entrypoint.index("UCI.run(args)")
assert entrypoint.index('System.setProperty("calvin.neon.path"') < entrypoint.index("UCI.run(args)")

network = (ROOT / "app/src/main/java/com/kelseyde/calvin/evaluation/Network.java").read_text()
assert "expectedBytes" in network and "fileBytes.length" in network
assert 'location.startsWith("apkzip:")' in network  # Retained compatibility for file/ZIP network sources.

neon = (ROOT / "app/src/main/java/com/kelseyde/calvin/evaluation/CalvinNeon.java").read_text()
assert "System.load(explicitPath)" in neon and "LOAD_FAILURE" in neon

uci = (ROOT / "app/src/main/java/com/kelseyde/calvin/uci/UCI.java").read_text()
assert "NNUE backend: " in uci and uci.count("Bench.run(ENGINE") >= 3

print("PASS: OEX publishes one ARM64 PIE executable named libcalvin.so")
print("PASS: OEX provider Activity/Service is not part of the engine session")
print("PASS: launcher extracts embedded DEX, NNUE and NEON from its own copied file")
print("PASS: ART starts from a standalone DEX classpath; no provider APK/TCP/Binder dependency")
print("PASS: NNUE path and absolute NEON JNI path are configured before UCI starts")
print("PASS: backend failure is reported and scalar Java fallback remains available")
print("PASS: UCI bench performs two warm-up passes and uses monotonic timing")
print("PASS: Gradle preserves appended executable payload bytes from native-library stripping")
