"""Exercise production menu callbacks with recording host fixtures."""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from host_java_fixture import run_fixture

APP = 'com.sevtinge.hyperceiler'
LIB = APP + '.libhook'
COMMON = APP + '.common'
PREFS = COMMON + '.utils'
RULE = LIB + '.rules.systemframework.others'
HOOK = 'io.github.lingqiqi5211.ezhooktool.xposed'
ROOT = Path(__file__).resolve().parents[2]
RULE_DIR = Path('library/libhook/src/main/java') / RULE.replace('.', '/')
PROCESS_TEXT_BASELINE = '56d98887a'

STUBS = {
    'android.os.Handler': 'public class Handler {}',
    'android.os.BadParcelableException': 'public class BadParcelableException extends RuntimeException {}',
    'android.net.Uri': 'public class Uri {}',
    'android.content.pm.PackageManager': 'public class PackageManager {}',
    'android.content.pm.ResolveInfo': '''public class ResolveInfo {
        public static class ActivityInfo { public String packageName; }
        public final ActivityInfo activityInfo = new ActivityInfo();
        public ResolveInfo(String name) { activityInfo.packageName = name; }
    }''',
    'android.content.Context': '''public class Context {
        public static class Resolver { public String getType(android.net.Uri uri) { return "image/png"; } }
        public static class Resources {
            public int getIdentifier(String name, String type, String pkg) { return "app1".equals(name) ? 1 : 2; }
        }
        public Resolver getContentResolver() { return new Resolver(); }
        public Resources getResources() { return new Resources(); }
        public int packageManagerReads;
        public android.content.pm.PackageManager getPackageManager() {
            packageManagerReads++;
            return new android.content.pm.PackageManager();
        }
    }''',
    'android.content.Intent': '''public class Intent {
        public static final String ACTION_VIEW = "view", ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT";
        public final String action, scheme, type;
        public boolean bypass;
        public Intent(String action, String scheme, String type) { this.action = action; this.scheme = scheme; this.type = type; }
        @Override public Intent clone() {
            Intent copy = new Intent(action, scheme, type);
            copy.bypass = bypass;
            return copy;
        }
        public String getAction() { return action; }
        public boolean hasExtra(String key) { return "HyperCeiler".equals(key) && bypass; }
        public boolean getBooleanExtra(String key, boolean def) { return hasExtra(key) || def; }
        public String getScheme() { return scheme; }
        public String getType() { return type; }
        public android.net.Uri getData() { return new android.net.Uri(); }
    }''',
    'android.view.View': '''public class View {
        public final java.util.Map<Integer, View> children = new java.util.HashMap<>();
        public int clicks;
        public View findViewById(int id) { return children.get(id); }
        public boolean performClick() { clicks++; return true; }
    }''',
    COMMON + '.log.XposedLog': 'public class XposedLog { public static void e(String tag, String pkg, Throwable e) { throw new AssertionError(e); } }',
    PREFS + '.PrefsBridge': '''import java.util.*;
        public class PrefsBridge {
            public static final Map<String, Object> cache = new HashMap<>(), values = new HashMap<>();
            public static int intReads;
            private static String wrap(String key) { return key.startsWith("prefs_key_") ? key : "prefs_key_" + key; }
            private static Object get(String key, Object fallback) { key = wrap(key); return cache.getOrDefault(key, values.getOrDefault(key, fallback)); }
            public static int getInt(String key, int def) { intReads++; return (int) get(key, def); }
            @SuppressWarnings("unchecked") public static Set<String> getStringSet(String key) { return (Set<String>) get(key, Set.of()); }
            public static void removeHookCache(String key) { cache.remove(wrap(key)); }
            public static void putHookCache(String key, Object value) { cache.put(wrap(key), value); }
        }''',
    PREFS + '.prefs.PrefType': 'public enum PrefType { Any, StringSet, Integer }',
    PREFS + '.prefs.PrefsChangeObserver': '''import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
        public class PrefsChangeObserver {
            public static PrefsChangeObserver instance;
            private final boolean autoApply;
            private final PrefType type;
            private final String name;
            public PrefsChangeObserver(android.content.Context c, android.os.Handler h, PrefType t, String n, Object d) {
                this(c, h, false, t, n);
            }
            public PrefsChangeObserver(android.content.Context c, android.os.Handler h, boolean autoApply, String name) {
                this(c, h, autoApply, PrefType.StringSet, name);
            }
            private PrefsChangeObserver(android.content.Context c, android.os.Handler h, boolean autoApply, PrefType type, String name) {
                instance = this;
                this.autoApply = autoApply;
                this.type = type;
                this.name = name;
            }
            public void dispatchStringSetChange(String key) {
                if (type != PrefType.StringSet || !key.equals(name)) return;
                if (autoApply) {
                    PrefsBridge.removeHookCache(key);
                    PrefsBridge.putHookCache(key, PrefsBridge.getStringSet(key));
                }
                onChange(type, null, key, null);
            }
            public void onChange(PrefType type, android.net.Uri uri, String name, Object def) {}
        }''',
    LIB + '.utils.hookapi.tool.AppsTool': '''public class AppsTool {
        public static class MimeType {
            public static final int IMAGE = 1, AUDIO = 2, VIDEO = 4, DOCUMENT = 8, ARCHIVE = 16, LINK = 32, OTHERS = 64, ALL = 127;
        }
    }''',
    HOOK + '.common.HookParam': '''public class HookParam {
        private final java.util.Map<String, Object> fields;
        private final Object[] args;
        private Object result;
        public HookParam(java.util.Map<String, Object> fields, Object result, Object... args) { this.fields = fields; this.result = result; this.args = args; }
        public Object getThisObject() { return fields; }
        public Object[] getArgs() { return args; }
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
    }''',
    HOOK + '.java.IMethodHook': f'''public interface IMethodHook {{ void after({HOOK}.common.HookParam param); }}''',
    LIB + '.base.BaseHook': f'''import {HOOK}.java.IMethodHook;
        import java.util.*;
        public abstract class BaseHook {{
            protected static final String TAG = "test";
            public static final Map<String, IMethodHook> hooks = new HashMap<>();
            public static final Set<String> duals = new HashSet<>(), failingDuals = new HashSet<>();
            public static int dualQueries;
            public abstract void init();
            protected String getPackageName() {{ return "android"; }}
            protected static Class<?> findClassIfExists(String name) {{ return Object.class; }}
            protected static void findAndHookMethod(Class<?> clazz, String method, IMethodHook hook) {{ hooks.put(method, hook); }}
            protected static void hookAllMethods(String clazz, String method, IMethodHook hook) {{ hooks.put(method, hook); }}
            protected static void hookAllMethods(String clazz, String method, int minParameterCount, IMethodHook hook) {{
                if (minParameterCount != 6) throw new AssertionError(minParameterCount);
                hooks.put(method, hook);
            }}
            protected static void hookAllMethods(String clazz, ClassLoader loader, String method, IMethodHook hook) {{ hooks.put(method, hook); }}
            protected static Object getObjectField(Object obj, String field) {{ return ((Map<?, ?>) obj).get(field); }}
            protected static Object callMethod(Object obj, String method, String pkg, int flags, int user) {{
                dualQueries++;
                if (!"getPackageInfoAsUser".equals(method) || user != 999 || flags != 0) throw new AssertionError(method);
                if (failingDuals.contains(pkg)) throw new IllegalStateException("getPackageInfoAsUser failed");
                return duals.contains(pkg) ? new Object() : null;
            }}
        }}''',
}


def run_process_text():
    source = RULE_DIR / 'CleanProcessTextMenu.java'
    test = Path(__file__).with_name('CleanProcessTextMenuTest.java')
    main_class = RULE + '.CleanProcessTextMenuTest'
    with tempfile.TemporaryDirectory(prefix='hc-process-text-baseline-') as temp:
        baseline_source = Path(temp) / source.name
        baseline_source.write_bytes(subprocess.check_output(
            ['git', '--no-lazy-fetch', '-C', str(ROOT), 'show', f'{PROCESS_TEXT_BASELINE}:{source.as_posix()}'],
            timeout=15,
        ))
        print(f'CleanProcessTextMenu baseline {PROCESS_TEXT_BASELINE}', flush=True)
        run_fixture([baseline_source, test], STUBS, main_class + '$Baseline')
    print('CleanProcessTextMenu worktree', flush=True)
    run_fixture([ROOT / source, test], STUBS, main_class)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--process-text', action='store_true', help='compare baseline and worktree PROCESS_TEXT callbacks')
    if parser.parse_args().process_text:
        run_process_text()
    else:
        sources = [ROOT / RULE_DIR / 'CleanOpenMenu.java', Path(__file__).with_name('CleanOpenMenuTest.java')]
        run_fixture(sources, STUBS, RULE + '.CleanOpenMenuTest')


if __name__ == '__main__':
    main()
