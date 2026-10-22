package io.github.pixelsuite;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

final class PopupAbove {
    private PopupAbove() {}

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
    private static int rowHeight(View v) {
        if (LauncherFeatures.type("shortcuts.DeepShortcutView").isInstance(v)) return v.getVisibility() == View.GONE ? 0 : v.getMeasuredHeight();
        int best = 0;
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) best = Math.max(best, rowHeight(g.getChildAt(i))); }
        return best;
    }

    static void place(ViewGroup popup) throws Exception {
        if (!LauncherFeatures.type("popup.PopupContainer").isInstance(popup)) return;
        Xp.callMethod(popup, "orientAboutObject");
        int gravity = ((Number) Xp.getObjectField(popup, "mGravity")).intValue();
        if ((gravity & 0x70) != 16) return;
        if (tighten(popup)) {
            Xp.callMethod(popup, "orientAboutObject");
            gravity = ((Number) Xp.getObjectField(popup, "mGravity")).intValue();
            if ((gravity & 0x70) != 16) return;
        }
        Rect target = new Rect();
        Xp.callMethod(popup, "getTargetObjectLocation", target);
        View layer = (View) popup.getParent(); if (layer == null) return;
        int top = 0; try { Rect insets = (Rect) Xp.callMethod(layer, "getInsets"); top = insets.top; } catch (Throwable ignored) { }
        int h = popup.getMeasuredHeight();
        int gap = Math.round(8 * popup.getResources().getDisplayMetrics().density);
        int y = Math.max(top, target.top - gap - h);
        Xp.setObjectField(popup, "mGravity", 0);
        ViewGroup.LayoutParams lp = popup.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) { ((FrameLayout.LayoutParams) lp).gravity = 48; ((FrameLayout.LayoutParams) lp).topMargin = y; ((FrameLayout.LayoutParams) lp).bottomMargin = 0; popup.setLayoutParams(lp); }
        popup.setY(y);
        Xp.setObjectField(popup, "mIsAboveIcon", Boolean.TRUE);
        try { final View arrow = (View) Xp.getObjectField(popup, "mArrow"); if (arrow != null) popup.post(new Runnable() { public void run() { arrow.setVisibility(View.INVISIBLE); } }); } catch (Throwable ignored) { }
    }
}
