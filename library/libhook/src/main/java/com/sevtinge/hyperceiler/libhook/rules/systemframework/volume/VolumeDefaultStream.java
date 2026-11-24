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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.common.utils.prefs.PrefsChangeObserver.PrefToUri;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public class VolumeDefaultStream extends BaseHook {

    private static final String KEY = "prefs_key_system_framework_default_volume_stream";
    private volatile boolean observing;
    private volatile boolean closed;
    private Runnable unregister;

    @Override
    public void init() {
        Class<?> audioService = findClassIfExists("com.android.server.audio.AudioService");
        if (audioService == null) return;
        registerHotReloadCleanup(this::close);
        findAndHookMethod(audioService, "getActiveStreamType", int.class, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                if (closed) return;
                if (!observing) observe(param.getThisObject());
                if (!closed) param.setResult(PrefsBridge.getStringAsInt(KEY, 0));
            }
        });
    }

    private synchronized void observe(Object service) {
        if (closed || observing) return;
        Context context = (Context) getObjectField(service, "mContext");
        if (context == null) return;
        Handler handler = new Handler(context.getMainLooper());
        SharedPreferences remote = PrefsBridge.isHookProcess() ? PrefsBridge.getSharedPreferences() : null;
        if (remote != null) {
            SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
                if (!closed && KEY.equals(key)) handler.post(this::refreshPreference);
            };
            remote.registerOnSharedPreferenceChangeListener(listener);
            unregister = () -> remote.unregisterOnSharedPreferenceChangeListener(listener);
        } else {
            ContentResolver resolver = context.getContentResolver();
            ContentObserver observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    if (!selfChange) refreshPreference();
                }
            };
            resolver.registerContentObserver(PrefToUri.stringPrefToUri(KEY), false, observer);
            unregister = () -> resolver.unregisterContentObserver(observer);
        }
        observing = true;
    }

    private synchronized void refreshPreference() {
        if (closed) return;
        PrefsBridge.removeHookCache(KEY);
        PrefsBridge.putHookCache(KEY, PrefsBridge.getString(KEY, "0"));
    }

    private void close() {
        Runnable cleanup;
        synchronized (this) {
            closed = true;
            cleanup = unregister;
            unregister = null;
        }
        if (cleanup != null) cleanup.run();
    }
}
