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
    static final String SETTINGS_SEARCH = "com.google.android.settings.intelligence";
    private static int sBlackMax = 0x2c;

    private static final java.util.List<Object> sHandles = new java.util.ArrayList<Object>();

    static void install(ClassLoader cl, String pkg) {
        if (SETTINGS.equals(pkg) || SETTINGS_SEARCH.equals(pkg)) sBlackMax = 0x18;
        int n = 0;
        sHandles.clear();
        n += hookResult(android.content.res.Resources.class, "getColor");
        n += hookResult(android.content.res.TypedArray.class, "getColor");
        n += hookResult(android.content.res.ColorStateList.class, "getColorForState");
        n += hookResult(android.content.res.ColorStateList.class, "getDefaultColor");
        n += hookFirstArg(android.graphics.drawable.GradientDrawable.class, "setColor");
        n += hookFirstArg(android.graphics.drawable.ColorDrawable.class, "setColor");
        n += hookFirstArg(android.view.View.class, "setBackgroundColor");
        if (!GBOARD.equals(pkg)) n += hookFirstArg(android.graphics.Paint.class, "setColor");
        if (AliasProvider.GOOGLE.equals(pkg)) {
            try {
                n += keep(Xp.hookAllMethods(android.graphics.Paint.class, "setColor", new Xp.Callback() {
                    @Override protected void beforeHookedMethod(Xp.Param p) {
                        if (p.args.length != 1 || !(p.args[0] instanceof Long) || !active()) return;
                        try {
                            if (sToArgb == null) { sToArgb = android.graphics.Color.class.getMethod("toArgb", long.class); sPack = android.graphics.Color.class.getMethod("pack", int.class); }
                            int c = (Integer) sToArgb.invoke(null, p.args[0]), v = remap(c);
                            if (v != c) p.args[0] = sPack.invoke(null, v);
                        } catch (Throwable ignored) { }
                    }
                }));
            } catch (Throwable t) { Module.log("SuperDark apps: Paint.setColor(long)", t); }
        }
        try {
            n += keep(Xp.hookAllMethods(android.graphics.drawable.GradientDrawable.class, "setColors", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof int[]) || !active()) return;
                    int[] in = (int[]) p.args[0], out = null;
                    for (int i = 0; i < in.length; i++) { int v = remap(in[i]); if (v != in[i]) { if (out == null) out = in.clone(); out[i] = v; } }
                    if (out != null) p.args[0] = out;
                }
            }));
        } catch (Throwable t) { Module.log("SuperDark apps: setColors", t); }
        if (AliasProvider.GOOGLE.equals(pkg)) {
            try {
                n += keep(Xp.hookAllMethods(android.view.View.class, "setBackgroundDrawable", new Xp.Callback() {
                    @Override protected void beforeHookedMethod(Xp.Param p) {
                        if (p.args.length != 1 || !(p.args[0] instanceof android.graphics.drawable.ColorDrawable) || !active()) return;
                        android.graphics.drawable.ColorDrawable d = (android.graphics.drawable.ColorDrawable) p.args[0];
                        int c = d.getColor(), v = remap(c);
                        if (v != c) d.setColor(v);
                    }
                }));
            } catch (Throwable t) { Module.log("SuperDark apps: window background", t); }
            try {
                Xp.Callback black = new Xp.Callback() {
                    @Override protected void afterHookedMethod(Xp.Param p) {
                        try {
                            if (!activeAnywhere() || Boolean.TRUE.equals(Xp.getAdditionalInstanceField(p.thisObject, "pixelsuite_task_black"))) return;
                            Xp.setAdditionalInstanceField(p.thisObject, "pixelsuite_task_black", Boolean.TRUE);
                            android.app.Activity a = (android.app.Activity) p.thisObject;
                            Object td;
                            try {
                                Class<?> b = Class.forName("android.app.ActivityManager$TaskDescription$Builder");
                                Object builder = b.getConstructor().newInstance();
                                b.getMethod("setBackgroundColor", int.class).invoke(builder, 0xff000000);
                                b.getMethod("setNavigationBarColor", int.class).invoke(builder, 0xff000000);
                                td = b.getMethod("build").invoke(builder);
                            } catch (Throwable old) {
                                td = new android.app.ActivityManager.TaskDescription();
                                android.app.ActivityManager.TaskDescription.class.getMethod("setBackgroundColor", int.class).invoke(td, 0xff000000);
                            }
                            a.setTaskDescription((android.app.ActivityManager.TaskDescription) td);
                        } catch (Throwable ignored) { }
                    }
                };
                n += keep(Xp.hookAllMethods(android.app.Activity.class, "onCreate", black));
                n += keep(Xp.hookAllMethods(android.app.Activity.class, "onResume", black));
            } catch (Throwable t) { Module.log("SuperDark apps: task background", t); }
        }
        if (!sLogged) { sLogged = true; Module.log("SuperDark apps: " + n + " colour hooks in " + pkg); }
    }
    private static boolean sLogged;
    private static java.lang.reflect.Method sToArgb, sPack;

    private static int keep(java.util.Set<Object> handles) { sHandles.addAll(handles); return handles.size(); }

    private static void unhookAll() {
        for (Object h : sHandles) try { if (h instanceof io.github.libxposed.api.XposedInterface.HookHandle) ((io.github.libxposed.api.XposedInterface.HookHandle) h).unhook(); } catch (Throwable ignored) { }
        sHandles.clear();
    }

    private static int hookResult(Class<?> c, String name) {
        try {
            return keep(Xp.hookAllMethods(c, name, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    Object r = p.getResult();
                    if (r instanceof Integer && active()) { int v = remap((Integer) r); if (v != (Integer) r) p.setResult(v); }
                }
            }));
        } catch (Throwable t) { Module.log("SuperDark apps: " + name, t); return 0; }
    }

    private static int hookFirstArg(Class<?> c, String name) {
        try {
            return keep(Xp.hookAllMethods(c, name, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 1 && p.args[0] instanceof Integer && active()) p.args[0] = remap((Integer) p.args[0]);
                }
            }));
        } catch (Throwable t) { Module.log("SuperDark apps: " + name, t); return 0; }
    }

    private static boolean sScoped; private static volatile boolean sForeign;

    static void installLauncherSettings(final ClassLoader cl) {
        sScoped = true;
        sBlackMax = 0x50;
        try {
            final Class<?> home = Class.forName("com.android.launcher3.Launcher", false, cl);
            Xp.hookAllMethods(android.app.Activity.class, "onResume", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    sForeign = !home.isInstance(p.thisObject); sAt = 0;
                    if (sForeign && sHandles.isEmpty()) install(cl, "com.google.android.apps.nexuslauncher (settings)");
                    else if (!sForeign && !sHandles.isEmpty()) unhookAll();
                }
            });
        } catch (Throwable t) { Module.log("SuperDark launcher settings unavailable", t); }
    }

    static void installLauncher(ClassLoader cl) {
        try {
            Class<?> scrim = Class.forName("com.android.launcher3.views.ScrimView", false, cl);
            int n = Xp.hookAllMethods(scrim, "setBackgroundColor", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 1 && p.args[0] instanceof Integer && activeAnywhere()) p.args[0] = ((Integer) p.args[0]) & 0xff000000;
                }
            }).size();
            Module.log("SuperDark launcher: scrim hooks " + n);
        } catch (Throwable t) { Module.log("SuperDark launcher: ScrimView unavailable", t); }
    }

    static boolean active() {
        if (sScoped && !sForeign) return false;
        return activeAnywhere();
    }

    static boolean activeAnywhere() {
        long now = SystemClock.uptimeMillis();
        if (sAt != 0 && now - sAt < 500) return sActive;
        sAt = now;
        boolean on = false;
        try {
            Context ctx = app();
            if (ctx != null) {
                boolean night = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                on = night && android.provider.Settings.Secure.getInt(ctx.getContentResolver(), SuperDark.KEY, 0) == 1;
            }
        } catch (Throwable ignored) { }
        sActive = on;
        return on;
    }

    private static volatile Context sApp;
    private static java.lang.reflect.Method sCurrentApplication;
    private static Context app() throws Exception {
        Context c = sApp;
        if (c != null) return c;
        if (sCurrentApplication == null) sCurrentApplication = Class.forName("android.app.ActivityThread").getMethod("currentApplication");
        return sApp = (Context) sCurrentApplication.invoke(null);
    }

    static int remap(int c) {
        int a = c >>> 24; if (a == 0) return c;
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max > 0x50 || max - min > 0x14 || max == 0) return c;
        if (sBlackMax >= 0x50) return a << 24;
        if (r == g && g == b && (max == 0x10 || max == 0x18 || max == 0x24)) return c;
        int B = sBlackMax;
        int v = max <= B ? 0 : max <= B + (0x50 - B) / 3 ? 0x10 : max <= B + 2 * (0x50 - B) / 3 ? 0x18 : 0x24;
        if (v == max) return c;
        return (a << 24) | (v << 16) | (v << 8) | v;
    }
}
