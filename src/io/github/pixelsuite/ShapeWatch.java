package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.graphics.Path;
import android.view.View;
import java.util.List;

final class ShapeWatch {
    private ShapeWatch() {}
    private static String last;
    private static volatile Object singleton;

    private static Object themeManager(Context c) throws Exception {
        Object holder = singleton;
        if (holder == null) singleton = holder = LauncherFeatures.type("graphics.ThemeManager").getField("INSTANCE").get(null);
        return LauncherFeatures.call(holder, "get", c);
    }

    static Path path(Context c) {
        try {
            Object manager = themeManager(c);
            try {
                Object state = LauncherFeatures.get(manager, "iconState");
                Object shape = LauncherFeatures.get(state, "iconShape");
                Path path = new Path();
                LauncherFeatures.call(shape, "addToPath", 0f, 0f, 50f, path);
                return path;
            } catch (Throwable noFields) {
                Object shape = LauncherFeatures.call(manager, "getIconShape");
                return (Path) shape.getClass().getMethod("getPath", float.class).invoke(shape, 100f);
            }
        } catch (Throwable error) { LauncherFeatures.logOnce("Icon shape from the launcher unavailable", error); return null; }
    }

    static String token(Context c) {
        try {
            Object m = themeManager(c);
            try { return String.valueOf(LauncherFeatures.get(LauncherFeatures.get(m, "iconState"), "iconMask")); }
            catch (Throwable noFields) { }
            try { return String.valueOf(LauncherFeatures.call(LauncherFeatures.call(m, "getIconState"), "getIconMask")); }
            catch (Throwable noState) { return LauncherFeatures.call(m, "getIconShape").getClass().getName() + "@" + System.identityHashCode(LauncherFeatures.call(m, "getIconShape")); }
        } catch (Throwable error) { return ""; }
    }

    private static boolean themeOn(Context c) {
        try {
            Object manager = themeManager(c);
            try { return LauncherFeatures.get(LauncherFeatures.get(manager, "iconState"), "themeController") != null; }
            catch (Throwable noFields) { return Boolean.TRUE.equals(LauncherFeatures.call(manager, "isIconThemeEnabled")); }
        } catch (Throwable error) { return true; }
    }

    private static void installDrawerShape() {
        try {
            int value;
            try {
                java.lang.reflect.Field field = LauncherFeatures.type("icons.BitmapInfo").getDeclaredField("FLAG_THEMED");
                field.setAccessible(true); value = field.getInt(null);
            } catch (NoSuchFieldException inlined) {
                value = 1;
            }
            final int themed = value;
            Xp.hookAllMethods(LauncherFeatures.type("BubbleTextView"), "getIconCreationFlagsForInfo", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        Object r = p.getResult(); if (!(r instanceof Integer)) return;
                        int flags = (Integer) r; if ((flags & themed) != 0) return;
                        if (themeOn(((View) p.thisObject).getContext())) return;
                        p.setResult(flags | themed);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Drawer icon shape unavailable", error); }
    }

    static void install() {
        installDrawerShape();
        Xp.Callback check = new Xp.Callback() {
            protected void afterHookedMethod(Xp.Param p) {
                final Activity a = LauncherFeatures.activity.get(); if (a == null) return;
                a.getWindow().getDecorView().postDelayed(new Runnable() { public void run() {
                    if (LauncherFeatures.activity.get() == a && !a.isDestroyed()) check(a);
                } }, 250);
            }
        };
        try {
            Xp.hookAllMethods(LauncherFeatures.type("Launcher"), "onResume", check);
            Xp.hookAllMethods(LauncherFeatures.type("LauncherModel"), "rebindCallbacks", check);
        } catch (Throwable error) { LauncherFeatures.logOnce("Icon shape watch unavailable", error); }
    }

    static void check(Activity a) {
        try {
            String now = token(a);
            if (now.isEmpty()) return;
            if (last == null) { last = now; return; }
            if (now.equals(last)) return;
            last = now;
            refreshDrawer(a);
        } catch (Throwable error) { LauncherFeatures.logOnce("Icon shape refresh unavailable", error); }
    }

    static void refreshDrawer(Activity a) {
        try {
            Object holders = LauncherFeatures.get(LauncherFeatures.call(a, "getAppsView"), "mAH");
            for (Object holder : (List<?>) holders) {
                if (holder == null) continue;
                try {
                    Object list = LauncherFeatures.get(holder, "mRecyclerView");
                    if (list != null) { LauncherFeatures.call(LauncherFeatures.call(list, "getRecycledViewPool"), "clear"); LauncherFeatures.call(LauncherFeatures.call(list, "getAdapter"), "notifyDataSetChanged"); }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable error) { LauncherFeatures.logOnce("Drawer rebind after shape change unavailable", error); }
        LauncherFeatures.refresh();
    }
}
