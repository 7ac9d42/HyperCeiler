package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.common.utils.prefs.PrefsChangeObserver;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CleanProcessTextMenuTest {
    private static final String KEY = "prefs_key_system_framework_clean_process_text_apps";
    private static final String APP = "example.app";
    private static final String ORIGINAL = APP + "|0";
    private static final String DUAL = APP + "|999";
    private static final Context CONTEXT = new Context();
    private static final Map<String, Object> SERVICE = Map.of("mContext", CONTEXT, "mHandler", new Handler());
    private static final Intent PROCESS_TEXT = new Intent(Intent.ACTION_PROCESS_TEXT, null, null);

    private enum Probe { PRESENT, ABSENT, THROWS }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<ResolveInfo> resultWithApp() {
        return new ArrayList<>(List.of(new ResolveInfo(APP)));
    }

    @SuppressWarnings("unchecked")
    private static int checkQuery(String scenario, Intent intent, List<ResolveInfo> input, List<String> expected,
                                  int expectedCalls) {
        int before = BaseHook.dualQueries;
        var param = new HookParam(SERVICE, input, intent, null, null, null, null, null);
        BaseHook.hooks.get("queryIntentActivitiesInternal").after(param);
        var result = (List<ResolveInfo>) param.getResult();
        var packages = result == null ? null : result.stream().map(info -> info.activityInfo.packageName).toList();
        int calls = BaseHook.dualQueries - before;
        check(Objects.equals(packages, expected), scenario + ": expected " + expected + ", got " + packages);
        check(calls == expectedCalls, scenario + ": expected " + expectedCalls + " dual queries, got " + calls);
        System.out.printf("%s: candidates=%s, dual queries=%d%n", scenario, packages, calls);
        return calls;
    }

    private static void matrixRow(String combination, Set<String> selection, Set<Probe> retained, int expectedCalls) {
        PrefsBridge.values.put(KEY, selection);
        for (Probe probe : Probe.values()) {
            BaseHook.duals.clear();
            BaseHook.failingDuals.clear();
            if (probe == Probe.PRESENT) BaseHook.duals.add(APP);
            if (probe == Probe.THROWS) BaseHook.failingDuals.add(APP);
            checkQuery(combination + "/" + probe, PROCESS_TEXT, resultWithApp(),
                retained.contains(probe) ? List.of(APP) : List.of(), expectedCalls);
        }
    }

    private static void run(boolean baseline) {
        new CleanProcessTextMenu().init();
        BaseHook.hooks.get("systemReady").after(new HookParam(SERVICE, null));
        check(PrefsChangeObserver.instance != null, "systemReady did not install the selection observer");

        int before = BaseHook.dualQueries;
        int unneededProbe = baseline ? 1 : 0;
        var all = Set.of(Probe.values());
        System.out.println("Probe order: present, absent, throws");
        // An unrelated selection keeps this row on the nonempty-list path.
        matrixRow("hide neither", Set.of("other.app"), all, unneededProbe);
        matrixRow("hide original only", Set.of(ORIGINAL), Set.of(Probe.PRESENT), 1);
        matrixRow("hide dual only", Set.of(DUAL), all, unneededProbe);
        matrixRow("hide both", Set.of(ORIGINAL, DUAL), Set.of(), unneededProbe);
        System.out.println("Matrix total: " + (BaseHook.dualQueries - before) + " dual queries (12 cases)");

        int pmBefore = CONTEXT.packageManagerReads;
        PrefsBridge.values.remove(KEY);
        int emptySelection = checkQuery("empty selection", PROCESS_TEXT, resultWithApp(), List.of(APP), unneededProbe);
        PrefsBridge.values.put(KEY, Set.of(ORIGINAL, DUAL));
        int emptyResult = checkQuery("empty result", PROCESS_TEXT, new ArrayList<>(), List.of(), 0);
        if (!baseline) checkQuery("null result", PROCESS_TEXT, null, null, 0);
        int pmReads = CONTEXT.packageManagerReads - pmBefore;
        if (!baseline) check(pmReads == 0, "empty inputs must not fetch PackageManager");
        System.out.printf("Empty guards: selection=%d, empty result=%d dual queries; PackageManager reads=%d%n",
            emptySelection, emptyResult, pmReads);

        checkQuery("other action", new Intent(Intent.ACTION_VIEW, null, null), resultWithApp(), List.of(APP), 0);
        var bypass = new Intent(Intent.ACTION_PROCESS_TEXT, null, null);
        bypass.bypass = true;
        checkQuery("module bypass", bypass, resultWithApp(), List.of(APP), 0);

        PrefsBridge.cache.put(KEY, Set.of(DUAL));
        PrefsBridge.values.put(KEY, Set.of(APP, DUAL));
        PrefsChangeObserver.instance.dispatchStringSetChange(KEY);
        int observer = checkQuery("selection observer refresh (legacy bare package key)",
            PROCESS_TEXT, resultWithApp(), List.of(), unneededProbe);
        System.out.println("Observer refresh: " + observer + " dual queries");
    }

    public static void main(String[] args) {
        run(false);
    }

    public static final class Baseline {
        public static void main(String[] args) {
            run(true);
        }
    }
}
