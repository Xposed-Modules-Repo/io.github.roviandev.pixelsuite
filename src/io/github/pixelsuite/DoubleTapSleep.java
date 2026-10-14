package io.github.pixelsuite;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.ViewConfiguration;

/**
 * Double-tap an empty spot on the home screen to turn the screen off and lock the phone.
 *
 * Runs entirely in system_server. When you tap empty home-screen space, the launcher sends the
 * wallpaper a "tap" command (WallpaperManager.COMMAND_TAP) through
 * com.android.server.wm.Session.sendWallpaperCommand. Icons, widgets, folders and scrolls never
 * send it, so it marks exactly "empty space". Two taps from the home app, close together in
 * time and place, put the phone to sleep the same way the power button does, with the same
 * short click as the other gestures.
 *
 * On/off: Settings.Secure Feature.DOUBLE_TAP_SLEEP, read at the moment of the double tap.
 */
final class DoubleTapSleep {

    private static final String SESSION = "com.android.server.wm.Session";
    private static final String COMMAND_TAP = "android.wallpaper.tap";
    /** Finger-up to finger-up: the double-tap timeout (300 ms) plus the second tap itself. */
    private static final long WINDOW_MS = 400L;
    private static final int SLEEP_REASON_POWER_BUTTON = 4;
    private static final long HOME_RECHECK_MS = 30000L;

    private static long sLastTapAt;
    private static int sLastX, sLastY;
    private static String sHome;
    private static long sHomeAt;
    private static boolean sLoggedFirstTap;
    /** system_server's context, found once (it never changes). */
    private static volatile Context sCtx;

    private DoubleTapSleep() {}

    static void installSystem(ClassLoader cl) {
        try {
            Class<?> session = Xp.findClass(SESSION, cl);
            int n = Xp.hookAllMethods(session, "sendWallpaperCommand", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    try {
                        onCommand(param.thisObject, param.args);
                    } catch (Throwable t) {
                        Module.log("double-tap-sleep: tap handling failed", t);
                    }
                }
            }).size();
            Module.log("double-tap-sleep: hooked " + n + " method(s)");
        } catch (Throwable t) {
            Module.log("double-tap-sleep: hook failed", t);
        }
    }

    private static void onCommand(Object session, Object[] args) {
        if (args.length < 4 || !COMMAND_TAP.equals(args[1])) return;
        final Context ctx = context(session);
        if (ctx == null) return;

        String pkg = (String) Xp.getObjectField(session, "mPackageName");
        if (!sLoggedFirstTap) {
            sLoggedFirstTap = true;
            Module.log("double-tap-sleep: home-screen taps arriving from " + pkg);
        }
        if (pkg == null || !pkg.equals(homePackage(ctx))) return;
        if (!Feature.on(ctx, Feature.DOUBLE_TAP_SLEEP)) return;

        int x = (Integer) args[2];
        int y = (Integer) args[3];
        long now = SystemClock.uptimeMillis();
        int slop = ViewConfiguration.get(ctx).getScaledDoubleTapSlop();
        long dx = x - sLastX, dy = y - sLastY;
        boolean close = dx * dx + dy * dy <= (long) slop * slop;

        if (sLastTapAt != 0 && now - sLastTapAt <= WINDOW_MS && close) {
            sLastTapAt = 0;
            Bg.handler().post(new Runnable() {
                @Override
                public void run() {
                    Haptics.click(ctx);
                    sleep(ctx);
                }
            });
        } else {
            sLastTapAt = now;
            sLastX = x;
            sLastY = y;
        }
    }

    private static void sleep(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        try {
            PowerManager.class.getMethod("goToSleep", long.class, int.class, int.class)
                    .invoke(pm, SystemClock.uptimeMillis(), SLEEP_REASON_POWER_BUTTON, 0);
            Module.log("double-tap-sleep: screen off");
        } catch (Throwable t) {
            Module.log("double-tap-sleep: goToSleep failed", t);
        }
    }

    /** The current default home app, refreshed every 30 s (it changes only if you switch). */
    private static String homePackage(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (sHome == null || now - sHomeAt > HOME_RECHECK_MS) {
            try {
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                ResolveInfo ri = ctx.getPackageManager()
                        .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri != null && ri.activityInfo != null) sHome = ri.activityInfo.packageName;
            } catch (Throwable ignored) { }
            sHomeAt = now;
        }
        return sHome;
    }

    private static Context context(Object session) {
        Context ctx = sCtx;
        if (ctx != null) return ctx;
        try {
            Object wms = Xp.getObjectField(session, "mService");
            ctx = (Context) Xp.getObjectField(wms, "mContext");
            sCtx = ctx;
            return ctx;
        } catch (Throwable t) {
            return null;
        }
    }
}
