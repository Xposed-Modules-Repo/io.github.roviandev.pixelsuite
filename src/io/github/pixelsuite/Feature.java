package io.github.pixelsuite;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * The six on/off switches, all in Settings.Secure. One key per feature, shared by the phone's
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
    /**
     * Master switch for "Tap or Double tap to wake" (double tap to wake, lock screen double tap
     * to sleep, lock screen shortcuts). The details live on the Settings > System > Gestures
     * page. Default on, so the per-option choices made with v1.1.0 keep working.
     */
    static final String TAP_CHECK = "pixelsuite_tap_check";
    static final String DOUBLE_TAP_WAKE = "pixelsuite_double_tap_wake";
    static final String LOCK_SLEEP = "pixelsuite_lockscreen_double_tap_sleep";
    /** Keep the lock screen clock/alarm/weather tappable. Default off (they are suppressed). */
    static final String LOCK_SHORTCUTS = "pixelsuite_lockscreen_shortcuts";

    private Feature() {}

    static int defaultFor(String key) {
        return (FLASHLIGHT.equals(key) || DOUBLE_TAP_WAKE.equals(key) || LOCK_SLEEP.equals(key)
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
