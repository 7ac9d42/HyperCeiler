/* SPDX-License-Identifier: AGPL-3.0-or-later */
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import com.android.server.audio.AudioService;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.systemframework.volume.VolumeDefaultStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public final class VolumeObserverTest {
    private static final String KEY = "prefs_key_system_framework_default_volume_stream";
    private static final Uri URI = Uri.parse("content://com.sevtinge.hyperceiler.provider.sharedprefs/string/" + KEY);
    private static int checks, failures;

    private static final class Store {
        String value = "3";
        int registrations;
        final List<SharedPreferences.OnSharedPreferenceChangeListener> listeners = new ArrayList<>();
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "registerOnSharedPreferenceChangeListener" -> {
                        registrations++;
                        listeners.add((SharedPreferences.OnSharedPreferenceChangeListener) args[0]);
                        yield null;
                    }
                    case "unregisterOnSharedPreferenceChangeListener" -> { listeners.remove(args[0]); yield null; }
                    case "getString" -> KEY.equals(args[0]) && value != null ? value : args[1];
                    default -> throw new AssertionError(method);
                };
            });

        void change(String key) { for (var listener : List.copyOf(listeners)) listener.onSharedPreferenceChanged(prefs, key); }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) { failures++; System.out.println("FAIL " + label); }
    }

    public static void main(String[] args) {
        var remote = new Store();
        var context = new Context(remote.prefs);
        var audio = new AudioService(context);
        PrefsBridge.initForHook(remote.prefs);
        new VolumeDefaultStream().init();
        boolean chosen = true;
        for (int i = 0; i < 100; i++) chosen &= BaseHook.choose(audio) == 3;
        check(chosen, "override unchanged across 100 requests");
        System.out.println("100 requests: Context reflections=" + BaseHook.lookups + ", Handlers=" + Handler.allocations
            + ", remote registrations=" + remote.registrations + ", physical registrations=" + context.resolver.registrations);
        check(BaseHook.lookups == 1 && Handler.allocations == 1 && remote.registrations == 1
            && context.resolver.registrations == 0, "one active subscription; no idle physical observer with remote");
        PrefsBridge.putHookCache(KEY, "3");
        remote.value = "2"; remote.change("other"); context.resolver.notifyChange(URI, null); Handler.drain();
        check(BaseHook.choose(audio) == 3, "unrelated key and suppressed physical notifications leave remote cache alone");
        remote.change(KEY); Handler.drain();
        check(BaseHook.choose(audio) == 2, "remote key notification reloads configured stream");
        remote.value = null; remote.change(KEY); Handler.drain();
        check(BaseHook.choose(audio) == 0, "removal retains existing stream-zero semantics");
        remote.value = "5"; remote.change(KEY);
        BaseHook.retire(); Handler.drain();
        check(remote.listeners.isEmpty() && context.resolver.active.isEmpty(), "retired generation holds no registrations");
        check(PrefsBridge.getStringAsInt(KEY, 0) == 0 && BaseHook.choose(audio) == 9,
            "queued callback cannot republish and retired dispatch keeps stock");
        PrefsBridge.initForHook(remote.prefs);
        int before = remote.registrations;
        new VolumeDefaultStream().init();
        check(BaseHook.choose(audio) == 5 && remote.registrations == before + 1 && remote.listeners.size() == 1,
            "next generation binds once and reads current preference");
        BaseHook.retire();

        var physical = new Store();
        var fallback = new Context(physical.prefs);
        PrefsBridge.initForApp(fallback);
        PrefsBridge.clearHookCache();
        new VolumeDefaultStream().init();
        var other = new AudioService(fallback);
        check(BaseHook.choose(other) == 3 && fallback.resolver.registrations == 1 && physical.registrations == 0,
            "without a remote listener bind the existing typed URI");
        PrefsBridge.putHookCache(KEY, "3");
        physical.value = "5"; fallback.resolver.notifyChange(URI, null); Handler.drain();
        check(BaseHook.choose(other) == 5 && fallback.resolver.registrations == 1, "physical-only update refreshes without re-registering");
        physical.value = null; fallback.resolver.notifyChange(URI, null); Handler.drain();
        check(BaseHook.choose(other) == 0, "physical key removal restores prior zero semantics");
        physical.value = "2"; fallback.resolver.notifyChange(URI, null);
        BaseHook.retire(); Handler.drain();
        check(fallback.resolver.active.isEmpty() && PrefsBridge.getStringAsInt(KEY, 0) == 0,
            "unregister physical observer and fence pending cache writes");
        System.out.println("Volume default stream checks=" + checks + " failures=" + failures);
        if (failures != 0) System.exit(1);
    }
}
