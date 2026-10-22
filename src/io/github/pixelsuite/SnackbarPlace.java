package io.github.pixelsuite;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 2.0.6-preview55 (owner, with a screenshot): on the home screen the launcher's message pill ("Item removed  Undo")
 * sits centred in the gap between the last row of apps and the dock, instead of over the search bar. Everywhere else
 * (the drawer, a folder open, no dock) it stays where the launcher puts it.
 * preview75: in Super Dark it is black with the thin rim (PillFrost), everywhere it shows.
 */
final class SnackbarPlace {
    private SnackbarPlace() {}

    static void install() {
        try {
            final Class<?> snackbar = LauncherFeatures.type("views.Snackbar");
            Xp.hookAllMethods(snackbar, "show", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length == 0 || !(p.args[0] instanceof Activity)) return;
                        Activity a = (Activity) p.args[0];
                        ViewGroup layer = (ViewGroup) LauncherFeatures.call(a, "getDragLayer");
                        View bar = null;
                        for (int i = layer.getChildCount() - 1; i >= 0; i--) if (snackbar.isInstance(layer.getChildAt(i))) { bar = layer.getChildAt(i); break; }
                        if (bar == null) return;
                        // preview75 (owner, with a screenshot): in Super Dark the pill is black with the thin rim, like
                        // Pixel Suite's other pills (it stayed the launcher's grey)
                        if (SuperDark.inEffect(a)) try { PillFrost.apply(bar, bar, true); } catch (Throwable error) { LauncherFeatures.logOnce("Message pill Super Dark unavailable", error); }
                        place(a, layer, bar);
                    } catch (Throwable error) { LauncherFeatures.logOnce("Message pill placement unavailable", error); }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Message pill placement hook unavailable", error); }
    }

    private static void place(Activity a, ViewGroup layer, View bar) throws Exception {
        View hotseat = (View) LauncherFeatures.get(a, "mHotseat");
        ViewGroup workspace = (ViewGroup) LauncherFeatures.get(a, "mWorkspace");
        if (hotseat == null || workspace == null || !hotseat.isShown() || hotseat.getAlpha() < 0.9f || !workspace.isShown() || workspace.getAlpha() < 0.9f) return;
        try { View apps = (View) LauncherFeatures.call(a, "getAppsView"); if (apps != null && apps.isShown() && apps.getAlpha() > 0.1f && apps.getTranslationY() < layer.getHeight() * 0.5f) return; } catch (Throwable ignored) { }
        try { if (Xp.callStaticMethod(LauncherFeatures.type("AbstractFloatingView"), "getOpenView", a, 1) != null) return; } catch (Throwable ignored) { }   // a folder is open
        int[] base = new int[2]; layer.getLocationOnScreen(base);
        // the lowest app name on the current page
        int page = ((Number) LauncherFeatures.call(workspace, "getCurrentPage")).intValue();
        View cells = workspace.getChildAt(page);
        float rowBottom = lowestLabel(cells, base);
        // the top of the dock's icons
        float dockTop = highestIcon(hotseat, base);
        if (Float.isNaN(rowBottom) || Float.isNaN(dockTop) || dockTop <= rowBottom) return;
        float target = (rowBottom + dockTop) / 2f;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
        float now = layer.getHeight() - lp.bottomMargin - lp.height / 2f;
        bar.setTranslationY(target - now);
    }

    private static float lowestLabel(View v, int[] base) {
        float best = Float.NaN;
        if (v instanceof TextView && LauncherFeatures.type("BubbleTextView").isInstance(v) && v.isShown()) {
            TextView t = (TextView) v; int[] at = new int[2]; t.getLocationOnScreen(at);
            float bottom = at[1] - base[1] + (t.getBaseline() + t.getPaint().getFontMetrics().descent) * t.getScaleY();
            return bottom;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { float b = lowestLabel(g.getChildAt(i), base); if (!Float.isNaN(b) && (Float.isNaN(best) || b > best)) best = b; }
        }
        return best;
    }

    private static float highestIcon(View v, int[] base) {
        float best = Float.NaN;
        if (v instanceof TextView && LauncherFeatures.type("BubbleTextView").isInstance(v) && v.isShown()) {
            int[] at = new int[2]; v.getLocationOnScreen(at);
            return at[1] - base[1] + v.getPaddingTop() * v.getScaleY();
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { float t = highestIcon(g.getChildAt(i), base); if (!Float.isNaN(t) && (Float.isNaN(best) || t < best)) best = t; }
        }
        return best;
    }
}
