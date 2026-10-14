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

/**
 * Double press Volume Down on the Always On Display to open the camera.
 *
 * system_server: detects the double press while the AOD is showing (or the lock screen is
 * fading into it), with no media playing and no call, and launches the camera exactly like
 * the power-button camera gesture. System UI: keeps the haptic identical by dropping its own
 * camera buzz for these launches. On/off: Settings.Secure Feature.VOLDOWN_CAMERA.
 */
final class VolumeCamera {

    /** Global: uptime of our last launch, so System UI can tell it apart from the power button. */
    static final String KEY_LAUNCH_AT = "voldowncam_launch_at";

    private VolumeCamera() {}


    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    /** StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP */
    static final int SOURCE_POWER_DOUBLE_TAP = 1;
    /** PowerManager.WAKE_REASON_CAMERA_LAUNCH */
    private static final int WAKE_REASON_CAMERA_LAUNCH = 5;
    private static final long DOUBLE_PRESS_MS = 400L;
    private static final int RESULT_CONSUMED = 0;

    private static ClassLoader sClassLoader;
    private static volatile boolean sInitDone;
    private static volatile boolean sEnabled = true;

    // Input-thread state.
    private static long sLastDown;
    private static boolean sConsumingPress;
    private static String sLastLogged;

    

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
                        Module.log("volume key handling failed", t);
                    }
                }
            });
            Module.log("system hooks installed");
        } catch (Throwable t) {
            Module.log("system hook setup failed", t);
        }
    }

    /** Runs on the input thread. @return true to consume the event. */
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
            String blocked = blockedReason(ctx);
            sConsumingPress = blocked == null;
            if (!sConsumingPress) {
                if (!"screen is awake".equals(blocked) && !blocked.equals(sLastLogged)) {
                    Module.log("not active: " + blocked);
                }
                sLastLogged = blocked;
                sLastDown = 0;
                return false;   // normal volume behaviour
            }
            sLastLogged = null;
            long t = ev.getEventTime();
            if (sLastDown != 0 && t - sLastDown <= DOUBLE_PRESS_MS) {
                sLastDown = 0;
                launchCamera(pwm, ctx);
            } else {
                sLastDown = t;
            }
            return true;
        }
        // Repeats and the key-up of a consumed press stay consumed: no half key presses.
        return sConsumingPress;
    }

    // ------------------------------------------------------------------ rules

    /** @return null when both rules allow the gesture, otherwise why not (for the log). */
    static String blockedReason(Context ctx) {
        try {
            // Rule 1: AOD on a locked phone (including the lock screen -> AOD animation).
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isInteractive()) return "screen is awake";

            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return "no display";
            int state = d.getState();
            if (state != Display.STATE_ON && state != Display.STATE_DOZE
                    && state != Display.STATE_DOZE_SUSPEND) {
                return "display off (state " + state + ")";
            }
            if (!isAodEnabled(ctx)) return "Always On Display is off";

            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardLocked()) return "not locked";

            // Rule 2: no media, no call.
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                if (am.isMusicActive()) return "media playing";
                if (am.getMode() != AudioManager.MODE_NORMAL) return "call or ringing (audio)";
            }
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && tm.isInCall()) return "call in progress";

            return null;
        } catch (Throwable t) {
            Module.log("rule check failed", t);
            return "rule check failed";
        }
    }

    private static boolean isAodEnabled(Context ctx) {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(), "doze_always_on");
            return v == null || !"0".equals(v.trim());   // unset: decided by the display state
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------ launch

    private static void launchCamera(Object pwm, final Context ctx) {
        Module.log("double press detected, launching camera");

        // Same haptic as the flashlight toggle, without delaying the launch.
        Bg.handler().post(new Runnable() {
            @Override
            public void run() {
                Haptics.click(ctx);
            }
        });

        // Lets SystemUI recognise this launch and skip its own camera buzz.
        try {
            Settings.Global.putLong(ctx.getContentResolver(), KEY_LAUNCH_AT,
                    SystemClock.uptimeMillis());
        } catch (Throwable t) {
            Module.log("could not mark launch for SystemUI", t);
        }

        // 1. PhoneWindowManager.handleCameraGesture(): the flags the keyguard reads on wake-up.
        boolean goingToSleep = false;
        try {
            Xp.setBooleanField(pwm, "mPowerButtonLaunchGestureTriggered", true);
            goingToSleep = Xp.getBooleanField(pwm, "mRequestedOrSleepingDefaultDisplay");
            if (goingToSleep) {
                Xp.setBooleanField(
                        pwm, "mPowerButtonLaunchGestureTriggeredDuringGoingToSleep", true);
            }
        } catch (Throwable t) {
            Module.log("could not set the camera gesture flags", t);
        }

        // 2. GestureLauncherService.handleCameraGesture(): tell SystemUI to launch the camera.
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

        // 3. Wake the way PWM does for the camera gesture. (With the power button the first
        //    press already woke the phone from AOD; here nothing has, so always wake.)
        wake(pwm, ctx);
        Module.log("camera launch sent (going to sleep: " + goingToSleep + ")");
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

    // ------------------------------------------------------------------ setup

    private static synchronized void ensureInit(final Context ctx) {
        if (sInitDone) return;
        try {
            sEnabled = isEnabled(ctx);
            ctx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Feature.VOLDOWN_CAMERA), false,
                    new ContentObserver(Bg.handler()) {
                        @Override
                        public void onChange(boolean selfChange) {
                            sEnabled = isEnabled(ctx);
                            Module.log("enabled: " + sEnabled);
                        }
                    });
            sInitDone = true;
            Module.log("initialised, enabled: " + sEnabled);
        } catch (Throwable t) {
            Module.log("init failed", t);
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
            // Fallback in case that private method is renamed or inlined on this build.
            for (Method m : Vibrator.class.getDeclaredMethods()) {
                if (m.getName().equals("vibrate") && !Modifier.isAbstract(m.getModifiers())) {
                    Xp.hookMethod(m, skipWhileSuppressed);
                }
            }
            Module.log("SystemUI hooks installed");
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
