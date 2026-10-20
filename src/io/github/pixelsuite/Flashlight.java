package io.github.pixelsuite;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.lang.reflect.Method;

final class Flashlight {

    static final String KEY_TARGET = "double_tap_power_button_gesture";
    static final String KEY_ENABLED = "double_tap_power_button_gesture_enabled";

    private Flashlight() {}

    static boolean isTorchSelected(Context ctx) {
        return Feature.on(ctx, Feature.FLASHLIGHT);
    }

    private static final String GLS = "com.android.server.GestureLauncherService";
    private static final int SOURCE_POWER_DOUBLE_TAP = 1;
    private static final int SLEEP_REASON_POWER_BUTTON = 4;
    private static final long SEQUENCE_GAP_MS = 500L;

    private static volatile boolean sTorchSelected;
    private static volatile boolean sInitDone;
    private static volatile long sLastPowerDown;
    private static volatile boolean sSequenceStartedInteractive = true;

    static void installSystem(ClassLoader cl) {
        final Class<?> gls;
        try {
            gls = Xp.findClass(GLS, cl);
        } catch (Throwable t) {
            Module.log("GestureLauncherService not found, system side disabled", t);
            return;
        }

        try {
            Xp.hookAllMethods(gls, "interceptPowerKeyDown", new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (param.args.length < 2 || !(param.args[0] instanceof KeyEvent)
                            || !(param.args[1] instanceof Boolean)) return;
                    KeyEvent ev = (KeyEvent) param.args[0];
                    if (ev.isLongPress() || ev.getRepeatCount() > 0) return;
                    long t = ev.getEventTime();
                    if (t - sLastPowerDown > SEQUENCE_GAP_MS) {
                        sSequenceStartedInteractive = (Boolean) param.args[1];
                    }
                    sLastPowerDown = t;
                    ensureInit(param.thisObject);
                }
            });

            Xp.hookAllMethods(gls, "handleCameraGesture", new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (param.args.length >= 2 && param.args[1] instanceof Integer
                            && (Integer) param.args[1] != SOURCE_POWER_DOUBLE_TAP) return;
                    if (handleIfTorch(param.thisObject)) param.setResult(Boolean.TRUE);
                }
            });

            hookIfPresent(gls, "handleWalletGesture");
            hookIfPresent(gls, "sendGestureTargetActivityPendingIntent");

            Module.log("flashlight: system hooks installed");
        } catch (Throwable t) {
            Module.log("system hook setup failed", t);
        }
    }

    private static void hookIfPresent(Class<?> gls, String name) {
        try {
            Xp.hookAllMethods(gls, name, new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (handleIfTorch(param.thisObject)) param.setResult(Boolean.TRUE);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean handleIfTorch(Object gls) {
        final Context ctx = context(gls);
        if (ctx == null) return false;
        ensureInit(gls);
        if (!sTorchSelected) return false;

        final boolean sleepAfter = !sSequenceStartedInteractive;
        Torch.toggle(ctx, new Runnable() {
            @Override
            public void run() {
                Haptics.click(ctx);
                if (sleepAfter) {
                    Torch.handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            goToSleep(ctx);
                        }
                    }, 500L);
                }
            }
        });
        return true;
    }

    private static void ensureInit(Object gls) {
        if (sInitDone) return;
        synchronized (Flashlight.class) {
            if (sInitDone) return;
            final Context ctx = context(gls);
            if (ctx == null) return;
            try {
                Torch.init(ctx);
                sTorchSelected = isTorchSelected(ctx);
                Uri uri = Settings.Secure.getUriFor(Feature.FLASHLIGHT);
                ctx.getContentResolver().registerContentObserver(uri, false,
                        new ContentObserver(Torch.handler()) {
                            @Override
                            public void onChange(boolean selfChange) {
                                sTorchSelected = isTorchSelected(ctx);
                            }
                        });
                sInitDone = true;
            } catch (Throwable t) {
                Module.log("flashlight: init failed", t);
            }
        }
    }

    private static Context context(Object gls) {
        try {
            Object c = Xp.callMethod(gls, "getContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) { }
        try {
            Object c = Xp.getObjectField(gls, "mContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) { }
        return null;
    }

    private static void goToSleep(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            try {
                Method m = PowerManager.class.getMethod(
                        "goToSleep", long.class, int.class, int.class);
                m.invoke(pm, SystemClock.uptimeMillis(), SLEEP_REASON_POWER_BUTTON, 0);
            } catch (NoSuchMethodException e) {
                PowerManager.class.getMethod("goToSleep", long.class)
                        .invoke(pm, SystemClock.uptimeMillis());
            }
        } catch (Throwable t) {
            Module.log("goToSleep failed", t);
        }
    }

}
