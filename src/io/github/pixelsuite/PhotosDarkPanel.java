package io.github.pixelsuite;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;

final class PhotosDarkPanel {
    private PhotosDarkPanel() {}

    static int phone, secondary, sheetId;
    static final java.util.Map<View, Boolean> panels = new java.util.WeakHashMap<View, Boolean>();

    static void install() {
        try {
            Xp.hookAllMethods(View.class, "setBackgroundDrawable", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    if (!panels.containsKey(p.thisObject)) return;
                    View v = (View) p.thisObject;
                    if (!(v.getBackground() instanceof Black)) darken(v);
                }
            });
            Xp.hookAllMethods(android.app.Dialog.class, "onStart", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        android.app.Dialog d = (android.app.Dialog) p.thisObject;
                        if (d.getWindow() == null) return;
                        View root = d.getWindow().getDecorView();
                        if (sheetId == 0) sheetId = root.getResources().getIdentifier("design_bottom_sheet", "id", root.getContext().getPackageName());
                        if (sheetId <= 0) { sheetId = -1; return; }
                        final View sheet = root.findViewById(sheetId);
                        if (sheet == null || !SuperDark.inEffect(sheet.getContext())) return;
                        darken(sheet);
                        sheet.post(new Runnable() { public void run() { darken(sheet); } });
                    } catch (Throwable ignored) { }
                }
            });
            int n = Xp.hookAllMethods(android.view.LayoutInflater.class, "inflate", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof Integer) || !(p.getResult() instanceof View)) return;
                    int id = (Integer) p.args[0];
                    if (phone == 0 && secondary == 0) resolve(((View) p.getResult()).getContext());
                    if (id == 0 || (id != phone && id != secondary)) return;
                    final View panel = (View) p.getResult();
                    if (!SuperDark.inEffect(panel.getContext())) return;
                    panel.post(new Runnable() { public void run() { darken(panel); } });
                }
            }).size();
            Module.log("SuperDark Photos: selection panel hooks " + n);
        } catch (Throwable error) { Module.log("SuperDark Photos: selection panel unavailable", error); }
    }

    static void resolve(Context c) {
        try {
            android.content.res.Resources r = c.getResources(); String pkg = c.getPackageName();
            phone = r.getIdentifier("photos_gridactionpanel_impl_fragment", "layout", pkg);
            secondary = r.getIdentifier("photos_gridactionpanel_impl_secondary_display_fragment", "layout", pkg);
            if (phone == 0 && secondary == 0) { phone = -1; Module.log("SuperDark Photos: selection panel layout not found"); }
        } catch (Throwable ignored) { phone = -1; }
    }

    static void darken(View panel) {
        panels.put(panel, Boolean.TRUE);
        Drawable bg = panel.getBackground();
        if (bg instanceof Black) return;
        if (bg == null) { panel.setBackgroundColor(Color.BLACK); return; }
        panel.setBackground(new Black(bg));
    }

    static final class Black extends Drawable implements Drawable.Callback {
        final Drawable in;
        final android.graphics.Paint fill = new android.graphics.Paint();
        Black(Drawable in) {
            this.in = in; in.setCallback(this);
            fill.setColor(Color.BLACK);
            fill.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            setBounds(in.getBounds());
        }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds(); if (b.isEmpty()) return;
            int save = c.saveLayer(b.left, b.top, b.right, b.bottom, null);
            in.draw(c);
            c.drawRect(b, fill);
            c.restoreToCount(save);
        }
        @Override protected void onBoundsChange(android.graphics.Rect b) { in.setBounds(b); }
        @Override public boolean isStateful() { return in.isStateful(); }
        @Override protected boolean onStateChange(int[] state) { return in.setState(state); }
        @Override public void getOutline(android.graphics.Outline o) { in.getOutline(o); }
        @Override public boolean getPadding(android.graphics.Rect r) { return in.getPadding(r); }
        @Override public void setAlpha(int a) { in.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return in.getIntrinsicWidth(); }
        @Override public int getIntrinsicHeight() { return in.getIntrinsicHeight(); }
        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    }
}
