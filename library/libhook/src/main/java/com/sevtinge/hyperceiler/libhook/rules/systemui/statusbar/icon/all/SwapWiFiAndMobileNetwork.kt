/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.icon.all

import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldOrNull

object SwapWiFiAndMobileNetwork : BaseHook() {
    override fun init() {
        if (isMoreHyperOSVersion(4f)) {
            // OS4 constructs the list inside this factory; the String[] constructor
            // no longer exists. Reorder the original Slot objects before consumers bind.
            loadClass("com.android.systemui.statusbar.dagger.CentralSurfacesDependenciesModule_ProvideStatusBarIconListFactory")
                .findMethod { name("provideStatusBarIconList") }
                .createAfterHook { param ->
                    reorderOS4Slots(param.result)
                }
        }
    }

    private fun reorderOS4Slots(result: Any?) {
        val slots = result?.getObjectFieldOrNull("mSlots") as? MutableList<*>
            ?: return
        val names = slots.map { it?.getObjectFieldOrNull("mName") as? String }
        val order = listOf("wifi", "demo_wifi", "mobile", "demo_mobile", "no_sim")
        // Preserve unfamiliar slots and avoid duplicating/inventing any slot.
        if (order.any { name -> names.count { it == name } != 1 }) return
        val indices = names.indices.filter { names[it] in order }
        val reordered = order.map { name -> slots[names.indexOf(name)] }
        @Suppress("UNCHECKED_CAST")
        val mutableSlots = slots as MutableList<Any?>
        indices.forEachIndexed { index, position -> mutableSlots[position] = reordered[index] }
        // mViewOnlySlots wraps this same list; keep its identity and Slot holders.
    }
}
