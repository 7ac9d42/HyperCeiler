import com.sevtinge.hyperceiler.common.log.ModuleLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.app.Others.VariousThirdApps;
import com.sevtinge.hyperceiler.libhook.appbase.input.InputMethodClassLoaderDispatcher;
import com.sevtinge.hyperceiler.libhook.appbase.input.MiuiImeSupport;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.AppsTool;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import java.util.Set;

public class RecreationTest {
    // The stock manager resets this cache during onDestroy, then reuses its classloader.
    public static class Manager {
        static int sIsImeSupport = -1;
        static void onDestroy() { sIsImeSupport = -1; }
        static boolean isImeSupport() { sIsImeSupport = 0; return false; }
        static boolean addMiuiBottomView() { return sIsImeSupport == 1; }
    }
    public static class OlderManager {
        static int sIsImeSupport = 0;
        static void addMiuiBottomView() {}
    }
    public static class PredicateOnly {
        static boolean isImeSupport() { return false; }
    }
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static HookParam before(Class<?> owner, String name) {
        var param = new HookParam();
        BaseHook.hooks.get(owner.getName()+"."+name).before(param);
        return param;
    }
    static void route(String style, boolean selected) {
        BaseLoad.loaded.clear(); PrefsBridge.values.clear(); AppsTool.queries=0;
        if (style != null) PrefsBridge.values.put("various_unlock_ime_style", style);
        PrefsBridge.values.put("various_unlock_ime_apps", selected ? Set.of("test.ime") : Set.of());
        loadRoute();
    }
    static void loadRoute() {
        BaseHook.hooks.clear();
        PrefsBridge.miuiChecks=0; PrefsBridge.aospChecks=0;
        new VariousThirdApps().onPackageLoaded();
    }
    static int duplicateRoutes;
    static void countRoute(String label) {
        System.out.println(label + ": MIUI=" + PrefsBridge.miuiChecks + " AOSP=" + PrefsBridge.aospChecks);
        if (PrefsBridge.miuiChecks != 1 || PrefsBridge.aospChecks != 1) duplicateRoutes++;
    }
    public static void main(String[] args) {
        MiuiImeSupport.install(Manager.class);
        check(Manager.addMiuiBottomView());
        for (int i=0;i<100;i++) {
            Manager.onDestroy();
            check(Boolean.TRUE.equals(before(Manager.class,"isImeSupport").result));
            before(Manager.class,"addMiuiBottomView");
            check(Manager.addMiuiBottomView());
        }
        check(ModuleLog.logs.stream().filter(s->s.contains("restored support")).count()==1);
        MiuiImeSupport.install(OlderManager.class);
        OlderManager.sIsImeSupport=-1;
        before(OlderManager.class,"addMiuiBottomView");
        check(OlderManager.sIsImeSupport==1);
        MiuiImeSupport.install(PredicateOnly.class);
        check(Boolean.TRUE.equals(before(PredicateOnly.class,"isImeSupport").result));
        BaseHook.cleanups.forEach(Runnable::run);
        check(Manager.sIsImeSupport==-1 && OlderManager.sIsImeSupport==0);
        route("1",true);
        countRoute("selected MIUI");
        check(BaseLoad.loaded.contains("UnlockIme") && !BaseLoad.loaded.contains("MiAospIme") && AppsTool.queries==0);
        check(BaseHook.hooks.containsKey("loadDex"));
        route("2",true);
        countRoute("selected AOSP on next load");
        check(BaseLoad.loaded.contains("MiAospIme") && !BaseLoad.loaded.contains("UnlockIme") && AppsTool.queries==0);
        check(BaseHook.hooks.containsKey("loadDex"));
        route("0",true);
        countRoute("disabled");
        check(!BaseLoad.loaded.contains("UnlockIme") && !BaseLoad.loaded.contains("MiAospIme"));
        route("1",false);
        countRoute("unselected");
        check(!BaseLoad.loaded.contains("InputMethodClassLoaderDispatcher") && AppsTool.queries==1);
        route(null,true);
        PrefsBridge.values.put("various_unlock_ime",true);
        loadRoute();
        countRoute("legacy MIUI");
        check(BaseLoad.loaded.contains("UnlockIme") && !BaseLoad.loaded.contains("MiAospIme"));
        BaseLoad.loaded.clear();
        PrefsBridge.values.put("various_aosp_ime",true);
        PrefsBridge.values.put("various_aosp_ime_apps", Set.of(BaseLoad.pkg));
        loadRoute();
        countRoute("both legacy keys");
        check(BaseLoad.loaded.contains("UnlockIme") && BaseLoad.loaded.contains("MiAospIme"));
        BaseHook.hooks.clear();
        PrefsBridge.values.clear();
        PrefsBridge.values.put("add_clipboard_clear", true);
        new InputMethodClassLoaderDispatcher(false, false).init();
        check(!BaseHook.hooks.containsKey("loadDex"));

        BaseLoad.pkg = "com.baidu.input_mi";
        PrefsBridge.values.put("various_unlock_ime_style", "1");
        PrefsBridge.values.put("various_unlock_ime_apps", Set.of(BaseLoad.pkg));
        loadRoute();
        check(BaseHook.hooks.containsKey("loadDex"));
        var load = new HookParam();
        load.args = new Object[]{RecreationTest.class.getClassLoader(), "fixture"};
        BaseHook.hooks.get("loadDex").after(load);
        check(!BaseHook.hooks.containsKey("getSupportIme"));

        PrefsBridge.values.put("various_unlock_ime_show_all", true);
        loadRoute();
        BaseHook.hooks.get("loadDex").after(load);
        check(BaseHook.hooks.keySet().equals(Set.of("loadDex", "getSupportIme")));
        var result = new HookParam();
        BaseHook.hooks.get("getSupportIme").before(result);
        check(result.result.equals(java.util.List.of("fixture.ime")));
        check(duplicateRoutes == 0);
        System.out.println("IME recreation: " + checks + " checks passed (service recovery, routing, retired clipboard preference, SDK 37 IME list)");
    }
}
