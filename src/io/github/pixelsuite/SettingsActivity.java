package io.github.pixelsuite;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pixel Suite's own screen, opened from LSPosed Manager.
 *
 * Shows the same switches as Settings > System > Gestures: every switch is the feature's
 * Settings.Secure key (Feature), so the two screens always agree. This app is an ordinary app
 * that Android won't let touch those keys, so it reads and writes them as root through the
 * `settings` command (KernelSU asks once). If root isn't granted, a banner says so and the
 * switches stay read-only.
 */
public class SettingsActivity extends Activity {

    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";

    private boolean mDark;
    private float mDp;
    private int mBg, mCard, mText, mTextSecondary, mAccent;

    private final Map<String, Switch> mSwitches = new LinkedHashMap<String, Switch>();
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private TextView mBanner;
    /** True while switches are set from stored values, so their listeners don't save. */
    private boolean mUpdating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(mDark ? android.R.style.Theme_DeviceDefault_NoActionBar
                : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mDp = getResources().getDisplayMetrics().density;
        resolvePalette();
        styleWindow();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(32));

        TextView title = text("Pixel Suite", 36, mText, false);
        title.setPadding(dp(8), dp(40), dp(8), dp(6));
        page.addView(title);

        TextView subtitle = text("Turn each tweak on or off. Changes apply right away.",
                15, mTextSecondary, false);
        subtitle.setPadding(dp(8), 0, dp(8), dp(20));
        page.addView(subtitle);

        mBanner = banner();
        mBanner.setVisibility(View.GONE);
        page.addView(mBanner);

        section(page, "Buttons");
        LinearLayout buttons = group(page);
        row(buttons, Feature.FLASHLIGHT, "ic_ps_flashlight", 0xFFE37400,
                "Power button flashlight",
                "Double-press power to toggle the flashlight. Same as Toggle flashlight in "
                        + "Settings \u203a Gestures \u203a Double press power button.");
        row(buttons, Feature.VOLDOWN_CAMERA, "ic_ps_camera", 0xFF1A73E8,
                "Volume-down camera",
                "Double-press volume down on the Always On Display to open the camera. "
                        + "Not while media plays or during calls.");
        finishGroup(buttons);

        section(page, "Gestures");
        LinearLayout gestures = group(page);
        row(gestures, Feature.THREE_FINGER, "ic_ps_threefinger", 0xFF00897B,
                "Three-finger screenshot",
                "Swipe down anywhere with three fingers to take a screenshot.");
        row(gestures, Feature.DOUBLE_TAP_SLEEP, "ic_ps_sleep", 0xFF6750A4,
                "Double tap to sleep",
                "Double-tap empty space on the home screen to lock the phone.");
        row(gestures, Feature.TAP_CHECK, "ic_ps_tapwake", 0xFF3949AB,
                "Tap or Double tap to wake",
                "Double-tap to wake the screen, double-tap the lock screen to sleep, and the lock "
                        + "screen shortcuts. Choose the options in Settings \u203a System \u203a "
                        + "Gestures \u203a Tap or Double Tap to check phone.");
        finishGroup(gestures);

        section(page, "Apps");
        LinearLayout apps = group(page);
        row(apps, Feature.CLEAR_ALL, "ic_ps_clearall", 0xFFD93025,
                "Clear all in recents",
                "Always-visible Clear all next to Screenshot in recents. Pixel Launcher "
                        + "restarts when you change this.");
        row(apps, Feature.FILES_SORT, "ic_ps_files", 0xFF1E8E3E,
                "Files: newest first",
                "Folders in Files by Google open sorted newest first instead of by name.");
        finishGroup(apps);

        TextView footer = text("The gestures also appear in Settings \u203a System \u203a "
                + "Gestures. Both places control the same switches.\n\n"
                + "Pixel Suite " + versionName(), 13, mTextSecondary, false);
        footer.setPadding(dp(8), dp(20), dp(8), 0);
        page.addView(footer);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();   // the phone's Settings may have changed something meanwhile
    }

    @Override
    protected void onDestroy() {
        mIo.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        final String[] keys = mSwitches.keySet().toArray(new String[0]);
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                final int[] values = Root.readSecure(keys);
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (values == null) {
                            showRootBanner(true);
                            for (Switch sw : mSwitches.values()) sw.setEnabled(false);
                            return;
                        }
                        showRootBanner(false);
                        mUpdating = true;
                        for (int i = 0; i < keys.length; i++) {
                            Switch sw = mSwitches.get(keys[i]);
                            sw.setChecked(values[i] == 1);
                            sw.setEnabled(true);
                        }
                        mUpdating = false;
                    }
                });
            }
        });
    }

    private void save(final String key, final boolean on, final Switch sw) {
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Root.run(command(key, on)) != null;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) return;
                        mUpdating = true;
                        sw.setChecked(!on);   // put the switch back
                        mUpdating = false;
                        showRootBanner(true);
                        Toast.makeText(SettingsActivity.this, "Couldn't save: root access needed",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    /** The root command that applies one switch. */
    private static String command(String key, boolean on) {
        String put = "settings put secure " + key + " " + (on ? 1 : 0);
        if (Feature.FLASHLIGHT.equals(key) && on) {
            // Same as picking Toggle flashlight in Settings: double press on, target on Camera
            // (that keeps the gesture armed; the module turns it into the flashlight).
            return put + "; settings put secure " + Flashlight.KEY_TARGET + " 0"
                    + "; settings put secure " + Flashlight.KEY_ENABLED + " 1";
        }
        if (Feature.CLEAR_ALL.equals(key)) {
            return put + "; am force-stop " + LAUNCHER;   // the button is built with recents
        }
        return put;
    }

    // ------------------------------------------------------------------ building blocks

    private void row(LinearLayout group, final String key, String icon, int iconColor,
                     String title, String summary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(16), dp(16), dp(16));
        row.setMinimumHeight(dp(72));

        FrameLayout badge = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(iconColor);
        badge.setBackground(circle);
        ImageView iv = new ImageView(this);
        Drawable d = drawable(icon);
        if (d != null) iv.setImageDrawable(d);
        badge.addView(iv, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(40));
        blp.rightMargin = dp(16);
        row.addView(badge, blp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 17, mText, false));
        TextView s = text(summary, 14, mTextSecondary, false);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setEnabled(false);   // until the stored value is loaded
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!mUpdating) save(key, on, sw);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(12);
        row.addView(sw, slp);
        mSwitches.put(key, sw);

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sw.isEnabled()) sw.toggle();
            }
        });
        row.setContentDescription(title);

        group.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView banner() {
        TextView b = text("Pixel Suite needs root access to change these switches. Allow it "
                + "in KernelSU, then reopen this screen. Settings \u203a System \u203a Gestures "
                + "works without it.", 14, mDark ? 0xFFFFB4AB : 0xFF8C1D18, false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(mDark ? 0xFF5C1F1A : 0xFFFCDAD6);
        b.setBackground(bg);
        b.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void showRootBanner(boolean show) {
        mBanner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void section(LinearLayout page, String label) {
        TextView t = text(label, 14, mAccent, true);
        t.setPadding(dp(8), dp(16), dp(8), dp(8));
        page.addView(t);
    }

    private LinearLayout group(LinearLayout page) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        page.addView(g);
        return g;
    }

    private void finishGroup(LinearLayout g) {
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            float big = dp(24), small = dp(6);
            float top = i == 0 ? big : small;
            float bottom = i == n - 1 ? big : small;
            View row = g.getChildAt(i);
            row.setBackground(rowBackground(top, bottom));
            if (i > 0) {
                ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = dp(3);
            }
        }
    }

    private Drawable rowBackground(float top, float bottom) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(mCard);
        shape.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        int ripple = mDark ? 0x33FFFFFF : 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), shape, mask);
    }

    private TextView text(String s, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private void resolvePalette() {
        if (mDark) {
            mBg = sys("system_surface_container_dark", 0xFF211F26);
            mCard = sys("system_surface_container_high_dark", 0xFF2B2930);
            mText = sys("system_on_surface_dark", 0xFFE6E1E5);
            mTextSecondary = sys("system_on_surface_variant_dark", 0xFFCAC4D0);
            mAccent = sys("system_primary_dark", 0xFFD0BCFF);
        } else {
            mBg = sys("system_surface_container_light", 0xFFF3EDF7);
            mCard = sys("system_surface_bright_light", 0xFFFEF7FF);
            mText = sys("system_on_surface_light", 0xFF1D1B20);
            mTextSecondary = sys("system_on_surface_variant_light", 0xFF49454F);
            mAccent = sys("system_primary_light", 0xFF6750A4);
        }
    }

    @SuppressWarnings("deprecation")
    private void styleWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(mBg));
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        View decor = w.getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!mDark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= 0x00000010;   // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR (API 26)
        }
        decor.setSystemUiVisibility(flags);
    }

    private int sys(String name, int fallback) {
        try {
            int id = getResources().getIdentifier(name, "color", "android");
            if (id != 0) return getResources().getColor(id, getTheme());
        } catch (Throwable ignored) { }
        return fallback;
    }

    private Drawable drawable(String name) {
        try {
            int id = getResources().getIdentifier(name, "drawable", getPackageName());
            if (id != 0) return getResources().getDrawable(id, getTheme());
        } catch (Throwable ignored) { }
        return null;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private int dp(float v) {
        return Math.round(v * mDp);
    }
}
