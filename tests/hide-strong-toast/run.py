# SPDX-License-Identifier: AGPL-3.0-or-later
"""Exercise the production HideStrongToast attachment and pre-draw callbacks on a local JVM."""

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
RULE = LIB + '.rules.systemui.statusbar.island'
EZ = 'io.github.lingqiqi5211.ezhooktool'

STUBS = {
    'android.os': '''import java.util.concurrent.LinkedBlockingQueue
        class Looper {
            companion object {
                private val mainThread = Thread.currentThread()
                private val main = Looper()
                val tasks = LinkedBlockingQueue<Runnable>()
                fun getMainLooper() = main
                fun myLooper() = if (Thread.currentThread() === mainThread) main else null
            }
        }
        class Handler(looper: Looper) {
            fun post(action: Runnable): Boolean = Looper.tasks.offer(action)
        }''',
    'android.view': '''import android.os.Looper
        class ViewTreeObserver {
            fun interface OnPreDrawListener { fun onPreDraw(): Boolean }
            private val listeners = mutableListOf<OnPreDrawListener>()
            var registrations = 0
                private set
            var removals = 0
                private set
            val listenerCount get() = listeners.size
            val isAlive = true

            fun addOnPreDrawListener(listener: OnPreDrawListener) {
                check(Looper.myLooper() == Looper.getMainLooper())
                registrations++
                listeners.add(listener)
            }
            fun removeOnPreDrawListener(listener: OnPreDrawListener) {
                check(Looper.myLooper() == Looper.getMainLooper())
                removals++
                check(listeners.remove(listener))
            }
            fun dispatchPreDraw() = listeners.toList().map { it.onPreDraw() }
        }''',
    'android.widget': '''import android.view.ViewTreeObserver
        open class FrameLayout(val viewTreeObserver: ViewTreeObserver)''',
    LIB + '.base': '''abstract class BaseHook {
            abstract fun init()
            companion object {
                private val cleanups = ArrayDeque<Runnable>()
                fun registerHotReloadCleanup(cleanup: Runnable) { cleanups.addLast(cleanup) }
                fun runHotReloadCleanups(): Int {
                    val count = cleanups.size
                    while (cleanups.isNotEmpty()) cleanups.removeLast().run()
                    return count
                }
            }
        }''',
    LIB + '.utils.hookapi': '''import fixture.MIUIStrongToast
        object LazyClass { val NewStrongToast: Class<*>? = MIUIStrongToast::class.java }''',
    EZ + '.core': '''class Finder {
            lateinit var methodName: String
            fun name(value: String) { methodName = value }
        }
        fun Class<*>.findMethod(select: Finder.() -> Unit) =
            getDeclaredMethod(Finder().apply(select).methodName)''',
    EZ + '.xposed.dsl': '''import java.lang.reflect.Method
        object Hooks {
            data class Param(val thisObject: Any)
            val after = linkedMapOf<Method, (Param) -> Unit>()
        }
        fun Method.createAfterHook(callback: (Hooks.Param) -> Unit) {
            check(Hooks.after.putIfAbsent(this, callback) == null)
        }''',
}


def cached_jar(cache, group, artifact, version):
    matches = list((cache / group / artifact / version).glob(f'*/{artifact}-{version}.jar'))
    if len(matches) != 1:
        raise SystemExit(f'Expected one cached {group}:{artifact}:{version}; check GRADLE_USER_HOME')
    return matches[0]


def main():
    cache = Path(os.environ.get('GRADLE_USER_HOME') or Path.home() / '.gradle') / 'caches/modules-2/files-2.1'
    kotlin = 'org.jetbrains.kotlin'
    version = '2.4.10'
    stdlib = cached_jar(cache, kotlin, 'kotlin-stdlib', version)
    compiler = [cached_jar(cache, group, artifact, ver) for group, artifact, ver in (
        (kotlin, 'kotlin-compiler-embeddable', version),
        (kotlin, 'kotlin-reflect', '2.4.0'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
        ('org.jetbrains', 'annotations', '13.0'),
    )] + [stdlib]
    java = java_tool('java')
    if not java.is_file():
        raise SystemExit(f'Missing local tool: {java}; see tests/PORTABILITY.md')

    source = Path('library/libhook/src/main/java') / RULE.replace('.', '/') / 'HideStrongToast.kt'
    with tempfile.TemporaryDirectory(prefix='hc-hide-strong-toast-') as temp:
        work = Path(temp)
        sources = [SOURCE_ROOT / source, HERE / 'HideStrongToastTest.kt']
        for index, (pkg, body) in enumerate(STUBS.items()):
            stub = work / f'Stub{index}.kt'
            stub.write_text(f'package {pkg}\n{body}\n', encoding='utf-8')
            sources.append(stub)
        classes = work / 'classes'
        subprocess.run([str(java), '-cp', os.pathsep.join(map(str, compiler)),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', str(stdlib), '-d', str(classes), *map(str, sources)], check=True, timeout=75)
        subprocess.run([str(java), '-cp', os.pathsep.join(map(str, (classes, stdlib))),
                        'fixture.HideStrongToastTestKt'], check=True, timeout=20)


if __name__ == '__main__':
    main()
