package io.github.pixelsuite;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;

/**
 * 2.0.6-preview69 (owner: even the power + volume screenshot had a delay; his log: the buttons at .216, Android then
 * started System UI's screenshot process from nothing (.251), it was ready ~0.6 s later, the screenshot UI at ~1.1 s).
 *
 * The screenshot process is kept running once it exists, so the next screenshot (buttons, three fingers, the recents
 * button) finds it already up. Costs that process's memory while it runs. Secure setting {@link #KEY}: "0" = off
 * (the process starts on demand again, as stock).
 *
 * preview89: our binding is added right after the system's own first screenshot binding (seen in bindServiceCommon)
 * and then holds the process. The first screenshot after a boot (or after the process died) is at stock speed; the
 * ones after it are instant. preview90: the real cause of the blank previews was the service running its own
 * setup / teardown for our binding; see installScreenshotProcess.
 */
final class ScreenshotWarm {
    private ScreenshotWarm() {}
    static final String KEY = "pixelsuite_fast_screenshot";
    private static final String KEEP = "io.github.pixelsuite.KEEP_SCREENSHOT_READY";
    private static final String SYSTEMUI = "com.android.systemui", PROCESS = ":screenshot";

    private static Context system; private static Handler worker;
    private static ServiceConnection connection; private static volatile ComponentName target;
    private static volatile boolean armed;   // the switch is on: join the process after the next real screenshot binding

    static void installSystem(ClassLoader cl) {
        try {
            Xp.hookAllMethods(Xp.findClass("com.android.server.am.ActivityManagerService", cl), "systemReady", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { start((Context) Xp.getObjectField(p.thisObject, "mContext")); } catch (Throwable error) { Module.log("Fast screenshot: start failed", error); }
                }
            });
        } catch (Throwable error) { Module.log("Fast screenshot unavailable", error); }
        // preview78 (owner: with the service kept up, the preview was glitchy from the second screenshot on): Android
        // binds the screenshot service once per distinct Intent; with the service still running, a screenshot's second
        // binding reused the first one (no onBind, only onRebind) while the service's onUnbind had already taken its
        // window down. Each screenshot binding now gets its own Intent identity, so it is a first binding every time
        // (onBind ... onUnbind), as when the service started fresh.
        try {
            int n = Xp.hookAllMethods(Class.forName("android.app.ContextImpl"), "bindServiceCommon", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        for (int i = 0; i < p.args.length; i++) {
                            if (!(p.args[i] instanceof Intent)) continue;
                            Intent in = (Intent) p.args[i];
                            ComponentName c = in.getComponent();
                            if (c == null || target == null || !c.equals(target) || KEEP.equals(in.getAction())) return;
                            // preview89: a real screenshot binding: the system starts the process; ours joins it right after
                            if (armed && connection == null && worker != null) worker.postDelayed(new Runnable() { public void run() { synchronized (ScreenshotWarm.class) { if (armed) bind(); } } }, 1500);
                            for (Object a : p.args) if (a instanceof ServiceConnection) synchronized (real) { real.put((ServiceConnection) a, Boolean.TRUE); }
                            if (connection == null) return;
                            Intent fresh = new Intent(in);
                            Intent.class.getMethod("setIdentifier", String.class).invoke(fresh, "pixelsuite-shot-" + (++shots));
                            p.args[i] = fresh;
                            return;
                        }
                    } catch (Throwable ignored) { }
                }
            }).size();
            if (n == 0) Module.log("Fast screenshot: binding identity hook unavailable");
        } catch (Throwable error) { Module.log("Fast screenshot: binding identity hook unavailable", error); }
        // preview92 (owner: the blank preview still came back on 311/312): stock Android makes a new screenshot service
        // for every screenshot (created, bound, unbound, destroyed). Ours kept one service alive for all of them, so
        // whatever it leaves behind after a screenshot carried into the next one. Now, when a screenshot's binding
        // ends, ours steps away too, so the service is destroyed as in stock, and binds again a moment later: a new
        // service is ready in the same, still running process (its own onBind is skipped, see installScreenshotProcess).
        try {
            int n = Xp.hookAllMethods(Class.forName("android.app.ContextImpl"), "unbindService", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 1 || !(p.args[0] instanceof ServiceConnection)) return;
                        boolean was; synchronized (real) { was = real.remove(p.args[0]) != null; }
                        if (was && worker != null) worker.postDelayed(new Runnable() { public void run() { recycle(); } }, 400);
                    } catch (Throwable ignored) { }
                }
            }).size();
            if (n == 0) Module.log("Fast screenshot: unbind hook unavailable");
        } catch (Throwable error) { Module.log("Fast screenshot: unbind hook unavailable", error); }
    }
    private static int shots;
    /** The system's own screenshot bindings that are still open. */
    private static final java.util.WeakHashMap<ServiceConnection, Boolean> real = new java.util.WeakHashMap<ServiceConnection, Boolean>();
    private static boolean loggedReady;

    /** A screenshot ended: let the service be destroyed as in stock, then make a new one ready. */
    private static synchronized void recycle() {
        synchronized (real) { if (!real.isEmpty()) return; }   // another screenshot is still running
        if (connection == null) return;
        unbind();
        worker.postDelayed(new Runnable() { public void run() { synchronized (ScreenshotWarm.class) { if (armed) bind(); } } }, 600);
    }

    /**
     * preview90 (owner: the blank preview came back on 310 after ~20 minutes; his log: turning the option off crashed the
     * screenshot process in TakeScreenshotService.onUnbind, "Receiver not registered"): Android ran the screenshot
     * service's own onBind / onUnbind for Pixel Suite's keep-ready binding too, as if it were a screenshot. Its setup and
     * teardown are shared by the whole service, so the real screenshots' preview ended up drawn on a half torn-down
     * setup. In the screenshot process, our binding (action KEEP) now gets a plain Binder without the service's onBind,
     * and its unbind skips the service's onUnbind: it only keeps the process alive. Real screenshots run exactly as stock.
     */
    static void installScreenshotProcess(ClassLoader cl) {
        try {
            Class<?> service = Class.forName("com.android.systemui.screenshot.TakeScreenshotService", false, cl);
            int n = Xp.hookAllMethods(service, "onBind", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 1 && p.args[0] instanceof Intent && KEEP.equals(((Intent) p.args[0]).getAction())) p.setResult(new android.os.Binder());
                }
            }).size();
            n += Xp.hookAllMethods(service, "onUnbind", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 1 && p.args[0] instanceof Intent && KEEP.equals(((Intent) p.args[0]).getAction())) p.setResult(Boolean.FALSE);
                }
            }).size();
            n += Xp.hookAllMethods(service, "onRebind", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 1 && p.args[0] instanceof Intent && KEEP.equals(((Intent) p.args[0]).getAction())) p.setResult(null);
                }
            }).size();
            if (n < 2) Module.log("Fast screenshot: service hooks incomplete (" + n + ")");
        } catch (Throwable error) { Module.log("Fast screenshot: service hooks unavailable", error); }
    }

    private static synchronized void start(final Context c) {
        if (system != null || c == null) return;
        system = c;
        HandlerThread t = new HandlerThread("PixelSuiteShotWarm"); t.start();
        worker = new Handler(t.getLooper());
        try {
            c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(KEY), false, new ContentObserver(worker) {
                @Override public void onChange(boolean self) { update(); }
            });
        } catch (Throwable ignored) { }
        worker.post(new Runnable() { public void run() { target = pick(); update(); } });
        // preview92: with the real causes fixed (our binding skips the service's own setup, and every screenshot gets a
        // fresh service), the process is made ready again after the boot settles, so the first screenshot is instant too.
        worker.postDelayed(new Runnable() { public void run() { synchronized (ScreenshotWarm.class) { if (armed) bind(); } } }, 30_000);
    }

    private static boolean on() {
        try { return !"0".equals(Settings.Secure.getString(system.getContentResolver(), KEY)); } catch (Throwable t) { return true; }
    }

    /** On: keep the process ready. Off: let it go. */
    private static synchronized void update() {
        armed = on();
        if (armed) { if (system != null && worker != null && android.os.SystemClock.elapsedRealtime() > 60_000) bind(); }
        else unbind();
    }

    /** A service that lives in the screenshot process and is not the screenshot service itself. */
    private static ComponentName pick() {
        try {
            PackageInfo info = system.getPackageManager().getPackageInfo(SYSTEMUI, PackageManager.GET_SERVICES | 0x200 /* MATCH_DISABLED_COMPONENTS */);
            ServiceInfo best = null, shot = null;
            if (info.services != null) for (ServiceInfo s : info.services) {
                if (s.processName == null || !s.processName.endsWith(PROCESS)) continue;
                if (s.name.endsWith("TakeScreenshotService")) { shot = s; continue; }
                if (best == null || s.name.contains("CrossProfile")) best = s;
            }
            // preview77 (owner's log: "no quiet service in the screenshot process"; the delay stayed): on his phone
            // the screenshot service is alone in that process, so it is bound itself, with our own Intent (KEEP), a
            // separate binding of its own: Android still starts and ends the screenshot's own binding as before
            // (its onBind / onUnbind for that Intent run unchanged); ours only keeps the process up.
            if (best == null) best = shot;
            return best == null ? null : new ComponentName(SYSTEMUI, best.name);
        } catch (Throwable error) { Module.log("Fast screenshot: services unreadable", error); return null; }
    }

    private static void bind() {
        if (connection != null) return;
        ComponentName c = target;
        if (c == null) { Module.log("Fast screenshot: no service in the screenshot process (stock start kept)"); return; }
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { }
            @Override public void onServiceDisconnected(ComponentName name) { }
            public void onBindingDied(ComponentName name) {   // (Android 8+)   // the process died: wait for the next real screenshot
                worker.post(new Runnable() { public void run() { synchronized (ScreenshotWarm.class) { unbind(); } } });
            }
        };
        try {
            Intent i = new Intent(KEEP).setComponent(c);
            boolean ok;
            try {
                Object user = android.os.UserHandle.class.getMethod("of", int.class).invoke(null, 0);
                ok = (Boolean) Context.class.getMethod("bindServiceAsUser", Intent.class, ServiceConnection.class, int.class, android.os.UserHandle.class)
                        .invoke(system, i, conn, Context.BIND_AUTO_CREATE, user);
            } catch (Throwable older) { ok = system.bindService(i, conn, Context.BIND_AUTO_CREATE); }
            if (ok) { connection = conn; if (!loggedReady) { loggedReady = true; Module.log("Fast screenshot: screenshot process kept ready (" + c.getShortClassName() + ")"); } }
            else Module.log("Fast screenshot: bind refused (stock start kept)");
        } catch (Throwable error) { Module.log("Fast screenshot: bind failed (stock start kept)", error); }
    }

    private static void unbind() {
        ServiceConnection conn = connection; connection = null;
        if (conn == null) return;
        try { system.unbindService(conn); } catch (Throwable ignored) { }
    }
}
