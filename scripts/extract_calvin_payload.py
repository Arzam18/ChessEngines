#!/usr/bin/env python3
"""Verify and extract the three runtime segments appended to libcalvin.so."""
from __future__ import annotations
import argparse
from pathlib import Path
import struct

MAGIC = b"CVRT001\0"
FOOTER = struct.Struct("<8sQQQ")
NNUE_MIN_BYTES = 12_589_058

parser = argparse.ArgumentParser()
parser.add_argument("--input", required=True, type=Path)
parser.add_argument("--output-dir", required=True, type=Path)
args = parser.parse_args()

data = args.input.read_bytes()
if len(data) < FOOTER.size + 64 or data[:4] != b"\x7fELF":
    raise SystemExit("ERROR: input is not a valid ELF launcher with an embedded payload")
magic, dex_len, nnue_len, neon_len = FOOTER.unpack_from(data, len(data) - FOOTER.size)
if magic != MAGIC:
    raise SystemExit("ERROR: Calvin runtime footer is missing or corrupt")
if not (0 < dex_len <= 64 * 1024 * 1024):
    raise SystemExit(f"ERROR: invalid DEX length: {dex_len}")
if not (NNUE_MIN_BYTES <= nnue_len <= 64 * 1024 * 1024):
    raise SystemExit(f"ERROR: invalid NNUE length: {nnue_len}")
if not (0 < neon_len <= 16 * 1024 * 1024):
    raise SystemExit(f"ERROR: invalid NEON library length: {neon_len}")
payload_len = dex_len + nnue_len + neon_len
payload_start = len(data) - FOOTER.size - payload_len
if payload_start < 64:
    raise SystemExit("ERROR: embedded payload overlaps the ELF header")
dex_start = payload_start
nnue_start = dex_start + dex_len
neon_start = nnue_start + nnue_len
dex = data[dex_start:nnue_start]
nnue = data[nnue_start:neon_start]
neon = data[neon_start:neon_start + neon_len]
if not dex.startswith(b"dex\n"):
    raise SystemExit("ERROR: embedded standalone DEX has an invalid header")
if b"com/kelseyde/calvin/android/CalvinUciMain" not in dex:
    raise SystemExit("ERROR: standalone DEX does not contain CalvinUciMain")
if not neon.startswith(b"\x7fELF"):
    raise SystemExit("ERROR: embedded NEON accelerator is not an ELF shared library")
if len(nnue) != nnue_len:
    raise SystemExit("ERROR: NNUE segment is truncated")
args.output_dir.mkdir(parents=True, exist_ok=True)
(args.output_dir / "classes.dex").write_bytes(dex)
(args.output_dir / "calvin1024_8b.nnue").write_bytes(nnue)
(args.output_dir / "libcalvinneon.so").write_bytes(neon)
print(f"PASS: DEX {len(dex):,} bytes; NNUE {len(nnue):,} bytes; NEON {len(neon):,} bytes")
