/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.gesture;

import android.database.Cursor;
import com.sevtinge.hyperceiler.libhook.provider.HomeGesturePrefsSnapshot;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;

public final class QuickSwitchPrefsTest {
    private static final String ROOT = "content://com.sevtinge.hyperceiler.provider.sharedprefs";
    private static final String QUICK = "prefs_key_home_navigation_quick_back";
    private static int checks, failures;

    private static final class Hook {
        /* PRODUCTION FIELDS */
        final Deque<Runnable> cleanup = new ArrayDeque<>();
        void registerHotReloadCleanup(Runnable action) { cleanup.addFirst(action); }
        void closeGeneration() { while (!cleanup.isEmpty()) cleanup.removeFirst().run(); }
        static void log(String message, Exception error) { }
        /* PRODUCTION METHODS */
    }

    private record Uri(URI uri) {
        static Uri parse(String value) { return new Uri(URI.create(value)); }
        static Uri withAppendedPath(Uri base, String path) { return parse(base.uri + "/" + path); }
        List<String> getPathSegments() {
            return Arrays.stream(uri.getPath().split("/")).filter(s -> !s.isEmpty()).toList();
        }
    }

    private static final class HandlerThread {
        final Queue<Runnable> queue = new ArrayDeque<>();
        boolean quitting;
        HandlerThread(String name) { }
        void start() { }
        HandlerThread getLooper() { return this; }
        void quitSafely() { quitting = true; }
    }

    private record Handler(HandlerThread thread) {
        boolean post(Runnable callback) { return !thread.quitting && thread.queue.add(callback); }
        void drain() { while (!thread.queue.isEmpty()) thread.queue.remove().run(); }
    }

    private static class ContentObserver {
        final Handler handler;
        ContentObserver(Handler handler) { this.handler = handler; }
        void dispatchChange(Uri uri) { handler.post(() -> onChange(false, uri)); }
        public void onChange(boolean self, Uri uri) { onChange(self); }
        public void onChange(boolean self) { }
    }

    private static final class Context {
        final Map<String, Object> values = new HashMap<>();
        ContentObserver observer;
        int queries;
        Runnable duringQuery;
        String getPackageName() { return "com.android.systemui"; }
        Context getContentResolver() { return this; }
        void registerContentObserver(Uri uri, boolean descendants, ContentObserver callback) {
            if (!uri.equals(Uri.parse(ROOT)) || !descendants || observer != null) throw new AssertionError("observer registration");
            observer = callback;
        }
        void unregisterContentObserver(ContentObserver callback) {
            if (observer != callback) throw new AssertionError("observer unregistration");
            observer = null;
        }
        Cursor query(Uri uri, Object projection, Object selection, Object args, Object sort) {
            if (!uri.equals(Uri.parse(ROOT + "/home_gestures"))) throw new AssertionError("unexpected snapshot route: " + uri);
            queries++;
            if (duringQuery != null) duringQuery.run();
            return HomeGesturePrefsSnapshot.cursor(() -> values);
        }
        void change(String path) { observer.dispatchChange(path == null ? null : Uri.parse(ROOT + path)); }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) { failures++; System.out.println("FAIL " + label); }
    }

    private static HomeQuickSwitchState active(Hook hook) {
        var state = new HomeQuickSwitchState(1000, hook.revision, 1, 0, 4098, 1, 1, 1, 1200);
        hook.receivers.put(new Object(), state);
        hook.trackers.put(new Object(), state);
        return state;
    }

    public static void main(String[] args) {
        var hook = new Hook();
        var app = new Context();
        app.values.put(QUICK, true);
        hook.install(app);
        var handler = app.observer.handler;
        handler.drain();
        check(app.queries == 1 && hook.enabled, "initial installed refresh");
        var state = active(hook);
        long revision = hook.revision;
        int queries = app.queries;
        String[] unrelated = {"/boolean/prefs_key_system_ui_status_bar_follow_enable",
            "/pref/boolean/prefs_key_system_ui_status_bar_follow_enable",
            "/integer/prefs_key_home_gesture_up_swipe_action", "/pref/integer/prefs_key_home_gesture_up_swipe_action",
            "/boolean/prefs_key_other_home_navigation_quick_back", "/pref/boolean/prefs_key_other_home_navigation_quick_back"};
        for (String path : unrelated) app.change(path);
        handler.drain();
        System.out.println("unrelated notifications=" + unrelated.length + " home_gestures queries=" + (app.queries - queries));
        check(app.queries == queries, "unrelated notifications never reach Provider");
        check(hook.revision == revision && !state.retired && hook.trackers.containsValue(state), "unrelated notifications preserve current gesture");

        state = active(hook);
        app.values.put(QUICK, false);
        queries = app.queries;
        app.change("/boolean/" + QUICK); handler.drain();
        check(app.queries == queries + 1 && !hook.enabled && state.retired, "typed quick-back notification retires old gesture and disables claim");
        app.values.put(QUICK, true);
        app.change("/pref/boolean/" + QUICK); handler.drain();
        check(app.queries == queries + 2 && hook.enabled, "aggregate keyed quick-back notification re-enables claim");
        app.values.remove(QUICK);
        app.change("/pref/string/" + QUICK); handler.drain();
        check(!hook.enabled && app.queries == queries + 3, "unknown-type reset can arrive as another typed path");
        for (String reset : new String[]{"", "/pref", "/home_gestures", null}) {
            app.values.put(QUICK, true);
            queries = app.queries; app.change(reset); handler.drain();
            check(app.queries == queries + 1 && hook.enabled, "refresh from scope notification " + reset);
        }
        app.values.clear(); queries = app.queries;
        app.change(null); handler.drain();
        check(app.queries == queries + 1 && !hook.enabled, "all-key reset restores stock");

        queries = app.queries; revision = hook.revision;
        app.values.put(QUICK, true); app.change("/boolean/" + QUICK);
        hook.closeGeneration(); handler.drain();
        check(app.observer == null && handler.thread.quitting, "hot reload unregisters and stops worker");
        check(app.queries == queries && hook.revision == revision && !hook.enabled, "queued notification after close does not query or publish");

        var inFlight = new Hook(); var racing = new Context();
        racing.values.put(QUICK, true); inFlight.install(racing);
        var racingHandler = racing.observer.handler;
        racing.duringQuery = inFlight::closeGeneration;
        racingHandler.drain();
        check(racing.queries == 1 && racing.observer == null && !inFlight.enabled && inFlight.revision == 0,
            "close during read fences publication without cancelling the in-flight query");
        System.out.println("QuickSwitch settings checks=" + checks + " failures=" + failures);
        if (failures != 0) System.exit(1);
    }
}
