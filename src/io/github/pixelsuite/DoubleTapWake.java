package io.github.pixelsuite;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class DoubleTapWake {

    static final String KEY_TAP_GESTURE = "doze_tap_gesture";
    static final String KEY_DOUBLE_TAP_GESTURE = "doze_pulse_on_double_tap";

    static final String OLD_TITLE = "Tap to check phone";
    static final String NEW_TITLE = "Tap or Double Tap to check phone";

    private static final String KEY_TAP = "pixelsuite_wake_tap";
    private static final String KEY_DOUBLE = "pixelsuite_wake_double_tap";
    private static final String KEY_MODDED = "pixelsuite_lock_modded";
    private static final String KEY_STOCK = "pixelsuite_lock_stock";
    private static final String KEY_WAKE_GROUP = "pixelsuite_wake_group";
    private static final String KEY_LOCK_GROUP = "pixelsuite_lock_group";
    private static final String CLICK = "androidx.preference.Preference$OnPreferenceClickListener";

    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String PMS = "com.android.server.power.PowerManagerService";
    private static final int STATE_ON = 2, STATE_DOZE = 3, STATE_DOZE_SUSPEND = 4;
    private static final String POWER_GROUP = "com.android.server.power.PowerGroup";

    private static final long DOUBLE_GAP_MS = 400L;
    private static final long TAP_MAX_MS = 400L;
    private static final int SLEEP_REASON_POWER_BUTTON = 4;
    private static final int MAX_PAGE_PREFS = 5;

    private static volatile boolean sWakeOn, sSleepOn, sInitDone;
    private static volatile long sLastSingle;
    private static volatile boolean sUdfpsSeen;
    private static volatile int sUdfpsVisibility = -1;
    private static final String UDFPS_WINDOW = "UdfpsControllerOverlay";
    private static final long SINGLE_PAIR_MS = 1000L;
    private static final long BACKUP_PAIR_MS = 1000L;
    private static final long BACKUP_WAIT_MS = 150L;
    private static final String BACKUP_DETAILS = "io.github.pixelsuite:DOUBLE_TAP";
    private static final float LOOSE_SLOP_TIMES = 4f;
    private static final long LOOSE_REPORT_MS = TAP_MAX_MS + 200L;
    private static volatile long sLooseUpAt;
    private static volatile float sLooseX, sLooseY;
    private static volatile long sLoosePairAt;
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final long PULSE_DELAY_MS = 150L;
    private static final long WAKE_GUARD_MS = 800L;
    private static final long LEFTOVER_COUNT_MS = 350L;
    private static final float WAKE_GUARD_TOP = 0.70f;
    private static volatile long sTapWakeAt;
    private static volatile long sScreenOffAt, sScreenOnAt;
    private static final String ALT_BOUNCER_WINDOW = "AlternateBouncerView";
    private static int sAltWindowId;
    private static volatile int sAltVisibility = -1;
    private static volatile long sAltShownAt;
    private static volatile boolean sLockShortcuts;
    private static volatile boolean sMasterOn = true;
    private static volatile boolean sCharging;
    private static volatile int sAodTouchLast = -1;
    private static volatile long sWindowUntil;
    private static final long TOUCH_WINDOW_MS = 3000L;
    private static final long WINDOW_PAIR_GRACE_MS = 600L;
    private static final long WINDOW_HOLD_STEP_MS = 250L;
    private static final int WINDOW_HOLD_STEPS = 4;
    private static volatile long sWindowFirstAt, sPairUntil;
    private static volatile PowerManager.WakeLock sWindowWl;
    private static volatile Object sAodPms;
    private static volatile Method sAodMethod;
    private static volatile Object[] sAodArgs;
    private static volatile long sChipTapAt;
    private static long sHandledChipAt;
    private static long sBackSentFor;
    private static final long CHIP_WINDOW_MS = 1000L;
    private static final long CHIP_PROMPT_MAX_MS = 800L;
    private static final long BACK_DELAY_MS = 80L;
    private static final long LAUNCH_DROP_MS = 1200L;
    private static final int START_CANCELED = -96;
    private static volatile boolean sDropArmed;
    private static volatile long sUnlockedAt;
    private static volatile int sSystemUiUid = -1;
    private static volatile long sTouchDownAt;
    private static volatile long sTouchUpAt;
    private static volatile long sTouchCancelAt;
    private static volatile boolean sTouchTap;
    private static float sTouchX, sTouchY;
    private static volatile int sDownSeq;
    private static final Object sTouchLock = new Object();
    private static final long QUIET_AFTER_UP_MS = 600L;
    private static final long DOWN_WAIT_MS = 60L;
    private static volatile long sFingerAt;
    private static final long FINGER_QUIET_MS = 2000L;
    private static final String VMS = "com.android.server.vibrator.VibratorManagerService";
    private static final int USAGE_CLASS_MASK = 0x0F;
    private static final int USAGE_CLASS_FEEDBACK = 0x02;
    private static final int USAGE_ACCESSIBILITY = 0x42;
    private static final int USAGE_TOUCH = 0x12;
    private static final String SMARTSPACE_STUB =
            "com.android.server.smartspace.SmartspaceManagerService$SmartspaceManagerStub";
    private static final int SMARTSPACE_TAP = 1;
    private static int sTapReason = 15;
    private static int sBiometricReason = 17;

    private static Context sCtx;
    private static Object sPwm;
    private static PowerManager sPm;
    private static Handler sWorker;
    private static volatile Object sMonitor;
    private static volatile InputChannel sChannel;
    private static volatile Receiver sReceiver;
    private static int sMonitorFailures;
    private static final int MAX_MONITOR_FAILURES = 3;
    private static int sTouchSlop, sDoubleTapSlop;

    private static Handler sMain;

    private interface Action {
        void run(Object pref, Object value);
    }

    private DoubleTapWake() {}

    static void installSystemUi(ClassLoader cl) { }

    static void installSystem(ClassLoader cl) {
        Module.log("double-tap-wake: v1.1.3 (touch on while held or on the lap, motion sensor on"
                + " a desk)");
        try {
            sTapReason = PowerManager.class.getField("WAKE_REASON_TAP").getInt(null);
        } catch (Throwable ignored) {
        }
        try {
            sBiometricReason = PowerManager.class.getField("WAKE_REASON_BIOMETRIC").getInt(null);
        } catch (Throwable ignored) { }

        try {
            Xp.hookAllMethods(Xp.findClass(PWM, cl), "systemBooted", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    try {
                        init(param.thisObject,
                                (Context) Xp.getObjectField(param.thisObject, "mContext"));
                    } catch (Throwable t) {
                        Module.log("double-tap-wake: init failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Module.log("double-tap-wake: systemBooted hook failed", t);
        }

        Xp.Callback wake = new Xp.Callback() {
            @Override
            protected void beforeHookedMethod(Xp.Param param) {
                try {
                    onWakeRequest(param);
                } catch (Throwable t) {
                    Module.log("double-tap-wake: wake handling failed", t);
                }
            }
        };
        int n = 0;
        try {
            n = Xp.hookAllMethods(Xp.findClass(PMS, cl), "wakePowerGroupLocked", wake).size();
        } catch (Throwable ignored) { }
        if (n == 0) {
            try {
                n = Xp.hookAllMethods(Xp.findClass(POWER_GROUP, cl), "wakeUpLocked", wake).size();
            } catch (Throwable ignored) { }
        }
        Module.log("double-tap-wake: hooked " + n + " wake method(s), tap reason " + sTapReason);
        installWindowWatch(cl);
        installSmartspaceWatch(cl);
        installLaunchGuard(cl);
        installTapQuiet(cl);
        installFingerWatch(cl);
        installAodTouch(cl);
        installShadeGuard(cl);
        installUnlockTouchEnd(cl);
    }

    private static volatile long sEndedTouchAt;

    private static void installUnlockTouchEnd(ClassLoader cl) {
        Xp.Callback cb = new Xp.Callback() {
            @Override
            protected void beforeHookedMethod(Xp.Param param) {
                try {
                    if (!param.thisObject.getClass().getName().contains("fingerprint")) return;
                    for (Object a : param.args) {
                        if (a instanceof Boolean) {
                            if ((Boolean) a) endTouchAtUnlock(0);
                            return;
                        }
                    }
                } catch (Throwable t) {
                    Module.log("double-tap-wake: unlock touch end failed", t);
                }
            }
        };
        String base = "com.android.server.biometrics.sensors.";
        String[] classes = {
                base + "fingerprint.aidl.FingerprintAuthenticationClient",
                base + "AuthenticationClient",
        };
        int n = 0;
        for (String c : classes) {
            try {
                n += Xp.hookAllMethods(Xp.findClass(c, cl), "onAuthenticated", cb).size();
            } catch (Throwable ignored) { }
        }
        Module.log("double-tap-wake: unlock touch end " + (n > 0 ? "installed (" + n + ")"
                : "unavailable (shade guard only)"));
    }

    private static void endTouchAtUnlock(long wakeAt) {
        if (sMonitor == null) return;
        long down = sTouchDownAt, up = sTouchUpAt, cancel = sTouchCancelAt;
        if (down == 0 || up != 0 || cancel >= down) return;
        if (down == sEndedTouchAt) return;
        boolean startedAsleep = down > sScreenOffAt && (wakeAt == 0 || down <= wakeAt);
        boolean asleep = startedAsleep
                && (wakeAt != 0 || (sPm != null && !sPm.isInteractive()));
        if (!asleep && !lockScreenShowing()) {
            Module.log("double-tap-wake: fingerprint accepted with a finger down in an app, "
                    + "left alone");
            return;
        }
        sEndedTouchAt = down;
        pilfer("fingerprint unlock with the finger still on the screen, touch ended before "
                + "waking (no shade pull-down)" + (wakeAt != 0 ? " [wake]" : ""),
                wakeAt == 0 ? PILFER_WAIT_MS : 0L);
    }

    private static final long SHADE_GUARD_MS = 1000L;
    private static final long[] SHADE_CLOSE_AT_MS = { 300L, 700L, 1200L };
    private static volatile boolean sShadeOpen;
    private static volatile long sShadeRevealAt;

    private static void installShadeGuard(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> sbms = Xp.findClass(
                    "com.android.server.statusbar.StatusBarManagerService", cl);
            n = Xp.hookAllMethods(sbms, "onPanelRevealed", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    sShadeOpen = true;
                    sShadeRevealAt = SystemClock.uptimeMillis();
                    maybeCloseShade(param.thisObject);
                }
            }).size();
            Xp.hookAllMethods(sbms, "onPanelHidden", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    sShadeOpen = false;
                }
            });
        } catch (Throwable t) {
            Module.log("double-tap-wake: shade guard failed", t);
        }
        Module.log("double-tap-wake: shade guard " + (n > 0 ? "installed" : "unavailable"));
    }

    private static void maybeCloseShade(final Object statusBarService) {
        long unlocked = sUnlockedAt;
        final long revealAt = sShadeRevealAt;
        if (unlocked == 0 || revealAt - unlocked > SHADE_GUARD_MS) return;
        long down = sTouchDownAt, up = sTouchUpAt;
        if (down == 0 || down > unlocked || (up != 0 && up < unlocked)) return;
        Handler h = sWorker;
        if (h == null) return;
        for (long delay : SHADE_CLOSE_AT_MS) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!sShadeOpen) return;
                    if (sShadeRevealAt != revealAt) return;
                    if (sTouchDownAt > revealAt) return;
                    if (keyguardShowing()) return;
                    try {
                        Xp.callMethod(statusBarService, "collapsePanels");
                        Module.log("double-tap-wake: notification shade opened by the unlocking "
                                + "touch, closing it");
                    } catch (Throwable t) {
                        Module.log("double-tap-wake: could not close the shade", t);
                    }
                }
            }, delay);
        }
    }

    private static void installAodTouch(ClassLoader cl) {
        int n = 0;
        try {
            n = Xp.hookAllMethods(Xp.findClass(PMS, cl), "setDozeOverrideFromDreamManagerInternal",
                    new Xp.Callback() {
                        @Override
                        protected void beforeHookedMethod(Xp.Param param) {
                            if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                            boolean ours = sReapplyThread == Thread.currentThread();
                            Object[] prev = sAodArgs;
                            if (param.method instanceof Method) {
                                sAodPms = param.thisObject;
                                sAodMethod = (Method) param.method;
                                sAodArgs = param.args.clone();
                            }
                            int state = (Integer) param.args[0];
                            boolean doze = state == STATE_DOZE || state == STATE_DOZE_SUSPEND;
                            if (!ours) onAodRequest(prev, param.args, doze, state);
                            updateRecording(doze ? "always-on display low-power"
                                    : "always-on display state " + state);
                            if (!doze) return;
                            boolean keep = aodTouchWanted();
                            if (keep) param.args[0] = STATE_ON;
                            int now = keep ? 1 : 0;
                            if (now != sAodTouchLast) {
                                sAodTouchLast = now;
                                Module.log(keep
                                        ? "double-tap-wake: always-on display touchscreen fully on ("
                                                + aodTouchReason() + ")"
                                        : "double-tap-wake: always-on display in low-power mode");
                            }
                            if (!keep) armPickupLater();
                        }
                    }).size();
        } catch (Throwable t) {
            Module.log("double-tap-wake: always-on touch hook failed", t);
        }
        Module.log("double-tap-wake: always-on touch hooked " + n + " method(s)");
    }

    private static boolean aodTouchWanted() {
        if (!sWakeOn) return false;
        return sCharging || touchWindowOpen() || shakingOn() || heldOn() || judgingOn();
    }

    private static String aodTouchReason() {
        if (sCharging) return "charging";
        if (shakingOn()) return "phone shaking";
        if (touchWindowOpen()) return "after a first tap";
        HeldWatch w = sHeldWatch;
        if (w != null && w.mHeldSeen) return "phone in the hand";
        return heldOn() ? "checking whether the phone is in the hand"
                : "judging hand-held from the recording";
    }

    private static boolean touchWindowOpen() {
        long u = sWindowUntil;
        return u != 0 && SystemClock.uptimeMillis() < u;
    }

    private static void initAodPower(Context ctx, Looper looper) {
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            sCharging = bm.isCharging();
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        sCharging = Intent.ACTION_POWER_CONNECTED.equals(i.getAction());
                        reapplyAodNow();
                        updateRecording(sCharging ? "charging" : "unplugged");
                    } catch (Throwable t) {
                        Module.log("double-tap-wake: charging change failed", t);
                    }
                }
            };
            registerReceiver(ctx, r, f, new Handler(looper));
            Module.log("double-tap-wake: charging watch on (charging=" + sCharging + ")");
        } catch (Throwable t) {
            Module.log("double-tap-wake: charging watch unavailable", t);
        }
    }

    private static void reapplyAodState() {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    reapplyAodNow();
                } catch (Throwable t) {
                    Module.log("double-tap-wake: always-on display re-apply failed", t);
                }
            }
        });
    }

    private static boolean reapplyAodNow() {
        final Object pms = sAodPms;
        final Method m = sAodMethod;
        final Object[] args = sAodArgs;
        if (pms == null || m == null || args == null || sPm == null) return false;
        if (sPm.isInteractive()) return false;
        if (!aodDozing(args)) return false;
        int want = aodTouchWanted() ? 1 : 0;
        if (want == sAodTouchLast) return false;
        try {
            m.setAccessible(true);
            sReapplyThread = Thread.currentThread();
            try {
                m.invoke(pms, args.clone());
            } finally {
                sReapplyThread = null;
            }
            return true;
        } catch (Throwable t) {
            Module.log("double-tap-wake: always-on display re-apply failed", t);
            return false;
        }
    }

    private static boolean aodDozing(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Integer)) return false;
        int state = (Integer) args[0];
        return state == STATE_DOZE || state == STATE_DOZE_SUSPEND;
    }

    private static boolean openTouchWindow(long tapAt) {
        final Handler h = sWorker;
        if (h == null || sPm == null || sPm.isInteractive()) return false;
        if (touchWindowOpen()) return true;
        boolean alreadyOn = sAodTouchLast == 1 && aodDozing(sAodArgs);
        final long start = SystemClock.uptimeMillis();
        final long until = start + TOUCH_WINDOW_MS;
        sWindowUntil = until;
        if (!alreadyOn && !reapplyAodNow()) {
            sWindowUntil = 0;
            return false;
        }
        startMotionTap(start, until);
        sWindowFirstAt = tapAt;
        sPairUntil = until + WINDOW_PAIR_GRACE_MS;
        holdAwake(TOUCH_WINDOW_MS + WINDOW_HOLD_STEP_MS * WINDOW_HOLD_STEPS + 500L);
        Module.log("double-tap-wake: first tap on the always-on display, touchscreen on for "
                + TOUCH_WINDOW_MS + " ms" + (alreadyOn ? " (was already coming on)" : ""));
        h.postDelayed(new WindowClose(start, until), TOUCH_WINDOW_MS);
        return true;
    }

    private static final long MOTION_LISTEN_MS = 500L;
    private static final long MOTION_SETTLE_MS = 15L;
    private static final float MOTION_SETTLE_TAU_MS = 5f, MOTION_TAU_MS = 20f;
    private static final float MOTION_MIN = 0.09f, MOTION_NOISE_TIMES = 3.5f;
    private static final int MOTION_NOISE_MIN_N = 8;
    private static final long MOTION_GAP_MS = 15L;
    private static final long MOTION_QUIET_MS = 40L;
    private static final int MOTION_JOLT_SAMPLES = 2;
    private static final long MOTION_PROX_WAIT_MS = 120L;
    private static final float MOTION_NOTE = 0.06f;
    private static final int MOTION_NOTES_MAX = 24;
    private static final float MOTION_SHARP_MIN = 0.5f, MOTION_SHARP_RATIO = 2f;
    private static final float MOTION_STANDOUT_TIMES = 2.5f, MOTION_STANDOUT_MIN = 0.15f;
    private static final float MOTION_REST_RATIO = 0.5f, MOTION_REGROW_RATIO = 1.0f;
    private static final float MOTION_LOBE_FALL = 0.8f;
    private static final long MOTION_LOBE_MS = 50L;
    private static final long TAP1_GUESS_MS = 100L;
    private static final long MOTION_TRACE_FROM_MS = 200L;
    private static final int MOTION_TRACE_MAX = 400;
    private static final long MOTION_SHARP_UNTIL_MS = 300L;
    private static final long MOTION_RISE_FROM_MS = 40L, MOTION_RISE_TO_MS = 20L;
    private static final long MOTION_FALL_MS = 25L;
    private static final int MOTION_HISTORY = 64;
    private static final String MOTION_DETAILS = "io.github.pixelsuite:MOTION_TAP";
    private static volatile MotionTap sMotion;

    private static final long MOTION_LOOKBACK_MS = 600L;
    private static final long TAP1_BEFORE_MS = 420L, TAP1_AFTER_MS = 10L, TAP_GAP_MS = 120L;
    private static final float EARLY_TAP2_RATIO = 0.5f, EARLY_TAP2_MIN = 0.15f;
    private static final float MOTION_SOFT_FLOOR_MAX = 0.045f, MOTION_SOFT_TIMES = 2.6f,
            MOTION_SOFT_MIN = 0.085f, MOTION_SOFT_PEAK_TIMES = 1.15f;
    private static final int MOTION_SOFT_SAMPLES = 3, MOTION_FLOOR_MIN_N = 40;
    private static final long MOTION_SOFT_UNTIL_MS = 300L;
    private static final long MOTION_FLOOR_GAP_MS = 20L;
    private static volatile SensorEventListener sLiveKick;

    private static void startMotionTap(long startUptime, long windowUntil) {
        stopMotionTap("new first tap");
        Handler h = sWorker;
        Context ctx = sCtx;
        if (h == null || ctx == null) return;
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc == null) return;
            final Recorder rec = sRecorder;
            final MotionTap m = new MotionTap(sm, h, startUptime, windowUntil, rec != null);
            long t0 = SystemClock.uptimeMillis();
            if (rec != null) {
                sMotion = m;
                rec.replayInto(m, m.mStartNs - MOTION_LOOKBACK_MS * 1000000L);
                if (sMotion != m || m.mJolted) {
                    m.mStartTook = SystemClock.uptimeMillis() - t0;
                    return;
                }
                final SensorManager fsm = sm;
                final Sensor facc = acc;
                final long ft0 = t0;
                sensorThread().post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (sMotion != m) return;
                            fsm.flush(rec);
                            SensorEventListener kick = new SensorEventListener() {
                                @Override
                                public void onSensorChanged(SensorEvent event) { }

                                @Override
                                public void onAccuracyChanged(Sensor sensor, int accuracy) { }
                            };
                            if (fsm.registerListener(kick, facc,
                                    SensorManager.SENSOR_DELAY_FASTEST, 0, sensorThread())) {
                                sLiveKick = kick;
                            } else {
                                Module.log("double-tap-wake: live motion readings unavailable");
                            }
                            m.mStartTook = SystemClock.uptimeMillis() - ft0;
                        } catch (Throwable t) {
                            Module.log("double-tap-wake: motion live start failed", t);
                        }
                    }
                });
            } else {
                if (!sm.registerListener(m, acc, SensorManager.SENSOR_DELAY_FASTEST, 0, h)) {
                    Module.log("double-tap-wake: motion sensor unavailable");
                    return;
                }
                sMotion = m;
                m.mStartTook = SystemClock.uptimeMillis() - t0;
            }
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sMotion == m) m.timeUp();
                }
            }, MOTION_LISTEN_MS);
        } catch (Throwable t) {
            Module.log("double-tap-wake: motion sensor start failed", t);
        }
    }

    private static void stopMotionTap(String why) {
        MotionTap m = sMotion;
        if (m == null) return;
        sMotion = null;
        m.stop(why);
    }

    private static void dropLiveKick() {
        sensorThread().post(new Runnable() {
            @Override
            public void run() {
                SensorEventListener kick = sLiveKick;
                if (kick == null) return;
                sLiveKick = null;
                try {
                    Context ctx = sCtx;
                    SensorManager sm = ctx == null ? null
                            : (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
                    if (sm != null) sm.unregisterListener(kick);
                } catch (Throwable ignored) { }
            }
        });
    }

    private static volatile Handler sSensorThread;

    private static Handler sensorThread() {
        Handler h = sSensorThread;
        if (h != null) return h;
        synchronized (DoubleTapWake.class) {
            if (sSensorThread == null) {
                HandlerThread t = new HandlerThread("PixelSuiteSensors");
                t.start();
                sSensorThread = new Handler(t.getLooper());
            }
            return sSensorThread;
        }
    }

    private static final int RECORD_PERIOD_US = 2500;
    private static final int RECORD_LATENCY_US = 10000000;
    private static final int RECORD_MIN_FIFO = 300;
    private static final int RECORD_KEEP = 800;
    private static final long PRE_RECORD_MS = 8000L;
    private static volatile Recorder sRecorder;
    private static boolean sRecordUnsupportedLogged;

    private static boolean recordWanted() {
        return sWakeOn && sPm != null && !sPm.isInteractive()
                && (aodShowing(sAodArgs) || justOff())
                && !sCharging && !shakingOn();
    }

    private static boolean aodShowing(Object[] args) {
        if (aodDozing(args)) return true;
        return args != null && args.length > 0 && args[0] instanceof Integer
                && (Integer) args[0] == STATE_ON;
    }

    private static boolean justOff() {
        long off = sScreenOffAt;
        return off != 0 && SystemClock.uptimeMillis() - off < PRE_RECORD_MS;
    }

    private static void updateRecording(final String why) {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean want = recordWanted();
                    if (want && sRecorder == null) {
                        startRecording(why);
                    } else if (!want && sRecorder != null) {
                        stopRecording(why);
                    }
                } catch (Throwable t) {
                    Module.log("double-tap-wake: motion recording update failed", t);
                }
            }
        });
    }

    private static void startRecording(String why) {
        Context ctx = sCtx;
        Handler h = sWorker;
        if (ctx == null || h == null) return;
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) return;
        int fifo = acc.getFifoMaxEventCount();
        if (fifo < RECORD_MIN_FIFO || acc.isWakeUpSensor()) {
            if (!sRecordUnsupportedLogged) {
                sRecordUnsupportedLogged = true;
                Module.log("double-tap-wake: the sensor hub can't record the accelerometer ("
                        + fifo + (acc.isWakeUpSensor() ? ", wake-up" : "") + "), the motion"
                        + " sensor starts at tap 1 instead");
            }
            return;
        }
        Recorder rec = new Recorder(sm);
        if (!sm.registerListener(rec, acc, RECORD_PERIOD_US, RECORD_LATENCY_US, h)) {
            Module.log("double-tap-wake: motion recording unavailable");
            return;
        }
        sRecorder = rec;
        Module.log("double-tap-wake: motion recording on (" + why + ", hub holds " + fifo
                + ", " + acc.getFifoReservedEventCount() + " reserved)");
    }

    private static void stopRecording(String why) {
        Recorder rec = sRecorder;
        if (rec == null) return;
        sRecorder = null;
        MotionTap m = sMotion;
        if (m != null && m.mFromRecording) stopMotionTap("motion recording off");
        rec.stop();
        Module.log("double-tap-wake: motion recording off (" + why + ", " + rec.mCount
                + " readings)");
    }

    private static final class Recorder implements android.hardware.SensorEventListener2 {
        private final SensorManager mSm;
        private final long[] mTs = new long[RECORD_KEEP];
        private final float[] mX = new float[RECORD_KEEP], mY = new float[RECORD_KEEP],
                mZ = new float[RECORD_KEEP];
        private int mN;
        long mCount;
        private boolean mStopped;

        Recorder(SensorManager sm) {
            mSm = sm;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (mStopped) return;
            try {
                if (e.values == null || e.values.length < 3) return;
                long ts = e.timestamp;
                int i = mN % RECORD_KEEP;
                mTs[i] = ts;
                mX[i] = e.values[0];
                mY[i] = e.values[1];
                mZ[i] = e.values[2];
                mN++;
                mCount++;
                MotionTap m = sMotion;
                if (m != null && m.mFromRecording) m.feed(ts, e.values[0], e.values[1],
                        e.values[2]);
            } catch (Throwable t) {
                Module.log("double-tap-wake: motion recording reading failed", t);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        @Override
        public void onFlushCompleted(Sensor sensor) {
            try {
                if (!mStopped) onRecordingFlushed(this);
            } catch (Throwable t) {
                Module.log("double-tap-wake: hand-held judgement failed", t);
            }
        }

        float[] stillness(long ms) {
            int n = Math.min(mN, RECORD_KEEP);
            if (n < 2) return null;
            long endNs = mTs[(mN - 1) % RECORD_KEEP];
            long fromNs = endNs - ms * 1000000L;
            float[] base = new float[3], grav = new float[3], ref = new float[3];
            long firstNs = 0, prevNs = 0;
            boolean win = false;
            int count = 0;
            float sum = 0f, maxTilt = 0f;
            for (int k = mN - n; k < mN; k++) {
                int i = k % RECORD_KEEP;
                long ts = mTs[i];
                if (ts < fromNs) continue;
                float x = mX[i], y = mY[i], z = mZ[i];
                float g = (float) Math.sqrt(x * x + y * y + z * z);
                if (Float.isNaN(g) || g < 2f || g > 40f) continue;
                if (firstNs == 0) {
                    firstNs = ts;
                    prevNs = ts;
                    base[0] = grav[0] = x;
                    base[1] = grav[1] = y;
                    base[2] = grav[2] = z;
                    continue;
                }
                if (ts <= prevNs) continue;
                float dt = (ts - prevNs) / 1e6f;
                prevNs = ts;
                float hx = x - base[0], hy = y - base[1], hz = z - base[2];
                float hp = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                boolean settling = (ts - firstNs) / 1000000L < MOTION_SETTLE_MS;
                float a = Math.min(1f, dt / (settling ? MOTION_SETTLE_TAU_MS : MOTION_TAU_MS));
                base[0] += a * hx;
                base[1] += a * hy;
                base[2] += a * hz;
                float b = Math.min(1f, dt / HELD_GRAVITY_TAU_MS);
                grav[0] += b * (x - grav[0]);
                grav[1] += b * (y - grav[1]);
                grav[2] += b * (z - grav[2]);
                if ((ts - firstNs) / 1000000L < HELD_SETTLE_MS || Float.isNaN(hp)) continue;
                if (!win) {
                    win = true;
                    System.arraycopy(grav, 0, ref, 0, 3);
                }
                sum += Math.min(hp, MOTION_MIN);
                count++;
                maxTilt = Math.max(maxTilt, angleDeg(grav, ref));
            }
            if (count == 0) return null;
            return new float[] {sum / count, maxTilt, count};
        }

        void replayInto(MotionTap m, long fromNs) {
            int n = Math.min(mN, RECORD_KEEP);
            for (int k = mN - n; k < mN; k++) {
                int i = k % RECORD_KEEP;
                if (mTs[i] >= fromNs) m.feed(mTs[i], mX[i], mY[i], mZ[i]);
            }
        }

        void stop() {
            mStopped = true;
            try {
                mSm.unregisterListener(this);
            } catch (Throwable ignored) { }
        }
    }

    private static final class MotionTap implements SensorEventListener {
        private final SensorManager mSm;
        private final Handler mHandler;
        private final long mStartUp, mStartNs, mWindowUntil;
        private long mFirstNs, mPrevNs, mPeakAt = -1;
        private long mLastOverNs, mJoltNs;
        private boolean mJoltFromQuiet;
        private int mJoltSamples;
        private float mJoltPeak;
        private final float[] mBase = new float[3];
        private int mSamples, mOld, mBad, mNoiseN, mJolts, mBlips, mNotQuiet;
        private final long[] mHistNs = new long[MOTION_HISTORY];
        private final float[] mHistHp = new float[MOTION_HISTORY];
        private int mHistN;
        private float mJoltPreMax;
        private float mJoltDip, mJoltPrevLobe;
        private final StringBuilder mTrace = new StringBuilder();
        private int mTraceN;
        private long mTraceFromNs;
        private long mSharpNs;
        private float mSharpPeak, mSharpFrom;
        volatile long mStartTook;
        private final StringBuilder mJoltLog = new StringBuilder();

        private void noteJolt(long joltNs, float peak, String what) {
            if (mJoltLog.length() > 200) return;
            mJoltLog.append(mJoltLog.length() == 0 ? "" : " ").append(String.format(
                    java.util.Locale.US, "%+d:%.2f %s", (joltNs - mStartNs) / 1000000L, peak,
                    what));
        }
        private float mNoiseSum, mPeak;
        private final StringBuilder mNotes = new StringBuilder();
        private int mNoteCount;
        private boolean mJolted;
        private boolean mDone;
        final boolean mFromRecording;
        private long mLastFedNs;
        private long mTap1Ns, mJudgedJoltNs;
        private float mTap1Peak;
        private boolean mJoltFinal;
        private int mBefore;
        private final long[] mPreNs = new long[RECORD_KEEP];
        private final float[] mPreHp = new float[RECORD_KEEP];
        private int mPreN;
        private float mFloor = Float.NaN, mSoftLimit;
        private boolean mFloorDone;
        private long mSoftLastNs, mSoftJoltNs, mSoftJudgedNs;
        private int mSoftN;
        private float mSoftPeak;
        private boolean mSoftFromQuiet;
        private boolean mSoftWake;

        MotionTap(SensorManager sm, Handler h, long startUptime, long windowUntil,
                  boolean fromRecording) {
            mSm = sm;
            mHandler = h;
            mStartUp = startUptime;
            mWindowUntil = windowUntil;
            mFromRecording = fromRecording;
            mStartNs = SystemClock.elapsedRealtimeNanos()
                    - (SystemClock.uptimeMillis() - startUptime) * 1000000L;
        }

        private boolean early(long joltNs) {
            return mFromRecording && joltNs <= mStartNs + TAP1_AFTER_MS * 1000000L;
        }

        private boolean earlyJoltIsNextTap(long joltNs, float peak) {
            if (joltNs < mStartNs - TAP1_BEFORE_MS * 1000000L) {
                noteJolt(joltNs, peak, "old");
                return false;
            }
            if (mTap1Ns == 0) {
                mTap1Ns = joltNs;
                mTap1Peak = peak;
                noteJolt(joltNs, peak, "tap1");
                return false;
            }
            if (joltNs - mTap1Ns < TAP_GAP_MS * 1000000L) {
                if (joltNs - mTap1Ns > 5000000L) noteJolt(joltNs, peak, "tap1-end");
                return false;
            }
            boolean big = peak >= Math.max(EARLY_TAP2_MIN, EARLY_TAP2_RATIO * mTap1Peak);
            noteJolt(joltNs, peak, big ? "tap2" : "small");
            return big;
        }

        private boolean lateJoltIsNextTap(long joltNs) {
            return true;
        }

        private boolean finishJolt(float limit) {
            mJoltFinal = true;
            if (!early(mJoltNs) || mJoltSamples < MOTION_JOLT_SAMPLES) return false;
            boolean standsOut = mNoiseN >= MOTION_NOISE_MIN_N
                    && mJoltPeak >= Math.max(Math.max(limit, MOTION_STANDOUT_MIN),
                            MOTION_STANDOUT_TIMES * mJoltPreMax);
            if (!mJoltFromQuiet && !standsOut) return false;
            if (!earlyJoltIsNextTap(mJoltNs, mJoltPeak)) return false;
            Module.log(String.format(java.util.Locale.US,
                    "double-tap-wake: next tap felt by the motion sensor %+d ms after tap 1, from"
                            + " the recording (peak %.2f, tap 1 %.2f at %+d, limit %.2f),"
                            + " checking proximity", (mJoltNs - mStartNs) / 1000000L, mJoltPeak,
                    mTap1Peak, (mTap1Ns - mStartNs) / 1000000L, limit));
            onJolt();
            return true;
        }

        private boolean regrows(float limit) {
            if (mJoltFromQuiet || mNoiseN < MOTION_NOISE_MIN_N
                    || mJoltSamples < MOTION_JOLT_SAMPLES) {
                return false;
            }
            if (mJoltDip > MOTION_REST_RATIO * limit) return false;
            if (mJoltPeak < Math.max(limit, MOTION_STANDOUT_MIN)) return false;
            if (mJoltPrevLobe < limit) return false;
            if (mJoltPeak < MOTION_REGROW_RATIO * mJoltPrevLobe) return false;
            long tap1 = mTap1Ns != 0 ? mTap1Ns : mStartNs - TAP1_GUESS_MS * 1000000L;
            return mJoltNs - tap1 >= TAP_GAP_MS * 1000000L;
        }

        void timeUp() {
            if (!mJolted) stopMotionTap("time up");
        }

        private void workOutFloor() {
            mFloorDone = true;
            long tap1 = mTap1Ns != 0 ? mTap1Ns : mStartNs - TAP1_GUESS_MS * 1000000L;
            long cut = tap1 - MOTION_FLOOR_GAP_MS * 1000000L;
            float[] rest = new float[mPreN];
            int n = 0;
            for (int i = 0; i < mPreN; i++) {
                if (mPreNs[i] < cut) rest[n++] = mPreHp[i];
            }
            if (n < MOTION_FLOOR_MIN_N) return;
            java.util.Arrays.sort(rest, 0, n);
            mFloor = (n % 2 == 1) ? rest[n / 2] : (rest[n / 2 - 1] + rest[n / 2]) / 2f;
            mSoftLimit = Math.max(MOTION_SOFT_MIN, MOTION_SOFT_TIMES * mFloor);
            for (int i = 0; i < mPreN; i++) {
                if (mPreHp[i] >= mSoftLimit) mSoftLastNs = mPreNs[i];
            }
        }

        private boolean softTap(long ts, float hp) {
            if (!mFloorDone) workOutFloor();
            if (Float.isNaN(mFloor) || mFloor > MOTION_SOFT_FLOOR_MAX) return false;
            if (hp < mSoftLimit) return false;
            boolean same = mSoftJoltNs != 0 && ts - mSoftLastNs <= MOTION_GAP_MS * 1000000L;
            if (!same) {
                mSoftFromQuiet = mSoftLastNs == 0
                        || ts - mSoftLastNs > MOTION_QUIET_MS * 1000000L;
                mSoftJoltNs = ts;
                mSoftN = 0;
                mSoftPeak = 0f;
            }
            mSoftLastNs = ts;
            mSoftN++;
            mSoftPeak = Math.max(mSoftPeak, hp);
            if (!mSoftFromQuiet || mSoftJudgedNs == mSoftJoltNs) return false;
            long from = (mSoftJoltNs - mStartNs) / 1000000L;
            if (from < 0 || from > MOTION_SOFT_UNTIL_MS) return false;
            long tap1 = mTap1Ns != 0 ? mTap1Ns : mStartNs - TAP1_GUESS_MS * 1000000L;
            if (mSoftJoltNs - tap1 < TAP_GAP_MS * 1000000L) return false;
            if (mSoftN < MOTION_SOFT_SAMPLES || mSoftPeak < MOTION_SOFT_PEAK_TIMES * mSoftLimit) {
                return false;
            }
            mSoftJudgedNs = mSoftJoltNs;
            mSoftWake = true;
            Module.log(String.format(java.util.Locale.US,
                    "double-tap-wake: next tap felt by the motion sensor %+d ms after tap 1 (soft"
                            + " tap on a still phone: jolt from %+d, peak %.2f, %d readings over"
                            + " %.3f, resting floor %.3f), checking proximity",
                    (ts - mStartNs) / 1000000L, from, mSoftPeak, mSoftN, mSoftLimit, mFloor));
            onJolt();
            return true;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (mDone) return;
            try {
                if (e.sensor != null && e.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                    if (mJolted) {
                        boolean near = e.values[0] < e.sensor.getMaximumRange();
                        decide(near ? "near" : "far");
                    }
                    return;
                }
                if (mFromRecording) return;
                feed(e.timestamp, e.values[0], e.values[1], e.values[2]);
            } catch (Throwable t) {
                Module.log("double-tap-wake: motion sensor reading failed", t);
                stopMotionTap("error");
            }
        }

        void feed(long ts, float x, float y, float z) {
            if (mDone || mJolted) return;
            try {
                if (sWindowUntil != mWindowUntil) {
                    stopMotionTap("touch window over");
                    return;
                }
                if (sTouchDownAt >= mStartUp) {
                    stopMotionTap("touchscreen back");
                    return;
                }
                if (ts <= mLastFedNs) return;
                mLastFedNs = ts;
                long earliest = mFromRecording ? mStartNs - MOTION_LOOKBACK_MS * 1000000L
                        : mStartNs;
                if (ts < earliest) {
                    mOld++;
                    return;
                }
                if (ts < mStartNs) mBefore++;
                float g = (float) Math.sqrt(x * x + y * y + z * z);
                if (Float.isNaN(g) || g < 2f || g > 40f) {
                    mBad++;
                    return;
                }
                mSamples++;
                if (mFirstNs == 0) {
                    mFirstNs = ts;
                    mPrevNs = ts;
                    mBase[0] = x;
                    mBase[1] = y;
                    mBase[2] = z;
                    return;
                }
                float dt = Math.max(0f, (ts - mPrevNs) / 1e6f);
                mPrevNs = ts;
                float hx = x - mBase[0], hy = y - mBase[1], hz = z - mBase[2];
                float hp = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                boolean settling = (ts - mFirstNs) / 1000000L < MOTION_SETTLE_MS;
                float a = Math.min(1f, dt / (settling ? MOTION_SETTLE_TAU_MS : MOTION_TAU_MS));
                mBase[0] += a * hx;
                mBase[1] += a * hy;
                mBase[2] += a * hz;
                float noise = mNoiseN == 0 ? 0f : mNoiseSum / mNoiseN;
                float limit = Math.max(MOTION_MIN, MOTION_NOISE_TIMES * noise);
                mHistNs[mHistN % MOTION_HISTORY] = ts;
                mHistHp[mHistN % MOTION_HISTORY] = hp;
                mHistN++;
                if (ts >= mStartNs - MOTION_TRACE_FROM_MS * 1000000L
                        && mTraceN < MOTION_TRACE_MAX) {
                    if (mTraceN == 0) mTraceFromNs = ts;
                    mTrace.append(mTraceN == 0 ? "" : ",").append(Math.round(hp * 100f));
                    mTraceN++;
                }
                if (!settling) {
                    long at = (ts - mStartNs) / 1000000L;
                    if (mJoltNs != 0 && !mJoltFinal
                            && ts - mLastOverNs > MOTION_GAP_MS * 1000000L) {
                        if (finishJolt(limit)) return;
                    }
                    if (ts < mStartNs) {
                        if (mPreN < RECORD_KEEP) {
                            mPreNs[mPreN] = ts;
                            mPreHp[mPreN] = hp;
                            mPreN++;
                        }
                    } else if (mFromRecording && softTap(ts, hp)) {
                        return;
                    }
                    if (hp > mPeak) {
                        mPeak = hp;
                        mPeakAt = at;
                    }
                    if (hp >= (mFromRecording ? Math.max(MOTION_NOTE, limit) : MOTION_NOTE)
                            && mNoteCount < MOTION_NOTES_MAX) {
                        mNoteCount++;
                        mNotes.append(mNotes.length() == 0 ? "" : " ")
                                .append(at >= 0 ? "+" : "").append(at).append(':')
                                .append(String.format(java.util.Locale.US, "%.2f", hp));
                    }
                    if (hp >= limit) {
                        boolean sameJolt = mJoltNs != 0
                                && ts - mLastOverNs <= MOTION_GAP_MS * 1000000L;
                        if (!sameJolt) {
                            if (mJoltNs != 0 && mJoltSamples < MOTION_JOLT_SAMPLES) mBlips++;
                            mJolts++;
                            mJoltNs = ts;
                            mJoltSamples = 0;
                            mJoltPeak = 0f;
                            mJoltFinal = false;
                            mJoltPreMax = 0f;
                            mJoltDip = Float.MAX_VALUE;
                            mJoltPrevLobe = 0f;
                            int dipK = -1;
                            int k0 = Math.max(0, mHistN - MOTION_HISTORY);
                            for (int k = k0; k < mHistN - 1; k++) {
                                int i = k % MOTION_HISTORY;
                                if (mHistNs[i] >= ts - MOTION_QUIET_MS * 1000000L) {
                                    mJoltPreMax = Math.max(mJoltPreMax, mHistHp[i]);
                                    if (mHistHp[i] < mJoltDip) {
                                        mJoltDip = mHistHp[i];
                                        dipK = k;
                                    }
                                }
                            }
                            if (dipK >= 0) {
                                long dipNs = mHistNs[dipK % MOTION_HISTORY];
                                for (int k = dipK - 1; k >= k0; k--) {
                                    int i = k % MOTION_HISTORY;
                                    if (mHistNs[i] < dipNs - MOTION_LOBE_MS * 1000000L) break;
                                    float hk = mHistHp[i];
                                    mJoltPrevLobe = Math.max(mJoltPrevLobe, hk);
                                    if (mJoltPrevLobe >= limit
                                            && hk < MOTION_LOBE_FALL * mJoltPrevLobe) {
                                        break;
                                    }
                                }
                            }
                            mJoltFromQuiet = mNoiseN >= MOTION_NOISE_MIN_N && (mLastOverNs == 0
                                    || ts - mLastOverNs > MOTION_QUIET_MS * 1000000L);
                            if (!mJoltFromQuiet) mNotQuiet++;
                        }
                        mLastOverNs = ts;
                        mJoltSamples++;
                        mJoltPeak = Math.max(mJoltPeak, hp);
                        if (mJoltFromQuiet && mJoltSamples >= MOTION_JOLT_SAMPLES
                                && mJudgedJoltNs != mJoltNs && !early(mJoltNs)) {
                            mJudgedJoltNs = mJoltNs;
                            if (lateJoltIsNextTap(mJoltNs)) {
                                Module.log(String.format(java.util.Locale.US,
                                        "double-tap-wake: next tap felt by the motion sensor %+d"
                                                + " ms after tap 1 (jolt from %+d, peak %.2f"
                                                + " m/s^2, limit %.2f), checking proximity", at,
                                        (mJoltNs - mStartNs) / 1000000L, mJoltPeak, limit));
                                onJolt();
                                return;
                            }
                        }
                        if (!mJoltFromQuiet && !early(mJoltNs) && mJudgedJoltNs != mJoltNs
                                && mJoltSamples >= MOTION_JOLT_SAMPLES
                                && mNoiseN >= MOTION_NOISE_MIN_N
                                && mJoltPeak >= Math.max(Math.max(limit, MOTION_STANDOUT_MIN),
                                        MOTION_STANDOUT_TIMES * mJoltPreMax)) {
                            mJudgedJoltNs = mJoltNs;
                            Module.log(String.format(java.util.Locale.US,
                                    "double-tap-wake: next tap felt by the motion sensor %+d ms"
                                            + " after tap 1 (jolt from %+d standing out: peak"
                                            + " %.2f, %.2f in the 40 ms before, limit %.2f),"
                                            + " checking proximity", at,
                                    (mJoltNs - mStartNs) / 1000000L, mJoltPeak, mJoltPreMax,
                                    limit));
                            onJolt();
                            return;
                        }
                        if (!mJoltFromQuiet && !early(mJoltNs) && mJudgedJoltNs != mJoltNs
                                && regrows(limit)) {
                            mJudgedJoltNs = mJoltNs;
                            Module.log(String.format(java.util.Locale.US,
                                    "double-tap-wake: next tap felt by the motion sensor %+d ms"
                                            + " after tap 1 (jolt from %+d growing back: peak"
                                            + " %.2f after a dip to %.2f, bump before %.2f,"
                                            + " limit %.2f), checking proximity", at,
                                    (mJoltNs - mStartNs) / 1000000L, mJoltPeak, mJoltDip,
                                    mJoltPrevLobe, limit));
                            onJolt();
                            return;
                        }
                    }
                    if (sharpPeak(limit)) return;
                    if (hp >= limit) return;
                } else if (hp >= MOTION_MIN) {
                    mLastOverNs = ts;
                }
                mNoiseSum += Math.min(hp, MOTION_MIN);
                mNoiseN++;
            } catch (Throwable t) {
                Module.log("double-tap-wake: motion sensor reading failed", t);
                stopMotionTap("error");
            }
        }

        private boolean sharpPeak(float limit) {
            int n = mHistN;
            if (n < 3) return false;
            long ts = mHistNs[(n - 1) % MOTION_HISTORY];
            float hp = mHistHp[(n - 1) % MOTION_HISTORY];
            if (mSharpNs != 0) {
                if (hp > mSharpPeak) {
                    mSharpNs = 0;
                } else if (hp <= mSharpPeak / MOTION_SHARP_RATIO) {
                    long tp = mSharpNs;
                    mSharpNs = 0;
                    boolean next = early(tp) ? earlyJoltIsNextTap(tp, mSharpPeak)
                            : lateJoltIsNextTap(tp);
                    if (!next) return false;
                    Module.log(String.format(java.util.Locale.US,
                            "double-tap-wake: next tap felt by the motion sensor %+d ms after"
                                    + " tap 1 (sharp jolt on a moving phone: %.2f, at most %.2f"
                                    + " before, %.2f %d ms after), checking proximity",
                            (tp - mStartNs) / 1000000L, mSharpPeak, mSharpFrom, hp,
                            (ts - tp) / 1000000L));
                    onJolt();
                    return true;
                } else if (ts - mSharpNs > MOTION_FALL_MS * 1000000L) {
                    mSharpNs = 0;
                    return false;
                } else {
                    return false;
                }
            }
            float p = mHistHp[(n - 2) % MOTION_HISTORY];
            if (p < mHistHp[(n - 3) % MOTION_HISTORY] || p <= hp) return false;
            if (p < Math.max(limit, MOTION_SHARP_MIN)) return false;
            long tp = mHistNs[(n - 2) % MOTION_HISTORY];
            if (tp - mStartNs > MOTION_SHARP_UNTIL_MS * 1000000L) return false;
            long from = tp - MOTION_RISE_FROM_MS * 1000000L, to = tp - MOTION_RISE_TO_MS * 1000000L;
            float high = -1f;
            for (int k = Math.max(0, n - MOTION_HISTORY); k < n - 2; k++) {
                long t = mHistNs[k % MOTION_HISTORY];
                if (t >= from && t <= to) high = Math.max(high, mHistHp[k % MOTION_HISTORY]);
            }
            if (high < 0f || high > p / MOTION_SHARP_RATIO) return false;
            mSharpNs = tp;
            mSharpPeak = p;
            mSharpFrom = high;
            if (hp <= p / MOTION_SHARP_RATIO) return sharpPeak(limit);
            return false;
        }

        private void onJolt() {
            mJolted = true;
            dropLiveKick();
            try {
                mSm.unregisterListener(this);
            } catch (Throwable ignored) { }
            Sensor prox = null;
            try {
                prox = mSm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                if (prox != null && !mSm.registerListener(this, prox,
                        SensorManager.SENSOR_DELAY_FASTEST, 0, mHandler)) {
                    prox = null;
                }
            } catch (Throwable t) {
                prox = null;
            }
            if (prox == null) {
                decide("no sensor");
                return;
            }
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!mDone) decide("no reading");
                }
            }, MOTION_PROX_WAIT_MS);
        }

        private void decide(String proximity) {
            if (mDone) return;
            if (sMotion == this) sMotion = null;
            stop("tap felt, proximity " + proximity);
            if ("near".equals(proximity)) {
                Module.log("double-tap-wake: screen covered, motion tap ignored");
                return;
            }
            if (sPm == null || sPm.isInteractive() || sWindowUntil != mWindowUntil) return;
            wakeForMotionTap();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        void stop(String why) {
            if (mDone) return;
            mDone = true;
            dropLiveKick();
            try {
                mSm.unregisterListener(this);
            } catch (Throwable ignored) { }
            long firstAfter = mFirstNs == 0 ? -1 : (mFirstNs - mStartNs) / 1000000L;
            long span = mFirstNs == 0 ? 0 : (mPrevNs - mFirstNs) / 1000000L;
            float noise = mNoiseN == 0 ? 0f : mNoiseSum / mNoiseN;
            if (mJoltNs != 0 && !mJolted && mJoltSamples < MOTION_JOLT_SAMPLES) mBlips++;
            Module.log(String.format(java.util.Locale.US,
                    "double-tap-wake: motion sensor off (%s): %d samples in %d ms (%s, %d from"
                            + " before tap 1, tap 1 %s, jolts {%s}), on in %d ms, first %+d ms"
                            + " after tap 1,"
                            + " peak %.2f at %+d, noise %.3f, limit %.2f, resting floor %s,"
                            + " jolts %d (blips %d, not after quiet %d), old %d, bad %d [%s]",
                    why, mSamples, span, mFromRecording ? "recorded" : "live", mBefore,
                    mTap1Ns == 0 ? "not felt" : String.format(java.util.Locale.US, "felt at %+d",
                            (mTap1Ns - mStartNs) / 1000000L), mJoltLog,
                    mStartTook, firstAfter, mPeak, mPeakAt, noise,
                    Math.max(MOTION_MIN, MOTION_NOISE_TIMES * noise),
                    Float.isNaN(mFloor) ? "n/a" : String.format(java.util.Locale.US,
                            "%.3f (soft limit %.3f%s)", mFloor, mSoftLimit,
                            mFloor > MOTION_SOFT_FLOOR_MAX ? ", moving: off" : ""),
                    mJolts, mBlips, mNotQuiet, mOld, mBad, mNotes));
            if ((!mJolted || mSoftWake) && mTraceN > 0) {
                Module.log(String.format(java.util.Locale.US,
                        "double-tap-wake: motion trace from %+d ms (x100, %d readings): %s",
                        (mTraceFromNs - mStartNs) / 1000000L, mTraceN, mTrace));
            }
            if (shaking(noise, mNoiseN)) onMovingTap(noise);
        }
    }

    private static final float SHAKING_NOISE = 0.084f;
    private static final int SHAKING_MIN_SAMPLES = 10;
    private static final long SHAKING_MS = 5L * 60L * 1000L;
    private static final long SHAKING_PROBE_MS = 400L;
    private static final long SHAKING_WATCH_MS = 15L * 60L * 1000L;
    private static final long SHAKING_RECHECK_MS = 10L * 1000L;
    private static final long SHAKING_AFTER_OFF_MS = 6L * 1000L;
    private static volatile long sShakingUntil;
    private static long sMovingSeenAt;
    private static ShakeProbe sProbe;

    private static boolean shakingOn() {
        long u = sShakingUntil;
        return u != 0 && SystemClock.uptimeMillis() < u;
    }

    private abstract static class ProbeDone {
        abstract void done(float noise, int samples);
    }

    private static boolean runProbe(final ProbeDone done) {
        Handler h = sWorker;
        Context ctx = sCtx;
        if (h == null || ctx == null) return false;
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) return false;
        holdAwake(SHAKING_PROBE_MS + 1000L);
        final ShakeProbe probe = new ShakeProbe(sm);
        if (sProbe != null) sProbe.cancel();
        sProbe = null;
        if (!sm.registerListener(probe, acc, SensorManager.SENSOR_DELAY_FASTEST, 0, h)) {
            return false;
        }
        sProbe = probe;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sProbe != probe) return;
                    sProbe = null;
                    float noise = probe.finish();
                    done.done(noise, probe.samples());
                } catch (Throwable t) {
                    Module.log("double-tap-wake: motion listen failed", t);
                    endShaking("error");
                }
            }
        }, SHAKING_PROBE_MS);
        return true;
    }

    private static boolean shaking(float noise, int samples) {
        return samples >= SHAKING_MIN_SAMPLES && noise >= SHAKING_NOISE;
    }

    private static void onMovingTap(float noise) {
        try {
            Handler h = sWorker;
            if (h == null || !sWakeOn || shakingOn()) return;
            final long at = SystemClock.uptimeMillis();
            sMovingSeenAt = at;
            Module.log(String.format(java.util.Locale.US,
                    "double-tap-wake: phone moving at the tap (noise %.3f), checking whether it"
                            + " keeps shaking", noise));
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sMovingSeenAt == at) confirmShaking("10 s after the tap");
                }
            }, SHAKING_RECHECK_MS);
        } catch (Throwable t) {
            Module.log("double-tap-wake: shaking watch failed", t);
        }
    }

    private static void onScreenOffForShaking() {
        try {
            Handler h = sWorker;
            if (h == null) return;
            if (shakingOn()) {
                final long until = sShakingUntil;
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (sShakingUntil == until && shakingOn()) shakingCheck();
                    }
                }, SHAKING_AFTER_OFF_MS);
                return;
            }
            final long at = sMovingSeenAt;
            if (at == 0) return;
            if (SystemClock.uptimeMillis() - at > SHAKING_WATCH_MS) {
                sMovingSeenAt = 0;
                return;
            }
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sMovingSeenAt == at) confirmShaking("after locking");
                }
            }, SHAKING_AFTER_OFF_MS);
        } catch (Throwable t) {
            Module.log("double-tap-wake: shaking watch failed", t);
        }
    }

    private static void confirmShaking(final String when) {
        try {
            if (!sWakeOn || sPm == null || shakingOn()) return;
            if (sPm.isInteractive() || !aodDozing(sAodArgs)) return;
            runProbe(new ProbeDone() {
                @Override
                void done(float noise, int samples) {
                    if (shakingOn()) return;
                    sMovingSeenAt = 0;
                    if (shaking(noise, samples)) {
                        startShaking(noise, when);
                    } else {
                        Module.log(String.format(java.util.Locale.US,
                                "double-tap-wake: phone not shaking %s (noise %.3f), always-on"
                                        + " display stays low-power", when, noise));
                    }
                }
            });
        } catch (Throwable t) {
            Module.log("double-tap-wake: shaking check failed", t);
        }
    }

    private static void startShaking(float noise, String when) {
        extendShaking(SHAKING_MS);
        Module.log(String.format(java.util.Locale.US,
                "double-tap-wake: phone shaking %s (noise %.3f), touchscreen kept on for the"
                        + " always-on display, checking again in %d min", when, noise,
                SHAKING_MS / 60000L));
        reapplyAodNow();
        updateRecording("phone shaking");
    }

    private static void extendShaking(long ms) {
        Handler h = sWorker;
        if (h == null) return;
        final long until = SystemClock.uptimeMillis() + ms;
        sShakingUntil = until;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sShakingUntil == until) shakingCheck();
            }
        }, ms);
    }

    private static void shakingCheck() {
        try {
            if (!sWakeOn || sPm == null) {
                endShaking("double tap to wake off");
                return;
            }
            if (sPm.isInteractive()) {
                extendShaking(SHAKING_MS);
                return;
            }
            if (!aodDozing(sAodArgs)) {
                endShaking("always-on display off");
                return;
            }
            sShakingUntil = SystemClock.uptimeMillis() + SHAKING_PROBE_MS + 1000L;
            reapplyAodNow();
            boolean started = runProbe(new ProbeDone() {
                @Override
                void done(float noise, int samples) {
                    if (shaking(noise, samples)) {
                        extendShaking(SHAKING_MS);
                        reapplyAodNow();
                        Module.log(String.format(java.util.Locale.US,
                                "double-tap-wake: phone still shaking (noise %.3f), touchscreen"
                                        + " stays on %d more min", noise, SHAKING_MS / 60000L));
                    } else {
                        endShaking(String.format(java.util.Locale.US, "phone still (noise %.3f,"
                                + " %d samples)", noise, samples));
                    }
                }
            });
            if (!started) endShaking("motion sensor unavailable");
        } catch (Throwable t) {
            Module.log("double-tap-wake: shaking check failed", t);
            endShaking("error");
        }
    }

    private static void endShaking(String why) {
        if (sProbe != null) {
            sProbe.cancel();
            sProbe = null;
        }
        if (sShakingUntil == 0) return;
        sShakingUntil = 0;
        Module.log("double-tap-wake: shaking mode over (" + why + ")");
        reapplyAodNow();
        updateRecording("shaking over");
    }

    private static final class ShakeProbe implements SensorEventListener {
        private final SensorManager mSm;
        private final long mStartNs = SystemClock.elapsedRealtimeNanos();
        private final float[] mBase = new float[3];
        private long mFirstNs, mPrevNs;
        private int mN;
        private float mSum;
        private boolean mDone;

        ShakeProbe(SensorManager sm) {
            mSm = sm;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (mDone) return;
            try {
                float x = e.values[0], y = e.values[1], z = e.values[2];
                long ts = e.timestamp;
                if (ts < mStartNs) return;
                float g = (float) Math.sqrt(x * x + y * y + z * z);
                if (Float.isNaN(g) || g < 2f || g > 40f) return;
                if (mFirstNs == 0) {
                    mFirstNs = ts;
                    mPrevNs = ts;
                    mBase[0] = x;
                    mBase[1] = y;
                    mBase[2] = z;
                    return;
                }
                float dt = Math.max(0f, (ts - mPrevNs) / 1e6f);
                mPrevNs = ts;
                float hx = x - mBase[0], hy = y - mBase[1], hz = z - mBase[2];
                float hp = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                boolean settling = (ts - mFirstNs) / 1000000L < MOTION_SETTLE_MS;
                float a = Math.min(1f, dt / (settling ? MOTION_SETTLE_TAU_MS : MOTION_TAU_MS));
                mBase[0] += a * hx;
                mBase[1] += a * hy;
                mBase[2] += a * hz;
                if (settling || Float.isNaN(hp)) return;
                mSum += Math.min(hp, MOTION_MIN);
                mN++;
            } catch (Throwable t) {
                cancel();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        int samples() {
            return mN;
        }

        float finish() {
            cancel();
            return mN == 0 ? 0f : mSum / mN;
        }

        void cancel() {
            if (mDone) return;
            mDone = true;
            try {
                mSm.unregisterListener(this);
            } catch (Throwable ignored) { }
        }
    }

    private static final long HELD_SETTLE_MS = 250L;
    private static final long HELD_WINDOW_MS = 1500L;
    private static final long HELD_MARGIN_MS = 750L;
    private static final long HELD_EPISODE_MS = 90L * 1000L;
    private static final long HELD_BUDGET_MS = 4L * 60L * 1000L;
    private static final String AFTER_A_TAP = "after a tap";

    private static boolean budgetApplies(String trigger) {
        return !AFTER_A_TAP.equals(trigger);
    }
    private static final float STILL_NOISE = 0.026f, STILL_TILT_DEG = 0.12f;
    private static final float HELD_GRAVITY_TAU_MS = 100f;
    private static final int HELD_MIN_SAMPLES = 100;
    private static final int HELD_PERIOD_US = 2500;

    private static volatile long sHeldUntil;
    private static volatile HeldWatch sHeldWatch;
    private static volatile long sHeldSpentMs;
    private static volatile boolean sHeldBudgetLogged;
    private static volatile Thread sReapplyThread;

    private static boolean heldOn() {
        long u = sHeldUntil;
        return u != 0 && SystemClock.uptimeMillis() < u;
    }

    private static void onAodRequest(Object[] prev, Object[] args, boolean doze, int state) {
        if (!doze) {
            if (sHeldUntil != 0 || sHeldWatch != null) postEndHeld("always-on display state " + state);
            postDisarmPickup();
            return;
        }
        if (!aodDozing(prev)) {
            judgeHeld("always-on display going low-power", true);
        } else if (!sameRequest(prev, args)) {
            judgeHeld("always-on display brightness changed", false);
        }
    }

    private static final long JUDGE_MAX_MS = 400L;
    private static final int JUDGE_MIN_READINGS = 400;
    private static volatile long sJudgeUntil;
    private static volatile Judge sJudge;

    private static final class Judge {
        final String trigger;
        final boolean keptTouch;
        final Recorder rec;

        Judge(String trigger, boolean keptTouch, Recorder rec) {
            this.trigger = trigger;
            this.keptTouch = keptTouch;
            this.rec = rec;
        }
    }

    private static boolean judgingOn() {
        long u = sJudgeUntil;
        return u != 0 && SystemClock.uptimeMillis() < u;
    }

    private static boolean judgeHeld(final String trigger, final boolean keepTouch) {
        final Handler h = sWorker;
        if (h == null || !sWakeOn || sCharging || shakingOn()) return false;
        if (heldOn()) return true;
        final Recorder rec = sRecorder;
        if (rec == null || (budgetApplies(trigger) && sHeldSpentMs >= HELD_BUDGET_MS)) {
            return startHeldCheck(trigger);
        }
        if (keepTouch) sJudgeUntil = SystemClock.uptimeMillis() + JUDGE_MAX_MS + 200L;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final Judge j = new Judge(trigger, keepTouch, rec);
                    sJudge = j;
                    sensorThread().post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (sJudge != j) return;
                                Context ctx = sCtx;
                                SensorManager sm = ctx == null ? null
                                        : (SensorManager) ctx.getSystemService(
                                                Context.SENSOR_SERVICE);
                                if (sm == null || !sm.flush(rec)) postJudgeFallback(j, "no flush");
                            } catch (Throwable t) {
                                postJudgeFallback(j, "flush failed");
                            }
                        }
                    });
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (sJudge == j) judgeFallback(j, "no flush in time");
                        }
                    }, JUDGE_MAX_MS);
                } catch (Throwable t) {
                    Module.log("double-tap-wake: hand-held judgement failed", t);
                    sJudgeUntil = 0;
                    startHeldCheck(trigger);
                }
            }
        });
        return keepTouch;
    }

    private static void postJudgeFallback(final Judge j, final String why) {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (sJudge == j) judgeFallback(j, why);
            }
        });
    }

    private static void judgeFallback(Judge j, String why) {
        sJudge = null;
        boolean live = startHeldCheck(j.trigger);
        sJudgeUntil = 0;
        Module.log("double-tap-wake: hand-held judgement from the recording not possible ("
                + why + "), " + (live ? "checking live" : "low-power"));
        reapplyAodNow();
    }

    private static void onRecordingFlushed(Recorder rec) {
        Judge j = sJudge;
        if (j == null || j.rec != rec) return;
        sJudge = null;
        if (sPm == null || sPm.isInteractive() || !sWakeOn || sCharging || shakingOn()) {
            sJudgeUntil = 0;
            reapplyAodNow();
            return;
        }
        float[] r = rec.stillness(HELD_SETTLE_MS + HELD_WINDOW_MS);
        if (r == null || r[2] < JUDGE_MIN_READINGS) {
            judgeFallback(j, r == null ? "no readings" : "too few readings: " + (int) r[2]);
            return;
        }
        String m = String.format(java.util.Locale.US, "noise %.3f, tilt %.2f deg, %d readings",
                r[0], r[1], (int) r[2]);
        boolean still = r[0] < STILL_NOISE && r[1] < STILL_TILT_DEG;
        if (still) {
            sJudgeUntil = 0;
            Module.log("double-tap-wake: phone lying still (" + m + ", from the recording; "
                    + j.trigger + ")" + (j.keptTouch ? ", always-on display low-power" : ""));
            reapplyAodNow();
            return;
        }
        boolean live = startHeldCheck(j.trigger);
        sJudgeUntil = 0;
        Module.log("double-tap-wake: phone moving (" + m + ", from the recording; " + j.trigger
                + ")" + (live ? ", touchscreen on while checking" : ""));
        reapplyAodNow();
    }

    private static boolean sameRequest(Object[] a, Object[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 1; i < a.length; i++) {
            Object x = a[i], y = b[i];
            if (x == null ? y != null : !x.equals(y)) return false;
        }
        return true;
    }

    private static boolean startHeldCheck(final String trigger) {
        final Handler h = sWorker;
        if (h == null || !sWakeOn || sCharging || shakingOn()) return false;
        if (heldOn()) return true;
        if (budgetApplies(trigger) && sHeldSpentMs >= HELD_BUDGET_MS) {
            if (!sHeldBudgetLogged) {
                sHeldBudgetLogged = true;
                Module.log("double-tap-wake: hand-held time used up until the next screen-off ("
                        + HELD_BUDGET_MS / 60000L + " min), always-on display stays low-power");
            }
            return false;
        }
        final long until = SystemClock.uptimeMillis() + HELD_SETTLE_MS + HELD_WINDOW_MS
                + HELD_MARGIN_MS;
        sHeldUntil = until;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sHeldUntil != until) return;
                    startHeldWatch(trigger, until);
                } catch (Throwable t) {
                    Module.log("double-tap-wake: hand-held check failed", t);
                    endHeld("error", true);
                }
            }
        });
        return true;
    }

    private static void startHeldWatch(String trigger, long until) {
        HeldWatch old = sHeldWatch;
        if (old != null) old.cancel();
        sHeldWatch = null;
        Context ctx = sCtx;
        SensorManager sm = ctx == null ? null
                : (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) {
            endHeld("no motion sensor", true);
            return;
        }
        HeldWatch w = new HeldWatch(sm, trigger);
        if (!sm.registerListener(w, acc, HELD_PERIOD_US, 0, sWorker)) {
            endHeld("motion sensor unavailable", true);
            return;
        }
        sHeldWatch = w;
        holdAwake(until - SystemClock.uptimeMillis() + 500L);
        updateRecording("hand-held check");
        watchHeldUntil(until);
    }

    private static void watchHeldUntil(final long until) {
        Handler h = sWorker;
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sHeldUntil == until) endHeld("no motion readings", true);
            }
        }, Math.max(0L, until - SystemClock.uptimeMillis()) + 50L);
    }

    private static void postEndHeld(final String why) {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                endHeld(why, false);
            }
        });
    }

    private static void endHeld(String why, boolean reapply) {
        HeldWatch w = sHeldWatch;
        sHeldWatch = null;
        boolean was = sHeldUntil != 0 || sJudgeUntil != 0;
        sHeldUntil = 0;
        sJudge = null;
        sJudgeUntil = 0;
        if (w != null) {
            w.cancel();
            sHeldSpentMs += SystemClock.uptimeMillis() - w.mStartUp;
            if (w.mHeldSeen) {
                Module.log("double-tap-wake: hand-held mode over (" + why + ", "
                        + (SystemClock.uptimeMillis() - w.mStartUp) / 1000L + " s)");
            } else if (!w.mDecided) {
                Module.log("double-tap-wake: hand-held check stopped (" + why + ")");
            }
        } else if (was && !"screen on".equals(why)) {
            Module.log("double-tap-wake: hand-held check stopped (" + why + ")");
        }
        if (reapply && was) {
            reapplyAodNow();
            updateRecording("hand-held over");
        }
    }

    private static final class HeldWatch implements SensorEventListener {
        private final SensorManager mSm;
        private final String mTrigger;
        final long mStartUp = SystemClock.uptimeMillis();
        private final long mStartNs = SystemClock.elapsedRealtimeNanos();
        private final float[] mBase = new float[3], mGrav = new float[3], mRef = new float[3];
        private long mFirstNs, mPrevNs, mWinNs;
        private int mN;
        private float mSum, mMaxTilt;
        volatile boolean mHeldSeen;
        boolean mDecided;
        private boolean mDone;

        HeldWatch(SensorManager sm, String trigger) {
            mSm = sm;
            mTrigger = trigger;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (mDone || sHeldWatch != this) return;
            try {
                float x = e.values[0], y = e.values[1], z = e.values[2];
                long ts = e.timestamp;
                if (ts < mStartNs) return;
                float g = (float) Math.sqrt(x * x + y * y + z * z);
                if (Float.isNaN(g) || g < 2f || g > 40f) return;
                if (mFirstNs == 0) {
                    mFirstNs = ts;
                    mPrevNs = ts;
                    mBase[0] = mGrav[0] = x;
                    mBase[1] = mGrav[1] = y;
                    mBase[2] = mGrav[2] = z;
                    return;
                }
                float dt = Math.max(0f, (ts - mPrevNs) / 1e6f);
                mPrevNs = ts;
                float hx = x - mBase[0], hy = y - mBase[1], hz = z - mBase[2];
                float hp = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                boolean settling = (ts - mFirstNs) / 1000000L < MOTION_SETTLE_MS;
                float a = Math.min(1f, dt / (settling ? MOTION_SETTLE_TAU_MS : MOTION_TAU_MS));
                mBase[0] += a * hx;
                mBase[1] += a * hy;
                mBase[2] += a * hz;
                float b = Math.min(1f, dt / HELD_GRAVITY_TAU_MS);
                mGrav[0] += b * (x - mGrav[0]);
                mGrav[1] += b * (y - mGrav[1]);
                mGrav[2] += b * (z - mGrav[2]);
                if ((ts - mFirstNs) / 1000000L < HELD_SETTLE_MS || Float.isNaN(hp)) return;
                if (mWinNs == 0) {
                    mWinNs = ts;
                    System.arraycopy(mGrav, 0, mRef, 0, 3);
                    mN = 0;
                    mSum = 0f;
                    mMaxTilt = 0f;
                }
                mSum += Math.min(hp, MOTION_MIN);
                mN++;
                mMaxTilt = Math.max(mMaxTilt, angleDeg(mGrav, mRef));
                if ((ts - mWinNs) / 1000000L >= HELD_WINDOW_MS) {
                    float noise = mN == 0 ? 0f : mSum / mN;
                    windowDone(noise, mMaxTilt, mN);
                    mWinNs = 0;
                }
            } catch (Throwable t) {
                Module.log("double-tap-wake: hand-held reading failed", t);
                endHeld("error", true);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        private void windowDone(float noise, float tilt, int n) {
            String m = String.format(java.util.Locale.US, "noise %.3f, tilt %.2f deg, %d readings",
                    noise, tilt, n);
            if (!sWakeOn || sCharging || shakingOn()) {
                endHeld(!sWakeOn ? "double tap to wake off" : "touchscreen on anyway", true);
                return;
            }
            if (n < HELD_MIN_SAMPLES) {
                endHeld("too few motion readings, " + m, true);
                return;
            }
            boolean still = noise < STILL_NOISE && tilt < STILL_TILT_DEG;
            long now = SystemClock.uptimeMillis();
            if (still) {
                mDecided = true;
                if (!mHeldSeen) {
                    Module.log("double-tap-wake: phone lying still (" + m + "; " + mTrigger
                            + "), always-on display low-power");
                }
                endHeld("phone put down: " + m, true);
                return;
            }
            if (!mHeldSeen) {
                mHeldSeen = true;
                mDecided = true;
                Module.log("double-tap-wake: phone in the hand (" + m + "; " + mTrigger
                        + "), touchscreen stays on for the always-on display");
            }
            long spent = now - mStartUp;
            if (spent >= HELD_EPISODE_MS || (budgetApplies(mTrigger)
                    && sHeldSpentMs + spent >= HELD_BUDGET_MS)) {
                endHeld("time up, " + m, true);
                return;
            }
            long until = now + HELD_WINDOW_MS + HELD_MARGIN_MS;
            sHeldUntil = until;
            holdAwake(HELD_WINDOW_MS + HELD_MARGIN_MS + 500L);
            watchHeldUntil(until);
        }

        void cancel() {
            if (mDone) return;
            mDone = true;
            try {
                mSm.unregisterListener(this);
            } catch (Throwable ignored) { }
        }
    }

    private static float angleDeg(float[] a, float[] b) {
        float cx = a[1] * b[2] - a[2] * b[1];
        float cy = a[2] * b[0] - a[0] * b[2];
        float cz = a[0] * b[1] - a[1] * b[0];
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return (float) Math.toDegrees(Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), dot));
    }

    private static final int TYPE_PICK_UP_GESTURE = 25;
    private static Sensor sPickupSensor;
    private static boolean sPickupLooked, sPickupArmed;
    private static android.hardware.TriggerEventListener sPickupTrigger;
    private static SensorEventListener sPickupListener;

    private static void armPickupLater() {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    armPickup();
                } catch (Throwable t) {
                    Module.log("double-tap-wake: pickup sensor failed", t);
                }
            }
        });
    }

    private static void postDisarmPickup() {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                disarmPickup();
            }
        });
    }

    private static void armPickup() {
        if (sPickupArmed || !sWakeOn || sCtx == null) return;
        if (sPm == null || sPm.isInteractive() || !aodDozing(sAodArgs)) return;
        SensorManager sm = (SensorManager) sCtx.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        Sensor s = pickupSensor(sm);
        if (s == null) return;
        if (s.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            if (sPickupTrigger == null) {
                sPickupTrigger = new android.hardware.TriggerEventListener() {
                    @Override
                    public void onTrigger(android.hardware.TriggerEvent event) {
                        postPickup();
                    }
                };
            }
            sPickupArmed = sm.requestTriggerSensor(sPickupTrigger, s);
        } else {
            if (sPickupListener == null) {
                sPickupListener = new SensorEventListener() {
                    @Override
                    public void onSensorChanged(SensorEvent event) {
                        postPickup();
                    }

                    @Override
                    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
                };
            }
            sPickupArmed = sm.registerListener(sPickupListener, s,
                    SensorManager.SENSOR_DELAY_NORMAL, sWorker);
        }
    }

    private static void disarmPickup() {
        if (!sPickupArmed) return;
        sPickupArmed = false;
        try {
            SensorManager sm = (SensorManager) sCtx.getSystemService(Context.SENSOR_SERVICE);
            if (sPickupTrigger != null) sm.cancelTriggerSensor(sPickupTrigger, sPickupSensor);
            if (sPickupListener != null) sm.unregisterListener(sPickupListener);
        } catch (Throwable ignored) { }
    }

    private static void postPickup() {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    onPickup();
                } catch (Throwable t) {
                    Module.log("double-tap-wake: pickup handling failed", t);
                }
            }
        });
    }

    private static void onPickup() {
        boolean oneShot = sPickupSensor != null
                && sPickupSensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT;
        if (oneShot) {
            sPickupArmed = false;
        } else {
            disarmPickup();
        }
        if (!sWakeOn || sPm == null || sPm.isInteractive() || !aodDozing(sAodArgs)) return;
        if (aodTouchWanted()) return;
        if (startHeldCheck("phone picked up")) {
            Module.log("double-tap-wake: phone picked up, touchscreen on for the always-on"
                    + " display while checking");
            reapplyAodNow();
        }
    }

    private static Sensor pickupSensor(SensorManager sm) {
        if (sPickupLooked) return sPickupSensor;
        sPickupLooked = true;
        StringBuilder wake = new StringBuilder();
        try {
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
                if (!s.isWakeUpSensor()) continue;
                String st = s.getStringType();
                boolean pickup = s.getType() == TYPE_PICK_UP_GESTURE
                        || (st != null && st.toLowerCase(java.util.Locale.US).contains("pick"));
                if (pickup && sPickupSensor == null) sPickupSensor = s;
                if (wake.length() < 600) {
                    wake.append(wake.length() == 0 ? "" : ", ").append(s.getName()).append(" (")
                            .append(s.getType()).append(')');
                }
            }
        } catch (Throwable t) {
            Module.log("double-tap-wake: sensor list unavailable", t);
        }
        Sensor p = sPickupSensor;
        Module.log(p != null
                ? "double-tap-wake: pickup sensor: " + p.getName() + " (" + p.getStringType()
                        + ", mode " + p.getReportingMode() + ")"
                : "double-tap-wake: no pickup sensor; wake-up sensors: " + wake);
        return p;
    }

    private static void wakeForMotionTap() {
        try {
            PowerManager.class.getMethod("wakeUp", long.class, int.class, String.class)
                    .invoke(sPm, SystemClock.uptimeMillis(), sTapReason, MOTION_DETAILS);
        } catch (Throwable t) {
            Module.log("double-tap-wake: motion tap wake failed", t);
        }
    }

    private static final class WindowClose implements Runnable {
        private final long mStart, mUntil;
        private final int mWaits;

        WindowClose(long start, long until) {
            this(start, until, 0);
        }

        WindowClose(long start, long until, int waits) {
            mStart = start;
            mUntil = until;
            mWaits = waits;
        }

        @Override
        public void run() {
            try {
                if (sPm.isInteractive()) {
                    if (sWindowUntil == mUntil) sWindowUntil = 0;
                    return;
                }
                long down = sTouchDownAt, up = sTouchUpAt, cancel = sTouchCancelAt;
                boolean fingerDown = down >= mStart && up == 0 && cancel < down;
                if (fingerDown && mWaits < WINDOW_HOLD_STEPS && sWindowUntil == mUntil) {
                    long more = SystemClock.uptimeMillis() + WINDOW_HOLD_STEP_MS;
                    sWindowUntil = more;
                    sPairUntil = more + WINDOW_PAIR_GRACE_MS;
                    reapplyAodNow();
                    Handler h = sWorker;
                    if (h != null) {
                        h.postDelayed(new WindowClose(mStart, more, mWaits + 1),
                                WINDOW_HOLD_STEP_MS);
                        return;
                    }
                }
                if (sWindowUntil == mUntil) sWindowUntil = 0;
                boolean check = sWindowUntil == 0 && judgeHeld(AFTER_A_TAP, true);
                if (reapplyAodNow() || check) {
                    Module.log("double-tap-wake: touch window closed" + (check
                            ? ", judging whether the phone is in the hand" : ""));
                }
            } catch (Throwable t) {
                Module.log("double-tap-wake: touch window close failed", t);
            }
        }
    }

    private static void holdAwake(long ms) {
        try {
            PowerManager.WakeLock wl = sWindowWl;
            if (wl == null && sPm != null) {
                wl = sPm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelSuite:TapWindow");
                wl.setReferenceCounted(false);
                sWindowWl = wl;
            }
            if (wl != null) wl.acquire(ms);
        } catch (Throwable t) {
            Module.log("double-tap-wake: wake lock unavailable", t);
        }
    }

    private static void installLaunchGuard(ClassLoader cl) {
        Xp.Callback unlock = new Xp.Callback() {
            @Override
            protected void beforeHookedMethod(Xp.Param param) {
                for (Object a : param.args) {
                    if (a instanceof Boolean) {
                        if (!(Boolean) a) onUnlocked();
                        return;
                    }
                }
            }
        };
        int u = 0;
        String tms = "com.android.server.trust.TrustManagerService";
        try {
            Class<?> c = Xp.findClass(tms, cl);
            u += Xp.hookAllMethods(c, "setDeviceLockedForUser", unlock).size();
            u += Xp.hookAllMethods(c, "notifyKeystoreOfDeviceLockState", unlock).size();
        } catch (Throwable t) {
            Module.log("double-tap-wake: unlock watch failed", t);
        }
        for (int i = 1; i <= 15; i++) {
            try {
                Class<?> c = Class.forName(tms + "$" + i, false, cl);
                u += Xp.hookAllMethods(c, "setDeviceLockedForUser", unlock).size();
            } catch (Throwable ignored) { }
        }

        int a = 0;
        try {
            a = Xp.hookAllMethods(Xp.findClass("com.android.server.wm.ActivityStarter", cl),
                    "executeRequest", new Xp.Callback() {
                        @Override
                        protected void beforeHookedMethod(Xp.Param param) {
                            if (sDropArmed && param.args.length > 0) maybeDropLaunch(param);
                        }
                    }).size();
        } catch (Throwable t) {
            Module.log("double-tap-wake: launch guard failed", t);
        }
        Module.log("double-tap-wake: launch guard " + (a > 0 && u > 0 ? "installed"
                : "incomplete (starts " + a + ", unlock " + u + ")"));
    }

    private static void installTapQuiet(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> c = Xp.findClass(VMS, cl);
            String[] methods = {
                    "vibrate", "performHapticFeedback", "performHapticFeedbackForInputDevice"
            };
            for (final String m : methods) {
                n += Xp.hookAllMethods(c, m, new Xp.Callback() {
                    @Override
                    protected void beforeHookedMethod(Xp.Param param) {
                        if (undoingChips()) maybeQuietVibration(param, m);
                    }
                }).size();
            }
        } catch (Throwable t) {
            Module.log("double-tap-wake: tap vibration guard failed", t);
        }
        Module.log("double-tap-wake: tap vibration guard "
                + (n > 0 ? "installed (" + n + ")" : "unavailable"));
    }

    private static void trackQuietTap(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sTouchUpAt = 0;
                sTouchTap = true;
                sTouchX = ev.getX();
                sTouchY = ev.getY();
                sTouchDownAt = ev.getEventTime();
                synchronized (sTouchLock) {
                    sDownSeq++;
                    sTouchLock.notifyAll();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                sTouchTap = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                sTouchTap = false;
                sTouchCancelAt = ev.getEventTime();
                break;
            case MotionEvent.ACTION_MOVE:
                if (sTouchTap && (Math.abs(ev.getX() - sTouchX) > sTouchSlop
                        || Math.abs(ev.getY() - sTouchY) > sTouchSlop)) {
                    sTouchTap = false;
                }
                break;
            case MotionEvent.ACTION_UP:
                sTouchUpAt = ev.getEventTime();
                break;
            default:
                break;
        }
    }

    private static boolean tapActive(long now) {
        long down = sTouchDownAt;
        long up = sTouchUpAt;
        if (!sTouchTap || down == 0) return false;
        if (up == 0) return now - down <= TAP_MAX_MS;
        return up >= down && up - down <= TAP_MAX_MS && now - up <= QUIET_AFTER_UP_MS;
    }

    private static boolean mayAwaitDown(long now) {
        long down = sTouchDownAt, up = sTouchUpAt, cancel = sTouchCancelAt;
        boolean ended = down == 0 || up != 0 || cancel >= down;
        if (!ended) return false;
        long finger = sFingerAt;
        return finger == 0 || now - finger > FINGER_QUIET_MS;
    }

    private static void installFingerWatch(ClassLoader cl) {
        Xp.Callback finger = new Xp.Callback() {
            @Override
            protected void beforeHookedMethod(Xp.Param param) {
                sFingerAt = SystemClock.uptimeMillis();
            }
        };
        String base = "com.android.server.biometrics.sensors.";
        String[][] targets = {
                { base + "fingerprint.aidl.FingerprintProvider", "onPointerDown" },
                { base + "fingerprint.aidl.FingerprintAuthenticationClient", "onAcquired" },
        };
        int n = 0;
        for (String[] t : targets) {
            try {
                n += Xp.hookAllMethods(Xp.findClass(t[0], cl), t[1], finger).size();
            } catch (Throwable ignored) { }
        }
        Module.log("double-tap-wake: fingerprint watch " + (n > 0 ? "installed (" + n + ")"
                : "unavailable (fingerprint haptics may wait up to " + DOWN_WAIT_MS + " ms)"));
    }

    private static boolean awaitDown(int seq, long max) {
        long deadline = SystemClock.uptimeMillis() + max;
        synchronized (sTouchLock) {
            while (sDownSeq == seq) {
                long left = deadline - SystemClock.uptimeMillis();
                if (left <= 0) return false;
                try {
                    sTouchLock.wait(left);
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean quietPlace() {
        return (sUdfpsVisibility == android.view.View.VISIBLE || chipTapRecent())
                && lockScreenShowing();
    }

    private static void maybeQuietVibration(Xp.Param param, String method) {
        int seq = sDownSeq;
        if (!lockScreenShowing()) return;
        boolean haptic = method.startsWith("performHapticFeedback");
        String pkg = null;
        int usage = haptic ? USAGE_TOUCH : 0;
        int constant = -1, ints = 0;
        for (Object a : param.args) {
            if (a instanceof String) {
                if (pkg == null) pkg = (String) a;
            } else if (a instanceof Integer) {
                if (++ints == 3 && haptic) constant = (Integer) a;
            } else if (!haptic && a != null
                    && "android.os.VibrationAttributes".equals(a.getClass().getName())) {
                Object u = Xp.callMethod(a, "getUsage");
                if (u instanceof Integer) usage = (Integer) u;
            }
        }
        boolean touch = usage == 0 || ((usage & USAGE_CLASS_MASK) == USAGE_CLASS_FEEDBACK
                && usage != USAGE_ACCESSIBILITY);
        long now = SystemClock.uptimeMillis();
        if (!touch) {
            if (tapActive(now) && quietPlace()) {
                Module.log("double-tap-wake: lock screen tap, vibration kept ("
                        + describeVibration(pkg, method, constant, usage) + ")");
            }
            return;
        }
        long waited = -1;
        if (!tapActive(now)) {
            if (!SYSTEMUI.equals(pkg) || !mayAwaitDown(now) || !quietPlace()) return;
            if (!awaitDown(seq, DOWN_WAIT_MS)) return;
            long after = SystemClock.uptimeMillis();
            waited = after - now;
            if (!tapActive(after)) return;
        }
        if (!quietPlace()) return;
        param.setResult(null);
        Module.log("double-tap-wake: lock screen tap vibration blocked ("
                + describeVibration(pkg, method, constant, usage)
                + (waited >= 0 ? ", waited " + waited + " ms for the touch" : "") + ")");
    }

    private static String describeVibration(String pkg, String method, int constant, int usage) {
        return pkg + " " + method + (constant >= 0 ? " constant=" + constant : "")
                + " usage=0x" + Integer.toHexString(usage);
    }

    private static void onUnlocked() {
        final long at = SystemClock.uptimeMillis();
        sUnlockedAt = at;
        Handler h = sWorker;
        if (h == null || !sDropArmed) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sDropArmed && sUnlockedAt == at) {
                    sDropArmed = false;
                    Module.log("double-tap-wake: no queued card launch after unlock");
                }
            }
        }, LAUNCH_DROP_MS + 100);
    }

    private static void maybeDropLaunch(Xp.Param param) {
        long unlocked = sUnlockedAt;
        if (unlocked == 0 || SystemClock.uptimeMillis() - unlocked > LAUNCH_DROP_MS) return;
        Object req = param.args[0];
        Object real = Xp.getObjectField(req, "realCallingUid");
        if (!(real instanceof Integer) || (Integer) real != sSystemUiUid || sSystemUiUid < 0) return;
        sDropArmed = false;
        Object intent = null;
        try {
            intent = Xp.getObjectField(req, "intent");
            Object opts = Xp.getObjectField(req, "activityOptions");
            if (opts != null) Xp.callMethod(opts, "abort");
        } catch (Throwable ignored) { }
        param.setResult(START_CANCELED);
        Module.log("double-tap-wake: queued lock screen card launch cancelled ("
                + describeIntent(intent) + ")");
    }

    private static String describeIntent(Object o) {
        if (!(o instanceof android.content.Intent)) return "?";
        android.content.Intent i = (android.content.Intent) o;
        android.content.ComponentName c = i.getComponent();
        return (i.getAction() != null ? i.getAction() : "") + " "
                + (c != null ? c.flattenToShortString() : String.valueOf(i.getPackage()));
    }

    private static void installSmartspaceWatch(ClassLoader cl) {
        int n = 0;
        try {
            n = Xp.hookAllMethods(Xp.findClass(SMARTSPACE_STUB, cl), "notifySmartspaceEvent",
                    new Xp.Callback() {
                        @Override
                        protected void beforeHookedMethod(Xp.Param param) {
                            for (Object a : param.args) {
                                if (a == null || !a.getClass().getName()
                                        .endsWith("SmartspaceTargetEvent")) continue;
                                Object type = Xp.callMethod(a, "getEventType");
                                if (type instanceof Integer && (Integer) type == SMARTSPACE_TAP) {
                                    onChipTap();
                                }
                            }
                        }
                    }).size();
        } catch (Throwable t) {
            Module.log("double-tap-wake: smartspace watch failed", t);
        }
        Module.log("double-tap-wake: smartspace watch " + (n > 0 ? "installed" : "unavailable"));
    }

    private static boolean undoingChips() {
        return sSleepOn && !sLockShortcuts && sReceiver != null;
    }

    private static boolean chipTapRecent() {
        long t = sChipTapAt;
        return t != 0 && SystemClock.uptimeMillis() - t <= CHIP_WINDOW_MS;
    }

    private static void onChipTap() {
        if (!undoingChips()) return;
        if (android.os.Binder.getCallingUid() != sSystemUiUid) return;
        if (!lockScreenShowing()) return;
        long now = SystemClock.uptimeMillis();
        long prev = sChipTapAt;
        if (prev != 0 && now - prev < 300) return;
        sChipTapAt = now;
        if (deviceLocked()) sDropArmed = true;
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                maybeUndoChipTap();
            }
        });
    }

    private static void maybeUndoChipTap() {
        long tap = sChipTapAt;
        if (tap == 0 || tap == sHandledChipAt || !chipTapRecent()) return;
        if (!undoingChips() || !lockScreenShowing()) {
            sChipTapAt = 0;
            return;
        }
        long shown = sAltShownAt;
        if (shown == 0 || shown < tap - 300 || shown > tap + CHIP_PROMPT_MAX_MS) return;
        sHandledChipAt = tap;
        long wait = Math.max(0, shown + BACK_DELAY_MS - SystemClock.uptimeMillis());
        sWorker.postDelayed(backFor(tap, "lock screen card tap undone"), wait);
    }

    private static Runnable backFor(final long tap, final String why) {
        return new Runnable() {
            @Override
            public void run() {
                if (sBackSentFor == tap) return;
                if (!lockScreenShowing() || sAltVisibility != android.view.View.VISIBLE) return;
                sBackSentFor = tap;
                pressBack();
                sDropArmed = true;
                Module.log("double-tap-wake: " + why);
            }
        };
    }

    private static void checkIconAfterChip() {
        Handler h = sWorker;
        long tap = sChipTapAt;
        if (h == null || tap == 0) return;
        long wait = Math.max(0, tap + CHIP_WINDOW_MS + 50 - SystemClock.uptimeMillis());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                Receiver r = sReceiver;
                if (r != null && sUdfpsVisibility != android.view.View.VISIBLE
                        && lockScreenShowing()) {
                    r.disarmFromIcon();
                }
            }
        }, wait);
    }

    private static void installWindowWatch(ClassLoader cl) {
        int n = 0;
        try {
            n = Xp.hookAllMethods(Xp.findClass("com.android.server.wm.WindowState", cl),
                    "setViewVisibility", new Xp.Callback() {
                        @Override
                        protected void afterHookedMethod(Xp.Param param) {
                            if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                onWindowVisibility(param.thisObject, (Integer) param.args[0]);
                            }
                        }
                    }).size();
        } catch (Throwable ignored) { }
        if (n == 0) {
            try {
                n = Xp.hookAllMethods(Xp.findClass("com.android.server.wm.WindowManagerService", cl),
                        "relayoutWindow", new Xp.Callback() {
                            @Override
                            protected void afterHookedMethod(Xp.Param param) {
                                relayoutFallback(param);
                            }
                        }).size();
                if (n > 0) Module.log("double-tap-wake: watching windows via relayoutWindow");
            } catch (Throwable ignored) { }
        }
        Module.log("double-tap-wake: window watch " + (n > 0 ? "installed" : "unavailable"));
    }

    private static void relayoutFallback(Xp.Param param) {
        try {
            if (param.args.length < 2 || !(param.args[1] instanceof android.os.IInterface)) return;
            int ints = 0;
            int vis = -1;
            for (Object a : param.args) {
                if (a instanceof Integer && ++ints == 3) {
                    vis = (Integer) a;
                    break;
                }
            }
            if (vis < 0) return;
            Object map = Xp.getObjectField(param.thisObject, "mWindowMap");
            if (!(map instanceof java.util.Map)) return;
            Object ws = ((java.util.Map<?, ?>) map).get(
                    ((android.os.IInterface) param.args[1]).asBinder());
            if (ws != null) onWindowVisibility(ws, vis);
        } catch (Throwable ignored) { }
    }

    private static void onWindowVisibility(Object windowState, int visibility) {
        try {
            Object attrs = attrsOf(windowState);
            if (!(attrs instanceof android.view.WindowManager.LayoutParams)) return;
            CharSequence title = ((android.view.WindowManager.LayoutParams) attrs).getTitle();
            if (title == null) return;
            if (title.toString().contains(ALT_BOUNCER_WINDOW)) {
                int id = System.identityHashCode(windowState);
                if (id != sAltWindowId || visibility != sAltVisibility) {
                    sAltWindowId = id;
                    sAltVisibility = visibility;
                    if (visibility == android.view.View.VISIBLE) {
                        sAltShownAt = SystemClock.uptimeMillis();
                        Handler h = sWorker;
                        if (h != null) {
                            h.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (sDropArmed && !chipTapRecent()) sDropArmed = false;
                                    maybeUndoChipTap();
                                }
                            });
                        }
                    }
                }
                return;
            }
            if (!title.toString().contains(UDFPS_WINDOW)) return;
            sUdfpsSeen = true;
            if (visibility == sUdfpsVisibility) return;
            sUdfpsVisibility = visibility;
            final boolean visible = visibility == android.view.View.VISIBLE;
            Handler h = sWorker;
            if (h == null) return;
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (visible) ensureMonitor("lock screen shown");
                    Receiver r = sReceiver;
                    if (r != null) r.onFingerprintIcon(visible);
                }
            });
        } catch (Throwable ignored) { }
    }

    private static volatile java.lang.reflect.Field sAttrsField;

    private static Object attrsOf(Object windowState) throws Exception {
        java.lang.reflect.Field f = sAttrsField;
        if (f == null || !f.getDeclaringClass().isInstance(windowState)) {
            f = null;
            for (Class<?> c = windowState.getClass(); c != null && f == null;
                    c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField("mAttrs");
                } catch (NoSuchFieldException ignored) { }
            }
            if (f == null) return null;
            f.setAccessible(true);
            sAttrsField = f;
        }
        return f.get(windowState);
    }

    private static synchronized void init(Object pwm, Context ctx) {
        if (sInitDone || ctx == null) return;
        sPwm = pwm;
        sCtx = ctx;
        sPm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        try {
            sSystemUiUid = ctx.getPackageManager()
                    .getApplicationInfo("com.android.systemui", 0).uid;
        } catch (Throwable t) {
            Module.log("double-tap-wake: System UI uid unknown", t);
        }
        HandlerThread t = new HandlerThread("PixelSuiteDoubleTap");
        t.start();
        sWorker = new Handler(t.getLooper());

        ViewConfiguration vc = ViewConfiguration.get(ctx);
        sTouchSlop = vc.getScaledTouchSlop();
        sDoubleTapSlop = vc.getScaledDoubleTapSlop();

        readFlags();
        ContentObserver obs = new ContentObserver(sWorker) {
            @Override
            public void onChange(boolean selfChange) {
                readFlags();
                repairTapSensor();
                updateMonitorForFlags();
                Module.log("double-tap-wake: on=" + sMasterOn + " doubleTapWake=" + sWakeOn
                        + " lockScreen=" + (sLockShortcuts ? "stock" : "modded"));
            }
        };
        ContentResolver cr = ctx.getContentResolver();
        cr.registerContentObserver(Settings.Secure.getUriFor(Feature.TAP_CHECK), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(Feature.DOUBLE_TAP_WAKE), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(Feature.LOCK_SHORTCUTS), false, obs);
        initAodPower(ctx, t.getLooper());

        sWorker.post(new Runnable() {
            @Override
            public void run() {
                repairTapSensor();
            }
        });

        IntentFilter onOff = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        onOff.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(ctx, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                    sScreenOnAt = SystemClock.uptimeMillis();
                    sWindowUntil = 0;
                    sWindowFirstAt = 0;
                    sLastSingle = 0;
                    sLooseUpAt = 0;
                    sLoosePairAt = 0;
                    stopMotionTap("screen on");
                    endHeld("screen on", false);
                    disarmPickup();
                    sHeldSpentMs = 0;
                    updateRecording("screen on");
                } else {
                    sScreenOffAt = SystemClock.uptimeMillis();
                    sHeldSpentMs = 0;
                    sHeldBudgetLogged = false;
                    ensureMonitor("screen off");
                    onScreenOffForShaking();
                    updateRecording("screen off");
                    final long off = sScreenOffAt;
                    sWorker.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (sScreenOffAt == off) updateRecording("screen off a while");
                        }
                    }, PRE_RECORD_MS + 100L);
                }
            }
        }, onOff, sWorker);

        sInitDone = true;
        sWorker.post(new Runnable() {
            @Override
            public void run() {
                ensureMonitor("boot");
                Module.log("double-tap-wake: initialised, on=" + sMasterOn + " doubleTapWake="
                        + sWakeOn + " lockScreen=" + (sLockShortcuts ? "stock" : "modded")
                        + " monitor=" + (sReceiver != null));
            }
        });
    }

    private static boolean monitorWanted() {
        return sSleepOn || sWakeOn;
    }

    private static void ensureMonitor(String why) {
        if (sReceiver != null || sCtx == null || sWorker == null || !monitorWanted()) return;
        if (sMonitorFailures >= MAX_MONITOR_FAILURES) return;
        Object monitor = null;
        try {
            Object im = sCtx.getSystemService(Context.INPUT_SERVICE);
            monitor = Xp.callMethod(im, "monitorGestureInput",
                    "PixelSuiteLockTap", Display.DEFAULT_DISPLAY);
            InputChannel channel = (InputChannel) Xp.callMethod(monitor, "getInputChannel");
            Receiver r = new Receiver(channel, sWorker.getLooper());
            sMonitor = monitor;
            sChannel = channel;
            sReceiver = r;
            Module.log("double-tap-wake: lock screen touch monitor on (" + why + ")");
        } catch (Throwable e) {
            sMonitorFailures++;
            if (monitor != null) {
                try {
                    Xp.callMethod(monitor, "dispose");
                } catch (Throwable ignored) { }
            }
            Module.log("double-tap-wake: touch monitor unavailable", e);
        }
    }

    private static void releaseMonitor(String why) {
        Receiver r = sReceiver;
        Object monitor = sMonitor;
        if (r == null && monitor == null) return;
        sReceiver = null;
        sMonitor = null;
        sChannel = null;
        sTouchCancelAt = SystemClock.uptimeMillis();
        try {
            if (r != null) r.dispose();
        } catch (Throwable ignored) { }
        try {
            if (monitor != null) Xp.callMethod(monitor, "dispose");
        } catch (Throwable t) {
            Module.log("double-tap-wake: monitor dispose failed", t);
        }
        Module.log("double-tap-wake: lock screen touch monitor off (" + why + ")");
    }

    private static void updateMonitorForFlags() {
        if (!monitorWanted()) {
            releaseMonitor("switched off");
        } else if (sPm != null && (!sPm.isInteractive() || keyguardShowing())) {
            ensureMonitor("switched on");
        }
    }

    private static boolean keyguardShowing() {
        try {
            return !Boolean.FALSE.equals(Xp.callMethod(sPwm, "isKeyguardShowing"));
        } catch (Throwable t) {
            return true;
        }
    }

    private static void registerReceiver(Context ctx, BroadcastReceiver r, IntentFilter f,
                                         Handler handler) {
        try {
            try {
                Context.class.getMethod("registerReceiver", BroadcastReceiver.class,
                        IntentFilter.class, String.class, Handler.class, int.class)
                        .invoke(ctx, r, f, null, handler, 4);
            } catch (NoSuchMethodException e) {
                ctx.registerReceiver(r, f, null, handler);
            }
        } catch (Throwable t) {
            Module.log("double-tap-wake: receiver registration failed", t);
        }
    }

    private static void readFlags() {
        sMasterOn = Feature.on(sCtx, Feature.TAP_CHECK);
        sWakeOn = sMasterOn && Feature.on(sCtx, Feature.DOUBLE_TAP_WAKE);
        sSleepOn = sMasterOn;
        sLockShortcuts = Feature.on(sCtx, Feature.LOCK_SHORTCUTS);
        if (!sWakeOn && sWorker != null) {
            sWorker.post(new Runnable() {
                @Override
                public void run() {
                    endHeld("double tap to wake off", false);
                    disarmPickup();
                }
            });
        }
        reapplyAodState();
        updateRecording("settings");
    }

    private static void repairTapSensor() {
        try {
            ContentResolver r = sCtx.getContentResolver();
            if (sWakeOn && Settings.Secure.getInt(r, KEY_TAP_GESTURE, 1) == 0) {
                Settings.Secure.putInt(r, KEY_TAP_GESTURE, 1);
                Settings.Secure.putInt(r, KEY_DOUBLE_TAP_GESTURE, 1);
                Module.log("double-tap-wake: tap sensor switched back on");
            }
        } catch (Throwable e) {
            Module.log("double-tap-wake: settings check failed", e);
        }
    }

    private static void onWakeRequest(Xp.Param param) {
        Handler h = sWorker;
        if (h != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    ensureMonitor("wake");
                    Receiver r = sReceiver;
                    if (r != null) r.rearm();
                }
            });
        }
        int reason = -1;
        String details = null;
        for (Object a : param.args) {
            if (reason == -1 && a instanceof Integer) reason = (Integer) a;
            if (details == null && a instanceof String) details = (String) a;
        }
        if (h != null && sScreenOffAt > sScreenOnAt && (reason == sBiometricReason
                || (details != null && details.contains("BIOMETRIC")))) {
            final long wakeAt = SystemClock.uptimeMillis();
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        endTouchAtUnlock(wakeAt);
                    } catch (Throwable t) {
                        Module.log("double-tap-wake: unlock touch end failed", t);
                    }
                }
            });
        }
        if (reason != sTapReason || !sWakeOn) return;
        long now = SystemClock.uptimeMillis();
        if (isSingleTap(details)) {
            long wokeAt = sTapWakeAt;
            if (wokeAt != 0 && wokeAt > sScreenOffAt && now - wokeAt <= SINGLE_PAIR_MS) {
                param.setResult(null);
                Module.log("double-tap-wake: tap right after a tap wake, ignored (" + details + ")");
                return;
            }
            long first = sLastSingle;
            boolean windowPair = first != 0 && first == sWindowFirstAt && now <= sPairUntil;
            if (first != 0 && (windowPair || now - first <= SINGLE_PAIR_MS)) {
                sLastSingle = 0;
                sTapWakeAt = now;
                Module.log("double-tap-wake: second tap, waking (" + details + ")");
            } else if (!isGestureEngineTap(details) && sLoosePairAt != 0
                    && now - sLoosePairAt >= 0 && now - sLoosePairAt <= LOOSE_REPORT_MS) {
                sLoosePairAt = 0;
                sLooseUpAt = 0;
                sLastSingle = 0;
                sTapWakeAt = now;
                Module.log("double-tap-wake: second tap, waking (first tap moved a little; "
                        + details + ")");
            } else {
                sLastSingle = now;
                param.setResult(null);
                Module.log("double-tap-wake: single tap ignored (" + details + ")");
                if (isGestureEngineTap(details)) afterGestureTap(now);
            }
        } else {
            sLastSingle = 0;
            sTapWakeAt = now;
            Module.log("double-tap-wake: waking (" + details + ")");
        }
    }

    static boolean isSingleTap(String details) {
        if (details == null) return false;
        if (details.contains("SINGLE_TAP")) return true;
        return details.endsWith(" tap");
    }

    static boolean isGestureEngineTap(String details) {
        return details != null && details.contains("NODOZE");
    }

    private static void afterGestureTap(final long tapAt) {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!openTouchWindow(tapAt)) requestPulse(tapAt);
                } catch (Throwable t) {
                    Module.log("double-tap-wake: first tap handling failed", t);
                }
            }
        });
    }

    private static void requestPulse(final long tapAt) {
        Handler h = sWorker;
        final Context ctx = sCtx;
        if (h == null || ctx == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sPm != null && sPm.isInteractive()) return;
                if (sLastSingle != tapAt) return;
                try {
                    Intent i = new Intent(PULSE_ACTION).setPackage(SYSTEMUI);
                    UserHandle all = (UserHandle) UserHandle.class.getField("ALL").get(null);
                    ctx.sendBroadcastAsUser(i, all);
                    Module.log("double-tap-wake: pulse requested to re-arm taps");
                } catch (Throwable t) {
                    Module.log("double-tap-wake: pulse request failed", t);
                }
                confirmPulse(tapAt);
            }
        }, PULSE_DELAY_MS);
    }

    private static final long PULSE_CONFIRM_MS = 400L;

    private static void confirmPulse(final long tapAt) {
        Handler h = sWorker;
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sPm == null || sPm.isInteractive() || sLastSingle != tapAt) return;
                if (aodShowing(sAodArgs)) return;
                Module.log("double-tap-wake: no pulse (always-on display paused), waking on"
                        + " the tap");
                sLastSingle = 0;
                wakeForTap();
            }
        }, PULSE_CONFIRM_MS);
    }

    private static void onDozeDoubleTap() {
        final long single = sLastSingle;
        long now = SystemClock.uptimeMillis();
        if (single == 0 || now - single > BACKUP_PAIR_MS) return;
        Handler h = sWorker;
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sPm == null || sPm.isInteractive() || sLastSingle != single) return;
                Module.log("double-tap-wake: System UI missed the second tap (lock screen was "
                        + "still coming up), waking from Pixel Suite's own double tap");
                wakeForTap();
            }
        }, BACKUP_WAIT_MS);
    }

    private static void wakeForTap() {
        try {
            PowerManager.class.getMethod("wakeUp", long.class, int.class, String.class)
                    .invoke(sPm, SystemClock.uptimeMillis(), sTapReason, BACKUP_DETAILS);
        } catch (Throwable t) {
            Module.log("double-tap-wake: backup wake failed", t);
        }
    }

    private static final class Receiver extends InputEventReceiver {
        private boolean mArmed = true;
        private boolean mIgnore, mTap;
        private boolean mLeftover;
        private boolean mPilfered;
        private long mDownAt, mPrevUpAt;
        private float mDownX, mDownY, mPrevX, mPrevY;

        Receiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        private boolean mDzActive, mDzTap, mDzMulti;
        private long mDzDownAt, mDzPrevUpAt;
        private float mDzX, mDzY, mDzPrevX, mDzPrevY, mDzMove;

        private void onDozeTouch(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDzActive = sPm != null && !sPm.isInteractive();
                    if (!mDzActive) {
                        mDzPrevUpAt = 0;
                        return;
                    }
                    mDzTap = true;
                    mDzMulti = false;
                    mDzMove = 0f;
                    mDzDownAt = ev.getEventTime();
                    mDzX = ev.getX();
                    mDzY = ev.getY();
                    long looseUp = sLooseUpAt;
                    float lx = mDzX - sLooseX, ly = mDzY - sLooseY;
                    sLoosePairAt = looseUp != 0 && mDzDownAt - looseUp >= 0
                            && mDzDownAt - looseUp <= DOUBLE_GAP_MS
                            && lx * lx + ly * ly <= (float) sDoubleTapSlop * sDoubleTapSlop
                            ? mDzDownAt : 0;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    mDzTap = false;
                    mDzMulti = true;
                    mDzPrevUpAt = 0;
                    sLoosePairAt = 0;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (mDzActive) {
                        mDzMove = Math.max(mDzMove, Math.max(Math.abs(ev.getX() - mDzX),
                                Math.abs(ev.getY() - mDzY)));
                    }
                    if (mDzActive && mDzTap && (Math.abs(ev.getX() - mDzX) > sTouchSlop
                            || Math.abs(ev.getY() - mDzY) > sTouchSlop)) {
                        mDzTap = false;
                        mDzPrevUpAt = 0;
                    }
                    break;
                case MotionEvent.ACTION_UP: {
                    if (!mDzActive) return;
                    long up = ev.getEventTime();
                    long held = up - mDzDownAt;
                    boolean shortTouch = !mDzMulti && held <= TAP_MAX_MS
                            && mDzMove <= LOOSE_SLOP_TIMES * sTouchSlop;
                    sLooseUpAt = shortTouch ? up : 0;
                    sLooseX = mDzX;
                    sLooseY = mDzY;
                    if (!mDzTap) {
                        Module.log("double-tap-wake: touch while asleep, not a tap ("
                                + (mDzMulti ? "second finger or taken by another window"
                                        : "moved " + Math.round(mDzMove) + " px, slop "
                                                + sTouchSlop + ", in " + held + " ms") + ")");
                        return;
                    }
                    if (up - mDzDownAt > TAP_MAX_MS) {
                        Module.log("double-tap-wake: touch while asleep, not a tap (held "
                                + (up - mDzDownAt) + " ms)");
                        mDzPrevUpAt = 0;
                        return;
                    }
                    Module.log("double-tap-wake: tap while asleep (" + (up - mDzDownAt) + " ms)");
                    float dx = mDzX - mDzPrevX, dy = mDzY - mDzPrevY;
                    boolean close = dx * dx + dy * dy <= (float) sDoubleTapSlop * sDoubleTapSlop;
                    if (mDzPrevUpAt != 0 && close && mDzDownAt - mDzPrevUpAt <= DOUBLE_GAP_MS) {
                        mDzPrevUpAt = 0;
                        long late = SystemClock.uptimeMillis() - up;
                        if (late > STALE_TOUCH_MS) {
                            Module.log("double-tap-wake: double tap while asleep ignored (seen "
                                    + late + " ms late)");
                        } else {
                            onDozeDoubleTap();
                        }
                    } else {
                        mDzPrevUpAt = up;
                        mDzPrevX = mDzX;
                        mDzPrevY = mDzY;
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent) {
                    noteLag((MotionEvent) event);
                    trackQuietTap((MotionEvent) event);
                    if (sWakeOn) onDozeTouch((MotionEvent) event);
                    if (sSleepOn || sWakeOn) onTouch((MotionEvent) event);
                }
            } catch (Throwable t) {
                Module.log("double-tap-wake: touch handling failed", t);
            } finally {
                finishInputEvent(event, false);
            }
        }

        void rearm() {
            mArmed = true;
            mPrevUpAt = 0;
            sChipTapAt = 0;
        }

        void onFingerprintIcon(boolean visible) {
            if (!sSleepOn) return;
            boolean chip = undoingChips() && chipTapRecent();
            if (visible) {
                if (lockScreenShowing()) {
                    if (chip) {
                        mArmed = true;
                        return;
                    }
                    if (!mArmed) Module.log("double-tap-wake: back at the lock screen, double tap on");
                    rearm();
                }
            } else if (!chip) {
                disarm("PIN pad or fingerprint prompt open");
            } else {
                checkIconAfterChip();
            }
        }

        private boolean offWithoutIcon() {
            return !mPilfered && !sUdfpsSeen;
        }

        void disarmFromIcon() {
            disarm("PIN pad or fingerprint prompt open");
        }

        private void disarm(String why) {
            if (mArmed) Module.log("double-tap-wake: lock screen double tap off until next wake ("
                    + why + ")");
            mArmed = false;
            mPrevUpAt = 0;
        }

        private void onTouch(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    boolean screenOn = sPm != null && sPm.isInteractive();
                    boolean lock = lockScreenShowing();
                    mIgnore = !screenOn || !lock;
                    mTap = false;
                    mPilfered = false;
                    mLeftover = isLeftoverAfterTapWake(ev, LEFTOVER_COUNT_MS);
                    if (!mIgnore) mPilfered = cancelIfLeftoverAfterTapWake(ev);
                    if (!sSleepOn) mIgnore = true;
                    if (screenOn && !lock) {
                        mArmed = true;
                        mPrevUpAt = 0;
                        if (!keyguardShowing()) {
                            final Receiver self = this;
                            sWorker.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (sReceiver == self) releaseMonitor("unlocked");
                                }
                            });
                        }
                    }
                    if (mIgnore) return;
                    long down = ev.getEventTime();
                    float px = ev.getX() - mPrevX, py = ev.getY() - mPrevY;
                    if (mPrevUpAt != 0 && down - mPrevUpAt <= DOUBLE_GAP_MS
                            && px * px + py * py <= (float) sDoubleTapSlop * sDoubleTapSlop
                            && undoingChips() && chipTapRecent()) {
                        if (pilfer("second tap kept off the card prompt")) mPilfered = true;
                    }
                    if (mPrevUpAt != 0 && down - mPrevUpAt > DOUBLE_GAP_MS) {
                        if (sUdfpsSeen) {
                            mPrevUpAt = 0;
                        } else {
                            disarm("single tap");
                        }
                    }
                    mDownAt = down;
                    mDownX = ev.getX();
                    mDownY = ev.getY();
                    mTap = true;
                    break;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (!mIgnore) {
                        mTap = false;
                        mPrevUpAt = 0;
                        if (offWithoutIcon()) disarm("two fingers");
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!mIgnore && mTap && (Math.abs(ev.getX() - mDownX) > sTouchSlop
                            || Math.abs(ev.getY() - mDownY) > sTouchSlop)) {
                        mTap = false;
                        mPrevUpAt = 0;
                        if (offWithoutIcon()) disarm("swipe");
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    mTap = false;
                    mPrevUpAt = 0;
                    break;
                case MotionEvent.ACTION_UP: {
                    if (mIgnore || !mTap) return;
                    long up = ev.getEventTime();
                    if (up - mDownAt > TAP_MAX_MS) {
                        mPrevUpAt = 0;
                        if (offWithoutIcon()) disarm("long press");
                        return;
                    }
                    if (mLeftover) {
                        mPrevUpAt = 0;
                        return;
                    }
                    float dx = mDownX - mPrevX, dy = mDownY - mPrevY;
                    boolean close = dx * dx + dy * dy <= (float) sDoubleTapSlop * sDoubleTapSlop;
                    if (mPrevUpAt != 0 && close && mDownAt - mPrevUpAt <= DOUBLE_GAP_MS) {
                        mPrevUpAt = 0;
                        long late = SystemClock.uptimeMillis() - up;
                        if (late > STALE_TOUCH_MS) {
                            Module.log("double-tap-wake: lock screen double tap ignored (seen "
                                    + late + " ms late)");
                        } else if (mArmed) {
                            onLockDoubleTap();
                        } else {
                            Module.log("double-tap-wake: lock screen double tap ignored "
                                    + "(PIN pad, prompt or shade was open)");
                        }
                    } else {
                        mPrevUpAt = up;
                        mPrevX = mDownX;
                        mPrevY = mDownY;
                    }
                    break;
                }
            }
        }
    }

    private static boolean cancelIfLeftoverAfterTapWake(MotionEvent ev) {
        if (!isLeftoverAfterTapWake(ev, WAKE_GUARD_MS)) return false;
        long since = ev.getEventTime() - sTapWakeAt;
        int height = screenHeight();
        if (height > 0 && ev.getY() > height * WAKE_GUARD_TOP) return false;
        return pilfer("leftover tap " + since + " ms after tap wake cancelled");
    }

    private static boolean isLeftoverAfterTapWake(MotionEvent ev, long ms) {
        long wakeAt = sTapWakeAt;
        if (wakeAt == 0) return false;
        long since = ev.getEventTime() - wakeAt;
        return since >= 0 && since <= ms;
    }

    private static boolean pilfer(String why) {
        return pilfer(why, 0L);
    }

    private static boolean pilfer(final String why, long waitMs) {
        final Object monitor = sMonitor;
        if (monitor == null) return false;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final long down = sTouchDownAt;
        final long asked = SystemClock.uptimeMillis();
        pilferThread().post(new Runnable() {
            @Override
            public void run() {
                long start = SystemClock.uptimeMillis();
                long up = sTouchUpAt, cancel = sTouchCancelAt;
                if (sTouchDownAt != down || (up != 0 && up >= down) || cancel >= down) {
                    done.countDown();
                    Module.log("double-tap-wake: " + why + ": too late, the touch was over ("
                            + (start - asked) + " ms)");
                    return;
                }
                try {
                    Xp.callMethod(monitor, "pilferPointers");
                    done.countDown();
                    long took = SystemClock.uptimeMillis() - start, waited = start - asked;
                    Module.log("double-tap-wake: " + why + (took + waited > 100
                            ? " (cancel took " + took + " ms, waited " + waited + " ms)" : ""));
                } catch (Throwable t) {
                    done.countDown();
                    Module.log("double-tap-wake: could not cancel touch (" + why + ")", t);
                }
            }
        });
        if (waitMs > 0) {
            try {
                if (!done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    Module.log("double-tap-wake: cancel not done within " + waitMs + " ms ("
                            + why + ")");
                }
            } catch (InterruptedException ignored) { }
        }
        return true;
    }

    private static final long PILFER_WAIT_MS = 50L;

    private static final long STALL_CHECK_MS = 1000L;
    private static volatile long sPingSeq, sPongSeq;
    private static long sStallLoggedAt;

    private static void checkStall() {
        final Handler w = sWorker;
        if (w == null) return;
        final long seq = ++sPingSeq;
        w.post(new Runnable() {
            @Override
            public void run() {
                sPongSeq = seq;
            }
        });
        sensorThread().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sPongSeq >= seq) return;
                long now = SystemClock.uptimeMillis();
                if (now - sStallLoggedAt < 10000L) return;
                sStallLoggedAt = now;
                StringBuilder sb = new StringBuilder();
                try {
                    StackTraceElement[] st = w.getLooper().getThread().getStackTrace();
                    for (int i = 0; i < st.length && i < 18; i++) {
                        sb.append("\n  at ").append(st[i]);
                    }
                } catch (Throwable t) {
                    sb.append(" (stack unavailable: ").append(t).append(')');
                }
                Module.log("double-tap-wake: touch thread held up over " + STALL_CHECK_MS
                        + " ms:" + sb);
            }
        }, STALL_CHECK_MS);
    }

    private static final long STALE_TOUCH_MS = 600L;
    private static final long TOUCH_LAG_LOG_MS = 250L;
    private static long sLagLoggedAt;
    private static volatile Handler sPilferThread;

    private static Handler pilferThread() {
        Handler h = sPilferThread;
        if (h != null) return h;
        synchronized (DoubleTapWake.class) {
            if (sPilferThread == null) {
                HandlerThread t = new HandlerThread("PixelSuitePilfer");
                t.start();
                sPilferThread = new Handler(t.getLooper());
            }
            return sPilferThread;
        }
    }

    private static void noteLag(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) checkStall();
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_UP) return;
        long now = SystemClock.uptimeMillis();
        long lag = now - ev.getEventTime();
        if (lag < TOUCH_LAG_LOG_MS || now - sLagLoggedAt < 1000L) return;
        sLagLoggedAt = now;
        Module.log("double-tap-wake: touch " + (a == MotionEvent.ACTION_DOWN ? "down" : "up")
                + " handled " + lag + " ms late");
    }

    private static void pressBack() {
        try {
            Object im = sCtx.getSystemService(Context.INPUT_SERVICE);
            long now = SystemClock.uptimeMillis();
            int[] actions = { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP };
            for (int action : actions) {
                KeyEvent ev = new KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM,
                        InputDevice.SOURCE_KEYBOARD);
                Xp.callMethod(im, "injectInputEvent", ev, 0 );
            }
            Module.log("double-tap-wake: fingerprint prompt closed, queued app cancelled");
        } catch (Throwable t) {
            Module.log("double-tap-wake: could not close the fingerprint prompt", t);
        }
    }

    private static int sScreenHeight;

    private static int screenHeight() {
        if (sScreenHeight > 0) return sScreenHeight;
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    sCtx.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point p = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(p);
            sScreenHeight = Math.max(p.x, p.y);
        } catch (Throwable t) {
            sScreenHeight = sCtx.getResources().getDisplayMetrics().heightPixels;
        }
        return sScreenHeight;
    }

    private static void onLockDoubleTap() {
        boolean screenOn = sPm != null && sPm.isInteractive();
        boolean lockShowing = lockScreenShowing();
        Module.log("double-tap-wake: lock screen double tap, screenOn=" + screenOn
                + " lockScreen=" + lockShowing);
        if (!screenOn || !lockShowing) return;
        long tap = sChipTapAt;
        Handler h = sWorker;
        if (h != null && undoingChips() && chipTapRecent() && sBackSentFor != tap) {
            long shown = sAltShownAt;
            long now = SystemClock.uptimeMillis();
            long wait = (shown != 0 && shown >= tap - 300)
                    ? Math.max(0, shown + BACK_DELAY_MS - now) : BACK_DELAY_MS;
            h.postDelayed(backFor(tap, "card prompt closed before sleeping"), wait);
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    goToSleep();
                }
            }, wait + BACK_DELAY_MS);
            return;
        }
        goToSleep();
    }

    private static void goToSleep() {
        sChipTapAt = 0;
        try {
            PowerManager.class.getMethod("goToSleep", long.class, int.class, int.class)
                    .invoke(sPm, SystemClock.uptimeMillis(), SLEEP_REASON_POWER_BUTTON, 0);
            Module.log("double-tap-wake: screen off");
        } catch (Throwable t) {
            Module.log("double-tap-wake: goToSleep failed", t);
        }
    }

    private static boolean deviceLocked() {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    sCtx.getSystemService(Context.KEYGUARD_SERVICE);
            return km == null || km.isDeviceLocked();
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean lockScreenShowing() {
        try {
            Object r = Xp.callMethod(sPwm, "isKeyguardShowingAndNotOccluded");
            if (r instanceof Boolean) return (Boolean) r;
        } catch (Throwable ignored) { }
        try {
            boolean showing = Boolean.TRUE.equals(Xp.callMethod(sPwm, "isKeyguardShowing"));
            boolean occluded = Boolean.TRUE.equals(Xp.callMethod(sPwm, "isKeyguardOccluded"));
            return showing && !occluded;
        } catch (Throwable t) {
            return false;
        }
    }

    static void installSettings(ClassLoader cl) {
        Xp.Callback rename = new Xp.Callback() {
            @Override
            protected void afterHookedMethod(Xp.Param param) {
                Object r = param.getResult();
                if (r instanceof CharSequence) {
                    CharSequence s = (CharSequence) r;
                    if (s.length() == OLD_TITLE.length() && OLD_TITLE.contentEquals(s)
                            && masterOnInSettings()) {
                        param.setResult(NEW_TITLE);
                    }
                }
            }
        };
        hookQuiet(cl, "android.content.res.Resources", "getText", rename);
        hookQuiet(cl, "android.content.res.TypedArray", "getText", rename);
        hookQuiet(cl, "android.content.res.TypedArray", "getString", rename);

        try {
            final Class<?> prefFragment =
                    Xp.findClass("androidx.preference.PreferenceFragmentCompat", cl);
            Class<?> fragment = Xp.findClass("androidx.fragment.app.Fragment", cl);
            int n = Xp.hookAllMethods(fragment, "onResume", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    if (prefFragment.isInstance(param.thisObject)) schedule(param.thisObject);
                }
            }).size();
            Module.log("double-tap-wake: settings hooks installed (" + n + ")");
        } catch (Throwable t) {
            Module.log("double-tap-wake: settings page hook failed", t);
        }
    }

    private static final Feature.Cached sMasterCached = new Feature.Cached(Feature.TAP_CHECK);

    private static boolean masterOnInSettings() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            return !(app instanceof Context) || sMasterCached.on((Context) app);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void writeReal(ContentResolver cr, String key, int value) {
        Settings.Secure.putInt(cr, key, value);
    }

    private static void hookQuiet(ClassLoader cl, String cls, String method, Xp.Callback cb) {
        try {
            Xp.hookAllMethods(Xp.findClass(cls, cl), method, cb);
        } catch (Throwable t) {
            Module.log("double-tap-wake: rename hook skipped for " + cls + "." + method, t);
        }
    }

    private static Handler main() {
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
        return sMain;
    }

    private static void schedule(final Object fragment) {
        main().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Object screen = Xp.callMethod(fragment, "getPreferenceScreen");
                    if (screen != null) scan(screen, screen);
                } catch (Throwable t) {
                    Module.log("double-tap-wake: page scan failed", t);
                }
            }
        }, 200L);
    }

    private static boolean scan(Object root, Object group) throws Exception {
        int n = (Integer) Xp.callMethod(group, "getPreferenceCount");
        for (int i = 0; i < n; i++) {
            Object p = Xp.callMethod(group, "getPreference", i);
            if (p == null) continue;
            if (isTapCheckSwitch(p)) {
                int total = (Integer) Xp.callMethod(root, "getPreferenceCount");
                if (total <= MAX_PAGE_PREFS || find(group, KEY_TAP) != null) {
                    inject(group, p);
                } else {
                    Module.log("double-tap-wake: skipped a page with " + total + " items");
                }
                return true;
            }
            if (has(p, "getPreferenceCount") && scan(root, p)) return true;
        }
        return false;
    }

    private static boolean isTapCheckSwitch(Object p) {
        Object t = Xp.callMethod(p, "getTitle");
        if (t == null) return false;
        String s = t.toString();
        if (!OLD_TITLE.equals(s) && !NEW_TITLE.equals(s)) return false;
        if (!has(p, "setChecked", boolean.class)) return false;
        return Xp.callMethod(p, "getFragment") == null;
    }

    private static void inject(final Object group, final Object mainSwitch) throws Exception {
        final Context ctx = (Context) Xp.callMethod(mainSwitch, "getContext");
        if (find(group, KEY_TAP) == null) {
            ClassLoader loader = mainSwitch.getClass().getClassLoader();
            Class<?> radio = radioClass(loader);
            Object tap = make(radio, ctx, KEY_TAP, "Tap to wake",
                    "Tap the screen once to wake it.");
            Object dbl = make(radio, ctx, KEY_DOUBLE, "Double tap to wake",
                    "Tap the screen twice to wake it. A single tap does nothing.\n\n"
                            + "Works on the Always On Display: the motion sensor feels the second "
                            + "tap, even before the touchscreen is back. If it doesn't wake right "
                            + "away, one more tap within 3 seconds does it.\n\n"
                            + "While you're holding the phone or it rests on your lap, the "
                            + "touchscreen stays on instead (up to 90 seconds at a time), so both "
                            + "taps are real touches.\n\n"
                            + "When the phone shakes too much to feel taps (in a car mount, "
                            + "say), the touchscreen stays on until it's still again, so double "
                            + "taps keep working.");
            Object modded = make(radio, ctx, KEY_MODDED, "Modded behavior (recommended)",
                    "Double-tap anywhere on the lock screen to turn the screen off, even on the "
                            + "alarm, weather or date under the clock: they act like empty "
                            + "space, so a double tap never opens them by mistake. Taps on the "
                            + "lock screen don't vibrate.");
            Object stock = make(radio, ctx, KEY_STOCK, "Stock behavior",
                    "Double-tap the lock screen to turn the screen off, and keep the lock screen "
                            + "as Google made it: the alarm, weather and date under the clock "
                            + "open their apps, and taps vibrate.\n\n"
                            + "Trade-off: a double tap that lands on one of them can open it "
                            + "instead of turning the screen off.");

            Object radioClick = listener(loader, CLICK, "onPreferenceClick", new Action() {
                @Override
                public void run(Object pref, Object value) {
                    String key = String.valueOf(Xp.callMethod(pref, "getKey"));
                    ContentResolver cr = ctx.getContentResolver();
                    if (KEY_TAP.equals(key) || KEY_DOUBLE.equals(key)) {
                        writeReal(cr, Feature.DOUBLE_TAP_WAKE, KEY_DOUBLE.equals(key) ? 1 : 0);
                        writeReal(cr, KEY_TAP_GESTURE, 1);
                        Xp.callMethod(mainSwitch, "setChecked", true);
                    } else {
                        writeReal(cr, Feature.LOCK_SHORTCUTS, KEY_STOCK.equals(key) ? 1 : 0);
                    }
                    refresh(group, ctx);
                }
            });
            for (Object p : new Object[]{tap, dbl, modded, stock}) {
                Xp.callMethod(p, "setOnPreferenceClickListener", radioClick);
            }

            int order = (Integer) Xp.callMethod(mainSwitch, "getOrder");
            boolean ordered = order < Integer.MAX_VALUE - 10;
            Class<?> category = categoryClass(loader);
            if (category != null) {
                Object wake = make(category, ctx, KEY_WAKE_GROUP, "Wake the screen", null);
                Object lock = make(category, ctx, KEY_LOCK_GROUP,
                        "Double tap lock screen to sleep", null);
                if (ordered) {
                    Xp.callMethod(wake, "setOrder", order + 1);
                    Xp.callMethod(lock, "setOrder", order + 2);
                }
                Xp.callMethod(group, "addPreference", wake);
                Xp.callMethod(group, "addPreference", lock);
                addInOrder(wake, tap, dbl);
                addInOrder(lock, modded, stock);
            } else {
                if (ordered) {
                    Xp.callMethod(tap, "setOrder", order + 1);
                    Xp.callMethod(dbl, "setOrder", order + 2);
                    Xp.callMethod(modded, "setOrder", order + 3);
                    Xp.callMethod(stock, "setOrder", order + 4);
                }
                for (Object p : new Object[]{tap, dbl, modded, stock}) {
                    Xp.callMethod(group, "addPreference", p);
                }
            }
            Module.log("double-tap-wake: options added (" + radio.getSimpleName()
                    + (category != null ? ", grouped" : "") + ")");
        }
        refresh(group, ctx);
    }

    private static void addInOrder(Object category, Object... prefs) {
        for (int i = 0; i < prefs.length; i++) {
            Xp.callMethod(prefs[i], "setOrder", i);
            Xp.callMethod(category, "addPreference", prefs[i]);
        }
    }

    private static Class<?> categoryClass(ClassLoader loader) {
        try {
            return Class.forName("androidx.preference.PreferenceCategory", false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object make(Class<?> c, Context ctx, String key, String title, String summary) {
        Object p = Xp.newInstance(c, ctx);
        Xp.callMethod(p, "setKey", key);
        Xp.callMethod(p, "setTitle", (CharSequence) title);
        if (summary != null) Xp.callMethod(p, "setSummary", (CharSequence) summary);
        Xp.callMethod(p, "setPersistent", false);
        return p;
    }

    private static void refresh(Object group, Context ctx) {
        boolean master = Feature.on(ctx, Feature.TAP_CHECK);
        boolean d = Feature.on(ctx, Feature.DOUBLE_TAP_WAKE);
        boolean stock = Feature.on(ctx, Feature.LOCK_SHORTCUTS);
        Object tap = find(group, KEY_TAP), dbl = find(group, KEY_DOUBLE);
        Object mod = find(group, KEY_MODDED), stk = find(group, KEY_STOCK);
        check(tap, !d);
        check(dbl, d);
        check(mod, !stock);
        check(stk, stock);
        for (Object p : new Object[]{find(group, KEY_WAKE_GROUP), find(group, KEY_LOCK_GROUP),
                tap, dbl, mod, stk}) {
            visible(p, master);
        }
    }

    private static void visible(Object pref, boolean on) {
        if (pref != null && has(pref, "setVisible", boolean.class)) {
            Xp.callMethod(pref, "setVisible", on);
        }
    }

    private static Object find(Object group, String key) {
        return Xp.callMethod(group, "findPreference", (CharSequence) key);
    }

    private static void check(Object pref, boolean on) {
        if (pref != null) Xp.callMethod(pref, "setChecked", on);
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>,
            java.util.concurrent.ConcurrentHashMap<String, Boolean>> sHas =
            new java.util.concurrent.ConcurrentHashMap<Class<?>,
                    java.util.concurrent.ConcurrentHashMap<String, Boolean>>();

    private static boolean has(Object o, String name, Class<?>... types) {
        Class<?> c = o.getClass();
        java.util.concurrent.ConcurrentHashMap<String, Boolean> byName = sHas.get(c);
        if (byName == null) {
            byName = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
            sHas.put(c, byName);
        }
        String sig = types.length == 0 ? name : name + java.util.Arrays.toString(types);
        Boolean hit = byName.get(sig);
        if (hit == null) {
            boolean found;
            try {
                c.getMethod(name, types);
                found = true;
            } catch (Throwable t) {
                found = false;
            }
            hit = found;
            byName.put(sig, hit);
        }
        return hit;
    }

    private static Class<?> radioClass(ClassLoader loader) throws ClassNotFoundException {
        String[] names = {
                "com.android.settingslib.widget.SelectorWithWidgetPreference",
                "com.android.settingslib.widget.RadioButtonPreference"
        };
        for (String n : names) {
            try {
                return Class.forName(n, false, loader);
            } catch (ClassNotFoundException ignored) { }
        }
        return Class.forName("androidx.preference.CheckBoxPreference", false, loader);
    }

    private static Object listener(ClassLoader loader, String iface, final String method,
                                   final Action action) throws ClassNotFoundException {
        Class<?> type = Class.forName(iface, false, loader);
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method m, Object[] a) {
                String name = m.getName();
                if (method.equals(name)) {
                    try {
                        action.run(a != null && a.length > 0 ? a[0] : null,
                                a != null && a.length > 1 ? a[1] : null);
                    } catch (Throwable t) {
                        Module.log("double-tap-wake: saving failed", t);
                    }
                    return Boolean.TRUE;
                }
                if ("equals".equals(name)) return proxy == a[0];
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                return "PixelSuite.DoubleTapWake";
            }
        });
    }
}
