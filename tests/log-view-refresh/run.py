"""Compile the production log fragment against a host-only UI/DAO fixture."""

from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from host_java_fixture import run_fixture


APP = 'com.sevtinge.hyperceiler'
LOG = APP + '.log'
DB = LOG + '.db'
WIDGET = APP + '.widget'
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/java' / LOG.replace('.', '/')

STUBS = {
    'android.content.Context': 'public class Context { public Context getApplicationContext() { return this; } public String getString(int id) { return ""; } }',
    'android.os.Bundle': 'public class Bundle { private int value; public void putInt(String key, int value) { this.value = value; } public int getInt(String key, int fallback) { return value; } }',
    'android.os.Looper': 'public class Looper { public static Looper getMainLooper() { return new Looper(); } }',
    'android.os.Handler': '''
        public class Handler {
            public static final java.util.concurrent.BlockingQueue<Runnable> QUEUE = new java.util.concurrent.LinkedBlockingQueue<>();
            public Handler(Looper looper) {}
            public boolean post(Runnable task) { return QUEUE.add(task); }
        }''',
    'android.util.Log': 'public class Log { public static int e(String tag, String text, Throwable error) { return 0; } }',
    'android.view.View': '''
        public class View {
            public static final int VISIBLE = 0, GONE = 8;
            private final java.util.Map<Integer, View> children = new java.util.HashMap<>();
            public void add(int id, View view) { children.put(id, view); }
            @SuppressWarnings("unchecked") public <T extends View> T findViewById(int id) { return (T) children.get(id); }
            public void setVisibility(int value) {}
        }''',
    'android.view.ViewGroup': 'public class ViewGroup extends View {}',
    'android.view.LayoutInflater': '''
        public class LayoutInflater {
            private final View view;
            public LayoutInflater(View view) { this.view = view; }
            public View inflate(int id, ViewGroup container, boolean attach) { return view; }
        }''',
    'android.widget.ImageView': 'public class ImageView extends android.view.View { public void setImageResource(int id) {} }',
    'android.widget.TextView': 'public class TextView extends android.view.View { public void setText(int id) {} }',
    'androidx.fragment.app.Fragment': '''
        import android.content.Context;
        import android.os.Bundle;
        import android.view.*;
        public class Fragment {
            private final Context context = new Context();
            private Bundle args;
            private View view;
            private boolean visible = true;
            public void setArguments(Bundle args) { this.args = args; }
            public Bundle getArguments() { return args; }
            public void setUserVisibleHint(boolean visible) { this.visible = visible; }
            public boolean getUserVisibleHint() { return visible; }
            public Context requireContext() { return context; }
            public Object getActivity() { return context; }
            public Object requireActivity() { return context; }
            public View getView() { return view; }
            public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) { return null; }
            public void onViewCreated(View view, Bundle state) {}
            public void onDestroyView() {}
            public void mount(View root) { view = onCreateView(new LayoutInflater(root), null, null); onViewCreated(view, null); }
            public void unmount() { onDestroyView(); view = null; }
        }''',
    'androidx.recyclerview.widget.LinearLayoutManager': 'public class LinearLayoutManager { public LinearLayoutManager(android.content.Context context) {} }',
    'androidx.recyclerview.widget.RecyclerView': f'''
        public class RecyclerView extends android.view.View {{
            public {LOG}.LogAdapter adapter;
            public void setAdapter({LOG}.LogAdapter adapter) {{ this.adapter = adapter; }}
            public void setLayoutManager(LinearLayoutManager manager) {{}}
            public void addItemDecoration(Object decoration) {{}}
        }}''',
    'fan.recyclerview.card.CardItemDecoration': 'public class CardItemDecoration { public CardItemDecoration(android.content.Context context) {} }',
    'fan.springback.view.SpringBackLayout': f'public class SpringBackLayout extends android.view.View {{ public {WIDGET}.PullViewHelper helper; }}',
    WIDGET + '.PullToRefreshListener': 'public interface PullToRefreshListener { void onRefresh(); void onLoadMore(); void onEnterPrivate(); }',
    WIDGET + '.PullViewHelper': '''
        public class PullViewHelper {
            private PullToRefreshListener listener;
            public int completions;
            public PullViewHelper(android.content.Context context, PullToRefreshListener listener) { this.listener = listener; }
            public void attachSpringBackLayout(fan.springback.view.SpringBackLayout view) { view.helper = this; }
            public void setEnablePullRefresh(boolean enabled) {}
            public void setPullListener(PullToRefreshListener listener) { this.listener = listener; }
            public void onPullRefreshComplete() { completions++; }
            public void pull() { if (listener != null) listener.onRefresh(); }
        }''',
    DB + '.LogEntry': 'public class LogEntry {}',
    DB + '.LogRepository': '''
        public class LogRepository {
            public static LogDao dao;
            public static LogRepository getInstance() { return new LogRepository(); }
            public LogDao getDao() { return dao; }
        }''',
    LOG + '.LogAdapter': f'''
        import {DB}.LogEntry;
        import java.util.List;
        public class LogAdapter {{
            public String published;
            private int count;
            public LogAdapter(android.content.Context context, java.util.function.Consumer<LogEntry> click) {{}}
            public void updateData(List<LogEntry> logs, String keyword) {{ published = keyword; count = logs.size(); }}
            public int getItemCount() {{ return count; }}
        }}''',
    LOG + '.LogDetailBottomSheet': f'public class LogDetailBottomSheet {{ public LogDetailBottomSheet(Object activity) {{}} public void showRecord({DB}.LogEntry entry) {{}} }}',
    LOG + '.LogSyncDiagnostics': f'public class LogSyncDiagnostics {{ public static boolean checkRoot({DB}.LogDao dao) {{ return false; }} }}',
    LOG + '.XposedLogLoader': 'public class XposedLogLoader { public static volatile int imports; public static void syncLogsToDatabaseSync(android.content.Context context) { imports++; } }',
    LOG + '.LogManager': 'public class LogManager { public static void startHealthCheck(android.content.Context context, Object unused) {} }',
}
for annotation in ('NonNull', 'Nullable', 'StringRes'):
    STUBS['androidx.annotation.' + annotation] = f'public @interface {annotation} {{}}'
STUBS['androidx.room.Dao'] = 'public @interface Dao {}'
STUBS['androidx.room.Insert'] = 'public @interface Insert { int onConflict() default 0; }'
STUBS['androidx.room.OnConflictStrategy'] = 'public class OnConflictStrategy { public static final int REPLACE = 1, IGNORE = 2; }'
STUBS['androidx.room.Query'] = 'public @interface Query { String value(); }'


def main():
    sources = [SOURCE / name for name in ('LogListFragment.java', 'LogLevelFilter.java', 'db/LogDao.java')]
    ids = {}
    for path in sources:
        for kind, name in re.findall(r'\bR\.(\w+)\.(\w+)', path.read_text(encoding='utf-8')):
            ids.setdefault(kind, set()).add(name)
    STUBS[APP + '.R'] = 'public class R {' + ''.join(
        f'public static class {kind} {{' + ''.join(
            f'public static final int {name} = {i};' for i, name in enumerate(sorted(names), 1)
        ) + '}' for kind, names in ids.items()
    ) + '}'
    sources.append(Path(__file__).with_name('RefreshTest.java'))
    run_fixture(sources, STUBS, LOG + '.RefreshTest')


if __name__ == '__main__':
    main()
