package io.github.pixelsuite;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

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

    private static final Set<String> sLogged = new HashSet<String>();

    static void installSystemUi(ClassLoader cl) {
        int n = 0;
        for (String c : new String[] {"android.content.om.FabricatedOverlay", "android.content.om.FabricatedOverlay$Builder"}) {
            try { n += Xp.hookAllMethods(Class.forName(c), "setResourceValue", HOOK).size(); }
            catch (Throwable t) { Module.log("SuperDark: " + c + " unavailable", t); }
        }
        Module.log("SuperDark: colour hooks " + n);
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
        // Scrims (the lock screen PIN/pattern background, the shade's dimming) carry their own tint: black while in effect.
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
        // The lock screen PIN/pattern background (#2D303B) does not come from any system colour (preview92: still grey
        // after a boot with Super Dark from the first second, and after the scrim tint went black). It is painted
        // directly, so the stock panel greys are caught where System UI sets them on its views: only these exact colours, any alpha.
        int n2 = 0;
        // Not Paint.setColor / Canvas.drawColor: they run for everything System UI draws (status bar, shade frames), and
        // the logs show the lock screen colour always arrives through View.setBackgroundColor.
        n2 += hookPanel(android.graphics.drawable.ColorDrawable.class, "setColor");
        n2 += hookPanel(android.graphics.drawable.GradientDrawable.class, "setColor");
        n2 += hookPanel(android.view.View.class, "setBackgroundColor");
        Module.log("SuperDark: panel colour hooks " + n2);
    }

    private static int sScrimLogged2;
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
    private static boolean switchedOn(Context ctx) {
        try {
            String v = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, PROP);
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
            synchronized (sLogged) { sLogged.clear(); }
            return true;
        } catch (Throwable t) { Module.log("SuperDark: could not rebuild colours", t); return false; }
    }

    /** Brings System UI's colours and the blur in line with the current state, if they are not already. */
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
            synchronized (sLogged) { if (sLogged.add("build")) Module.log("SuperDark: System UI colours built with Super Dark"); }   // once per build
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

    private static String hex(int c) { String h = Integer.toHexString(c); while (h.length() < 8) h = "0" + h; return h; }

    private static Context app() {
        try { return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); }
        catch (Throwable t) { return null; }
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
