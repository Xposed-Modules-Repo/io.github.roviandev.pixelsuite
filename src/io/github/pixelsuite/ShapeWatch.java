package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.graphics.Path;
import android.view.View;
import java.util.List;

final class ShapeWatch {
    private ShapeWatch() {}
    private static String last;

    private static Object themeManager(Context c) throws Exception {
        Object singleton = LauncherFeatures.type("graphics.ThemeManager").getField("INSTANCE").get(null);
        return LauncherFeatures.call(singleton, "get", c);
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
            try { Object st = LauncherFeatures.get(m, "iconState"); return String.valueOf(LauncherFeatures.get(st, "iconMask")) + "|" + String.valueOf(LauncherFeatures.get(st, "themeCode")); }
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
                        p.setResult(flags | themed);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Drawer icon shape unavailable", error); }
    }

    private static void installFolderShape() {
        try {
            Xp.hookAllMethods(LauncherFeatures.type("graphics.ThemeManager"), "parseIconState", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        Object state = p.getResult(); if (state == null) return;
                        Object icon = LauncherFeatures.get(state, "iconShape");
                        if (icon != null) LauncherFeatures.set(state, "folderShape", icon);
                    } catch (Throwable error) { LauncherFeatures.logOnce("Folder shape from icon shape unavailable", error); }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Folder shape hook unavailable", error); }
    }

    private static void installThemedLookups() {
        try {
            Class<?> type = Class.forName("com.android.launcher3.icons.cache.CacheLookupFlag", true, LauncherFeatures.type("Launcher").getClassLoader());
            java.lang.reflect.Field flag = type.getDeclaredField("flag"); flag.setAccessible(true);
            Object[] cache = (Object[]) type.getField("flagCache").get(null);
            int changed = 0;
            for (Object f : cache) if (f != null) { int v = flag.getInt(f); if ((v & 8) == 0) { flag.setInt(f, v | 8); changed++; } }
            Object fallback = type.getField("DEFAULT_LOOKUP_FLAG").get(null);
            if (fallback != null) { int v = flag.getInt(fallback); if ((v & 8) == 0) { flag.setInt(fallback, v | 8); changed++; } }
            Module.log("Themed icons for every app: " + changed + " lookup flags");
        } catch (Throwable error) { LauncherFeatures.logOnce("Themed icons for drawer apps unavailable", error); }
    }

    private static final java.util.Set<String> neverThemed = new java.util.HashSet<String>();
    private static void installThemedWait() {
        try {
            Xp.hookAllMethods(LauncherFeatures.type("BubbleTextView"), "setStandardIcon", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        final View cell = (View) p.thisObject;
                        Object info = p.args.length > 0 ? p.args[0] : null; if (info == null) return;
                        final android.graphics.drawable.Drawable icon = (android.graphics.drawable.Drawable) LauncherFeatures.get(cell, "mIcon"); if (icon == null) return;
                        Object waiting = Xp.getAdditionalInstanceField(cell, "pixelsuite_theme_wait");
                        Object bitmap = LauncherFeatures.get(info, "bitmap");
                        Object themed = bitmap == null ? null : LauncherFeatures.get(bitmap, "themedBitmap");
                        boolean low = false; try { low = Boolean.TRUE.equals(LauncherFeatures.call(bitmap, "isLowRes")); } catch (Throwable ignored) { }
                        int flags = ((Number) LauncherFeatures.call(cell, "getIconCreationFlagsForInfo", info)).intValue();
                        final String key = LauncherFeatures.identity(info);
                        boolean wants = (flags & 1) != 0 && themeOn(cell.getContext()) && !low && key != null;
                        if (waiting != null) {
                            if (themed != null || !wants) {
                                Xp.setAdditionalInstanceField(cell, "pixelsuite_theme_wait", null);
                                fadeIn(icon);
                            } else icon.setAlpha(0);
                            return;
                        }
                        if (!wants || themed != null) return;
                        synchronized (neverThemed) { if (neverThemed.contains(key)) return; }
                        icon.setAlpha(0);
                        final Object stamp = new Object();
                        Xp.setAdditionalInstanceField(cell, "pixelsuite_theme_wait", stamp);
                        cell.postDelayed(new Runnable() { public void run() {
                            if (Xp.getAdditionalInstanceField(cell, "pixelsuite_theme_wait") != stamp) return;
                            Xp.setAdditionalInstanceField(cell, "pixelsuite_theme_wait", null);
                            synchronized (neverThemed) { neverThemed.add(key); }
                            try { fadeIn((android.graphics.drawable.Drawable) LauncherFeatures.get(cell, "mIcon")); } catch (Throwable ignored) { }
                        } }, 800);
                    } catch (Throwable error) { LauncherFeatures.logOnce("Themed icon wait unavailable", error); }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Themed icon wait hook unavailable", error); }
    }
    private static void fadeIn(final android.graphics.drawable.Drawable icon) {
        if (icon == null) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(0, 255);
        a.setDuration(160);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(android.animation.ValueAnimator v) { icon.setAlpha((Integer) v.getAnimatedValue()); } });
        a.start();
    }

    private static int themedFill, themedFillMode = -1;
    private static void installThemedPlaceholder() {
        try {
            Xp.hookAllMethods(LauncherFeatures.type("icons.PlaceHolderDrawableDelegate$PlaceHolderDelegateFactory"), "newDelegate", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        Object delegate = p.getResult(); if (delegate == null) return;
                        Activity a = LauncherFeatures.activity.get(); if (a == null || !themeOn(a)) return;
                        int mode = a.getResources().getConfiguration().uiMode;
                        if (mode != themedFillMode) {
                            int id = a.getResources().getIdentifier("themed_icon_background_color", "color", a.getPackageName());
                            if (id == 0) return;
                            themedFill = a.getResources().getColor(id, a.getTheme()); themedFillMode = mode;
                        }
                        int loading = ((Number) LauncherFeatures.get(p.thisObject, "loadingColor")).intValue();
                        LauncherFeatures.set(delegate, "fillColor", FrostedSurface.composite(loading, themedFill | 0xff000000));
                    } catch (Throwable error) { LauncherFeatures.logOnce("Themed loading placeholder unavailable", error); }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Themed loading placeholder hook unavailable", error); }
    }

    static void install() {
        installThemedPlaceholder();
        installThemedWait();
        installThemedLookups();
        installFolderShape();
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
        int lists = 0;
        try {
            Object holders = LauncherFeatures.get(LauncherFeatures.call(a, "getAppsView"), "mAH");
            for (Object holder : (List<?>) holders) {
                if (holder == null) continue;
                try {
                    Object list = LauncherFeatures.get(holder, "mRecyclerView");
                    if (list != null) { LauncherFeatures.call(LauncherFeatures.call(list, "getRecycledViewPool"), "clear"); LauncherFeatures.call(LauncherFeatures.call(list, "getAdapter"), "notifyDataSetChanged"); lists++; }
                } catch (Throwable ignored) { }
            }
        } catch (Throwable error) { LauncherFeatures.logOnce("Drawer rebind after shape change unavailable", error); }
        LauncherFeatures.refresh();
        Module.log("Icon shape changed: drawer redrawn (" + lists + " lists)");
    }
}
