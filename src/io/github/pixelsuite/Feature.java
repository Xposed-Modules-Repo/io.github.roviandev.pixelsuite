package io.github.pixelsuite;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * The on/off switches, all in Settings.Secure. One key per feature, shared by the phone's
 * Settings > System > Gestures pages and Pixel Suite's own screen, so both always agree.
 *
 * Settings.Secure is read natively by every process involved: system_server and system apps
 * (Settings, System UI, Pixel Launcher, Files) are exempt from Android's settings-read
 * restriction. The keys of the existing gestures are kept, so current choices carry over.
 */
final class Feature {

    /** Power double press toggles the flashlight (the "Toggle flashlight" radio). Default off. */
    static final String FLASHLIGHT = "powertorch_double_press";
    static final String VOLDOWN_CAMERA = "voldowncam_enabled";
    static final String THREE_FINGER = "threefingershot_enabled";
    static final String DOUBLE_TAP_SLEEP = "pixelsuite_double_tap_sleep";
    static final String CLEAR_ALL = "pixelsuite_clear_all";
    static final String FILES_SORT = "pixelsuite_files_sort";
    /** Screenshot editor (Pixel Studio): crop without the stock minimum. Default on. */
    static final String SCREENSHOT_FREE_CROP = "pixelsuite_screenshot_free_crop";
    /** Screenshot editor (Pixel Studio): Doodles next to the pen, with shapes and colors. Default on. */
    static final String SCREENSHOT_DOODLES = "pixelsuite_screenshot_doodles";
    /**
     * Master switch for "Tap or Double tap to wake". On, it brings: tap or double tap to wake
     * (DOUBLE_TAP_WAKE picks which; on the Always On Display the touchscreen stays on while
     * the phone is in the hand, and lying still the second tap of a double tap is felt by the
     * motion sensor), double tap the lock screen to sleep, and the
     * lock screen behavior (LOCK_SHORTCUTS). The choices live on Settings > System >
     * Gestures > Tap or Double Tap to check phone. Default on.
     */
    static final String TAP_CHECK = "pixelsuite_tap_check";
    /** 1 = double tap to wake, 0 = tap to wake. Default tap. */
    static final String DOUBLE_TAP_WAKE = "pixelsuite_double_tap_wake";
    /**
     * Lock screen behavior. 0 (default, "Modded behavior"): the cards under the clock (alarm,
     * weather, date) act like empty space and lock screen taps don't vibrate, so a double tap to
     * sleep never opens anything. 1 ("Stock behavior"): the cards open their apps and taps
     * vibrate as usual.
     */
    static final String LOCK_SHORTCUTS = "pixelsuite_lockscreen_shortcuts";

    private Feature() {}

    static int defaultFor(String key) {
        if(LauncherFeatures.LABEL_LEVEL.equals(key))return 2;
        if(LauncherFeatures.GRID_SIZE.equals(key)||LauncherFeatures.DRAWER_GRID_SIZE.equals(key))return 3;
        return (LauncherFeatures.LABEL_SIZE.equals(key) || LauncherFeatures.HIDE_SHORTCUTS.equals(key) || LauncherFeatures.GRID.equals(key) || LauncherFeatures.DRAWER.equals(key) || LauncherFeatures.RENAME.equals(key) || LauncherFeatures.DRAG_PILL.equals(key) || LauncherFeatures.ICONS.equals(key) || FLASHLIGHT.equals(key) || DOUBLE_TAP_WAKE.equals(key)
                || LOCK_SHORTCUTS.equals(key)) ? 0 : 1;   // everything else starts on
    }

    static boolean on(Context ctx, String key) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), key, defaultFor(key)) == 1;
        } catch (Throwable t) {
            return defaultFor(key) == 1;
        }
    }

    /** Throttled read for very hot paths (Files sorting): at most one lookup a second. */
    static final class Cached {
        private final String mKey;
        private long mAt;
        private boolean mValue;

        Cached(String key) { mKey = key; }

        synchronized boolean on(Context ctx) {
            long now = SystemClock.uptimeMillis();
            if (mAt == 0 || now - mAt > 1000L) {
                mValue = Feature.on(ctx, mKey);
                mAt = now;
            }
            return mValue;
        }
    }
}
