package io.github.pixelsuite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class MiscSystem {
    private MiscSystem() {}
    static final String SECURE_KEY = "pixelsuite_allow_secure_screenshots";
    static final String A11Y_KEY = "pixelsuite_hide_accessibility";
    static final String A11Y_APPS_KEY = "pixelsuite_hide_accessibility_apps";
    static final String DEV_KEY = "pixelsuite_hide_developer_options";
    private static final Set<String> DEV_SETTINGS = new HashSet<String>(java.util.Arrays.asList("development_settings_enabled", "adb_enabled", "adb_wifi_enabled"));
    private static volatile boolean devOn;
    private static final Map<Integer, Boolean> regularApp = new ConcurrentHashMap<Integer, Boolean>();

    private static volatile boolean secureOn, a11yOn;
    private static volatile Set<String> a11yApps = Collections.emptySet();
    private static volatile Context system;
    private static final Map<Integer, String[]> uidPackages = new ConcurrentHashMap<Integer, String[]>();
    private static final ThreadLocal<Boolean> inside = new ThreadLocal<Boolean>();

    static void installSystem(ClassLoader cl) {
        int n = 0;
        try {
            n += Xp.hookAllMethods(Xp.findClass("com.android.server.am.ActivityManagerService", cl), "systemReady", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { start((Context) Xp.getObjectField(p.thisObject, "mContext")); } catch (Throwable error) { Module.log("Miscellaneous: start failed", error); }
                }
            }).size();
        } catch (Throwable error) { Module.log("Miscellaneous: start hook unavailable", error); }
        n += installSecure(cl);
        n += installAccessibility(cl);
        n += installSettingsBridge();
        n += installSettingsQuery();
        Module.log("Miscellaneous: " + n + " system hooks");
    }

    private static synchronized void start(final Context c) {
        if (system != null || c == null) return;
        system = c;
        HandlerThread t = new HandlerThread("PixelSuiteMisc"); t.start();
        final Handler h = new Handler(t.getLooper());
        final Runnable load = new Runnable() { public void run() {
            try {
                secureOn = "1".equals(Settings.Secure.getString(c.getContentResolver(), SECURE_KEY));
                a11yOn = "1".equals(Settings.Secure.getString(c.getContentResolver(), A11Y_KEY));
                devOn = "1".equals(Settings.Secure.getString(c.getContentResolver(), DEV_KEY));
                Module.log("Miscellaneous: secure screenshots " + (secureOn ? "on" : "off") + ", hide accessibility " + (a11yOn ? "on" : "off") + " (" + a11yApps.size() + " apps), hide developer options " + (devOn ? "on" : "off"));
                a11yApps = Collections.unmodifiableSet(PrivacyLock.parse(Settings.Secure.getString(c.getContentResolver(), A11Y_APPS_KEY)));
            } catch (Throwable error) { Module.log("Miscellaneous: settings unreadable", error); }
        } };
        h.post(load);
        h.post(new Runnable() { public void run() { findProvider(h); } });
        try {
            ContentObserver o = new ContentObserver(h) { @Override public void onChange(boolean self) { load.run(); } };
            for (String k : new String[] { SECURE_KEY, A11Y_KEY, A11Y_APPS_KEY, DEV_KEY })
                c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(k), false, o);
            IntentFilter f = new IntentFilter(); f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REMOVED); f.addDataScheme("package");
            c.registerReceiver(new BroadcastReceiver() { @Override public void onReceive(Context x, Intent i) { uidPackages.clear(); regularApp.clear(); } }, f, null, h);
        } catch (Throwable error) { Module.log("Miscellaneous: settings changes not followed", error); }
    }

    private static int installSecure(ClassLoader cl) {
        int n = 0;
        try {
            n += Xp.hookAllMethods(Xp.findClass("com.android.server.wm.ActivityTaskManagerService", cl), "registerScreenCaptureObserver", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) { if (secureOn) p.setResult(null); }
            }).size();
        } catch (Throwable error) { Module.log("Secure screenshots: capture observer unavailable", error); }
        try {
            n += Xp.hookAllMethods(Xp.findClass("com.android.server.wm.WindowManagerService", cl), "registerScreenRecordingCallback", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) { if (secureOn) p.setResult(Boolean.FALSE); }
            }).size();
        } catch (Throwable error) { Module.log("Secure screenshots: recording callback unavailable", error); }
        try {
            Class<?> capture, args; String fieldName; final boolean policy;
            Class<?> internal = null; try { internal = Class.forName("android.window.ScreenCaptureInternal", false, cl); } catch (Throwable ignored) { }
            if (internal != null) { capture = internal; args = Class.forName("android.window.ScreenCaptureInternal$CaptureArgs", false, cl); fieldName = "mSecureContentPolicy"; policy = true; }
            else { capture = Class.forName("android.window.ScreenCapture", false, cl); args = Class.forName("android.window.ScreenCapture$CaptureArgs", false, cl); fieldName = "mCaptureSecureLayers"; policy = false; }
            final Field field = args.getDeclaredField(fieldName); field.setAccessible(true);
            Xp.Callback withSecure = new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (!secureOn || p.args.length == 0 || p.args[0] == null) return;
                    try { if (policy) field.setInt(p.args[0], 1); else field.setBoolean(p.args[0], true); } catch (Throwable ignored) { }
                }
            };
            n += Xp.hookAllMethods(capture, "nativeCaptureDisplay", withSecure).size();
            n += Xp.hookAllMethods(capture, "nativeCaptureLayers", withSecure).size();
        } catch (Throwable error) { Module.log("Secure screenshots: screen capture unavailable", error); }
        try {
            n += Xp.hookAllMethods(Xp.findClass("com.android.server.display.DisplayControl", cl), "createVirtualDisplay", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) { if (secureOn && p.args.length == 2 && p.args[1] instanceof Boolean) p.args[1] = Boolean.TRUE; }
            }).size();
        } catch (Throwable error) { Module.log("Secure screenshots: display control unavailable", error); }
        try {
            n += Xp.hookAllMethods(Xp.findClass("com.android.server.display.VirtualDisplayAdapter", cl), "createVirtualDisplayLocked", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (!secureOn || p.args.length < 4) return;
                    int caller = p.args[2] instanceof Integer ? (Integer) p.args[2] : 0;
                    if (caller >= 10000 && p.args[1] == null) return;
                    for (int i = 3; i < p.args.length; i++) if (p.args[i] instanceof Integer) { p.args[i] = ((Integer) p.args[i]) | 4 ; return; }
                }
            }).size();
        } catch (Throwable error) { Module.log("Secure screenshots: virtual displays unavailable", error); }
        try {
            Class<?> state = Xp.findClass("com.android.server.wm.WindowState", cl);
            n += Xp.hookAllMethods(state, "isSecureLocked", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (!secureOn) return;
                    for (StackTraceElement f : new Throwable().getStackTrace()) {
                        String m = f.getMethodName();
                        if (("setInitialSurfaceControlProperties".equals(m) || "createSurfaceLocked".equals(m)) && f.getClassName().startsWith("com.android.server.wm.")) return;
                    }
                    p.setResult(Boolean.FALSE);
                }
            }).size();
            Xp.deoptimizeAll(Xp.findClass("com.android.server.wm.WindowStateAnimator", cl), "createSurfaceLocked");
            Xp.deoptimizeAll(Xp.findClass("com.android.server.wm.WindowManagerService", cl), "relayoutWindow");
            for (int i = 0; i < 20; i++) {
                try { Class<?> k = Class.forName("com.android.server.wm.RootWindowContainer$$ExternalSyntheticLambda" + i, false, cl); if (java.util.function.BiConsumer.class.isAssignableFrom(k)) Xp.deoptimizeAll(k, "accept"); } catch (Throwable ignored) { }
                try { Class<?> k = Class.forName("com.android.server.wm.DisplayContent$" + i, false, cl); if (java.util.function.BiPredicate.class.isAssignableFrom(k)) Xp.deoptimizeAll(k, "test"); } catch (Throwable ignored) { }
            }
        } catch (Throwable error) { Module.log("Secure screenshots: secure windows unavailable", error); }
        return n;
    }

    private static boolean hiddenFromCaller() {
        if (!a11yOn || a11yApps.isEmpty()) return false;
        int uid = Binder.getCallingUid();
        if (uid < 10000) return false;
        String[] pkgs = uidPackages.get(uid);
        if (pkgs == null) {
            Context c = system; if (c == null) return false;
            long id = Binder.clearCallingIdentity();
            try { pkgs = c.getPackageManager().getPackagesForUid(uid); } catch (Throwable t) { pkgs = null; } finally { Binder.restoreCallingIdentity(id); }
            if (pkgs == null) pkgs = new String[0];
            uidPackages.put(uid, pkgs);
        }
        Set<String> list = a11yApps;
        for (String p : pkgs) if (list.contains(p)) return fromAppItself();
        return false;
    }

    private static boolean fromAppItself() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String k = e.getClassName();
            if (k.startsWith("android.provider.Settings") || k.startsWith("android.content.ContentResolver") || k.startsWith("android.content.ContentProviderClient")) return false;
        }
        return true;
    }

    private static int installAccessibility(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> ams = Xp.findClass("com.android.server.accessibility.AccessibilityManagerService", cl);
            n += Xp.hookAllMethods(ams, "addClient", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 2 && hiddenFromCaller()) p.setResult(0L);
                }
            }).size();
            n += Xp.hookAllMethods(ams, "getEnabledAccessibilityServiceList", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (hiddenFromCaller()) p.setResult(new java.util.ArrayList<Object>());
                }
            }).size();
        } catch (Throwable error) { Module.log("Hide accessibility: unavailable", error); }
        return n;
    }

    private static volatile boolean providerHooked;

    private static int installSettingsBridge() {
        try {
            return Xp.hookAllMethods(Class.forName("android.content.ContentProvider$Transport"), "call", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (providerHooked) return;
                    String[] s = strings(p.args); if (s == null || !"settings".equals(s[0])) return;
                    callBefore(p, s[1], s[2]);
                }
                @Override protected void afterHookedMethod(Xp.Param p) {
                    if (providerHooked) return;
                    String[] s = strings(p.args); if (s == null || !"settings".equals(s[0])) return;
                    callAfter(p, s[1], s[2]);
                }
            }).size();
        } catch (Throwable error) { Module.log("Miscellaneous: settings bridge unavailable", error); return 0; }
    }

    private static int hookProvider(Object provider) {
        int n = 0;
        try {
            n += Xp.hookAllMethods(provider.getClass(), "call", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) { String[] m = methodArg(p.args); if (m != null) callBefore(p, m[0], m[1]); }
                @Override protected void afterHookedMethod(Xp.Param p) { String[] m = methodArg(p.args); if (m != null) callAfter(p, m[0], m[1]); }
            }).size();
            n += Xp.hookAllMethods(provider.getClass(), "query", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    android.net.Uri uri = null; for (Object a : p.args) if (a instanceof android.net.Uri) { uri = (android.net.Uri) a; break; }
                    if (uri != null) queryAfter(p, uri);
                }
            }).size();
        } catch (Throwable error) { Module.log("Miscellaneous: settings provider hooks failed", error); }
        return n;
    }
    private static String[] methodArg(Object[] args) {
        int b = args.length; for (int i = 0; i < args.length; i++) if (args[i] instanceof Bundle) { b = i; break; }
        if (b < 2) return null;
        Object method = args[b - 2], arg = args[b - 1];
        if (!(method instanceof String) || (arg != null && !(arg instanceof String))) return null;
        return new String[] { (String) method, (String) arg };
    }
    private static int providerTries;
    private static void findProvider(final Handler h) {
        Object found = null;
        try {
            Object at = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
            for (String field : new String[] { "mLocalProvidersByName", "mLocalProviders" }) {
                Object map; try { map = Xp.getObjectField(at, field); } catch (Throwable t) { continue; }
                if (!(map instanceof Map)) continue;
                for (Object rec : ((Map<?, ?>) map).values()) {
                    Object prov = null; try { prov = Xp.getObjectField(rec, "mLocalProvider"); } catch (Throwable ignored) { }
                    if (prov != null && prov.getClass().getName().endsWith(".SettingsProvider")) { found = prov; break; }
                }
                if (found != null) break;
            }
        } catch (Throwable error) { Module.log("Miscellaneous: provider lookup failed", error); }
        if (found == null) {
            if (++providerTries < 10) h.postDelayed(new Runnable() { public void run() { findProvider(h); } }, 3000);
            else Module.log("Miscellaneous: settings provider not found (Binder hooks kept)");
            return;
        }
        int n = hookProvider(found);
        providerHooked = n > 0;
        Module.log("Miscellaneous: settings provider " + found.getClass().getName() + ": " + n + " hooks");
    }

    private static void callBefore(Xp.Param p, String method, String arg) {
        if (Boolean.TRUE.equals(inside.get())) return;
        if (!"GET_secure".equals(method) || arg == null) return;
        if (!arg.startsWith("pixelsuite_") || Binder.getCallingUid() < 10000) return;
        Context c = system; if (c == null) return;
        inside.set(Boolean.TRUE);
        long id = Binder.clearCallingIdentity();
        try {
            Bundle b = new Bundle();
            b.putString("value", Settings.Secure.getString(c.getContentResolver(), arg));
            p.setResult(b);
        } catch (Throwable ignored) {
        } finally { Binder.restoreCallingIdentity(id); inside.remove(); }
    }

    private static void callAfter(Xp.Param p, String method, String arg) {
        if (Boolean.TRUE.equals(inside.get())) return;
        if (method != null && method.startsWith("LIST_") && p.getResult() instanceof Bundle && devOn && devHiddenFromCaller()) {
            Bundle b = new Bundle((Bundle) p.getResult());
            for (String key : new java.util.ArrayList<String>(b.keySet())) {
                Object v = b.get(key);
                if (!(v instanceof java.util.ArrayList)) continue;
                java.util.ArrayList<String> lines = new java.util.ArrayList<String>();
                boolean changed = false;
                for (Object o : (java.util.ArrayList<?>) v) {
                    String line = String.valueOf(o); int eq = line.indexOf('=');
                    if (eq > 0 && DEV_SETTINGS.contains(line.substring(0, eq))) { lines.add(line.substring(0, eq) + "=0"); changed = true; } else lines.add(line);
                }
                if (changed) b.putStringArrayList(key, lines);
            }
            p.setResult(b);
            return;
        }
        if (arg == null) return;
        String value;
        if (("GET_global".equals(method) || "GET_secure".equals(method)) && DEV_SETTINGS.contains(arg)) {
            if (!devHiddenFromCaller() || !(p.getResult() instanceof Bundle)) return;
            value = "0";
        } else {
            if (!"GET_secure".equals(method)) return;
            if ("enabled_accessibility_services".equals(arg)) value = "";
            else if ("accessibility_enabled".equals(arg)) value = "0";
            else return;
            if (!hiddenFromCaller() || !(p.getResult() instanceof Bundle)) return;
        }
        Bundle b = new Bundle((Bundle) p.getResult());
        b.putString("value", value);
        b.putInt("_generation_index", -1);
        b.remove("_track_generation");
        p.setResult(b);
    }

    private static boolean devHiddenFromCaller() {
        if (!devOn) return false;
        int uid = Binder.getCallingUid();
        if (uid < 10000) return false;
        Boolean regular = regularApp.get(uid);
        if (regular == null) {
            Context c = system; if (c == null) return false;
            boolean r = true;
            long id = Binder.clearCallingIdentity();
            try {
                android.content.pm.PackageManager pm = c.getPackageManager();
                String[] pkgs = pm.getPackagesForUid(uid);
                if (pkgs == null || pkgs.length == 0) r = false;
                else for (String pkg : pkgs) {
                    if (PrivacyLock.OUR.equals(pkg)) { r = false; break; }
                    int flags = pm.getApplicationInfo(pkg, 0).flags;
                    if ((flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) { r = false; break; }
                }
            } catch (Throwable t) { r = false; } finally { Binder.restoreCallingIdentity(id); }
            regular = r; regularApp.put(uid, regular);
        }
        return regular && fromAppItself();
    }

    private static int installSettingsQuery() {
        try {
            return Xp.hookAllMethods(Class.forName("android.content.ContentProvider$Transport"), "query", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    if (providerHooked) return;
                    android.net.Uri uri = null; for (Object a : p.args) if (a instanceof android.net.Uri) { uri = (android.net.Uri) a; break; }
                    if (uri == null || !"settings".equals(uri.getAuthority())) return;
                    queryAfter(p, uri);
                }
            }).size();
        } catch (Throwable error) { Module.log("Hide developer options: settings query unavailable", error); return 0; }
    }
    private static void queryAfter(Xp.Param p, android.net.Uri uri) {
        if (!devOn || Boolean.TRUE.equals(inside.get())) return;
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.isEmpty()) return;
        String table = seg.get(0);
        if (!"global".equals(table) && !"secure".equals(table) && !"system".equals(table)) return;
        if (!(p.getResult() instanceof android.database.Cursor) || !devHiddenFromCaller()) return;
        if (seg.size() == 2 && !DEV_SETTINGS.contains(seg.get(1))) return;
        p.setResult(new Hidden((android.database.Cursor) p.getResult()));
    }
    private static final class Hidden extends android.database.CursorWrapper {
        Hidden(android.database.Cursor c) { super(c); }
        private boolean hiddenRow(int column) {
            try {
                if (!"value".equalsIgnoreCase(getColumnName(column))) return false;
                int name = getColumnIndex("name"); if (name < 0) return false;
                return DEV_SETTINGS.contains(super.getString(name));
            } catch (Throwable t) { return false; }
        }
        @Override public String getString(int column) { return hiddenRow(column) ? "0" : super.getString(column); }
        @Override public int getInt(int column) { return hiddenRow(column) ? 0 : super.getInt(column); }
        @Override public long getLong(int column) { return hiddenRow(column) ? 0L : super.getLong(column); }
        @Override public short getShort(int column) { return hiddenRow(column) ? 0 : super.getShort(column); }
        @Override public float getFloat(int column) { return hiddenRow(column) ? 0f : super.getFloat(column); }
        @Override public double getDouble(int column) { return hiddenRow(column) ? 0d : super.getDouble(column); }
        @Override public void copyStringToBuffer(int column, android.database.CharArrayBuffer buffer) {
            if (!hiddenRow(column)) { super.copyStringToBuffer(column, buffer); return; }
            if (buffer.data == null || buffer.data.length < 1) buffer.data = new char[1];
            buffer.data[0] = '0'; buffer.sizeCopied = 1;
        }
    }

    private static String[] strings(Object[] args) {
        String[] out = new String[3]; int k = 0;
        for (Object a : args) { if (a instanceof String) { if (k < 3) out[k++] = (String) a; } else if (a == null && k > 0 && k < 3) out[k++] = null; }
        return k >= 2 ? out : null;
    }
}
