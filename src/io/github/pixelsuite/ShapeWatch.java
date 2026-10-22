package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.graphics.Path;
import android.view.View;
import java.util.List;

/**
 * The icon shape chosen in Wallpaper & style > Icons, as the launcher itself holds it
 * (ThemeManager), and a drawer refresh when it changes: the launcher redraws the home screen
 * for a new shape, but the app drawer kept its old icons until it was rebuilt.
 */
final class ShapeWatch {
    private ShapeWatch() {}
    private static String last;

    private static Object themeManager(Context c) throws Exception {
        Object singleton = LauncherFeatures.type("graphics.ThemeManager").getField("INSTANCE").get(null);
        return LauncherFeatures.call(singleton, "get", c);
    }

    /** The launcher's current icon shape as a 100x100 path, or null when it cannot be read. */
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

    /** A value that changes whenever the shape changes. */
    static String token(Context c) {
        try {
            Object m = themeManager(c);
            // preview47: the shape and the icon colours (themed icons on or off, which colours)
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

    /**
     * The launcher only crops an icon to the chosen shape when it is created with its "themed" flag,
     * and the app drawer does not set that flag unless drawer icons are themed: the home screen and
     * folders followed the shape, the drawer kept circles. With themed icons off the flag changes
     * nothing but the shape (ItemInfoWithIcon.newIcon drops it after choosing the shape).
     */
    private static void installDrawerShape() {
        try {
            int value;
            try {
                java.lang.reflect.Field field = LauncherFeatures.type("icons.BitmapInfo").getDeclaredField("FLAG_THEMED");
                field.setAccessible(true); value = field.getInt(null);
            } catch (NoSuchFieldException inlined) {
                // Verified in Launcher 17: supportsCustomShapes checks bit 1; newIcon clears it
                // after choosing the shape when iconState.themeController is null.
                value = 1;
            }
            final int themed = value;
            Xp.hookAllMethods(LauncherFeatures.type("BubbleTextView"), "getIconCreationFlagsForInfo", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        Object r = p.getResult(); if (!(r instanceof Integer)) return;
                        int flags = (Integer) r; if ((flags & themed) != 0) return;
                        // preview47 (owner: with themed icons on, the drawer kept circles and no colours, only the
                        // apps in its folders followed): the drawer follows the shape and the themed colours too.
                        p.setResult(flags | themed);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Drawer icon shape unavailable", error); }
    }

    /**
     * 2.0.6-preview49 (owner): folders take the exact icon shape chosen in Wallpaper & style > Icons. The launcher only
     * gives folders a circle or a rounded square (IconShapeModel.folderRadiusRatio); every folder drawing (the closed
     * folder on the home screen and in the drawer, its open and close animation, the drag view, the drop ring) reads
     * iconState.folderShape, which is now the icon shape itself. Every launcher shape class can draw it (Circle,
     * RoundedSquare, GenericPathShape).
     */
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

    /**
     * 2.0.6-preview50 (owner: in the drawer only some apps took the themed colours): the launcher loads drawer-only apps
     * from its icon cache without their themed version (lookup flag 8, "with theme"); only apps also on the home screen
     * had it, so only those could be coloured. Every cache lookup now includes the themed version: each of the
     * launcher's lookup flag values carries flag 8 (CacheLookupFlag keeps one object per value).
     */
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

    /**
     * 2.0.6-preview53 (owner's slow-motion video: swiping to another page of a home folder, its apps showed their own
     * colours for a moment, then turned themed; mostly after the phone sat or just booted): the launcher shows a cell
     * before the app's themed version is loaded, then swaps it in. A themed cell whose themed version is still missing
     * now waits invisible and fades in once it arrives (at most 0.8 s; an app that never gets one is shown at once
     * from then on).
     */
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
                        if (waiting != null) {   // it was waiting: now in, fading
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

    /**
     * 2.0.6-preview54 (owner: build 274 did not fix the folder swipe): what showed was the launcher's loading
     * placeholder, a soft blob in the app's own main colour that fades into the icon once it is loaded. With themed
     * icons on, the blob now takes the themed icons' background colour, so it fades into the themed icon instead.
     */
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

    /** Rebinds every drawer cell (on screen and pooled), so each icon is drawn again in the new shape. */
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
