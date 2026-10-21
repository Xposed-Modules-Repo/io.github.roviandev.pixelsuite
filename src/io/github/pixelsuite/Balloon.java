package io.github.pixelsuite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import java.util.ArrayList;
import java.util.List;

/**
 * 2.0.4 (owner): the drag buttons (Cancel / Remove / Uninstall at the top, and the drawer's "drag to home" pills) no
 * longer fade: they inflate like a balloon when a drag starts and deflate when it ends.
 *
 * Inflate: from 55% with a soft overshoot (about 6% past full size) and back, opacity reaching full in the first third.
 * Deflate: a short breath in (to 104%), then it shrinks to 50% while it fades, quicker than it came.
 * The frosted blur layer of a button (layer 0 of its background) follows the opacity, since it does not follow the view's.
 */
final class Balloon {
    private Balloon() {}
    private static final PathInterpolator EASE = new PathInterpolator(0.3f, 0f, 0.2f, 1f);

    static void inflate(final View v, long delay, final Runnable end) {
        stop(v);
        apply(v, 0.55f, 0f);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(440); a.setStartDelay(delay);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator va) {
            float t = va.getAnimatedFraction();
            if (stopped(v, va)) return;
            apply(v, inflateScale(t), Math.min(1f, t * 3f));
        }});
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            public void onAnimationCancel(Animator x) { cancelled = true; }
            public void onAnimationEnd(Animator x) { forget(v); if (!cancelled) { apply(v, 1f, 1f); if (end != null) end.run(); } }
        });
        keep(v, a); a.start();
    }

    static void deflate(final View v, long delay, final Runnable end) {
        stop(v);
        final float from = v.getAlpha();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(240); a.setStartDelay(delay);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator va) {
            float t = va.getAnimatedFraction();
            float scale = t < 0.25f ? 1f + 0.04f * EASE.getInterpolation(t / 0.25f) : 1.04f - 0.54f * EASE.getInterpolation((t - 0.25f) / 0.75f);
            float alpha = t < 0.3f ? from : from * (1f - EASE.getInterpolation((t - 0.3f) / 0.7f));
            apply(v, scale, alpha);
        }});
        a.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            public void onAnimationCancel(Animator x) { cancelled = true; }
            public void onAnimationEnd(Animator x) { forget(v); if (!cancelled && end != null) end.run(); }
        });
        keep(v, a); a.start();
    }

    /** 0.55 -> 1.06 by t = 0.6, then settles on 1. */
    static float inflateScale(float t) {
        if (t < 0.6f) return 0.55f + 0.51f * EASE.getInterpolation(t / 0.6f);
        return 1.06f - 0.06f * EASE.getInterpolation((t - 0.6f) / 0.4f);
    }

    static void apply(View v, float scale, float alpha) {
        if (v.getWidth() > 0) { v.setPivotX(v.getWidth() / 2f); v.setPivotY(v.getHeight() / 2f); }   // from its middle (a new pill has no size yet)
        v.setScaleX(scale); v.setScaleY(scale); v.setAlpha(alpha);
        Drawable bg = v.getBackground();
        if (bg instanceof LayerDrawable && ((LayerDrawable) bg).getNumberOfLayers() > 1) ((LayerDrawable) bg).getDrawable(0).setAlpha(Math.round(255 * alpha));
    }

    static void stop(View v) {
        Object a = Xp.getAdditionalInstanceField(v, "pixelsuite_balloon");
        if (a instanceof ValueAnimator) ((ValueAnimator) a).cancel();
        v.animate().cancel();
    }
    private static boolean stopped(View v, ValueAnimator va) {
        if (!MultiDrag.hiding(v)) return false;   // several apps held: Uninstall is shrinking away instead (MultiDrag.shiftBar)
        va.cancel(); return true;
    }
    /** A balloon animation is running on the view. */
    static boolean busy(View v) { Object a = Xp.getAdditionalInstanceField(v, "pixelsuite_balloon"); return a instanceof ValueAnimator && ((ValueAnimator) a).isStarted(); }
    private static void keep(View v, ValueAnimator a) { Xp.setAdditionalInstanceField(v, "pixelsuite_balloon", a); }
    private static void forget(View v) { Xp.setAdditionalInstanceField(v, "pixelsuite_balloon", null); }

    // ------------------------------------------------------------------ the top drop bar

    /** Replaces DropTargetBar.animateToVisibility(boolean): the bar is shown at once and its buttons inflate or deflate. */
    static void install() {
        try {
            int n = Xp.hookAllMethods(LauncherFeatures.type("DropTargetBar"), "animateToVisibility", new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    try { if (p.args.length == 1 && p.args[0] instanceof Boolean) { bar((ViewGroup) p.thisObject, (Boolean) p.args[0]); p.setResult(null); } }
                    catch (Throwable error) { LauncherFeatures.logOnce("Drop bar balloon unavailable, stock fade used", error); }
                }
            }).size();
            Module.log("Drop bar balloon: " + n + " hooks");
        } catch (Throwable error) { LauncherFeatures.logOnce("Drop bar balloon unavailable", error); }
    }

    static void bar(final ViewGroup bar, boolean show) {
        if (Boolean.valueOf(show).equals(LauncherFeatures.get(bar, "mVisible"))) return;
        LauncherFeatures.set(bar, "mVisible", show);
        Object running = LauncherFeatures.get(bar, "mCurrentAnimation");
        if (running instanceof android.view.ViewPropertyAnimator) ((android.view.ViewPropertyAnimator) running).cancel();
        LauncherFeatures.set(bar, "mCurrentAnimation", null);
        bar.animate().cancel();
        final List<View> buttons = new ArrayList<View>(); buttons(bar, buttons);
        if (show) {
            bar.setVisibility(View.VISIBLE); bar.setAlpha(1f);
            // A short pause first, so the buttons are laid out and blurred before they appear (preview77).
            for (int i = 0; i < buttons.size(); i++) {
                View b = buttons.get(i);
                if (MultiDrag.hiding(b)) continue;
                inflate(b, 110 + 45L * i, null);
            }
            return;
        }
        if (bar.getAlpha() <= 0f || bar.getVisibility() != View.VISIBLE || buttons.isEmpty()) { finish(bar, buttons); return; }
        final int[] left = {buttons.size()};
        for (int i = 0; i < buttons.size(); i++) {
            deflate(buttons.get(i), 25L * i, new Runnable() { public void run() { if (--left[0] == 0 && !Boolean.TRUE.equals(LauncherFeatures.get(bar, "mVisible"))) finish(bar, buttons); } });
        }
    }

    private static void finish(ViewGroup bar, List<View> buttons) {
        bar.setAlpha(0f);
        for (View b : buttons) { stop(b); apply(b, 1f, 1f); }
        Object end = LauncherFeatures.get(bar, "mFadeAnimationEndRunnable");
        if (end instanceof Runnable) ((Runnable) end).run();
    }

    static void buttons(View v, List<View> out) {
        try { if (LauncherFeatures.type("ButtonDropTarget").isInstance(v)) { if (v.getVisibility() == View.VISIBLE) out.add(v); return; } } catch (Throwable ignored) { }
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) buttons(g.getChildAt(i), out); }
    }
}
