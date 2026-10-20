package io.github.pixelsuite;

import android.content.Context;
import android.provider.Settings;

final class SurfaceStyle {
    private SurfaceStyle() {}

    static final String KEY = "pixelsuite_surface_style";
    static final int OFF = 0, STOCK = 1, FROSTED = 2;
    static final String[] NAMES = {"Blur Off", "Stock", "Frosted Glass"};

    private static long at; private static int value = FROSTED;

    static int get(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        if (at == 0 || now - at > 500) {
            try { value = Math.max(OFF, Math.min(FROSTED, Settings.Secure.getInt(c.getContentResolver(), KEY, FROSTED))); } catch (Throwable t) { value = FROSTED; }
            at = now;
        }
        return value;
    }

    static void refresh() { at = 0; }

    static boolean blur(Context c) { return get(c) == FROSTED; }

    static boolean restyled(Context c) {
        int s = get(c);
        if (s == FROSTED) return true;
        if (SuperDark.inEffect(c)) return true;
        return s == OFF && FrostedSurface.dark(c);
    }
}
