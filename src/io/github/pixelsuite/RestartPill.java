package io.github.pixelsuite;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small floating pill in Pixel Suite's own screen that offers to restart the app a changed
 * setting lives in (Pixel Launcher, Files). It sits in the bottom corner over every page,
 * follows the user through scrolling and between pages (the pending list is shared), and is
 * never automatic: restarting the launcher closes whatever the user has open there, so the user
 * picks the moment. Settings that apply live never show it.
 */
final class RestartPill {
    static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    static final String FILES = "com.google.android.apps.nbu.files";
    /** Apps waiting for a restart, in the order their settings changed: package -> label. */
    private static final Map<String, String> pending = new LinkedHashMap<String, String>();
    private static boolean wasShown;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final PathInterpolator EASE = new PathInterpolator(.2f, 0f, 0f, 1f);

    private final Activity activity;
    private final LinearLayout pill;
    private final TextView label;
    private final LauncherGlyph glyph;
    private final ImageView icon;
    private final int accent, onAccent;
    private boolean busy;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * Which app must restart for a setting to take full effect, or null when it applies live (or the app is already
     * restarted by the save itself, like Clear all and the screenshot editor).
     */
    static String scopeOf(String key) {
        if (key == null) return null;
        if (key.equals(LauncherFeatures.GRID) || key.equals(LauncherFeatures.GRID_SIZE) || key.equals(LauncherFeatures.DRAWER)
                || key.equals(LauncherFeatures.DRAWER_GRID_SIZE) || key.equals(LauncherFeatures.ICONS)
                || key.equals(LauncherFeatures.HIDE_SHORTCUTS) || key.equals(LauncherFeatures.LABEL_SIZE)
                || key.equals(LauncherFeatures.LABEL_LEVEL) || key.equals(LauncherFeatures.RENAME)
                || key.equals(SuperDark.KEY)) return LAUNCHER;   // Super Dark: folder colours and the drawer scrim are set when the launcher starts
        if (key.equals(Feature.FILES_SORT)) return FILES;
        return null;
    }

    /** A setting was saved: remember that its app wants a restart. */
    static void changed(String key) {
        String pkg = scopeOf(key); if (pkg == null) return;
        synchronized (pending) { pending.put(pkg, LAUNCHER.equals(pkg) ? "Pixel Launcher" : "Files"); }
    }

    RestartPill(Activity a, FrameLayout root, int accent, int onAccent) {
        activity = a; this.accent = accent; this.onAccent = onAccent;
        float d = a.getResources().getDisplayMetrics().density;
        pill = new LinearLayout(a); pill.setOrientation(LinearLayout.HORIZONTAL); pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(d, 14), 0, dp(d, 18), 0); pill.setMinimumHeight(dp(d, 48));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(accent); bg.setCornerRadius(dp(d, 24)); pill.setBackground(bg);
        pill.setElevation(dp(d, 6));
        icon = new ImageView(a); glyph = new LauncherGlyph(a, 4); glyph.setTintList(android.content.res.ColorStateList.valueOf(onAccent)); icon.setImageDrawable(glyph);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(d, 20), dp(d, 20)); ip.rightMargin = dp(d, 10);
        pill.addView(icon, ip);
        label = new TextView(a); label.setTextColor(onAccent); label.setTextSize(14); label.setSingleLine(true);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        pill.addView(label);
        pill.setClickable(true); pill.setFocusable(true);
        pill.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { restart(); } });
        // Long-press: not now (the setting still applies after the next restart of that app).
        pill.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { synchronized (pending) { pending.clear(); } hide(true); return true; } });
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        final int margin = dp(d, 16); lp.setMargins(margin, margin, margin, margin);
        root.addView(pill, lp);
        // Above the gesture bar, whatever the insets.
        pill.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                lp.bottomMargin = margin + insets.getSystemWindowInsetBottom(); lp.rightMargin = margin + insets.getSystemWindowInsetRight();
                v.setLayoutParams(lp); return insets;
            }
        });
        pill.setVisibility(View.GONE);
    }

    /** Shows or hides the pill for the current pending list (call from onResume and after each save). */
    void refresh() {
        if (busy) return;
        String text;
        synchronized (pending) {
            if (pending.isEmpty()) { hide(false); return; }
            text = pending.size() == 1 ? "Restart " + pending.values().iterator().next() : "Restart " + pending.size() + " apps";
        }
        label.setText(text);
        pill.setContentDescription(text + ". Applies your changes. Long-press to dismiss.");
        if (pill.getVisibility() == View.VISIBLE) return;
        pill.setVisibility(View.VISIBLE);
        if (wasShown) { pill.setAlpha(1f); pill.setTranslationY(0f); pill.setScaleX(1f); pill.setScaleY(1f); return; }   // already there on the previous page
        wasShown = true;
        pill.setAlpha(0f); pill.setTranslationY(dp(activity.getResources().getDisplayMetrics().density, 24)); pill.setScaleX(.9f); pill.setScaleY(.9f);
        pill.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(EASE).start();
    }

    private void hide(boolean animate) {
        wasShown = false;
        if (pill.getVisibility() != View.VISIBLE) return;
        if (!animate) { pill.setVisibility(View.GONE); return; }
        pill.animate().alpha(0f).translationY(dp(activity.getResources().getDisplayMetrics().density, 24)).scaleX(.9f).scaleY(.9f).setDuration(220).setInterpolator(EASE)
                .withEndAction(new Runnable() { public void run() { pill.setVisibility(View.GONE); } }).start();
    }

    private void restart() {
        if (busy) return;
        final String command; synchronized (pending) {
            if (pending.isEmpty()) return;
            StringBuilder b = new StringBuilder();
            for (String pkg : pending.keySet()) { if (b.length() > 0) b.append("; "); b.append("am force-stop ").append(pkg); }
            command = b.toString();
        }
        busy = true; label.setText("Restarting…");
        final android.animation.ObjectAnimator spin = android.animation.ObjectAnimator.ofFloat(icon, "rotation", 0f, 360f);
        spin.setDuration(700); spin.setRepeatCount(android.animation.ValueAnimator.INFINITE); spin.setInterpolator(new android.view.animation.LinearInterpolator()); spin.start();
        IO.execute(new Runnable() { public void run() {
            final boolean ok = Root.run(command) != null;
            main.post(new Runnable() { public void run() {
                spin.cancel(); icon.setRotation(0f); busy = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!ok) { refresh(); Toast.makeText(activity, "Couldn't restart: root access needed", Toast.LENGTH_SHORT).show(); return; }
                synchronized (pending) { pending.clear(); }
                label.setText("Done");
                main.postDelayed(new Runnable() { public void run() { hide(true); } }, 700);
            }});
        }});
    }

    private static int dp(float density, float v) { return Math.round(v * density); }
}
