"""Compile the real HookBase processor and compare registry output across locales."""

import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'library/processor/src/main/java/com/hchen/database'


def main():
    java_bin = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path()
    javac = str(java_bin / 'javac')
    with tempfile.TemporaryDirectory(prefix='hc-hook-processor-') as temp:
        work = Path(temp)
        stub = work / 'AutoService.java'
        stub.write_text('package com.google.auto.service; public @interface AutoService { Class<?>[] value(); }',
                        encoding='utf-8')
        processor = work / 'processor'
        processor.mkdir()
        subprocess.run([javac, '--release', '25', '-d', str(processor), str(stub),
                        str(SOURCE / 'HookBase.java'), str(SOURCE / 'HookBaseProcessor.java')], check=True, timeout=30)
        hook = work / 'Fixture.java'
        hook.write_text('''
            package fixture;
            import com.hchen.database.HookBase;
            @HookBase(targetPackage = "system", minSdk = 36, maxSdk = 37, minOSVersion = 3.5F, deviceType = 2)
            public class Fixture {}
            @HookBase(targetPackage = "com.miui.home")
            class Defaults {}
        ''', encoding='utf-8')
        registries = []
        for language, country in [('en', 'US'), ('ar', 'EG')]:
            output = work / language
            output.mkdir()
            subprocess.run([javac, f'-J-Duser.language={language}', f'-J-Duser.country={country}', '--release', '25',
                            '-cp', str(processor), '-processorpath', str(processor),
                            '-processor', 'com.hchen.database.HookBaseProcessor',
                            '-s', str(output), '-d', str(output), str(hook)], check=True, timeout=30)
            registries.append((output / 'com/sevtinge/hyperceiler/libhook/base/DataBase.java').read_bytes())
        assert registries[0] == registries[1], 'generated registry depends on the build locale'
        assert b'dataMap.put("fixture.Fixture", new DataBase("system", 36, 37, 3.5F, -1.0F, 2));' in registries[0]
        assert b'dataMap.put("fixture.Defaults", new DataBase("com.miui.home", -1, -1, -1.0F, -1.0F, 0));' in registries[0]
    print('Hook registry: generated sources compile and match under en-US and ar-EG')


if __name__ == '__main__':
    main()
