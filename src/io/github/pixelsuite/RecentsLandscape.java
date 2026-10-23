package io.github.pixelsuite;

import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

final class RecentsLandscape {
    private RecentsLandscape() {}
    private static ClassLoader cl;
    private static boolean barHidden;
    private static java.lang.ref.WeakReference<View> last;

    static void install(ClassLoader loader) {
        cl = loader;
        int n = 0;
        try {
            Class<?> taskView = Xp.findClass("com.android.quickstep.views.TaskView", cl);
            n += Xp.hookAllMethods(taskView, "onLayout", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { try { centreChips((View) p.thisObject); } catch (Throwable ignored) { } }
            }).size();
            Class<?> recents = Xp.findClass("com.android.quickstep.views.RecentsView", cl);
            Xp.Callback watch = new Xp.Callback() {
                @Override protected void afterHookedMethod(final Xp.Param p) {
                    try {
                        final View rv = (View) p.thisObject;
                        if (Xp.getAdditionalInstanceField(rv, "pixelsuite_bar_watch") == null) {
                            ViewTreeObserver.OnGlobalLayoutListener l = new ViewTreeObserver.OnGlobalLayoutListener() { public void onGlobalLayout() {
                                View now = last == null ? null : last.get();
                                if (now != null && now != rv) {
                                    try { rv.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) { }
                                    return;
                                }
                                update(rv);
                            } };
                            rv.getViewTreeObserver().addOnGlobalLayoutListener(l);
                            Xp.setAdditionalInstanceField(rv, "pixelsuite_bar_watch", l);
                        }
                        View was = last == null ? null : last.get();
                        if (was == null || was == rv || rv.isAttachedToWindow() || !was.isAttachedToWindow()) last = new java.lang.ref.WeakReference<View>(rv);
                        rv.post(new Runnable() { public void run() { update(rv); } });
                    } catch (Throwable ignored) { }
                }
            };
            n += Xp.hookAllMethods(recents, "animateRecentsRotationInPlace", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        View rv = (View) p.thisObject;
                        if (p.args.length != 1 || !(p.args[0] instanceof Integer) || !rv.isShown()) return;
                        int r = (Integer) p.args[0];
                        sensed = r;
                        turned(rv, r == 1 || r == 3 || landscape(rv));
                    } catch (Throwable ignored) { }
                }
            }).size();
            n += Xp.hookAllMethods(recents, "setOverviewStateEnabled", watch).size();
            try {
                final Class<?> state = Xp.findClass("com.android.quickstep.util.RecentsOrientedState", cl);
                n += Xp.hookAllMethods(Xp.findClass("com.android.quickstep.util.RecentsOrientedState$1", cl), "onOrientationChanged", new Xp.Callback() {
                    @Override protected void beforeHookedMethod(Xp.Param p) {
                        try {
                            final View rv = last == null ? null : last.get();
                            if (rv == null || !rv.isShown() || p.args.length != 1 || !(p.args[0] instanceof Integer)) return;
                            int deg = (Integer) p.args[0];
                            if (deg < 0) return;
                            Object outer = Xp.getObjectField(p.thisObject, "this$0");
                            int now = sensed >= 0 ? sensed : phoneRotation(outer); if (now < 0) now = 0;
                            final int r = ((Number) Xp.callStaticMethod(state, "getRotationForUserDegreesRotated", (float) deg, now)).intValue();
                            sensed = r;
                            if (!autoRotate(rv)) return;
                            rv.post(new Runnable() { public void run() {
                                try {
                                    Object st = Xp.getObjectField(rv, "mOverviewStateEnabled");
                                    if (st instanceof Boolean && !(Boolean) st) return;
                                    turned(rv, r == 1 || r == 3 || landscape(rv));
                                } catch (Throwable ignored) { }
                            } });
                        } catch (Throwable ignored) { }
                    }
                }).size();
            } catch (Throwable error) { Module.log("Recents landscape: early turn unavailable", error); }
            n += Xp.hookAllMethods(recents, "setVisibility", watch).size();
            n += Xp.hookAllMethods(recents, "onConfigurationChanged", watch).size();
        } catch (Throwable error) { Module.log("Recents landscape unavailable", error); }
        Module.log("Recents landscape: " + n + " hooks");
    }

    private static boolean autoRotate(View v) {
        try { return android.provider.Settings.System.getInt(v.getContext().getContentResolver(), android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1; }
        catch (Throwable t) { return true; }
    }

    static boolean landscape(View v) {
        try { return v.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; } catch (Throwable t) { return false; }
    }

    static float degrees(View rv) {
        try { Object h = Xp.callMethod(rv, "getPagedOrientationHandler"); return ((Number) Xp.callMethod(h, "getDegreesRotated")).floatValue(); } catch (Throwable t) { return 0f; }
    }
    static boolean sideways(View rv) {
        if (landscape(rv)) return true;
        float d = Math.abs(degrees(rv)) % 360f;
        if (!(d > 45f && d < 315f)) return false;
        if (!autoRotate(rv)) return false;
        int r = sensed >= 0 ? sensed : phoneRotation(orientationState(rv));
        return r < 0 || r == 1 || r == 3;
    }
    private static volatile int sensed = -1;
    private static Object orientationState(View rv) {
        try { return Xp.getObjectField(rv, "mOrientationState"); } catch (Throwable t) { return null; }
    }
    static int phoneRotation(Object state) {
        if (state == null) return -1;
        for (String name : new String[] { "mPreviousRotation", "mRotation", "mDeviceRotation" }) {
            try { Object v = Xp.getObjectField(state, name); if (v instanceof Number) return ((Number) v).intValue(); } catch (Throwable ignored) { }
        }
        return -1;
    }
    static android.graphics.RectF drawn(View v) {
        android.graphics.RectF r = new android.graphics.RectF(0, 0, v.getWidth(), v.getHeight());
        v.getMatrix().mapRect(r);
        r.offset(v.getLeft(), v.getTop());
        return r;
    }
    private static void centreChips(View tv) {
        for (Object c : RecentsLock.containers(tv)) {
            try {
                View chip = (View) Xp.callMethod(c, "getIconView");
                View content = (View) Xp.callMethod(c, "getTaskContentView");
                if (chip == null || content == null || chip.getWidth() <= 0 || chip.getParent() != content.getParent()) continue;
                float rot = ((chip.getRotation() % 360f) + 360f) % 360f;
                boolean turned = rot > 45f && rot < 315f;
                if (!turned && !landscape(tv)) continue;
                android.graphics.RectF at = drawn(chip), app = drawn(content);
                if (turned) {
                    int dy = Math.round(app.centerY() - at.centerY());
                    if (dy != 0) chip.offsetTopAndBottom(dy);
                } else {
                    int dx = Math.round(app.centerX() - at.centerX());
                    if (dx != 0) chip.offsetLeftAndRight(dx);
                }
            } catch (Throwable ignored) { }
        }
    }

    private static void update(View rv) {
        View now = last == null ? null : last.get();
        if (now != null && now != rv) return;
        boolean open = rv.isShown() && rv.isAttachedToWindow();
        try {
            Object state = Xp.getObjectField(rv, "mOverviewStateEnabled");
            if (state instanceof Boolean && !(Boolean) state) open = false;
        } catch (Throwable ignored) { }
        if (!open) sensed = -1;
        if (open && android.os.SystemClock.uptimeMillis() < turnUntil) return;
        set(rv, open && sideways(rv));
    }
    private static long turnUntil, sensorAt;
    private static void turned(View rv, boolean hide) {
        long now = android.os.SystemClock.uptimeMillis();
        if (hide != barHidden && now - sensorAt < 350) return;
        if (hide != barHidden) sensorAt = now;
        turnUntil = now + 1200;
        set(rv, hide);
    }
    private static void set(View rv, boolean hide) {
        if (hide == barHidden) return;
        barHidden = hide;
        StatusBarFade.send(rv.getContext(), hide);
    }
}
