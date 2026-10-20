package io.github.pixelsuite;

import android.content.Context;
import android.os.Vibrator;

import java.lang.reflect.Method;

/**
 * The one haptic used by every gesture in this module: a predefined EFFECT_CLICK.
 * Shared by the flashlight, volume-down camera and double-tap-to-sleep, so all three feel identical.
 * The effect and the vibrate method are looked up once (VibrationEffect is API 26, above the
 * android-23 compile jar, so it is reached by reflection).
 */
final class Haptics {
    private static final int EFFECT_CLICK = 0;   // VibrationEffect.EFFECT_CLICK
    private static final int EFFECT_TICK = 2;    // VibrationEffect.EFFECT_TICK

    private static volatile Object sClick, sTick;
    private static volatile Method sVibrate;
    private static volatile boolean sResolved;

    private Haptics() {}

    static void click(Context ctx) {
        play(ctx, true, 30L);
    }

    /**
     * A light tick (VibrationEffect.EFFECT_TICK), straight from the vibrator so it plays even when
     * a view's own haptic feedback is ignored. Falls back to a 12 ms pulse.
     */
    static void tick(Context ctx) {
        play(ctx, false, 12L);
    }

    private static void play(Context ctx, boolean click, long fallbackMs) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            resolve();
            Object effect = click ? sClick : sTick;
            Method vibrate = sVibrate;
            if (effect != null && vibrate != null) {
                try {
                    vibrate.invoke(v, effect);
                    return;
                } catch (Throwable ignored) { }
            }
            v.vibrate(fallbackMs);
        } catch (Throwable ignored) { }
    }

    private static void resolve() {
        if (sResolved) return;
        try {
            Class<?> effect = Class.forName("android.os.VibrationEffect");
            Method create = effect.getMethod("createPredefined", int.class);
            sClick = create.invoke(null, EFFECT_CLICK);
            sTick = create.invoke(null, EFFECT_TICK);
            sVibrate = Vibrator.class.getMethod("vibrate", effect);
        } catch (Throwable ignored) {
            sClick = null;
            sTick = null;
            sVibrate = null;
        }
        sResolved = true;
    }
}
