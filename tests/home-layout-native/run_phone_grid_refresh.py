#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Check worker publication and the tweaks plan/rollback using synthetic mapped words."""
import os
import subprocess
import tempfile
from pathlib import Path

here = Path(__file__).resolve().parent
cpp = here.parents[1] / 'app/src/main/cpp'
home = cpp / 'targets/home'
tweaks = home / 'tweaks'


def body(source, signature):
    start = source.index(signature + ' {')
    return source[start:source.index('\n}', start) + 2]


def main():
    hooks = (home / 'home_layout_hooks.cpp').read_text(encoding='utf-8')
    entry = (tweaks / 'entry.cpp').read_text(encoding='utf-8')
    start = hooks.index('                const bool cell_changed = latest.cell_x')
    end = hooks.index('                (void) sync_widget_move', start)
    worker = ('void refresh(home_layout::Config &config, const home_layout::Config &latest) {\n'
              + hooks[start:end] + '}')
    start = entry.index('struct AppliedPatch {')
    end = entry.index('\nState g_state;', start)
    slots = entry[entry.index('struct FeatureSlot {'):entry.index('uint32_t MaskOf(')]
    with tempfile.TemporaryDirectory(prefix='hc-phone-grid-refresh-') as directory:
        out = Path(directory)
        files = {
            'state.inc': entry[start:end],
            'engine.inc': slots + '\n\n'.join(body(entry, signature) for signature in (
                'uint32_t MaskOf(const LocatedSites& sites)',
                'bool RevertApplied(State* state)', 'long DecodeMovzSmi(uint32_t word)',
                'PatchStats ApplyPatches(State* state, const std::vector<PlanPatch>& patches)',
                'void SettleLocked(State* state, bool allowScan)',
                'void PushLayoutConfig(const Config& config)',
            )),
            'worker.inc': '\n\n'.join((
                body(hooks, 'home_layout_engine::Config make_layout_engine_config(const home_layout::Config &values)'),
                body(hooks, 'void push_tweaks(const home_layout::Config &values)'), worker,
            )),
        }
        for name, text in files.items():
            (out / name).write_text(text, encoding='utf-8')
        executable = out / 'phone-grid-refresh'
        subprocess.run([
            os.environ.get('CXX', 'c++'), '-std=c++20', '-O1', '-ffunction-sections',
            '-fdata-sections', '-Wl,--gc-sections', '-fsanitize=undefined', '-fno-sanitize-recover=all',
            '-I' + str(cpp), '-I' + str(out), '-I' + str(here / 'stubs'),
            here / 'phone_grid_refresh_test.cpp', tweaks / 'ht_plan.cpp', tweaks / 'scanner.cpp',
            tweaks / 'blob.cpp', '-o', executable,
        ], check=True, timeout=90)
        subprocess.run([executable], check=True, timeout=15)


if __name__ == '__main__':
    main()
