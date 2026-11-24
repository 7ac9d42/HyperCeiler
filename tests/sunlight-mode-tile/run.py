# SPDX-License-Identifier: AGPL-3.0-or-later
"""Exercise the production SunlightModeTile listening callback on a local JVM."""

from pathlib import Path
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from host_java_fixture import run_fixture
from hyperceiler_test_paths import SOURCE_ROOT

HERE = Path(__file__).resolve().parent
APP = 'com.sevtinge.hyperceiler'
LIB = APP + '.libhook'
TILE = LIB + '.appbase.systemui'
PACKAGE = LIB + '.rules.systemui.controlcenter.tiles'
RULE = Path('library/libhook/src/main/java') / PACKAGE.replace('.', '/') / 'SunlightModeTile.java'

STUBS = {
    'androidx.annotation.NonNull': 'public @interface NonNull {}',
    'androidx.annotation.Nullable': 'public @interface Nullable {}',
    'android.os.Looper': 'public final class Looper {}',
    'android.os.Handler': 'public record Handler(Looper looper) {}',
    'android.net.Uri': 'public record Uri(String key) {}',
    'android.database.ContentObserver': '''import android.net.Uri;
        import android.os.Handler;
        public class ContentObserver {
            public ContentObserver(Handler handler) {}
            public void onChange(boolean selfChange, Uri uri) { throw new AssertionError("Notification dispatch is outside this fixture"); }
        }''',
    'android.content.ContentResolver': '''import android.database.ContentObserver;
        import android.net.Uri;
        import java.util.ArrayList;
        import java.util.List;
        public final class ContentResolver {
            public record Registration(Uri uri, boolean descendants, ContentObserver observer) {}
            public final List<Registration> registrations = new ArrayList<>();
            public final List<Registration> active = new ArrayList<>();
            public final List<ContentObserver> unregistrations = new ArrayList<>();
            public void registerContentObserver(Uri uri, boolean descendants, ContentObserver observer) {
                var entry = new Registration(uri, descendants, observer);
                registrations.add(entry);
                active.add(entry);
            }
            public void unregisterContentObserver(ContentObserver observer) {
                unregistrations.add(observer);
                if (!active.removeIf(entry -> entry.observer() == observer)) {
                    throw new AssertionError("Unregister called for an unknown observer");
                }
            }
        }''',
    'android.content.Context': '''import android.os.Looper;
        public final class Context {
            public static final int RECEIVER_EXPORTED = 2;
            private final ContentResolver resolver = new ContentResolver();
            public ContentResolver getContentResolver() { return resolver; }
            public Looper getMainLooper() { return new Looper(); }
            public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
                throw new AssertionError("Unexpected broadcast registration");
            }
            public void unregisterReceiver(BroadcastReceiver receiver) { throw new AssertionError("Unexpected broadcast removal"); }
        }''',
    'android.content.BroadcastReceiver': '''public abstract class BroadcastReceiver {
        public abstract void onReceive(Context context, Intent intent);
    }''',
    'android.content.ComponentName': 'public record ComponentName(String pkg, String cls) {}',
    'android.content.IntentFilter': 'public record IntentFilter(String action) {}',
    'android.content.Intent': '''public final class Intent {
        public static final String ACTION_MAIN = "android.intent.action.MAIN", ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";
        public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000, FLAG_ACTIVITY_RESET_TASK_IF_NEEDED = 0x00200000;
        public Intent(String action) { throw new AssertionError("Unexpected Intent"); }
        public String getAction() { throw new AssertionError("Unexpected Intent access"); }
        public void setFlags(int flags) { throw new AssertionError("Unexpected Intent write"); }
        public void setComponent(ComponentName component) { throw new AssertionError("Unexpected Intent write"); }
    }''',
    'android.provider.Settings': '''import android.content.ContentResolver;
        import android.net.Uri;
        public final class Settings {
            public static final class SettingNotFoundException extends Exception {}
            public static final class System {
                public static Uri getUriFor(String key) { return new Uri(key); }
                public static int getInt(ContentResolver resolver, String key) throws SettingNotFoundException {
                    if (!key.equals("screen_brightness_custom_mode")) throw new AssertionError("Unexpected Settings read: " + key);
                    return 0;
                }
                public static boolean putInt(ContentResolver resolver, String key, int value) {
                    throw new AssertionError("Unexpected Settings write: " + key);
                }
            }
        }''',
    APP + '.common.log.XposedLog': '''public final class XposedLog {
        public static void d(String tag, String message) {}
        public static void w(String tag, String message) { throw new AssertionError(message); }
        public static void e(String tag, String message, Throwable cause) { throw new AssertionError(message, cause); }
    }''',
    APP + '.common.utils.PrefsBridge': '''public final class PrefsBridge {
        public static int getStringAsInt(String key, int fallback) { throw new AssertionError("Initialization is outside this fixture"); }
        public static int getInt(String key, int fallback) { throw new AssertionError("Initialization is outside this fixture"); }
    }''',
    APP + '.common.utils.ShellUtils': '''public final class ShellUtils {
        public static String rootExecCmd(String command) { throw new AssertionError("Unexpected Shell request"); }
    }''',
    LIB + '.R': '''public final class R {
        public static final class string { public static final int tiles_sunshine_mode = 1; }
        public static final class drawable { public static final int baseline_wb_sunny_24 = 2; }
    }''',
    TILE + '.TileConfig': '''public final class TileConfig {
        public static final class Builder {
            public Builder setTileClass(Class<?> value) { return this; }
            public Builder setTileName(String value) { return this; }
            public Builder setTileProvider(String value) { return this; }
            public Builder setLabelResId(int value) { return this; }
            public Builder setIcons(int on, int off) { return this; }
            public TileConfig build() { throw new AssertionError("Tile configuration is outside this fixture"); }
        }
    }''',
    TILE + '.TileContext': '''import android.content.Context;
        import java.util.HashMap;
        import java.util.Map;
        public record TileContext(Context context, Host tile) {
            public static final class Host {
                private final Map<String, Object> fields = new HashMap<>();
            }
            public Context getContext() { return context; }
            public Object getTileInstance() { return tile; }
            @SuppressWarnings("unchecked")
            public <T> T getAdditionalField(String key) { return (T) tile.fields.get(key); }
            public void setAdditionalField(String key, Object value) { tile.fields.put(key, value); }
            public void removeAdditionalField(String key) { tile.fields.remove(key); }
            public void refreshState() { throw new AssertionError("Unexpected refresh"); }
        }''',
    TILE + '.TileState': 'public record TileState(boolean enabled) {}',
    TILE + '.TileUtils': '''import android.content.BroadcastReceiver;
        import android.content.ContentResolver;
        import android.content.Context;
        import android.content.Intent;
        import android.database.ContentObserver;
        import java.util.ArrayList;
        import java.util.List;
        public abstract class TileUtils {
            public static final List<Runnable> cleanups = new ArrayList<>();
            protected final String TAG = "fixture";
            public void init() { throw new AssertionError("Hook installation is outside this fixture"); }
            protected abstract TileConfig onCreateTileConfig();
            protected abstract boolean onCheckAvailable(TileContext ctx);
            protected abstract void onTileClick(TileContext ctx);
            protected abstract Intent onGetLongClickIntent(TileContext ctx);
            protected abstract void onListeningChanged(TileContext ctx, boolean listening);
            protected abstract TileState onUpdateState(TileContext ctx);
            protected static Class<?> findClassIfExists(String name) { throw new AssertionError("Unexpected class lookup"); }
            protected static void registerReceiverHotReloadCleanup(Context context, BroadcastReceiver receiver) {
                throw new AssertionError("Unexpected receiver cleanup");
            }
            protected static void registerContentObserverHotReloadCleanup(ContentResolver resolver, ContentObserver observer) {
                registerHotReloadCleanup(() -> resolver.unregisterContentObserver(observer));
            }
            protected static void registerHotReloadCleanup(Runnable cleanup) { cleanups.add(cleanup); }
        }''',
}


def main():
    run_fixture([SOURCE_ROOT / RULE, HERE / 'SunlightModeTileTest.java'], STUBS, PACKAGE + '.SunlightModeTileTest')


if __name__ == '__main__':
    main()
