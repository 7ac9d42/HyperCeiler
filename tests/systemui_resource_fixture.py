# SPDX-License-Identifier: AGPL-3.0-or-later
"""Run a SystemUI resource rule through the production package entry on a local JVM."""

import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from host_java_fixture import run_fixture
from hyperceiler_test_paths import SOURCE_ROOT

LIB = 'com.sevtinge.hyperceiler.libhook'
MAIN = Path('library/libhook/src/main')
JAVA = MAIN / 'java' / LIB.replace('.', '/')
ROUTE = JAVA / 'app/SystemUI/SystemUIB.java'

STUBS = {
    'com.hchen.database.HookBase': 'public @interface HookBase { String targetPackage(); int minSdk(); }',
    'com.sevtinge.hyperceiler.common.BuildConfig': '''public final class BuildConfig {
        public static final boolean STATUS_BAR_FOLLOW_AVAILABLE = false;
    }''',
    'com.sevtinge.hyperceiler.common.utils.PrefsBridge': '''import java.util.ArrayList;
        import java.util.List;
        import java.util.Map;
        import java.util.Set;
        public final class PrefsBridge {
            public static Map<String, Boolean> flags = Map.of();
            public static final List<String> reads = new ArrayList<>();
            public static boolean getBoolean(String key) { return getBoolean(key, false); }
            public static boolean getBoolean(String key, boolean fallback) {
                reads.add(key);
                return flags.getOrDefault(key, fallback);
            }
            public static int getInt(String key, int fallback) { return fallback; }
            public static int getStringAsInt(String key, int fallback) { return fallback; }
            public static String getString(String key, String fallback) { return fallback; }
            public static Set<String> getStringSet(String key) { return Set.of(); }
        }''',
    LIB + '.utils.api.DeviceHelper': '''public final class DeviceHelper {
        public static final class Miui { public static boolean isPad() { return false; } }
        public static final class System {
            public static boolean isHyperOSVersion(float version) { return version == 4f; }
            public static boolean isMoreHyperOSVersion(float version) { return version <= 4f; }
        }
    }''',
    LIB + '.utils.hookapi.systemui.MiuiStub': 'public final class MiuiStub { public static void createHook() {} }',
    LIB + '.base.BaseLoad': '''public abstract class BaseLoad {
        public abstract void onPackageLoaded();
        protected void initHook(BaseHook hook) { hook.init(); }
        protected void initHook(BaseHook hook, boolean enabled) { if (enabled) hook.init(); }
    }''',
    LIB + '.base.BaseHook': '''import java.util.ArrayList;
        import java.util.List;
        public abstract class BaseHook {
            public record Replacement(String pkg, String type, String name, int id) {}
            public static final List<Replacement> replacements = new ArrayList<>();
            public abstract void init();
            public static void setResReplacement(String pkg, String type, String name, int id) {
                replacements.add(new Replacement(pkg, type, name, id));
            }
        }''',
}


def systemui_stubs(route, production_rules):
    stubs = dict(STUBS)
    peers = set(re.findall(r'^import (' + re.escape(LIB) + r'\.(?:rules|appbase)\.[\w.]+);', route, re.M))
    peers.update(re.findall(r'\bnew (' + re.escape(LIB) + r'\.rules\.[\w.]+)\(', route))
    for qualified_name in sorted(peers - production_rules):
        name = qualified_name.rsplit('.', 1)[1]
        stubs[qualified_name] = f'''public final class {name} extends {LIB}.base.BaseHook {{
            public static final {name} INSTANCE = new {name}();
            public void init() {{}}
        }}'''
    return stubs


def fixture_stubs(route, rule_name, res_type, resources):
    stubs = systemui_stubs(route, {rule_name})
    names = sorted({element.attrib['name'] for element in ET.fromstring(resources).findall(res_type)})
    fields = '\n'.join(f'public static final int {name} = {index};' for index, name in enumerate(names, 1))
    stubs[LIB + '.R'] = f'public final class R {{ public static final class {res_type} {{\n{fields}\n}} }}'
    return stubs


def run_resource_fixture(description, baseline_commit, rule, res_type, resource_file, test):
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument('--baseline', action='store_true',
                        help=f'compile the unchanged inputs from local commit {baseline_commit}')
    baseline = parser.parse_args().baseline

    def source(path):
        if baseline:
            return subprocess.check_output(['git', '--no-lazy-fetch', 'show', f'{baseline_commit}:{path.as_posix()}'],
                                           cwd=SOURCE_ROOT, timeout=15)
        return (SOURCE_ROOT / path).read_bytes()

    print('Production input:', baseline_commit if baseline else 'working tree', flush=True)
    route = source(ROUTE)
    rule_name = LIB + '.' + '.'.join(rule.relative_to(JAVA).with_suffix('').parts)
    stubs = fixture_stubs(route.decode('utf-8'), rule_name, res_type, source(resource_file))
    with tempfile.TemporaryDirectory(prefix='hc-systemui-resource-') as temp:
        work = Path(temp)
        (work / ROUTE.name).write_bytes(route)
        (work / rule.name).write_bytes(source(rule))
        run_fixture([work / ROUTE.name, work / rule.name, test], stubs, test.stem)
