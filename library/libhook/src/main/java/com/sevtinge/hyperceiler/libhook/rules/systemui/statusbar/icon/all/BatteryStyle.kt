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
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.icon.all

import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils.dp2px
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook

object BatteryStyle : BaseHook() {
    private val fontSize by lazy {
        PrefsBridge.getInt("system_ui_status_bar_battery_style_font_size", 15) * 0.5f
    }
    private val fontSizeMark by lazy {
        PrefsBridge.getInt("system_ui_status_bar_battery_style_font_mark_size", 15) * 0.5f
    }
    private val verticalOffset by lazy {
        PrefsBridge.getInt("system_ui_status_bar_battery_style_vertical_offset", 8)
    }
    private val verticalOffsetMark by lazy {
        PrefsBridge.getInt("system_ui_status_bar_battery_style_vertical_offset_mark", 27)
    }
    private val isHideText by lazy {
        PrefsBridge.getBoolean("system_ui_status_bar_battery_percent")
    }
    private val isEnableCustom by lazy {
        PrefsBridge.getBoolean("system_ui_status_bar_battery_style_enable_custom")
    }
    private val isEnableBold by lazy {
        PrefsBridge.getBoolean("system_ui_status_bar_battery_style_bold")
    }
    private val isEnableBatteryMark by lazy {
        PrefsBridge.getBoolean("system_ui_status_bar_battery_percent_mark")
    }
    private val leftMargin by lazy {
        dp2px(PrefsBridge.getInt("system_ui_status_bar_battery_style_left_margin", 0) * 0.5f)
    }
    private val rightMargin by lazy {
        dp2px(PrefsBridge.getInt("system_ui_status_bar_battery_style_right_margin", 0) * 0.5f)
    }

    private val mBatteryMeterViewClass by lazy {
        loadClass("com.android.systemui.statusbar.views.MiuiBatteryMeterView")
    }

    override fun init() {
        mBatteryMeterViewClass.findMethod { name("updateAll$1") }.createAfterHook { param ->
                hookStatusBattery(param)
            }
    }

    private fun setBatterySize(view: TextView, size: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
    }

    private fun setMargin(view1: TextView, view2: TextView) {
        // 左右间距已随其他电量样式配置缓存。
        val leftMargin = this.leftMargin
        val rightMargin = this.rightMargin

        // 上下偏移量
        var topMargin = 0
        if (verticalOffset != 12) {
            topMargin = dp2px((verticalOffset - 12) * 0.5f)
        }
        view1.setPaddingRelative(leftMargin, topMargin, rightMargin, 0)

        var digitRightMargin = 0
        var markRightMargin = 0
        if (isEnableBatteryMark) {
            digitRightMargin = rightMargin
        } else {
            markRightMargin = rightMargin
        }
        if (leftMargin > 0 || topMargin != 8 || digitRightMargin > 0) {
            view1.setPaddingRelative(
                leftMargin, topMargin, digitRightMargin, 0
            )
        }

        if (verticalOffsetMark < 27) {
            val marginTop =
                dp2px((verticalOffsetMark - 8) * 0.5f)
            topMargin = marginTop
        }
        if (verticalOffsetMark < 27 || markRightMargin > 0) {
            view2.setPaddingRelative(0, topMargin, markRightMargin, 0)
        }
    }

    private fun hookStatusBattery(param: HookParam) {
        val mBatteryPercentView =
            getObjectField(param.thisObject, "mBatteryPercentView") as TextView
        val mBatteryPercentMarkView =
            getObjectField(param.thisObject, "mBatteryPercentMarkView") as TextView
        val mBatteryTextDigitView =
            getObjectField(param.thisObject, "mBatteryTextDigitView") as TextView

        // 以下功能需要启用修改
        if (!isHideText && isEnableCustom) {
            if (fontSize > 7.5) {
                setBatterySize(mBatteryTextDigitView, fontSize)
                setBatterySize(mBatteryPercentView, fontSize)
            }
            if (fontSizeMark > 7.5) {
                setBatterySize(mBatteryPercentMarkView, fontSizeMark)
            }

            if (isEnableBold) {
                mBatteryTextDigitView.typeface = Typeface.DEFAULT_BOLD
                mBatteryPercentView.typeface = Typeface.DEFAULT_BOLD
            }

            // 设置边距
            setMargin(mBatteryPercentView, mBatteryPercentMarkView)
        }
    }
}
