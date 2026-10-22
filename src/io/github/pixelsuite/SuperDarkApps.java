package io.github.pixelsuite;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;

/**
 * Super Dark-Mode inside Google apps that work out their own dark colours instead of using the
 * system ones (Gboard; the Google app's search page). Logs showed neither changed with any system
 * colour, even after a restart. In these apps only, the colours they hand to Android for
 * backgrounds (resources, colour state lists, solid and gradient shapes, view backgrounds) are
 * passed through a remap: neutral dark greys go to black, the slightly lighter greys used for keys,
 * chips and cards go to near-black, keeping their order so keys still stand out from the keyboard.
 * Light colours, text on dark, accents and anything with real colour are untouched.
 */
final class SuperDarkApps {
    static final String GBOARD = "com.google.android.inputmethod.latin";
    private SuperDarkApps() {}

    private static long sAt;
    private static boolean sActive;

    static final String SETTINGS = "com.android.settings";
    /** 2.0.6 (owner: Settings' search page was not fully black): the search page is its own app, Settings Intelligence. */
    static final String SETTINGS_SEARCH = "com.google.android.settings.intelligence";
    /** Greys up to this become black; above it they step from near-black. Settings keeps its cards a step above. */
    private static int sBlackMax = 0x2c;

    /** Hook handles, so the launcher can remove its set while the home screen is in front. */
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
        // Compose screens (the Google app's search page) paint their backgrounds straight through Paint, never through
        // resources or drawables (preview86: the keyboard turned black, the search page did not). Only in these apps.
        // Paint.setColor runs for every piece of text and shape an app draws, so it is only hooked where needed: the
        // Google app and Settings (Compose). Gboard went black without it (preview86).
        if (!GBOARD.equals(pkg)) n += hookFirstArg(android.graphics.Paint.class, "setColor");
        // 2.0.6-preview11 (the Google feed's grey panel survived everything else): newer Compose screens hand Paint their
        // colours as a packed long (Paint.setColor(long)), which the int hook above never sees. Same remap for those.
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
                    int[] in = (int[]) p.args[0], out = null;   // copied only when a colour really changes
                    for (int i = 0; i < in.length; i++) { int v = remap(in[i]); if (v != in[i]) { if (out == null) out = in.clone(); out[i] = v; } }
                    if (out != null) p.args[0] = out;
                }
            }));
        } catch (Throwable t) { Module.log("SuperDark apps: setColors", t); }
        // 2.0.6 (owner's slow-motion videos: opening the Google feed, or the Google app from search, showed a blue-grey
        // #202228 panel for a moment before its black content): in the Google app only, a plain colour background (the
        // windows' own background, inflated from the theme, which no colour call above sees) takes the same remap.
        if (AliasProvider.GOOGLE.equals(pkg)) {
            try {
                n += keep(Xp.hookAllMethods(android.view.View.class, "setBackgroundDrawable", new Xp.Callback() {
                    @Override protected void beforeHookedMethod(Xp.Param p) {
                        if (p.args.length != 1 || !(p.args[0] instanceof android.graphics.drawable.ColorDrawable) || !active()) return;
                        // preview9: the same drawable takes the colour (a new one broke apps that fade their own drawable away:
                        // Google search stayed blank)
                        android.graphics.drawable.ColorDrawable d = (android.graphics.drawable.ColorDrawable) p.args[0];
                        int c = d.getColor(), v = remap(c);
                        if (v != c) d.setColor(v);
                    }
                }));
            } catch (Throwable t) { Module.log("SuperDark apps: window background", t); }
            // 2.0.6-preview10 (the feed's grey panel is not its window, nor a starting window): Android paints a window's
            // task background colour under it while it slides in, taken from the app's theme (#202228). Each Google app
            // screen tells Android its background is black instead, under Super Dark.
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
                n += keep(Xp.hookAllMethods(android.app.Activity.class, "onCreate", black));   // as early as the screen exists
                n += keep(Xp.hookAllMethods(android.app.Activity.class, "onResume", black));
            } catch (Throwable t) { Module.log("SuperDark apps: task background", t); }
        }
        // Pixel Launcher re-installs these each time one of its settings screens comes to the front: one line is enough.
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

    /**
     * Pixel Launcher: the scrim behind the app drawer (the strip under the status bar above the drawer sheet) keeps a
     * fixed grey of its own. Under Super Dark it keeps its alpha but turns black.
     */
    private static boolean sScoped; private static volatile boolean sForeign;

    /**
     * Pixel Launcher's own settings screens (Home settings and its sub-pages) keep stock greys, such as the header that
     * fills in grey when the list is scrolled. The same colour remap as Gboard/Settings runs in the launcher process,
     * but only while one of those screens is in front: the home screen, drawer and folders are never touched.
     */
    static void installLauncherSettings(final ClassLoader cl) {
        sScoped = true;
        sBlackMax = 0x50;   // these screens are plain lists on black: every dark grey (the scrolled header too) goes black
        try {
            final Class<?> home = Class.forName("com.android.launcher3.Launcher", false, cl);
            Xp.hookAllMethods(android.app.Activity.class, "onResume", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    sForeign = !home.isInstance(p.thisObject); sAt = 0;
                    // The colour hooks only exist while a settings screen is in front: the home screen, drawer and
                    // folders draw with no extra work at all.
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

    /** On and the app is in dark theme (checked at most twice a second). */
    static boolean active() {
        if (sScoped && !sForeign) return false;   // Pixel Launcher: only its settings screens, never the home screen
        return activeAnywhere();
    }

    /** Super Dark on and dark theme, whatever screen of the app is in front. */
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
    /** The app's Application (one per process, so kept once found); the lookup method is resolved once. */
    private static Context app() throws Exception {
        Context c = sApp;
        if (c != null) return c;
        if (sCurrentApplication == null) sCurrentApplication = Class.forName("android.app.ActivityThread").getMethod("currentApplication");
        return sApp = (Context) sCurrentApplication.invoke(null);
    }

    /** Neutral dark greys to black / near-black; everything else unchanged. Alpha is kept. */
    static int remap(int c) {
        int a = c >>> 24; if (a == 0) return c;
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max > 0x50 || max - min > 0x14 || max == 0) return c;
        if (sBlackMax >= 0x50) return a << 24; // Launcher settings: neutral surfaces are black, including repeated passes.
        // A resource can pass through a ColorStateList and a drawable hook as well. Keep the output
        // shades as fixed points so the second pass cannot turn a key/card surface into black.
        if (r == g && g == b && (max == 0x10 || max == 0x18 || max == 0x24)) return c;
        int B = sBlackMax;
        int v = max <= B ? 0 : max <= B + (0x50 - B) / 3 ? 0x10 : max <= B + 2 * (0x50 - B) / 3 ? 0x18 : 0x24;
        if (v == max) return c;
        return (a << 24) | (v << 16) | (v << 8) | v;
    }
}
