#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Run the SystemUI quick-switch observer and snapshot reader against a queued host resolver."""
import os
from pathlib import Path
import subprocess
import tempfile

here = Path(__file__).resolve().parent
root = here.parents[1]
lib = root / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook'
gesture = lib / 'rules/home/gesture'
shared = root / 'tests/home-layout-native/stubs'
source = (gesture / 'HomeQuickSwitchSystemUIOS4.java').read_text(encoding='utf-8')
fields = source[source.index('    private static final Uri ROOT='):source.index('    @Override protected boolean useDexKit()')]
# Only the observer/read lifecycle runs here; DexKit, platform Hook dispatch and WMS are not emulated.
methods = source[source.index('    private synchronized void install('):source.index('    private void complete(')]
fixture = (here / 'QuickSwitchPrefsTest.java').read_text(encoding='utf-8')
fixture = fixture.replace('/* PRODUCTION FIELDS */', fields).replace('/* PRODUCTION METHODS */', methods)
with tempfile.TemporaryDirectory(prefix='hc-quick-prefs-') as temp:
    out = Path(temp)
    test = out / 'QuickSwitchPrefsTest.java'
    test.write_text(fixture, encoding='utf-8')
    jdk = os.environ.get('JAVA_HOME')
    def java_tool(name):
        return str(Path(jdk) / 'bin' / name) if jdk else name

    files = [test, gesture / 'HomeQuickSwitchState.java', lib / 'provider/HomeGesturePrefsSnapshot.java']
    files += [shared / path for path in ('android/content/SharedPreferences.java', 'android/database/Cursor.java',
                                        'android/database/MatrixCursor.java', 'android/os/Bundle.java')]
    subprocess.run([java_tool('javac'), '-encoding', 'UTF-8', '-d', str(out), *map(str, files)], check=True)
    raise SystemExit(subprocess.run([java_tool('java'), '-cp', str(out),
        'com.sevtinge.hyperceiler.libhook.rules.home.gesture.QuickSwitchPrefsTest']).returncode)
