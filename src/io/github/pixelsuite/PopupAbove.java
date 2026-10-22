package io.github.pixelsuite;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 2.0.6-preview76 (owner's video: holding Keep, Chrome or WhatsApp, the menu came up over the app and the app then
 * showed on top of it): the launcher places a long-press menu while its rows are still the stock size, and a menu too
 * tall for the space above the app is centred on the screen, over the app. With Pixel Suite's rows final (their own
 * size, plus Change icon / Lock app / Rename), the launcher's own placement is run again, so the menu sits above the
 * app (or below it) whenever it fits. If it fits neither way, it is kept clear of the app: right above it, as high as
 * the screen allows, never covering it from below.
 */
final class PopupAbove {
    private PopupAbove() {}

    /** Tightens the menu's rows so it fits in the room above the held app; true when it changed them. */
    private static boolean tighten(ViewGroup popup) throws Exception {
        View layer = (View) popup.getParent(); if (layer == null) return false;
        Rect target = new Rect();
        Xp.callMethod(popup, "getTargetObjectLocation", target);
        int top = 0; try { Rect insets = (Rect) Xp.callMethod(layer, "getInsets"); top = insets.top; } catch (Throwable ignored) { }
        popup.measure(0, 0);
        int extra = 0;
        try { extra += ((Number) Xp.callMethod(popup, "getExtraVerticalOffset")).intValue(); } catch (Throwable ignored) { }
        try { extra += ((Number) Xp.getObjectField(popup, "mArrowHeight")).intValue() + ((Number) Xp.getObjectField(popup, "mArrowOffsetVertical")).intValue(); } catch (Throwable ignored) { }
        try {
            int visible = 0; for (int i = 0; i < popup.getChildCount(); i++) if (popup.getChildAt(i).getVisibility() == View.VISIBLE) visible++;
            extra += Math.max(0, visible - 1) * ((Number) Xp.getObjectField(popup, "mChildContainerMargin")).intValue();
        } catch (Throwable ignored) { }
        int needed = popup.getMeasuredHeight() + extra;
        int room = target.top - layer.getTop() - top - 2;
        if (needed <= room) return false;
        int rows = DrawerPopupLayout.countRows(popup);
        if (rows <= 0) return false;
        int current = rowHeight(popup);
        if (current <= 0) return false;
        float d = popup.getResources().getDisplayMetrics().density;
        int fit = Math.max(Math.round(38 * d), current - (int) Math.ceil((needed - room) / (double) rows));
        if (fit >= current) return false;
        DrawerPopupLayout.fitRows(popup, fit);
        popup.requestLayout();
        return true;
    }
    /** The tallest menu row now (stock and Pixel Suite rows are all DeepShortcutView). */
    private static int rowHeight(View v) {
        if (LauncherFeatures.type("shortcuts.DeepShortcutView").isInstance(v)) return v.getVisibility() == View.GONE ? 0 : v.getMeasuredHeight();
        int best = 0;
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) best = Math.max(best, rowHeight(g.getChildAt(i))); }
        return best;
    }

    static void place(ViewGroup popup) throws Exception {
        if (!LauncherFeatures.type("popup.PopupContainer").isInstance(popup)) return;
        Xp.callMethod(popup, "orientAboutObject");   // the launcher's own placement, with the final size
        int gravity = ((Number) Xp.getObjectField(popup, "mGravity")).intValue();
        if ((gravity & 0x70) != 16) return;   // above or below the app: done
        // preview77 (owner: Keep and WhatsApp still a little too tall): the rows are tightened (not below 38 dp) until
        // the whole menu fits above the app, then the launcher places it again.
        if (tighten(popup)) {
            Xp.callMethod(popup, "orientAboutObject");
            gravity = ((Number) Xp.getObjectField(popup, "mGravity")).intValue();
            if ((gravity & 0x70) != 16) return;
        }
        // Still too tall either way: as high as it can go, its bottom kept above the app where possible.
        Rect target = new Rect();
        Xp.callMethod(popup, "getTargetObjectLocation", target);
        View layer = (View) popup.getParent(); if (layer == null) return;
        int top = 0; try { Rect insets = (Rect) Xp.callMethod(layer, "getInsets"); top = insets.top; } catch (Throwable ignored) { }
        int h = popup.getMeasuredHeight();
        int gap = Math.round(8 * popup.getResources().getDisplayMetrics().density);
        int y = Math.max(top, target.top - gap - h);
        Xp.setObjectField(popup, "mGravity", 0);   // the launcher's onLayout no longer centres it
        ViewGroup.LayoutParams lp = popup.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) { ((FrameLayout.LayoutParams) lp).gravity = 48; ((FrameLayout.LayoutParams) lp).topMargin = y; ((FrameLayout.LayoutParams) lp).bottomMargin = 0; popup.setLayoutParams(lp); }
        popup.setY(y);
        Xp.setObjectField(popup, "mIsAboveIcon", Boolean.TRUE);
        // its little arrow would point at the wrong place: hidden, as the launcher does for a centred menu
        try { final View arrow = (View) Xp.getObjectField(popup, "mArrow"); if (arrow != null) popup.post(new Runnable() { public void run() { arrow.setVisibility(View.INVISIBLE); } }); } catch (Throwable ignored) { }
    }
}
