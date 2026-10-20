package io.github.pixelsuite;

import android.content.Context;
import android.provider.Settings;

/**
 * 2.0.4 build 190 (owner): one switch for every surface Pixel Suite draws, under Themes, a three-step slider:
 *
 *  0  Blur Off      no blur anywhere on the phone (the system's window blur is turned off too, as Super Dark does):
 *                   light theme keeps the stock white surfaces; dark theme gets Pixel Suite's tinted black, solid;
 *                   Super Dark-Mode stays pure black.
 *  1  Stock         Google's own surfaces, untouched: the launcher's folders, menus and drawer, Photos' bar and
 *                   messages, every toast keep their stock look (sizes, places and positions stay as Pixel Suite
 *                   sets them; only the surface itself is stock). Super Dark-Mode still applies when it is on.
 *  2  Frosted Glass the folders' frost on all of them: what lies behind blurred, under the tint (the default).
 *
 * Read through Settings.Secure like every other switch (readable by every app Pixel Suite runs in), at most twice a
 * second per process for the hot paths that ask every frame.
 */
final class SurfaceStyle {
    private SurfaceStyle() {}

    static final String KEY = "pixelsuite_surface_style";
    static final int OFF = 0, STOCK = 1, FROSTED = 2;
    static final String[] NAMES = {"Blur Off", "Stock", "Frosted Glass"};

    private static long at; private static int value = FROSTED;

    /** The chosen style, 0..2 (Frosted Glass when nothing was chosen). */
    static int get(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        if (at == 0 || now - at > 500) {
            try { value = Math.max(OFF, Math.min(FROSTED, Settings.Secure.getInt(c.getContentResolver(), KEY, FROSTED))); } catch (Throwable t) { value = FROSTED; }
            at = now;
        }
        return value;
    }

    /** Re-read on the next call (after the setting changed). */
    static void refresh() { at = 0; }

    /** Blur is allowed by the style (Frosted Glass only; the phone's own blur setting still has the last word). */
    static boolean blur(Context c) { return get(c) == FROSTED; }

    /**
     * Pixel Suite draws its own surfaces (tinted, blurred under Frosted Glass): true for Frosted Glass, for Blur Off in
     * dark theme (tinted black, solid), and whenever Super Dark-Mode is in effect (black). False for Stock, and for Blur
     * Off in light theme, where the owner wants the stock white.
     */
    static boolean restyled(Context c) {
        int s = get(c);
        if (s == FROSTED) return true;
        if (SuperDark.inEffect(c)) return true;
        return s == OFF && FrostedSurface.dark(c);
    }
}
