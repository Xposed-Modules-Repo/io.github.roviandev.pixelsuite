package io.github.pixelsuite;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Three-finger swipe down takes a screenshot (system_server).
 *
 * A full-screen gesture monitor, registered at boot like the back gesture's, sees every touch.
 * When a third finger lands it takes the touch stream from the app, so nothing underneath
 * scrolls; the downward swipe then takes the screenshot through Android's own screenshot
 * service. On/off: Settings.Secure Feature.THREE_FINGER.
 */
final class ThreeFinger {

    private ThreeFinger() {}


    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String LISTENER =
            "com.android.server.wm.SystemGesturesPointerEventListener";

    private static final int FINGERS = 3;
    /** Max time from the third finger landing to recognition. */
    private static final long SWIPE_TIMEOUT_MS = 800L;
    private static final long COOLDOWN_MS = 1200L;
    private static final float DISTANCE_FRACTION = 0.12f;
    private static final float MIN_DISTANCE_DP = 80f;
    private static final float VERTICAL_RATIO = 1.2f;

    private static final int TAKE_SCREENSHOT_FULLSCREEN = 1;
    private static final int SCREENSHOT_VENDOR_GESTURE = 6;

    private static ClassLoader sClassLoader;
    private static volatile boolean sEnabled = 1 == 1;
    private static volatile boolean sInitDone;
    /** True once our gesture monitor is live; the read-only fallback then stays idle. */
    private static volatile boolean sMonitorActive;

    private static Context sContext;
    private static Object sScreenshotHelper;
    private static Handler sWorker;
    private static float sMinDistancePx;

    // Gesture monitor objects (kept so they are never garbage collected).
    private static Object sInputMonitor;
    private static InputChannel sInputChannel;
    private static Receiver sReceiver;

    

    static void installSystem(ClassLoader cl) {
        sClassLoader = cl;
        try {
            // Main path: create the gesture monitor once the system has booted.
            Xp.hookAllMethods(Xp.findClass(PWM, cl), "systemBooted",
                    new Xp.Callback() {
                        @Override
                        protected void afterHookedMethod(Xp.Param param) {
                            Context ctx = null;
                            try {
                                ctx = (Context) Xp.getObjectField(
                                        param.thisObject, "mContext");
                            } catch (Throwable ignored) { }
                            init(ctx);
                        }
                    });

            // Fallback path (and a late-init trigger if systemBooted was missed).
            Xp.hookAllMethods(Xp.findClass(LISTENER, cl), "onPointerEvent",
                    new Xp.Callback() {
                        @Override
                        protected void afterHookedMethod(Xp.Param param) {
                            if (sMonitorActive) return;
                            if (param.args.length == 0 || !(param.args[0] instanceof MotionEvent)) {
                                return;
                            }
                            if (!sInitDone) init(systemContext());
                            if (sMonitorActive) return;
                            try {
                                Detector.FALLBACK.onTouch((MotionEvent) param.args[0], false);
                            } catch (Throwable t) {
                                Module.log("fallback touch handling failed", t);
                            }
                        }
                    });
            Module.log("system hooks installed");
        } catch (Throwable t) {
            Module.log("system hook setup failed", t);
        }
    }

    // ------------------------------------------------------------------ setup

    private static synchronized void init(Context ctx) {
        if (sInitDone || ctx == null) return;
        try {
            sContext = ctx;
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            float byFraction = dm.heightPixels * DISTANCE_FRACTION;
            float byDp = MIN_DISTANCE_DP * dm.density;
            sMinDistancePx = Math.max(byDp, Math.min(byFraction, dm.heightPixels * 0.25f));

            sScreenshotHelper = Xp.findClass(
                    "com.android.internal.util.ScreenshotHelper", sClassLoader)
                    .getConstructor(Context.class).newInstance(ctx);

            HandlerThread t = new HandlerThread("3FingerShot");
            t.start();
            sWorker = new Handler(t.getLooper());

            sEnabled = isEnabled(ctx);
            ctx.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Feature.THREE_FINGER), false,
                    new ContentObserver(sWorker) {
                        @Override
                        public void onChange(boolean selfChange) {
                            sEnabled = isEnabled(sContext);
                            Module.log("enabled: " + sEnabled);
                        }
                    });
            sInitDone = true;

            createMonitor(ctx, t);
            Module.log("initialised, enabled: " + sEnabled + ", blocks app touches: "
                    + sMonitorActive + ", min swipe " + (int) sMinDistancePx + "px");
        } catch (Throwable t) {
            Module.log("init failed", t);
        }
    }

    /** Registers our gesture monitor (spy window) on the default display. */
    private static void createMonitor(Context ctx, HandlerThread thread) {
        try {
            Object im = ctx.getSystemService(Context.INPUT_SERVICE);
            sInputMonitor = Xp.callMethod(im, "monitorGestureInput",
                    "ThreeFingerScreenshot", Display.DEFAULT_DISPLAY);
            sInputChannel = (InputChannel) Xp.callMethod(sInputMonitor, "getInputChannel");
            sReceiver = new Receiver(sInputChannel, thread);
            sMonitorActive = true;
        } catch (Throwable t) {
            sMonitorActive = false;
            Module.log("gesture monitor unavailable, using read-only fallback", t);
        }
    }

    /** Takes the touch stream away from the app (it receives ACTION_CANCEL). */
    static void pilfer() {
        try {
            IBinder token = sInputChannel.getToken();
            Object im = sContext.getSystemService(Context.INPUT_SERVICE);
            Xp.callMethod(im, "pilferPointers", token);
            return;
        } catch (Throwable ignored) { }
        try {
            Xp.callMethod(sInputMonitor, "pilferPointers");
        } catch (Throwable t) {
            Module.log("pilfer failed", t);
        }
    }

    // ------------------------------------------------------------------ receiver

    /** Reads the monitor's touch stream on our own thread. */
    private static final class Receiver extends InputEventReceiver {
        Receiver(InputChannel channel, HandlerThread thread) {
            super(channel, thread.getLooper());
        }

        @Override
        public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent) {
                    Detector.MONITOR.onTouch((MotionEvent) event, true);
                }
            } catch (Throwable t) {
                Module.log("touch handling failed", t);
            } finally {
                // A monitor must always finish its events, or input would stall.
                finishInputEvent(event, false);
            }
        }
    }

    // ------------------------------------------------------------------ detection

    /** One per input path, so the two paths never share state. */
    private static final class Detector {
        static final Detector MONITOR = new Detector();
        static final Detector FALLBACK = new Detector();

        private static final int MAX = 12;
        private final int[] mId = new int[MAX];
        private final float[] mDownX = new float[MAX];
        private final float[] mDownY = new float[MAX];
        private int mCount;
        private long mThreeAt;
        private boolean mPilfered;
        private boolean mFired;
        private long mLastShotAt;

        void onTouch(MotionEvent ev, boolean canPilfer) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    reset();
                    capture(ev, ev.getActionIndex());
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    capture(ev, ev.getActionIndex());
                    if (ev.getPointerCount() == FINGERS && mThreeAt == 0) {
                        mThreeAt = ev.getEventTime();
                        // Three fingers down: stop the app from scrolling right away.
                        if (canPilfer && sEnabled && !mPilfered) {
                            mPilfered = true;
                            pilfer();
                        }
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (sEnabled && !mFired && mThreeAt != 0
                            && ev.getPointerCount() == FINGERS) {
                        detect(ev);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    reset();
                    break;
            }
        }

        private void detect(MotionEvent ev) {
            long now = ev.getEventTime();
            if (now - mThreeAt > SWIPE_TIMEOUT_MS) return;
            for (int p = 0; p < FINGERS; p++) {
                int slot = indexOf(ev.getPointerId(p));
                if (slot < 0) return;
                float dy = ev.getY(p) - mDownY[slot];
                float dx = Math.abs(ev.getX(p) - mDownX[slot]);
                if (dy < sMinDistancePx) return;
                if (dy < dx * VERTICAL_RATIO) return;
            }
            mFired = true;
            if (now - mLastShotAt < COOLDOWN_MS) return;
            mLastShotAt = now;
            takeScreenshot();
        }

        private void reset() {
            mCount = 0;
            mThreeAt = 0;
            mPilfered = false;
            mFired = false;
        }

        private void capture(MotionEvent ev, int index) {
            if (mCount >= MAX) return;
            int id = ev.getPointerId(index);
            if (indexOf(id) >= 0) return;
            mId[mCount] = id;
            mDownX[mCount] = ev.getX(index);
            mDownY[mCount] = ev.getY(index);
            mCount++;
        }

        private int indexOf(int id) {
            for (int i = 0; i < mCount; i++) if (mId[i] == id) return i;
            return -1;
        }
    }

    // ------------------------------------------------------------------ screenshot

    private static void takeScreenshot() {
        Module.log("three-finger swipe detected, taking screenshot");
        final Object helper = sScreenshotHelper;
        final Handler h = sWorker;
        if (helper == null || h == null) {
            Module.log("screenshot helper not ready");
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> consumer = Class.forName("java.util.function.Consumer");
                    Object request = buildRequest();
                    if (request != null) {
                        helper.getClass().getMethod("takeScreenshot",
                                request.getClass(), Handler.class, consumer)
                                .invoke(helper, request, h, null);
                        return;
                    }
                    helper.getClass().getMethod("takeScreenshot",
                            int.class, int.class, Handler.class, consumer)
                            .invoke(helper, TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_VENDOR_GESTURE,
                                    h, null);
                } catch (Throwable t) {
                    Module.log("screenshot request failed", t);
                }
            }
        });
    }

    private static Object buildRequest() {
        try {
            Class<?> builderCls = Xp.findClass(
                    "com.android.internal.util.ScreenshotRequest$Builder", sClassLoader);
            Object builder = builderCls.getConstructor(int.class, int.class)
                    .newInstance(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_VENDOR_GESTURE);
            Method build = builderCls.getMethod("build");
            return build.invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Context systemContext() {
        try {
            Class<?> at = Xp.findClass("android.app.ActivityThread", sClassLoader);
            Object thread = Xp.callStaticMethod(at, "currentActivityThread");
            Object ctx = Xp.callMethod(thread, "getSystemContext");
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) { }
        return null;
    }

    static boolean isEnabled(Context ctx) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(),
                    Feature.THREE_FINGER, 1) == 1;
        } catch (Throwable t) {
            return 1 == 1;
        }
    }
}
