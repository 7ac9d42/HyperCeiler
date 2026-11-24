// SPDX-License-Identifier: AGPL-3.0-or-later

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.sevtinge.hyperceiler.libhook.rules.systemui.lockscreen.LockScreenDoubleTapToSleep
import com.sevtinge.hyperceiler.libhook.rules.systemui.lockscreen.System as TouchClock
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.Fields
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.InflateHook

private data class Touch(val time: Long, val x: Float, val y: Float)

private val keys = listOf("currentTouchTime", "currentTouchX", "currentTouchY")
private val first = Touch(10_000L, 40f, 60f)
private val second = Touch(10_100L, 44f, 65f)

private fun View.press(touch: Touch): Boolean {
    TouchClock.now = touch.time
    return dispatch(MotionEvent(MotionEvent.ACTION_DOWN, touch.x, touch.y))
}

private fun touches(owner: Any) = Touch(
    Fields.peek(owner, keys[0]) as Long,
    Fields.peek(owner, keys[1]) as Float,
    Fields.peek(owner, keys[2]) as Float,
)

private fun newView(locked: Boolean = true) = View(Context(locked)).also {
    InflateHook.callback(InflateHook.Param(it))
}

private fun verifyThreshold(label: String, touch: Touch, matched: Boolean, locked: Boolean = true) {
    // Seed the same starting state so even the baseline exercises the threshold checks.
    val view = newView(locked)
    Fields.write(view, keys[0], first.time)
    Fields.write(view, keys[1], first.x)
    Fields.write(view, keys[2], first.y)
    check(!view.press(touch))
    check(view.clicks == 1) { "$label: click dispatch changed" }
    check(view.context.keyguardReads == (if (matched) 1 else 0)) { "$label: lock check count changed" }
    val expectedSleep = if (matched && locked) listOf(1_000L) else emptyList()
    check(view.context.sleepRequests == expectedSleep) { "$label: unexpected power requests" }
    println("$label: lock checks=${view.context.keyguardReads}, sleep requests=${view.context.sleepRequests.size}")
}

fun main(args: Array<String>) {
    val baseline = args.singleOrNull() == "baseline"
    val rule = LockScreenDoubleTapToSleep
    rule.init()
    val view = newView()
    val empty = Touch(0L, 0f, 0f)
    check(touches(view) == empty)

    for ((index, touch) in listOf(first, second).withIndex()) {
        Fields.reads.clear()
        Fields.writes.clear()
        check(!view.press(touch)) { "ACTION_DOWN should not be consumed" }
        check(Fields.reads.size == keys.size && Fields.reads.all { it === view })
        val owner = if (baseline) rule else view
        check(Fields.writes.size == keys.size && Fields.writes.all { it === owner })
        val detected = !baseline && index == 1
        val expectedTouch = if (detected) empty else touch
        check(touches(owner) == expectedTouch && (!baseline || touches(view) == empty))
        check(view.clicks == index + 1 && view.context.keyguardReads == (if (detected) 1 else 0))
        check(view.context.sleepRequests == (if (detected) listOf(1_000L) else emptyList()))
        println("DOWN ${index + 1}: write owner=${if (baseline) "rule" else "View"}, view=${touches(view)}, " +
            "clicks=${view.clicks}, lock checks=${view.context.keyguardReads}, sleep requests=${view.context.sleepRequests.size}")
    }

    Fields.reads.clear()
    Fields.writes.clear()
    check(!view.dispatch(MotionEvent(1, second.x, second.y)))
    check(Fields.reads.isEmpty() && Fields.writes.isEmpty() && view.clicks == 2)
    check(view.context.keyguardReads == (if (baseline) 0 else 1))
    check(view.context.sleepRequests == (if (baseline) emptyList() else listOf(1_000L)))

    verifyThreshold("249 ms and 99 px on both axes", Touch(first.time + 249L, first.x + 99f, first.y + 99f), true)
    verifyThreshold("250 ms", second.copy(time = first.time + 250L), false)
    verifyThreshold("100 px horizontally", second.copy(x = first.x + 100f), false)
    verifyThreshold("100 px vertically", second.copy(y = first.y + 100f), false)
    verifyThreshold("valid pair while unlocked", second, true, locked = false)
    println("PASS ${if (baseline) "baseline" else "worktree"}: repeated presses, thresholds, lock gate and clicks")
}
