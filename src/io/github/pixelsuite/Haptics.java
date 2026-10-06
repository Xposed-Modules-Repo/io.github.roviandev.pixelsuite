package io.github.pixelsuite;

import android.content.Context;
import android.os.Vibrator;

/**
 * The one haptic used by every gesture in this module: a predefined EFFECT_CLICK.
 * Shared by the flashlight, volume-down camera and double-tap-to-sleep, so all three feel identical.
 */
final class Haptics {
    private static final int EFFECT_CLICK = 0;   // VibrationEffect.EFFECT_CLICK

    private Haptics() {}

    static void click(Context ctx) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            try {
                Class<?> effect = Class.forName("android.os.VibrationEffect");
                Object click = effect.getMethod("createPredefined", int.class)
                        .invoke(null, EFFECT_CLICK);
                Vibrator.class.getMethod("vibrate", effect).invoke(v, click);
            } catch (Throwable t) {
                v.vibrate(30L);
            }
        } catch (Throwable ignored) { }
    }
}
