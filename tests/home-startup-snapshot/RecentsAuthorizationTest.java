package com.sevtinge.hyperceiler.libhook.rules.home.dock;

import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Parcel;
import android.os.SystemClock;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.provider.HomeLayoutPrefsSnapshot;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Ordinary layout and combined Recents preference/authorization publication with host adapters. */
public final class RecentsAuthorizationTest {
 static final String DESC="android.view.IWindowManager";
 static final AtomicLong now=new AtomicLong();
 static int passed,failed;
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
 static void set(String n,Object v){try{var f=HomeLayoutNativeEndpointOS4.class.getDeclaredField(n);f.setAccessible(true);f.set(null,v);}catch(Exception e){throw new AssertionError(e);}}
 static Object get(String n){try{var f=HomeLayoutNativeEndpointOS4.class.getDeclaredField(n);f.setAccessible(true);return f.get(null);}catch(Exception e){throw new AssertionError(e);}}
 static void reset(){
  for(String s:new String[]{"cachedValues","combinedPublished"})set(s,null);
  for(String s:new String[]{"refreshEpoch","refreshTicket","notificationVersion"})set(s,0L);
  set("paused",false);set("refresher",new Thread());
  BaseHook.resetTestState();BaseLoad.resetFrameworkForTest();Binder.setCallingIdentityForTest(10100,42);
  PackageManager.packages=new String[]{"com.miui.home"};PackageManager.fail=false;
  ContentResolver.values=Map.of();ContentResolver.failure="";ContentResolver.afterSnapshot=()->{};
  ContentResolver.queries.set(0);ContentResolver.reads.set(0);now.set(10_000_000_000L);SystemClock.source=now::get;
 }
 static void run(String n,Runnable r){reset();try{r.run();passed++;System.out.println("PASS "+n);}catch(Throwable t){failed++;System.out.println("FAIL "+n+" "+t);t.printStackTrace(System.out);}}
 static HomeLayoutNativeEndpointOS4.Snapshot layout(){
  return new HomeLayoutNativeEndpointOS4((u,p)->u==10100&&p==42,HomeLayoutNativeEndpointOS4::readPreferences).receive(new Parcel(DESC),0);
 }
 static HomeLayoutNativeEndpointOS4.RecentsBarPreference read(){return HomeLayoutNativeEndpointOS4.recentsBarPreference();}
 static Map<String,Object> prefs(Object...p){var m=new HashMap<String,Object>();for(int i=0;i<p.length;i+=2)m.put("prefs_key_"+p[i],p[i+1]);return m;}
 static Map<String,Object> recentsPrefs(Object...p){var m=prefs(p);m.put("prefs_key_"+HomeLayoutPrefsSnapshot.RECENTS_BAR_KEY,true);m.putIfAbsent("prefs_key_allow_hook",true);return m;}
 static void publish(Map<String,Object> p){ContentResolver.values=p;check(HomeLayoutNativeEndpointOS4.refreshFromProvider());}
 static void unavailable(){var p=read();check(!p.available()&&!p.enabled());eq(p.deadline(),0L);}
 static void enabled(boolean expected){var p=read();check(p.available());eq(p.enabled(),expected);}
 static final class Gate {final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);void block(){entered.countDown();await(release);}void entered(){await(entered);}}
 static void await(CountDownLatch l){try{check(l.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
 static void join(Thread t){try{t.join(5000);check(!t.isAlive());}catch(InterruptedException e){throw new AssertionError(e);}}

 public static void main(String[] args){
  run("provider schema and typed keys generated from one getAll",()->{
   ContentResolver.values=prefs("allow_hook",true,"home_dock_add_blur","1");
   try(var cursor=ContentResolver.cursor()){
    check(cursor.moveToFirst());eq(cursor.getInt(0),HomeLayoutPrefsSnapshot.SCHEMA);
    var keys=HomeLayoutPrefsSnapshot.specs();eq(cursor.getColumnCount(),keys.length+1);
    for(int i=0;i<keys.length;i++)eq(cursor.getColumnName(i+1),keys[i][1]);
   }
   eq(ContentResolver.reads.get(),1);
  });
  run("returned specs cannot change the provider schema",()->{
   var keys=HomeLayoutPrefsSnapshot.specs();var originalType=keys[0][0];
   keys[0][0]="wrong";eq(HomeLayoutPrefsSnapshot.specs()[0][0],originalType);
   publish(recentsPrefs());enabled(true);
  });
  run("caller UID must have the exact Home package",()->{
   check(HomeLayoutNativeEndpointOS4.uidBelongsToLauncher(10100));
   PackageManager.packages=new String[]{"com.miui.home.fake"};check(!HomeLayoutNativeEndpointOS4.uidBelongsToLauncher(10100));
   PackageManager.packages=null;check(!HomeLayoutNativeEndpointOS4.uidBelongsToLauncher(10100));
   PackageManager.packages=new String[]{"com.other","com.miui.home"};check(HomeLayoutNativeEndpointOS4.uidBelongsToLauncher(10100));
   check(!HomeLayoutNativeEndpointOS4.uidBelongsToLauncher(1000));
  });
  run("package service failure cannot authorize caller",()->{
   PackageManager.fail=true;check(!HomeLayoutNativeEndpointOS4.uidBelongsToLauncher(10100));
  });
  run("cold Recents preference unavailable",RecentsAuthorizationTest::unavailable);
  run("restored layout cache cannot authorize Recents",()->{
   BaseHook.putHotReloadRuntimeState("OS4.HomeLayout.ProviderSnapshot.v1",Map.of("allow_hook",1,
    HomeLayoutPrefsSnapshot.RECENTS_BAR_KEY,1,"home_layout_unlock_grids_new",1,"home_layout_unlock_grids_cell_x",9));
   eq(layout().cellX(),9);unavailable();
  });
  run("empty provider snapshot disables Recents; ordinary layout keeps defaults",()->{
   publish(Map.of());enabled(false);var s=layout();
   eq(s.acknowledgment(),0x48434c34);eq(s.gridEnabled(),0);eq(s.cellX(),4);eq(s.cellY(),6);
   eq(s.tweaks()[0],3);eq(s.tweaks()[1],0);eq(s.tweaks()[8],0x66);for(int v:s.knobDeltaDp())eq(v,0);
  });
  run("Recents and ordinary layout use the same physical getAll",()->{
   publish(recentsPrefs("home_title_hide_report_text",true,"home_layout_unlock_grids_new",true,
    "home_layout_unlock_grids_cell_x",7,"home_layout_unlock_grids_cell_y",11,
    "home_layout_workspace_padding_top_enable",true,"home_layout_workspace_padding_top",53));
   enabled(true);var s=layout();eq(s.cellX(),7);eq(s.cellY(),11);eq(s.knobDeltaDp()[2],23);eq(s.policies(),1L);
   eq(ContentResolver.reads.get(),1);eq(ContentResolver.queries.get(),1);
  });
  run("disabled knob always zero delta despite stored nondefault slider",()->{
   publish(prefs("home_layout_hotseats_margin_bottom_enable",false,"home_layout_hotseats_margin_bottom",100));
   var s=layout();eq(s.knobEnabled()[0],0);eq(s.knobDeltaDp()[0],0);
  });
  run("returned layout arrays cannot corrupt later snapshots",()->{
   publish(prefs("home_layout_workspace_padding_top_enable",true,"home_layout_workspace_padding_top",55));
   var s=layout();s.knobEnabled()[2]=0;s.knobDeltaDp()[2]=999;s.tweaks()[0]=15;
   var next=layout();eq(next.knobEnabled()[2],1);eq(next.knobDeltaDp()[2],25);eq(next.tweaks()[0],3);
  });
  run("ordinary layout retains extended fields and string titles",()->{
   publish(prefs("home_title_font_size",15,"home_drawer_title_font_size",14,
    "home_title_title_icontitlecustomization_onoff",true,"home_title_title_icontitlecustomization",Set.of("pkg.name฿新标题฿fixture"),
    "home_layout_small_folder_scale_enable",true,"home_layout_small_folder_scale",83,
    "home_layout_big_folder_scale_enable",true,"home_layout_big_folder_scale",121,
    "home_layout_widget_scale_enable",true,"home_layout_widget_scale",130,"home_dock_icon_title",true,
    "home_widget_allow_moved_to_minus_one_screen",true,"home_folder_auto_close",true,"home_folder_width",true,
    "home_navigation_back_area_height",80,"home_navigation_back_area_width",230));
   var s=layout();eq(s.title()[0],15);eq(s.title()[1],14);
   check(Arrays.deepEquals(s.customTitles(),new String[][]{{"pkg.name","新标题"}}));
   eq(Arrays.toString(s.objectScales()),"[1, 83, 1, 121, 1, 130]");
   check(s.dockShowTitle()&&s.widgetAllowMove()&&s.folderAutoClose());
   eq(s.folder()[1],1);eq(Arrays.toString(s.backGesture()),"[80, 230]");
  });
  run("notification during provider query rejects the entire in-flight revision",()->{
   publish(recentsPrefs("home_layout_unlock_grids_cell_x",7));Object previous=get("cachedValues");
   ContentResolver.afterSnapshot=()->set("notificationVersion",(long)get("notificationVersion")+1);
   ContentResolver.values=recentsPrefs("home_layout_unlock_grids_cell_x",9);
   check(!HomeLayoutNativeEndpointOS4.refreshFromProvider());unavailable();check(get("cachedValues")==previous);
   ContentResolver.afterSnapshot=()->{};publish(ContentResolver.values);enabled(true);eq(layout().cellX(),9);
  });
  run("global off disables Recents despite the setting being enabled",()->{publish(recentsPrefs("allow_hook",false));enabled(false);});
  run("framework stored false unchanged fingerprint denied",()->{
   publish(recentsPrefs("framework_api_allow_hook",false,"framework_check_reason","SERVICE_ERROR",
    "framework_check_name","FixtureFramework","framework_check_version","102-test","framework_check_version_code",102L));enabled(false);
  });
  run("changed framework override same gate as Java main",()->{publish(recentsPrefs("framework_api_allow_hook",false,"framework_check_reason","SERVICE_ERROR","framework_check_name","previous"));enabled(true);});
  run("framework service failing cannot authorize override",()->{publish(recentsPrefs("framework_api_allow_hook",false,"framework_check_reason","SERVICE_ERROR","framework_check_name","previous"));BaseLoad.fail=true;enabled(false);});
  run("provider delayed two seconds gets only remaining three seconds",()->{ContentResolver.afterSnapshot=()->now.addAndGet(2_000_000_000L);publish(recentsPrefs());enabled(true);eq(read().deadline(),15_000_000_000L);});
  run("provider query at five seconds cannot publish or renew",()->{publish(recentsPrefs());ContentResolver.afterSnapshot=()->now.addAndGet(5_000_000_000L);check(!HomeLayoutNativeEndpointOS4.refreshFromProvider());unavailable();});
  run("server deadline expires exactly at boundary",()->{publish(recentsPrefs());now.set(14_999_999_999L);enabled(true);now.incrementAndGet();unavailable();});
  run("suspend-inclusive clock expiry does not depend on nanoTime",()->{publish(recentsPrefs());now.addAndGet(86_400_000_000_000L);unavailable();});
  run("repeated reads never renew deadline",()->{publish(recentsPrefs());var s=read();now.addAndGet(2_000_000_000L);for(int i=0;i<50;i++)eq(read(),s);eq(ContentResolver.queries.get(),1);});
  run("zero clock cannot authorize or refresh",()->{now.set(0);check(!HomeLayoutNativeEndpointOS4.refreshFromProvider());unavailable();});
  run("clock deadline overflow rejected",()->{now.set(Long.MAX_VALUE-2);check(!HomeLayoutNativeEndpointOS4.refreshFromProvider());unavailable();});
  run("clock moves backwards during query rejects snapshot",()->{ContentResolver.afterSnapshot=()->now.decrementAndGet();check(!HomeLayoutNativeEndpointOS4.refreshFromProvider());unavailable();});
  run("clock moves before published query start cannot extend remaining lease",()->{publish(recentsPrefs());now.decrementAndGet();unavailable();});
  run("query failure revokes Recents and preserves layout cache",()->{
   publish(recentsPrefs("home_layout_unlock_grids_cell_x",7));ContentResolver.failure="query";
   check(!HomeLayoutNativeEndpointOS4.refreshFromProvider());unavailable();eq(layout().cellX(),7);
  });
  for(String mode:new String[]{"getAll","null","empty","columns","columnName","nullSchema","schema1","read","close"})run("bad whole cursor revokes Recents: "+mode,()->{publish(recentsPrefs());ContentResolver.failure=mode;check(!HomeLayoutNativeEndpointOS4.refreshFromProvider());unavailable();});
  for(int[] dims:new int[][]{{2,6},{10,6},{4,3},{4,14}})run("ordinary layout rejects out of range grid "+Arrays.toString(dims),()->{publish(prefs("home_layout_unlock_grids_cell_x",dims[0],"home_layout_unlock_grids_cell_y",dims[1]));eq(layout().cellX(),0);});
  run("publication replaced during read cannot return old Recents authorization",()->{
   publish(recentsPrefs());SystemClock.source=()->{
    SystemClock.source=now::get;publish(recentsPrefs("allow_hook",false));return now.get();
   };
   unavailable();enabled(false);
  });
  run("late older provider query cannot overwrite newer revision",()->{
   Gate g=new Gate();ContentResolver.values=recentsPrefs("home_layout_unlock_grids_cell_x",7);ContentResolver.afterSnapshot=g::block;
   AtomicBoolean accepted=new AtomicBoolean(true);Thread a=new Thread(()->accepted.set(HomeLayoutNativeEndpointOS4.refreshFromProvider()));a.start();g.entered();
   ContentResolver.afterSnapshot=()->{};publish(recentsPrefs("allow_hook",false,"home_layout_unlock_grids_cell_x",9));
   g.release.countDown();join(a);check(!accepted.get());enabled(false);eq(layout().cellX(),9);
  });
  run("blocked old failure cannot revoke newer successful publication",()->{
   Gate g=new Gate();ContentResolver.values=recentsPrefs();ContentResolver.afterSnapshot=()->{g.block();throw new IllegalStateException("late failure");};
   AtomicBoolean ok=new AtomicBoolean(true);Thread a=new Thread(()->ok.set(HomeLayoutNativeEndpointOS4.refreshFromProvider()));a.start();g.entered();
   ContentResolver.afterSnapshot=()->{};publish(recentsPrefs());g.release.countDown();join(a);check(!ok.get());enabled(true);
  });
  run("pause revokes Recents and rejected reload requires live new query",()->{
   publish(recentsPrefs());check(HomeLayoutNativeEndpointOS4.pauseForHotReload());unavailable();
   HomeLayoutNativeEndpointOS4.resumeAfterRejectedHotReload();set("refresher",new Thread());unavailable();publish(recentsPrefs());enabled(true);
  });
  run("inflight provider cannot publish after reload retirement",()->{
   Gate g=new Gate();ContentResolver.values=recentsPrefs();ContentResolver.afterSnapshot=g::block;
   AtomicBoolean ok=new AtomicBoolean(true);Thread a=new Thread(()->ok.set(HomeLayoutNativeEndpointOS4.refreshFromProvider()));a.start();g.entered();
   check(HomeLayoutNativeEndpointOS4.pauseForHotReload());g.release.countDown();join(a);check(!ok.get());unavailable();
  });
  run("prefetch worker renews leases without a preference notification",()->{
   set("refresher",null);SystemClock.source=System::nanoTime;ContentResolver.values=recentsPrefs();
   Gate invalidation=new Gate();
   HomeLayoutNativeEndpointOS4.startCombinedPrefetch();
   try {
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
    while(!read().available()&&System.nanoTime()<until)Thread.sleep(20);
    enabled(true);long firstDeadline=read().deadline();
    while(read().deadline()<=firstDeadline&&System.nanoTime()<until)Thread.sleep(20);
    enabled(true);check(read().deadline()>firstDeadline);
    ContentResolver.values=recentsPrefs("allow_hook",false);ContentResolver.afterSnapshot=invalidation::block;
    ContentResolver.signalChange("content://com.sevtinge.hyperceiler.provider.sharedprefs/pref/boolean/allow_hook");
    invalidation.entered();unavailable();invalidation.release.countDown();
    for(int i=0;i<100&&!read().available();i++)Thread.sleep(10);
    enabled(false);
   } catch(InterruptedException e){throw new AssertionError(e);}
   finally {invalidation.release.countDown();check(HomeLayoutNativeEndpointOS4.pauseForHotReload());}
  });
  System.out.println("RESULT passes="+passed+" failures="+failed+"; production Java layout and Recents publication, simulated Android/clock; device NOT_TESTED");
  if(failed>0)System.exit(1);
 }
}
