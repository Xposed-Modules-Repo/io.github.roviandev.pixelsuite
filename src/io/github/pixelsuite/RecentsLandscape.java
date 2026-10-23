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

/**
 * 2.0.6-preview30 (owner), recents in landscape only:
 * - each app's name chip (the dropdown) sits centred over its app;
 * - the status bar (clock, icons, battery, signal, everything) fades away while recents is shown in landscape, and
 *   fades back when recents turns to portrait or closes (preview35: System UI fades it, see StatusBarFade).
 * Portrait recents is unchanged.
 */
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
                                // 2.0.8-preview9 (owner's 316 log, after hours of use: "show by layout | recents #1 of 2
                                // attached false"): the launcher had made a new recents, but the old one's listener stayed
                                // on the window and kept saying "closed", against the new one's "sideways". Only the
                                // recents in use decides; an old one stops listening.
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
                        View was = last == null ? null : last.get();   // preview9: an old, detached recents never takes over
                        if (was == null || was == rv || rv.isAttachedToWindow() || !was.isAttachedToWindow()) last = new java.lang.ref.WeakReference<View>(rv);
                        rv.post(new Runnable() { public void run() { update(rv); } });
                    } catch (Throwable ignored) { }
                }
            };
            // preview35 (owner: the bar went about a second after the turn): the launcher's own "the phone turned" call
            // (RecentsView.animateRecentsRotationInPlace, at the start of its turn) hides or shows it straight away.
            n += Xp.hookAllMethods(recents, "animateRecentsRotationInPlace", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        View rv = (View) p.thisObject;
                        if (p.args.length != 1 || !(p.args[0] instanceof Integer) || !rv.isShown()) return;
                        int r = (Integer) p.args[0];
                        sensed = r;   // preview93: the launcher turns recents to the phone's rotation
                        turned(rv, r == 1 || r == 3 || landscape(rv));
                    } catch (Throwable ignored) { }
                }
            }).size();
            n += Xp.hookAllMethods(recents, "setOverviewStateEnabled", watch).size();
            // preview36 (owner: still not instant): the launcher only turns after three steady sensor readings; the bar
            // follows the first reading that says the phone is now on its side (or upright again).
            try {
                final Class<?> state = Xp.findClass("com.android.quickstep.util.RecentsOrientedState", cl);
                n += Xp.hookAllMethods(Xp.findClass("com.android.quickstep.util.RecentsOrientedState$1", cl), "onOrientationChanged", new Xp.Callback() {
                    @Override protected void beforeHookedMethod(Xp.Param p) {
                        try {
                            final View rv = last == null ? null : last.get();
                            if (rv == null || !rv.isShown() || p.args.length != 1 || !(p.args[0] instanceof Integer)) return;
                            int deg = (Integer) p.args[0];
                            if (deg < 0) return;   // flat on a table: no reading
                            // preview91 (owner's video: the bar flashed with the phone sideways and recents upright): with
                            // auto-rotate off the launcher never turns recents, so the sensor alone must not hide the bar
                            // (the layout check then brought it straight back, over and over).
                            Object outer = Xp.getObjectField(p.thisObject, "this$0");
                            int now = sensed >= 0 ? sensed : phoneRotation(outer); if (now < 0) now = 0;
                            final int r = ((Number) Xp.callStaticMethod(state, "getRotationForUserDegreesRotated", (float) deg, now)).intValue();
                            sensed = r;   // preview93: the phone's own rotation, as the launcher's rule reads the sensor
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

    /** Auto-rotate is on (without it the launcher keeps recents upright whatever the sensor says). */
    private static boolean autoRotate(View v) {
        try { return android.provider.Settings.System.getInt(v.getContext().getContentResolver(), android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1; }
        catch (Throwable t) { return true; }
    }

    static boolean landscape(View v) {
        try { return v.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; } catch (Throwable t) { return false; }
    }

    /**
     * Recents shown sideways. preview31 (owner's log: nothing changed in 251): the launcher itself stays portrait and
     * turns the cards instead (its "landscape"/"seascape" layout, 90 or 270 degrees), so that is what counts, besides a
     * real landscape launcher.
     */
    static float degrees(View rv) {
        try { Object h = Xp.callMethod(rv, "getPagedOrientationHandler"); return ((Number) Xp.callMethod(h, "getDegreesRotated")).floatValue(); } catch (Throwable t) { return 0f; }
    }
    static boolean sideways(View rv) {
        if (landscape(rv)) return true;
        float d = Math.abs(degrees(rv)) % 360f;
        if (!(d > 45f && d < 315f)) return false;
        // preview93 (owner's video + log: the bar flashed while scrolling recents with the phone upright and still):
        // on Android 17 the cards' layout turns for an app last used in landscape (YouTube) while it is the centred
        // card, so the layout alone is not "the phone is sideways". The launcher's own record of the phone's rotation
        // must agree: the last sensor reading (read with the launcher's own rule), or its own field before any reading.
        // If neither can be read, the layout decides as before.
        if (!autoRotate(rv)) return false;   // without auto-rotate the launcher never turns recents for the phone
        int r = sensed >= 0 ? sensed : phoneRotation(orientationState(rv));
        return r < 0 || r == 1 || r == 3;
    }
    /** The phone's rotation from the last sensor reading in recents (0 upright, 1 / 3 sideways), or -1 before any. */
    private static volatile int sensed = -1;
    private static Object orientationState(View rv) {
        try { return Xp.getObjectField(rv, "mOrientationState"); } catch (Throwable t) { return null; }
    }
    /** The phone's rotation as the launcher tracks it (0 upright, 1 / 3 sideways, 2 upside down), or -1 if unknown. */
    static int phoneRotation(Object state) {
        if (state == null) return -1;
        for (String name : new String[] { "mPreviousRotation", "mRotation", "mDeviceRotation" }) {
            try { Object v = Xp.getObjectField(state, name); if (v instanceof Number) return ((Number) v).intValue(); } catch (Throwable ignored) { }
        }
        return -1;
    }
    /** Where a view is drawn inside its parent (its rotation, scale and translation included). */
    static android.graphics.RectF drawn(View v) {
        android.graphics.RectF r = new android.graphics.RectF(0, 0, v.getWidth(), v.getHeight());
        v.getMatrix().mapRect(r);
        r.offset(v.getLeft(), v.getTop());
        return r;
    }
    /** Sideways: every app chip of the card centred along its app's width (as the user sees it). */
    private static void centreChips(View tv) {
        for (Object c : RecentsLock.containers(tv)) {
            try {
                View chip = (View) Xp.callMethod(c, "getIconView");
                View content = (View) Xp.callMethod(c, "getTaskContentView");
                if (chip == null || content == null || chip.getWidth() <= 0 || chip.getParent() != content.getParent()) continue;
                float rot = ((chip.getRotation() % 360f) + 360f) % 360f;
                boolean turned = rot > 45f && rot < 315f;
                if (!turned && !landscape(tv)) continue;   // portrait recents: as before
                android.graphics.RectF at = drawn(chip), app = drawn(content);
                if (turned) {   // the user's left-right runs along the launcher's up-down
                    int dy = Math.round(app.centerY() - at.centerY());
                    if (dy != 0) chip.offsetTopAndBottom(dy);
                } else {
                    int dx = Math.round(app.centerX() - at.centerX());
                    if (dx != 0) chip.offsetLeftAndRight(dx);
                }
            } catch (Throwable ignored) { }
        }
    }

    /** Recents shown sideways: the status bar fades away; otherwise it is back (System UI does it, see StatusBarFade). */
    private static void update(View rv) {
        View now = last == null ? null : last.get();
        if (now != null && now != rv) return;   // preview9: an old recents no longer decides
        // preview40 (owner's video: the bar still came back for a moment during the turn): the launcher fades recents
        // out and in while it turns it, so recents' own opacity no longer counts as "closed"; its state does.
        boolean open = rv.isShown() && rv.isAttachedToWindow();
        try {
            Object state = Xp.getObjectField(rv, "mOverviewStateEnabled");
            if (state instanceof Boolean && !(Boolean) state) open = false;
        } catch (Throwable ignored) { }
        // preview39 (owner: the bar sometimes came back for a moment, or flickered, during the turn): while the turn
        // the sensor announced is still being carried out, the layout's older answer does not undo it. Closing recents
        // always brings the bar back.
        if (!open) sensed = -1;   // preview93: a reading from an earlier visit to recents no longer counts
        if (open && android.os.SystemClock.uptimeMillis() < turnUntil) return;
        set(rv, open && sideways(rv));
    }
    /** Until when the sensor's last decision stands against layout checks (the launcher's turn takes about this long). */
    private static long turnUntil, sensorAt;
    /** The phone was turned (the first sensor reading, or the launcher's own turn). */
    private static void turned(View rv, boolean hide) {
        long now = android.os.SystemClock.uptimeMillis();
        // a reading that flips straight back (the phone held near the edge) does not flip the bar within 350 ms
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
