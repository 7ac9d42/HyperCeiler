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
package com.sevtinge.hyperceiler.hooker.systemui;


import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.Miui.isPad;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreSmallVersion;

import androidx.preference.SwitchPreference;

import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;

public class LockScreenSettings extends DashboardFragment {
    SwitchPreference mHideRightButton; // 隐藏右侧按钮
    SwitchPreference mBlurButton; // 锁屏模糊按钮

    @Override
    public int getPreferenceScreenResId() {
        return R.xml.system_ui_lock_screen;
    }

    @Override
    public void initPrefs() {
        mHideRightButton = findPreference("prefs_key_system_ui_lock_screen_hide_camera");
        mBlurButton = findPreference("prefs_key_system_ui_lock_screen_blur_button");

        if (isPad()) {
            setFuncHint(mHideRightButton, 1);
            setFuncHint(mBlurButton, 1);
        } else if (isMoreHyperOSVersion(3f)) {
            setPreVisible(mHideRightButton, false);
        } else if (isMoreSmallVersion(200, 2f)) {
            setFuncHint(mHideRightButton, 2);
        }
    }
}
