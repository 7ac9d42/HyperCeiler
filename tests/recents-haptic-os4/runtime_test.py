#!/usr/bin/env python3
"""Run production runtime; only NDK transport is replaced on non-Android hosts."""
from pathlib import Path
import os,subprocess,tempfile,sys
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
linux=sys.platform.startswith('linux')
with tempfile.TemporaryDirectory(prefix='hc-haptic-worker-') as work:
    binary=str(Path(work)/'runtime')
    fault_flags=['-DHC_TEST_PIPE_FAULTS','-Wl,--wrap=read','-Wl,--wrap=write'] if linux else []
    subprocess.run([os.environ.get('CXX','c++'),'-std=c++20','-O1','-g','-Wall','-Wextra','-Werror',
        '-Wno-misleading-indentation','-fdiagnostics-color=never','-pthread',
        '-fsanitize=address,undefined','-fno-omit-frame-pointer',
        '-Wl,-export_dynamic' if sys.platform=='darwin' else '-Wl,--export-dynamic',
        *fault_flags,'-I'+str(HERE/'runtime-stubs'),'-I'+str(ROOT/'app/src/main/cpp'),
        str(HERE/'runtime_test.cpp'),str(ROOT/'app/src/main/cpp/targets/home/home_recents_haptic_runtime.cpp'),'-o',binary],check=True,timeout=90)
    for scenario in sys.argv[1:] or (['both','scroll','close','off','v1']+(['read-failure'] if linux else [])):
        subprocess.run([binary,scenario],check=True,timeout=12)
