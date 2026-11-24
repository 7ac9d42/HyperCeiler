package fixture;

import android.os.AsyncTask;
import android.os.Handler;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.rules.systemui.navigation.BackGestureWidthOS4;

public final class RuleTest {
    private static int checks, failures;

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) { failures++; System.out.println("FAIL " + label); }
    }

    private static void drain() {
        do {
            Handler.drain();
            AsyncTask.drain();
        } while (!Handler.q.isEmpty());
    }

    public static void main(String[] args) {
        new BackGestureWidthOS4().init();
        var handler = new EdgeBackGestureHandler();
        var c = BaseHook.ctx.resolver;
        var remote = PrefsBridge.remote;
        check(handler.mEdgeWidthLeft == 97, "stock widths before initial read");
        drain();
        check(c.queries == 1 && handler.mEdgeWidthLeft == 194 && handler.mEdgeWidthRight == 194,
            "initial physical read applies to both edges");

        int before = c.queries;
        c.value = 300; remote.change(); c.change(); drain();
        int queued = c.queries - before;
        check(queued == 1 && handler.mEdgeWidthLeft == 291, "same change queued together reads once");

        before = c.queries;
        c.value = 200; remote.change(); drain(); c.change(); drain();
        int separate = c.queries - before;
        check(separate == 2 && handler.mEdgeWidthLeft == 194, "late physical callback can be an independent change");

        before = c.queries; c.peak = 0;
        c.value = 300; remote.change(); Handler.drain();
        c.duringQuery = () -> { c.change(); Handler.drain(); AsyncTask.drain(); };
        drain();
        int overlapping = c.queries - before, peak = c.peak;
        System.out.println("same commit: queued=" + queued + " separated=" + separate
            + " while reading=" + overlapping + " concurrently active peak=" + peak);
        check(overlapping == 2 && peak == 1 && handler.mEdgeWidthLeft == 291,
            "in-flight notification requires one follow-up, not a parallel query");

        before = c.queries;
        c.value = 200; c.change(); Handler.drain();
        c.duringQuery = () -> { c.value = 400; remote.change(); Handler.drain(); };
        AsyncTask.runNext(); Handler.drain();
        check(handler.mEdgeWidthLeft == 291, "superseded in-flight value cannot publish");
        drain();
        check(c.queries == before + 2 && handler.mEdgeWidthLeft == 388,
            "distinct update arriving during read is not lost");

        handler.reset(50);
        check(handler.mEdgeWidthLeft == 200, "new resource width is the baseline");
        c.value = 100; c.change(); drain();
        check(handler.mEdgeWidthLeft == 50, "physical-only delivery updates width");
        c.value = 300; remote.change(); drain();
        check(handler.mEdgeWidthLeft == 150, "remote-only delivery still reads physical truth");
        c.fail = true; c.change(); drain();
        check(handler.mEdgeWidthLeft == 150, "provider failure retains last successful value");
        c.fail = false;

        c.value = 400; c.change(); Handler.drain(); AsyncTask.runNext();
        c.change(); remote.change();
        before = c.queries;
        for (int i = BaseHook.cleanup.size() - 1; i >= 0; --i) BaseHook.cleanup.get(i).run();
        drain();
        check(c.observer == null && remote.listener == null && handler.mEdgeWidthLeft == 50 && c.queries == before,
            "hot reload restores stock widths and fences both callbacks and queued publication");

        System.out.println("SYSTEMUI_RULE=" + checks + " checks; failed=" + failures);
        if (failures != 0) System.exit(1);
    }
}
