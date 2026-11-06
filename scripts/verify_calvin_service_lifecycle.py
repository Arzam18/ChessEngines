#!/usr/bin/env python3
"""Compatibility wrapper: Stage 2 service checks are superseded by Stage 3."""
from pathlib import Path
import runpy

script = Path(__file__).with_name("verify_calvin_standalone_launcher.py")
runpy.run_path(str(script), run_name="__main__")
