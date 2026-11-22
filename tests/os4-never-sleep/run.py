#!/usr/bin/env python3
"""Run the OS4 rule against a reviewed ROM-method host model."""
import subprocess
import sys
import tempfile
from pathlib import Path

TEST_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TEST_DIR.parent))
import hyperceiler_test_paths as test_paths

RULE = test_paths.SOURCE_ROOT / (
    "library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/"
    "rules/systemsettings/UnlockNeverSleepScreenOS4.java"
)
with tempfile.TemporaryDirectory(prefix="hc-never-sleep-") as tmp:
    fixtures = sorted((TEST_DIR / "fixtures").rglob("*.java"))
    subprocess.run([
        str(test_paths.java_tool("javac")), "-encoding", "UTF-8", "-d", tmp,
        str(RULE), *map(str, fixtures),
    ], check=True)
    raise SystemExit(subprocess.run([
        str(test_paths.java_tool("java")), "-cp", tmp, "TestMain",
    ]).returncode)
