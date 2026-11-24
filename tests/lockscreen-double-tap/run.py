# SPDX-License-Identifier: AGPL-3.0-or-later
"""Run the production lockscreen touch callback with local JVM recording stubs."""

import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hyperceiler_test_paths import SOURCE_ROOT, java_tool

HERE = Path(__file__).resolve().parent
LIB = 'com.sevtinge.hyperceiler.libhook'
RULE = LIB + '.rules.systemui.lockscreen'
EZ = 'io.github.lingqiqi5211.ezhooktool'

STUBS = {
    'android.app': '''class KeyguardManager(val isKeyguardLocked: Boolean)''',
    'android.content': '''import android.app.KeyguardManager
    class Context(private val locked: Boolean = true) {
        companion object {
            const val KEYGUARD_SERVICE = "keyguard"
            const val POWER_SERVICE = "power"
        }
        var keyguardReads = 0
        val sleepRequests = mutableListOf<Long>()
        fun getSystemService(name: String): Any = when (name) {
            KEYGUARD_SERVICE -> KeyguardManager(locked).also { keyguardReads++ }
            POWER_SERVICE -> this
            else -> error("Unexpected service: $name")
        }
    }''',
    'android.os': '''object SystemClock {
        fun uptimeMillis() = 1_000L
    }''',
    'android.view': '''import android.content.Context
    class MotionEvent(val action: Int, val x: Float, val y: Float) {
        companion object { const val ACTION_DOWN = 0 }
    }
    class View(val context: Context) {
        fun interface OnTouchListener { fun onTouch(view: View, event: MotionEvent): Boolean }
        private var listener: OnTouchListener? = null
        var clicks = 0
            private set
        fun setOnTouchListener(value: OnTouchListener?) { listener = value }
        fun dispatch(event: MotionEvent) = checkNotNull(listener).onTouch(this, event)
        fun performClick(): Boolean { clicks++; return true }
    }''',
    # Shadow only the wall clock; the production rule source is compiled unchanged.
    RULE: '''object System {
        var now = 0L
        fun currentTimeMillis() = now
    }''',
    LIB + '.base': '''import android.content.Context
    abstract class BaseHook {
        abstract fun init()
        fun <T : Any> getHotReloadRuntimeState(key: String, type: Class<T>): T? = null
        fun putHotReloadRuntimeState(key: String, value: Any) {}
        fun registerHotReloadCleanup(cleanup: () -> Unit) {}
        fun callMethod(target: Any, name: String, time: Long) {
            check(name == "goToSleep")
            (target as Context).sleepRequests.add(time)
        }
    }''',
    EZ + '.core': '''data class Target(val owner: String, var method: String = "") {
        fun name(value: String) { method = value }
    }
    fun loadClass(owner: String) = Target(owner)
    fun Target.findMethod(selector: Target.() -> Unit) = apply(selector)''',
    EZ + '.xposed.dsl': '''import java.util.IdentityHashMap
    import #EZ#.core.Target
    object InflateHook {
        data class Param(val thisObject: Any)
        lateinit var callback: (Param) -> Unit
        fun before(value: (Param) -> Unit) { callback = value }
    }
    fun Target.createHook(configure: InflateHook.() -> Unit) {
        check(owner == "com.android.systemui.shade.NotificationsQuickSettingsContainer" && method == "onFinishInflate")
        InflateHook.configure()
    }
    object Fields {
        private val values = IdentityHashMap<Any, MutableMap<String, Any>>()
        val reads = mutableListOf<Any>()
        val writes = mutableListOf<Any>()
        fun peek(owner: Any, key: String): Any? = values[owner]?.get(key)
        fun read(owner: Any, key: String): Any? { reads.add(owner); return peek(owner, key) }
        fun write(owner: Any, key: String, value: Any) {
            writes.add(owner)
            values.getOrPut(owner) { mutableMapOf() }[key] = value
        }
    }
    fun Any.getAdditionalInstanceField(key: String) = Fields.read(this, key)
    fun Any.setAdditionalInstanceField(key: String, value: Any) = Fields.write(this, key, value)'''.replace('#EZ#', EZ),
}


def cached_jar(cache, group, artifact, version):
    matches = list((cache / group / artifact / version).glob(f'*/{artifact}-{version}.jar'))
    if len(matches) != 1:
        raise SystemExit(f'Expected one cached {group}:{artifact}:{version}; check GRADLE_USER_HOME')
    return matches[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', action='store_true', help='run the pre-change rule from local commit aea82c4cc')
    baseline = parser.parse_args().baseline
    cache = Path(os.environ.get('GRADLE_USER_HOME') or Path.home() / '.gradle') / 'caches/modules-2/files-2.1'
    kotlin = 'org.jetbrains.kotlin'
    version = '2.4.10'
    stdlib = cached_jar(cache, kotlin, 'kotlin-stdlib', version)
    compiler = [cached_jar(cache, group, name, ver) for group, name, ver in (
        (kotlin, 'kotlin-compiler-embeddable', version),
        (kotlin, 'kotlin-reflect', '2.4.0'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
        ('org.jetbrains', 'annotations', '13.0'),
    )] + [stdlib]
    java = java_tool('java')
    if not java.is_file():
        raise SystemExit(f'Missing local tool: {java}; see tests/PORTABILITY.md')

    source = Path('library/libhook/src/main/java') / RULE.replace('.', '/') / 'LockScreenDoubleTapToSleep.kt'
    with tempfile.TemporaryDirectory(prefix='hc-lockscreen-double-tap-') as temp:
        work = Path(temp)
        actual = SOURCE_ROOT / source
        if baseline:
            actual = work / source.name
            actual.write_bytes(subprocess.check_output(
                ['git', '--no-lazy-fetch', 'show', f'aea82c4cc:{source.as_posix()}'], cwd=SOURCE_ROOT, timeout=15,
            ))
        print('Production input:', 'aea82c4cc' if baseline else 'working tree', flush=True)
        sources = [actual, HERE / 'LockScreenDoubleTapTest.kt']
        for index, (pkg, body) in enumerate(STUBS.items()):
            stub = work / f'Stub{index}.kt'
            stub.write_text(f'package {pkg}\n{body}\n', encoding='utf-8')
            sources.append(stub)
        classes = work / 'classes'
        subprocess.run([str(java), '-cp', os.pathsep.join(map(str, compiler)),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', str(stdlib), '-d', str(classes), *map(str, sources)], check=True, timeout=75)
        subprocess.run([str(java), '-cp', os.pathsep.join(map(str, (classes, stdlib))),
                        'LockScreenDoubleTapTestKt', *(['baseline'] if baseline else [])], check=True, timeout=20)


if __name__ == '__main__':
    main()
