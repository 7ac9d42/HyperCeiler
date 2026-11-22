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
package com.sevtinge.hyperceiler.libhook.app;

import com.hchen.database.HookBase;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.rules.gallery.ChangeBackupServer;
import com.sevtinge.hyperceiler.libhook.rules.gallery.EnableIdPhoto;
import com.sevtinge.hyperceiler.libhook.rules.gallery.EnableMagicMatting;
import com.sevtinge.hyperceiler.libhook.rules.gallery.EnablePhotoMovie;
import com.sevtinge.hyperceiler.libhook.rules.gallery.EnableVideoEditor;
import com.sevtinge.hyperceiler.libhook.rules.gallery.EnableVideoPost;

@HookBase(targetPackage = "com.miui.gallery")
public class Gallery extends BaseLoad {

    @Override
    public void onPackageLoaded() {
        initHook(new EnablePhotoMovie(), PrefsBridge.getBoolean("gallery_enable_photo_movie"));
        initHook(new EnableIdPhoto(), PrefsBridge.getBoolean("gallery_enable_id_photo"));
        initHook(new EnableMagicMatting(), PrefsBridge.getBoolean("gallery_enable_magic_matting"));
        initHook(new EnableVideoPost(), PrefsBridge.getBoolean("gallery_enable_video_post"));
        initHook(new EnableVideoEditor(), PrefsBridge.getBoolean("gallery_enable_video_editor"));
        initHook(new ChangeBackupServer(), PrefsBridge.getStringAsInt("gallery_backup_server", 0) != 0);
    }
}
