#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Exercise the real Dock startup functions with fault-injected thread creation."""
import os
import subprocess
import tempfile
from pathlib import Path


def body(source, signature):
    start = source.index(signature + ' {')
    return source[start:source.index('\n}', start) + 2]


def main():
    here = Path(__file__).resolve().parent
    home = here.parents[1] / 'app/src/main/cpp/targets/home'
    hooks = (home / 'dock_native_hooks.cpp').read_text(encoding='utf-8')
    motion = (home / 'dock_native_motion.cpp').read_text(encoding='utf-8')
    with tempfile.TemporaryDirectory(prefix='hc-dock-health-start-') as directory:
        out = Path(directory)
        (out / 'dock_start.inc').write_text('\n\n'.join([
            body(motion, 'bool prepare_dock_motion()'),
            body(hooks, 'void *motion_worker_impl()'),
            body(hooks, 'void *motion_worker(void *)'),
            body(hooks, 'void start_dock_native_motion(Hook hook, Unhook unhook)'),
        ]), encoding='utf-8')
        binary = out / 'dock-health-start'
        subprocess.run([os.environ.get('CXX', 'c++'), '-std=c++20', '-pthread', '-Wall', '-Wextra',
                        '-Werror', '-I' + str(out), here / 'DockHealthStartTest.cpp', '-o', binary],
                       check=True, timeout=60)
        subprocess.run([binary], check=True, timeout=10)


if __name__ == '__main__':
    main()
