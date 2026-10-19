package io.github.pixelsuite;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

final class SuperDark {
    static final String KEY = "pixelsuite_super_dark";
    private static final String KEY_TURNED_DARK = "pixelsuite_super_dark_turned_dark";
    private static final String KEY_BLUR_OFF = "pixelsuite_super_dark_blur_off";
    private static final String OLD_PREFIX = "pixelsuite_sd_";
    private static final int OLD_COUNT = 11;

    private SuperDark() {}

    private static final Set<String> sLogged = new HashSet<String>();

    static void installSystemUi(ClassLoader cl) {
        int n = 0;
        for (String c : new String[] {"android.content.om.FabricatedOverlay", "android.content.om.FabricatedOverlay$Builder"}) {
            try { n += Xp.hookAllMethods(Class.forName(c), "setResourceValue", HOOK).size(); }
            catch (Throwable t) { Module.log("SuperDark: " + c + " unavailable", t); }
        }
        Module.log("SuperDark: colour hooks " + n);
        try {
            Class<?> c = Class.forName("com.android.systemui.theme.ThemeOverlayController", false, cl);
            int h = Xp.hookAllMethods(c, "reevaluateSystemTheme", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    sController = p.thisObject;
                    if (!sStarted) { sStarted = true; start(); }
                    Context ctx = app(); if (ctx == null) return;
                    boolean eff = effective(ctx);
                    if (sApplied != null && sApplied == eff) return;
                    boolean first = sApplied == null; sApplied = eff;
                    syncBlur(ctx, eff);
                    if (first) return;
                    if (needCreation(p.thisObject)) {
                        if (p.args.length > 0 && p.args[0] instanceof Boolean) p.args[0] = Boolean.TRUE;
                        Module.log("SuperDark: rebuilding System UI colours (" + (eff ? "on" : "off") + ")");
                    }
                }
            }).size();
            Module.log("SuperDark: rebuild hooks " + h);
        } catch (Throwable t) { Module.log("SuperDark: ThemeOverlayController unavailable", t); }
        try {
            Class<?> cc = Class.forName("com.android.systemui.statusbar.phone.ConfigurationControllerImpl", false, cl);
            int h = Xp.hookAllMethods(cc, "onConfigurationChanged", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { if (sController != null) scheduleSync("System UI configuration"); }
            }).size();
            Module.log("SuperDark: configuration hooks " + h);
        } catch (Throwable t) { Module.log("SuperDark: ConfigurationControllerImpl unavailable (" + t + ")"); }
        try {
            Class<?> sv = Class.forName("com.android.systemui.scrim.ScrimView", false, cl);
            int h = Xp.hookAllMethods(sv, "setTint", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof Integer)) return;
                    if (!effective(app())) return;
                    int c = (Integer) p.args[0]; if ((c & 0xffffff) == 0) return;
                    if (sScrimLogged < 1) { sScrimLogged++; Module.log("SuperDark: scrim #" + hex(c) + " -> black"); }
                    p.args[0] = c & 0xff000000;
                }
            }).size();
            Module.log("SuperDark: scrim hooks " + h);
        } catch (Throwable t) { Module.log("SuperDark: ScrimView unavailable (" + t + ")"); }
        int n2 = 0;
        n2 += hookPanel(android.graphics.drawable.ColorDrawable.class, "setColor");
        n2 += hookPanel(android.graphics.drawable.GradientDrawable.class, "setColor");
        n2 += hookPanel(android.view.View.class, "setBackgroundColor");
        Module.log("SuperDark: panel colour hooks " + n2);
    }

    private static int sScrimLogged2;
    private static long sEffAt; private static boolean sEff;

    private static boolean effectiveCached() {
        long now = android.os.SystemClock.uptimeMillis();
        if (sEffAt == 0 || now - sEffAt > 500) { sEff = effective(app()); sEffAt = now; }
        return sEff;
    }

    private static boolean stockPanel(int rgb) {
        return rgb == 0x2d303b || rgb == 0x2c303d || rgb == 0x2f3036 || rgb == 0x2e3038 || rgb == 0x2c2f3a;
    }

    private static int hookPanel(Class<?> c, String name) {
        try {
            return Xp.hookAllMethods(c, name, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof Integer)) return;
                    int v = (Integer) p.args[0];
                    if (!stockPanel(v & 0xffffff) || !effectiveCached()) return;
                    p.args[0] = v & 0xff000000;
                    if (sScrimLogged2 < 2) { sScrimLogged2++; Module.log("SuperDark: panel #" + hex(v) + " -> black (" + p.method.getDeclaringClass().getSimpleName() + "." + p.method.getName() + ")"); }
                }
            }).size();
        } catch (Throwable t) { Module.log("SuperDark: " + name + " hook", t); return 0; }
    }

    private static int sScrimLogged;

    private static volatile Boolean sApplied;
    private static volatile Object sController;
    private static boolean sStarted;

    private static boolean effective(Context ctx) {
        if (!switchedOn(ctx)) return false;
        return night(ctx);
    }

    static final String PROP = "persist.pixelsuite.superdark";

    private static boolean switchedOn(Context ctx) {
        try {
            String v = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, PROP);
            if ("1".equals(v)) return true;
            if ("0".equals(v)) return false;
        } catch (Throwable ignored) { }
        return ctx != null && Feature.on(ctx, KEY);
    }

    private static boolean night(Context ctx) {
        int mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK, yes = android.content.res.Configuration.UI_MODE_NIGHT_YES;
        try { return (android.content.res.Resources.getSystem().getConfiguration().uiMode & mask) == yes; }
        catch (Throwable t) {
            try { return (ctx.getResources().getConfiguration().uiMode & mask) == yes; } catch (Throwable u) { return true; }
        }
    }

    private static void scheduleSync(final String why) {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() { public void run() { sync(why); } }, 300);
        h.postDelayed(new Runnable() { public void run() { sync(why + ", again"); } }, 1500);
    }

    private static void syncBlur(Context ctx, boolean eff) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            if (eff) {
                if (android.provider.Settings.Global.getInt(cr, "disable_window_blurs", 0) != 1) {
                    android.provider.Settings.Global.putInt(cr, "disable_window_blurs", 1);
                    android.provider.Settings.Secure.putInt(cr, KEY_BLUR_OFF, 1);
                }
            } else if (android.provider.Settings.Secure.getInt(cr, KEY_BLUR_OFF, 0) == 1) {
                android.provider.Settings.Global.putInt(cr, "disable_window_blurs", 0);
                android.provider.Settings.Secure.putInt(cr, KEY_BLUR_OFF, 0);
            }
        } catch (Throwable t) { Module.log("SuperDark: blur setting", t); }
    }

    private static boolean needCreation(Object controller) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> k = controller.getClass(); k != null && f == null; k = k.getSuperclass()) {
                try { f = k.getDeclaredField("mNeedsOverlayCreation"); } catch (NoSuchFieldException ignored) { }
            }
            if (f == null) throw new NoSuchFieldException("mNeedsOverlayCreation");
            f.setAccessible(true); f.setBoolean(controller, true);
            synchronized (sLogged) { sLogged.clear(); }
            return true;
        } catch (Throwable t) { Module.log("SuperDark: could not rebuild colours", t); return false; }
    }

    private static void sync(String why) {
        Object controller = sController; Context ctx = app();
        if (controller == null || ctx == null) return;
        boolean eff = effective(ctx);
        if (sApplied != null && sApplied == eff) return;
        Module.log("SuperDark: " + why + ": night=" + night(ctx) + ", was " + sApplied + ", now " + eff);
        sApplied = eff;
        syncBlur(ctx, eff);
        if (!needCreation(controller)) return;
        try {
            java.lang.reflect.Method m = null;
            for (Class<?> k = controller.getClass(); k != null && m == null; k = k.getSuperclass()) {
                for (java.lang.reflect.Method x : k.getDeclaredMethods())
                    if (x.getName().equals("reevaluateSystemTheme") && x.getParameterTypes().length == 1 && x.getParameterTypes()[0] == boolean.class) { m = x; break; }
            }
            if (m == null) throw new NoSuchMethodException("reevaluateSystemTheme(boolean)");
            m.setAccessible(true); m.invoke(controller, true);
            Module.log("SuperDark: " + (eff ? "applied" : "removed") + " (" + why + ")");
        } catch (Throwable t) { Module.log("SuperDark: " + why + " failed", t); }
    }

    private static void start() {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries;
            public void run() {
                final Context ctx = app();
                if (ctx == null) { if (++tries < 10) h.postDelayed(this, 2000); return; }
                sApplied = effective(ctx) ? Boolean.FALSE : null;
                if (sApplied != null) sync("after boot"); else { sApplied = Boolean.FALSE; syncBlur(ctx, false); }
                try {
                    ctx.registerComponentCallbacks(new android.content.ComponentCallbacks() {
                        public void onConfigurationChanged(android.content.res.Configuration c) { scheduleSync("configuration"); }
                        public void onLowMemory() {}
                    });
                } catch (Throwable t) { Module.log("SuperDark: theme listener", t); }
                try {
                    ctx.getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor("ui_night_mode"), false,
                            new android.database.ContentObserver(h) { @Override public void onChange(boolean self) { scheduleSync("dark theme setting"); } });
                } catch (Throwable t) { Module.log("SuperDark: dark theme observer", t); }
            }
        }, 6000);
    }

    private static final Xp.Callback HOOK = new Xp.Callback() {
        @Override protected void beforeHookedMethod(Xp.Param p) {
            if (p.args.length < 3 || !(p.args[0] instanceof String) || !(p.args[2] instanceof Integer)) return;
            String full = (String) p.args[0];
            if (!full.contains("color/")) return;
            String name = full.substring(full.lastIndexOf('/') + 1);
            String config = p.args.length > 3 && p.args[3] instanceof String ? (String) p.args[3] : "";
            Context ctx = app();
            if (!effective(ctx)) return;
            Integer black = colorFor(name, config);
            if (black == null) return;
            p.args[2] = black;
            synchronized (sLogged) { if (sLogged.add("build")) Module.log("SuperDark: System UI colours built with Super Dark"); }
        }
    };

    static Integer colorFor(String n, String config) {
        if (n.startsWith("system_neutral1_") || n.startsWith("system_neutral2_") || n.startsWith("system_accent2_")) {
            String shade = n.substring(n.indexOf('_', 7) + 1);
            if (shade.endsWith("_dark")) shade = shade.substring(0, shade.length() - 5);
            else if (shade.endsWith("_light")) shade = shade.substring(0, shade.length() - 6);
            if (n.startsWith("system_accent2_"))
                return shade.equals("800") ? 0xff000000 : null;
            boolean n2 = n.startsWith("system_neutral2_");
            if (shade.equals("1000") || shade.equals("900")) return 0xff000000;
            if (shade.equals("800")) return n2 ? 0xff181818 : 0xff161616;
            return null;
        }
        String role;
        if (n.endsWith("_dark")) role = n.substring(0, n.length() - 5);
        else if (config != null && ("-" + config + "-").contains("-night-")) role = n;
        else return null;
        if (role.startsWith("system_")) role = role.substring(7);
        if (role.startsWith("materialColor")) role = role.substring(13);
        role = role.replace("_", "").toLowerCase(java.util.Locale.ROOT);
        switch (role) {
            case "background": case "surface": case "surfacedim": case "surfacecontainerlowest": case "surfaceheader":
                return 0xff000000;
            case "surfacecontainerlow": return 0xff060606;
            case "surfacecontainer": return 0xff0b0b0b;
            case "surfacecontainerhigh": return 0xff121212;
            case "surfacecontainerhighest": return 0xff1a1a1a;
            case "surfacebright": return 0xff0e0e0e;
            case "surfacevariant": return 0xff161616;
            case "surfaceeffect0": case "surfaceeffect0fallback": return 0xff000000;
            case "surfaceeffect1": return 0xff0e0e0e;
            case "surfaceeffect2": return 0x14ffffff;
            case "surfaceeffect3": return 0x0fffffff;
            case "shadeinactive": return 0xff161616;
            case "shadedisabled": return 0xff0e0e0e;
            case "widgetbackground": return 0xff121212;
            case "overviewbackground": case "undersurface": return 0xff000000;
            default: return null;
        }
    }

    private static String hex(int c) { String h = Integer.toHexString(c); while (h.length() < 8) h = "0" + h; return h; }

    private static Context app() {
        try { return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); }
        catch (Throwable t) { return null; }
    }

    private static final String REAPPLY =
        "j=$(settings get secure theme_customization_overlay_packages); t=$(date +%s)000; "
        + "case \"$j\" in ''|null|'{}') j=\"{\\\"_applied_timestamp\\\":$t}\";; "
        + "*_applied_timestamp*) j=$(echo \"$j\" | sed \"s/\\\"_applied_timestamp\\\":[0-9]*/\\\"_applied_timestamp\\\":$t/\");; "
        + "*) j=$(echo \"$j\" | sed \"s/^{/{\\\"_applied_timestamp\\\":$t,/\");; esac; "
        + "settings put secure theme_customization_overlay_packages \"$j\"";

    static String commands(boolean on) {
        StringBuilder b = new StringBuilder();
        b.append("setprop ").append(PROP).append(on ? " 1; " : " 0; ");
        b.append("if cmd overlay list 2>/dev/null | grep -q '\\[x\\] com.android.shell:").append(OLD_PREFIX).append("'; then for i in");
        for (int i = 0; i < OLD_COUNT; i++) b.append(' ').append(i);
        b.append("; do cmd overlay disable --user 0 com.android.shell:").append(OLD_PREFIX).append("$i >/dev/null 2>&1; done; fi; ");
        if (on) {
            b.append("m=$(cmd uimode night 2>/dev/null); case \"$m\" in *\": no\"*) cmd uimode night yes; settings put secure ")
             .append(KEY_TURNED_DARK).append(" 1;; esac; ");
        } else {
            b.append("if [ \"$(settings get secure ").append(KEY_TURNED_DARK).append(")\" = 1 ]; then cmd uimode night no; fi; settings put secure ")
             .append(KEY_TURNED_DARK).append(" 0; ");
            b.append("if [ \"$(settings get secure ").append(KEY_BLUR_OFF).append(")\" = 1 ]; then settings put global disable_window_blurs 0; fi; settings put secure ")
             .append(KEY_BLUR_OFF).append(" 0; ");
        }
        b.append(REAPPLY);
        b.append("; sleep 1; am force-stop com.google.android.inputmethod.latin >/dev/null 2>&1; am force-stop com.google.android.googlequicksearchbox >/dev/null 2>&1;");
        b.append(" true");
        return b.toString();
    }
}
