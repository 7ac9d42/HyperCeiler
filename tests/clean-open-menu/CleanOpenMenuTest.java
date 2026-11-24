package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.view.View;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.common.utils.prefs.PrefType;
import com.sevtinge.hyperceiler.common.utils.prefs.PrefsChangeObserver;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.AppsTool.MimeType;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CleanOpenMenuTest {
    private static final String KEY = "prefs_key_system_framework_clean_open_apps";
    private static final Context CONTEXT = new Context();
    private static final Intent IMAGE = new Intent(Intent.ACTION_VIEW, "content", "image/png");

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<String> resolve(Intent intent, String... packages) {
        var results = new ArrayList<ResolveInfo>();
        for (String pkg : packages) results.add(new ResolveInfo(pkg));
        BaseHook.hooks.get("queryIntentActivitiesInternal").after(
            new HookParam(Map.of("mContext", CONTEXT), results, intent, null, null, null, null, null));
        return results.stream().map(info -> info.activityInfo.packageName).toList();
    }

    private static void notifyChange(PrefType type, String key) {
        PrefsChangeObserver.instance.onChange(type, null, key, null);
    }

    public static void main(String[] args) {
        new CleanOpenMenu().init();
        BaseHook.hooks.get("systemReady").after(new HookParam(Map.of("mContext", CONTEXT, "mHandler", new Handler()), null));
        check(resolve(IMAGE, "ordinary").equals(List.of("ordinary")), "empty selection changed the result");
        check(PrefsBridge.intReads == 0 && BaseHook.dualQueries == 0, "empty selection did unnecessary work");

        PrefsBridge.cache.put(KEY, Set.of("stale"));
        PrefsBridge.values.put(KEY, Set.of("normal", "dual|0", "both|0", "both|999", "onlyDual|999", "link"));
        PrefsBridge.values.put(KEY + "_link|0", MimeType.LINK);
        BaseHook.duals.addAll(List.of("dual", "both", "onlyDual"));
        notifyChange(PrefType.StringSet, KEY);
        check(resolve(IMAGE, "ordinary", "normal", "dual", "both", "onlyDual", "link")
            .equals(List.of("ordinary", "dual", "onlyDual", "link")), "MIME or dual-app filtering changed");
        check(PrefsBridge.intReads == 5, "only selected and applicable flags should be read");
        check(BaseHook.dualQueries == 2, "only original matches with no dual match should be queried");
        check(resolve(new Intent(Intent.ACTION_VIEW, "https", null), "link").isEmpty(), "link MIME mapping changed");

        PrefsBridge.values.put(KEY + "_normal|0", MimeType.DOCUMENT);
        PrefsBridge.cache.put(KEY + "_normal|0", MimeType.ALL);
        notifyChange(PrefType.Integer, KEY + "_normal|0");
        check(resolve(IMAGE, "normal").equals(List.of("normal")), "live MIME update used the stale flag");
        PrefsBridge.values.remove(KEY + "_normal|0");
        notifyChange(PrefType.Any, KEY + "_normal|0");
        check(resolve(IMAGE, "normal").isEmpty(), "MIME removal did not restore the default mask");

        var root = new View();
        root.children.put(1, new View());
        root.children.put(2, new View());
        var runner = BaseHook.hooks.get("run");
        runner.after(new HookParam(Map.of("mContext", CONTEXT, "mAimPackageName", "onlyDual",
            "mOriginalIntent", IMAGE, "mRootView", root), null));
        runner.after(new HookParam(Map.of("mContext", CONTEXT, "mAimPackageName", "both",
            "mOriginalIntent", IMAGE, "mRootView", root), null));
        check(root.children.get(1).clicks == 1 && root.children.get(2).clicks == 1,
            "XSpace must prefer the same side as before");

        var bypass = new Intent(Intent.ACTION_VIEW, "content", "image/png");
        bypass.bypass = true;
        check(resolve(bypass, "both").equals(List.of("both")), "internal bypass no longer honored");
        PrefsBridge.values.remove(KEY);
        notifyChange(PrefType.Any, KEY);
        check(resolve(IMAGE, "both").equals(List.of("both")), "selection reset did not clear the snapshot");
        System.out.println("CleanOpenMenu: live selection/mask updates, XSpace, MIME and query counts passed");
    }
}
