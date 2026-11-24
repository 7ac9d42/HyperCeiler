# SPDX-License-Identifier: AGPL-3.0-or-later
"""Compile production Java with small host adapters in a disposable directory."""

import subprocess
import tempfile
from pathlib import Path

from hyperceiler_test_paths import java_tool


def run_fixture(sources: list[Path], stubs: dict[str, str], main_class: str) -> None:
    with tempfile.TemporaryDirectory(prefix='hc-java-fixture-') as temp:
        output = Path(temp)
        stub_sources = []
        for name, body in stubs.items():
            path = output / (name.replace('.', '/') + '.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package ' + name.rsplit('.', 1)[0] + ';\n' + body, encoding='utf-8')
            stub_sources.append(path)
        subprocess.run([str(java_tool('javac')), '-encoding', 'UTF-8', '-d', str(output),
                        *map(str, sources), *map(str, stub_sources)], check=True, timeout=30)
        subprocess.run([str(java_tool('java')), '-cp', str(output), main_class], check=True, timeout=30)
