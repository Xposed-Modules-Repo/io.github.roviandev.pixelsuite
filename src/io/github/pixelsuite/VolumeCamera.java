package io.github.pixelsuite;

import android.app.KeyguardManager;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.Display;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class VolumeCamera {

    static final String KEY_LAUNCH_AT = "voldowncam_launch_at";

    private VolumeCamera() {}

    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    static final int SOURCE_POWER_DOUBLE_TAP = 1;
    private static final int WAKE_REASON_CAMERA_LAUNCH = 5;
    private static final long DOUBLE_PRESS_MS = 400L;
    private static final int RESULT_CONSUMED = 0;

    private static ClassLoader sClassLoader;
    private static volatile boolean sInitDone;
    private static volatile boolean sEnabled = true;

    private static long sLastDown;
    private static boolean sConsumingPress;
    private static boolean sErrorLogged;

    static void installSystem(ClassLoader cl) {
        sClassLoader = cl;
        try {
            Class<?> pwm = Xp.findClass(PWM, cl);
            Xp.hookAllMethods(pwm, "interceptKeyBeforeQueueing", new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof KeyEvent)) return;
                    KeyEvent ev = (KeyEvent) param.args[0];
                    if (ev.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN) return;
                    try {
                        if (onVolumeDown(param.thisObject, ev)) param.setResult(RESULT_CONSUMED);
                    } catch (Throwable t) {
                        if (!sErrorLogged) {
                            sErrorLogged = true;
                            Module.log("volume key handling failed", t);
                        }
                    }
                }
            });
            Module.log("volume-down camera: system hooks installed");
        } catch (Throwable t) {
            Module.log("system hook setup failed", t);
        }
    }

    private static boolean onVolumeDown(Object pwm, KeyEvent ev) {
        final Context ctx = context(pwm);
        if (ctx == null) return false;
        ensureInit(ctx);
        if (!sEnabled) {
            sConsumingPress = false;
            sLastDown = 0;
            return false;
        }

        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
            sConsumingPress = gestureAllowed(ctx);
            if (!sConsumingPress) {
                sLastDown = 0;
                return false;
            }
            long t = ev.getEventTime();
            if (sLastDown != 0 && t - sLastDown <= DOUBLE_PRESS_MS) {
                sLastDown = 0;
                launchCamera(pwm, ctx);
            } else {
                sLastDown = t;
            }
            return true;
        }
        return sConsumingPress;
    }

    private static boolean gestureAllowed(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isInteractive()) return false;

            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return false;
            int state = d.getState();
            if (state != Display.STATE_ON && state != Display.STATE_DOZE
                    && state != Display.STATE_DOZE_SUSPEND) {
                return false;
            }
            if (!isAodEnabled(ctx)) return false;

            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardLocked()) return false;

            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                if (am.isMusicActive()) return false;
                if (am.getMode() != AudioManager.MODE_NORMAL) return false;
            }
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            return tm == null || !tm.isInCall();
        } catch (Throwable t) {
            if (!sErrorLogged) {
                sErrorLogged = true;
                Module.log("volume-down camera: rule check failed", t);
            }
            return false;
        }
    }

    private static boolean isAodEnabled(Context ctx) {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(), "doze_always_on");
            return v == null || !"0".equals(v.trim());
        } catch (Throwable t) {
            return true;
        }
    }

    private static void launchCamera(Object pwm, final Context ctx) {
        Bg.handler().post(new Runnable() {
            @Override
            public void run() {
                Haptics.click(ctx);
            }
        });

        try {
            Settings.Global.putLong(ctx.getContentResolver(), KEY_LAUNCH_AT,
                    SystemClock.uptimeMillis());
        } catch (Throwable t) {
            Module.log("could not mark launch for SystemUI", t);
        }

        try {
            Xp.setBooleanField(pwm, "mPowerButtonLaunchGestureTriggered", true);
            if (Xp.getBooleanField(pwm, "mRequestedOrSleepingDefaultDisplay")) {
                Xp.setBooleanField(
                        pwm, "mPowerButtonLaunchGestureTriggeredDuringGoingToSleep", true);
            }
        } catch (Throwable t) {
            Module.log("could not set the camera gesture flags", t);
        }

        try {
            Class<?> local = Xp.findClass("com.android.server.LocalServices", sClassLoader);
            Class<?> sbmi = Xp.findClass(
                    "com.android.server.statusbar.StatusBarManagerInternal", sClassLoader);
            Object sbm = Xp.callStaticMethod(local, "getService", sbmi);
            if (sbm != null) {
                Xp.callMethod(sbm, "onCameraLaunchGestureDetected",
                        SOURCE_POWER_DOUBLE_TAP);
            } else {
                Module.log("StatusBarManagerInternal unavailable");
            }
        } catch (Throwable t) {
            Module.log("could not notify SystemUI", t);
        }

        wake(pwm, ctx);
    }

    private static void wake(Object pwm, Context ctx) {
        try {
            Object policy = Xp.getObjectField(pwm, "mWindowWakeUpPolicy");
            if (policy != null) {
                Xp.callMethod(policy, "wakeUpFromPowerKeyCameraGesture");
                return;
            }
        } catch (Throwable t) {
            Module.log("wake policy unavailable, using PowerManager", t);
        }
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            PowerManager.class.getMethod("wakeUp", long.class, int.class, String.class)
                    .invoke(pm, SystemClock.uptimeMillis(), WAKE_REASON_CAMERA_LAUNCH,
                            "CAMERA_GESTURE_PREVENT_LOCK");
        } catch (Throwable t) {
            Module.log("wake up failed", t);
        }
    }

    private static void ensureInit(final Context ctx) {
        if (sInitDone) return;
        synchronized (VolumeCamera.class) {
            if (sInitDone) return;
            try {
                sEnabled = isEnabled(ctx);
                ctx.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(Feature.VOLDOWN_CAMERA), false,
                        new ContentObserver(Bg.handler()) {
                            @Override
                            public void onChange(boolean selfChange) {
                                sEnabled = isEnabled(ctx);
                            }
                        });
                sInitDone = true;
            } catch (Throwable t) {
                Module.log("volume-down camera: init failed", t);
            }
        }
    }

    static boolean isEnabled(Context ctx) {
        return Feature.on(ctx, Feature.VOLDOWN_CAMERA);
    }

    private static Context context(Object pwm) {
        try {
            Object c = Xp.getObjectField(pwm, "mContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) { }
        return null;
    }

    private static final String CALLBACKS =
            "com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks";
    private static final long MARK_WINDOW_MS = 3000L;

    private static volatile boolean sSuppress;

    static void installSystemUi(ClassLoader cl) {
        final Class<?> callbacks;
        try {
            callbacks = Xp.findClass(CALLBACKS, cl);
        } catch (Throwable t) {
            Module.log("SystemUI camera callbacks not found", t);
            return;
        }
        try {
            Xp.hookAllMethods(callbacks, "onCameraLaunchGestureDetected",
                    new Xp.Callback() {
                        @Override
                        protected void beforeHookedMethod(Xp.Param param) {
                            sSuppress = isOurLaunch(param.thisObject);
                        }

                        @Override
                        protected void afterHookedMethod(Xp.Param param) {
                            sSuppress = false;
                        }
                    });

            Xp.Callback skipWhileSuppressed = new Xp.Callback() {
                @Override
                protected void beforeHookedMethod(Xp.Param param) {
                    if (sSuppress) param.setResult(null);
                }
            };
            Xp.hookAllMethods(callbacks, "vibrateForCameraGesture", skipWhileSuppressed);
            for (Method m : Vibrator.class.getDeclaredMethods()) {
                if (m.getName().equals("vibrate") && !Modifier.isAbstract(m.getModifiers())) {
                    Xp.hookMethod(m, skipWhileSuppressed);
                }
            }
            Module.log("volume-down camera: SystemUI hooks installed");
        } catch (Throwable t) {
            Module.log("SystemUI hook setup failed", t);
        }
    }

    private static boolean isOurLaunch(Object callbacks) {
        try {
            Context ctx = (Context) Xp.getObjectField(callbacks, "mContext");
            long at = Settings.Global.getLong(ctx.getContentResolver(), KEY_LAUNCH_AT, 0L);
            long age = SystemClock.uptimeMillis() - at;
            return at > 0 && age >= 0 && age <= MARK_WINDOW_MS;
        } catch (Throwable t) {
            return false;
        }
    }
}
