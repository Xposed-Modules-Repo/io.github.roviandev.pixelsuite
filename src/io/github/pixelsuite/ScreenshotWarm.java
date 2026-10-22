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

final class ScreenshotWarm {
    private ScreenshotWarm() {}
    static final String KEY = "pixelsuite_fast_screenshot";
    private static final String KEEP = "io.github.pixelsuite.KEEP_SCREENSHOT_READY";
    private static final String SYSTEMUI = "com.android.systemui", PROCESS = ":screenshot";

    private static Context system; private static Handler worker;
    private static ServiceConnection connection; private static volatile ComponentName target;
    private static volatile boolean armed;

    static void installSystem(ClassLoader cl) {
        try {
            Xp.hookAllMethods(Xp.findClass("com.android.server.am.ActivityManagerService", cl), "systemReady", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { start((Context) Xp.getObjectField(p.thisObject, "mContext")); } catch (Throwable error) { Module.log("Fast screenshot: start failed", error); }
                }
            });
        } catch (Throwable error) { Module.log("Fast screenshot unavailable", error); }
        try {
            int n = Xp.hookAllMethods(Class.forName("android.app.ContextImpl"), "bindServiceCommon", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        for (int i = 0; i < p.args.length; i++) {
                            if (!(p.args[i] instanceof Intent)) continue;
                            Intent in = (Intent) p.args[i];
                            ComponentName c = in.getComponent();
                            if (c == null || target == null || !c.equals(target) || KEEP.equals(in.getAction())) return;
                            if (armed && connection == null && worker != null) worker.postDelayed(new Runnable() { public void run() { synchronized (ScreenshotWarm.class) { if (armed) bind(); } } }, 1500);
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
    }
    private static int shots;

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
    }

    private static boolean on() {
        try { return !"0".equals(Settings.Secure.getString(system.getContentResolver(), KEY)); } catch (Throwable t) { return true; }
    }

    private static synchronized void update() {
        armed = on();
        if (!armed) unbind();
    }

    private static ComponentName pick() {
        try {
            PackageInfo info = system.getPackageManager().getPackageInfo(SYSTEMUI, PackageManager.GET_SERVICES | 0x200 );
            ServiceInfo best = null, shot = null;
            if (info.services != null) for (ServiceInfo s : info.services) {
                if (s.processName == null || !s.processName.endsWith(PROCESS)) continue;
                if (s.name.endsWith("TakeScreenshotService")) { shot = s; continue; }
                if (best == null || s.name.contains("CrossProfile")) best = s;
            }
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
            public void onBindingDied(ComponentName name) {
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
            if (ok) { connection = conn; Module.log("Fast screenshot: screenshot process kept ready after a screenshot (" + c.getShortClassName() + ")"); }
            else Module.log("Fast screenshot: bind refused (stock start kept)");
        } catch (Throwable error) { Module.log("Fast screenshot: bind failed (stock start kept)", error); }
    }

    private static void unbind() {
        ServiceConnection conn = connection; connection = null;
        if (conn == null) return;
        try { system.unbindService(conn); } catch (Throwable ignored) { }
    }
}
