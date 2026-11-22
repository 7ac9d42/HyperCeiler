from pathlib import Path
import re
import subprocess
import sys
import tempfile

D = Path(__file__).resolve().parent
W = D.parents[1]
R = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else W
sys.path.insert(0, str(D.parent))
from hyperceiler_test_paths import java_tool

java, javac = (str(java_tool(name)) for name in ('java', 'javac'))
if not (Path(java).is_file() and Path(javac).is_file()):
    java, javac = 'java', 'javac'


def run_java(out, sources, main):
    subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(out), *map(str, sources)], check=True)
    subprocess.run([java, '-ea', '-cp', str(out), main], check=True)


libhook = R / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook'
endpoint_sources = [
    *sorted((W / 'tests/home-layout-native/stubs').rglob('*.java')),
    *sorted((W / 'tests/home-dock-window/stubs').rglob('*.java')),
    libhook / 'base/FrameworkHookGate.java',
    libhook / 'provider/HomeGesturePrefsSnapshot.java',
    libhook / 'provider/HomeLayoutPrefsSnapshot.java',
    libhook / 'rules/home/dock/HomeLayoutNativeEndpointOS4.java',
    D / 'BackGestureEndpointTest.java',
]

base = R / 'library/core/src/main/java/com/sevtinge/hyperceiler/hooker/home'
sources = [base / (name + '.java') for name in ('HomeGestureSettings', 'HomeOS4AdaptationGate')]
stubs = {
    'androidx/preference/Preference.java': '''package androidx.preference;
public class Preference {
 public String key;
 public boolean enabled=true, visible=true;
 public OnPreferenceChangeListener change;
 public String getKey(){return key;}
 public void setEnabled(boolean value){enabled=value;}
 public void setVisible(boolean value){visible=value;}
 public void setOnPreferenceChangeListener(OnPreferenceChangeListener listener){change=listener;}
 public interface OnPreferenceChangeListener {boolean onPreferenceChange(Preference p, Object value);}
}''',
    'androidx/preference/PreferenceGroup.java': '''package androidx.preference;
import java.util.*;
public class PreferenceGroup extends Preference {
 private final List<Preference> children=new ArrayList<>();
 public void addPreference(Preference p){children.add(p);}
 public int getPreferenceCount(){return children.size();}
 public Preference getPreference(int i){return children.get(i);}
}''',
    'androidx/preference/PreferenceCategory.java': 'package androidx.preference; public class PreferenceCategory extends PreferenceGroup {}',
    'androidx/preference/SwitchPreference.java': 'package androidx.preference; public class SwitchPreference extends Preference {}',
    'fan/preference/SeekBarPreferenceCompat.java': 'package fan.preference; public class SeekBarPreferenceCompat extends androidx.preference.Preference {}',
    'android/content/SharedPreferences.java': 'package android.content; public interface SharedPreferences {boolean getBoolean(String key, boolean fallback);}',
    'com/sevtinge/hyperceiler/libhook/utils/api/DeviceHelper.java': '''package com.sevtinge.hyperceiler.libhook.utils.api;
public class DeviceHelper {
 public static class System {public static boolean isHyperOSVersion(float version){return version==4;}}
}''',
    'com/sevtinge/hyperceiler/dashboard/DashboardFragment.java': '''package com.sevtinge.hyperceiler.dashboard;
import android.content.SharedPreferences;
import androidx.preference.*;
import java.util.*;
public abstract class DashboardFragment {
 public final Map<String,Preference> prefs=new HashMap<>();
 public final PreferenceGroup screen=new PreferenceGroup();
 @SuppressWarnings("unchecked")
 public <T extends Preference> T findPreference(String key){return (T)prefs.get(key);}
 public PreferenceGroup getPreferenceScreen(){return screen;}
 public SharedPreferences getSharedPreferences(){return (key, fallback) -> {
  if (!key.equals("prefs_key_home_gesture_enable")) throw new AssertionError(key);
  return fallback;
 };}
 public abstract int getPreferenceScreenResId();
 public abstract void initPrefs();
}''',
}
res = {}
for path in sources:
    for kind, name in re.findall(r'R\.(\w+)\.(\w+)', path.read_text(encoding='utf-8')):
        res.setdefault(kind, set()).add(name)
stubs['com/sevtinge/hyperceiler/core/R.java'] = 'package com.sevtinge.hyperceiler.core; public class R {' + ''.join(
    f'public static class {kind} {{' + ''.join(
        f'public static final int {name}={i};' for i, name in enumerate(sorted(names), 1)
    ) + '}' for kind, names in res.items()
) + '}'
stubs['BackGestureGateTest.java'] = '''
import androidx.preference.*;
import fan.preference.SeekBarPreferenceCompat;
import com.sevtinge.hyperceiler.hooker.home.HomeGestureSettings;

public class BackGestureGateTest {
 static int checks;
 static void check(boolean result, String name) {checks++;if(!result)throw new AssertionError(name);}
 static <T extends Preference> T add(HomeGestureSettings f, String key, T preference) {
  preference.key="prefs_key_home_"+key;
  f.prefs.put(preference.key, preference);
  f.screen.addPreference(preference);
  return preference;
 }
 public static void main(String[] args) {
  var settings=new HomeGestureSettings();
  var enable=add(settings,"gesture_enable",new SwitchPreference());
  var quick=add(settings,"navigation_quick_back",new SwitchPreference());
  var height=add(settings,"navigation_back_area_height",new SeekBarPreferenceCompat());
  var width=add(settings,"navigation_back_area_width",new SeekBarPreferenceCompat());
  var actions=add(settings,"gesture_actions",new PreferenceCategory());
  settings.initPrefs();
  check(quick.enabled && quick.visible,"quick back stays available");
  check(height.enabled && height.visible,"back area height stays available");
  check(width.enabled && width.visible,"back area width stays available");
  check(!actions.visible,"gesture actions start hidden when disabled");
  check(enable.change.onPreferenceChange(enable,true) && actions.visible,"enabling gestures shows actions");
  System.out.println("BACK_GESTURE_UI="+checks+" checks; failed=0");
 }
}'''

with tempfile.TemporaryDirectory(prefix='hc-back-gesture-java-') as temp:
    out = Path(temp)
    run_java(out / 'endpoint', endpoint_sources, 'com.sevtinge.hyperceiler.libhook.rules.home.dock.BackGestureEndpointTest')
    for name, source in stubs.items():
        path = out / 'stubs' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8')
        sources.append(path)
    run_java(out / 'gate', sources, 'BackGestureGateTest')
