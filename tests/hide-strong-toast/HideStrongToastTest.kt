// SPDX-License-Identifier: AGPL-3.0-or-later
package fixture

import android.os.Looper
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.island.HideStrongToast
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.Hooks
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class MIUIStrongToast(observer: ViewTreeObserver) : FrameLayout(observer) {
    private var attached = false
    var hostAttachments = 0
        private set

    fun onAttachedToWindow() {
        check(!attached)
        attached = true
        hostAttachments++
    }

    fun onDetachedFromWindow() {
        check(attached)
        attached = false
    }
}

private val attachMethod = MIUIStrongToast::class.java.getDeclaredMethod("onAttachedToWindow")

private fun attach(toast: MIUIStrongToast) {
    attachMethod.invoke(toast)
    Hooks.after[attachMethod]?.invoke(Hooks.Param(toast))
}

private fun verifyObserver(
    label: String,
    observer: ViewTreeObserver,
    registrations: Int,
    expectedResults: List<Boolean>,
    removals: Int = 0,
) {
    check(observer.registrations == registrations) { "$label: registrations=${observer.registrations}" }
    check(observer.removals == removals) { "$label: removals=${observer.removals}" }
    check(observer.listenerCount == expectedResults.size) { "$label: listeners=${observer.listenerCount}" }
    val results = observer.dispatchPreDraw()
    check(results == expectedResults) { "$label: pre-draw=$results" }
    println("$label: registrations=${observer.registrations}, removals=${observer.removals}, " +
        "listeners=${observer.listenerCount}, pre-draw=$results")
}

fun main() {
    val observer = ViewTreeObserver()
    val toast = MIUIStrongToast(observer)
    HideStrongToast.init()
    check(Hooks.after.keys == setOf(attachMethod))
    verifyObserver("before attachment", observer, 0, emptyList())
    repeat(3) { index ->
        if (index > 0) toast.onDetachedFromWindow()
        attach(toast)
        check(toast.hostAttachments == index + 1)
        verifyObserver("attachment ${index + 1}", observer, 1, listOf(false))
    }

    attach(MIUIStrongToast(observer))
    verifyObserver("another view sharing the observer", observer, 1, listOf(false))
    val otherObserver = ViewTreeObserver()
    attach(MIUIStrongToast(otherObserver))
    verifyObserver("independent observer", otherObserver, 1, listOf(false))

    val retirement = FutureTask { BaseHook.runHotReloadCleanups() }
    Thread(retirement, "fixture-hot-reload").start()
    val cleanup = checkNotNull(Looper.tasks.poll(2, TimeUnit.SECONDS)) { "Missing main-thread cleanup" }
    check(!retirement.isDone)
    verifyObserver("while main-thread cleanup is pending", observer, 1, listOf(true))
    cleanup.run()
    val cleanups = retirement.get(2, TimeUnit.SECONDS)
    check(cleanups == 1) { "Unexpected rule-owned hot-reload cleanups: $cleanups" }
    Hooks.after.clear()
    verifyObserver("after retirement", observer, 1, emptyList(), 1)
    verifyObserver("independent observer after retirement", otherObserver, 1, emptyList(), 1)

    toast.onDetachedFromWindow()
    attach(toast)
    check(toast.hostAttachments == 4)
    verifyObserver("host reattachment without hook", observer, 1, emptyList(), 1)
    println("PASS observer registrations and simulated retirement; Android runtime NOT_TESTED")
}
