package com.sevtinge.hyperceiler.libhook.rules.home.dock;
public class HomeLayoutNativeEndpointOS4 {
 public record RecentsBarPreference(boolean available,boolean enabled,long deadline,long revision){}
 public static final RecentsBarPreference NO_RECENTS_BAR_PREFERENCE=new RecentsBarPreference(false,false,0,0);
 public static RecentsBarPreference pref=new RecentsBarPreference(true,true,Long.MAX_VALUE,1);
 public static RecentsBarPreference recentsBarPreference(){return pref;}
 public static boolean isCurrentRecentsBarPreference(RecentsBarPreference value){
  return value.available() && value.revision()==pref.revision() && android.os.SystemClock.elapsedRealtimeNanos()<value.deadline();
 }
 public static Integer observation;
 public static Runnable listener;
 public static java.util.function.LongConsumer superseded;
 public static int requests,renewals;
 public static long cooldown;
 public static Object wmLock;
 public static Runnable beforeAdmission;
 public static int readRecentsBarObservation(){
  return observation!=null?observation:pref.available()?(pref.enabled()?1:0):-1;
 }
 public static long requestRecentsBarRefresh(boolean overview,java.util.function.LongConsumer retry){
  return requestRecentsBarRefresh(overview,retry,null);
 }
 public static long requestRecentsBarRefresh(boolean overview,java.util.function.LongConsumer retry,java.util.function.BooleanSupplier current){
  if(Thread.holdsLock(wmLock))throw new AssertionError("Provider demand under WMS lock");
  var before=beforeAdmission;beforeAdmission=null;
  if(before!=null)before.run();
  if(current!=null&&!current.getAsBoolean())return 0;
  requests++;if(overview)renewals++;
  superseded=retry;
  return cooldown;
 }
 public static void setRecentsPublicationListener(Runnable callback){listener=callback;}
 public static void clearRecentsPublicationListener(Runnable callback){if(listener==callback)listener=null;}
}