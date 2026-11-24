from pathlib import Path
import os
import subprocess
import tempfile

D=Path(__file__).resolve().parent
sources={
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler{public static java.util.List<Runnable> q=new java.util.ArrayList<>();public Handler(Looper l){}public boolean post(Runnable r){q.add(r);return true;}public static void drain(){while(!q.isEmpty())q.remove(0).run();}}',
'android/os/AsyncTask.java':'package android.os;public class AsyncTask{public static java.util.List<Runnable> q=new java.util.ArrayList<>();public static java.util.concurrent.Executor THREAD_POOL_EXECUTOR=r->q.add(r);public static void runNext(){if(!q.isEmpty())q.remove(0).run();}public static void drain(){while(!q.isEmpty())runNext();}}',
'android/net/Uri.java':'package android.net;public class Uri{String s;Uri(String s){this.s=s;}public static Uri parse(String s){return new Uri(s);}public static Uri withAppendedPath(Uri u,String p){return new Uri(u.s+"/"+p);}public String toString(){return s;}}',
'android/database/Cursor.java':'package android.database;public interface Cursor extends AutoCloseable{boolean moveToFirst();int getInt(int i);void close();}',
'android/database/ContentObserver.java':'package android.database;public class ContentObserver{final android.os.Handler handler;public ContentObserver(android.os.Handler h){handler=h;}public void onChange(boolean b,android.net.Uri u){}public void dispatch(android.net.Uri u){handler.post(()->onChange(false,u));}}',
'android/content/ContentResolver.java':'''package android.content;
import android.database.*;import android.net.Uri;
public class ContentResolver {
 public int value=200,queries,active,peak;public boolean fail;public ContentObserver observer;public Runnable duringQuery;
 public void registerContentObserver(Uri u,boolean b,ContentObserver o){observer=o;}
 public void unregisterContentObserver(ContentObserver o){observer=null;}
 public Cursor query(Uri u,Object a,Object b,Object c,Object d){
  if(!u.toString().endsWith("integer/prefs_key_home_navigation_back_area_width/100"))throw new AssertionError(u);
  queries++;peak=Math.max(peak,++active);
  try {
   int v=value;var run=duringQuery;duringQuery=null;if(run!=null)run.run();
   if(fail)return null;
   return new Cursor(){public boolean moveToFirst(){return true;}public int getInt(int i){return v;}public void close(){}};
  } finally {active--;}
 }
 public void change(){
  String root="content://com.sevtinge.hyperceiler.provider.sharedprefs";
  observer.dispatch(Uri.parse(root+"/integer/prefs_key_home_navigation_back_area_width"));
  observer.dispatch(Uri.parse(root+"/pref/integer/prefs_key_home_navigation_back_area_width"));
 }
}''',
'android/content/Context.java':'package android.content;public class Context{public final ContentResolver resolver=new ContentResolver();public Context getApplicationContext(){return this;}public ContentResolver getContentResolver(){return resolver;}}',
'android/content/SharedPreferences.java':'package android.content;public interface SharedPreferences{interface OnSharedPreferenceChangeListener{void onSharedPreferenceChanged(SharedPreferences p,String key);}void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);}',
'com/sevtinge/hyperceiler/common/log/XposedLog.java':'package com.sevtinge.hyperceiler.common.log;public class XposedLog{public static void i(String a,String b){}public static void w(String a,String b){throw new AssertionError(b);}}',
'com/sevtinge/hyperceiler/common/utils/PrefsBridge.java':'''package com.sevtinge.hyperceiler.common.utils;
import android.content.SharedPreferences;
public class PrefsBridge {
 public static int getInt(String k,int d){return 100;}
 public static final Remote remote=new Remote();
 public static SharedPreferences getSharedPreferences(){return remote;}
 public static class Remote implements SharedPreferences {
  public OnSharedPreferenceChangeListener listener;
  public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listener=l;}
  public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){if(listener==l)listener=null;}
  public void change(){listener.onSharedPreferenceChanged(this,"prefs_key_home_navigation_back_area_width");}
 }
}''',
'io/github/lingqiqi5211/ezhooktool/xposed/common/HookParam.java':'package io.github.lingqiqi5211.ezhooktool.xposed.common;public class HookParam{Object o;public HookParam(Object o){this.o=o;}public Object getThisObject(){return o;}}',
'io/github/lingqiqi5211/ezhooktool/xposed/java/IMethodHook.java':'package io.github.lingqiqi5211.ezhooktool.xposed.java;public interface IMethodHook{default void after(io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam p){}}',
'com/sevtinge/hyperceiler/libhook/base/BaseHook.java':'''package com.sevtinge.hyperceiler.libhook.base;import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;public abstract class BaseHook{public final String TAG="Test";public static android.content.Context ctx=new android.content.Context();public static IMethodHook resource,ctor;public static java.util.List<Runnable> cleanup=new java.util.ArrayList<>();public abstract void init();public static Class<?> findClassIfExists(String s){try{return Class.forName(s);}catch(Exception e){throw new AssertionError(e);}}public static void hookMethod(java.lang.reflect.Method m,IMethodHook h){resource=h;}public static void hookAllConstructors(Class<?> c,IMethodHook h){ctor=h;}public static void registerHotReloadCleanup(Runnable r){cleanup.add(r);}public static void runOnApplicationAttach(java.util.function.Consumer<android.content.Context> c){c.accept(ctx);}public static void registerContentObserverHotReloadCleanup(android.content.ContentResolver c,android.database.ContentObserver o){cleanup.add(()->c.unregisterContentObserver(o));}}''',
'com/android/systemui/navigationbar/gestural/EdgeBackGestureHandler.java':'''package com.android.systemui.navigationbar.gestural;import com.sevtinge.hyperceiler.libhook.base.BaseHook;import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;public class EdgeBackGestureHandler{public int mEdgeWidthLeft=97,mEdgeWidthRight=97;public EdgeBackGestureHandler(){BaseHook.ctor.after(new HookParam(this));}private void updateCurrentUserResources(){}public void reset(int n){mEdgeWidthLeft=n;mEdgeWidthRight=n;BaseHook.resource.after(new HookParam(this));}}''',
}

prod=D.parents[1]/'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/systemui/navigation'

def java_tool(name):
    jdk=os.environ.get('JAVA_HOME')
    return str(Path(jdk)/'bin'/name) if jdk else name

with tempfile.TemporaryDirectory(prefix='hc-back-width-') as temp:
    out=Path(temp)
    files=[D/'RuleTest.java',prod/'BackGestureWidthState.java',prod/'BackGestureWidthOS4.java']
    for name,text in sources.items():
        path=out/'stubs'/name
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(text,encoding='utf8')
        files.append(path)
    subprocess.run([java_tool('javac'),'-encoding','UTF-8','-d',str(out),*map(str,files)],check=True)
    raise SystemExit(subprocess.run([java_tool('java'),'-ea','-cp',str(out),'fixture.RuleTest']).returncode)
