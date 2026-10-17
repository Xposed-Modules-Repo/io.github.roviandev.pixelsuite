package io.github.pixelsuite;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;

final class SuperDarkApps {
    static final String GBOARD = "com.google.android.inputmethod.latin";
    private SuperDarkApps() {}

    private static long sAt;
    private static boolean sActive;

    static final String SETTINGS = "com.android.settings";
    private static int sBlackMax = 0x2c;

    static void install(ClassLoader cl, String pkg) {
        if (SETTINGS.equals(pkg)) sBlackMax = 0x18;
        int n = 0;
        n += hookResult(android.content.res.Resources.class, "getColor");
        n += hookResult(android.content.res.TypedArray.class, "getColor");
        n += hookResult(android.content.res.ColorStateList.class, "getColorForState");
        n += hookResult(android.content.res.ColorStateList.class, "getDefaultColor");
        n += hookFirstArg(android.graphics.drawable.GradientDrawable.class, "setColor");
        n += hookFirstArg(android.graphics.drawable.ColorDrawable.class, "setColor");
        n += hookFirstArg(android.view.View.class, "setBackgroundColor");
        n += hookFirstArg(android.graphics.Paint.class, "setColor");
        try {
            n += Xp.hookAllMethods(android.graphics.drawable.GradientDrawable.class, "setColors", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof int[]) || !active()) return;
                    int[] in = (int[]) p.args[0], out = in.clone(); boolean changed = false;
                    for (int i = 0; i < out.length; i++) { out[i] = remap(in[i]); changed |= out[i] != in[i]; }
                    if (changed) p.args[0] = out;
                }
            }).size();
        } catch (Throwable t) { Module.log("SuperDark apps: setColors", t); }
        Module.log("SuperDark apps: " + n + " colour hooks in " + pkg);
    }

    private static int hookResult(Class<?> c, String name) {
        try {
            return Xp.hookAllMethods(c, name, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    Object r = p.getResult();
                    if (r instanceof Integer && active()) { int v = remap((Integer) r); if (v != (Integer) r) p.setResult(v); }
                }
            }).size();
        } catch (Throwable t) { Module.log("SuperDark apps: " + name, t); return 0; }
    }

    private static int hookFirstArg(Class<?> c, String name) {
        try {
            return Xp.hookAllMethods(c, name, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 1 && p.args[0] instanceof Integer && active()) p.args[0] = remap((Integer) p.args[0]);
                }
            }).size();
        } catch (Throwable t) { Module.log("SuperDark apps: " + name, t); return 0; }
    }

    static void installLauncher(ClassLoader cl) {
        try {
            Class<?> scrim = Class.forName("com.android.launcher3.views.ScrimView", false, cl);
            int n = Xp.hookAllMethods(scrim, "setBackgroundColor", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 1 && p.args[0] instanceof Integer && active()) p.args[0] = ((Integer) p.args[0]) & 0xff000000;
                }
            }).size();
            Module.log("SuperDark launcher: scrim hooks " + n);
        } catch (Throwable t) { Module.log("SuperDark launcher: ScrimView unavailable", t); }
    }

    static boolean active() {
        long now = SystemClock.uptimeMillis();
        if (sAt != 0 && now - sAt < 500) return sActive;
        sAt = now;
        boolean on = false;
        try {
            Context ctx = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (ctx != null) {
                boolean night = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                on = night && android.provider.Settings.Secure.getInt(ctx.getContentResolver(), SuperDark.KEY, 0) == 1;
            }
        } catch (Throwable ignored) { }
        sActive = on;
        return on;
    }

    static int remap(int c) {
        int a = c >>> 24; if (a == 0) return c;
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max > 0x50 || max - min > 0x14 || max == 0) return c;
        if (r == g && g == b && (max == 0x10 || max == 0x18 || max == 0x24)) return c;
        int B = sBlackMax;
        int v = max <= B ? 0 : max <= B + (0x50 - B) / 3 ? 0x10 : max <= B + 2 * (0x50 - B) / 3 ? 0x18 : 0x24;
        if (v == max) return c;
        return (a << 24) | (v << 16) | (v << 8) | v;
    }
}
