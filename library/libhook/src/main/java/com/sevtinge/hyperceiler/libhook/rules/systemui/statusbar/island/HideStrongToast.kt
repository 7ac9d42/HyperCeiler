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
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.island

import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.hookapi.LazyClass.NewStrongToast
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


object HideStrongToast : BaseHook() {
    override fun init() {
        val observers = Collections.newSetFromMap(WeakHashMap<ViewTreeObserver, Boolean>())
        val active = AtomicBoolean(true)
        val listener = ViewTreeObserver.OnPreDrawListener { !active.get() }
        NewStrongToast!!.findMethod { name("onAttachedToWindow") }.createAfterHook {
            val observer = (it.thisObject as FrameLayout).viewTreeObserver
            synchronized(observers) {
                if (active.get() && observer.isAlive && observer !in observers) {
                    observer.addOnPreDrawListener(listener)
                    observers.add(observer)
                }
            }
        }
        registerHotReloadCleanup {
            active.set(false)
            val cleanup = FutureTask {
                synchronized(observers) {
                    for (observer in observers) {
                        if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                    }
                    observers.clear()
                }
            }
            val mainLooper = Looper.getMainLooper()
            if (Looper.myLooper() == mainLooper) {
                cleanup.run()
            } else {
                check(Handler(mainLooper).post(cleanup)) { "Strong toast cleanup could not reach main thread" }
            }
            cleanup.get(500, TimeUnit.MILLISECONDS)
        }
    }
}
