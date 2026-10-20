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

import java.lang.reflect.Method;

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
 * in the upper part of the lock screen is cancelled (pilferPointers on our gesture monitor). A
 * touch within LEFTOVER_COUNT_MS of the wake (the wake taps' own leftover or overshoot) also
 * doesn't count toward a lock screen double tap, so it can't put the phone straight back to
 * sleep; later ones do, so a quick double tap to sleep right after waking still works. The
 * bottom part (fingerprint sensor, swipe up) is not cancelled.
 *
 * Lock screen behavior, "Modded" (Feature.LOCK_SHORTCUTS = 0, the default): tapping a
 * smartspace card under the clock (next alarm, weather, date) makes System UI report the tap
 * to SmartspaceManagerService (in system_server, so we see it), queue "open it after unlock"
 * and show the fingerprint prompt (AlternateBouncerView). Such a tap is undone: once its prompt
 * is up, Back closes it, which drops the queued app, so the tap behaves like empty space.
 * Notifications don't go through smartspace and are left alone. While a card tap is being
 * undone, the prompt hiding the fingerprint icon doesn't switch the double tap off, and a
 * second tap is kept off the prompt, so a double tap on a card still sleeps. "Stock"
 * (LOCK_SHORTCUTS = 1) skips all this: the cards work, with the occasional accidental launch
 * from a double tap.
 *
 * In Modded, lock screen taps don't vibrate either: tapping the clock (which still plays
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
 * too. Stock: stock vibration.
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
 * none of the above or below runs and the Settings page shows only the stock switch. When on,
 * double tap the lock screen to sleep is always on. With double tap to wake, a double tap
 * wakes the low-power always-on display too: with the phone in the hand the touchscreen stays
 * fully on, so both taps are real touches (see HeldWatch); lying still, the second tap is felt
 * by the motion sensor (see installAodTouch), with one more tap as the fallback.
 *
 * Double tap lock screen to sleep, in system_server: a read-only touch
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


    private static final String KEY_TAP = "pixelsuite_wake_tap";

    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String PMS = "com.android.server.power.PowerManagerService";
    /** android.view.Display screen states. */
    private static final int STATE_ON = 2, STATE_DOZE = 3, STATE_DOZE_SUSPEND = 4;
    private static final String POWER_GROUP = "com.android.server.power.PowerGroup";

    /** Max gap between the two taps of a lock screen double tap (finger up to finger down). */
    private static final long DOUBLE_GAP_MS = 400L;
    /** A touch held longer than this is a press, not a tap. */
    private static final long TAP_MAX_MS = 400L;
    private static final int SLEEP_REASON_POWER_BUTTON = 4;

    private static volatile boolean sWakeOn, sSleepOn, sInitDone;
    private static volatile long sLastSingle;
    private static volatile boolean sUdfpsSeen;
    private static volatile int sUdfpsVisibility = -1;
    private static final String UDFPS_WINDOW = "UdfpsControllerOverlay";
    /**
     * Two single-tap events this close together also count as a double tap. On the low-power
     * always-on display that is tap 1 and a later tap the touchscreen caught (installAodTouch).
     */
    private static final long SINGLE_PAIR_MS = 1000L;
    /**
     * Backup double tap (see Receiver.onDozeTouch): how long after System UI's single tap our
     * own double tap still counts, and how long we give System UI to wake the phone itself.
     */
    private static final long BACKUP_PAIR_MS = 1000L;
    private static final long BACKUP_WAIT_MS = 150L;
    private static final String BACKUP_DETAILS = "io.github.pixelsuite:DOUBLE_TAP";
    /**
     * A first tap that moved a little (a quick, slightly sliding finger; touch jitter while
     * charging): System UI doesn't take it as a tap, so the real second tap arrives as a
     * single tap and is swallowed. Our monitor sees it: a touch up to TAP_MAX_MS long that moved
     * at most LOOSE_SLOP_TIMES the touch slop, then the next touch starting within
     * DOUBLE_GAP_MS close by. If System UI then reports that next touch as a single tap (so it
     * accepted it: not in a pocket, not a false touch) within LOOSE_REPORT_MS of its finger
     * down, the two are a double tap and it wakes. Measured: while charging, a double tap
     * whose first touch was "moved" left the phone asleep until a third tap.
     */
    private static final float LOOSE_SLOP_TIMES = 4f;
    private static final long LOOSE_REPORT_MS = TAP_MAX_MS + 200L;
    /** Last short touch while asleep (uptimeMillis of its finger up, 0 = none) and where. */
    private static volatile long sLooseUpAt;
    private static volatile float sLooseX, sLooseY;
    /** Finger down of a touch that pairs with the last short touch (0 = none). */
    private static volatile long sLoosePairAt;
    /** System UI's doze pulse request (DozeTriggers.PULSE_ACTION). */
    private static final String PULSE_ACTION = "com.android.systemui.doze.pulse";
    private static final String SYSTEMUI = "com.android.systemui";
    /** Delay before the pulse request, so System UI has finished handling the tap. */
    private static final long PULSE_DELAY_MS = 150L;
    /** After a tap wake: how long new lock screen touches are treated as leftovers. */
    private static final long WAKE_GUARD_MS = 800L;
    /**
     * After a tap wake: touches starting this soon are the wake taps' own leftovers (a tap
     * after the one that woke, an overshoot) and don't count toward a lock screen double tap.
     * Measured: leftovers start 70-300 ms after the wake, a deliberate double tap to sleep
     * right after waking 440 ms or later.
     */
    private static final long LEFTOVER_COUNT_MS = 350L;
    /** Leftover touches are cancelled only above this fraction of the screen height. */
    private static final float WAKE_GUARD_TOP = 0.70f;
    /** uptimeMillis of the last wake we let through for a tap, 0 = none. */
    private static volatile long sTapWakeAt;
    /** uptimeMillis of the last screen-off / screen-on broadcast, 0 = none yet. */
    private static volatile long sScreenOffAt, sScreenOnAt;
    /** Fingerprint prompt System UI shows for "unlock to open" (alternate bouncer). */
    private static final String ALT_BOUNCER_WINDOW = "AlternateBouncerView";
    private static int sAltWindowId;
    private static volatile int sAltVisibility = -1;
    /** uptimeMillis when the fingerprint prompt last became visible, 0 = never. */
    private static volatile long sAltShownAt;
    /** Lock screen "Stock behavior": cards stay tappable. Off (Modded, default) undoes their taps. */
    private static volatile boolean sLockShortcuts;
    /** Master switch, Feature.TAP_CHECK. */
    private static volatile boolean sMasterOn = true;
    /** Charging: the always-on display's touchscreen stays fully on (it costs nothing then). */
    private static volatile boolean sCharging;
    /** What the always-on touch hook last did (1 = touchscreen fully on), -1 = nothing yet. */
    private static volatile int sAodTouchLast = -1;
    /** End (uptimeMillis) of the touch window after a first tap on the always-on display, 0 = none. */
    private static volatile long sWindowUntil;
    /** How long the touchscreen stays fully on after a first tap on the always-on display. */
    private static final long TOUCH_WINDOW_MS = 3000L;
    /**
     * A tap System UI reports this long after the window closed still pairs with tap 1: it
     * reports on finger up, so a tap that started inside the window can be reported just after.
     */
    private static final long WINDOW_PAIR_GRACE_MS = 600L;
    /** While a finger that landed in the window is still down, closing waits (steps, max). */
    private static final long WINDOW_HOLD_STEP_MS = 250L;
    private static final int WINDOW_HOLD_STEPS = 4;
    /** Tap 1 of the current or last touch window (sLastSingle value) and its pairing deadline. */
    private static volatile long sWindowFirstAt, sPairUntil;
    private static volatile PowerManager.WakeLock sWindowWl;
    /** Last always-on display request from System UI (unedited), to re-apply it on changes. */
    private static volatile Object sAodPms;
    private static volatile Method sAodMethod;
    private static volatile Object[] sAodArgs;
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
    private static int sBiometricReason = 17;   // PowerManager.WAKE_REASON_BIOMETRIC in AOSP

    private static Context sCtx;
    private static Object sPwm;
    private static PowerManager sPm;
    private static Handler sWorker;
    /**
     * The lock screen touch monitor. Every touch on the phone is copied to it, but it is only
     * needed while the lock screen can show, so it exists from screen-off (or the lock screen
     * appearing) until the first touch after the phone is unlocked. Created and removed on the
     * worker thread; read from others.
     */
    private static volatile Object sMonitor;
    private static volatile InputChannel sChannel;
    private static volatile Receiver sReceiver;
    private static int sMonitorFailures;   // worker thread
    private static final int MAX_MONITOR_FAILURES = 3;
    private static int sTouchSlop, sDoubleTapSlop;

    private DoubleTapWake() {}

    // ------------------------------------------------------------------ system_server

    static void installSystem(ClassLoader cl) {
        try {
            sTapReason = PowerManager.class.getField("WAKE_REASON_TAP").getInt(null);
        } catch (Throwable ignored) {
            // Hidden field not reachable: keep the AOSP value.
        }
        try {
            sBiometricReason = PowerManager.class.getField("WAKE_REASON_BIOMETRIC").getInt(null);
        } catch (Throwable ignored) { }

        int hooks = 0;
        try {
            hooks += Xp.hookAllMethods(Xp.findClass(PWM, cl), "systemBooted", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    try {
                        init(param.thisObject,
                                (Context) Xp.getObjectField(param.thisObject, "mContext"));
                    } catch (Throwable t) {
                        Module.log("double-tap-wake: init failed", t);
                    }
                }
            }).size();
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
        if (n == 0) Module.log("double-tap-wake: wake hook unavailable");
        hooks += n;
        hooks += installWindowWatch(cl);
        hooks += installSmartspaceWatch(cl);
        hooks += installLaunchGuard(cl);
        hooks += installTapQuiet(cl);
        hooks += installFingerWatch(cl);
        hooks += installAodTouch(cl);
        hooks += installShadeGuard(cl);
        hooks += installUnlockTouchEnd(cl);
        Module.log("double-tap-wake: " + hooks + " hooks");
    }

    /** Finger-down time of the touch we last ended at a fingerprint unlock (one cancel each). */
    private static volatile long sEndedTouchAt;

    /**
     * Prevents the shade flash at its source. Unlocking very soon after locking, the finger on
     * the sensor is a touch on System UI's NotificationShade window (the fingerprint overlay
     * isn't up yet), and our monitor holds it too. System UI switches from the always-on
     * display to unlocked with that finger still down; ~150 ms later the window drops the
     * touch, System UI gets ACTION_CANCEL in the unlocked state and finishes it as a pull-down
     * (Expand). So at fingerprint success, in system_server before System UI is told, a touch
     * that is still down is cancelled (pilferPointers): System UI gets the cancel while still
     * dozing, where it opens nothing. The biometric wake request (System UI's own, right after
     * it handled the unlock) repeats the check on the worker thread, in case the first one ran
     * before our monitor had read the finger down; it usually still lands before the doze
     * ends.
     *
     * Locking and unlocking within a few seconds, Android often doesn't show the lock screen
     * at all (no keyguard while the phone is still "recently unlocked"): the always-on display
     * is then System UI's shade scene, and the fingerprint just wakes the phone. The earlier
     * version only acted with the lock screen showing, so it never ran in exactly this case.
     * What matters is that the phone is asleep (no app can be using that touch); while awake,
     * only the lock screen itself qualifies, never an app over it (camera, Wallet, a
     * fingerprint prompt). The shade guard stays as a fallback.
     */
    private static int installUnlockTouchEnd(ClassLoader cl) {
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
        if (n == 0) Module.log("double-tap-wake: unlock touch end unavailable (shade guard only)");
        return n;
    }

    /**
     * Fingerprint accepted (biometric thread, {@code wakeAt} 0), or the phone waking for it
     * (worker thread, {@code wakeAt} = the wake request's uptimeMillis): cancel the finger if
     * our monitor still has it.
     */
    private static void endTouchAtUnlock(long wakeAt) {
        if (sMonitor == null) return;
        long down = sTouchDownAt, up = sTouchUpAt, cancel = sTouchCancelAt;
        if (down == 0 || up != 0 || cancel >= down) return;   // no finger down on our monitor
        if (down == sEndedTouchAt) return;                    // already done (base + override)
        // Asleep (always-on display or screen off): no app can be using this touch, as long as
        // it started after the screen went off (never a finger held across the sleep) and,
        // for the wake request, before the wake (never a touch on what came up after it).
        // Awake, only the lock screen itself, never an app over it (camera, Wallet, a
        // fingerprint prompt): fails closed.
        boolean startedAsleep = down > sScreenOffAt && (wakeAt == 0 || down <= wakeAt);
        boolean asleep = startedAsleep
                && (wakeAt != 0 || (sPm != null && !sPm.isInteractive()));
        if (!asleep && !lockScreenShowing()) return;   // a finger down in an app: left alone
        sEndedTouchAt = down;
        // From the fingerprint callback (wakeAt 0) the cancel must reach System UI before the
        // unlock does: wait for it, briefly (it takes under a millisecond normally).
        pilfer(wakeAt == 0 ? PILFER_WAIT_MS : 0L);
    }

    /** How long after an unlock a shade the unlocking touch opened gets closed again. */
    private static final long SHADE_GUARD_MS = 1000L;
    /**
     * When to close it, after it was reported open. Right away doesn't work: System UI is
     * still animating it open from the cancelled touch and ignores a close until that ends.
     */
    private static final long[] SHADE_CLOSE_AT_MS = { 300L, 700L, 1200L };
    /** The notification shade is open, as System UI last reported it. */
    private static volatile boolean sShadeOpen;
    /** uptimeMillis of the last "shade opened" report. */
    private static volatile long sShadeRevealAt;

    /**
     * Fingerprint unlock right after locking (before the lock screen has finished coming up,
     * about a second) can leave the notification shade open: the finger on the sensor is a
     * touch on System UI's shade window, the unlock hides that window, the touch is cancelled,
     * and System UI settles the half-tracked panel as open. The shade opening and closing are
     * reported to system_server (StatusBarManagerService.onPanelRevealed / onPanelHidden). If
     * it opens within a second of an unlock, with the lock screen gone, and the touch in
     * progress started before the unlock (so it is the finger that unlocked, not a swipe
     * down), it is closed again once System UI has finished opening it.
     */
    private static int installShadeGuard(ClassLoader cl) {
        int n = 0, hidden = 0;
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
            hidden = Xp.hookAllMethods(sbms, "onPanelHidden", new Xp.Callback() {
                @Override
                protected void afterHookedMethod(Xp.Param param) {
                    sShadeOpen = false;
                }
            }).size();
        } catch (Throwable t) {
            Module.log("double-tap-wake: shade guard failed", t);
        }
        if (n == 0) Module.log("double-tap-wake: shade guard unavailable");
        return n + hidden;
    }

    /** Binder thread (System UI reporting the shade opening): cheap checks only. */
    private static void maybeCloseShade(final Object statusBarService) {
        long unlocked = sUnlockedAt;
        final long revealAt = sShadeRevealAt;
        if (unlocked == 0 || revealAt - unlocked > SHADE_GUARD_MS) return;
        long down = sTouchDownAt, up = sTouchUpAt;
        // The touch must have started before the unlock and still be on (or have ended after
        // it): the finger that unlocked. A new touch after the unlock is a real swipe down.
        if (down == 0 || down > unlocked || (up != 0 && up < unlocked)) return;
        Handler h = sWorker;
        if (h == null) return;
        for (long delay : SHADE_CLOSE_AT_MS) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!sShadeOpen) return;                     // closed (by us or System UI)
                    if (sShadeRevealAt != revealAt) return;      // opened again since: not ours
                    if (sTouchDownAt > revealAt) return;         // you touched it since
                    if (keyguardShowing()) return;   // on the lock screen the panel is the lock screen
                    try {
                        Xp.callMethod(statusBarService, "collapsePanels");
                    } catch (Throwable t) {
                        Module.log("double-tap-wake: could not close the shade", t);
                    }
                }
            }, delay);
        }
    }

    /**
     * Always-on display touch (part of double tap to wake). System UI asks for the always-on
     * display's screen state through PowerManagerService.setDozeOverrideFromDreamManagerInternal
     * (first argument). For the resting always-on display it asks for STATE_DOZE /
     * STATE_DOZE_SUSPEND: the panel goes to its 30 Hz low-power mode and the touch chip is
     * handed to the always-on processor, which reports one single tap (the tap sensor System
     * UI wakes on) and then goes quiet. Getting the normal touchscreen back takes ~250 ms,
     * longer than the gap between the taps of a double tap, so the second tap is lost.
     * Measured on a Pixel 11 Pro: re-requesting the tap sensor never brings a second report,
     * and keeping the touchscreen fully on for the whole always-on display (STATE_ON, the
     * v1.1.2 behavior) keeps the processor awake (~15% battery overnight).
     *
     * So on the low-power always-on display:
     * - tap 1 reaches System UI through the tap sensor ("NODOZE tap"); onWakeRequest swallows
     *   it and openTouchWindow() turns the request into STATE_ON for TOUCH_WINDOW_MS (the
     *   brightness stays the always-on display's, so the screen looks the same) and starts
     *   the accelerometer for MOTION_LISTEN_MS (MotionTap);
     * - tap 2 of a double tap lands 55-170 ms after tap 1 is reported, while the touchscreen
     *   is still coming back (200-240 ms right after the always-on display goes low-power;
     *   250-340 ms once the phone has been still a few seconds and the panel has dropped to
     *   1 Hz, whose exit waits ~100 ms for a frame that doesn't come). It still jolts the
     *   phone: MotionTap feels it and wakes the phone. The accelerometer records into the
     *   sensor hub while the always-on display is low-power (Recorder), so a tap 2 that landed
     *   before the processor was even awake (deep sleep: ~150 ms) is felt too;
     * - if MotionTap missed it, any later tap the touchscreen catches pairs with tap 1 (it
     *   reaches System UI as a pulsing tap): any tap during the window counts. A tap reported
     *   up to WINDOW_PAIR_GRACE_MS after the window still counts, and a finger that landed in
     *   the window keeps it open until lifted (WindowClose). System UI's own checks
     *   (proximity, false touches) decide, as for any tap on the always-on display; our
     *   monitor only steps in for a full double tap it saw (onDozeDoubleTap).
     * When the window ends, the always-on display goes back to low-power, which also re-arms
     * the tap detector. The processor only stays awake during a window, so the battery cost
     * is the same as stock. While charging the touchscreen stays fully on (it costs nothing
     * then), so a normal double tap works.
     *
     * If the window can't open (always-on display paused in a pocket or face down, or off:
     * STATE_OFF), the stock re-arm pulse is used instead (requestPulse).
     *
     * The motion sensor only works well on a phone lying still; in the hand tap 2 drowns in
     * the wobble tap 1 starts. So low-power only happens once the phone is known to be lying
     * still: each time System UI asks for it, a short hand-held check (HeldWatch) runs with the
     * touchscreen still on, and while the phone is in the hand it stays on.
     */
    private static int installAodTouch(ClassLoader cl) {
        int n = 0;
        try {
            n = Xp.hookAllMethods(Xp.findClass(PMS, cl), "setDozeOverrideFromDreamManagerInternal",
                    new Xp.Callback() {
                        @Override
                        protected void beforeHookedMethod(Xp.Param param) {
                            if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                            // Our own re-send of System UI's request (reapplyAodNow) is not a new
                            // request: it must not start a hand-held check of its own.
                            boolean ours = sReapplyThread == Thread.currentThread();
                            Object[] prev = sAodArgs;
                            if (param.method instanceof Method) {
                                sAodPms = param.thisObject;
                                sAodMethod = (Method) param.method;
                                sAodArgs = param.args.clone();
                            }
                            int state = (Integer) param.args[0];
                            boolean doze = state == STATE_DOZE || state == STATE_DOZE_SUSPEND;
                            if (!ours) onAodRequest(prev, param.args, doze);
                            updateRecording();
                            if (!doze) return;
                            boolean keep = aodTouchWanted();
                            if (keep) param.args[0] = STATE_ON;
                            sAodTouchLast = keep ? 1 : 0;
                            // Low-power: a pickup (if the phone has that sensor) brings touch back.
                            if (!keep) armPickupLater();
                        }
                    }).size();
        } catch (Throwable t) {
            Module.log("double-tap-wake: always-on touch hook failed", t);
        }
        if (n == 0) Module.log("double-tap-wake: always-on touch unavailable");
        return n;
    }

    /**
     * Touchscreen fully on for the always-on display: while charging, during a touch window,
     * while the phone shakes, and while it is in the hand (or being checked for that).
     */
    private static boolean aodTouchWanted() {
        if (!sWakeOn) return false;
        return sCharging || touchWindowOpen() || shakingOn() || heldOn() || judgingOn();
    }

    private static boolean touchWindowOpen() {
        long u = sWindowUntil;
        return u != 0 && SystemClock.uptimeMillis() < u;
    }

    /** Charging state, from the plug broadcasts only (no polling). */
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
                        updateRecording();
                    } catch (Throwable t) {
                        Module.log("double-tap-wake: charging change failed", t);
                    }
                }
            };
            registerReceiver(ctx, r, f, new Handler(looper));
        } catch (Throwable t) {
            Module.log("double-tap-wake: charging watch unavailable", t);
        }
    }

    /** Re-applies on the worker thread (see reapplyAodNow). */
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

    /**
     * Re-sends System UI's latest always-on display request so the hook decides again, but only
     * while the always-on display is in its low-power state and only if the answer changed.
     * Worker thread; the request is read here, so a newer one from System UI is never undone.
     * Returns whether it was re-sent.
     */
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

    /** System UI's request is the low-power always-on display (DOZE / DOZE_SUSPEND). */
    private static boolean aodDozing(Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof Integer)) return false;
        int state = (Integer) args[0];
        return state == STATE_DOZE || state == STATE_DOZE_SUSPEND;
    }

    /**
     * After a first tap on the low-power always-on display (worker thread): the touchscreen is
     * fully on for TOUCH_WINDOW_MS, then back to low-power. Returns false if that can't be done
     * (always-on display paused or off), so the caller re-arms taps with the pulse instead.
     */
    private static boolean openTouchWindow(long tapAt) {
        final Handler h = sWorker;
        if (h == null || sPm == null || sPm.isInteractive()) return false;
        if (touchWindowOpen()) return true;
        // Touch already switched on (picked up, a hand-held check): the tap engine still
        // reported tap 1, so the touchscreen isn't back yet and tap 2 can still be lost. The
        // window opens all the same (motion sensor listening, the next tap pairing with this
        // one), without a display switch.
        boolean alreadyOn = sAodTouchLast == 1 && aodDozing(sAodArgs);
        final long start = SystemClock.uptimeMillis();
        final long until = start + TOUCH_WINDOW_MS;
        sWindowUntil = until;
        if (!alreadyOn && !reapplyAodNow()) {
            sWindowUntil = 0;
            return false;
        }
        // Right after the display switch was handed off (turning the sensor on takes a few ms
        // on this thread; the touchscreen's return must not wait for it).
        startMotionTap(start, until);
        sWindowFirstAt = tapAt;
        sPairUntil = until + WINDOW_PAIR_GRACE_MS;
        holdAwake(TOUCH_WINDOW_MS + WINDOW_HOLD_STEP_MS * WINDOW_HOLD_STEPS + 500L);
        h.postDelayed(new WindowClose(start, until), TOUCH_WINDOW_MS);
        return true;
    }

    // ------------------------------------------------------------------ taps felt by motion

    /**
     * How long after tap 1 the accelerometer listens for the next tap's jolt. The touchscreen
     * is back within ~350 ms (then touch takes over), so longer would only add false wakes.
     */
    private static final long MOTION_LISTEN_MS = 500L;
    /** Samples in the first MOTION_SETTLE_MS of the stream only set the baseline and noise. */
    private static final long MOTION_SETTLE_MS = 15L;
    /** Baseline time constants: fast while settling, then the high-pass for taps. */
    private static final float MOTION_SETTLE_TAU_MS = 5f, MOTION_TAU_MS = 20f;
    /**
     * A sample is "over" at this high-passed size (m/s^2) or NOISE_TIMES the resting noise,
     * whichever is larger. Measured (Pixel 11 Pro, ~400 samples/s): resting noise averages
     * 0.02-0.045 and stays under 0.06; a quick tap's jolt peaks at 0.18-0.74 over 10-25 ms.
     */
    private static final float MOTION_MIN = 0.09f, MOTION_NOISE_TIMES = 3.5f;
    /** Noise samples needed before a jolt can count. */
    private static final int MOTION_NOISE_MIN_N = 8;
    /** Over samples this close together belong to one jolt. */
    private static final long MOTION_GAP_MS = 15L;
    /** A jolt counts only if nothing was over for this long before it (a tap starts from rest). */
    private static final long MOTION_QUIET_MS = 40L;
    /** Over samples a jolt needs to count (one alone is a blip). */
    private static final int MOTION_JOLT_SAMPLES = 2;
    /** After a jolt, how long to wait for a proximity reading before waking anyway. */
    private static final long MOTION_PROX_WAIT_MS = 120L;
    /**
     * A tap on a phone that is already moving (just taken out of a pocket, turned in the hand):
     * nothing is ever quiet, so instead a short, sharp bump counts. Its peak is at least
     * MOTION_SHARP_MIN, within MOTION_SHARP_UNTIL_MS of tap 1 (when a quick tap 2 lands; touch
     * takes over after). It rose fast: 20-40 ms before the peak the movement stayed at or
     * under 1/MOTION_SHARP_RATIO of it. And it ends fast: within MOTION_FALL_MS after the
     * peak it is back at or under 1/MOTION_SHARP_RATIO. Hand swings and walking rise and fall
     * far slower. Measured: a tap 2 on a phone just taken out of a pocket peaked at 1.21, from
     * at most 0.49 20-40 ms before, and was at 0.61 10 ms after.
     */
    private static final float MOTION_SHARP_MIN = 0.5f, MOTION_SHARP_RATIO = 2f;
    /**
     * A jolt after the window opened that didn't start from quiet (tap 1's lift-off or the
     * hand still moving 15-40 ms before it) still counts if it stands out: its peak at least
     * STANDOUT_TIMES the biggest reading in the 40 ms before it, and STANDOUT_MIN. Measured:
     * double taps right after the always-on display went low-power, where tap 1's lift-off
     * (0.28-0.39) ran into tap 2, were lost to the quiet rule.
     */
    private static final float MOTION_STANDOUT_TIMES = 2.5f, MOTION_STANDOUT_MIN = 0.15f;
    /**
     * A jolt that grows back after a dip also counts: in the hand, tap 1 sets the phone
     * wobbling for ~200 ms (bumps every ~40 ms, each smaller than the last), and tap 2 lands
     * in it. Tap 2 shows as a bump after the wobble dipped to MOTION_REST_RATIO of the limit or
     * less (in the MOTION_QUIET_MS before it) that is at least MOTION_REGROW_RATIO of the bump
     * before that dip (its peak, found walking back from the dip until the readings fall below
     * MOTION_LOBE_FALL of it, at most MOTION_LOBE_MS), so the wobble grew instead of dying out.
     * It must start TAP_GAP_MS or more after tap 1's jolt (tap 1's lift-off comes sooner), and
     * after the window opened: tap 1's lift-off is before that, so early jolts don't use it.
     * Measured: a double tap in the hand, tap 1 0.63 with its wobble 0.49 then 0.29, a dip to
     * 0.06, then tap 2 at 0.32 and 0.39, ~37 ms and ~20 ms after the last over reading: both
     * lost to the quiet rule.
     */
    private static final float MOTION_REST_RATIO = 0.5f, MOTION_REGROW_RATIO = 1.0f;
    private static final float MOTION_LOBE_FALL = 0.8f;
    private static final long MOTION_LOBE_MS = 50L;
    /** Without the recording tap 1's jolt isn't seen: taken as this long before the window. */
    private static final long TAP1_GUESS_MS = 100L;
    private static final long MOTION_SHARP_UNTIL_MS = 300L;
    private static final long MOTION_RISE_FROM_MS = 40L, MOTION_RISE_TO_MS = 20L;
    private static final long MOTION_FALL_MS = 25L;
    /** Recent samples kept for the rise check (~160 ms at 400 samples/s). */
    private static final int MOTION_HISTORY = 64;
    private static final String MOTION_DETAILS = "io.github.pixelsuite:MOTION_TAP";
    /** The listening session, or null. Set on the worker thread. */
    private static volatile MotionTap sMotion;

    /**
     * With the recording (Recorder) the session also gets the accelerometer from before it
     * started: from MOTION_LOOKBACK_MS before tap 1 was reported. Tap 1's own jolt is in it:
     * the first jolt from TAP1_BEFORE_MS before the window opened up to TAP1_AFTER_MS after
     * it is tap 1 (in deep sleep the processor takes ~150 ms to wake, so tap 1's touch can be
     * ~350 ms back). Another jolt before the window opened is tap 2 only TAP_GAP_MS or more
     * after it (a finger stays down 65-100 ms, measured: its lift-off comes sooner) and if it
     * is big enough (see EARLY_TAP2_RATIO); any new jolt after the window opened is tap 2
     * (tap 1 was over by then: the tap sensor reports on lift-off).
     */
    private static final long MOTION_LOOKBACK_MS = 600L;
    private static final long TAP1_BEFORE_MS = 420L, TAP1_AFTER_MS = 10L, TAP_GAP_MS = 120L;
    /**
     * A jolt from before the window opened can also be tap 1's finger lifting off (the tap
     * sensor reports on lift-off, and in deep sleep the window opens ~150 ms after that), so
     * one counts as tap 2 only if it is a real tap's size: at least EARLY_TAP2_RATIO of tap 1's
     * jolt and EARLY_TAP2_MIN. Jolts after the window opened come after the lift-off anyway.
     */
    private static final float EARLY_TAP2_RATIO = 0.5f, EARLY_TAP2_MIN = 0.15f;
    /**
     * A soft tap 2 on a phone lying still (a bed, a sofa, a mat, a light touch). The limit
     * above follows the average resting noise, which tap 1's own jolt and wobble pull up (they
     * are counted, capped, as noise), so a soft tap 2 can be under it while standing out
     * clearly from the real rest. Measured in v1.1.3: two double taps on a still phone were
     * missed with tap 2 at 0.10-0.12 (three readings over 0.085 within ~20 ms, 90-110 ms after
     * the window opened) against a limit of 0.13-0.14, while the readings before tap 1 had a
     * median of 0.030 and never went over 0.08 apart from the taps.
     *
     * So, with the recording: the floor is the median of the readings before tap 1 (from the
     * lookback, at least MOTION_FLOOR_MIN_N of them). If it is at most MOTION_SOFT_FLOOR_MAX
     * (lying still; a held phone is far noisier and uses touch, see HeldWatch), a jolt starting
     * after the window opened and within MOTION_SOFT_UNTIL_MS, from quiet (nothing over the
     * soft limit in the MOTION_QUIET_MS before it), TAP_GAP_MS or more after tap 1, with at
     * least MOTION_SOFT_SAMPLES readings at or over the soft limit (MOTION_SOFT_TIMES the floor,
     * at least MOTION_SOFT_MIN) at most MOTION_GAP_MS apart and a peak of MOTION_SOFT_PEAK_TIMES
     * the soft limit, is tap 2. On a still phone after a single tap nothing moves, so a jolt
     * like that right after tap 1 is a tap.
     */
    private static final float MOTION_SOFT_FLOOR_MAX = 0.045f, MOTION_SOFT_TIMES = 2.6f,
            MOTION_SOFT_MIN = 0.085f, MOTION_SOFT_PEAK_TIMES = 1.15f;
    private static final int MOTION_SOFT_SAMPLES = 3, MOTION_FLOOR_MIN_N = 40;
    private static final long MOTION_SOFT_UNTIL_MS = 300L;
    /** Readings this long before tap 1's jolt (or its guessed time) are rest, for the floor. */
    private static final long MOTION_FLOOR_GAP_MS = 20L;
    /** Makes the recording deliver right away during a session (registered with no delay). */
    private static volatile SensorEventListener sLiveKick;

    /** After tap 1 opened a touch window (worker thread): listen for the next tap's jolt. */
    private static void startMotionTap(long startUptime, long windowUntil) {
        stopMotionTap();
        Handler h = sWorker;
        Context ctx = sCtx;
        if (h == null || ctx == null) return;
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc == null) return;
            final Recorder rec = sRecorder;
            final MotionTap m = new MotionTap(sm, h, startUptime, windowUntil, rec != null);
            if (rec != null) {
                // What was recorded already (tap 1, maybe tap 2 in deep sleep), then whatever
                // the sensor hub still holds (flush), then live: all through the recording, in
                // order.
                sMotion = m;
                rec.replayInto(m, m.mStartNs - MOTION_LOOKBACK_MS * 1000000L);
                if (sMotion != m || m.mJolted) {
                    // Tap 2 was already in the recording (deep sleep): handled.
                    return;
                }
                // The flush and the no-delay registration take 30-70 ms: off this thread, so
                // the readings they bring are handled here as soon as they come.
                final SensorManager fsm = sm;
                final Sensor facc = acc;
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

    /** Ends the listening session, if any. Worker thread. */
    private static void stopMotionTap() {
        MotionTap m = sMotion;
        if (m == null) return;
        sMotion = null;
        m.stop();
    }

    /**
     * Drops the no-delay registration of a session. On the sensor thread, so it comes after a
     * registration still pending there. Any thread.
     */
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

    /** A thread for slow sensor calls, so the worker keeps handling readings meanwhile. */
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

    // ------------------------------------------------------------------ the recording

    /**
     * While the always-on display is low-power (the only time tap 1 can come from the tap
     * sensor), the accelerometer records at RECORD_PERIOD_US into the sensor hub's own buffer
     * (batched, up to RECORD_LATENCY_US: the processor isn't woken for it and keeps sleeping).
     * When tap 1 arrives, the session takes the last moments from it, so a second tap that
     * landed while the processor was still waking up (deep sleep: ~150 ms) or the sensor was
     * still starting (30-75 ms) is there too. Only if the hub can hold at least
     * RECORD_MIN_FIFO readings; otherwise the session starts the sensor itself, as before.
     */
    private static final int RECORD_PERIOD_US = 2500;
    private static final int RECORD_LATENCY_US = 10000000;
    private static final int RECORD_MIN_FIFO = 300;
    /** Readings kept here (~2 s at 400 a second: enough for a hand-held judgement too). */
    private static final int RECORD_KEEP = 800;
    /**
     * The recording also runs for this long after the screen goes off, before the always-on
     * display goes low-power (System UI asks ~4 s after), so that request can be judged
     * hand-held or lying still from what was already recorded (see judgeHeld).
     */
    private static final long PRE_RECORD_MS = 8000L;
    /** The recording, or null. Set on the worker thread. */
    private static volatile Recorder sRecorder;
    private static boolean sRecordUnsupportedLogged;

    /**
     * Recording wanted: double tap to wake on, asleep, and the always-on display showing
     * (low-power, or fully on for a pulse or the touchscreen) or the screen just went off
     * (PRE_RECORD_MS). Not while it is off (paused in a pocket, face down). It also runs
     * while the touchscreen is kept on (in the hand, a pulse), so it is full whenever a
     * hand-held judgement needs it; it is batched in the sensor hub, so that costs little.
     */
    private static boolean recordWanted() {
        return sWakeOn && sPm != null && !sPm.isInteractive()
                && (aodShowing(sAodArgs) || justOff())
                && !sCharging && !shakingOn();
    }

    /** System UI's request shows the always-on display: low-power, or fully on (a pulse). */
    private static boolean aodShowing(Object[] args) {
        if (aodDozing(args)) return true;
        return args != null && args.length > 0 && args[0] instanceof Integer
                && (Integer) args[0] == STATE_ON;
    }

    /** The screen went off less than PRE_RECORD_MS ago. */
    private static boolean justOff() {
        long off = sScreenOffAt;
        return off != 0 && SystemClock.uptimeMillis() - off < PRE_RECORD_MS;
    }

    /** The posted body of updateRecording (one instance: it carries no state). */
    private static final Runnable UPDATE_RECORDING = new Runnable() {
        @Override
        public void run() {
            try {
                boolean want = recordWanted();
                if (want && sRecorder == null) {
                    startRecording();
                } else if (!want && sRecorder != null) {
                    stopRecording();
                }
            } catch (Throwable t) {
                Module.log("double-tap-wake: motion recording update failed", t);
            }
        }
    };

    /** Starts or stops the recording to match recordWanted() (posts to the worker). */
    private static void updateRecording() {
        Handler h = sWorker;
        if (h == null) return;
        h.post(UPDATE_RECORDING);
    }

    private static void startRecording() {
        Context ctx = sCtx;
        Handler h = sWorker;
        if (ctx == null || h == null) return;
        SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) return;
        int fifo = acc.getFifoMaxEventCount();
        // A wake-up accelerometer would wake the processor every time the buffer fills.
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
    }

    private static void stopRecording() {
        Recorder rec = sRecorder;
        if (rec == null) return;
        sRecorder = null;
        // A session fed by it stops too (it would get nothing more).
        MotionTap m = sMotion;
        if (m != null && m.mFromRecording) stopMotionTap();
        rec.stop();
    }

    /** The recording's listener: keeps the last RECORD_KEEP readings, feeds a session. */
    private static final class Recorder implements android.hardware.SensorEventListener2 {
        private final SensorManager mSm;
        private final long[] mTs = new long[RECORD_KEEP];
        private final float[] mX = new float[RECORD_KEEP], mY = new float[RECORD_KEEP],
                mZ = new float[RECORD_KEEP];
        private int mN;
        private boolean mStopped, mErrorLogged;

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
                MotionTap m = sMotion;
                if (m != null && m.mFromRecording) m.feed(ts, e.values[0], e.values[1],
                        e.values[2]);
            } catch (Throwable t) {
                if (!mErrorLogged) {   // up to 400 readings a second: once
                    mErrorLogged = true;
                    Module.log("double-tap-wake: motion recording reading failed", t);
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        /** Everything the hub held has been delivered (worker thread): a judgement may wait. */
        @Override
        public void onFlushCompleted(Sensor sensor) {
            try {
                if (!mStopped) onRecordingFlushed(this);
            } catch (Throwable t) {
                Module.log("double-tap-wake: hand-held judgement failed", t);
            }
        }

        /**
         * Still or moving over the last {@code ms} of kept readings, measured exactly as
         * HeldWatch does (HELD_SETTLE_MS to settle, then resting noise and tilt over the rest).
         * Returns {noise, tilt in degrees, readings judged}, or null if there's too little.
         */
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
                if (Float.isNaN(g) || g < 2f || g > 40f) continue;   // not a real reading
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

        /** Feeds the kept readings from {@code fromNs} on, oldest first. */
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

    /**
     * The accelerometer for a moment after tap 1 (worker thread). A tap on the screen jolts
     * the phone, on a table and in the hand: it rocks sideways, then is pushed along the
     * screen's axis, for 10-25 ms. A high-pass (the sample minus a MOTION_TAU_MS moving
     * baseline) keeps the jolt and drops gravity and slow hand movement. A sample is "over"
     * above the limit (the larger of MOTION_MIN and NOISE_TIMES the resting noise, so a shaky
     * hand needs a harder jolt). A jolt (over samples at most MOTION_GAP_MS apart) that starts
     * after MOTION_QUIET_MS without any, and reaches MOTION_JOLT_SAMPLES, is the next tap; a
     * lone blip or movement that was already going on is not. Then the accelerometer stops
     * and the proximity sensor is asked (only now, so it doesn't touch the display's switch
     * out of low-power at tap 1): covered, no wake; otherwise wake, as for a second tap.
     * Stops when the touchscreen is back (a finger down since tap 1: touch takes it from
     * there), the window ends, the screen turns on (SCREEN_ON), or after MOTION_LISTEN_MS.
     */
    private static final class MotionTap implements SensorEventListener {
        private final SensorManager mSm;
        private final Handler mHandler;
        private final long mStartUp, mStartNs, mWindowUntil;
        private long mFirstNs, mPrevNs;
        /** Last over sample, and the current jolt's first one (0 = none). */
        private long mLastOverNs, mJoltNs;
        /** The current jolt started after quiet; its over samples so far; its peak. */
        private boolean mJoltFromQuiet;
        private int mJoltSamples;
        private float mJoltPeak;
        private final float[] mBase = new float[3];
        private int mNoiseN;
        /** Recent high-passed samples (ring buffer, settling ones too), for the rise check. */
        private final long[] mHistNs = new long[MOTION_HISTORY];
        private final float[] mHistHp = new float[MOTION_HISTORY];
        private int mHistN;
        /** The biggest reading in the 40 ms before the current jolt started. */
        private float mJoltPreMax;
        /** The lowest reading in the 40 ms before it, and the biggest just before that dip. */
        private float mJoltDip, mJoltPrevLobe;
        /** A sharp peak that rose fast and must now fall fast (0 = none), and its size. */
        private long mSharpNs;
        private float mSharpPeak;
        private float mNoiseSum;
        /** A jolt was felt and we are waiting for the proximity reading. */
        private boolean mJolted;
        private boolean mDone;
        /** Fed by the recording (with what came before tap 1), not its own listener. */
        final boolean mFromRecording;
        /** Last reading fed (readings come once each, oldest first). */
        private long mLastFedNs;
        /** Tap 1's jolt (from the recording), 0 = not felt, and its peak; the jolt judged. */
        private long mTap1Ns, mJudgedJoltNs;
        private float mTap1Peak;
        /** The current jolt has ended (and was judged if early). */
        private boolean mJoltFinal;
        /** High-passed readings from before the window opened (for the floor), oldest first. */
        private final long[] mPreNs = new long[RECORD_KEEP];
        private final float[] mPreHp = new float[RECORD_KEEP];
        private int mPreN;
        /** Resting floor (see MOTION_SOFT_*): NaN until worked out (or unavailable), its limit. */
        private float mFloor = Float.NaN, mSoftLimit;
        private boolean mFloorDone;
        /** Soft-rule jolt tracking: last reading over the soft limit, the jolt's start. */
        private long mSoftLastNs, mSoftJoltNs, mSoftJudgedNs;
        private int mSoftN;
        private float mSoftPeak;
        private boolean mSoftFromQuiet;

        MotionTap(SensorManager sm, Handler h, long startUptime, long windowUntil,
                  boolean fromRecording) {
            mSm = sm;
            mHandler = h;
            mStartUp = startUptime;
            mWindowUntil = windowUntil;
            mFromRecording = fromRecording;
            // Sensor timestamps are elapsedRealtimeNanos; this is the window's start in them.
            mStartNs = SystemClock.elapsedRealtimeNanos()
                    - (SystemClock.uptimeMillis() - startUptime) * 1000000L;
        }

        /** The jolt starts by the window's start (+TAP1_AFTER_MS): judged as an early one. */
        private boolean early(long joltNs) {
            return mFromRecording && joltNs <= mStartNs + TAP1_AFTER_MS * 1000000L;
        }

        /**
         * A whole jolt from around tap 1 (recording only): the first is tap 1 itself; a later
         * one is tap 2 if it is TAP_GAP_MS after it and a real tap's size (EARLY_TAP2_*).
         */
        private boolean earlyJoltIsNextTap(long joltNs, float peak) {
            if (joltNs < mStartNs - TAP1_BEFORE_MS * 1000000L) return false;   // older movement
            if (mTap1Ns == 0) {
                mTap1Ns = joltNs;
                mTap1Peak = peak;
                return false;
            }
            if (joltNs - mTap1Ns < TAP_GAP_MS * 1000000L) return false;
            return peak >= Math.max(EARLY_TAP2_MIN, EARLY_TAP2_RATIO * mTap1Peak);
        }

        // A new jolt starting after the window opened is always the next tap (tap 1, lift-off
        // included, was over before the tap sensor reported it). Measured: tap 2 lands as soon
        // as 45 ms after the window opened, ~100 ms after tap 1's touch.

        /** The current jolt ended (worker): judge it if it was an early one. True if woken. */
        private boolean finishJolt(float limit) {
            mJoltFinal = true;
            if (!early(mJoltNs) || mJoltSamples < MOTION_JOLT_SAMPLES) return false;
            // From quiet, or standing out from what came just before (tap 2 running into tap
            // 1's lift-off).
            boolean standsOut = mNoiseN >= MOTION_NOISE_MIN_N
                    && mJoltPeak >= Math.max(Math.max(limit, MOTION_STANDOUT_MIN),
                            MOTION_STANDOUT_TIMES * mJoltPreMax);
            if (!mJoltFromQuiet && !standsOut) return false;
            if (!earlyJoltIsNextTap(mJoltNs, mJoltPeak)) return false;
            onJolt();
            return true;
        }

        /**
         * The current jolt (not from quiet) grows back after a dip in tap 1's wobble (see
         * MOTION_REGROW_RATIO): the dip got down to rest, the jolt is a tap's size and not
         * smaller than the bump before the dip, and it is far enough after tap 1.
         */
        private boolean regrows(float limit) {
            if (mJoltFromQuiet || mNoiseN < MOTION_NOISE_MIN_N
                    || mJoltSamples < MOTION_JOLT_SAMPLES) {
                return false;
            }
            if (mJoltDip > MOTION_REST_RATIO * limit) return false;
            if (mJoltPeak < Math.max(limit, MOTION_STANDOUT_MIN)) return false;
            // The bump before the dip was over the limit (the jolt isn't from quiet), so a
            // smaller one means it wasn't found.
            if (mJoltPrevLobe < limit) return false;
            if (mJoltPeak < MOTION_REGROW_RATIO * mJoltPrevLobe) return false;
            long tap1 = mTap1Ns != 0 ? mTap1Ns : mStartNs - TAP1_GUESS_MS * 1000000L;
            return mJoltNs - tap1 >= TAP_GAP_MS * 1000000L;
        }

        /** MOTION_LISTEN_MS passed (a jolt waiting for proximity finishes on its own). */
        void timeUp() {
            if (!mJolted) stopMotionTap();
        }

        /**
         * The resting floor, once, at the first reading after the window opened: the median of
         * the readings before tap 1 (MOTION_FLOOR_GAP_MS before its jolt, or before its guessed
         * time if it wasn't felt). Also finds the last pre-window reading over the soft limit,
         * so the quiet rule sees tap 1's lift-off.
         */
        private void workOutFloor() {
            mFloorDone = true;
            long tap1 = mTap1Ns != 0 ? mTap1Ns : mStartNs - TAP1_GUESS_MS * 1000000L;
            long cut = tap1 - MOTION_FLOOR_GAP_MS * 1000000L;
            float[] rest = new float[mPreN];
            int n = 0;
            for (int i = 0; i < mPreN; i++) {
                if (mPreNs[i] < cut) rest[n++] = mPreHp[i];
            }
            if (n < MOTION_FLOOR_MIN_N) return;   // too little rest seen: rule off
            java.util.Arrays.sort(rest, 0, n);
            mFloor = (n % 2 == 1) ? rest[n / 2] : (rest[n / 2 - 1] + rest[n / 2]) / 2f;
            mSoftLimit = Math.max(MOTION_SOFT_MIN, MOTION_SOFT_TIMES * mFloor);
            for (int i = 0; i < mPreN; i++) {
                if (mPreHp[i] >= mSoftLimit) mSoftLastNs = mPreNs[i];
            }
        }

        /**
         * The soft-tap rule (see MOTION_SOFT_*) for one reading after the window opened. Returns
         * true if it found tap 2 (and handled it).
         */
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
            onJolt();
            return true;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (mDone) return;
            try {
                if (e.sensor != null && e.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                    if (mJolted) decide(e.values[0] < e.sensor.getMaximumRange());
                    return;
                }
                // With the recording, this registration only makes readings come at once; they
                // reach the session through the recording, in order (feed() would otherwise
                // drop older flushed ones after a newer live one).
                if (mFromRecording) return;
                feed(e.timestamp, e.values[0], e.values[1], e.values[2]);
            } catch (Throwable t) {
                Module.log("double-tap-wake: motion sensor reading failed", t);
                stopMotionTap();
            }
        }

        /** One accelerometer reading (from its own listener or the recording). Worker. */
        void feed(long ts, float x, float y, float z) {
            if (mDone || mJolted) return;
            try {
                if (sWindowUntil != mWindowUntil) {   // touch window over
                    stopMotionTap();
                    return;
                }
                if (sTouchDownAt >= mStartUp) {   // touchscreen back
                    stopMotionTap();
                    return;
                }
                if (ts <= mLastFedNs) return;   // already had it
                mLastFedNs = ts;
                long earliest = mFromRecording ? mStartNs - MOTION_LOOKBACK_MS * 1000000L
                        : mStartNs;
                // Too old (without the recording: tap 1's own jolt).
                if (ts < earliest) return;
                float g = (float) Math.sqrt(x * x + y * y + z * z);
                if (Float.isNaN(g) || g < 2f || g > 40f) return;   // not a real reading
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
                if (!settling) {
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
                    if (hp >= limit) {
                        boolean sameJolt = mJoltNs != 0
                                && ts - mLastOverNs <= MOTION_GAP_MS * 1000000L;
                        if (!sameJolt) {
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
                            // The bump before the dip: walk back from it over the bump's peak
                            // until it falls on the other side (or MOTION_LOBE_MS).
                            if (dipK >= 0) {
                                long dipNs = mHistNs[dipK % MOTION_HISTORY];
                                for (int k = dipK - 1; k >= k0; k--) {
                                    int i = k % MOTION_HISTORY;
                                    if (mHistNs[i] < dipNs - MOTION_LOBE_MS * 1000000L) break;
                                    float hk = mHistHp[i];
                                    mJoltPrevLobe = Math.max(mJoltPrevLobe, hk);
                                    // (Only past a real bump, not in the dip's own wiggle.)
                                    if (mJoltPrevLobe >= limit
                                            && hk < MOTION_LOBE_FALL * mJoltPrevLobe) {
                                        break;
                                    }
                                }
                            }
                            mJoltFromQuiet = mNoiseN >= MOTION_NOISE_MIN_N && (mLastOverNs == 0
                                    || ts - mLastOverNs > MOTION_QUIET_MS * 1000000L);
                        }
                        mLastOverNs = ts;
                        mJoltSamples++;
                        mJoltPeak = Math.max(mJoltPeak, hp);
                        // Late jolts are judged at once (every ms counts); early ones (from
                        // the recording) once they're over, by size (finishJolt).
                        if (mJoltFromQuiet && mJoltSamples >= MOTION_JOLT_SAMPLES
                                && mJudgedJoltNs != mJoltNs && !early(mJoltNs)) {
                            mJudgedJoltNs = mJoltNs;
                            onJolt();   // a late jolt from quiet is the next tap
                            return;
                        }
                        // Not from quiet, but standing out from what came just before.
                        if (!mJoltFromQuiet && !early(mJoltNs) && mJudgedJoltNs != mJoltNs
                                && mJoltSamples >= MOTION_JOLT_SAMPLES
                                && mNoiseN >= MOTION_NOISE_MIN_N
                                && mJoltPeak >= Math.max(Math.max(limit, MOTION_STANDOUT_MIN),
                                        MOTION_STANDOUT_TIMES * mJoltPreMax)) {
                            mJudgedJoltNs = mJoltNs;
                            onJolt();
                            return;
                        }
                        // Not from quiet, but growing back after a dip in tap 1's wobble.
                        if (!mJoltFromQuiet && !early(mJoltNs) && mJudgedJoltNs != mJoltNs
                                && regrows(limit)) {
                            mJudgedJoltNs = mJoltNs;
                            onJolt();
                            return;
                        }
                    }
                    if (sharpPeak(limit)) return;
                    if (hp >= limit) return;   // over samples never count as noise
                } else if (hp >= MOTION_MIN) {
                    mLastOverNs = ts;   // something moving while settling: not quiet
                }
                // Resting noise, from the settling samples on; one sample adds at most
                // MOTION_MIN, so a jolt in the first moments can't hide the next one.
                mNoiseSum += Math.min(hp, MOTION_MIN);
                mNoiseN++;
            } catch (Throwable t) {
                Module.log("double-tap-wake: motion sensor reading failed", t);
                stopMotionTap();
            }
        }

        /**
         * The sharp-bump check (see MOTION_SHARP_MIN), on each new sample: first a peak that
         * rose fast (the sample before the newest one), then the fall within MOTION_FALL_MS.
         * When both hold, handles it as the next tap.
         */
        private boolean sharpPeak(float limit) {
            int n = mHistN;
            // No noise-sample minimum here: MOTION_SHARP_MIN is far above any noise limit, and
            // on a moving phone few samples ever count as noise.
            if (n < 3) return false;
            long ts = mHistNs[(n - 1) % MOTION_HISTORY];
            float hp = mHistHp[(n - 1) % MOTION_HISTORY];
            if (mSharpNs != 0) {
                if (hp > mSharpPeak) {
                    mSharpNs = 0;   // still rising: not over, look at the new peak instead
                } else if (hp <= mSharpPeak / MOTION_SHARP_RATIO) {
                    long tp = mSharpNs;
                    mSharpNs = 0;
                    // A late one is always the next tap; an early one may be tap 1's own bump,
                    // or its lift-off.
                    if (early(tp) && !earlyJoltIsNextTap(tp, mSharpPeak)) return false;
                    onJolt();
                    return true;
                } else if (ts - mSharpNs > MOTION_FALL_MS * 1000000L) {
                    mSharpNs = 0;   // stayed up: movement, not a tap
                    return false;
                } else {
                    return false;
                }
            }
            float p = mHistHp[(n - 2) % MOTION_HISTORY];
            if (p < mHistHp[(n - 3) % MOTION_HISTORY] || p <= hp) return false;   // not a peak
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
            mSharpNs = tp;   // rose fast; now it has to fall fast
            mSharpPeak = p;
            if (hp <= p / MOTION_SHARP_RATIO) return sharpPeak(limit);   // already fell
            return false;
        }

        /** A tap's jolt: accelerometer off, wait (briefly) for the proximity sensor. */
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
                decide(false);   // no sensor
                return;
            }
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!mDone) decide(false);   // no reading
                }
            }, MOTION_PROX_WAIT_MS);
        }

        /** After a jolt: wake unless the screen is covered or the phone already woke. */
        private void decide(boolean near) {
            if (mDone) return;
            if (sMotion == this) sMotion = null;
            stop();
            if (near) return;   // screen covered: ignored
            if (sPm == null || sPm.isInteractive() || sWindowUntil != mWindowUntil) return;
            wakeForMotionTap();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        void stop() {
            if (mDone) return;
            mDone = true;
            dropLiveKick();
            try {
                mSm.unregisterListener(this);
            } catch (Throwable ignored) { }
            float noise = mNoiseN == 0 ? 0f : mNoiseSum / mNoiseN;
            if (shaking(noise, mNoiseN)) onMovingTap();
        }
    }

    // ------------------------------------------------------------------ phone shaking

    /**
     * Shaking (a car mount on the road, walking with the phone in hand): the phone moves so
     * much that the motion sensor can't tell a tap from the road (measured in a car mount:
     * resting noise mostly 0.085-0.090, the most it can read; in the hand or on a vibrating
     * surface up to 0.080, on a desk 0.02-0.035; about half of the second taps were lost in
     * the car). Then the touchscreen stays fully on for the
     * always-on display, like while charging, so a double tap works on the touchscreen alone.
     *
     * One tap on a moving phone isn't enough: taking it out of a pocket looks the same for a
     * moment. So such a tap only starts watching (onMovingTap), and a short listen
     * (SHAKING_PROBE_MS) on the always-on display decides: SHAKING_RECHECK_MS after the tap if
     * the phone stayed asleep, or SHAKING_AFTER_OFF_MS after the next lock (within
     * SHAKING_WATCH_MS). Shaking then: the touchscreen stays on for SHAKING_MS, and at its end
     * another listen extends it while the phone still shakes (with the screen on then, in use,
     * it extends without asking); otherwise back to low-power. Every lock while it is on is
     * also followed by a listen (SHAKING_AFTER_OFF_MS later), so taking the phone off the
     * mount and putting it down ends it within seconds. The processor stays awake while
     * the touchscreen is on (~2.5% battery an hour, measured in v1.1.2), only while shaking and
     * only while the always-on display shows (in a pocket it is off).
     */
    private static final float SHAKING_NOISE = 0.084f;
    private static final int SHAKING_MIN_SAMPLES = 10;
    private static final long SHAKING_MS = 5L * 60L * 1000L;
    private static final long SHAKING_PROBE_MS = 400L;
    private static final long SHAKING_WATCH_MS = 15L * 60L * 1000L;
    private static final long SHAKING_RECHECK_MS = 10L * 1000L;
    private static final long SHAKING_AFTER_OFF_MS = 6L * 1000L;
    /** End (uptimeMillis) of the shaking mode, 0 = off. */
    private static volatile long sShakingUntil;
    /** When a tap last found the phone moving (uptimeMillis), 0 = not watching. Worker thread. */
    private static long sMovingSeenAt;
    /** The running listen, or null. Worker thread. */
    private static ShakeProbe sProbe;

    private static boolean shakingOn() {
        long u = sShakingUntil;
        return u != 0 && SystemClock.uptimeMillis() < u;
    }

    /** What a listen found (worker thread). */
    private abstract static class ProbeDone {
        abstract void done(float noise, int samples);
    }

    /**
     * Listens to the accelerometer for SHAKING_PROBE_MS, then reports (worker thread). Returns
     * false if it couldn't start (and then never reports).
     */
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
                    endShaking();
                }
            }
        }, SHAKING_PROBE_MS);
        return true;
    }

    private static boolean shaking(float noise, int samples) {
        return samples >= SHAKING_MIN_SAMPLES && noise >= SHAKING_NOISE;
    }

    /** A tap's motion session found the phone moving a lot (worker thread): watch it. */
    private static void onMovingTap() {
        try {
            Handler h = sWorker;
            if (h == null || !sWakeOn || shakingOn()) return;
            final long at = SystemClock.uptimeMillis();
            sMovingSeenAt = at;
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sMovingSeenAt == at) confirmShaking();   // 10 s after the tap
                }
            }, SHAKING_RECHECK_MS);
        } catch (Throwable t) {
            Module.log("double-tap-wake: shaking watch failed", t);
        }
    }

    /** Screen off (worker thread): while watching, check once the always-on display is up. */
    private static void onScreenOffForShaking() {
        try {
            Handler h = sWorker;
            if (h == null) return;
            if (shakingOn()) {
                // Each lock while on: still shaking? (Unlocked in the car mount, then put on a
                // desk, it would otherwise stay on until the next check.)
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
                    if (sMovingSeenAt == at) confirmShaking();   // after locking
                }
            }, SHAKING_AFTER_OFF_MS);
        } catch (Throwable t) {
            Module.log("double-tap-wake: shaking watch failed", t);
        }
    }

    /** While watching: is the phone still shaking? Asked on the always-on display only. */
    private static void confirmShaking() {
        try {
            if (!sWakeOn || sPm == null || shakingOn()) return;
            // In use, or the always-on display is off (pocket): ask after the next lock.
            if (sPm.isInteractive() || !aodDozing(sAodArgs)) return;
            runProbe(new ProbeDone() {
                @Override
                void done(float noise, int samples) {
                    if (shakingOn()) return;
                    sMovingSeenAt = 0;
                    // Not shaking: the always-on display stays low-power.
                    if (shaking(noise, samples)) startShaking();
                }
            });
        } catch (Throwable t) {
            Module.log("double-tap-wake: shaking check failed", t);
        }
    }

    /** The phone keeps shaking (worker thread): touchscreen on for the always-on display. */
    private static void startShaking() {
        extendShaking(SHAKING_MS);
        reapplyAodNow();
        updateRecording();
    }

    /** Shaking mode for {@code ms} more, with its end check scheduled. Worker thread. */
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

    /** Shaking mode time is up (worker thread): still shaking? Asked on the always-on display. */
    private static void shakingCheck() {
        try {
            if (!sWakeOn || sPm == null) {   // double tap to wake off
                endShaking();
                return;
            }
            if (sPm.isInteractive()) {
                // In use (navigation in the car mount, say): nothing to measure against; ask
                // again later, so the always-on display after it still keeps touch on.
                extendShaking(SHAKING_MS);
                return;
            }
            if (!aodDozing(sAodArgs)) {   // always-on display off
                endShaking();
                return;
            }
            // Keep the touchscreen on while asking (a doze request meanwhile would drop it).
            sShakingUntil = SystemClock.uptimeMillis() + SHAKING_PROBE_MS + 1000L;
            reapplyAodNow();   // in case a doze request came in the moment the time ran out
            boolean started = runProbe(new ProbeDone() {
                @Override
                void done(float noise, int samples) {
                    if (shaking(noise, samples)) {   // still shaking: touchscreen stays on
                        extendShaking(SHAKING_MS);
                        reapplyAodNow();
                    } else {
                        endShaking();   // phone still
                    }
                }
            });
            if (!started) endShaking();   // motion sensor unavailable
        } catch (Throwable t) {
            Module.log("double-tap-wake: shaking check failed", t);
            endShaking();
        }
    }

    private static void endShaking() {
        if (sProbe != null) {
            sProbe.cancel();
            sProbe = null;
        }
        if (sShakingUntil == 0) return;
        sShakingUntil = 0;
        reapplyAodNow();
        updateRecording();
    }

    /** A short accelerometer listen: the resting noise, measured like MotionTap's. */
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
                if (ts < mStartNs) return;   // queued from before the probe
                float g = (float) Math.sqrt(x * x + y * y + z * z);
                if (Float.isNaN(g) || g < 2f || g > 40f) return;   // not a real reading
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

        /** Stops listening and returns the noise. */
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

    // ------------------------------------------------------------------ phone in the hand

    /**
     * Phone in the hand: the touchscreen stays fully on for the always-on display, so a double
     * tap is two real touches and always works, instead of tap 2 having to be felt by the
     * motion sensor.
     *
     * Why: on the low-power always-on display tap 2 lands while the touchscreen is still coming
     * back (~270 ms after tap 1 is reported; tap 2 comes 100-200 ms after tap 1), so only the
     * motion sensor can catch it. On a desk that works: the tap jolts the phone sharply. In the
     * hand it doesn't: tap 1 sets the phone wobbling (bumps every ~45 ms that don't die out)
     * and tap 2 drowns in it, or the hand soaks it up. Measured in v1.1.3: 7 of 21 double taps
     * on the low-power always-on display were missed, 4 of them in the hand (resting floor
     * 0.05-0.12, tap 2 hidden in the wobble), and those are exactly the ones right after
     * locking with the phone still held, often just as the always-on display's brightness
     * adjusts to the room light (which is stock, and only a sign the phone is held up).
     *
     * How: whenever the always-on display is about to go low-power (System UI asks for
     * DOZE / DOZE_SUSPEND after the first seconds after locking, after a pulse, coming out of a
     * pocket), after its brightness changed (the light sensor saw a change: often the phone
     * being picked up or turned), after a tap that didn't wake it, and when the phone's pickup
     * sensor fires (if it has one), the last moments of the recording are judged first
     * (judgeHeld: no time awake, no delay). Still (on a desk, a table, a bed: little vibration
     * and the phone's tilt not changing) means low-power at once, as stock. Moving, or no
     * recording to judge, (and on a pickup) the touchscreen stays on while a short
     * accelerometer listen (HeldWatch) decides over HELD_SETTLE_MS + HELD_WINDOW_MS.
     * Moving or tilting means in the hand: the touchscreen stays on, checked
     * again every HELD_WINDOW_MS, until the phone is still, HELD_EPISODE_MS have passed, or
     * the always-on display goes off (pocket, face down) or the phone wakes.
     *
     * Battery: the processor stays awake while the touchscreen is on (~2.5% an hour, measured
     * in v1.1.2), so this only runs while the phone is actually in the hand, and at most
     * HELD_BUDGET_MS per screen-off. A phone put down is back in low-power within ~2 s.
     */
    private static final long HELD_SETTLE_MS = 250L;
    private static final long HELD_WINDOW_MS = 1500L;
    /** Extra time the touchscreen stays on past a window, in case readings are late. */
    private static final long HELD_MARGIN_MS = 750L;
    /**
     * How long one stretch of touch-on lasts while the phone stays in the hand or on the lap,
     * and how much of it one screen-off allows in total. After that the motion sensor (and one
     * more tap) has to do, until a tap that didn't wake the phone ("after a tap": someone is
     * trying right now), which may start a new stretch even past the total.
     */
    private static final long HELD_EPISODE_MS = 90L * 1000L;
    private static final long HELD_BUDGET_MS = 4L * 60L * 1000L;
    private static final String AFTER_A_TAP = "after a tap";

    /** The per-screen-off total applies to this trigger (not to a tap that didn't wake). */
    private static boolean budgetApplies(String trigger) {
        return !AFTER_A_TAP.equals(trigger);
    }
    /**
     * Still: resting noise (high-passed like MotionTap's, so the same scale) under STILL_NOISE,
     * and the phone's tilt (gravity's direction, low-passed over HELD_GRAVITY_TAU_MS so hand
     * tremor doesn't count) moving less than STILL_TILT_DEG within a window. Measured: on a
     * desk 0.016-0.020 and 0.02-0.09 degree; held gently 0.024-0.045 and 0.32-0.47 degree
     * (more when moving). A phone resting on a lap or thigh breathes with the body: it counts
     * as held too, since taps on it are soft and the motion sensor missed them.
     */
    private static final float STILL_NOISE = 0.026f, STILL_TILT_DEG = 0.12f;
    private static final float HELD_GRAVITY_TAU_MS = 100f;
    private static final int HELD_MIN_SAMPLES = 100;
    /** 400 readings a second, as the recording and MotionTap (the noise scale depends on it). */
    private static final int HELD_PERIOD_US = 2500;

    /** End (uptimeMillis) of hand-held mode or its check, 0 = off. */
    private static volatile long sHeldUntil;
    /** The running watch, or null. Worker thread (read from others). */
    private static volatile HeldWatch sHeldWatch;
    /** Time spent in hand-held mode since the screen last went off (ms). */
    private static volatile long sHeldSpentMs;
    /** The thread re-sending System UI's request in reapplyAodNow, null otherwise. */
    private static volatile Thread sReapplyThread;

    private static boolean heldOn() {
        long u = sHeldUntil;
        return u != 0 && SystemClock.uptimeMillis() < u;
    }

    /**
     * A new always-on display request from System UI (not our re-send), from the hook: cheap,
     * posts only. Going low-power (from anything else) or a changed low-power request (new
     * brightness) starts a hand-held check; anything else (pulse, off) ends hand-held mode.
     */
    private static void onAodRequest(Object[] prev, Object[] args, boolean doze) {
        if (!doze) {
            if (sHeldUntil != 0 || sHeldWatch != null) postEndHeld();
            postDisarmPickup();
            return;
        }
        if (!aodDozing(prev)) {
            // Being decided right now: the touchscreen stays on for the judgement.
            judgeHeld("always-on display going low-power", true);
        } else if (!sameRequest(prev, args)) {
            // Already low-power: judged without touching the display.
            judgeHeld("always-on display brightness changed", false);
        }
    }

    // ------------------------------------------------------------------ hand-held, judged

    /**
     * Hand-held or lying still, judged from the recording (it runs from screen-off and while
     * the always-on display is low-power, in the sensor hub) instead of a fresh listen with the
     * touchscreen on: a flush and some arithmetic, no extra time awake. Lying still (nearly
     * always), the always-on display goes low-power at once, as stock, with no delay and
     * nothing else running. Moving, the live check (startHeldCheck) takes over as before.
     * Without the recording (or if the flush doesn't come within JUDGE_MAX_MS), the live check
     * runs instead.
     */
    private static final long JUDGE_MAX_MS = 400L;
    /** Readings a judgement needs after settling (~1 s at 400 a second; fewer: live check). */
    private static final int JUDGE_MIN_READINGS = 400;
    /** End (uptimeMillis) of the touchscreen kept on while a judgement runs, 0 = none. */
    private static volatile long sJudgeUntil;
    /** The judgement waiting for its flush, or null. Set on the worker thread. */
    private static volatile Judge sJudge;

    private static final class Judge {
        final String trigger;
        final Recorder rec;

        Judge(String trigger, Recorder rec) {
            this.trigger = trigger;
            this.rec = rec;
        }
    }

    private static boolean judgingOn() {
        long u = sJudgeUntil;
        return u != 0 && SystemClock.uptimeMillis() < u;
    }

    /**
     * Starts a judgement (any thread; cheap). With {@code keepTouch} (a request being decided
     * now) the touchscreen stays on until it's done. Returns whether touch is kept on for it.
     */
    private static boolean judgeHeld(final String trigger, final boolean keepTouch) {
        final Handler h = sWorker;
        if (h == null || !sWakeOn || sCharging || shakingOn()) return false;
        if (heldOn()) return true;   // a live check or hand-held mode is already running
        final Recorder rec = sRecorder;
        if (rec == null || (budgetApplies(trigger) && sHeldSpentMs >= HELD_BUDGET_MS)) {
            return startHeldCheck(trigger);   // the live check (it also handles a spent budget)
        }
        if (keepTouch) sJudgeUntil = SystemClock.uptimeMillis() + JUDGE_MAX_MS + 200L;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final Judge j = new Judge(trigger, rec);
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
                                if (sm == null || !sm.flush(rec)) postJudgeFallback(j);
                            } catch (Throwable t) {
                                postJudgeFallback(j);
                            }
                        }
                    });
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (sJudge == j) judgeFallback(j);   // no flush in time
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

    private static void postJudgeFallback(final Judge j) {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (sJudge == j) judgeFallback(j);
            }
        });
    }

    /** The recording can't answer (worker thread): the live check decides instead. */
    private static void judgeFallback(Judge j) {
        sJudge = null;
        startHeldCheck(j.trigger);   // sets its own touch-on time first
        sJudgeUntil = 0;
        reapplyAodNow();
    }

    /** The recording was flushed (worker thread): judge the judgement waiting for it. */
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
        if (r == null || r[2] < JUDGE_MIN_READINGS) {   // no readings, or too few
            judgeFallback(j);
            return;
        }
        boolean still = r[0] < STILL_NOISE && r[1] < STILL_TILT_DEG;
        if (still) {   // lying still: the always-on display goes low-power
            sJudgeUntil = 0;
            reapplyAodNow();
            return;
        }
        // Moving: the live check takes over (touch on while it runs; it starts before the
        // judgement's own touch-on time ends, so the display doesn't blink to low-power).
        startHeldCheck(j.trigger);
        sJudgeUntil = 0;
        reapplyAodNow();
    }

    /** The two requests are the same apart from the screen state (first argument). */
    private static boolean sameRequest(Object[] a, Object[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 1; i < a.length; i++) {
            Object x = a[i], y = b[i];
            if (x == null ? y != null : !x.equals(y)) return false;
        }
        return true;
    }

    /**
     * Keeps the touchscreen on and starts a hand-held check (any thread: sets the flag at once,
     * so a request being decided right now keeps touch on; the watch starts on the worker).
     * Returns whether touch is now kept on for it. The caller re-applies if needed.
     */
    private static boolean startHeldCheck(final String trigger) {
        final Handler h = sWorker;
        if (h == null || !sWakeOn || sCharging || shakingOn()) return false;
        if (heldOn()) return true;   // a check or hand-held mode is already running
        // Hand-held time used up until the next screen-off: the always-on display stays
        // low-power.
        if (budgetApplies(trigger) && sHeldSpentMs >= HELD_BUDGET_MS) return false;
        final long until = SystemClock.uptimeMillis() + HELD_SETTLE_MS + HELD_WINDOW_MS
                + HELD_MARGIN_MS;
        sHeldUntil = until;
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sHeldUntil != until) return;   // ended or replaced meanwhile
                    startHeldWatch(trigger, until);
                } catch (Throwable t) {
                    Module.log("double-tap-wake: hand-held check failed", t);
                    endHeld(true);
                }
            }
        });
        return true;
    }

    /** Worker thread: the listen behind a check (sHeldUntil already set to {@code until}). */
    private static void startHeldWatch(String trigger, long until) {
        HeldWatch old = sHeldWatch;
        if (old != null) old.cancel();
        sHeldWatch = null;
        Context ctx = sCtx;
        SensorManager sm = ctx == null ? null
                : (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) {   // no motion sensor
            endHeld(true);
            return;
        }
        HeldWatch w = new HeldWatch(sm, trigger);
        if (!sm.registerListener(w, acc, HELD_PERIOD_US, 0, sWorker)) {
            endHeld(true);
            return;
        }
        sHeldWatch = w;
        holdAwake(until - SystemClock.uptimeMillis() + 500L);
        updateRecording();   // the recording isn't needed while touch is on
        watchHeldUntil(until);
    }

    /** If nothing extended or ended hand-held mode by {@code until}, end it (readings stopped). */
    private static void watchHeldUntil(final long until) {
        Handler h = sWorker;
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sHeldUntil == until) endHeld(true);
            }
        }, Math.max(0L, until - SystemClock.uptimeMillis()) + 50L);
    }

    private static void postEndHeld() {
        Handler h = sWorker;
        if (h == null) return;
        h.post(new Runnable() {
            @Override
            public void run() {
                // The always-on display left low-power: nothing to re-apply.
                endHeld(false);
            }
        });
    }

    /**
     * Ends hand-held mode or its check (worker thread), counts its time, and (if
     * {@code reapply}) lets the always-on display go back to low-power.
     */
    private static void endHeld(boolean reapply) {
        HeldWatch w = sHeldWatch;
        sHeldWatch = null;
        boolean was = sHeldUntil != 0 || sJudgeUntil != 0;
        sHeldUntil = 0;
        sJudge = null;   // a judgement waiting for its flush is dropped too
        sJudgeUntil = 0;
        if (w != null) {
            w.cancel();
            sHeldSpentMs += SystemClock.uptimeMillis() - w.mStartUp;
        }
        if (reapply && was) {
            reapplyAodNow();
            updateRecording();
        }
    }

    /**
     * Listens to the accelerometer in windows of HELD_WINDOW_MS (worker thread) and keeps
     * hand-held mode going while the phone moves (see the section comment).
     */
    private static final class HeldWatch implements SensorEventListener {
        private final SensorManager mSm;
        private final String mTrigger;
        final long mStartUp = SystemClock.uptimeMillis();
        private final long mStartNs = SystemClock.elapsedRealtimeNanos();
        private final float[] mBase = new float[3], mGrav = new float[3], mRef = new float[3];
        private long mFirstNs, mPrevNs, mWinNs;
        private int mN;
        private float mSum, mMaxTilt;
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
                if (ts < mStartNs) return;   // queued from before the watch
                float g = (float) Math.sqrt(x * x + y * y + z * z);
                if (Float.isNaN(g) || g < 2f || g > 40f) return;   // not a real reading
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
                // High-passed movement, exactly as ShakeProbe / MotionTap measure it.
                float hx = x - mBase[0], hy = y - mBase[1], hz = z - mBase[2];
                float hp = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                boolean settling = (ts - mFirstNs) / 1000000L < MOTION_SETTLE_MS;
                float a = Math.min(1f, dt / (settling ? MOTION_SETTLE_TAU_MS : MOTION_TAU_MS));
                mBase[0] += a * hx;
                mBase[1] += a * hy;
                mBase[2] += a * hz;
                // Gravity's direction (slow), for the tilt.
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
                endHeld(true);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        private void windowDone(float noise, float tilt, int n) {
            // Double tap to wake off, or the touchscreen is on anyway.
            if (!sWakeOn || sCharging || shakingOn()) {
                endHeld(true);
                return;
            }
            if (n < HELD_MIN_SAMPLES) {
                // Readings too sparse to judge (shouldn't happen at 400 a second): err on the
                // battery's side.
                endHeld(true);
                return;
            }
            boolean still = noise < STILL_NOISE && tilt < STILL_TILT_DEG;
            long now = SystemClock.uptimeMillis();
            if (still) {   // lying still or put down: the always-on display goes low-power
                endHeld(true);
                return;
            }
            // In the hand: the touchscreen stays on for the always-on display, until time is up.
            long spent = now - mStartUp;
            if (spent >= HELD_EPISODE_MS || (budgetApplies(mTrigger)
                    && sHeldSpentMs + spent >= HELD_BUDGET_MS)) {
                endHeld(true);
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

    /** Angle between two vectors, in degrees (small angles: from the cross product). */
    private static float angleDeg(float[] a, float[] b) {
        float cx = a[1] * b[2] - a[2] * b[1];
        float cy = a[2] * b[0] - a[0] * b[2];
        float cz = a[0] * b[1] - a[1] * b[0];
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return (float) Math.toDegrees(Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), dot));
    }

    // ------------------------------------------------------------------ pickup

    /**
     * The phone's pickup sensor (the one "Lift to check phone" uses: a wake-up sensor in the
     * sensor hub, no cost while nothing happens), if it has one. Armed while the always-on
     * display is low-power; picking the phone up starts a hand-held check, so the touchscreen
     * is back before the double tap that usually follows. Looked up once.
     */
    private static final int TYPE_PICK_UP_GESTURE = 25;
    private static Sensor sPickupSensor;   // worker thread
    private static boolean sPickupLooked, sPickupArmed;   // worker thread
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

    /** Worker thread. */
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

    /** Worker thread. */
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

    /** The phone was picked up (worker thread): touchscreen on while a check decides. */
    private static void onPickup() {
        boolean oneShot = sPickupSensor != null
                && sPickupSensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT;
        if (oneShot) {
            sPickupArmed = false;   // a one-shot sensor is disarmed by firing
        } else {
            disarmPickup();
        }
        if (!sWakeOn || sPm == null || sPm.isInteractive() || !aodDozing(sAodArgs)) return;
        if (aodTouchWanted()) return;   // touch is on already
        // Touchscreen on for the always-on display while checking.
        if (startHeldCheck("phone picked up")) reapplyAodNow();
    }

    /** The wake-up pickup sensor, looked up once (worker thread). */
    private static Sensor pickupSensor(SensorManager sm) {
        if (sPickupLooked) return sPickupSensor;
        sPickupLooked = true;
        try {
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
                if (!s.isWakeUpSensor()) continue;
                String st = s.getStringType();
                boolean pickup = s.getType() == TYPE_PICK_UP_GESTURE
                        || (st != null && st.toLowerCase(java.util.Locale.US).contains("pick"));
                if (pickup) {
                    sPickupSensor = s;
                    break;
                }
            }
        } catch (Throwable t) {
            Module.log("double-tap-wake: sensor list unavailable", t);
        }
        return sPickupSensor;
    }

    /** Wakes for a tap felt by the motion sensor (our wake hook lets it through as a wake). */
    private static void wakeForMotionTap() {
        try {
            PowerManager.class.getMethod("wakeUp", long.class, int.class, String.class)
                    .invoke(sPm, SystemClock.uptimeMillis(), sTapReason, MOTION_DETAILS);
        } catch (Throwable t) {
            Module.log("double-tap-wake: motion tap wake failed", t);
        }
    }

    /** Ends a touch window (worker thread); waits a little while a finger from it is down. */
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
                // A tap in progress (finger landed during the window, not lifted yet): keep
                // the touchscreen on until it ends, so it completes as a tap and pairs.
                long down = sTouchDownAt, up = sTouchUpAt, cancel = sTouchCancelAt;
                boolean fingerDown = down >= mStart && up == 0 && cancel < down;
                // At most WINDOW_HOLD_STEPS waits in total (a finger resting on the screen can't
                // keep it on).
                if (fingerDown && mWaits < WINDOW_HOLD_STEPS && sWindowUntil == mUntil) {
                    long more = SystemClock.uptimeMillis() + WINDOW_HOLD_STEP_MS;
                    sWindowUntil = more;
                    sPairUntil = more + WINDOW_PAIR_GRACE_MS;
                    // Put the touchscreen back on if a doze request in between dropped it.
                    reapplyAodNow();
                    Handler h = sWorker;
                    if (h != null) {
                        h.postDelayed(new WindowClose(mStart, more, mWaits + 1),
                                WINDOW_HOLD_STEP_MS);
                        return;
                    }
                }
                // Always re-evaluate (a no-op if nothing changed): if the window was cleared
                // early (screen on) but a quick re-lock sent the always-on display on while it
                // was open, this puts it back in low-power.
                if (sWindowUntil == mUntil) sWindowUntil = 0;
                // Someone just tapped the always-on display without waking it: if the phone is
                // in their hand, keep the touchscreen on (a hand-held check decides) so their
                // next double tap is caught by touch, not by the motion sensor.
                // Judged from the recording first, the touchscreen still on meanwhile (lying
                // still, low-power right after; moving, the live check keeps it on).
                if (sWindowUntil == 0) judgeHeld(AFTER_A_TAP, true);
                reapplyAodNow();
            } catch (Throwable t) {
                Module.log("double-tap-wake: touch window close failed", t);
            }
        }
    }

    /** Keeps the processor up for a short wait, so a pending step runs on time. Worker thread. */
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

    /**
     * Unlock moments (TrustManagerService tells keystore the device is unlocked) and activity
     * starts (ActivityStarter.executeRequest), to cancel a queued card launch.
     */
    private static int installLaunchGuard(ClassLoader cl) {
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
        if (a == 0 || u == 0) {
            Module.log("double-tap-wake: launch guard incomplete (starts " + a + ", unlock " + u
                    + ")");
        }
        return a + u;
    }

    /**
     * Every vibration request reaches VibratorManagerService through these binder methods
     * (View haptics come straight here or via PhoneWindowManager, which calls in with the
     * app's uid).
     */
    private static int installTapQuiet(ClassLoader cl) {
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
        if (n == 0) Module.log("double-tap-wake: tap vibration guard unavailable");
        return n;
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
                // Event time, like finger down/up, so a cancel read late can't hide a later down.
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
    private static int installFingerWatch(ClassLoader cl) {
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
        if (n == 0) {
            Module.log("double-tap-wake: fingerprint watch unavailable (fingerprint haptics may"
                    + " wait up to " + DOWN_WAIT_MS + " ms)");
        }
        return n;
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
        // Everything below only acts on the lock screen (quietPlace), so anything else (e.g.
        // keyboard haptics while typing) leaves right away, before the arguments are parsed.
        if (!lockScreenShowing()) return;
        boolean haptic = method.startsWith("performHapticFeedback");
        String pkg = null;
        int usage = haptic ? USAGE_TOUCH : 0;   // vibrate() without attributes: USAGE_UNKNOWN
        for (Object a : param.args) {
            if (a instanceof String) {
                if (pkg == null) pkg = (String) a;
            } else if (!haptic && a != null
                    && "android.os.VibrationAttributes".equals(a.getClass().getName())) {
                Object u = Xp.callMethod(a, "getUsage");
                if (u instanceof Integer) usage = (Integer) u;
            }
        }
        boolean touch = usage == 0 || ((usage & USAGE_CLASS_MASK) == USAGE_CLASS_FEEDBACK
                && usage != USAGE_ACCESSIBILITY);
        if (!touch) return;   // alarm, ringtone, notification, accessibility: kept
        long now = SystemClock.uptimeMillis();
        if (!tapActive(now)) {
            if (!SYSTEMUI.equals(pkg) || !mayAwaitDown(now) || !quietPlace()) return;
            if (!awaitDown(seq, DOWN_WAIT_MS)) return;   // no touch: not a tap's vibration
            if (!tapActive(SystemClock.uptimeMillis())) return;
        }
        if (!quietPlace()) return;
        param.setResult(null);   // a lock screen tap's vibration: dropped
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
                if (sDropArmed && sUnlockedAt == at) sDropArmed = false;
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
        try {
            Object opts = Xp.getObjectField(req, "activityOptions");
            if (opts != null) Xp.callMethod(opts, "abort");   // cancels a pending launch animation
        } catch (Throwable ignored) { }
        param.setResult(START_CANCELED);   // the queued lock screen card launch
    }

    /** Sees lock screen card taps (alarm, weather, date) as System UI reports them. */
    private static int installSmartspaceWatch(ClassLoader cl) {
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
        if (n == 0) Module.log("double-tap-wake: smartspace watch unavailable");
        return n;
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
        sWorker.postDelayed(backFor(tap), wait);   // the lock screen card tap is undone
    }

    /** One Back per card tap, only while the lock screen and its prompt are still up. */
    private static Runnable backFor(final long tap) {
        return new Runnable() {
            @Override
            public void run() {
                if (sBackSentFor == tap) return;
                if (!lockScreenShowing() || sAltVisibility != android.view.View.VISIBLE) return;
                sBackSentFor = tap;
                pressBack();
                sDropArmed = true;   // its "open after unlock" may still be queued
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
    private static int installWindowWatch(ClassLoader cl) {
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
            } catch (Throwable ignored) { }
        }
        if (n == 0) Module.log("double-tap-wake: window watch unavailable");
        return n;
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
            CharSequence cs = ((android.view.WindowManager.LayoutParams) attrs).getTitle();
            if (cs == null) return;
            String title = cs.toString();   // runs for every window: converted once
            if (title.contains(ALT_BOUNCER_WINDOW)) {
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
            if (!title.contains(UDFPS_WINDOW)) return;
            sUdfpsSeen = true;
            if (visibility == sUdfpsVisibility) return;
            sUdfpsVisibility = visibility;
            final boolean visible = visibility == android.view.View.VISIBLE;
            Handler h = sWorker;
            if (h == null) return;
            h.post(new Runnable() {
                @Override
                public void run() {
                    // The icon shows on the lock screen (also when it comes up without the
                    // screen going off first, e.g. lockdown).
                    if (visible) ensureMonitor();
                    Receiver r = sReceiver;
                    if (r != null) r.onFingerprintIcon(visible);
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
                updateMonitorForFlags();
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

        // Screen off: the lock screen comes next, so the touch monitor is created then. Screen
        // on: a touch window still open is over.
        IntentFilter onOff = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        onOff.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(ctx, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                    sScreenOnAt = SystemClock.uptimeMillis();
                    // Awake: a pending first tap (and its window) is over, whatever woke the
                    // phone, so a single tap after the next lock can't pair with it.
                    sWindowUntil = 0;
                    sWindowFirstAt = 0;
                    sLastSingle = 0;
                    sLooseUpAt = 0;
                    sLoosePairAt = 0;
                    stopMotionTap();
                    endHeld(false);
                    disarmPickup();
                    sHeldSpentMs = 0;
                    updateRecording();
                } else {
                    sScreenOffAt = SystemClock.uptimeMillis();
                    sHeldSpentMs = 0;   // a new time allowance for each time the screen goes off
                    ensureMonitor();
                    onScreenOffForShaking();
                    updateRecording();
                    // The recording since screen-off ends here unless low-power started.
                    final long off = sScreenOffAt;
                    sWorker.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (sScreenOffAt == off) updateRecording();
                        }
                    }, PRE_RECORD_MS + 100L);
                }
            }
        }, onOff, sWorker);

        sInitDone = true;
        sWorker.post(new Runnable() {
            @Override
            public void run() {
                ensureMonitor();   // boots to the lock screen
            }
        });
    }

    /** Anything that needs the lock screen touch monitor is on. */
    private static boolean monitorWanted() {
        return sSleepOn || sWakeOn;
    }

    /** Creates the lock screen touch monitor if it is wanted and missing. Worker thread. */
    private static void ensureMonitor() {
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

    /** Removes the monitor: the input system stops copying touches to it. Worker thread. */
    private static void releaseMonitor() {
        Receiver r = sReceiver;
        Object monitor = sMonitor;
        if (r == null && monitor == null) return;
        sReceiver = null;
        sMonitor = null;
        sChannel = null;
        // The touch that was in progress (the first one after unlocking) never reports its
        // finger up to us now: mark it ended, so it can't look "still down" later.
        sTouchCancelAt = SystemClock.uptimeMillis();
        // Same order as System UI's gesture handlers: the receiver, then the monitor.
        try {
            if (r != null) r.dispose();
        } catch (Throwable ignored) { }
        try {
            if (monitor != null) Xp.callMethod(monitor, "dispose");
        } catch (Throwable t) {
            Module.log("double-tap-wake: monitor dispose failed", t);
        }
    }

    /** After a settings change: monitor off if nothing needs it, on if the lock screen is up. */
    private static void updateMonitorForFlags() {
        if (!monitorWanted()) {
            releaseMonitor();
        } else if (sPm != null && (!sPm.isInteractive() || keyguardShowing())) {
            ensureMonitor();
        }
    }

    /** The lock screen is up, even if an app (camera, alarm, call) covers it. */
    private static boolean keyguardShowing() {
        try {
            return !Boolean.FALSE.equals(Xp.callMethod(sPwm, "isKeyguardShowing"));
        } catch (Throwable t) {
            return true;   // unknown: keep the monitor
        }
    }

    /** registerReceiver, not exported (Android 13+ requires saying so), on {@code handler}. */
    private static void registerReceiver(Context ctx, BroadcastReceiver r, IntentFilter f,
                                         Handler handler) {
        try {
            try {
                // registerReceiver(..., flags) with RECEIVER_NOT_EXPORTED (4).
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
        sSleepOn = sMasterOn;    // double tap the lock screen to sleep: part of the master switch
        sLockShortcuts = Feature.on(sCtx, Feature.LOCK_SHORTCUTS);
        if (!sWakeOn && sWorker != null) {
            sWorker.post(new Runnable() {
                @Override
                public void run() {
                    endHeld(false);   // re-applied just below
                    disarmPickup();
                }
            });
        }
        reapplyAodState();       // double tap to wake switched: touchscreen policy may change
        updateRecording();
    }

    /** Double tap to wake needs the stock tap sensor on (the double-tap sensor depends on it). */
    private static void repairTapSensor() {
        try {
            ContentResolver r = sCtx.getContentResolver();
            if (sWakeOn && Settings.Secure.getInt(r, KEY_TAP_GESTURE, 1) == 0) {
                Settings.Secure.putInt(r, KEY_TAP_GESTURE, 1);
                Settings.Secure.putInt(r, KEY_DOUBLE_TAP_GESTURE, 1);
            }
        } catch (Throwable e) {
            Module.log("double-tap-wake: settings check failed", e);
        }
    }

    /** Posted at every wake (one instance: it carries no state). Worker thread. */
    private static final Runnable REARM_AT_WAKE = new Runnable() {
        @Override
        public void run() {
            ensureMonitor();
            Receiver r = sReceiver;
            if (r != null) r.rearm();
        }
    };

    /**
     * Runs inside PowerManagerService's lock: no settings reads, no blocking. Every wake re-arms
     * the lock screen double tap.
     */
    private static void onWakeRequest(Xp.Param param) {
        Handler h = sWorker;
        if (h != null) h.post(REARM_AT_WAKE);
        int reason = -1;
        String details = null;
        for (Object a : param.args) {
            if (reason == -1 && a instanceof Integer) reason = (Integer) a;
            if (details == null && a instanceof String) details = (String) a;
        }
        // A biometric wake of a phone that was asleep (screen-off broadcast newer than
        // screen-on): the unlocking finger may still be on the screen.
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
                // A tap reported just after we woke for one (the last of three quick taps, or
                // System UI catching up after Pixel Suite's own wake): not a new first tap.
                param.setResult(null);
                return;
            }
            // During a touch window (opened by tap 1 on the always-on display) any tap that
            // reaches System UI counts: quick taps right after tap 1 land while the touchscreen
            // is still coming back (~250 ms) and are lost, so the first one to arrive may be
            // well after the usual pairing time.
            long first = sLastSingle;
            boolean windowPair = first != 0 && first == sWindowFirstAt && now <= sPairUntil;
            if (first != 0 && (windowPair || now - first <= SINGLE_PAIR_MS)) {
                sLastSingle = 0;   // the second tap: waking
                sTapWakeAt = now;
            } else if (!isGestureEngineTap(details) && sLoosePairAt != 0
                    && now - sLoosePairAt >= 0 && now - sLoosePairAt <= LOOSE_REPORT_MS) {
                // Its first tap moved a little, so System UI didn't count it (see
                // LOOSE_SLOP_TIMES); our monitor saw it right before this one.
                sLoosePairAt = 0;
                sLooseUpAt = 0;
                sLastSingle = 0;
                sTapWakeAt = now;
            } else {
                sLastSingle = now;   // a single tap: ignored
                param.setResult(null);
                if (isGestureEngineTap(details)) afterGestureTap(now);
            }
        } else {
            sLastSingle = 0;
            sTapWakeAt = now;
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
     * After we swallowed a single tap from the touch chip's low-power gesture engine (called
     * inside PowerManagerService's lock: only posts). The engine is quiet after a tap until the
     * touchscreen comes back: the touch window does that at once (installAodTouch); if it can't
     * open, the pulse does.
     */
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

    /**
     * Asks System UI for a short always-on-display pulse, off PowerManagerService's lock. This
     * re-arms the touch chip after we swallowed one of its taps (see the class comment).
     * Skipped if the phone woke meanwhile or a newer tap came.
     */
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
                    // UserHandle.ALL is a system API, not in the android-23 compile jar.
                    UserHandle all = (UserHandle) UserHandle.class.getField("ALL").get(null);
                    ctx.sendBroadcastAsUser(i, all);
                } catch (Throwable t) {
                    Module.log("double-tap-wake: pulse request failed", t);
                }
                confirmPulse(tapAt);
            }
        }, PULSE_DELAY_MS);
    }

    /**
     * System UI doesn't pulse while the always-on display is paused (it was in a pocket or
     * face down, and the phone was just taken out): the tap engine then stays quiet after the
     * tap we swallowed, and every further tap does nothing. That tap already passed System
     * UI's proximity check (it reported it), so if no pulse shows within PULSE_CONFIRM_MS, the
     * tap wakes the phone: better one tap than none working. Measured: out of a pocket, tap 1
     * swallowed, the pulse never came, and the phone stayed asleep through several taps.
     */
    private static final long PULSE_CONFIRM_MS = 400L;

    private static void confirmPulse(final long tapAt) {
        Handler h = sWorker;
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sPm == null || sPm.isInteractive() || sLastSingle != tapAt) return;
                if (aodShowing(sAodArgs)) return;   // the pulse (or the always-on display) came
                // No pulse (always-on display paused): wake on the tap.
                sLastSingle = 0;
                wakeForTap();
            }
        }, PULSE_CONFIRM_MS);
    }

    /** A double tap seen by our monitor while the screen is off (worker thread). */
    private static void onDozeDoubleTap() {
        final long single = sLastSingle;
        long now = SystemClock.uptimeMillis();
        // Only when System UI took the first tap as a tap (and swallowed it as a single).
        if (single == 0 || now - single > BACKUP_PAIR_MS) return;
        Handler h = sWorker;
        if (h == null) return;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                // System UI woke the phone after all (its double tap, or a paired single).
                if (sPm == null || sPm.isInteractive() || sLastSingle != single) return;
                // System UI missed the second tap (lock screen still coming up): wake from
                // Pixel Suite's own double tap.
                wakeForTap();
            }
        }, BACKUP_WAIT_MS);
    }

    /** Wakes like a tap to wake (our wake hook sees a double tap and lets it through). */
    private static void wakeForTap() {
        try {
            PowerManager.class.getMethod("wakeUp", long.class, int.class, String.class)
                    .invoke(sPm, SystemClock.uptimeMillis(), sTapReason, BACKUP_DETAILS);
        } catch (Throwable t) {
            Module.log("double-tap-wake: backup wake failed", t);
        }
    }

    // ------------------------------------------------------------------ lock screen taps

    private static final class Receiver extends InputEventReceiver {
        private boolean mArmed = true;
        private boolean mIgnore, mTap;
        /** This touch started right after a tap wake (a leftover of the wake taps). */
        private boolean mLeftover;
        /** We cancelled this touch for the lock screen: its movement can't open anything. */
        private boolean mPilfered;
        private long mDownAt, mPrevUpAt;
        private float mDownX, mDownY, mPrevX, mPrevY;

        Receiver(InputChannel channel, Looper looper) {
            super(channel, looper);
        }

        // Backup double tap while the screen is off (touchscreen awake: the first seconds
        // after locking, or the always-on display in full touch mode).
        private boolean mDzActive, mDzTap, mDzMulti;
        private long mDzDownAt, mDzPrevUpAt;
        private float mDzX, mDzY, mDzPrevX, mDzPrevY, mDzMove;

        /**
         * System UI recognises the double tap to wake from touches on its own window. Right
         * after locking from an unlocked phone it swaps the shade for the lock screen about a
         * second later (once the screen-off animation ends); a double tap that straddles that
         * swap loses its second tap there, so only the first arrives (as a single tap, which
         * we ignore) and the phone stays asleep. This monitor still sees both taps. If System
         * UI reported the first as a single tap (so it accepted it as a real tap: not in a
         * pocket, etc.), the two make a proper double tap here, and the phone is still asleep
         * a moment later, wake it the same way System UI would have.
         */
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
                    // Close after a short touch that System UI may not have counted (see
                    // LOOSE_SLOP_TIMES): if System UI reports this one as a single tap, wake.
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
                    // A short touch, even one that moved a little, can be the first tap of a
                    // double tap System UI only half saw (see LOOSE_SLOP_TIMES).
                    boolean shortTouch = !mDzMulti && held <= TAP_MAX_MS
                            && mDzMove <= LOOSE_SLOP_TIMES * sTouchSlop;
                    sLooseUpAt = shortTouch ? up : 0;
                    sLooseX = mDzX;
                    sLooseY = mDzY;
                    if (!mDzTap) return;   // not a tap (moved, second finger, taken away)
                    if (held > TAP_MAX_MS) {   // held: not a tap
                        mDzPrevUpAt = 0;
                        return;
                    }
                    float dx = mDzX - mDzPrevX, dy = mDzY - mDzPrevY;
                    boolean close = dx * dx + dy * dy <= (float) sDoubleTapSlop * sDoubleTapSlop;
                    if (mDzPrevUpAt != 0 && close && mDzDownAt - mDzPrevUpAt <= DOUBLE_GAP_MS) {
                        mDzPrevUpAt = 0;
                        // A double tap seen too late is ignored.
                        if (SystemClock.uptimeMillis() - up <= STALE_TOUCH_MS) onDozeDoubleTap();
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
                    trackQuietTap((MotionEvent) event);
                    if (sWakeOn) onDozeTouch((MotionEvent) event);
                    if (sSleepOn || sWakeOn) onTouch((MotionEvent) event);
                }
            } catch (Throwable t) {
                if (!sTouchErrorLogged) {   // the touch path: logged once
                    sTouchErrorLogged = true;
                    Module.log("double-tap-wake: touch handling failed", t);
                }
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
                    rearm();   // back at the lock screen: double tap on
                }
            } else if (!chip) {
                disarm();   // PIN pad or fingerprint prompt open
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
            disarm();   // PIN pad or fingerprint prompt open
        }

        /** Lock screen double tap off until the next wake. */
        private void disarm() {
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
                    // A leftover from the wake taps (e.g. the third of three quick taps) can't
                    // open anything on the lock screen; one in the first LEFTOVER_COUNT_MS
                    // doesn't count toward a lock screen double tap either.
                    mLeftover = isLeftoverAfterTapWake(ev, LEFTOVER_COUNT_MS);
                    if (!mIgnore) mPilfered = cancelIfLeftoverAfterTapWake(ev);
                    if (!sSleepOn) mIgnore = true;
                    if (screenOn && !lock) {   // unlocked: start clean next time
                        mArmed = true;
                        mPrevUpAt = 0;
                        // Unlocked for real (not just an app over the lock screen): nothing
                        // here is needed until the lock screen is back, so stop receiving
                        // touches. Posted, so this event is finished first.
                        if (!keyguardShowing()) {
                            final Receiver self = this;
                            sWorker.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (sReceiver == self) releaseMonitor();
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
                        // Second tap after a card tap: its prompt may be up; tapping it would
                        // open the PIN pad. Keep this tap off it (it still counts for us).
                        if (pilfer(0L)) mPilfered = true;
                    }
                    if (mPrevUpAt != 0 && down - mPrevUpAt > DOUBLE_GAP_MS) {
                        // The fingerprint icon tells us when the PIN pad opens; without it,
                        // a lone tap (which may open the PIN pad) switches the double tap off.
                        if (sUdfpsSeen) {
                            mPrevUpAt = 0;
                        } else {
                            disarm();
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
                        if (offWithoutIcon()) disarm();   // two fingers
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!mIgnore && mTap && (Math.abs(ev.getX() - mDownX) > sTouchSlop
                            || Math.abs(ev.getY() - mDownY) > sTouchSlop)) {
                        mTap = false;
                        mPrevUpAt = 0;   // not a tap: no double tap across it
                        if (offWithoutIcon()) disarm();   // swipe
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
                        if (offWithoutIcon()) disarm();   // long press
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
                        // Seen too late to act on, the user has moved on (turning the screen
                        // off now would come out of nowhere); not armed, the PIN pad, a prompt
                        // or the shade was open: ignored.
                        if (SystemClock.uptimeMillis() - up <= STALE_TOUCH_MS && mArmed) {
                            onLockDoubleTap();
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
        if (!isLeftoverAfterTapWake(ev, WAKE_GUARD_MS)) return false;
        int height = screenHeight();
        if (height > 0 && ev.getY() > height * WAKE_GUARD_TOP) return false;
        return pilfer(0L);
    }

    /** The touch started within {@code ms} after a tap wake. */
    private static boolean isLeftoverAfterTapWake(MotionEvent ev, long ms) {
        long wakeAt = sTapWakeAt;
        if (wakeAt == 0) return false;
        long since = ev.getEventTime() - wakeAt;
        return since >= 0 && since <= ms;
    }

    /**
     * Cancels the current touch for every window (our monitor keeps receiving it), waiting up
     * to {@code waitMs} for the cancel to be done. Returns true if it was cancelled.
     */
    private static boolean pilfer(long waitMs) {
        final Object monitor = sMonitor;
        if (monitor == null) return false;
        final java.util.concurrent.CountDownLatch done =
                waitMs > 0 ? new java.util.concurrent.CountDownLatch(1) : null;
        // On its own thread: the call can block for seconds (seen once: 4.4 s, which held up
        // every touch behind it, so a lock screen double tap ran 4 s late and the taps meant
        // for it reached the lock screen). Only the touch it was asked for is cancelled, and
        // only while that finger is still down: a later one must never be.
        final long down = sTouchDownAt;
        pilferThread().post(new Runnable() {
            @Override
            public void run() {
                try {
                    long up = sTouchUpAt, cancel = sTouchCancelAt;
                    // Too late: the touch was over.
                    if (sTouchDownAt != down || (up != 0 && up >= down) || cancel >= down) return;
                    Xp.callMethod(monitor, "pilferPointers");
                } catch (Throwable t) {
                    Module.log("double-tap-wake: could not cancel touch", t);
                } finally {
                    if (done != null) done.countDown();
                }
            }
        });
        if (done != null) {
            try {
                done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) { }
        }
        return true;
    }

    /** How long the fingerprint callback waits for its cancel (see endTouchAtUnlock). */
    private static final long PILFER_WAIT_MS = 50L;

    /** Touch events handled later than this after they happened aren't acted on. */
    private static final long STALE_TOUCH_MS = 600L;
    /** A failure in the touch path was logged (once: it would repeat on every touch). */
    private static volatile boolean sTouchErrorLogged;
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
        if (!screenOn || !lockScreenShowing()) return;
        long tap = sChipTapAt;
        Handler h = sWorker;
        if (h != null && undoingChips() && chipTapRecent() && sBackSentFor != tap) {
            // The first tap hit a card: close its prompt (drops the queued app), then sleep.
            // Give the prompt time to appear and take focus first.
            long shown = sAltShownAt;
            long now = SystemClock.uptimeMillis();
            long wait = (shown != 0 && shown >= tap - 300)
                    ? Math.max(0, shown + BACK_DELAY_MS - now) : BACK_DELAY_MS;
            h.postDelayed(backFor(tap), wait);   // the card prompt closed before sleeping
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

}
