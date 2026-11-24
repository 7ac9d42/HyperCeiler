#!/usr/bin/env python3
"""Compile the shipping native scene adapter/recognizer with an observable original receiver."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2];HERE=Path(__file__).resolve().parent
source=(ROOT/'app/src/main/cpp/targets/home/home_gestures_os4.cpp').read_text()
def block(name):
 at=source.index(name);i=source.index('{',at);end=i+1;depth=1
 while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[at:end]
a=source.index('struct Api {');b=source.index('void *on_create(')
fixture=(HERE/'fixture.inc').read_text();at=fixture.index('void install_api()')
text=fixture[:at]+source[a:b]+fixture[at:]
worker=(HERE/'worker_fixture.inc').read_text()
worker+='\n'+source[source.index('void *action_worker('):source.index('template<class F> void forward(')]
worker+='\n'+block('extern "C" void hc_workspace_double_emit(')+'\n'
text+='\n#ifdef TEST_ACTION_WORKER\n'+worker+'\n#endif\n'
for name in ('template<class F> void forward(', 'void hc_workspace_scene_ready(', 'void hc_workspace_gestures_ready(', 'extern "C" void hc_workspace_scene('):text+='\n'+block(name)+'\n'
with tempfile.TemporaryDirectory(prefix='hc-touch-lifecycle-')as work:
 work=Path(work);src=work/'test.cpp';binary=work/'test'
 for test,flags in (('test.inc',[]),('worker_test.inc',['-DTEST_ACTION_WORKER'])):
  src.write_text(text+(HERE/test).read_text())
  subprocess.run(['c++','-std=c++20','-O2','-pthread','-DHYPERCEILER_DOCK_NATIVE_MOTION','-I'+str(ROOT/'app/src/main/cpp'),*flags,str(src),'-o',str(binary)],check=True,timeout=60)
  subprocess.run([str(binary)],check=True,timeout=15)
