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
package com.sevtinge.hyperceiler.hooker.home;

import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion;

import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.rules.home.dock.DockWindowPolicy;

import fan.preference.DropDownPreference;

public class HomeDockSettings extends DashboardFragment {

    @Override
    public int getPreferenceScreenResId() {
        return R.xml.home_dock;
    }

    @Override
    public void initPrefs() {
        DropDownPreference unlockStyle = findPreference("prefs_key_home_dock_unlock_style");
        // The old keyframe mode was removed rather than retuned. Persist the one-way rename so
        // an existing installation visibly selects 重力势能 instead of retaining an orphaned value.
        String revealStyle = PrefsBridge.getString("prefs_key_home_dock_unlock_style", "daybreak");
        if ("elastic_burst".equalsIgnoreCase(revealStyle)) {
            PrefsBridge.putString("prefs_key_home_dock_unlock_style", "auto_aim");
            unlockStyle.setValue("auto_aim");
        }

        int blurMode = PrefsBridge.getStringAsInt("prefs_key_home_dock_add_blur", 0);
        DropDownPreference blurModePreference = findPreference("prefs_key_home_dock_add_blur");

        if (isMoreHyperOSVersion(4f)) {
            blurMode = DockWindowPolicy.normalizeBackgroundMode(blurMode);
            blurModePreference.setValue(String.valueOf(blurMode));
        }
    }
}
