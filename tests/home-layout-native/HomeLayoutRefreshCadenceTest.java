/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

import android.content.ContentResolver;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Counts calls of the real endpoint worker, not just synchronous cache reads. */
public final class HomeLayoutRefreshCadenceTest {
    private static final String PREF = "content://com.sevtinge.hyperceiler.provider.sharedprefs/pref";
    private static final String TOP = "prefs_key_home_layout_workspace_padding_top";

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = HomeLayoutNativeEndpointOS4.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(BooleanSupplier ready, String message) throws InterruptedException {
        final long deadline = System.nanoTime() + 3_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(ready.getAsBoolean(), message);
    }

    private static int delta() {
        var snapshot = HomeLayoutNativeEndpointOS4.readReadyPreferences();
        return snapshot == null ? -1 : snapshot.knobDeltaDp()[2];
    }

    public static void main(String[] args) throws Exception {
        ContentResolver.setSpecs((String[][]) field("PREF_KEYS").get(null));
        ContentResolver.setRows(Map.of(TOP + "_enable", 1, TOP, 60));
        final AtomicLong now = new AtomicLong(10_000_000_000L);
        SystemClock.source = now::get;
        var endpoint = new HomeLayoutNativeEndpointOS4();
        endpoint.receive(new android.os.Parcel("android.view.IWindowManager"), 0);
        check(field("refresher").get(null) == null && ContentResolver.queryCount() == 0,
            "install or unauthenticated request started a query");

        try {
            await(() -> delta() == 30, "cold authenticated path did not load");
            Thread worker = (Thread) field("refresher").get(null);
            await(() -> worker.getState() == Thread.State.WAITING, "worker kept a timed idle wait");
            check(ContentResolver.registrationCount() == 1, "observer not registered once");
            for (int pulse = 0; pulse < 20; ++pulse) {
                now.addAndGet(1_000_000_000L);
                check(delta() == 30, "steady layout request lost cache");
                HomeLayoutNativeEndpointOS4.requestRecentsBarPreference(false, null);
            }
            ContentResolver.signalChange(PREF + "/integer/prefs_key_unrelated_setting");
            check(delta() == 30 && ContentResolver.queryCount() == 1, "idle heartbeat or unrelated write queried provider");

            ContentResolver.putRow(TOP, 90);
            for (int index = 0; index < 20; ++index) ContentResolver.signalChange(PREF + "/integer/" + TOP);
            now.addAndGet(1_000_000_000_000L); // Elapsed time across simulated suspend.
            Thread.sleep(200);
            check(worker.getState() == Thread.State.WAITING && ContentResolver.queryCount() == 1,
                "configuration write without a client queried provider");
            await(() -> delta() == 60, "dirty snapshot did not refresh on the next request");
            await(() -> worker.getState() == Thread.State.WAITING, "worker did not settle after update");
            check(ContentResolver.queryCount() == 2, "write burst did not coalesce");

            ContentResolver.failAt(0, "query");
            ContentResolver.signalChange(PREF + "/integer/" + TOP);
            await(() -> delta() == 60 && ContentResolver.queryCount() == 1, "initial failed attempt missing");
            await(() -> worker.getState() == Thread.State.WAITING, "failed query armed a timer");
            Thread.sleep(350);
            check(ContentResolver.queryCount() == 1, "an unattended failure retried");
            await(() -> delta() == 60 && ContentResolver.queryCount() == 2, "first requested retry missing");
            await(() -> delta() == 60 && ContentResolver.queryCount() == 3, "second requested retry missing");
            await(() -> worker.getState() == Thread.State.WAITING, "last quick retry armed a timer");
            check(field("nextQueryAtNanos").getLong(null) - System.nanoTime() > 25_000_000_000L,
                "failure burst did not enter cooldown");
            for (int index = 0; index < 100; ++index) {
                check(delta() == 60, "failure lost last-good layout");
                HomeLayoutNativeEndpointOS4.requestRecentsBarPreference(false, null);
            }
            check(ContentResolver.queryCount() == 3, "repeated requests bypassed cooldown");
        } finally {
            check(HomeLayoutNativeEndpointOS4.pauseForHotReload(), "worker failed to retire");
        }
        check(ContentResolver.unregistrationCount() == 1, "observer leaked after retirement");
        HomeLayoutNativeEndpointOS4.resumeAfterRejectedHotReload();
        ContentResolver.setRows(Map.of());
        try {
            check(field("refresher").get(null) == null && ContentResolver.queryCount() == 0,
                "reload resume restarted without a request");
            await(() -> delta() == 0, "demand after reload did not load an explicit reset");
            check(ContentResolver.queryCount() == 1, "reload demand duplicated query");
        } finally {
            check(HomeLayoutNativeEndpointOS4.pauseForHotReload(), "resumed worker failed to retire");
        }
        System.out.println("Home layout demand: PASS (cold, steady, suspended, change, retry, reload)");
    }
}
