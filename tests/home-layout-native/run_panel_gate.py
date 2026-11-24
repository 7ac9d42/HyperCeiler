#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Run the layout worker's real panel gate with a fake clock and transport."""
import os
import re
import subprocess
import tempfile
from pathlib import Path

here = Path(__file__).resolve().parent
hooks = here.parents[1] / 'app/src/main/cpp/targets/home/home_layout_hooks.cpp'


def main():
    source = hooks.read_text(encoding='utf-8')
    interval = source.index('bool interval_due(')
    query = source.index(' * Ask whether the panel')
    worker = source.index('    int iterations = 0;', source.index('    setup_lease.release();'))
    limit = source.index('        // A late libapp', worker)
    config = re.search(r'^        if \(([^\n]+)\) \{\n            // Rust may be mapped before Dart\.',
                       source[limit:], re.M).group(1)
    constants = re.findall(r'^constexpr uint64_t k(?:PassInterval|PanelQueryInterval|DozingIteration)Ms = \d+;',
                           source, re.M)
    assert len(constants) == 3
    with tempfile.TemporaryDirectory(prefix='hc-layout-panel-') as directory:
        output = Path(directory)
        (output / 'panel_gate.inc').write_text('\n'.join([
            *constants,
            source[interval:source.index('\n}', interval) + 2],
            source[source.rindex('/*', 0, query):source.index('\nvoid *attempt_finished()', query)],
            'void run_passes() {',
            source[worker:limit],
            '++iterations;',
            f'on_maintenance({config});',
            '}', '}',
        ]), encoding='utf-8')
        for with_dock in (True, False):
            binary = output / 'panel-gate'
            defines = ['-DHYPERCEILER_DOCK_NATIVE_MOTION'] if with_dock else []
            subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++20', '-O1', *defines,
                            '-I' + str(output), here / 'panel_gate_test.cpp', '-o', binary],
                           check=True, timeout=60)
            subprocess.run([binary], check=True, timeout=10)


if __name__ == '__main__':
    main()
