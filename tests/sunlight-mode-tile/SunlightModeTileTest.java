// SPDX-License-Identifier: AGPL-3.0-or-later
package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter.tiles;

import android.content.ContentResolver.Registration;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;

import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileContext;
import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileUtils;

import java.util.List;

public final class SunlightModeTileTest {
    private static final String OBSERVER_FIELD = "sunlight_content_observer";

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static List<Registration> registrationsFor(ContentObserver observer) {
        return List.of(
            new Registration(new Uri("screen_brightness"), false, observer),
            new Registration(new Uri("screen_brightness_custom_mode"), false, observer),
            new Registration(new Uri("sunlight_mode"), false, observer)
        );
    }

    public static void main(String[] args) {
        verifyListening();
        verifyCleanup();
    }

    private static void verifyListening() {
        TileUtils.cleanups.clear();
        var rule = new SunlightModeTile();
        var context = new Context();
        var host = new TileContext.Host();
        var resolver = context.getContentResolver();

        var firstContext = new TileContext(context, host);
        rule.onListeningChanged(firstContext, true);
        ContentObserver first = firstContext.getAdditionalField(OBSERVER_FIELD);
        check(first != null && resolver.registrations.equals(registrationsFor(first)), "First true must register one observer for all three URIs");
        check(resolver.unregistrations.isEmpty() && resolver.active.equals(resolver.registrations), "First true unexpectedly removed a registration");
        check(TileUtils.cleanups.size() == 1, "Expected one cleanup registration for the first observer");
        System.out.println("PASS first true: 1 observer, 3 URI registrations, 0 unregistrations");

        var secondContext = new TileContext(context, host);
        rule.onListeningChanged(secondContext, true);
        ContentObserver second = secondContext.getAdditionalField(OBSERVER_FIELD);
        check(second == first && resolver.registrations.equals(registrationsFor(first)), "Second true must not register a new observer or URI");
        check(TileUtils.cleanups.size() == 1, "Second true must not register another cleanup");
        check(resolver.unregistrations.isEmpty() && resolver.active.equals(resolver.registrations), "Second true unexpectedly removed a registration");

        var stopContext = new TileContext(context, host);
        rule.onListeningChanged(stopContext, false);
        check(resolver.registrations.equals(registrationsFor(first)) && resolver.unregistrations.equals(List.of(first)),
            "False must unregister only the saved observer without new registrations");
        check(resolver.active.isEmpty(), "Unexpected registrations remaining after false");
        check(stopContext.getAdditionalField(OBSERVER_FIELD) == null, "False must clear the saved observer");
        System.out.println("PASS false: 1 unregistration, " + resolver.active.size() + " URI registrations remaining");
    }

    private static void verifyCleanup() {
        TileUtils.cleanups.clear();
        var context = new Context();
        var ctx = new TileContext(context, new TileContext.Host());
        var resolver = context.getContentResolver();
        var rule = new SunlightModeTile();

        rule.onListeningChanged(ctx, true);
        ContentObserver first = ctx.getAdditionalField(OBSERVER_FIELD);
        rule.onListeningChanged(ctx, false);
        rule.onListeningChanged(ctx, true);
        ContentObserver second = ctx.getAdditionalField(OBSERVER_FIELD);
        check(second != null && first != second && TileUtils.cleanups.size() == 2, "Reopening must register a fresh observer and cleanup");
        TileUtils.cleanups.get(0).run();
        check(resolver.active.equals(registrationsFor(second)) && resolver.unregistrations.equals(List.of(first))
            && ctx.getAdditionalField(OBSERVER_FIELD) == second, "Old cleanup must leave the current observer registered");

        TileUtils.cleanups.get(1).run();
        check(resolver.active.isEmpty() && resolver.unregistrations.equals(List.of(first, second))
            && ctx.getAdditionalField(OBSERVER_FIELD) == null, "Current cleanup must unregister and clear the observer");
        new SunlightModeTile().onListeningChanged(ctx, true);
        ContentObserver restored = ctx.getAdditionalField(OBSERVER_FIELD);
        check(restored != null && restored != second && resolver.active.equals(registrationsFor(restored)),
            "Replaying listening after cleanup must register a fresh observer");
        System.out.println("PASS registered cleanup: old cleanup leaves current observer intact; active cleanup permits listening replay");
    }
}
