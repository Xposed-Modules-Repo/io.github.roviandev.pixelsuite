package io.github.pixelsuite;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PrivacyLock {
    private PrivacyLock() {}

    static final String KEY = "pixelsuite_privacy_apps";
    static final String OUR = "io.github.roviandev.pixelsuite";
    static final String ACTIVITY = "io.github.pixelsuite.PrivacyLockActivity";
    static final String MENU_ACTIVITY = "io.github.pixelsuite.PrivacyLockMenuActivity";
    static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    static boolean validPackage(String p) { return p != null && p.length() < 200 && p.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"); }
    static final String EXTRA_MODE = "pixelsuite_mode", EXTRA_PACKAGE = "pixelsuite_package", EXTRA_LABEL = "pixelsuite_label";
    static final String MODE_OPEN = "open", MODE_LOCK = "lock", MODE_UNLOCK = "unlock";
    static final long AWAY_MS = 60_000;
    static final String GRACE_KEY = "pixelsuite_privacy_grace";
    static boolean graceOn(String value) { return "1".equals(value) || "true".equals(value); }
    static final String ENABLED_KEY = "pixelsuite_privacy_enabled", PROTECT_KEY = "pixelsuite_privacy_protect";
    static boolean onValue(String v) { return !"0".equals(v) && !"false".equals(v); }
    static final String MODE_CONFIRM = "confirm", ACTION_CONFIRMED = "io.github.pixelsuite.APP_LOCK_CONFIRMED";
    static final String EXTRA_REASON = "pixelsuite_reason", EXTRA_TOKEN = "pixelsuite_token", EXTRA_OK = "pixelsuite_ok";
    static final String REASON_UNINSTALL = "uninstall", REASON_DISABLE = "disable", REASON_PAUSE = "pause";
    private static final long ARRIVE_MS = 4000;

    static Set<String> parse(String value) {
        Set<String> out = new HashSet<String>();
        if (value == null) return out;
        for (String s : value.split(",")) { s = s.trim(); if (!s.isEmpty() && !"null".equals(s) && !"none".equals(s)) out.add(s); }
        return out;
    }
    static String join(Set<String> set) {
        StringBuilder b = new StringBuilder();
        for (String s : set) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }

    static final String MODE_COVER = "cover";
    static final String ACTION_OPENED = "io.github.pixelsuite.APP_LOCK_OPENED";
    private static final int FLAG_SHOW_WHEN_LOCKED = 0x800000;

    private static volatile Set<String> locked = Collections.emptySet();
    private static final Map<String, Boolean> open = new ConcurrentHashMap<String, Boolean>();
    private static final Map<String, Long> leftAt = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Long> coveredAt = new ConcurrentHashMap<String, Long>();
    private static final Set<Object> carried = Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());
    private static final ThreadLocal<Object> starting = new ThreadLocal<Object>();
    private static final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile String front;
    private static volatile boolean grace;
    private static volatile boolean protect = true;
    private static volatile int installerUid = -1;
    private static final Map<String, Object[]> waiting = new ConcurrentHashMap<String, Object[]>();
    private static final Map<String, Long> askedAt = new ConcurrentHashMap<String, Long>();
    private static final ThreadLocal<Boolean> replaying = new ThreadLocal<Boolean>();
    private static volatile String guest;
    private static final Map<String, Long> arriving = new ConcurrentHashMap<String, Long>();
    private static volatile int ourUid = -1;
    private static volatile boolean ready;
    private static volatile Context system;
    private static volatile Handler worker;

    static void installSystem(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> ams = Xp.findClass("com.android.server.am.ActivityManagerService", cl);
            n += Xp.hookAllMethods(ams, "systemReady", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { start((Context) Xp.getObjectField(p.thisObject, "mContext")); } catch (Throwable error) { Module.log("App lock: start failed", error); }
                }
            }).size();
            int b = Xp.hookAllMethods(ams, "broadcastIntentWithFeature", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        Intent i = null; for (Object o : p.args) if (o instanceof Intent) { i = (Intent) o; break; }
                        if (i == null) return;
                        if (ACTION_CONFIRMED.equals(i.getAction())) {
                            if (ourUid >= 0 && android.os.Binder.getCallingUid() == ourUid) confirmed(i.getStringExtra(EXTRA_TOKEN), i.getBooleanExtra(EXTRA_OK, true));
                            p.setResult(0); return;
                        }
                        if (PrivacyGuard.ACTION_RELOCK.equals(i.getAction())) {
                            if (launcherUid >= 0 && android.os.Binder.getCallingUid() == launcherUid) relockFolder(i.getStringExtra(PrivacyFolders.EXTRA_FOLDER));
                            p.setResult(0); return;
                        }
                        if (!ACTION_OPENED.equals(i.getAction())) return;
                        int caller = android.os.Binder.getCallingUid();
                        String pkg = i.getStringExtra(EXTRA_PACKAGE);
                        if (ourUid >= 0 && caller == ourUid && pkg != null) unlocked(pkg);
                        p.setResult(0);
                    } catch (Throwable ignored) { }
                }
            }).size();
            if (b == 0) Module.log("App lock: unlock reports unavailable (a covered app asks again when it returns)");
            n += b;
        } catch (Throwable error) { Module.log("App lock: system start hook unavailable", error); }
        try {
            Class<?> starter = Xp.findClass("com.android.server.wm.ActivityStarter", cl);
            n += Xp.hookAllMethods(starter, "executeRequest", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try { starting.set(p.args.length > 0 && p.args[0] != null ? Xp.getObjectField(p.args[0], "originatingPendingIntent") : null); } catch (Throwable t) { starting.set(null); }
                }
                @Override protected void afterHookedMethod(Xp.Param p) { starting.remove(); }
            }).size();
        } catch (Throwable error) { Module.log("App lock: start origin unavailable", error); }
        try {
            Class<?> interceptor = Xp.findClass("com.android.server.wm.ActivityStartInterceptor", cl);
            n += Xp.hookAllMethods(interceptor, "intercept", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { if (!Boolean.TRUE.equals(p.getResult()) && intercept(p.thisObject)) p.setResult(Boolean.TRUE); }
                    catch (Throwable error) { Module.log("App lock: start check failed (the app opens)", error); }
                }
            }).size();
        } catch (Throwable error) { Module.log("App lock: start hook unavailable", error); }
        try {
            Class<?> atms = Xp.findClass("com.android.server.wm.ActivityTaskManagerService", cl);
            int r = Xp.hookAllMethods(atms, "setLastResumedActivityUncheckLocked", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length == 0 || p.args[0] == null) return;
                        Object pkg = Xp.getObjectField(p.args[0], "packageName");
                        int flags = 0; try { flags = ((ActivityInfo) Xp.getObjectField(p.args[0], "info")).flags; } catch (Throwable ignored) { }
                        String owner = null; try { owner = taskPackage(Xp.callMethod(p.args[0], "getTask")); } catch (Throwable ignored) { }
                        if (pkg instanceof String) resumed((String) pkg, owner == null ? (String) pkg : owner, flags);
                    } catch (Throwable ignored) { }
                }
            }).size();
            if (r == 0) Module.log("App lock: screen tracking unavailable (relocks only when the screen turns off; recents not covered)");
            n += r;
            n += Xp.hookAllMethods(atms, "removeTask", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length == 0 || !(p.args[0] instanceof Integer) || open.isEmpty()) return;
                        Object root = Xp.getObjectField(p.thisObject, "mRootWindowContainer");
                        Object task = Xp.callMethod(root, "anyTaskForId", (Integer) p.args[0]);
                        if (task == null) return;
                        String pkg = taskPackage(task);
                        if (pkg != null) { open.remove(pkg); leftAt.remove(pkg); arriving.remove(pkg); }
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable error) { Module.log("App lock: screen tracking unavailable (relocks only when the screen turns off)", error); }
        n += installProtection(cl);
        Module.log("App lock: " + n + " system hooks");
    }

    private static int installProtection(ClassLoader cl) {
        int n = 0;
        String[] impls = { "com.android.server.pm.IPackageManagerBase", "com.android.server.pm.PackageManagerService$IPackageManagerImpl" };
        for (String name : impls) {
            Class<?> k; try { k = Xp.findClass(name, cl); } catch (Throwable t) { continue; }
            if (k == null) continue;
            n += Xp.hookAllMethods(k, "deletePackageVersioned", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (!protect || Boolean.TRUE.equals(replaying.get())) return;
                        String pkg = null; Object observer = null;
                        for (Object a : p.args) {
                            if (a != null && a.getClass().getName().equals("android.content.pm.VersionedPackage")) pkg = (String) Xp.callMethod(a, "getPackageName");
                            else if (a != null && !(a instanceof Number) && !(a instanceof String)) observer = a;
                        }
                        if (pkg == null || !locked.contains(pkg)) return;
                        int uid = android.os.Binder.getCallingUid();
                        if (installerUid >= 0 && uid == installerUid) return;
                        final long caller = android.os.Binder.clearCallingIdentity(); android.os.Binder.restoreCallingIdentity(caller);
                        p.setResult(null);
                        final Object to = observer; final String name = pkg;
                        ask(pkg, REASON_UNINSTALL, replay(p, caller), new Runnable() { public void run() { deleted(to, name, -5 ); } });
                        Module.log("App lock: an app asked to uninstall " + pkg + ", asking first");
                    } catch (Throwable error) { Module.log("App lock: uninstall check failed (it goes on)", error); }
                }
            }).size();
            n += Xp.hookAllMethods(k, "setApplicationEnabledSetting", new Xp.Callback() {
                @Override protected void beforeHookedMethod(final Xp.Param p) {
                    try {
                        if (!protect || Boolean.TRUE.equals(replaying.get()) || p.args.length < 2) return;
                        if (!(p.args[0] instanceof String) || !(p.args[1] instanceof Integer)) return;
                        String pkg = (String) p.args[0]; int state = (Integer) p.args[1];
                        if (state != 2 && state != 3 && state != 4) return;
                        if (!locked.contains(pkg)) return;
                        p.setResult(null);
                        ask(pkg, REASON_DISABLE, replay(p));
                    } catch (Throwable error) { Module.log("App lock: disable check failed (it goes on)", error); }
                }
            }).size();
            n += Xp.hookAllMethods(k, "setPackagesSuspendedAsUser", new Xp.Callback() {
                @Override protected void beforeHookedMethod(final Xp.Param p) {
                    try {
                        if (!protect || Boolean.TRUE.equals(replaying.get())) return;
                        String[] pkgs = null; Boolean suspend = null;
                        for (Object a : p.args) { if (pkgs == null && a instanceof String[]) pkgs = (String[]) a; else if (pkgs != null && suspend == null && a instanceof Boolean) suspend = (Boolean) a; }
                        if (pkgs == null || !Boolean.TRUE.equals(suspend)) return;
                        String first = null; for (String s : pkgs) if (s != null && locked.contains(s)) { first = s; break; }
                        if (first == null) return;
                        p.setResult(pkgs.clone());
                        ask(first, REASON_PAUSE, replay(p));
                    } catch (Throwable error) { Module.log("App lock: pause check failed (it goes on)", error); }
                }
            }).size();
        }
        try {
            Class<?> installer = Xp.findClass("com.android.server.pm.PackageInstallerService", cl);
            n += Xp.hookAllMethods(installer, "uninstall", new Xp.Callback() {
                @Override protected void beforeHookedMethod(final Xp.Param p) {
                    try {
                        if (!protect || Boolean.TRUE.equals(replaying.get())) return;
                        String pkg = null; IntentSender receiver = null;
                        for (Object a : p.args) {
                            if (a != null && a.getClass().getName().equals("android.content.pm.VersionedPackage")) pkg = (String) Xp.callMethod(a, "getPackageName");
                            else if (a instanceof IntentSender) receiver = (IntentSender) a;
                        }
                        if (pkg == null || !locked.contains(pkg)) return;
                        if (installerUid >= 0 && android.os.Binder.getCallingUid() == installerUid) return;
                        final long caller = android.os.Binder.clearCallingIdentity(); android.os.Binder.restoreCallingIdentity(caller);
                        p.setResult(null);
                        final String name = pkg; final IntentSender to = receiver;
                        ask(pkg, REASON_UNINSTALL, replay(p, caller), new Runnable() { public void run() { aborted(to, name); } });
                    } catch (Throwable error) { Module.log("App lock: uninstall check failed (it goes on)", error); }
                }
            }).size();
        } catch (Throwable error) { Module.log("App lock: app uninstall check unavailable", error); }
        if (n == 0) Module.log("App lock: uninstall / disable / pause protection unavailable");
        return n;
    }

    private static Runnable replay(Xp.Param p) { return replay(p, Long.MIN_VALUE); }
    private static Runnable replay(Xp.Param p, final long caller) {
        final java.lang.reflect.Method m = (java.lang.reflect.Method) p.method; final Object self = p.thisObject; final Object[] args = p.args.clone();
        return new Runnable() { public void run() {
            replaying.set(Boolean.TRUE);
            long id = android.os.Binder.clearCallingIdentity();
            if (caller != Long.MIN_VALUE) android.os.Binder.restoreCallingIdentity(caller);
            try { m.setAccessible(true); m.invoke(self, args); }
            catch (Throwable error) { Module.log("App lock: confirmed action failed", error); }
            finally { android.os.Binder.restoreCallingIdentity(id); replaying.remove(); }
        } };
    }

    private static void ask(final String pkg, final String reason, Runnable action) { ask(pkg, reason, action, null); }

    private static void deleted(Object observer, String pkg, int code) {
        if (observer == null) return;
        try {
            for (java.lang.reflect.Method m : observer.getClass().getMethods())
                if (m.getName().equals("onPackageDeleted") && m.getParameterTypes().length == 3) { m.invoke(observer, pkg, code, "Cancelled: the app is locked by Pixel Suite"); return; }
        } catch (Throwable error) { Module.log("App lock: uninstall cancel not delivered", error); }
    }

    private static void aborted(IntentSender to, String pkg) {
        if (to == null) return;
        try {
            Intent fill = new Intent();
            fill.putExtra("android.content.pm.extra.PACKAGE_NAME", pkg);
            fill.putExtra("android.content.pm.extra.STATUS", 3);
            fill.putExtra("android.content.pm.extra.STATUS_MESSAGE", "Cancelled: the app is locked by Pixel Suite");
            to.sendIntent(system, 0, fill, null, null);
        } catch (Throwable error) { Module.log("App lock: uninstall cancel not delivered", error); }
    }

    private static void ask(final String pkg, final String reason, Runnable action, Runnable cancel) {
        final Handler h = worker; final Context c = system;
        if (h == null || c == null || ourUid < 0) { action.run(); return; }
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, Object[]> e : waiting.entrySet()) if (now - (Long) e.getValue()[1] > 120_000) { waiting.remove(e.getKey()); Object c2 = e.getValue()[2]; if (c2 instanceof Runnable) ((Runnable) c2).run(); }
        final String token = java.util.UUID.randomUUID().toString();
        waiting.put(token, new Object[] { action, now, cancel });
        Long last = askedAt.get(pkg + reason); askedAt.put(pkg + reason, now);
        if (last != null && now - last < 1500 && cancel == null) return;
        h.post(new Runnable() { public void run() {
            try {
                Intent i = new Intent().setComponent(new ComponentName(OUR, ACTIVITY));
                i.setData(android.net.Uri.parse("pixelsuite-lock://confirm/" + sequence.incrementAndGet()));
                i.putExtra(EXTRA_MODE, MODE_CONFIRM); i.putExtra(EXTRA_PACKAGE, pkg); i.putExtra(EXTRA_REASON, reason); i.putExtra(EXTRA_TOKEN, token);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                c.startActivity(i);
            } catch (Throwable error) { Module.log("App lock: confirm sheet unavailable", error); }
        } });
    }
    private static void confirmed(String token, boolean ok) {
        if (token == null) return;
        Object[] job = waiting.remove(token);
        if (job == null) return;
        if (!ok || SystemClock.elapsedRealtime() - (Long) job[1] > 120_000) {
            if (job[2] instanceof Runnable) { Handler h = worker; if (h != null) h.post((Runnable) job[2]); else ((Runnable) job[2]).run(); }
            return;
        }
        Runnable first = (Runnable) job[0];
        Handler h = worker; if (h != null) h.post(first); else first.run();
    }

    private static String uninstallTarget(Intent i) {
        if (i == null || i.getData() == null) return null;
        String a = i.getAction();
        if (!Intent.ACTION_DELETE.equals(a) && !"android.intent.action.UNINSTALL_PACKAGE".equals(a)) return null;
        if (!"package".equals(i.getData().getScheme())) return null;
        return i.getData().getSchemeSpecificPart();
    }

    private static String labelOf(String pkg) {
        try { Context c = system; android.content.pm.PackageManager pm = c.getPackageManager(); return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
        catch (Throwable t) { return pkg; }
    }
    private static void say(final String text) {
        final Context c = system; Handler h = worker; if (c == null || h == null) return;
        h.post(new Runnable() { public void run() { try { android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { } } });
    }

    private static void start(final Context c) {
        if (ready || c == null) return;
        ready = true;
        system = c;
        HandlerThread thread = new HandlerThread("PixelSuiteAppLock");
        thread.start();
        final Handler h = new Handler(thread.getLooper());
        worker = h;
        final Runnable load = new Runnable() { public void run() {
            try {
                boolean on = onValue(Settings.Secure.getString(c.getContentResolver(), ENABLED_KEY));
                locked = on ? Collections.unmodifiableSet(parse(Settings.Secure.getString(c.getContentResolver(), KEY))) : Collections.<String>emptySet();
            } catch (Throwable error) { Module.log("App lock: list unreadable", error); }
            try { protect = onValue(Settings.Secure.getString(c.getContentResolver(), PROTECT_KEY)); } catch (Throwable ignored) { }
            for (String installer : new String[] { "com.google.android.packageinstaller", "com.android.packageinstaller" }) {
                try { installerUid = c.getPackageManager().getApplicationInfo(installer, 0).uid; break; } catch (Throwable ignored) { }
            }
            try { grace = graceOn(Settings.Secure.getString(c.getContentResolver(), GRACE_KEY)); } catch (Throwable ignored) { }
            try { ourUid = c.getPackageManager().getApplicationInfo(OUR, 0).uid; } catch (Throwable ignored) { }
            try { launcherUid = c.getPackageManager().getApplicationInfo(LAUNCHER, 0).uid; } catch (Throwable ignored) { }
        } };
        h.post(load);
        try {
            c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(KEY), false, new ContentObserver(h) {
                @Override public void onChange(boolean self) { load.run(); for (String pkg : open.keySet()) if (!locked.contains(pkg)) open.remove(pkg); }
            });
            ContentObserver reload = new ContentObserver(h) { @Override public void onChange(boolean self) { load.run(); } };
            c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(GRACE_KEY), false, reload);
            c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(ENABLED_KEY), false, reload);
            c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(PROTECT_KEY), false, reload);
        } catch (Throwable error) { Module.log("App lock: list changes not followed", error); }
        try {
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent i) { open.clear(); arriving.clear(); front = null; guest = null; }
            }, new IntentFilter(Intent.ACTION_SCREEN_OFF), null, h);
        } catch (Throwable error) { Module.log("App lock: screen-off relock unavailable", error); }
        try {
            IntentFilter f = new IntentFilter(); f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REPLACED); f.addDataScheme("package");
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent i) { if (i.getData() != null && OUR.equals(i.getData().getSchemeSpecificPart())) load.run(); }
            }, f, null, h);
        } catch (Throwable ignored) { }
        try {
            IntentFilter f = new IntentFilter(); f.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED); f.addAction(Intent.ACTION_PACKAGE_REMOVED); f.addDataScheme("package");
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context x, Intent i) {
                    if (i.getData() == null || i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                    String pkg = i.getData().getSchemeSpecificPart();
                    if (pkg != null) forget(c, pkg);
                }
            }, f, null, h);
        } catch (Throwable error) { Module.log("App lock: uninstall cleanup unavailable", error); }
    }

    private static volatile int launcherUid = -1;

    private static void relockFolder(final String key) {
        final Context c = system; Handler h = worker;
        if (c == null || h == null || key == null || key.length() < 3 || !(key.startsWith("h:") || key.startsWith("d:"))) return;
        h.post(new Runnable() { public void run() {
            long id = android.os.Binder.clearCallingIdentity();
            try {
                Set<String> list = parse(Settings.Secure.getString(c.getContentResolver(), PrivacyFolders.KEY));
                if (list.add(key)) Settings.Secure.putString(c.getContentResolver(), PrivacyFolders.KEY, join(list));
                Module.log("App lock: folder lock given back after Undo");
            } catch (Throwable error) { Module.log("App lock: folder lock not given back after Undo", error); }
            finally { android.os.Binder.restoreCallingIdentity(id); }
        } });
    }

    private static void forget(Context c, String pkg) {
        long id = android.os.Binder.clearCallingIdentity();
        try {
            Set<String> list = parse(Settings.Secure.getString(c.getContentResolver(), KEY));
            if (!list.remove(pkg)) return;
            String value = join(list);
            if (value.isEmpty()) Settings.Secure.putString(c.getContentResolver(), KEY, null); else Settings.Secure.putString(c.getContentResolver(), KEY, value);
            open.remove(pkg); leftAt.remove(pkg); arriving.remove(pkg);
            Module.log("App lock: " + pkg + " was uninstalled, taken off the lock list");
        } catch (Throwable error) { Module.log("App lock: lock list not updated after an uninstall", error); }
        finally { android.os.Binder.restoreCallingIdentity(id); }
    }

    private static void resumed(final String pkg, final String owner, int flags) {
        String was = front, wasGuest = guest;
        if (owner.equals(was) && (pkg.equals(owner) || pkg.equals(wasGuest))) return;
        if (!owner.equals(was)) {
            front = owner; guest = null;
            if (was != null && !was.equals(pkg)) left(was);
            if (wasGuest != null && !wasGuest.equals(pkg)) left(wasGuest);
        }
        if (!pkg.equals(owner)) {
            if (open.containsKey(pkg) && (pkg.equals(was) || pkg.equals(wasGuest) || unlockedNow(pkg))) { guest = pkg; arriving.remove(pkg); return; }
        } else arriving.remove(pkg);
        if (OUR.equals(pkg) || !locked.contains(pkg) || (flags & FLAG_SHOW_WHEN_LOCKED) != 0 || unlockedNow(pkg)) return;
        final Handler h = worker; final Context c = system;
        if (h == null || c == null || ourUid < 0) return;
        Long last = coveredAt.get(pkg); long now = SystemClock.elapsedRealtime();
        if (last != null && now - last < 1500) return;
        coveredAt.put(pkg, now);
        h.post(new Runnable() { public void run() {
            try {
                String f = front;
                if (!(pkg.equals(f) || owner.equals(f)) || unlockedNow(pkg) || !locked.contains(pkg)) return;
                Intent cover = new Intent().setComponent(new ComponentName(OUR, ACTIVITY));
                cover.setData(android.net.Uri.parse("pixelsuite-lock://cover/" + sequence.incrementAndGet()));
                cover.putExtra(EXTRA_MODE, MODE_COVER);
                cover.putExtra(EXTRA_PACKAGE, pkg);
                cover.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                c.startActivity(cover);
            } catch (Throwable error) { Module.log("App lock: cover unavailable", error); }
        } });
    }

    private static void unlocked(String pkg) {
        open.put(pkg, Boolean.TRUE); leftAt.remove(pkg);
        arriving.put(pkg, SystemClock.elapsedRealtime());
        front = pkg;
    }

    private static void left(String pkg) {
        if (!open.containsKey(pkg)) return;
        Long at = arriving.get(pkg);
        if (at != null && SystemClock.elapsedRealtime() - at < ARRIVE_MS) return;
        arriving.remove(pkg);
        if (grace) leftAt.put(pkg, SystemClock.elapsedRealtime());
        else { open.remove(pkg); leftAt.remove(pkg); }
    }

    private static boolean unlockedNow(String pkg) {
        if (!Boolean.TRUE.equals(open.get(pkg))) return false;
        if (pkg.equals(front) || pkg.equals(guest)) return true;
        Long at = arriving.get(pkg);
        if (at != null && SystemClock.elapsedRealtime() - at < ARRIVE_MS) return true;
        Long left = leftAt.get(pkg);
        if (grace && left != null && SystemClock.elapsedRealtime() - left < AWAY_MS) return true;
        if (!grace && left == null && at == null) return true;
        open.remove(pkg); leftAt.remove(pkg); arriving.remove(pkg);
        return false;
    }

    private static String taskPackage(Object task) {
        if (task == null) return null;
        try { Object base = Xp.callMethod(task, "getBaseIntent"); if (base instanceof Intent && ((Intent) base).getComponent() != null) return ((Intent) base).getComponent().getPackageName(); } catch (Throwable ignored) { }
        try { Object real = Xp.getObjectField(task, "realActivity"); if (real instanceof ComponentName) return ((ComponentName) real).getPackageName(); } catch (Throwable ignored) { }
        return null;
    }

    private static boolean keyguardLocked(Object it) {
        try {
            Object service = Xp.getObjectField(it, "mService");
            Object keyguard = Xp.getObjectField(service, "mKeyguardController");
            return Boolean.TRUE.equals(Xp.callMethod(keyguard, "isKeyguardLocked", 0));
        } catch (Throwable t) { return false; }
    }

    private static boolean intercept(Object it) throws Exception {
        Set<String> list = locked;
        if (list.isEmpty() || ourUid < 0) return false;
        ActivityInfo target = (ActivityInfo) Xp.getObjectField(it, "mAInfo");
        String victim = protect ? uninstallTarget((Intent) Xp.getObjectField(it, "mIntent")) : null;
        if (victim != null && list.contains(victim) && ((Number) Xp.getObjectField(it, "mUserId")).intValue() == 0) {
            Object origin = starting.get();
            if (((Number) Xp.getObjectField(it, "mRealCallingUid")).intValue() == ourUid && origin != null) {
                boolean ours; synchronized (carried) { ours = carried.remove(origin); }
                if (ours) return false;
            }
            return gate(it, victim, REASON_UNINSTALL);
        }
        if (target == null || target.packageName == null || !list.contains(target.packageName)) return false;
        String pkg = target.packageName;
        int realUid = ((Number) Xp.getObjectField(it, "mRealCallingUid")).intValue();
        int realPid = ((Number) Xp.getObjectField(it, "mRealCallingPid")).intValue();
        int userId = ((Number) Xp.getObjectField(it, "mUserId")).intValue();
        int appUid = target.applicationInfo != null ? target.applicationInfo.uid : -1;
        if (userId != 0) return false;
        if (appUid >= 0 && realUid % 100000 == appUid % 100000) return false;
        Object origin = starting.get();
        if (realUid == ourUid && origin != null) {
            boolean ours; synchronized (carried) { ours = carried.remove(origin); }
            if (ours) { unlocked(pkg); return false; }
        }
        if (unlockedNow(pkg)) return false;
        if ((target.flags & FLAG_SHOW_WHEN_LOCKED) != 0 && keyguardLocked(it)) return false;
        return gate(it, pkg, null);
    }

    private static boolean gate(Object it, String pkg, String reason) throws Exception {
        int realUid = ((Number) Xp.getObjectField(it, "mRealCallingUid")).intValue();
        int realPid = ((Number) Xp.getObjectField(it, "mRealCallingPid")).intValue();
        int userId = ((Number) Xp.getObjectField(it, "mUserId")).intValue();
        Intent original = (Intent) Xp.getObjectField(it, "mIntent");
        int callingUid = ((Number) Xp.getObjectField(it, "mCallingUid")).intValue();
        if (original != null) { Intent forward = new Intent(original); forward.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT); Xp.setObjectField(it, "mIntent", forward); }
        IntentSender carry = (IntentSender) Xp.callMethod(it, "createIntentSenderForOriginalIntent", callingUid,
                android.app.PendingIntent.FLAG_ONE_SHOT | 0x04000000 );
        try { Object record = Xp.callMethod(carry, "getTarget"); if (record != null) synchronized (carried) { carried.add(record); } } catch (Throwable ignored) { }
        Intent lock = new Intent().setComponent(new ComponentName(OUR, ACTIVITY));
        lock.setData(android.net.Uri.parse("pixelsuite-lock://open/" + sequence.incrementAndGet()));
        lock.putExtra(EXTRA_MODE, MODE_OPEN);
        lock.putExtra(EXTRA_PACKAGE, pkg);
        lock.putExtra(Intent.EXTRA_INTENT, carry);
        if (reason != null) lock.putExtra(EXTRA_REASON, reason);
        int keep = original == null ? 0 : original.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        lock.addFlags(keep | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        Object supervisor = Xp.getObjectField(it, "mSupervisor");
        ResolveInfo ri = (ResolveInfo) Xp.callMethod(supervisor, "resolveIntent", lock, null, userId, 0, realUid, realPid);
        if (ri == null) return false;
        int startFlags = 0; try { startFlags = ((Number) Xp.getObjectField(it, "mStartFlags")).intValue(); } catch (Throwable ignored) { }
        ActivityInfo ai = (ActivityInfo) Xp.callMethod(supervisor, "resolveActivity", lock, ri, startFlags, null);
        if (ai == null) return false;
        Xp.setObjectField(it, "mIntent", lock);
        Xp.setObjectField(it, "mResolvedType", null);
        Xp.setObjectField(it, "mCallingPid", realPid);
        Xp.setObjectField(it, "mCallingUid", realUid);
        Xp.setObjectField(it, "mRInfo", ri);
        Xp.setObjectField(it, "mAInfo", ai);
        try { if (Xp.getObjectField(it, "mInTask") != null) Xp.setObjectField(it, "mInTask", null); } catch (Throwable ignored) { }
        return true;
    }

    private static long readAt; private static Set<String> readList = Collections.emptySet();
    static Set<String> lockedNow(Context c) {
        long now = SystemClock.uptimeMillis();
        if (now - readAt > 500) {
            try { readList = onValue(Settings.Secure.getString(c.getContentResolver(), ENABLED_KEY)) ? parse(Settings.Secure.getString(c.getContentResolver(), KEY)) : Collections.<String>emptySet(); } catch (Throwable ignored) { }
            readAt = now;
        }
        return readList;
    }
    static void changed() { readAt = 0; graceAt = 0; }
    private static long graceAt; private static boolean graceRead;
    static boolean graceNow(Context c) {
        long now = SystemClock.uptimeMillis();
        if (now - graceAt > 500) {
            try { graceRead = graceOn(Settings.Secure.getString(c.getContentResolver(), GRACE_KEY)); } catch (Throwable ignored) { }
            graceAt = now;
        }
        return graceRead;
    }

    static boolean lockedCard(View taskView) {
        try {
            Set<String> list = lockedNow(taskView.getContext()); if (list.isEmpty()) return false;
            for (Object c : RecentsLock.containers(taskView)) { String id = RecentsLock.identity(c); if (id != null && list.contains(id.substring(0, id.indexOf('|')))) return true; }
        } catch (Throwable ignored) { }
        return false;
    }

    static void addRow(final ViewGroup rows, final Object popup, final Object info) {
        try {
            Object component = LauncherFeatures.call(info, "getTargetComponent");
            if (!(component instanceof ComponentName)) return;
            final String pkg = ((ComponentName) component).getPackageName();
            if (pkg == null || OUR.equals(pkg) || pkg.equals(rows.getContext().getPackageName()) || "com.android.systemui".equals(pkg)) return;
            final boolean isLocked = lockedNow(rows.getContext()).contains(pkg);
            final CharSequence label = (CharSequence) LauncherFeatures.get(info, "title");
            String title = isLocked ? "Unlock app" : "Lock app";
            LauncherFeatures.addMenuRow(rows, title, "pixelsuite_privacy", new View.OnClickListener() {
                public void onClick(View v) {
                    LauncherFeatures.call(popup, "close", true);
                    Activity a = LauncherFeatures.activity.get(); if (a == null) return;
                    Intent i = new Intent().setComponent(new ComponentName(OUR, MENU_ACTIVITY));
                    i.putExtra(EXTRA_MODE, isLocked ? MODE_UNLOCK : MODE_LOCK);
                    i.putExtra(EXTRA_PACKAGE, pkg);
                    i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    android.os.Bundle options = null;
                    try {
                        android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                        android.app.ActivityOptions.class.getMethod("setShareIdentityEnabled", boolean.class).invoke(o, true);
                        options = o.toBundle();
                    } catch (Throwable ignored) { }
                    try { a.startActivity(i, options); changed(); } catch (Throwable error) { Module.log("App lock: unlock screen unavailable", error); }
                }
            }, false);
            View row = rows.findViewWithTag("pixelsuite_privacy");
            View rename = rows.findViewWithTag("pixelsuite_rename");
            if (row != null && rename != null) { rows.removeView(row); rows.addView(row, rows.indexOfChild(rename)); }
            if (row != null) {
                View icon = (View) LauncherFeatures.get(row, "mIconView");
                int color = ((TextView) LauncherFeatures.get(row, "mBubbleText")).getCurrentTextColor();
                icon.setBackground(new RecentsLock.Lock(color, isLocked ? 1f : 0f));
            }
        } catch (Throwable error) { LauncherFeatures.logOnce("App lock menu row unavailable", error); }
    }
}
