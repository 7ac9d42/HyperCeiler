#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Count subscriptions and exercise the production audio rule and preference bridge."""
import os
from pathlib import Path
import subprocess
import tempfile

here = Path(__file__).resolve().parent
root = here.parents[1]
common = root / 'library/common/src/main/java/com/sevtinge/hyperceiler/common/utils'
rule = root / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemframework/volume'
shared = here.parent / 'dock-style-refresh/stubs'
sources = {
    'android/os/Looper.java': 'package android.os; public class Looper {}',
    'android/os/Handler.java': '''package android.os;
import java.util.ArrayDeque;
public class Handler {
 public static int allocations;
 private static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
 public Handler(Looper l) { allocations++; }
 public boolean post(Runnable r) { queue.add(r); return true; }
 public static void drain() { while (!queue.isEmpty()) queue.remove().run(); }
}''',
    'android/net/Uri.java': '''package android.net;
import java.util.*;
public record Uri(String value) {
 public static Uri parse(String v) { return new Uri(v); }
 public List<String> getPathSegments() {
  return Arrays.stream(java.net.URI.create(value).getPath().split("/")).filter(s -> !s.isEmpty()).toList();
 }
 @Override public String toString() { return value; }
}''',
    'android/database/ContentObserver.java': '''package android.database;
import android.net.Uri; import android.os.Handler;
public class ContentObserver {
 final Handler handler;
 public ContentObserver(Handler h) { handler=h; }
 public void onChange(boolean self, Uri uri) {}
 public void dispatch(Uri uri) { handler.post(() -> onChange(false, uri)); }
}''',
    'android/content/ContentResolver.java': '''package android.content;
import android.database.ContentObserver; import android.net.Uri; import java.util.*;
public class ContentResolver {
 public int registrations;
 public final Map<ContentObserver,Uri> active=new LinkedHashMap<>();
 public void registerContentObserver(Uri u,boolean descendants,ContentObserver o) { registrations++; active.put(o,u); }
 public void unregisterContentObserver(ContentObserver o) { active.remove(o); }
 public void notifyChange(Uri uri,Object ignored) {
  active.forEach((o,u)->{if (uri.equals(u)) o.dispatch(uri);});
 }
}''',
    'android/content/Context.java': '''package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 private final SharedPreferences prefs;
 public final ContentResolver resolver = new ContentResolver();
 public Context(SharedPreferences p) { prefs=p; }
 public Context createDeviceProtectedStorageContext() { return this; }
 public SharedPreferences getSharedPreferences(String n,int m) { return prefs; }
 public ContentResolver getContentResolver() { return resolver; }
 public android.os.Looper getMainLooper() { return new android.os.Looper(); }
}''',
    'com/sevtinge/hyperceiler/common/utils/api/ProjectApi.java': '''package com.sevtinge.hyperceiler.common.utils.api;
public class ProjectApi { public static final String mAppModulePkg="com.sevtinge.hyperceiler"; }''',
    'com/android/server/audio/AudioService.java': '''package com.android.server.audio;
import android.content.Context;
public class AudioService {
 private final Context mContext;
 public AudioService(Context c) { mContext=c; }
 public int getActiveStreamType(int suggested) { return 9; }
}''',
    'io/github/lingqiqi5211/ezhooktool/xposed/common/HookParam.java': '''package io.github.lingqiqi5211.ezhooktool.xposed.common;
public class HookParam {
 private final Object target;
 public Integer result;
 public HookParam(Object t) { target=t; }
 public Object getThisObject() { return target; }
 public void setResult(Object r) { result=(Integer)r; }
}''',
    'io/github/lingqiqi5211/ezhooktool/xposed/java/IMethodHook.java': '''package io.github.lingqiqi5211.ezhooktool.xposed.java;
public interface IMethodHook { void before(io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam p); }''',
    'com/sevtinge/hyperceiler/libhook/base/BaseHook.java': '''package com.sevtinge.hyperceiler.libhook.base;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import com.android.server.audio.AudioService;
public abstract class BaseHook {
 public static IMethodHook before;
 public static int lookups;
 private static final java.util.ArrayDeque<Runnable> cleanups = new java.util.ArrayDeque<>();
 public abstract void init();
 public static Class<?> findClassIfExists(String n) { try { return Class.forName(n); } catch (ClassNotFoundException e) { return null; } }
 public static void findAndHookMethod(Class<?> t,String name,Class<?> signature,IMethodHook cb) {
  try { t.getDeclaredMethod(name,signature); before=cb; } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
 }
 public static Object getObjectField(Object target,String name) {
  lookups++;
  try { var f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
  catch (ReflectiveOperationException e) { throw new AssertionError(e); }
 }
 public static void registerHotReloadCleanup(Runnable r) { cleanups.push(r); }
 public static void retire() { while (!cleanups.isEmpty()) cleanups.pop().run(); }
 public static int choose(AudioService service) {
  var p=new HookParam(service); before.before(p); return p.result==null ? service.getActiveStreamType(0) : p.result;
 }
}''',
}

with tempfile.TemporaryDirectory(prefix='hc-volume-stream-') as tmp:
    out = Path(tmp)
    files = [here / 'VolumeObserverTest.java', rule / 'VolumeDefaultStream.java', common / 'PrefsBridge.java',
             common / 'prefs/PrefsChangeObserver.java', common / 'prefs/PrefType.java']
    files += [shared / name for name in ('android/content/SharedPreferences.java', 'android/text/TextUtils.java',
              'androidx/annotation/NonNull.java', 'androidx/annotation/Nullable.java',
              'com/sevtinge/hyperceiler/common/log/AndroidLog.java')]
    for name, source in sources.items():
        file = out / 'stubs' / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(source, encoding='utf-8')
        files.append(file)
    jdk = os.environ.get('JAVA_HOME')
    def tool(name):
        return str(Path(jdk) / 'bin' / name) if jdk else name
    subprocess.run([tool('javac'), '-encoding', 'UTF-8', '-d', str(out), *map(str, files)], check=True)
    raise SystemExit(subprocess.run([tool('java'), '-cp', str(out), 'VolumeObserverTest']).returncode)
