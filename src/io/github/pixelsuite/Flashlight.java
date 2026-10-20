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

/**
 * Power double press toggles the flashlight.
 *
 * System side: GestureLauncherService's camera gesture is intercepted for a power double tap
 * and toggles the torch instead (Torch). Settings side: adds "Toggle flashlight" next to Camera
 * and Wallet on Settings > System > Gestures > Double press power button, and fixes the
 * Gestures summary. The choice lives in Settings.Secure Feature.FLASHLIGHT; while it is on, the
 * stock target stays on Camera so the system keeps the gesture armed.
 */
final class Flashlight {

    /** AOSP target: 0 = camera, 1 = wallet. */
    static final String KEY_TARGET = "double_tap_power_button_gesture";
    /** AOSP: 1 = double press enabled. */
    static final String KEY_ENABLED = "double_tap_power_button_gesture_enabled";

    private Flashlight() {}

    static boolean isTorchSelected(Context ctx) {
        return Feature.on(ctx, Feature.FLASHLIGHT);
    }


    private static final String GLS = "com.android.server.GestureLauncherService";
    /** StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP */
    private static final int SOURCE_POWER_DOUBLE_TAP = 1;
    /** PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON */
    private static final int SLEEP_REASON_POWER_BUTTON = 4;
    /** Presses further apart than this start a new sequence. */
    private static final long SEQUENCE_GAP_MS = 500L;

    private static volatile boolean sTorchSelected;
    private static volatile boolean sInitDone;
    private static volatile long sLastPowerDown;
    /** Whether the screen was on at the first press of the current double press. */
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
            // Every power key down passes through here first: remember whether the screen
            // was on when this press sequence started, and lazily set things up.
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
                    // Only the power double press, never camera lift/other sources.
                    if (param.args.length >= 2 && param.args[1] instanceof Integer
                            && (Integer) param.args[1] != SOURCE_POWER_DOUBLE_TAP) return;
                    if (handleIfTorch(param.thisObject)) param.setResult(Boolean.TRUE);
                }
            });

            // Belt and braces: if the stock target ends up on Wallet while flashlight is
            // selected, intercept that path too.
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
            // Not on this build.
        }
    }

    /** @return true if the double press was consumed as a flashlight toggle. */
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
                    // Screen was off before the double press: put it back to sleep once the
                    // power key handling has finished (same idea as PixelXpert).
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

    /** Runs on every power key press: after the first, only the volatile check. */
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
            Object c = Xp.callMethod(gls, "getContext");   // SystemService
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
