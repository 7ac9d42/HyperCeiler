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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Handler;
import android.view.View;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.common.utils.prefs.PrefType;
import com.sevtinge.hyperceiler.common.utils.prefs.PrefsChangeObserver;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.AppsTool;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public class CleanOpenMenu extends BaseHook {
    private static final String PREF_KEY = "system_framework_clean_open_apps";
    private static final String PHYSICAL_PREF_KEY = "prefs_key_" + PREF_KEY;
    private static final int MIN_QUERY_PARAMETER_COUNT = 6;
    private volatile Set<String> selectedApps;

    Class<?> mPackageManagerService;

    private Set<String> selectedApps() {
        Set<String> snapshot = selectedApps;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (selectedApps == null) refreshSelectedApps();
            return selectedApps;
        }
    }

    private synchronized void refreshSelectedApps() {
        PrefsBridge.removeHookCache(PHYSICAL_PREF_KEY);
        selectedApps = Set.copyOf(PrefsBridge.getStringSet(PREF_KEY));
    }

    @Override
    public void init() {

        mPackageManagerService = findClassIfExists("com.android.server.pm.PackageManagerService");

        findAndHookMethod(mPackageManagerService, "systemReady", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Context mContext = (Context) getObjectField(param.getThisObject(), "mContext");
                Handler mHandler = (Handler) getObjectField(param.getThisObject(), "mHandler");
                new PrefsChangeObserver(mContext, mHandler, PrefType.Any, null, null) {
                    @Override
                    public void onChange(PrefType type, Uri uri, String name, Object def) {
                        try {
                            if (PHYSICAL_PREF_KEY.equals(name)) {
                                refreshSelectedApps();
                            } else if (name != null && name.startsWith(PHYSICAL_PREF_KEY + "_")) {
                                PrefsBridge.removeHookCache(name);
                                if (type == PrefType.Integer) {
                                    PrefsBridge.putHookCache(name, PrefsBridge.getInt(name, AppsTool.MimeType.ALL));
                                }
                            }
                        } catch (Throwable t) {
                            XposedLog.e(TAG, getPackageName(), t);
                        }
                    }
                };
                refreshSelectedApps();
            }
        });

        IMethodHook hook = new IMethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            public void after(HookParam param) {
                try {
                    Object[] args = param.getArgs();
                    if (args.length < MIN_QUERY_PARAMETER_COUNT || !(args[0] instanceof Intent intent)) return;
                    if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;
                    if (intent.hasExtra("HyperCeiler") && intent.getBooleanExtra("HyperCeiler", false)) return;
                    String scheme = intent.getScheme();
                    boolean validSchemes = "http".equals(scheme) || "https".equals(scheme) || "vnd.youtube".equals(scheme);
                    if (intent.getType() == null && !validSchemes) return;

                    List<ResolveInfo> resolved = (List<ResolveInfo>) param.getResult();
                    if (resolved == null || resolved.isEmpty()) return;
                    Set<String> selectedApps = selectedApps();
                    if (selectedApps.isEmpty()) return;

                    Context mContext = (Context) getObjectField(param.getThisObject(), "mContext");
                    int mimeFlag = getMimeFlag(getContentType(mContext, intent));
                    PackageManager pm = mContext.getPackageManager();
                    Iterator<ResolveInfo> itr = resolved.iterator();
                    while (itr.hasNext()) {
                        String packageName = itr.next().activityInfo.packageName;
                        if (!matchesOriginal(selectedApps, packageName, mimeFlag)) continue;
                        if (matchesDual(selectedApps, packageName, mimeFlag) || !hasDualApp(pm, packageName)) {
                            itr.remove();
                        }
                    }

                    param.setResult(resolved);
                } catch (Throwable t) {
                    if (!(t instanceof BadParcelableException)) XposedLog.e(TAG, getPackageName(), t);
                }
            }
        };

        hookAllMethods("com.android.server.pm.ComputerEngine", "queryIntentActivitiesInternal", MIN_QUERY_PARAMETER_COUNT, hook);

        com.sevtinge.hyperceiler.libhook.base.BaseHook.hookAllMethods("miui.securityspace.XSpaceResolverActivityHelper.ResolverActivityRunner", null, "run", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Intent mOriginalIntent = (Intent) getObjectField(param.getThisObject(), "mOriginalIntent");
                if (mOriginalIntent == null) return;
                String action = mOriginalIntent.getAction();
                if (!Intent.ACTION_VIEW.equals(action)) return;

                Context mContext = (Context) getObjectField(param.getThisObject(), "mContext");
                String mAimPackageName = (String) getObjectField(param.getThisObject(), "mAimPackageName");
                if (mContext == null || mAimPackageName == null) return;
                Set<String> selectedApps = selectedApps();
                if (selectedApps.isEmpty()) return;
                int mimeFlag = getMimeFlag(getContentType(mContext, mOriginalIntent));
                String choice;
                if (matchesOriginal(selectedApps, mAimPackageName, mimeFlag)) choice = "app2";
                else if (matchesDual(selectedApps, mAimPackageName, mimeFlag)) choice = "app1";
                else return;

                View mRootView = (View) getObjectField(param.getThisObject(), "mRootView");
                int resId = mContext.getResources().getIdentifier(choice, "id", "android.miui");
                if (mRootView == null) return;
                View target = mRootView.findViewById(resId);
                if (target != null) target.performClick();
            }
        });
    }

    private static boolean hasDualApp(PackageManager pm, String packageName) {
        try {
            return callMethod(pm, "getPackageInfoAsUser", packageName, 0, 999) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean matchesOriginal(Set<String> selectedApps, String packageName, int mimeFlag) {
        String entry = packageName + "|0";
        return (selectedApps.contains(packageName) || selectedApps.contains(entry)) && matchesMime(entry, mimeFlag);
    }

    private static boolean matchesDual(Set<String> selectedApps, String packageName, int mimeFlag) {
        String entry = packageName + "|999";
        return selectedApps.contains(entry) && matchesMime(entry, mimeFlag);
    }

    private static boolean matchesMime(String entry, int mimeFlag) {
        int mask = PrefsBridge.getInt(PREF_KEY + "_" + entry, AppsTool.MimeType.ALL);
        return (mask & mimeFlag) == mimeFlag;
    }

    private static String getContentType(Context context, Intent intent) {
        String scheme = intent.getScheme();
        boolean linkSchemes = "http".equals(scheme) || "https".equals(scheme) || "vnd.youtube".equals(scheme);
        String mimeType = intent.getType();
        if (mimeType == null && linkSchemes) mimeType = "link/*";
        if (mimeType == null && intent.getData() != null) try {
            mimeType = context.getContentResolver().getType(intent.getData());
        } catch (Throwable ignore) {
        }
        return mimeType;
    }

    private static int getMimeFlag(String mimeType) {
        int dataType = AppsTool.MimeType.OTHERS;
        if (mimeType != null)
            if (mimeType.startsWith("image/")) dataType = AppsTool.MimeType.IMAGE;
            else if (mimeType.startsWith("audio/")) dataType = AppsTool.MimeType.AUDIO;
            else if (mimeType.startsWith("video/")) dataType = AppsTool.MimeType.VIDEO;
            else if (mimeType.startsWith("text/") ||
                    mimeType.startsWith("application/pdf") ||
                    mimeType.startsWith("application/msword") ||
                    mimeType.startsWith("application/vnd.ms-") ||
                    mimeType.startsWith("application/vnd.openxmlformats-"))
                dataType = AppsTool.MimeType.DOCUMENT;
            else if (mimeType.startsWith("application/vnd.android.package-archive") ||
                    mimeType.startsWith("application/zip") ||
                    mimeType.startsWith("application/x-zip") ||
                    mimeType.startsWith("application/octet-stream") ||
                    mimeType.startsWith("application/rar") ||
                    mimeType.startsWith("application/x-rar") ||
                    mimeType.startsWith("application/x-tar") ||
                    mimeType.startsWith("application/x-bzip") ||
                    mimeType.startsWith("application/gzip") ||
                    mimeType.startsWith("application/x-lz") ||
                    mimeType.startsWith("application/x-compress") ||
                    mimeType.startsWith("application/x-7z") ||
                    mimeType.startsWith("application/java-archive"))
                dataType = AppsTool.MimeType.ARCHIVE;
            else if (mimeType.startsWith("link/")) dataType = AppsTool.MimeType.LINK;
        return dataType;
    }


}
