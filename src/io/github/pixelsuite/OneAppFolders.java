package io.github.pixelsuite;

import android.view.View;

import java.util.List;

final class OneAppFolders {
    private OneAppFolders() {}
    private static final float ONE = 0.62f;

    static void install(ClassLoader cl) {
        int n = 0;
        try {
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
            n += Xp.hookAllMethods(Xp.findClass("com.android.launcher3.folder.LauncherDelegate", cl), "replaceFolderWithFinalItem", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 1 || !(p.args[0] instanceof View)) return;
                        if (Xp.getAdditionalInstanceField(p.thisObject, "pixelsuite_drawer_delegate") != null) return;
                        Object count = Xp.callMethod(p.args[0], "getItemCount");
                        if (count instanceof Integer && (Integer) count == 1) p.setResult(Boolean.FALSE);
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable error) { Module.log("One-app folders unavailable", error); }
        Module.log("One-app folders: " + n + " hooks");
    }
}
