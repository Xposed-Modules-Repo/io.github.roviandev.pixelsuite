package io.github.pixelsuite;

import android.view.View;

import java.util.List;

/**
 * 2.0.6-preview19 (owner): a folder with one app left stays a folder, on the home screen as in the drawer, and shows
 * that app centred inside the folder's circle, so going from two apps to one inflates the app into the middle like a
 * bubble (the launcher's own preview animation runs between the two layouts). Drawer
 * folders already kept one app since build 228; the home screen's folder was turned back into its app by the launcher.
 * A folder with no app left still goes away as before.
 */
final class OneAppFolders {
    private OneAppFolders() {}
    /** A one-app folder's app, as a share of the preview's base scale (a two-app folder's apps are 0.51). */
    private static final float ONE = 0.62f;

    static void install(ClassLoader cl) {
        int n = 0;
        try {
            // preview22 (owner: with one app the folder looked like a plain app icon): the one app is drawn centred inside
            // the folder's circle, a little larger than in a two-app folder; the circle stays, so it is still a folder.
            // preview23 (owner: opening and closing a one-app folder jumped): done in the preview's layout rule itself, so
            // the icon, its preview animation and the folder's open/close animation all use the same centred place.
            Class<?> rule = Xp.findClass("com.android.launcher3.folder.ClippedFolderIconLayoutRule", cl);
            n += Xp.hookAllMethods(rule, "scaleForItem", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 2 || !Integer.valueOf(1).equals(p.args[0])) return;
                        float base = ((Number) Xp.getObjectField(p.thisObject, "mBaselineIconScale")).floatValue();
                        if (base > 0) p.setResult(ONE * base);
                    } catch (Throwable ignored) { }
                }
            }).size();
            n += Xp.hookAllMethods(rule, "getPosition", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 3 || !Integer.valueOf(1).equals(p.args[1]) || !(p.args[2] instanceof float[])) return;
                        float space = ((Number) Xp.getObjectField(p.thisObject, "mAvailableSpace")).floatValue();
                        float size = ((Number) Xp.getObjectField(p.thisObject, "mIconSize")).floatValue();
                        float base = ((Number) Xp.getObjectField(p.thisObject, "mBaselineIconScale")).floatValue();
                        if (space <= 0 || size <= 0 || base <= 0) return;
                        float at = (space - size * ONE * base) / 2f;
                        float[] out = (float[]) p.args[2];
                        out[0] = at; out[1] = at;
                    } catch (Throwable ignored) { }
                }
            }).size();
            // Home screen: the launcher is not allowed to replace a one-app folder with its app (an empty folder still goes).
            n += Xp.hookAllMethods(Xp.findClass("com.android.launcher3.folder.LauncherDelegate", cl), "replaceFolderWithFinalItem", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 1 || !(p.args[0] instanceof View)) return;
                        if (Xp.getAdditionalInstanceField(p.thisObject, "pixelsuite_drawer_delegate") != null) return;   // DrawerFolders handles those
                        Object count = Xp.callMethod(p.args[0], "getItemCount");
                        if (count instanceof Integer && (Integer) count == 1) p.setResult(Boolean.FALSE);
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable error) { Module.log("One-app folders unavailable", error); }
        Module.log("One-app folders: " + n + " hooks");
    }
}
