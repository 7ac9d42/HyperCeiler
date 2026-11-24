"""Exercise the production terms sheet on a host JVM without network or Android."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from host_java_fixture import run_fixture


PKG = 'com.sevtinge.hyperceiler.provision'
WIDGET = PKG + '.widget'
ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    'android.content.Context': '''public class Context {
        public static final String CONNECTIVITY_SERVICE = "connectivity";
        public Object getSystemService(String name) { return new android.net.ConnectivityManager(); }
        public String getString(int id, Object... args) {
            return args.length == 0 ? "[Retry](hyperceiler://refresh) %URL%" : "Code " + args[0];
        }
        public void startActivity(Intent intent) { throw new AssertionError("unexpected activity"); }
    }''',
    'android.content.Intent': 'public class Intent { public static final String ACTION_VIEW = "view"; public Intent(String a, android.net.Uri uri) {} }',
    'android.content.res.Resources': '''public class Resources {
        public static class Metrics { public final float density = 1; }
        public Metrics getDisplayMetrics() { return new Metrics(); }
    }''',
    'android.graphics.Canvas': 'public class Canvas { public void drawRect(float a, float b, float c, float d, Paint p) {} }',
    'android.graphics.Color': 'public class Color { public static final int TRANSPARENT = 0; public static int parseColor(String value) { return 0; } }',
    'android.graphics.Paint': 'public class Paint { public int getColor() { return 0; } public void setColor(int color) {} }',
    'android.graphics.Typeface': 'public class Typeface { public static final int BOLD = 1; }',
    'android.net.ConnectivityManager': 'public class ConnectivityManager { public NetworkInfo getActiveNetworkInfo() { return null; } }',
    'android.net.NetworkInfo': 'public class NetworkInfo { public boolean isConnected() { throw new AssertionError("network was used"); } }',
    'android.net.Uri': 'public class Uri { public static Uri parse(String value) { return new Uri(); } }',
    'android.os.Build': '''public class Build {
        public static class VERSION { public static int SDK_INT = 37; }
        public static class VERSION_CODES { public static final int P = 28; }
    }''',
    'android.os.Handler': '''public class Handler {
        public static final java.util.ArrayDeque<Runnable> QUEUE = new java.util.ArrayDeque<>();
        public Handler(Looper looper) {}
        public boolean post(Runnable task) { return QUEUE.add(task); }
    }''',
    'android.os.Looper': 'public class Looper { public static Looper getMainLooper() { return new Looper(); } }',
    'android.text.Layout': 'public class Layout { public int getWidth() { return 0; } }',
    'android.text.Spanned': 'public interface Spanned { int SPAN_EXCLUSIVE_EXCLUSIVE = 33; }',
    'android.text.SpannableStringBuilder': '''public class SpannableStringBuilder implements CharSequence {
        private final StringBuilder text = new StringBuilder();
        public final java.util.List<android.text.style.ClickableSpan> links = new java.util.ArrayList<>();
        public int length() { return text.length(); }
        public char charAt(int i) { return text.charAt(i); }
        public CharSequence subSequence(int a, int b) { return text.subSequence(a, b); }
        public SpannableStringBuilder append(String s) { text.append(s); return this; }
        public void replace(int a, int b, String s) { text.replace(a, b, s); }
        public void delete(int a, int b) { text.delete(a, b); }
        public void setSpan(Object span, int a, int b, int flags) {
            if (span instanceof android.text.style.ClickableSpan link) links.add(link);
        }
        public String toString() { return text.toString(); }
    }''',
    'android.text.TextPaint': 'public class TextPaint extends android.graphics.Paint { public void setUnderlineText(boolean on) {} }',
    'android.text.method.LinkMovementMethod': 'public class LinkMovementMethod { public static LinkMovementMethod getInstance() { return new LinkMovementMethod(); } }',
    'android.text.style.ClickableSpan': '''public abstract class ClickableSpan {
        public abstract void onClick(android.view.View view);
        public void updateDrawState(android.text.TextPaint text) {}
    }''',
    'android.text.style.LeadingMarginSpan': '''import android.graphics.*;
        public interface LeadingMarginSpan {
            int getLeadingMargin(boolean first);
            void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int base, int bottom,
                CharSequence text, int start, int end, boolean first, android.text.Layout layout);
            class Standard { public Standard(int margin) {} }
        }''',
    'android.text.style.AbsoluteSizeSpan': 'public class AbsoluteSizeSpan { public AbsoluteSizeSpan(int size, boolean dip) {} }',
    'android.text.style.BulletSpan': 'public class BulletSpan { public BulletSpan(int... options) {} }',
    'android.text.style.ForegroundColorSpan': 'public class ForegroundColorSpan { public ForegroundColorSpan(int color) {} }',
    'android.text.style.StyleSpan': 'public class StyleSpan { public StyleSpan(int style) {} }',
    'android.util.AttributeSet': 'public interface AttributeSet {}',
    'android.view.View': '''public class View {
        public static final int VISIBLE = 0, INVISIBLE = 4, GONE = 8;
        private final android.content.Context context;
        private int visibility;
        private final java.util.Map<Integer, View> children = new java.util.HashMap<>();
        public View(android.content.Context context) { this.context = context; }
        public android.content.Context getContext() { return context; }
        public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
        public void setVisibility(int visibility) { this.visibility = visibility; }
        public int getVisibility() { return visibility; }
        public void add(int id, View view) { children.put(id, view); }
        @SuppressWarnings("unchecked") public <T extends View> T findViewById(int id) { return (T) children.get(id); }
    }''',
    'android.widget.FrameLayout': 'public class FrameLayout {}',
    'android.widget.ProgressBar': 'public class ProgressBar extends android.view.View { public ProgressBar(android.content.Context context) { super(context); } }',
    'android.widget.TextView': '''public class TextView extends android.view.View {
        private CharSequence text = "";
        public TextView(android.content.Context context, android.util.AttributeSet attrs) { super(context); }
        public void setText(CharSequence text) { this.text = text; }
        public CharSequence getText() { return text; }
        public void setTextSize(float size) {}
        public void setLineSpacing(float add, float mul) {}
        public void setPadding(int a, int b, int c, int d) {}
        public void setMovementMethod(android.text.method.LinkMovementMethod method) {}
        public void setHighlightColor(int color) {}
    }''',
    'androidx.annotation.NonNull': 'public @interface NonNull {}',
    'androidx.fragment.app.FragmentActivity': 'public class FragmentActivity extends android.content.Context {}',
    'fan.appcompat.R': 'public class R { public static class attr { public static final int textColorList = 1, textColorListSecondary = 2; } }',
    'fan.internal.utils.AttributeResolver': 'public class AttributeResolver { public static int resolveColor(android.content.Context context, int id) { return id; } }',
    'fan.provision.OobeUtils': '''public class OobeUtils {
        private static int code;
        public static void refreshSecureSixDigit() { code++; }
        public static String getSecureSixDigit() { return Integer.toString(code); }
    }''',
    'fan.bottomsheet.BottomSheetBehavior': '''public class BottomSheetBehavior<T> {
        public static final int STATE_EXPANDED = 3;
        public void setState(int state) {}
        public void setForceFullHeight(boolean value) {}
        public void setModeConfig(int mode) {}
        public void setDraggable(boolean value) {}
        public void setSkipHalfExpanded(boolean value) {}
        public void setSkipCollapsed(boolean value) {}
        public void setFixedHeightRatioEnabled(boolean value) {}
    }''',
    'fan.bottomsheet.BottomSheetModal': f'''import android.view.View;
        import android.widget.*;
        import {WIDGET}.MarkdownView;
        import {PKG}.R;
        public class BottomSheetModal {{
            public interface OnDismissListener {{ void onDismiss(); }}
            public static BottomSheetModal last;
            private final View root;
            private OnDismissListener listener;
            public BottomSheetModal(androidx.fragment.app.FragmentActivity context) {{
                last = this;
                root = new View(context);
                root.add(R.id.progress_bar, new ProgressBar(context));
                root.add(R.id.markdown, new MarkdownView(context, null));
                root.add(R.id.verification_code_tip, new TextView(context, null));
            }}
            public View getRootView() {{ return root; }}
            public void setContentView(int id) {{}}
            public void setDragHandleViewEnabled(boolean enabled) {{}}
            public BottomSheetBehavior<FrameLayout> getBehavior() {{ return new BottomSheetBehavior<>(); }}
            public void setOnDismissListener(OnDismissListener listener) {{ this.listener = listener; }}
            public void show() {{}}
            public void dismiss() {{ if (listener != null) listener.onDismiss(); }}
        }}''',
    PKG + '.R': '''public class R {
        public static class id { public static final int progress_bar = 1, markdown = 2, verification_code_tip = 3; }
        public static class layout { public static final int fragment_bottom_sheet_web = 1; }
        public static class string { public static final int provision_get_md_failed = 2, provision_terms_of_use_verification_code_tip = 3; }
    }''',
}


def main():
    source = ROOT / 'library/provision/src/main/java' / WIDGET.replace('.', '/')
    sources = [source / name for name in ('MarkdownView.java', 'TermsAndStatementBottomSheet.java')]
    sources.append(Path(__file__).with_name('TermsSheetTest.java'))
    run_fixture(sources, STUBS, WIDGET + '.TermsSheetTest')


if __name__ == '__main__':
    main()
