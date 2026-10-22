package io.github.pixelsuite;

import android.content.Context;

/**
 * Super Dark-Mode: a true-black (OLED) version of Android's own dark theme.
 *
 * Done inside System UI's own colour engine (ThemeOverlayController): it writes every system colour
 * into its fabricated overlays, which all apps read, and the background/surface roles are swapped
 * for black as it writes them (preview80's separate root overlays lost to it). Text and icon
 * colours ("on_*") and accents are never touched. Flipping the switch makes System UI rebuild its
 * overlays live (reevaluateSystemTheme with mNeedsOverlayCreation set).
 */
final class SuperDark {
    static final String KEY = "pixelsuite_super_dark";
    /** "1" when Super Dark switched the phone from light to dark (so it switches it back). */
    private static final String KEY_TURNED_DARK = "pixelsuite_super_dark_turned_dark";
    /** "1" when Super Dark turned window blurs off (so it turns them back on). */
    private static final String KEY_BLUR_OFF = "pixelsuite_super_dark_blur_off";
    /** preview80's root overlays (removed now). */
    private static final String OLD_PREFIX = "pixelsuite_sd_";
    private static final int OLD_COUNT = 11;

    private SuperDark() {}

    // ------------------------------------------------------------------ System UI side

    private static int sHooks;

    static void installSystemUi(ClassLoader cl) {
        int n = 0;
        for (String c : new String[] {"android.content.om.FabricatedOverlay", "android.content.om.FabricatedOverlay$Builder"}) {
            try { n += Xp.hookAllMethods(Class.forName(c), "setResourceValue", HOOK).size(); }
            catch (Throwable t) { Module.log("SuperDark: " + c + " unavailable", t); }
        }
        sHooks += n;
        // System UI only rebuilds its colour overlays when the wallpaper colour changes. Super Dark must also rebuild them
        // when the switch changes and when the phone goes between light and dark (quick settings tile, schedule), because
        // it only applies while the phone is dark: in light theme every colour and the blur are stock.
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
                    if (first) return;   // the boot build (and the boot check below) cover the current state
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
        // Scrims (the lock screen PIN/pattern background, the shade's dimming) carry their own tint: black while in effect.
        try {
            Class<?> sv = Class.forName("com.android.systemui.scrim.ScrimView", false, cl);
            int h = Xp.hookAllMethods(sv, "setTint", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof Integer)) return;
                    if (!effective(app())) {
                        // Blur Off in dark theme: the scrim behind the lock screen's PIN pad (cheapest checks first)
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
        // The lock screen PIN/pattern background (#2D303B) does not come from any system colour (preview92: still grey
        // after a boot with Super Dark from the first second, and after the scrim tint went black). It is painted
        // directly, so the stock panel greys are caught where System UI sets them on its views: only these exact colours, any alpha.
        int n2 = 0;
        // Not Paint.setColor / Canvas.drawColor: they run for everything System UI draws (status bar, shade frames), and
        // the logs show the lock screen colour always arrives through View.setBackgroundColor.
        n2 += hookPanel(android.graphics.drawable.ColorDrawable.class, "setColor");
        n2 += hookPanel(android.graphics.drawable.GradientDrawable.class, "setColor");
        n2 += hookPanel(android.view.View.class, "setBackgroundColor");
        sHooks += n2 + backdropHooks() + splashHooks(cl);
        Module.log("SuperDark: " + sHooks + " hooks");
    }

    /**
     * build 211 (owner: Blur Off, dark theme: pulling the shade down washed the black screen with a bright grey; asked
     * that nothing else changes): inside System UI only, the one colour named surface_effect_0_fallback (Android's
     * no-blur stand-in for the shade's blurred backdrop) is read as the stock blurred shade's dark tone #17181C, under
     * Blur Off in dark theme only (alpha kept). Found by name, so no other colour of the same value is touched, and no
     * other app is affected. Read through Resources.getColor and TypedArray.getColor (theme attributes).
     */
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
    /**
     * 2.0.6 (owner's slow-motion video: opening the Google app from search showed a blue-grey #202228 panel for a moment
     * before its black content): that panel is the app's starting window, which System UI draws in the app's own window
     * colour. Under Super Dark, for the Google app only, a neutral dark grey there is black (alpha kept).
     */
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
    /** A neutral dark grey (no real colour, darker than #404040). */
    private static boolean darkGrey(int c) {
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff, max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max > 0 && max <= 0x40 && max - min <= 0x14;
    }
    private static final android.util.SparseIntArray sBackdropKind = new android.util.SparseIntArray();   // id -> 1 other, 3 the backdrop
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
    /**
     * build 210: the view (or the view a drawable belongs to) is part of the lock screen's PIN / pattern / password
     * screen: its name, or one of its parents', says Bouncer or KeyguardSecurity / KeyguardPin / KeyguardPassword /
     * KeyguardPattern. Returns that name, or null.
     */
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
    /** The lock screen's PIN / pattern / password screen is on screen right now (the last one coloured). */
    private static boolean bouncerShown() {
        Object v = sBouncer == null ? null : sBouncer.get();
        return v instanceof android.view.View && ((android.view.View) v).isShown() && ((android.view.View) v).getAlpha() > 0f;
    }
    private static String scrimName(Object scrim) { try { return String.valueOf(Xp.getObjectField(scrim, "mScrimName")); } catch (Throwable t) { return null; } }
    private static boolean keyguardLocked() {
        try { android.app.KeyguardManager k = (android.app.KeyguardManager) app().getSystemService(Context.KEYGUARD_SERVICE); return k != null && k.isKeyguardLocked(); } catch (Throwable t) { return false; }
    }
    /** Blur Off in dark theme (not Super Dark: that is black). */
    private static boolean blurOffDark() {
        try { Context c = app(); return c != null && SurfaceStyle.get(c) == SurfaceStyle.OFF && night(c); } catch (Throwable t) { return false; }
    }
    private static long sEffAt; private static boolean sEff;

    /** effective(), checked at most twice a second (for hooks on paths that run every frame). */
    private static boolean effectiveCached() {
        long now = android.os.SystemClock.uptimeMillis();
        if (sEffAt == 0 || now - sEffAt > 500) { sEff = effective(app()); sEffAt = now; }
        return sEff;
    }

    /** The stock dark panel greys of Android 17 (surface effect / panel fallback and the lock screen background). */
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
                        // build 208 (owner: Blur Off in dark theme, the lock screen PIN pad bluish; it should look like the
                        // stock one): with window blur off, Android paints its no-blur panel grey (#2C303D and kin) where the
                        // blurred lock screen and shade would be. Under Blur Off in dark theme it is the colour the stock,
                        // blurred lock screen shows (#17181C) instead; the keys over it follow, as in the stock one.
                        if (!blurOffDark()) return;
                        // build 210 (owner: "why did you change the control center look"): only the lock screen's own
                        // PIN / pattern / password views; the shade and quick settings keep Android's look.
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

    /** inEffect, checked at most twice a second (Pixel Suite's drawing hooks in other apps, DarkChips). */
    static boolean inEffectNow() { try { return effectiveCached(); } catch (Throwable t) { return false; } }

    /** For other parts of Pixel Suite (Doodles' menu, in any app): Super Dark is in effect right now. */
    static boolean inEffect(Context ctx) { try { return effective(ctx); } catch (Throwable t) { return false; } }

    /** Super Dark is in effect: switched on and the phone is in dark theme right now. */
    private static boolean effective(Context ctx) {
        if (!switchedOn(ctx)) return false;
        return night(ctx);
    }

    /** System property mirror of the switch, readable from the first moment of boot (written by the switch, as root). */
    static final String PROP = "persist.pixelsuite.superdark";

    /**
     * The switch. preview90 log: System UI's lock screen (bouncer) took its colours at 17:33:56, before the settings could
     * be read; the boot rebuild at 17:34:01 came too late for it and it stayed grey. The property is readable right away,
     * so the very first colours System UI builds at boot are already Super Dark. Falls back to the setting.
     */
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

    /** The phone is in dark theme right now (system configuration, which every process gets first). */
    private static boolean night(Context ctx) {
        int mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK, yes = android.content.res.Configuration.UI_MODE_NIGHT_YES;
        try { return (android.content.res.Resources.getSystem().getConfiguration().uiMode & mask) == yes; }
        catch (Throwable t) {
            try { return (ctx.getResources().getConfiguration().uiMode & mask) == yes; } catch (Throwable u) { return true; }
        }
    }

    /** A possible light/dark change: check now-ish and once more a moment later (the configuration can arrive late). */
    private static void scheduleSync(final String why) {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() { public void run() { sync(why); } }, 300);
        h.postDelayed(new Runnable() { public void run() { sync(why + ", again"); } }, 1500);
    }

    /**
     * Window blur follows Super Dark: off while it is in effect (so the drawer, folders and shade are solid black), back on
     * in light theme or when it is switched off, but only if Super Dark was the one that turned it off. System UI may
     * write these settings itself.
     */
    private static void syncBlur(Context ctx, boolean eff) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            // build 190: the Themes slider's Blur Off keeps the phone's window blur off as well (the slider itself turns
            // it off and on; this only keeps a Super Dark change from turning it back on while Blur Off is chosen).
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

    /** Tells System UI to build its colour overlays again on its next evaluation. */
    private static boolean needCreation(Object controller) {
        try {
            // Pixel's ThemeOverlayControllerGoogle extends the AOSP class, which holds the field.
            java.lang.reflect.Field f = null;
            for (Class<?> k = controller.getClass(); k != null && f == null; k = k.getSuperclass()) {
                try { f = k.getDeclaredField("mNeedsOverlayCreation"); } catch (NoSuchFieldException ignored) { }
            }
            if (f == null) throw new NoSuchFieldException("mNeedsOverlayCreation");
            f.setAccessible(true); f.setBoolean(controller, true);
            return true;
        } catch (Throwable t) { Module.log("SuperDark: could not rebuild colours", t); return false; }
    }

    /** Brings System UI's colours and the blur in line with the current state, if they are not already. */
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

    /**
     * preview87 log: at boot System UI builds and applies its colours before the settings can be read, so the phone
     * booted grey until the switch was flipped. A few seconds after boot the state is checked again (sApplied is cleared
     * so the check always rebuilds when Super Dark is in effect). From then on, every switch between light and dark
     * theme (quick settings tile, schedule) is followed through a configuration listener.
     */
    private static void start() {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries;
            public void run() {
                final Context ctx = app();
                if (ctx == null) { if (++tries < 10) h.postDelayed(this, 2000); return; }
                sApplied = effective(ctx) ? Boolean.FALSE : null;   // force a rebuild when it should be in effect
                if (sApplied != null) sync("after boot"); else { sApplied = Boolean.FALSE; syncBlur(ctx, false); }
                // preview89: a configuration listener alone missed the quick settings tile (no rebuild logged after the
                // switch to light at 17:23:51). Three triggers now: configuration changes, the dark theme setting itself
                // (tile, Settings) and System UI's configuration controller; each only rebuilds when the state differs.
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
            if (!effective(ctx)) return;   // light theme or switched off: stock colours (works before the app context exists)
            Integer black = colorFor(name, config);
            if (black == null) return;
            p.args[2] = black;
        }
    };

    /**
     * The OLED colour for a system colour, or null to leave it. Only background/surface roles change: "on_*" colours
     * (text and icons drawn on a surface) and the inverse surface are never touched. preview81 matched loosely and
     * also darkened system_on_surface_variant_dark, the secondary text colour, which made summaries unreadable.
     */
    static Integer colorFor(String n, String config) {
        // The two neutral palettes, which most backgrounds are drawn from (Settings, Pixel Launcher, the shade, Gboard,
        // the search widgets). Android 17 writes a separate "_dark" copy of each palette for dark theme (preview82 log),
        // so only that copy changes and light theme keeps its palette. Older versions write one copy (no suffix).
        if (n.startsWith("system_neutral1_") || n.startsWith("system_neutral2_") || n.startsWith("system_accent2_")) {
            // preview85: the "_dark" copies alone changed nothing in Gboard or the drawer, so the plain palette (which
            // apps reach as system_neutral1_900 etc.) follows the "_light" copy. All copies change: in light theme these
            // shades are dark text and icons, which only get a little darker.
            String shade = n.substring(n.indexOf('_', 7) + 1);
            if (shade.endsWith("_dark")) shade = shade.substring(0, shade.length() - 5);
            else if (shade.endsWith("_light")) shade = shade.substring(0, shade.length() - 6);
            if (n.startsWith("system_accent2_"))   // the stock drawer and quick settings panel colour (#2f3036)
                return shade.equals("800") ? 0xff000000 : null;
            boolean n2 = n.startsWith("system_neutral2_");
            if (shade.equals("1000") || shade.equals("900")) return 0xff000000;
            if (shade.equals("800")) return n2 ? 0xff181818 : 0xff161616;
            return null;
        }
        // Dark tokens: "<role>_dark", or the role written without a suffix for the night configuration.
        String role;
        if (n.endsWith("_dark")) role = n.substring(0, n.length() - 5);
        else if (config != null && ("-" + config + "-").contains("-night-")) role = n;
        else return null;
        if (role.startsWith("system_")) role = role.substring(7);
        if (role.startsWith("materialColor")) role = role.substring(13);   // SystemUI/SettingsLib camel-case names
        role = role.replace("_", "").toLowerCase(java.util.Locale.ROOT);
        switch (role) {
            case "background": case "surface": case "surfacedim": case "surfacecontainerlowest": case "surfaceheader":
                return 0xff000000;
            case "surfacecontainerlow": return 0xff060606;
            case "surfacecontainer": return 0xff0b0b0b;
            case "surfacecontainerhigh": return 0xff121212;
            case "surfacecontainerhighest": return 0xff1a1a1a;
            case "surfacebright": return 0xff0e0e0e;   // System UI dialogs ("Active apps") sit on it
            case "surfacevariant": return 0xff161616;
            // Android 16+/17 frosted surfaces: the app drawer, folders, quick settings, notifications and the shade are
            // drawn with these "effect" colours over a blur (preview83 log: the plain surfaces changed but these screens
            // did not). Opaque black for the base layer, small steps up for the layers drawn on it.
            case "surfaceeffect0": case "surfaceeffect0fallback": return 0xff000000;
            case "surfaceeffect1": return 0xff0e0e0e;
            // 2 and 3 are light veils laid on top of other layers (stock #26dfe2f2 / #1adfe2f2); kept as veils, softer.
            case "surfaceeffect2": return 0x14ffffff;
            case "surfaceeffect3": return 0x0fffffff;
            // Quick settings tiles that are off, and disabled ones.
            case "shadeinactive": return 0xff161616;
            case "shadedisabled": return 0xff0e0e0e;
            // Home screen widgets (the Google search bar, At a Glance cards) and the recents background.
            case "widgetbackground": return 0xff121212;
            case "overviewbackground": case "undersurface": return 0xff000000;
            default: return null;
        }
    }

    private static volatile Context sApp;
    private static java.lang.reflect.Method sCurrentApplication;
    /** System UI's application context (looked up once it exists). */
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

    // ------------------------------------------------------------------ the switch (Pixel Suite app, root shell)

    /** Makes System UI rebuild its colours now (as the stock Wallpaper & style app does). */
    private static final String REAPPLY =
        "j=$(settings get secure theme_customization_overlay_packages); t=$(date +%s)000; "
        + "case \"$j\" in ''|null|'{}') j=\"{\\\"_applied_timestamp\\\":$t}\";; "
        + "*_applied_timestamp*) j=$(echo \"$j\" | sed \"s/\\\"_applied_timestamp\\\":[0-9]*/\\\"_applied_timestamp\\\":$t/\");; "
        + "*) j=$(echo \"$j\" | sed \"s/^{/{\\\"_applied_timestamp\\\":$t,/\");; esac; "
        + "settings put secure theme_customization_overlay_packages \"$j\"";

    /** The root shell commands for the switch (run after the setting itself is saved). */
    static String commands(boolean on) {
        StringBuilder b = new StringBuilder();
        // First, so System UI's rebuild below already reads the new value (and every boot after this one).
        b.append("setprop ").append(PROP).append(on ? " 1; " : " 0; ");
        // preview80's separate overlays: switch them off once if they are still there.
        b.append("if cmd overlay list 2>/dev/null | grep -q '\\[x\\] com.android.shell:").append(OLD_PREFIX).append("'; then for i in");
        for (int i = 0; i < OLD_COUNT; i++) b.append(' ').append(i);
        b.append("; do cmd overlay disable --user 0 com.android.shell:").append(OLD_PREFIX).append("$i >/dev/null 2>&1; done; fi; ");
        if (on) {
            // Dark mode on, unless a schedule is in charge; remember that we turned it on.
            b.append("m=$(cmd uimode night 2>/dev/null); case \"$m\" in *\": no\"*) cmd uimode night yes; settings put secure ")
             .append(KEY_TURNED_DARK).append(" 1;; esac; ");
            // Window blur (the drawer, folders, shade and quick settings are a blur of the wallpaper under a see-through
            // tint, which no colour can make black) is turned off by System UI itself while Super Dark is in effect, and
            // back on in light theme; see syncBlur.
        } else {
            b.append("if [ \"$(settings get secure ").append(KEY_TURNED_DARK).append(")\" = 1 ]; then cmd uimode night no; fi; settings put secure ")
             .append(KEY_TURNED_DARK).append(" 0; ");
            b.append("if [ \"$(settings get secure ").append(KEY_BLUR_OFF).append(")\" = 1 ]; then settings put global disable_window_blurs 0; fi; settings put secure ")
             .append(KEY_BLUR_OFF).append(" 0; ");
        }
        b.append(REAPPLY);
        // Gboard and the Google app keep their colours until they restart; both come straight back when next used.
        b.append("; sleep 1; am force-stop com.google.android.inputmethod.latin >/dev/null 2>&1; am force-stop com.google.android.googlequicksearchbox >/dev/null 2>&1;");
        b.append(" true");
        return b.toString();
    }
}
