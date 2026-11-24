/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.recent;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.SystemClock;
import com.sevtinge.hyperceiler.common.log.ModuleLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.base.OriginalArgumentsHook;
import com.sevtinge.hyperceiler.libhook.provider.HomeLayoutPrefsSnapshot;
import com.sevtinge.hyperceiler.libhook.utils.api.ContextUtils;
import com.sevtinge.hyperceiler.libhook.utils.api.ThreadPoolManager;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Filter the live Shell AIDL reply, not an OEM-named controller or the system task store. */
public final class HomeHideRecentCardsOS4 extends BaseHook {
    private static final String TAG = "HyperCeiler.RecentsCards";
    private static final Uri ROOT = Uri.parse("content://com.sevtinge.hyperceiler.provider.sharedprefs");
    private record Selection(Set<String> packages, int launcherAppId) {
        Selection { packages = Set.copyOf(packages); }
        boolean owns(int uid) { return uid >= 10000 && launcherAppId >= 10000 && uid % 100000 == launcherAppId; }
    }
    private final Object publication = new Object();
    private final Object contextGate = new Object();
    private final Set<Class<?>> bound = new HashSet<>();
    private static final class Listener {
        final int appId;
        final IInterface target;
        final IBinder binder;
        final Method changed;
        Set<String> packages;

        Listener(int appId, IInterface target, Method changed, Set<String> packages) {
            this.appId = appId;
            this.target = target;
            this.binder = target.asBinder();
            this.changed = changed;
            this.packages = packages;
        }

        boolean alive() { return binder != null && binder.isBinderAlive(); }
    }
    private final ConcurrentHashMap<Object, Listener> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean appliedReported = new AtomicBoolean();
    private final Set<Integer> requestedAppIds = new HashSet<>();
    private boolean queued;
    private final ThreadLocal<ArrayDeque<Selection>> calls = ThreadLocal.withInitial(ArrayDeque::new);
    private static final Selection STOCK = new Selection(Set.of(), -1);
    private volatile Selection selection;
    private volatile Context context;
    private volatile boolean closed;
    private volatile long retryAt;
    private long revision;
    private int knownLauncherAppId = -1;
    private Set<String> lastPackages = Set.of();
    private String lastFailure = "";
    private ContentObserver observer;
    private Method query;
    private HomeRecentCardsFilter.Plan plan;

    private static void observeMethod(Method method, IMethodHook callback) {
        chain(method, OriginalArgumentsHook.of(callback));
    }
    @Override public void init() {
        if (!"com.android.systemui".equals(Application.getProcessName())) return;
        registerHotReloadCleanup(this::close);
        try {
            // Public Shell Binder contract. Concrete implementation is learned
            // from its actual Stub construction, including vendor subclasses.
            Class<?> contract = findClass("com.android.wm.shell.recents.IRecentTasks");
            Class<?> stub = findClass(contract.getName() + "$Stub");
            if (!contract.isInterface() || !contract.isAssignableFrom(stub) || !Binder.class.isAssignableFrom(stub))
                throw new IllegalStateException("recent-task Stub contract");
            // Optimized OEM jars can move interface methods onto abstract Stub
            // while leaving IRecentTasks as a marker. Its live Stub ABI is authoritative.
            query = HomeRecentCardsFilter.query(stub);
            plan = new HomeRecentCardsFilter.Plan(query.getReturnType(), Class.forName("android.app.TaskInfo"));
            hookAllConstructors(stub, new IMethodHook() {
                @Override public void after(HookParam p) {
                    if (closed || p.getThrowable() != null) return;
                    try { bind(p.getThisObject().getClass()); }
                    catch (Exception | LinkageError e) { failure("runtime implementation", e); }
                }
            });
            hookMethod(Application.class.getDeclaredMethod("attach", Context.class), new IMethodHook() {
                @Override public void after(HookParam p) {
                    if (p.getThrowable() == null && p.getThisObject() instanceof Context app) attach(app);
                }
            });
            Context current = ContextUtils.getContextNoError(ContextUtils.FLAG_CURRENT_APP);
            if (current != null) attach(current);
            ModuleLog.i(TAG, "IRecentTasks reply contract ready, waiting for runtime implementation, original task permissions retained");
        } catch (Exception | LinkageError e) { closed = true; failure("contract unavailable, stock retained", e); }
    }
    private void bind(Class<?> type) throws Exception {
        synchronized (bound) {
            if (closed || bound.contains(type)) return;
            if (bound.size() >= 16) throw new IllegalStateException("implementation budget");
            Method implementation = type.getMethod(query.getName(), query.getParameterTypes());
            if (Modifier.isAbstract(implementation.getModifiers()) || implementation.getReturnType() != query.getReturnType())
                throw new NoSuchMethodException("concrete AIDL reply");
            implementation.setAccessible(true);
            bound.add(type); // Do not install duplicate hooks after a partial backend failure.
            observeMethod(implementation, new IMethodHook() {
                @Override public void before(HookParam p) {
                    Selection current = selection;
                    calls.get().push(!closed && current != null && current.owns(Binder.getCallingUid()) ? current : STOCK);
                }
                @Override public void after(HookParam p) {
                    var stack = calls.get();
                    Selection captured = stack.isEmpty() ? STOCK : stack.pop();
                    if (stack.isEmpty()) calls.remove();
                    if (closed || p.getThrowable() != null) return;
                    Selection current = selection;
                    if (current == null && SystemClock.elapsedRealtime() >= retryAt) {
                        int appId = Binder.getCallingUid() % 100000;
                        if (appId >= 10000) requestRefresh(appId);
                    }
                    if (captured == STOCK || captured != current || captured.packages().isEmpty()) return;
                    try {
                        Object original = p.getResult(), filtered = plan.filter(original, captured.packages());
                        if (selection != captured || closed || filtered == original) return;
                        p.setResult(filtered);
                        if (appliedReported.compareAndSet(false, true)) ModuleLog.i(TAG,
                            "selected-app cards filtered from launcher reply, task store and processes unchanged");
                    } catch (Exception | LinkageError e) { failure("reply declined, stock retained", e); }
                }
            });
            try {
                bindListener(type, "registerRecentTasksListener", true);
                bindListener(type, "unregisterRecentTasksListener", false);
            } catch (ReflectiveOperationException unavailable) { failure("listener contract unavailable", unavailable); }
            try {
                // ExternalInterfaceBinder retires old Stub instances whenever
                // the launcher reconnects. Do not retain their remote listeners.
                Method invalidate = type.getMethod("invalidate");
                if (invalidate.getReturnType() == void.class && !Modifier.isStatic(invalidate.getModifiers())) {
                    invalidate.setAccessible(true);
                    hookMethod(invalidate, new IMethodHook() {
                        @Override public void after(HookParam p) {
                            if (p.getThrowable() == null) listeners.remove(p.getThisObject());
                        }
                    });
                }
            } catch (NoSuchMethodException absent) { /* Older Shell variants can unregister explicitly. */ }
            ModuleLog.i(TAG, "runtime recent-task implementation bound " + type.getName());
        }
    }
    private void bindListener(Class<?> type, String name, boolean register) throws Exception {
        Method selected = null;
        for (Method method : type.getMethods()) if (method.getName().equals(name) && method.getParameterCount() == 1
            && method.getReturnType() == void.class && IInterface.class.isAssignableFrom(method.getParameterTypes()[0])) {
            if (selected != null) throw new NoSuchMethodException("listener contract");
            selected = method;
        }
        if (selected == null) return; // Filtering still applies to every requested reply.
        selected.setAccessible(true);
        hookMethod(selected, new IMethodHook() {
            @Override public void after(HookParam p) {
                if (closed || p.getThrowable() != null) return;
                Object owner = p.getThisObject();
                if (register && p.getArgs()[0] instanceof IInterface listener) {
                    int appId = Binder.getCallingUid() % 100000;
                    if (appId < 10000) { listeners.remove(owner); return; }
                    try {
                        // The listener interface can be optimized too. Resolve
                        // its actual local Stub OR remote Proxy, not a Stub-only Method.
                        Method callback = listener.getClass().getMethod("onRecentTasksChanged");
                        if (callback.getReturnType() != void.class || Modifier.isStatic(callback.getModifiers()))
                            throw new NoSuchMethodException("listener callback");
                        callback.setAccessible(true);
                        synchronized (publication) {
                            if (closed) return;
                            listeners.entrySet().removeIf(entry -> !entry.getValue().alive());
                            if (!listeners.containsKey(owner) && listeners.size() >= 8) return;
                            listeners.put(owner, new Listener(appId, listener, callback,
                                selection == null ? null : selection.packages()));
                        }
                        requestRefresh(0);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        listeners.remove(owner);
                        failure("listener callback unavailable", e);
                    }
                } else listeners.remove(owner);
            }
        });
    }
    private void attach(Context app) {
        synchronized (contextGate) {
            if (closed || context != null || !"com.android.systemui".equals(app.getPackageName())) return;
            Context application = app.getApplicationContext();
            Context source = application == null ? app : application;
            observer = new ContentObserver(null) {
                @Override public void onChange(boolean self, Uri uri) {
                    String key = uri == null ? "" : uri.getLastPathSegment();
                    if (key == null || key.isEmpty() || key.equals("home_layout")
                        || key.endsWith(HomeLayoutPrefsSnapshot.RECENT_CARDS_KEY)
                        || key.endsWith("allow_hook")) {
                        synchronized (publication) {
                            if (closed) return;
                            ++revision; selection = null; knownLauncherAppId = -1;
                        }
                        requestRefresh(0);
                    }
                }
            };
            try {
                source.getContentResolver().registerContentObserver(ROOT, true, observer);
                context = source;
            } catch (Exception e) {
                try { source.getContentResolver().unregisterContentObserver(observer); } catch (RuntimeException ignored) { }
                failure("preference observer", e);
            }
        }
        requestRefresh(0);
    }
    private boolean hasLiveListener() {
        for (Listener listener : listeners.values()) if (listener.alive()) return true;
        return false;
    }
    private void requestRefresh(int appId) {
        synchronized (publication) {
            if (closed || context == null || selection != null || SystemClock.elapsedRealtime() < retryAt) return;
            if (appId >= 10000) {
                if (knownLauncherAppId >= 0 && knownLauncherAppId != appId) return;
                requestedAppIds.add(appId);
            }
            if (queued || (requestedAppIds.isEmpty() && !hasLiveListener())) return;
            queued = true;
        }
        try { ThreadPoolManager.execute(this::refresh); }
        catch (RuntimeException rejected) {
            synchronized (publication) { postponeRefresh(); queued = false; }
        }
    }
    private void postponeRefresh() {
        retryAt = SystemClock.elapsedRealtime() + 2000;
        requestedAppIds.clear();
    }
    private void refresh() {
        try {
            long identityRevision;
            synchronized (publication) {
                if (closed || (requestedAppIds.isEmpty() && !hasLiveListener())) return;
                identityRevision = revision;
            }
            int appId = context.getPackageManager().getApplicationInfo("com.miui.home", 0).uid % 100000;
            if (appId < 10000) {
                synchronized (publication) { postponeRefresh(); }
                return;
            }
            for (int attempt = 0; attempt < 4; ++attempt) {
                long ticket;
                synchronized (publication) {
                    if (closed) return;
                    if (revision == identityRevision) knownLauncherAppId = appId;
                    listeners.entrySet().removeIf(entry -> entry.getValue().appId != appId);
                    boolean requested = requestedAppIds.contains(appId);
                    requestedAppIds.clear();
                    if (!requested && !hasLiveListener()) return;
                    ticket = revision;
                }
                Selection next = readSelection(appId);
                boolean changed;
                synchronized (publication) {
                    if (closed) return;
                    if (next == null) { postponeRefresh(); return; }
                    if (revision != ticket) continue;
                    requestedAppIds.clear();
                    selection = next;
                    retryAt = 0;
                    changed = !lastPackages.equals(next.packages());
                    lastPackages = next.packages();
                }
                if (changed) {
                    appliedReported.set(false);
                    ModuleLog.i(TAG, "physical selection published count=" + next.packages().size());
                }
                listeners.forEach((owner, listener) -> notifyListener(owner, listener, next, changed));
                return;
            }
            synchronized (publication) { postponeRefresh(); }
        } catch (Exception | LinkageError e) {
            synchronized (publication) { postponeRefresh(); }
            failure("launcher identity unavailable", e);
        } finally {
            synchronized (publication) { queued = false; }
            requestRefresh(0);
        }
    }
    private void notifyListener(Object owner, Listener listener, Selection next, boolean changed) {
        if (closed || selection != next || listener.appId != next.launcherAppId()
            || listeners.get(owner) != listener || !listener.alive()) return;
        Set<String> previous = listener.packages;
        listener.packages = next.packages();
        if (previous == null ? !changed && next.packages().isEmpty() : previous.equals(next.packages())) return;
        try { listener.changed.invoke(listener.target); }
        catch (Exception e) { failure("listener invalidation", e); }
    }
    private Selection readSelection(int appId) {
        try (Cursor cursor = context.getContentResolver().query(Uri.withAppendedPath(ROOT, "home_layout"), null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !"schema".equals(cursor.getColumnName(0))
                || cursor.getInt(0) != HomeLayoutPrefsSnapshot.SCHEMA) return null;
            var extras = cursor.getExtras();
            if (extras == null || extras.getInt(HomeLayoutPrefsSnapshot.RECENT_CARDS_VERSION, 0) != 1) return null;
            var packages = HomeRecentCardsFilter.packages(HomeLayoutPrefsSnapshot.decodeTitleRecords(
                extras.getString(HomeLayoutPrefsSnapshot.RECENT_CARDS_KEY)));
            for (String key : new String[]{"allow_hook", "framework_api_allow_hook"}) {
                int at = cursor.getColumnIndex(key);
                if (at >= 0 && !cursor.isNull(at) && cursor.getInt(at) != 1) packages = Set.of();
            }
            return new Selection(packages, appId);
        } catch (Exception | LinkageError e) { failure("physical preferences unavailable", e); return null; }
    }
    private synchronized void failure(String stage, Throwable error) {
        String value = stage + ":" + error.getClass().getSimpleName();
        if (lastFailure.equals(value)) return;
        lastFailure = value; ModuleLog.w(TAG, value + ", original behavior retained");
    }
    private void close() {
        synchronized (publication) {
            closed = true; ++revision; selection = null;
            requestedAppIds.clear(); listeners.clear();
        }
        synchronized (contextGate) {
            Context current = context;
            if (current != null && observer != null) try { current.getContentResolver().unregisterContentObserver(observer); }
            catch (RuntimeException ignored) { }
        }
        synchronized (bound) { bound.clear(); }
    }
}
