/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter.media3

import android.util.TypedValue
import android.widget.TextView
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion
import io.github.libxposed.api.XposedInterface
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook

object MediaViewSize : BaseHook() {
    private const val TITLE_TEXT = "titleText"
    private const val ARTIST_TEXT = "artistText"
    private const val ELAPSED_TIME_VIEW = "elapsedTimeView"
    private const val TOTAL_TIME_VIEW = "totalTimeView"


    override fun init() {
        if (isMoreHyperOSVersion(4f)) initOS4()
    }

    // OS4 inlines holder constructors. The NC layout resets TextAppearance;
    // the island reset publishes live and transition holders before returning.
    private class TextGroup(
        val owner: Class<*>, val holder: Class<*>, val holders: List<Field>,
        val textFields: List<Field>, val sizes: FloatArray
    ) {
        fun apply(receiver: Any?) {
            if (receiver?.javaClass != owner) return
            for (field in holders) {
                val target = field.get(receiver) ?: continue
                if (target.javaClass != holder) continue
                // Check the complete holder before changing any of its four views.
                val views = textFields.map { it.get(target) as? TextView ?: return }
                for (index in views.indices) {
                    views[index].setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes[index])
                }
            }
        }
    }

    // ROM calls and rollback can throw arbitrary runtime failures; preserve the host and cleanup boundary.
    @Suppress("TooGenericExceptionCaught")
    private fun initOS4() {
        val active = AtomicBoolean(false)
        val plans = ArrayList<Pair<Method, IMethodHook>>()
        val handles = ArrayList<XposedInterface.HookHandle>()
        try {
            for (island in listOf(false, true)) {
                val prefix = if (island) "system_ui_island_media_control" else "system_ui_control_center_media_control"
                if (!PrefsBridge.getBoolean(prefix + "_media_button_size_switch")) continue
                val packageName = "com.android.systemui.statusbar.notification." +
                    if (island) "mediaisland." else "mediacontrol."
                val owner = requiredClass(packageName + if (island) "MiuiIslandMediaControllerImpl" else "MiuiMediaNotificationControllerImpl")
                val holder = requiredClass(packageName + if (island) "MiuiIslandMediaViewHolder" else "MiuiMediaViewHolder")
                val names = if (island) listOf("miuiPlayerHolder", "miuiDummyPlayerHolder") else listOf("mediaViewHolder")
                val time = readSize(prefix + "_time_view_text_size", 130, 50..200)
                val group = TextGroup(owner, holder, names.map { requiredField(owner, it, holder) },
                    listOf(TITLE_TEXT, ARTIST_TEXT, ELAPSED_TIME_VIEW, TOTAL_TIME_VIEW)
                        .map { requiredField(holder, it, TextView::class.java) },
                    floatArrayOf(readSize(prefix + "_title_size", 180, 120..360),
                        readSize(prefix + "_artist_size", 120, 80..240), time, time))
                val update = requiredVoidMethod(owner, if (island) "resetPlayerHolder" else "updateLayout\$1")
                val configuration = requiredClass(owner.name + "\$configurationCallback\$1")
                val controller = requiredField(configuration, "this\$0", owner)
                val fontScale = requiredVoidMethod(configuration, "onDensityOrFontScaleChanged")
                plans.add(update to afterOriginal(active, owner) { group.apply(it) })
                // Pure font-scale changes do not rebuild either card in this ROM.
                // Preserve the original queued density reinflate; its later reset
                // reapplies the configured sizes to the replacement island views.
                plans.add(fontScale to afterOriginal(active, configuration) { group.apply(controller.get(it)) })
            }
            for ((method, callback) in plans) handles.add(checkNotNull(hookMethod(method, callback)))
            active.set(true)
        } catch (error: Exception) {
            active.set(false)
            for (handle in handles) {
                try { handle.unhook() } catch (rollback: RuntimeException) { error.addSuppressed(rollback) }
            }
            throw IllegalStateException("OS4 media text layout or registration unavailable", error)
        }
    }

    private fun afterOriginal(active: AtomicBoolean, owner: Class<*>, apply: (Any) -> Unit): IMethodHook =
        object : IMethodHook {
            // ROM calls and rollback can throw arbitrary runtime failures; preserve the host and cleanup boundary.
            @Suppress("TooGenericExceptionCaught")
            override fun after(param: HookParam) {
                if (!active.get() || param.hasThrowable || param.isSkipped) return
                if (param.thisObject?.javaClass != owner) return
                try {
                    apply(param.thisObject!!)
                } catch (error: ReflectiveOperationException) {
                    debugCallbackError("OS4 media text layout", error)
                } catch (error: RuntimeException) {
                    debugCallbackError("OS4 media text layout", error)
                }
            }
        }

    private fun requiredClass(name: String): Class<*> =
        findClassIfExists(name) ?: throw IllegalStateException("Missing OS4 media text class: $name")

    private fun requiredField(owner: Class<*>, name: String, expected: Class<*>): Field =
        owner.getDeclaredField(name).apply {
            check(type == expected && !Modifier.isStatic(modifiers))
            isAccessible = true
        }

    private fun requiredVoidMethod(owner: Class<*>, name: String): Method =
        owner.getDeclaredMethod(name).apply {
            check(returnType == Void.TYPE && !Modifier.isStatic(modifiers))
            isAccessible = true
        }

    private fun readSize(key: String, default: Int, range: IntRange): Float {
        val value = PrefsBridge.getInt(key, default)
        check(value in range) { "Invalid media text size: $key" }
        return value / 10f
    }
}
