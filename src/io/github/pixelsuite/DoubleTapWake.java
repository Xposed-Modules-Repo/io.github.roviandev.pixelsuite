package io.github.pixelsuite;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * "Tap or Double Tap to check phone".
 *
 * Double tap to wake (Feature.DOUBLE_TAP_WAKE), in system_server: the stock "Tap to check
 * phone" asks PowerManagerService to wake with reason WAKE_REASON_TAP. A single tap is ignored
 * unless a second one follows quickly. System UI labels a single tap "PULSING_SINGLE_TAP" while
 * the always-on display is pulsing and "NODOZE tap" otherwise (e.g. after a few slow taps), so
 * both count as single taps. The phone's own double tap ("PULSING_DOUBLE_TAP" or "NODOZE
 * doubletap") always wakes, so a real double tap can never be swallowed. The phone's
 * double-tap sensor only works while the stock single tap is on, so doze_tap_gesture stays on
 * in both modes.
 *
 * A "NODOZE tap" comes from the touch chip's own low-power gesture engine. After reporting it,
 * the chip stops sampling and waits for the screen to wake; if we swallow the wake, no later
 * tap (single or double) is detected until the display changes state again. So after
 * swallowing one, we ask System UI for a short always-on-display pulse
 * (com.android.systemui.doze.pulse). The pulse switches the display out of low-power mode and
 * back, which re-arms the chip, and while it lasts taps arrive as PULSING_* like right after
 * the screen turns off: a double tap wakes, a single tap doesn't.
 *
 * Right after a tap wakes the screen, a leftover tap (e.g. the user's last tap of a quick
 * series) would land on the freshly shown lock screen and could open something there, like
 * the next-alarm chip, which asks for the PIN. For WAKE_GUARD_MS after a tap wake, a new touch
 * in the upper part of the lock screen is cancelled (pilferPointers on our gesture monitor); it
 * still counts as a tap for the lock screen double tap. The bottom part (fingerprint sensor,
 * swipe up) is left alone.
 *
 * Lock screen shortcuts: tapping a smartspace card under the clock (next alarm, weather, date)
 * makes System UI report the tap to SmartspaceManagerService (in system_server, so we see it),
 * queue "open it after unlock" and show the fingerprint prompt (AlternateBouncerView). While
 * the lock screen double tap is on and Feature.LOCK_SHORTCUTS is off (the default), such a tap
 * is undone: once its prompt is up, Back closes it, which drops the queued app, so the tap
 * behaves like empty space. Notifications don't go through smartspace and are left alone. While
 * a shortcut tap is being undone, the prompt hiding the fingerprint icon doesn't switch the
 * double tap off, and a second tap is kept off the prompt, so a double tap on a card still
 * sleeps. Turning LOCK_SHORTCUTS on skips all this (the cards work, with the occasional
 * accidental launch from a double tap).
 *
 * Under the same switch, lock screen taps don't vibrate: tapping the clock (which still plays
 * its animation), a card, or empty space makes System UI ask for a short touch haptic. A tap on
 * the lock screen (fingerprint icon showing, so not the PIN pad) counts from finger down until
 * QUIET_AFTER_UP_MS after finger up, and touch-feedback vibrations asked for in that time are
 * dropped in VibratorManagerService. System UI asks within a few ms of the finger landing,
 * sometimes before our monitor has read it, so a System UI touch vibration with no tap on
 * record waits up to DOWN_WAIT_MS for the finger down. The fingerprint sensor takes its touch
 * away from our monitor (ACTION_CANCEL), so the unlock / try-again haptics stay: they don't
 * wait either, since the fingerprint service (in system_server) tells us a finger is on the
 * sensor. Other touches taken away (the back gesture) don't stop the wait for the next tap.
 * Swipes, long presses, the PIN pad, alarms, calls and notifications keep their vibrations
 * too. LOCK_SHORTCUTS on: stock vibration.
 *
 * Closing the prompt doesn't clear the queued action on every System UI build (with the
 * legacy bouncer it sits in the PIN-pad controller until an unlock runs it), and a card tapped
 * by the second tap of a double tap is queued with no prompt at all (the screen is already
 * going to sleep). So every lock screen card tap marks a card launch as queued, and the first
 * activity start System UI makes within LAUNCH_DROP_MS after the next unlock is cancelled
 * (ActivityStarter). A new
 * prompt that isn't a card's (a notification's, say) replaces the queued action, so it clears
 * that memory, as does each unlock once its window has passed.
 *
 * Feature.TAP_CHECK is the master switch (Pixel Suite's "Tap or Double tap to wake"): when off,
 * none of the above runs and the Settings page shows only the stock switch.
 *
 * Double tap lock screen to sleep (Feature.LOCK_SLEEP), in system_server: a read-only touch
 * monitor sees a double tap anywhere on the lock screen. The under-display fingerprint icon is
 * its own window (UdfpsControllerOverlay): it hides while the PIN pad, the fingerprint prompt
 * or the notification shade is up and comes back when you return to the clock, so its
 * visibility switches the double tap off and on again. A long press or a swipe that opens
 * nothing leaves it on. On phones without that window, a swipe, long press or lone single tap
 * switches it off until the screen wakes again. No vibration.
 */
final class DoubleTapWake {

    static final String KEY_TAP_GESTURE = "doze_tap_gesture";
    static final String KEY_DOUBLE_TAP_GESTURE = "doze_pulse_on_double_tap";

    static final String OLD_TITLE = "Tap to check phone";
    static final String NEW_TITLE = "Tap or Double Tap to check phone";

    private static final String KEY_TAP = "pixelsuite_wake_tap";
    private static final String KEY_DOUBLE = "pixelsuite_wake_double_tap";
    private static final String KEY_SLEEP = "pixelsuite_lock_sleep";
    private static final String KEY_SHORTCUTS = "pixelsuite_lock_shortcuts";
    private static final String CLICK = "androidx.preference.Preference$OnPreferenceClickListener";
    private static final String CHANGE = "androidx.preference.Preference$OnPreferenceChangeListener";

    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String PMS = "com.android.server.power.PowerManagerService";
    private static final String POWER_GROUP = "com.android.server.power.PowerGroup";

    /** Max gap between the two taps of a lock screen double tap (finger up to finger down). */
    private static final long DOUBLE_GAP_MS = 400L;
    /** A touch held longer than this is a press, not a tap. */
    private static final long TAP_MAX_MS = 400L;
    private static final int SLEEP_REASON_POWER_BUTTON = 4;
    /** The Tap to check phone page is short; bigger pages (Display > Lock screen) are skipped. */
    private static final int MAX_PAGE_PREFS = 5;

    private static volatile boolean sWakeOn, sSleepOn, sInitDone;
    private static volatile long sLastSingle;
    private static volatile boolean sUdfpsSeen;
    private static volatile int sUdfpsVisibility = -1;
    private static final String UDFPS_WINDOW = "UdfpsControllerOverlay";
    /** Two single-tap events this close together also count as a double tap. */
    private static final long SINGLE_PAIR_MS = 800L;
    /** System UI's doze pulse request (DozeTriggers.PULSE_ACTION). */
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";
    private static final String SYSTEMUI = "com.android.systemui";
    /** Delay before the pulse request, so System UI has finished handling the tap. */
    private static final long PULSE_DELAY_MS = 150L;
    /** After a tap wake: how long new lock screen touches are treated as leftovers. */
    private static final long WAKE_GUARD_MS = 800L;
    /** Leftover touches are cancelled only above this fraction of the screen height. */
    private static final float WAKE_GUARD_TOP = 0.70f;
    /** uptimeMillis of the last wake we let through for a tap, 0 = none. */
    private static volatile long sTapWakeAt;
    /** Fingerprint prompt System UI shows for "unlock to open" (alternate bouncer). */
    private static final String ALT_BOUNCER_WINDOW = "AlternateBouncerView";
    private static int sAltWindowId;
    private static volatile int sAltVisibility = -1;
    /** uptimeMillis when the fingerprint prompt last became visible, 0 = never. */
    private static volatile long sAltShownAt;
    /** Shortcut cards stay tappable when on; off (default) undoes their taps. */
    private static volatile boolean sLockShortcuts;
    /** Master switch, Feature.TAP_CHECK. */
    private static volatile boolean sMasterOn = true;
    /** uptimeMillis of the last smartspace card tap while undoing them, 0 = none. */
    private static volatile long sChipTapAt;
    /** The chip tap whose prompt we already scheduled a close for (worker thread). */
    private static long sHandledChipAt;
    /** The chip tap Back was actually pressed for: at most one Back per tap (worker thread). */
    private static long sBackSentFor;
    /** How long after a card tap its prompt, and the icon hiding, are attributed to it. */
    private static final long CHIP_WINDOW_MS = 1000L;
    /** A prompt counts as the card's only if it appears this soon after the card tap. */
    private static final long CHIP_PROMPT_MAX_MS = 800L;
    /** Wait this long after the prompt appears before Back, so it has input focus. */
    private static final long BACK_DELAY_MS = 80L;
    /** After an unlock, how long a System UI activity start counts as the queued card launch. */
    private static final long LAUNCH_DROP_MS = 1200L;
    /** ActivityManager.START_CANCELED. */
    private static final int START_CANCELED = -96;
    /** A card tap was undone: its "open after unlock" is still queued in System UI. */
    private static volatile boolean sDropArmed;
    /** uptimeMillis of the last device unlock, 0 = none yet. */
    private static volatile long sUnlockedAt;
    private static volatile int sSystemUiUid = -1;
    /** The current or last touch the monitor saw (uptimeMillis): finger down, 0 = none. */
    private static volatile long sTouchDownAt;
    /** Its finger up, 0 while the finger is still down (or the touch was cancelled). */
    private static volatile long sTouchUpAt;
    /** When a touch was last taken away from the monitor (fingerprint sensor), 0 = never. */
    private static volatile long sTouchCancelAt;
    /** The current or last touch is still a plain tap (no swipe, second finger or cancel). */
    private static volatile boolean sTouchTap;
    private static float sTouchX, sTouchY;   // worker thread
    /** Counts finger downs; vibration requests wait on sTouchLock for the next one. */
    private static volatile int sDownSeq;
    private static final Object sTouchLock = new Object();
    /** How long after a tap's finger up its vibrations are still dropped. */
    private static final long QUIET_AFTER_UP_MS = 600L;
    /** Longest wait for our monitor to read a finger down System UI already reacted to. */
    private static final long DOWN_WAIT_MS = 60L;
    /** uptimeMillis the fingerprint sensor last saw a finger, 0 = never. */
    private static volatile long sFingerAt;
    /** After the fingerprint sensor saw a finger, System UI's haptics are the sensor's. */
    private static final long FINGER_QUIET_MS = 2000L;
    private static final String VMS = "com.android.server.vibrator.VibratorManagerService";
    /** VibrationAttributes: usage class mask, the touch-feedback class, accessibility usage. */
    private static final int USAGE_CLASS_MASK = 0x0F;
    private static final int USAGE_CLASS_FEEDBACK = 0x02;
    private static final int USAGE_ACCESSIBILITY = 0x42;
    private static final int USAGE_TOUCH = 0x12;
    private static final String SMARTSPACE_STUB =
            "com.android.server.smartspace.SmartspaceManagerService$SmartspaceManagerStub";
    /** SmartspaceTargetEvent.EVENT_TARGET_INTERACTION: the user tapped a card. */
    private static final int SMARTSPACE_TAP = 1;
    private static int sTapReason = 15;   // PowerManager.WAKE_REASON_TAP in AOSP

    private static Context sCtx;
    private static Object sPwm;
    private static PowerManager sPm;
    private static Handler sWorker;
    private static Object sMonitor;
    private static InputChannel sChannel;
    private static Receiver sReceiver;
    private static int sTouchSlop, sDoubleTapSlop;

    private static Handler sMain;

    private interface Action {
        void run(Object pref, Object value);
    }

    private DoubleTapWake() {}

    /** Not used: System UI does not load the module on this phone. Kept for Module. */
    static void installSystemUi(ClassLoader cl) { }

    // ------------------------------------------------------------------ system_server

    static void installSystem(ClassLoader cl) {
        try {
            sTapReason = PowerManager.class.getField("WAKE_REASON_TAP").getInt(null);
        } catch (Throwable ignored) {
            // Hidden field not reachable: keep the AOSP value.
        }

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
    }

    /**
     * Unlock moments (TrustManagerService tells keystore the device is unlocked) and activity
     * starts (ActivityStarter.executeRequest), to cancel a queued card launch.
     */
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
        for (int i = 1; i <= 15; i++) {   // the binder stub is an anonymous inner class
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

    /**
     * Every vibration request reaches VibratorManagerService through these binder methods
     * (View haptics come straight here or via PhoneWindowManager, which calls in with the
     * app's uid).
     */
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

    /**
     * Records every touch the monitor sees (worker thread), as fast as possible: System UI
     * asks for its touch-down vibration only a few ms after the finger lands. No checks here;
     * whether the touch counts is decided when a vibration arrives. A swipe, a second finger or
     * another window taking the touch (the fingerprint sensor: ACTION_CANCEL) makes it no
     * longer a tap.
     */
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
                sTouchCancelAt = SystemClock.uptimeMillis();
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

    /** The current or last touch is a tap whose vibrations are still dropped at {@code now}. */
    private static boolean tapActive(long now) {
        long down = sTouchDownAt;   // read before up: DOWN clears up first, then sets down
        long up = sTouchUpAt;
        if (!sTouchTap || down == 0) return false;
        if (up == 0) return now - down <= TAP_MAX_MS;   // finger still down, not a long press
        return up >= down && up - down <= TAP_MAX_MS && now - up <= QUIET_AFTER_UP_MS;
    }

    /**
     * No finger is down (the last touch ended) and the fingerprint sensor hasn't seen a finger
     * lately: a vibration now may be for a touch whose DOWN we haven't read yet.
     */
    private static boolean mayAwaitDown(long now) {
        long down = sTouchDownAt, up = sTouchUpAt, cancel = sTouchCancelAt;
        boolean ended = down == 0 || up != 0 || cancel >= down;
        if (!ended) return false;   // a swipe or long press is in progress
        long finger = sFingerAt;
        return finger == 0 || now - finger > FINGER_QUIET_MS;
    }

    /**
     * The fingerprint service (system_server) hears about a finger on the sensor: from System
     * UI (onPointerDown) and from the sensor itself (onAcquired). Either marks the moment, so
     * the unlock / try-again haptics that follow never wait for a tap.
     */
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
                // Fingerprint only: face unlock reports acquisitions all the time on the lock
                // screen and must not count.
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

    /** Waits up to {@code max} ms for a new finger down after {@code seq}. */
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

    /** On the lock screen proper: fingerprint icon showing (not the PIN pad or a prompt). */
    private static boolean quietPlace() {
        return (sUdfpsVisibility == android.view.View.VISIBLE || chipTapRecent())
                && lockScreenShowing();
    }

    /**
     * A vibration request (binder thread, before VibratorManagerService takes its lock), while
     * card taps are undone. Drops touch-feedback vibrations (and ones without a usage) asked for
     * during a lock screen tap; anything else (alarm, ringtone, notification, accessibility)
     * goes through. System UI can ask a few ms before our monitor has read the finger down, so
     * a System UI touch vibration with no tap on record waits up to DOWN_WAIT_MS for it.
     */
    private static void maybeQuietVibration(Xp.Param param, String method) {
        int seq = sDownSeq;   // before tapActive, so a DOWN arriving in between isn't missed
        boolean haptic = method.startsWith("performHapticFeedback");
        String pkg = null;
        int usage = haptic ? USAGE_TOUCH : 0;   // vibrate() without attributes: USAGE_UNKNOWN
        int constant = -1, ints = 0;
        for (Object a : param.args) {
            if (a instanceof String) {
                if (pkg == null) pkg = (String) a;
            } else if (a instanceof Integer) {
                if (++ints == 3 && haptic) constant = (Integer) a;   // uid, deviceId, constant
            } else if (!haptic && a != null
                    && "android.os.VibrationAttributes".equals(a.getClass().getName())) {
                Object u = Xp.callMethod(a, "getUsage");
                if (u instanceof Integer) usage = (Integer) u;
            }
        }
        boolean touch = usage == 0 || ((usage & USAGE_CLASS_MASK) == USAGE_CLASS_FEEDBACK
                && usage != USAGE_ACCESSIBILITY);
        long now = SystemClock.uptimeMillis();
        String what = pkg + " " + method + (constant >= 0 ? " constant=" + constant : "")
                + " usage=0x" + Integer.toHexString(usage);
        if (!touch) {
            if (tapActive(now) && quietPlace()) {
                Module.log("double-tap-wake: lock screen tap, vibration kept (" + what + ")");
            }
            return;
        }
        long waited = -1;
        if (!tapActive(now)) {
            if (!SYSTEMUI.equals(pkg) || !mayAwaitDown(now) || !quietPlace()) return;
            if (!awaitDown(seq, DOWN_WAIT_MS)) return;   // no touch: not a tap's vibration
            long after = SystemClock.uptimeMillis();
            waited = after - now;
            if (!tapActive(after)) return;
        }
        if (!quietPlace()) return;
        param.setResult(null);
        Module.log("double-tap-wake: lock screen tap vibration blocked (" + what
                + (waited >= 0 ? ", waited " + waited + " ms for the touch" : "") + ")");
    }

    /** The device was just unlocked (any method). */
    private static void onUnlocked() {
        final long at = SystemClock.uptimeMillis();
        sUnlockedAt = at;
        Handler h = sWorker;
        if (h == null || !sDropArmed) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                // The queued action runs right at unlock; past the window it's gone either way.
                if (sDropArmed && sUnlockedAt == at) {
                    sDropArmed = false;
                    Module.log("double-tap-wake: no queued card launch after unlock");
                }
            }
        }, LAUNCH_DROP_MS + 100);
    }

    /** ActivityStarter.executeRequest, under the WM lock: cheap checks only. */
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
            if (opts != null) Xp.callMethod(opts, "abort");   // cancels a pending launch animation
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

    /** Sees lock screen card taps (alarm, weather, date) as System UI reports them. */
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
        // sSleepOn already includes the master switch; without the touch monitor there is no
        // lock screen double tap to protect.
        return sSleepOn && !sLockShortcuts && sReceiver != null;
    }

    private static boolean chipTapRecent() {
        long t = sChipTapAt;
        return t != 0 && SystemClock.uptimeMillis() - t <= CHIP_WINDOW_MS;
    }

    /**
     * A lock screen card was tapped (binder thread, called by System UI). System UI has queued
     * "open after unlock" the moment it reports the tap, even if its prompt never shows (the
     * tap was the second of a double tap that already put the screen to sleep). So the queued
     * launch is marked for the launch guard right away, not only once Back closed a prompt.
     */
    private static void onChipTap() {
        if (!undoingChips()) return;
        if (android.os.Binder.getCallingUid() != sSystemUiUid) return;   // launcher's At a Glance
        if (!lockScreenShowing()) return;
        long now = SystemClock.uptimeMillis();
        long prev = sChipTapAt;
        if (prev != 0 && now - prev < 300) return;   // same tap reported twice
        sChipTapAt = now;
        // Trusted (Smart Lock / watch unlock): System UI opens the card at once, nothing queued.
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

    /**
     * Once both the card tap and its fingerprint prompt have been seen (in either order), press
     * Back once to close the prompt; that also drops the queued "open after unlock". Worker
     * thread.
     */
    private static void maybeUndoChipTap() {
        long tap = sChipTapAt;
        if (tap == 0 || tap == sHandledChipAt || !chipTapRecent()) return;
        if (!undoingChips() || !lockScreenShowing()) {
            sChipTapAt = 0;   // not a lock screen card tap we undo
            return;
        }
        long shown = sAltShownAt;
        // Only a prompt that appears right around the card tap is the card's.
        if (shown == 0 || shown < tap - 300 || shown > tap + CHIP_PROMPT_MAX_MS) return;
        sHandledChipAt = tap;
        long wait = Math.max(0, shown + BACK_DELAY_MS - SystemClock.uptimeMillis());
        sWorker.postDelayed(backFor(tap, "lock screen card tap undone"), wait);
    }

    /** One Back per card tap, only while the lock screen and its prompt are still up. */
    private static Runnable backFor(final long tap, final String why) {
        return new Runnable() {
            @Override
            public void run() {
                if (sBackSentFor == tap) return;
                if (!lockScreenShowing() || sAltVisibility != android.view.View.VISIBLE) return;
                sBackSentFor = tap;
                pressBack();
                sDropArmed = true;   // its "open after unlock" may still be queued
                Module.log("double-tap-wake: " + why);
            }
        };
    }

    /**
     * After a card tap's window, if the fingerprint icon is still hidden on the lock screen
     * (the PIN pad, or a prompt we didn't close), switch the double tap off as usual.
     */
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

    /** Watches the fingerprint icon window's visibility (PIN pad open = icon hidden). */
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
                                    // Not a card's prompt (a notification's, say): its action
                                    // replaced the queued card launch, which must not be blocked.
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
            final Receiver r = sReceiver;
            if (h == null || r == null) return;
            h.post(new Runnable() {
                @Override
                public void run() {
                    r.onFingerprintIcon(visible);
                }
            });
        } catch (Throwable ignored) { }
    }

    private static volatile java.lang.reflect.Field sAttrsField;

    /** WindowState.mAttrs, with the field looked up once (runs on every relayout). */
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
                Module.log("double-tap-wake: master=" + sMasterOn + " wake=" + sWakeOn
                        + " lockSleep=" + sSleepOn + " shortcuts=" + sLockShortcuts);
            }
        };
        ContentResolver cr = ctx.getContentResolver();
        cr.registerContentObserver(Settings.Secure.getUriFor(Feature.TAP_CHECK), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(Feature.DOUBLE_TAP_WAKE), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(Feature.LOCK_SLEEP), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(Feature.LOCK_SHORTCUTS), false, obs);

        sWorker.post(new Runnable() {
            @Override
            public void run() {
                repairTapSensor();
            }
        });

        try {
            Object im = ctx.getSystemService(Context.INPUT_SERVICE);
            sMonitor = Xp.callMethod(im, "monitorGestureInput",
                    "PixelSuiteLockTap", Display.DEFAULT_DISPLAY);
            sChannel = (InputChannel) Xp.callMethod(sMonitor, "getInputChannel");
            sReceiver = new Receiver(sChannel, t.getLooper());
        } catch (Throwable e) {
            Module.log("double-tap-wake: touch monitor unavailable", e);
        }
        sInitDone = true;
        Module.log("double-tap-wake: initialised, wake=" + sWakeOn + " lockSleep=" + sSleepOn
                + " monitor=" + (sReceiver != null));
    }

    private static void readFlags() {
        sMasterOn = Feature.on(sCtx, Feature.TAP_CHECK);
        sWakeOn = sMasterOn && Feature.on(sCtx, Feature.DOUBLE_TAP_WAKE);
        sSleepOn = sMasterOn && Feature.on(sCtx, Feature.LOCK_SLEEP);
        sLockShortcuts = Feature.on(sCtx, Feature.LOCK_SHORTCUTS);
    }

    /** Double tap to wake needs the stock tap sensor on (the double-tap sensor depends on it). */
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

    /**
     * Runs inside PowerManagerService's lock: no settings reads, no blocking. Every wake re-arms
     * the lock screen double tap.
     */
    private static void onWakeRequest(Xp.Param param) {
        Handler h = sWorker;
        final Receiver r = sReceiver;
        if (h != null && r != null) {
            h.post(new Runnable() {
                @Override
                public void run() {
                    r.rearm();
                }
            });
        }
        int reason = -1;
        String details = null;
        for (Object a : param.args) {
            if (reason == -1 && a instanceof Integer) reason = (Integer) a;
            if (details == null && a instanceof String) details = (String) a;
        }
        if (reason != sTapReason || !sWakeOn) return;
        long now = SystemClock.uptimeMillis();
        if (isSingleTap(details)) {
            if (sLastSingle != 0 && now - sLastSingle <= SINGLE_PAIR_MS) {
                sLastSingle = 0;
                sTapWakeAt = now;
                Module.log("double-tap-wake: second tap, waking (" + details + ")");
            } else {
                sLastSingle = now;
                param.setResult(null);
                Module.log("double-tap-wake: single tap ignored (" + details + ")");
                if (isGestureEngineTap(details)) requestPulse();
            }
        } else {
            sLastSingle = 0;
            sTapWakeAt = now;
            Module.log("double-tap-wake: waking (" + details + ")");
        }
    }

    /**
     * A single tap, as System UI labels it: "...PULSING_SINGLE_TAP" (tap while the always-on
     * display pulses) or "...NODOZE tap" (tap sensor wake-up outside a pulse). Double taps are
     * "...PULSING_DOUBLE_TAP" / "...NODOZE doubletap" and never match.
     */
    static boolean isSingleTap(String details) {
        if (details == null) return false;
        if (details.contains("SINGLE_TAP")) return true;
        return details.endsWith(" tap");
    }

    /** A tap reported by the touch chip's low-power gesture engine (not a pulsing-screen tap). */
    static boolean isGestureEngineTap(String details) {
        return details != null && details.contains("NODOZE");
    }

    /**
     * Asks System UI for a short always-on-display pulse, off PowerManagerService's lock. This
     * re-arms the touch chip after we swallowed one of its taps (see the class comment).
     */
    private static void requestPulse() {
        Handler h = sWorker;
        final Context ctx = sCtx;
        if (h == null || ctx == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(PULSE_ACTION).setPackage(SYSTEMUI);
                    // UserHandle.ALL is a system API, not in the android-23 compile jar.
                    UserHandle all = (UserHandle) UserHandle.class.getField("ALL").get(null);
                    ctx.sendBroadcastAsUser(i, all);
                    Module.log("double-tap-wake: pulse requested to re-arm taps");
                } catch (Throwable t) {
                    Module.log("double-tap-wake: pulse request failed", t);
                }
            }
        }, PULSE_DELAY_MS);
    }

    // ------------------------------------------------------------------ lock screen taps

    private static final class Receiver extends InputEventReceiver {
        private boolean mArmed = true;
        private boolean mIgnore, mTap;
        /** We cancelled this touch for the lock screen: its movement can't open anything. */
        private boolean mPilfered;
        private long mDownAt, mPrevUpAt;
        private float mDownX, mDownY, mPrevX, mPrevY;

        Receiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent) {
                    trackQuietTap((MotionEvent) event);
                    if (sSleepOn || sWakeOn) onTouch((MotionEvent) event);
                }
            } catch (Throwable t) {
                Module.log("double-tap-wake: touch handling failed", t);
            } finally {
                finishInputEvent(event, false);   // read-only: never block input
            }
        }

        /** Screen woke: start fresh. */
        void rearm() {
            mArmed = true;
            mPrevUpAt = 0;
            sChipTapAt = 0;
        }

        /** Fingerprint icon window shown (back at the clock) or hidden (PIN pad / prompt up). */
        void onFingerprintIcon(boolean visible) {
            if (!sSleepOn) return;
            // The prompt of a card tap we're undoing hides the icon and brings it back: keep
            // the double tap on and keep a pending first tap.
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

        /**
         * A swipe, long press or second finger switches the double tap off only on phones
         * without the fingerprint icon window. With it, anything those open over the lock
         * screen (PIN pad, notification shade, the long-press "Customize lock screen" button
         * once tapped) hides the icon, which switches the double tap off and back on by itself;
         * a long press or a small swipe that opens nothing leaves it on. A touch we cancelled
         * (a leftover after a tap wake) never reached the lock screen, so it never counts.
         */
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
                    // A leftover from the wake taps can't open anything on the lock screen, but
                    // it still counts as the first tap of a lock screen double tap.
                    if (!mIgnore) mPilfered = cancelIfLeftoverAfterTapWake(ev);
                    if (!sSleepOn) mIgnore = true;
                    if (screenOn && !lock) {   // unlocked: start clean next time
                        mArmed = true;
                        mPrevUpAt = 0;
                    }
                    if (mIgnore) return;
                    long down = ev.getEventTime();
                    float px = ev.getX() - mPrevX, py = ev.getY() - mPrevY;
                    if (mPrevUpAt != 0 && down - mPrevUpAt <= DOUBLE_GAP_MS
                            && px * px + py * py <= (float) sDoubleTapSlop * sDoubleTapSlop
                            && undoingChips() && chipTapRecent()) {
                        // Second tap after a card tap: its prompt may be up; tapping it would
                        // open the PIN pad. Keep this tap off it (it still counts for us).
                        if (pilfer("second tap kept off the card prompt")) mPilfered = true;
                    }
                    if (mPrevUpAt != 0 && down - mPrevUpAt > DOUBLE_GAP_MS) {
                        // The fingerprint icon tells us when the PIN pad opens; without it,
                        // a lone tap (which may open the PIN pad) switches the double tap off.
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
                        mPrevUpAt = 0;   // not a tap: no double tap across it
                        if (offWithoutIcon()) disarm("two fingers");
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!mIgnore && mTap && (Math.abs(ev.getX() - mDownX) > sTouchSlop
                            || Math.abs(ev.getY() - mDownY) > sTouchSlop)) {
                        mTap = false;
                        mPrevUpAt = 0;   // not a tap: no double tap across it
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
                        mPrevUpAt = 0;   // not a tap: no double tap across it
                        if (offWithoutIcon()) disarm("long press");
                        return;
                    }
                    float dx = mDownX - mPrevX, dy = mDownY - mPrevY;
                    boolean close = dx * dx + dy * dy <= (float) sDoubleTapSlop * sDoubleTapSlop;
                    if (mPrevUpAt != 0 && close && mDownAt - mPrevUpAt <= DOUBLE_GAP_MS) {
                        mPrevUpAt = 0;
                        if (mArmed) {
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

    /**
     * A touch that starts within WAKE_GUARD_MS of a tap wake is a leftover from the wake taps.
     * In the upper part of the screen it is cancelled for the lock screen, so it can't open the
     * alarm chip, a notification and so on. The bottom part (fingerprint, swipe up) is kept.
     * Returns true if the touch was cancelled.
     */
    private static boolean cancelIfLeftoverAfterTapWake(MotionEvent ev) {
        long wakeAt = sTapWakeAt;
        if (wakeAt == 0) return false;
        long since = ev.getEventTime() - wakeAt;
        if (since < 0 || since > WAKE_GUARD_MS) return false;
        int height = screenHeight();
        if (height > 0 && ev.getY() > height * WAKE_GUARD_TOP) return false;
        return pilfer("leftover tap " + since + " ms after tap wake cancelled");
    }

    /**
     * Cancels the current touch for every window (our monitor keeps receiving it). Returns
     * true if it was cancelled.
     */
    private static boolean pilfer(String why) {
        try {
            Xp.callMethod(sMonitor, "pilferPointers");
            Module.log("double-tap-wake: " + why);
            return true;
        } catch (Throwable t) {
            Module.log("double-tap-wake: could not cancel touch (" + why + ")", t);
            return false;
        }
    }

    /** Presses Back: closes the fingerprint prompt and clears its queued "open after unlock". */
    private static void pressBack() {
        try {
            Object im = sCtx.getSystemService(Context.INPUT_SERVICE);
            long now = SystemClock.uptimeMillis();
            int[] actions = { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP };
            for (int action : actions) {
                KeyEvent ev = new KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM,   // not FLAG_VIRTUAL_HARD_KEY: that vibrates
                        InputDevice.SOURCE_KEYBOARD);
                Xp.callMethod(im, "injectInputEvent", ev, 0 /* async */);
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
            // The first tap hit a card: close its prompt (drops the queued app), then sleep.
            // Give the prompt time to appear and take focus first.
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

    /** The device needs the PIN or fingerprint to unlock (not unlocked by a trusted device). */
    private static boolean deviceLocked() {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    sCtx.getSystemService(Context.KEYGUARD_SERVICE);
            return km == null || km.isDeviceLocked();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Lock screen visible and not covered by an app (camera, alarm, call). */
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

    // ------------------------------------------------------------------ Settings

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

    /** Master switch, read in the Settings process (throttled: the rename hook is hot). */
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
        if (!has(p, "setChecked", boolean.class)) return false;   // links to the page are not
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
                    "Tap the screen twice to wake it. A single tap does nothing.");
            Object sleep = make(switchClass(loader), ctx, KEY_SLEEP,
                    "Double tap lock screen to sleep",
                    "Double-tap the lock screen to turn the screen off. Never while the PIN pad "
                            + "is open.");
            Object shortcuts = make(switchClass(loader), ctx, KEY_SHORTCUTS,
                    "Lock screen shortcuts",
                    "Keep the alarm, weather and date under the clock tappable. Off: tapping them "
                            + "does nothing, like empty space, and taps on the lock screen "
                            + "(the clock too) don't vibrate. On: they open and vibrate as "
                            + "usual, but a double tap to sleep on them can sometimes open the "
                            + "alarm or weather.");

            Object radioClick = listener(loader, CLICK, "onPreferenceClick", new Action() {
                @Override
                public void run(Object pref, Object value) {
                    boolean d = KEY_DOUBLE.equals(String.valueOf(Xp.callMethod(pref, "getKey")));
                    ContentResolver cr = ctx.getContentResolver();
                    writeReal(cr, Feature.DOUBLE_TAP_WAKE, d ? 1 : 0);
                    // Picking a mode also turns the feature on (both modes need the tap sensor).
                    writeReal(cr, KEY_TAP_GESTURE, 1);
                    Xp.callMethod(mainSwitch, "setChecked", true);
                    refresh(group, ctx);
                }
            });
            Object sleepChange = listener(loader, CHANGE, "onPreferenceChange", new Action() {
                @Override
                public void run(Object pref, Object value) {
                    Settings.Secure.putInt(ctx.getContentResolver(), Feature.LOCK_SLEEP,
                            Boolean.TRUE.equals(value) ? 1 : 0);
                    refresh(group, ctx);
                }
            });
            Object shortcutsChange = listener(loader, CHANGE, "onPreferenceChange", new Action() {
                @Override
                public void run(Object pref, Object value) {
                    Settings.Secure.putInt(ctx.getContentResolver(), Feature.LOCK_SHORTCUTS,
                            Boolean.TRUE.equals(value) ? 1 : 0);
                }
            });
            Xp.callMethod(tap, "setOnPreferenceClickListener", radioClick);
            Xp.callMethod(dbl, "setOnPreferenceClickListener", radioClick);
            Xp.callMethod(sleep, "setOnPreferenceChangeListener", sleepChange);
            Xp.callMethod(shortcuts, "setOnPreferenceChangeListener", shortcutsChange);

            int order = (Integer) Xp.callMethod(mainSwitch, "getOrder");
            if (order < Integer.MAX_VALUE - 10) {
                Xp.callMethod(tap, "setOrder", order + 1);
                Xp.callMethod(dbl, "setOrder", order + 2);
                Xp.callMethod(sleep, "setOrder", order + 3);
                Xp.callMethod(shortcuts, "setOrder", order + 4);
            }
            Xp.callMethod(group, "addPreference", tap);
            Xp.callMethod(group, "addPreference", dbl);
            Xp.callMethod(group, "addPreference", sleep);
            Xp.callMethod(group, "addPreference", shortcuts);
            Module.log("double-tap-wake: options added (" + radio.getSimpleName() + ")");
        }
        refresh(group, ctx);
    }

    private static Object make(Class<?> c, Context ctx, String key, String title, String summary) {
        Object p = Xp.newInstance(c, ctx);
        Xp.callMethod(p, "setKey", key);
        Xp.callMethod(p, "setTitle", (CharSequence) title);
        Xp.callMethod(p, "setSummary", (CharSequence) summary);
        Xp.callMethod(p, "setPersistent", false);
        return p;
    }

    private static void refresh(Object group, Context ctx) {
        boolean master = Feature.on(ctx, Feature.TAP_CHECK);
        boolean d = Feature.on(ctx, Feature.DOUBLE_TAP_WAKE);
        boolean sleep = Feature.on(ctx, Feature.LOCK_SLEEP);
        Object tap = find(group, KEY_TAP), dbl = find(group, KEY_DOUBLE);
        Object slp = find(group, KEY_SLEEP), cuts = find(group, KEY_SHORTCUTS);
        check(tap, !d);
        check(dbl, d);
        check(slp, sleep);
        check(cuts, Feature.on(ctx, Feature.LOCK_SHORTCUTS));
        // Pixel Suite's "Tap or Double tap to wake" off: the page is back to stock.
        for (Object p : new Object[]{tap, dbl, slp, cuts}) visible(p, master);
        // The shortcuts option only matters while the lock screen double tap is on.
        if (cuts != null) Xp.callMethod(cuts, "setEnabled", sleep);
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

    private static boolean has(Object o, String name, Class<?>... types) {
        try {
            o.getClass().getMethod(name, types);
            return true;
        } catch (Throwable t) {
            return false;
        }
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

    private static Class<?> switchClass(ClassLoader loader) throws ClassNotFoundException {
        try {
            return Class.forName("androidx.preference.SwitchPreferenceCompat", false, loader);
        } catch (ClassNotFoundException e) {
            return Class.forName("androidx.preference.SwitchPreference", false, loader);
        }
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
