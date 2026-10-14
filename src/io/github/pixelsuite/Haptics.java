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

    private static volatile Object sClick;
    private static volatile Method sVibrate;
    private static volatile boolean sResolved;

    private Haptics() {}

    static void click(Context ctx) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            resolve();
            Object click = sClick;
            Method vibrate = sVibrate;
            if (click != null && vibrate != null) {
                try {
                    vibrate.invoke(v, click);
                    return;
                } catch (Throwable ignored) { }
            }
            v.vibrate(30L);
        } catch (Throwable ignored) { }
    }

    private static void resolve() {
        if (sResolved) return;
        try {
            Class<?> effect = Class.forName("android.os.VibrationEffect");
            sClick = effect.getMethod("createPredefined", int.class).invoke(null, EFFECT_CLICK);
            sVibrate = Vibrator.class.getMethod("vibrate", effect);
        } catch (Throwable ignored) {
            sClick = null;
            sVibrate = null;
        }
        sResolved = true;
    }
}
