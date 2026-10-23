package io.github.pixelsuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 2.0.8 (owner: every panel in Pixel Studio in the one frost, as the Doodles menu was): the frosted editor's panels
 * match and never change shade once shown.
 *
 * The editor's window is already the folders' frost (the home screen behind it blurred, under the folders' tint).
 * The Doodles menu (its own view) is a hole in the window's content down to that frost (DST_OUT), then the folders'
 * tint, then a light veil that lifts it a step above the background. The editor's own panels (tool bar, pen size bar,
 * font bar, trash button) are faded in by Pixel Studio from a recording, so they take {@link #solid}: the same look
 * worked out as one opaque colour, which is identical during and after the fade.
 */
final class HoleFrost extends Drawable {
    private HoleFrost(float radius, int tint) { this.radius = radius; this.tint = tint; }

    static final int VEIL = 0x40ffffff;
    static final int TINT_ALPHA = 0xcc;

    private static final ThreadLocal<Paint[]> PAINTS = new ThreadLocal<Paint[]>();
    private static Paint[] paints() {
        Paint[] p = PAINTS.get();
        if (p == null) {
            p = new Paint[]{new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG), new Paint(Paint.ANTI_ALIAS_FLAG)};
            p[0].setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            PAINTS.set(p);
        }
        return p;
    }

    /** The folders' tint (the launcher's own tone), as the Doodles menu and the frost pills use it. */
    static int tint(Context c) { return (FrostedSurface.tone(c) & 0xffffff) | (TINT_ALPHA << 24); }
    /**
     * preview40: the panel colour the frosted editor uses where a panel must not let anything through: the folders'
     * tint (80%) over the frost's own colour, then the light veil, as one opaque colour.
     */
    static int solid(Context c) {
        int base = FrostedSurface.tone(c) | 0xff000000;   // the frost under the tint is close to the tint itself
        return over(VEIL, over(tint(c), base));
    }
    static int over(int top, int under) {
        int a = top >>> 24;
        int r = (((top >> 16) & 0xff) * a + ((under >> 16) & 0xff) * (255 - a)) / 255;
        int g = (((top >> 8) & 0xff) * a + ((under >> 8) & 0xff) * (255 - a)) / 255;
        int b = ((top & 0xff) * a + (under & 0xff) * (255 - a)) / 255;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
    static int scaled(int colour, int alpha) { return (colour & 0xffffff) | (((colour >>> 24) * alpha / 255) << 24); }

    /** The panel's three draws for a shape (args as the canvas call got them); the last one is left to the caller. */
    static void punch(Canvas canvas, Object[] args, int alpha) { shape(canvas, args, paint(0, alpha << 24)); }
    static void tint(Canvas canvas, Object[] args, int alpha, int tint) { shape(canvas, args, paint(1, scaled(tint, alpha))); }
    static int veil(int alpha) { return scaled(VEIL, alpha); }
    private static Paint paint(int which, int colour) { Paint p = paints()[which]; p.setColor(colour); return p; }

    private static void shape(Canvas canvas, Object[] a, Paint p) {
        Object a0 = a[0];
        Boolean was = PillFrost.DRAWING.get();
        PillFrost.DRAWING.set(Boolean.TRUE);   // the frost's own draws: not panels again
        try {
            if (a.length == 7) canvas.drawRoundRect((Float) a[0], (Float) a[1], (Float) a[2], (Float) a[3], (Float) a[4], (Float) a[5], p);
            else if (a0 instanceof RectF && a.length == 4) canvas.drawRoundRect((RectF) a0, (Float) a[1], (Float) a[2], p);
            else if (a0 instanceof RectF) canvas.drawRect((RectF) a0, p);
            else if (a0 instanceof Rect) canvas.drawRect((Rect) a0, p);
            else if (a0 instanceof Float && a.length == 5) canvas.drawRect((Float) a[0], (Float) a[1], (Float) a[2], (Float) a[3], p);
            else if (a0 instanceof Path) canvas.drawPath((Path) a0, p);
        } finally { PillFrost.DRAWING.set(was); }
    }

    // ------------------------------------------------------------------ the Doodles menu's background

    /** The menu's frost: a rounded panel of the given corner radius (px). */
    static HoleFrost panel(Context c, float radius) { return new HoleFrost(radius, tint(c)); }

    final float radius; final int tint; int alpha = 255;
    private final RectF box = new RectF();

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds(); if (b.isEmpty()) return;
        box.set(b);
        Object[] args = {box, radius, radius, null};
        punch(canvas, args, alpha);
        tint(canvas, args, alpha, tint);
        shape(canvas, args, paint(2, veil(alpha)));
    }
    /** Its own shape, so the menu's rounded clip and its shadow follow it (the hole clears the shadow inside the menu). */
    @Override public void getOutline(Outline o) { Rect b = getBounds(); o.setRoundRect(b, radius); o.setAlpha(1f); }
    @Override public void setAlpha(int a) { if (alpha != a) { alpha = a; invalidateSelf(); } }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
