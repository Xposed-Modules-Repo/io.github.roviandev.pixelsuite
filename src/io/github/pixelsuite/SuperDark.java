package io.github.pixelsuite;

import android.content.Context;

final class SuperDark {
    static final String KEY = "pixelsuite_super_dark";
    private static final String KEY_TURNED_DARK = "pixelsuite_super_dark_turned_dark";
    private static final String KEY_BLUR_OFF = "pixelsuite_super_dark_blur_off";
    private static final String OLD_PREFIX = "pixelsuite_sd_";
    private static final int OLD_COUNT = 11;

    private SuperDark() {}

    private static int sHooks;

    static void installSystemUi(ClassLoader cl) {
        int n = 0;
        for (String c : new String[] {"android.content.om.FabricatedOverlay", "android.content.om.FabricatedOverlay$Builder"}) {
            try { n += Xp.hookAllMethods(Class.forName(c), "setResourceValue", HOOK).size(); }
            catch (Throwable t) { Module.log("SuperDark: " + c + " unavailable", t); }
        }
        sHooks += n;
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
                    }
                }
            }).size();
            sHooks += h;
        } catch (Throwable t) { Module.log("SuperDark: ThemeOverlayController unavailable", t); }
        try {
            Class<?> cc = Class.forName("com.android.systemui.statusbar.phone.ConfigurationControllerImpl", false, cl);
            int h = Xp.hookAllMethods(cc, "onConfigurationChanged", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { if (sController != null) scheduleSync("System UI configuration"); }
            }).size();
            sHooks += h;
        } catch (Throwable t) { Module.log("SuperDark: ConfigurationControllerImpl unavailable (" + t + ")"); }
        try {
            Class<?> sv = Class.forName("com.android.systemui.scrim.ScrimView", false, cl);
            int h = Xp.hookAllMethods(sv, "setTint", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof Integer)) return;
                    if (!effective(app())) {
                        int c = (Integer) p.args[0];
                        if (stockPanel(c & 0xffffff) && bouncerShown() && blurOffDark() && "behind_scrim".equals(scrimName(p.thisObject)) && keyguardLocked()) p.args[0] = (c & 0xff000000) | 0x17181c;
                        return;
                    }
                    int c = (Integer) p.args[0]; if ((c & 0xffffff) == 0) return;
                    p.args[0] = c & 0xff000000;
                }
            }).size();
            sHooks += h;
        } catch (Throwable t) { Module.log("SuperDark: ScrimView unavailable (" + t + ")"); }
        int n2 = 0;
        n2 += hookPanel(android.graphics.drawable.ColorDrawable.class, "setColor");
        n2 += hookPanel(android.graphics.drawable.GradientDrawable.class, "setColor");
        n2 += hookPanel(android.view.View.class, "setBackgroundColor");
        sHooks += n2 + backdropHooks() + splashHooks(cl);
        Module.log("SuperDark: " + sHooks + " hooks");
    }

    private static int backdropHooks() {
        int n = 0;
        try {
            n += Xp.hookMethod(android.content.res.Resources.class.getDeclaredMethod("getColor", int.class, android.content.res.Resources.Theme.class), new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { backdrop(p, (android.content.res.Resources) p.thisObject, (Integer) p.args[0]); }
            }) != null ? 1 : 0;
        } catch (Throwable t) { Module.log("Blur Off: Resources.getColor hook", t); }
        try {
            n += Xp.hookMethod(android.content.res.TypedArray.class.getDeclaredMethod("getColor", int.class, int.class), new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { android.content.res.TypedArray a = (android.content.res.TypedArray) p.thisObject; int id = a.getResourceId((Integer) p.args[0], 0); if (id != 0) backdrop(p, a.getResources(), id); } catch (Throwable ignored) { }
                }
            }) != null ? 1 : 0;
        } catch (Throwable t) { Module.log("Blur Off: TypedArray.getColor hook", t); }
        return n;
    }
    private static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";
    private static volatile boolean sGoogleSplash;
    private static int splashHooks(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> drawer = Class.forName("com.android.wm.shell.startingsurface.SplashscreenContentDrawer", false, cl);
            n += Xp.hookAllMethods(drawer, "getBGColorFromCache", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        String pkg = p.args.length > 0 && p.args[0] instanceof android.content.pm.ActivityInfo ? ((android.content.pm.ActivityInfo) p.args[0]).packageName : null;
                        sGoogleSplash = GOOGLE_APP.equals(pkg);
                        if (!sGoogleSplash || !(p.getResult() instanceof Integer) || !effective(app())) return;
                        int c = (Integer) p.getResult();
                        if (darkGrey(c)) p.setResult(c & 0xff000000);
                    } catch (Throwable ignored) { }
                }
            }).size();
            n += Xp.hookAllMethods(Class.forName("android.window.SplashScreenView$Builder"), "setOverlayDrawable", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (!sGoogleSplash || p.args.length != 1 || !(p.args[0] instanceof android.graphics.drawable.ColorDrawable) || !effective(app())) return;
                        int c = ((android.graphics.drawable.ColorDrawable) p.args[0]).getColor();
                        if (darkGrey(c)) p.args[0] = new android.graphics.drawable.ColorDrawable(c & 0xff000000);
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable t) { Module.log("SuperDark: starting window colour unavailable", t); }
        return n;
    }
    private static boolean darkGrey(int c) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff, max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max > 0 && max <= 0x40 && max - min <= 0x14;
    }
    private static final android.util.SparseIntArray sBackdropKind = new android.util.SparseIntArray();
    private static void backdrop(Xp.Param p, android.content.res.Resources r, int id) {
        try {
            if (!(p.getResult() instanceof Integer)) return;
            int kind;
            synchronized (sBackdropKind) { kind = sBackdropKind.get(id, 0); }
            if (kind == 0) {
                String name; try { name = r.getResourceEntryName(id); } catch (Throwable t) { name = ""; }
                kind = name.contains("surface_effect_0_fallback") ? 3 : 1;
                synchronized (sBackdropKind) { sBackdropKind.put(id, kind); }
            }
            if (kind != 3 || !blurOffDark()) return;
            int v = (Integer) p.getResult();
            p.setResult((v & 0xff000000) | 0x17181c);
        } catch (Throwable ignored) { }
    }
    private static String bouncer(Object o) {
        try {
            Object v = o instanceof android.graphics.drawable.Drawable ? ((android.graphics.drawable.Drawable) o).getCallback() : o;
            for (int i = 0; i < 12 && v instanceof android.view.View; i++) {
                String n = v.getClass().getName();
                if (n.contains("Bouncer") || n.contains("KeyguardSecurity") || n.contains("KeyguardPin") || n.contains("KeyguardPassword") || n.contains("KeyguardPattern") || n.contains("KeyguardSim")) { sBouncer = new java.lang.ref.WeakReference<Object>(v); return v.getClass().getSimpleName(); }
                Object parent = ((android.view.View) v).getParent(); v = parent instanceof android.view.View ? parent : null;
            }
        } catch (Throwable ignored) { }
        return null;
    }
    private static java.lang.ref.WeakReference<Object> sBouncer;
    private static boolean bouncerShown() {
        Object v = sBouncer == null ? null : sBouncer.get();
        return v instanceof android.view.View && ((android.view.View) v).isShown() && ((android.view.View) v).getAlpha() > 0f;
    }
    private static String scrimName(Object scrim) { try { return String.valueOf(Xp.getObjectField(scrim, "mScrimName")); } catch (Throwable t) { return null; } }
    private static boolean keyguardLocked() {
        try { android.app.KeyguardManager k = (android.app.KeyguardManager) app().getSystemService(Context.KEYGUARD_SERVICE); return k != null && k.isKeyguardLocked(); } catch (Throwable t) { return false; }
    }
    private static boolean blurOffDark() {
        try { Context c = app(); return c != null && SurfaceStyle.get(c) == SurfaceStyle.OFF && night(c); } catch (Throwable t) { return false; }
    }
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
                    if (!stockPanel(v & 0xffffff)) return;
                    if (!effectiveCached()) {
                        if (!blurOffDark()) return;
                        if (bouncer(p.thisObject) == null) return;
                        p.args[0] = (v & 0xff000000) | 0x17181c;
                        return;
                    }
                    p.args[0] = v & 0xff000000;
                }
            }).size();
        } catch (Throwable t) { Module.log("SuperDark: " + name + " hook", t); return 0; }
    }

    private static volatile Boolean sApplied;
    private static volatile Object sController;
    private static boolean sStarted;

    static boolean inEffectNow() { try { return effectiveCached(); } catch (Throwable t) { return false; } }

    static boolean inEffect(Context ctx) { try { return effective(ctx); } catch (Throwable t) { return false; } }

    private static boolean effective(Context ctx) {
        if (!switchedOn(ctx)) return false;
        return night(ctx);
    }

    static final String PROP = "persist.pixelsuite.superdark";

    private static java.lang.reflect.Method sPropGet;
    private static boolean switchedOn(Context ctx) {
        try {
            if (sPropGet == null) sPropGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            String v = (String) sPropGet.invoke(null, PROP);
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
            SurfaceStyle.refresh();
            if (eff || SurfaceStyle.get(ctx) == SurfaceStyle.OFF) {
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
            return true;
        } catch (Throwable t) { Module.log("SuperDark: could not rebuild colours", t); return false; }
    }

    private static void sync(String why) {
        Object controller = sController; Context ctx = app();
        if (controller == null || ctx == null) return;
        boolean eff = effective(ctx);
        if (sApplied != null && sApplied == eff) return;
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

    private static volatile Context sApp;
    private static java.lang.reflect.Method sCurrentApplication;
    private static Context app() {
        Context c = sApp;
        if (c != null) return c;
        try {
            if (sCurrentApplication == null) sCurrentApplication = Class.forName("android.app.ActivityThread").getMethod("currentApplication");
            c = (Context) sCurrentApplication.invoke(null);
            if (c != null) sApp = c;
            return c;
        } catch (Throwable t) { return null; }
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
