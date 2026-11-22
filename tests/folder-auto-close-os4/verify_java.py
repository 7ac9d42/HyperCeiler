from pathlib import Path
import re
import subprocess
import sys
import tempfile

D = Path(__file__).resolve().parent
W = D.parents[1]
sys.path.insert(0, str(D.parent))
from hyperceiler_test_paths import java_tool

java, javac = (str(java_tool(name)) for name in ('java', 'javac'))
if not (Path(java).is_file() and Path(javac).is_file()):
    java, javac = 'java', 'javac'


def run_java(out, sources, main):
    subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(out), *map(str, sources)], check=True)
    subprocess.run([java, '-ea', '-cp', str(out), main], check=True)


libhook = W / 'library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook'
endpoint_sources = [
    *sorted((W / 'tests/home-layout-native/stubs').rglob('*.java')),
    *sorted((W / 'tests/home-dock-window/stubs').rglob('*.java')),
    libhook / 'base/FrameworkHookGate.java',
    libhook / 'provider/HomeGesturePrefsSnapshot.java',
    libhook / 'provider/HomeLayoutPrefsSnapshot.java',
    libhook / 'rules/home/dock/HomeLayoutNativeEndpointOS4.java',
    D / 'FolderAutoCloseEndpointTest.java',
]
stubs = {
    'androidx/preference/Preference.java': '''package androidx.preference;
public class Preference {
 public String key;
 public boolean enabled=true;
 public String getKey(){return key;}
 public void setEnabled(boolean value){enabled=value;}
}''',
    'androidx/preference/PreferenceGroup.java': '''package androidx.preference;
import java.util.ArrayList;
import java.util.List;
public class PreferenceGroup extends Preference {
 private final List<Preference> children=new ArrayList<>();
 public void addPreference(Preference p){children.add(p);}
 public int getPreferenceCount(){return children.size();}
 public Preference getPreference(int i){return children.get(i);}
}''',
    'com/sevtinge/hyperceiler/libhook/utils/api/DeviceHelper.java': '''package com.sevtinge.hyperceiler.libhook.utils.api;
public class DeviceHelper {
 public static class System {
  public static float version;
  public static boolean isHyperOSVersion(float expected){return version==expected;}
 }
}''',
}
gate=W/'library/core/src/main/java/com/sevtinge/hyperceiler/hooker/home/HomeOS4AdaptationGate.java'
res={}
for kind,name in re.findall(r'R\.(\w+)\.(\w+)',gate.read_text(encoding='utf8')):res.setdefault(kind,set()).add(name)
stubs['com/sevtinge/hyperceiler/core/R.java']='package com.sevtinge.hyperceiler.core;public class R {'+''.join('public static class '+k+'{'+''.join('public static final int '+n+'='+str(i)+';' for i,n in enumerate(sorted(v),1))+'}' for k,v in res.items())+'}'
stubs['FolderAutoGateTest.java']='''import androidx.preference.*;import com.sevtinge.hyperceiler.hooker.home.HomeOS4AdaptationGate;import com.sevtinge.hyperceiler.core.R;import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper;
public class FolderAutoGateTest {public static void main(String[]a){int checks=0;for(float version:new float[]{3,4})for(boolean prior:new boolean[]{false,true}){DeviceHelper.System.version=version;PreferenceGroup g=new PreferenceGroup();Preference auto=new Preference();auto.key="prefs_key_home_folder_auto_close";auto.enabled=prior;g.addPreference(auto);Preference other=new Preference();other.key="prefs_key_home_other_allow_share_apk";g.addPreference(other);HomeOS4AdaptationGate.apply(g,R.xml.home_other_new);if(auto.enabled!=prior)throw new AssertionError("dependency overwritten");checks++;if(other.enabled!=(version!=4))throw new AssertionError("other gate changed");checks++;}System.out.println("FOLDER_AUTO_CLOSE_UI="+checks+" checks; failed=0");}}'''

with tempfile.TemporaryDirectory(prefix='hc-folder-auto-close-java-') as temp:
    out = Path(temp)
    run_java(out / 'endpoint', endpoint_sources, 'com.sevtinge.hyperceiler.libhook.rules.home.dock.FolderAutoCloseEndpointTest')
    gate_sources = [gate]
    for name, source in stubs.items():
        path = out / 'stubs' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding='utf-8')
        gate_sources.append(path)
    run_java(out / 'gate', gate_sources, 'FolderAutoGateTest')
