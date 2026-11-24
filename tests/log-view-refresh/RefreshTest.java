package com.sevtinge.hyperceiler.log;

import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.sevtinge.hyperceiler.R;
import com.sevtinge.hyperceiler.log.db.LogDao;
import com.sevtinge.hyperceiler.log.db.LogEntry;
import com.sevtinge.hyperceiler.log.db.LogRepository;
import fan.springback.view.SpringBackLayout;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RefreshTest {
    private record Query(String keyword, int imports, CompletableFuture<List<LogEntry>> answer) {
        void complete(boolean hasLogs) {
            answer.complete(hasLogs ? List.of(new LogEntry()) : List.of());
        }
    }

    private static final BlockingQueue<Query> queries = new LinkedBlockingQueue<>();
    private static final AtomicInteger existenceChecks = new AtomicInteger();

    private static final class Page extends View {
        final RecyclerView recycler = new RecyclerView();
        final SpringBackLayout spring = new SpringBackLayout();

        Page() {
            add(R.id.spring_back, spring);
            add(R.id.recyclerView, recycler);
            add(R.id.loading_layout, new View());
            add(R.id.empty_layout, new View());
            add(R.id.empty_text, new TextView());
            add(R.id.empty_icon, new ImageView());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Query take(String keyword, int imports) throws InterruptedException {
        Query query = queries.poll(3, TimeUnit.SECONDS);
        check(query != null, "expected query for " + keyword);
        check(keyword.equals(query.keyword()), "unexpected query: " + query.keyword());
        check(imports == query.imports(), "unexpected import count for " + keyword + ": " + query.imports());
        return query;
    }

    private static void dispatch() throws InterruptedException {
        Runnable callback = Handler.QUEUE.poll(3, TimeUnit.SECONDS);
        check(callback != null, "expected a main thread callback");
        callback.run();
    }

    public static void main(String[] args) throws Exception {
        LogRepository.dao = (LogDao) Proxy.newProxyInstance(LogDao.class.getClassLoader(),
            new Class<?>[]{LogDao.class}, (proxy, method, params) -> switch (method.getName()) {
                case "queryLogs" -> {
                    var answer = new CompletableFuture<List<LogEntry>>();
                    queries.add(new Query((String) params[3], XposedLogLoader.imports, answer));
                    yield answer.orTimeout(3, TimeUnit.SECONDS).join();
                }
                case "hasModuleLogs" -> {
                    existenceChecks.incrementAndGet();
                    yield true;
                }
                default -> throw new AssertionError(method);
            });

        var fragment = LogListFragment.newInstance(1);
        var first = new Page();
        fragment.mount(first);
        var initial = take("", 1);
        fragment.applyFilter("intermediate", "ALL", "");
        first.spring.helper.pull();
        fragment.applyFilter("latest", "ALL", "");
        check(queries.poll(100, TimeUnit.MILLISECONDS) == null, "queries should not overlap");
        initial.complete(true);
        dispatch();
        check(first.recycler.adapter.published == null, "stale results reached the adapter");
        take("latest", 2).complete(true);
        dispatch();
        check("latest".equals(first.recycler.adapter.published), "latest filter was not displayed");
        check(first.spring.helper.completions == 1, "queued pull refresh did not finish exactly once");
        check(existenceChecks.get() == 0, "nonempty results should not need an existence query");

        first.spring.helper.pull();
        var oldPull = take("latest", 3);
        var oldAdapter = first.recycler.adapter;
        fragment.unmount();
        check(first.recycler.adapter == null, "destroyed view retained adapter");
        var second = new Page();
        fragment.mount(second);
        first.spring.helper.pull(); // detached listener must not enqueue into second view
        oldPull.complete(false);
        dispatch();
        check(second.recycler.adapter.published == null, "old view result reached new adapter");
        check(oldAdapter.getItemCount() == 1, "old view result reached old adapter");
        check(first.spring.helper.completions == 1 && second.spring.helper.completions == 0,
            "old view result completed the wrong pull");
        take("latest", 4).complete(false);
        dispatch();
        check("latest".equals(second.recycler.adapter.published), "recreated view did not load");
        check(existenceChecks.get() == 2, "empty filtered queries must retain module existence checks");

        second.spring.helper.pull();
        take("latest", 5).answer().completeExceptionally(new IllegalStateException("DAO failure"));
        dispatch();
        check(second.spring.helper.completions == 1, "failure left pull refresh running");
        fragment.applyFilter("recovered", "ALL", "");
        take("recovered", 5).complete(true);
        dispatch();
        check("recovered".equals(second.recycler.adapter.published), "failure blocked the next query");
        check(queries.isEmpty() && Handler.QUEUE.isEmpty(), "unexpected pending work");
        System.out.println("Log refresh: coalescing, import ordering, view recreation, pull completion and failure recovery passed");
    }
}
