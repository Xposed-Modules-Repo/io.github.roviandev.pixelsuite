package io.github.pixelsuite;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * 2.0.6-preview57 (owner, with a screenshot): System UI side of the app lock's fingerprint sheet. Only Pixel Suite's
 * own sheets are touched (Pixel Suite's name at the top and one of the app lock's descriptions):
 *  - "Pin" becomes a rounded pill right under "Touch the fingerprint sensor", centred, about the fingerprint
 *    circle's width, its text centred in it;
 *  - the top shows the locked app's own icon and name instead of Pixel Suite's.
 */
final class PromptStyle {
    private PromptStyle() {}
    static final String[] OURS = { "Please authenticate to proceed", "Confirm it's you to lock this app", "Confirm it's you to unlock this app", "Confirm it's you to lock this folder", "Confirm it's you to unlock this folder",
            "Confirm it's you to change privacy settings",
            "Confirm it's you to uninstall this app", "Confirm it's you to disable this app", "Confirm it's you to pause this app",
            "Confirm it's you to remove this app", "Confirm it's you to remove this folder" };   // preview64: Pixel Suite > Privacy & Security
    /** The sheet's own button texts that become the pill. */
    private static boolean pinText(CharSequence t) { return "Pin".contentEquals(t) || "Use PIN".contentEquals(t) || "PIN".contentEquals(t); }
    private static final Map<String, Object[]> apps = new HashMap<String, Object[]>();   // label -> {icon, label}

    static void install(ClassLoader cl) {
        try {
            Class<?> container = Xp.findClass("com.android.systemui.biometrics.AuthContainerView", cl);
            int n = Xp.hookAllMethods(container, "onAttachedToWindow", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        final View root = (View) p.thisObject;
                        if (Xp.getAdditionalInstanceField(root, "pixelsuite_prompt") != null) return;
                        ViewTreeObserver.OnGlobalLayoutListener l = new ViewTreeObserver.OnGlobalLayoutListener() {
                            public void onGlobalLayout() { try { style(root); } catch (Throwable ignored) { } }
                        };
                        root.getViewTreeObserver().addOnGlobalLayoutListener(l);
                        Xp.setAdditionalInstanceField(root, "pixelsuite_prompt", l);
                    } catch (Throwable error) { Module.log("App lock sheet style unavailable", error); }
                }
            }).size();
            Module.log("App lock sheet style: " + n + " hooks");
            warmLater(0);
        } catch (Throwable error) { Module.log("App lock sheet style unavailable", error); }
    }

    private static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id == 0 ? null : root.findViewById(id);
    }
    private static boolean ours(View root) {
        View logo = find(root, "logo_description"), desc = find(root, "description");
        boolean named = logo instanceof TextView && "Pixel Suite".contentEquals(((TextView) logo).getText());
        boolean marked = logo instanceof TextView && Boolean.TRUE.equals(Xp.getAdditionalInstanceField(logo, "pixelsuite_ours"));
        if (!named && !marked) return false;
        if (!(desc instanceof TextView)) return false;
        CharSequence d = ((TextView) desc).getText();
        for (String s : OURS) if (s.contentEquals(d)) return true;
        return false;
    }

    private static void style(View root) {
        if (!ours(root)) return;
        float dp = root.getResources().getDisplayMetrics().density;
        logo(root);
        View neg = find(root, "button_negative");
        if (!(neg instanceof TextView) || neg.getVisibility() != View.VISIBLE || !pinText(((TextView) neg).getText())) return;
        final TextView original = (TextView) neg;
        View under = find(root, "indicator");
        if (under == null || under.getVisibility() == View.GONE) under = find(root, "biometric_icon");
        if (under == null || !(under.getParent() instanceof ViewGroup)) return;
        ViewGroup panel = (ViewGroup) under.getParent();
        // preview61 (owner: tapping the pill did nothing): build 278 moved the sheet's own button out of its row, and a
        // tap outside a view's parent never reaches it. The pill is now its own view in the sheet's main area (where
        // taps land), and it presses the sheet's real "Pin" button; that one stays in place, invisible.
        TextView pin = (TextView) panel.findViewWithTag("pixelsuite_pin");
        if (pin == null) {
            boolean dark = (root.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            pin = new TextView(root.getContext());
            pin.setTag("pixelsuite_pin");
            pin.setText("Pin");   // the same pill on every sheet (also where the sheet says "Use PIN")
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(100 * dp);
            pill.setColor(system(root.getContext(), dark ? "system_accent2_700" : "system_accent2_100", dark ? 0xff4a4458 : 0xffe8def8));
            android.content.res.ColorStateList ripple = android.content.res.ColorStateList.valueOf(dark ? 0x33ffffff : 0x22000000);
            pin.setBackground(new android.graphics.drawable.RippleDrawable(ripple, pill, null));
            pin.setTextColor(system(root.getContext(), dark ? "system_accent2_50" : "system_accent2_900", dark ? 0xffe8def8 : 0xff1d192b));
            pin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            try { pin.setTypeface(android.graphics.Typeface.create("google-sans-text-medium", android.graphics.Typeface.NORMAL)); } catch (Throwable ignored) { }
            pin.setGravity(Gravity.CENTER);
            pin.setContentDescription("Use PIN");
            pin.setClickable(true); pin.setFocusable(true);
            pin.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { original.performClick(); } });
            panel.addView(pin, new ViewGroup.MarginLayoutParams(Math.round(150 * dp), Math.round(44 * dp)));
            panel.setClipChildren(false);
        }
        if (original.getAlpha() != 0f) original.setAlpha(0f);
        if (original.isClickable()) original.setClickable(false);
        // Right under "Touch the fingerprint sensor", centred on the sheet (positions inside the same area).
        float tx = (panel.getWidth() - pin.getWidth()) / 2f - pin.getLeft();
        float ty = under.getBottom() + 16 * dp - pin.getTop();
        if (Math.abs(pin.getTranslationX() - tx) > 0.5f) pin.setTranslationX(tx);
        if (Math.abs(pin.getTranslationY() - ty) > 0.5f) pin.setTranslationY(ty);
        pin.setVisibility(original.getVisibility());
    }

    /** Sheets about an app (not a folder, not Pixel Suite's own settings): the top shows that app. */
    private static boolean aboutApp(View root) {
        View desc = find(root, "description");
        if (!(desc instanceof TextView)) return false;
        String d = String.valueOf(((TextView) desc).getText());
        return !d.contains("folder") && !d.contains("privacy settings");
    }

    /**
     * The locked app's own icon and name at the top (the sheet's title is the app's name).
     * preview67 (owner: right after a reboot the top showed Pixel Suite's icon for a moment): the locked apps' icons
     * and names are loaded as soon as System UI starts (and again when the list changes), and until the app's own
     * icon is in, the top stays hidden instead of showing Pixel Suite's.
     */
    private static void logo(final View root) {
        final View icon = find(root, "logo"), name = find(root, "logo_description"), title = find(root, "title");
        if (!(icon instanceof ImageView) || !(name instanceof TextView) || !(title instanceof TextView)) return;
        final String label = String.valueOf(((TextView) title).getText());
        if (label.isEmpty() || label.equals(Xp.getAdditionalInstanceField(name, "pixelsuite_app"))) return;
        if (!aboutApp(root)) return;
        Object[] known; synchronized (apps) { known = apps.get(label); }
        if (known != null) { apply((ImageView) icon, (TextView) name, known, label, false); return; }
        if (Xp.getAdditionalInstanceField(name, "pixelsuite_looking") != null) return;
        Xp.setAdditionalInstanceField(name, "pixelsuite_looking", Boolean.TRUE);
        icon.setAlpha(0f); name.setAlpha(0f);   // not Pixel Suite's icon first
        final Context c = root.getContext();
        new Thread(new Runnable() { public void run() {
            Object[] found = null;
            try {
                PackageManager pm = c.getPackageManager();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if (label.contentEquals(pm.getApplicationLabel(ai))) { found = new Object[] { pm.getApplicationIcon(ai), label }; break; }
                }
            } catch (Throwable ignored) { }
            if (found != null) synchronized (apps) { apps.put(label, found); }
            final Object[] f = found;
            root.post(new Runnable() { public void run() {
                Xp.setAdditionalInstanceField(name, "pixelsuite_looking", null);
                if (f != null) apply((ImageView) icon, (TextView) name, f, label, true);
                else { icon.animate().alpha(1f).setDuration(120).start(); name.animate().alpha(1f).setDuration(120).start(); }   // not found: Pixel Suite's
            } });
        } }, "PixelSuitePromptIcon").start();
    }
    private static void apply(ImageView icon, TextView name, Object[] app, String label, boolean fade) {
        if (!(app[0] instanceof Drawable)) return;
        icon.setImageDrawable((Drawable) app[0]);
        Xp.setAdditionalInstanceField(name, "pixelsuite_ours", Boolean.TRUE);   // still known as ours once renamed
        name.setText(String.valueOf(app[1]));
        Xp.setAdditionalInstanceField(name, "pixelsuite_app", label);
        if (fade) { icon.animate().alpha(1f).setDuration(120).start(); name.animate().alpha(1f).setDuration(120).start(); }
        else { icon.setAlpha(1f); name.setAlpha(1f); }
    }

    // ------------------------------------------------------------------------------------------ loaded ahead

    private static boolean warmed;
    /** Once System UI is up: the locked apps' icons and names, ready before any sheet opens. */
    private static void warmLater(final int tries) {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() { public void run() {
            Context c = null;
            try { c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null); } catch (Throwable ignored) { }
            if (c == null) { if (tries < 20) warmLater(tries + 1); return; }
            if (warmed) return;
            warmed = true;
            final Context app = c;
            try {
                app.getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(PrivacyLock.KEY), false, new android.database.ContentObserver(h) {
                    @Override public void onChange(boolean self) { warm(app); }
                });
            } catch (Throwable ignored) { }
            warm(app);
        } }, tries == 0 ? 0 : 1500);
    }
    private static void warm(final Context c) {
        new Thread(new Runnable() { public void run() {
            try {
                PackageManager pm = c.getPackageManager();
                for (String pkg : PrivacyLock.parse(android.provider.Settings.Secure.getString(c.getContentResolver(), PrivacyLock.KEY))) {
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        String label = String.valueOf(pm.getApplicationLabel(ai));
                        Object[] entry = new Object[] { pm.getApplicationIcon(ai), label };
                        synchronized (apps) { apps.put(label, entry); }
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable error) { Module.log("App lock sheet: icons not loaded ahead", error); }
        } }, "PixelSuitePromptWarm").start();
    }

    private static int system(Context c, String name, int fallback) {
        try { int id = Resources.getSystem().getIdentifier(name, "color", "android"); return id == 0 ? fallback : c.getResources().getColor(id, c.getTheme()); }
        catch (Throwable t) { return fallback; }
    }
}
