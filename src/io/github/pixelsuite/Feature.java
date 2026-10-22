package io.github.pixelsuite;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

final class Feature {

    static final String FLASHLIGHT = "powertorch_double_press";
    static final String VOLDOWN_CAMERA = "voldowncam_enabled";
    static final String THREE_FINGER = "threefingershot_enabled";
    static final String DOUBLE_TAP_SLEEP = "pixelsuite_double_tap_sleep";
    static final String CLEAR_ALL = "pixelsuite_clear_all";
    static final String FILES_SORT = "pixelsuite_files_sort";
    static final String SCREENSHOT_FREE_CROP = "pixelsuite_screenshot_free_crop";
    static final String SCREENSHOT_DOODLES = "pixelsuite_screenshot_doodles";
    static final String PHOTOS_DOODLES = "pixelsuite_photos_doodles";
    static final String TAP_CHECK = "pixelsuite_tap_check";
    static final String DOUBLE_TAP_WAKE = "pixelsuite_double_tap_wake";
    static final String LOCK_SHORTCUTS = "pixelsuite_lockscreen_shortcuts";

    private Feature() {}

    static int defaultFor(String key) {
        if(PrivacyLock.GRACE_KEY.equals(key)||MiscOptions.defaultOff(key))return 0;
        if(LauncherFeatures.LABEL_LEVEL.equals(key))return 2;
        if(SurfaceStyle.KEY.equals(key))return SurfaceStyle.FROSTED;
        if(LauncherFeatures.GRID_SIZE.equals(key)||LauncherFeatures.DRAWER_GRID_SIZE.equals(key))return 3;
        return (LauncherFeatures.LABEL_SIZE.equals(key) || LauncherFeatures.HIDE_SHORTCUTS.equals(key) || LauncherFeatures.GRID.equals(key) || LauncherFeatures.DRAWER.equals(key) || LauncherFeatures.RENAME.equals(key) || LauncherFeatures.DRAG_PILL.equals(key) || LauncherFeatures.ICONS.equals(key) || FLASHLIGHT.equals(key) || DOUBLE_TAP_WAKE.equals(key)
                || LOCK_SHORTCUTS.equals(key) || SuperDark.KEY.equals(key)) ? 0 : 1;
    }

    static boolean on(Context ctx, String key) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), key, defaultFor(key)) == 1;
        } catch (Throwable t) {
            return defaultFor(key) == 1;
        }
    }

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
