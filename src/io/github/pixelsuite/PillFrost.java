package io.github.pixelsuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;

final class PillFrost extends Drawable {
    final View host; final boolean superDark, sameWindow, blur;
    final int tint;
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path clip = new Path();
    Drawable region;
    Object node, effect;
    int pad;
    boolean off;
    float corner = -1;
    float radius(Rect b) { return corner < 0 ? b.height() / 2f : corner; }
    ViewTreeObserver.OnPreDrawListener follow;

    PillFrost(View host, boolean sameWindow) {
        this.host = host; this.sameWindow = sameWindow;
        Context c = host.getContext();
        superDark = SuperDark.inEffect(c);
        blur = !superDark && FrostedSurface.blurEnabled(c);
        int tone = superDark ? Color.BLACK : FrostedSurface.tone(c);
        tint = blur ? (tone & 0xffffff) | 0xcc000000 : tone | 0xff000000;
        fill.setColor(tint);
        if (blur) {
            if (sameWindow) watch(); else windowBlur();
        }
    }

    static PillFrost apply(View pill, View textRoot, boolean sameWindow) {
        PillFrost d = frost(pill, sameWindow);
        d.text(textRoot);
        return d;
    }

    static PillFrost frost(View pill, boolean sameWindow) { return frost(pill, sameWindow, -1); }
    static PillFrost frost(View pill, boolean sameWindow, float cornerPx) {
        PillFrost d = new PillFrost(pill, sameWindow);
        d.corner = cornerPx;
        pill.setBackgroundTintList(null);
        pill.setBackground(d);
        pill.setForeground(d.superDark && cornerPx != 0 ? new DoodleOverlay.Rim(cornerPx < 0 ? FrostedSurface.dp(pill.getContext(), 100) : cornerPx, FrostedSurface.dp(pill.getContext(), 1.5f)) : null);
        pill.setClipToOutline(true);
        return d;
    }

    void text(View v) {
        boolean light = !superDark && !FrostedSurface.dark(host.getContext());
        if (v instanceof TextView) ((TextView) v).setTextColor(light ? 0xff1f1f1f : 0xffffffff);
        else if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) text(g.getChildAt(i)); }
    }

    void windowBlur() {
        if (!host.isAttachedToWindow()) {
            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewDetachedFromWindow(View v) { }
                public void onViewAttachedToWindow(View v) { v.removeOnAttachStateChangeListener(this); makeRegion(); invalidateSelf(); }
            });
        } else makeRegion();
    }
    void makeRegion() {
        try {
            Object root = Xp.callMethod(host, "getViewRootImpl");
            Drawable d = (Drawable) Xp.callMethod(root, "createBackgroundBlurDrawable");
            Xp.callMethod(d, "setBlurRadius", FrostedSurface.depthBlurRadius(host.getContext()));
            try { Xp.callMethod(d, "setColor", Color.TRANSPARENT); } catch (Throwable ignored) { }
            region = d;
            onBoundsChange(getBounds());
        } catch (Throwable unavailable) { solid("window blur", unavailable); }
    }

    static Field FLAGS;
    static final int DIRTY = 0x00200000, CACHE_VALID = 0x00008000, INVALIDATED = 0x80000000;

    void watch() {
        try {
            BackdropSurface.init();
            if (FLAGS == null) { Field f = View.class.getDeclaredField("mPrivateFlags"); f.setAccessible(true); FLAGS = f; }
        } catch (Throwable unavailable) { solid("render node access", unavailable); return; }
        follow = new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                if (!host.isAttachedToWindow()) { host.getViewTreeObserver().removeOnPreDrawListener(this); return true; }
                if (!off && host.getWidth() > 0 && host.isShown()) redraw();
                return true;
            }
        };
        host.getViewTreeObserver().addOnPreDrawListener(follow);
    }

    void redraw() {
        try {
            FLAGS.setInt(host, (FLAGS.getInt(host) | DIRTY | INVALIDATED) & ~CACHE_VALID);
            for (ViewParent p = host.getParent(); p instanceof View; p = p.getParent()) {
                View v = (View) p;
                FLAGS.setInt(v, (FLAGS.getInt(v) | DIRTY) & ~CACHE_VALID);
            }
        } catch (Throwable unavailable) { solid("redraw flags", unavailable); }
    }

    static final class Level { final ViewGroup group; final View branch; final float x, y; Level(ViewGroup g, View b, float x, float y) { group = g; branch = b; this.x = x; this.y = y; } }

    static boolean under(ViewGroup group, View child, int index, View branch) {
        float a = child.getZ(), b = branch.getZ();
        if (a != b) return a < b;
        return index < group.indexOfChild(branch);
    }

    ArrayList<Level> levels() {
        ArrayList<Level> levels = new ArrayList<Level>();
        float x = 0, y = 0; View v = host;
        for (ViewParent p = host.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            ViewGroup g = (ViewGroup) p;
            x += v.getLeft() + v.getTranslationX(); y += v.getTop() + v.getTranslationY();
            levels.add(new Level(g, v, x, y));
            x -= g.getScrollX(); y -= g.getScrollY();
            v = g;
        }
        return levels;
    }
    static boolean copied(ViewGroup group, View s, int index, View branch) {
        return s != branch && s.getVisibility() == View.VISIBLE && s.getAlpha() > 0f && under(group, s, index, branch);
    }

    static final int SOFT = 6;
    static final Paint SOFT_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    static boolean softLogged;
    boolean softwareFrost(Canvas c, Rect b) {
        if (!(host.getParent() instanceof ViewGroup)) return false;
        android.graphics.Bitmap bmp = null;
        try {
            if (pad == 0) pad = Math.round(FrostedSurface.depthBlurRadius(host.getContext()) * 2);
            ArrayList<Level> levels = levels();
            int W = b.width() + 2 * pad, H = b.height() + 2 * pad;
            int bw = Math.max(1, W / SOFT), bh = Math.max(1, H / SOFT);
            bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas bc = new Canvas(bmp);
            bc.scale(1f / SOFT, 1f / SOFT);
            for (int i = levels.size() - 1; i >= 0; i--) {
                Level l = levels.get(i);
                int save = bc.save();
                bc.translate(pad - l.x, pad - l.y);
                Drawable bg = l.group.getBackground();
                if (bg != null && !bg.getBounds().isEmpty()) { int s = bc.save(); bc.translate(l.group.getScrollX(), l.group.getScrollY()); bg.draw(bc); bc.restoreToCount(s); }
                for (int j = 0; j < l.group.getChildCount(); j++) {
                    View s = l.group.getChildAt(j);
                    if (!copied(l.group, s, j, l.branch)) continue;
                    int ss = bc.save();
                    bc.translate(s.getLeft(), s.getTop());
                    if (!s.getMatrix().isIdentity()) bc.concat(s.getMatrix());
                    if (!bc.quickReject(new RectF(0, 0, s.getWidth(), s.getHeight()), Canvas.EdgeType.BW)) s.draw(bc);
                    bc.restoreToCount(ss);
                }
                bc.restoreToCount(save);
            }
            boxBlur(bmp, Math.max(1, Math.round(FrostedSurface.depthBlurRadius(host.getContext()) / (float) SOFT)));
            int save = c.save();
            c.clipPath(clip);
            c.translate(b.left - pad, b.top - pad);
            c.scale(SOFT, SOFT);
            c.drawBitmap(bmp, 0, 0, SOFT_PAINT);
            c.restoreToCount(save);
            return true;
        } catch (Throwable error) {
            if (!softLogged) { softLogged = true; Module.log("frost pill: software canvas, blur failed (" + host.getClass().getSimpleName() + ")", error); }
            return false;
        } finally { if (bmp != null) bmp.recycle(); }
    }

    static void boxBlur(android.graphics.Bitmap bmp, int r) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] p = new int[w * h], q = new int[w * h];
        bmp.getPixels(p, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) { box(p, q, w, h, r, true); box(q, p, w, h, r, false); }
        bmp.setPixels(p, 0, w, 0, 0, w, h);
    }
    static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, step = horizontal ? 1 : w, n = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * w : line;
            int a = 0, rr = 0, g = 0, bl = 0;
            for (int k = -r; k <= r; k++) { int c = in[base + Math.max(0, Math.min(len - 1, k)) * step]; a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; bl += c & 255; }
            for (int k = 0; k < len; k++) {
                out[base + k * step] = (a / n) << 24 | (rr / n) << 16 | (g / n) << 8 | (bl / n);
                int add = in[base + Math.min(len - 1, k + r + 1) * step], sub = in[base + Math.max(0, k - r) * step];
                a += (add >>> 24) - (sub >>> 24); rr += ((add >> 16) & 255) - ((sub >> 16) & 255); g += ((add >> 8) & 255) - ((sub >> 8) & 255); bl += (add & 255) - (sub & 255);
            }
        }
    }

    boolean record(int w, int h) {
        if (!(host.getParent() instanceof ViewGroup)) return false;
        try {
            if (node == null) {
                node = BackdropSurface.NODE.getConstructor(String.class).newInstance("PixelSuitePill");
                float radius = FrostedSurface.depthBlurRadius(host.getContext());
                effect = BackdropSurface.CREATE_BLUR.invoke(null, radius, radius, Shader.TileMode.CLAMP);
                BackdropSurface.SET_EFFECT.invoke(node, effect);
                pad = Math.round(radius * 2);
            }
            ArrayList<Level> levels = levels();
            BackdropSurface.POSITION.invoke(node, -pad, -pad, w + pad, h + pad);
            Canvas c = (Canvas) BackdropSurface.BEGIN.invoke(node, w + 2 * pad, h + 2 * pad);
            try {
                for (int i = levels.size() - 1; i >= 0; i--) {
                    Level l = levels.get(i);
                    int save = c.save();
                    c.translate(pad - l.x, pad - l.y);
                    Drawable bg = l.group.getBackground();
                    if (bg != null && !bg.getBounds().isEmpty()) {
                        int s = c.save();
                        c.translate(l.group.getScrollX(), l.group.getScrollY());
                        bg.draw(c);
                        c.restoreToCount(s);
                    }
                    for (int j = 0; j < l.group.getChildCount(); j++) {
                        View s = l.group.getChildAt(j);
                        if (!copied(l.group, s, j, l.branch)) continue;
                        Object n = BackdropSurface.RENDER_NODE.get(s);
                        if (n != null) BackdropSurface.DRAW_NODE.invoke(c, n);
                    }
                    c.restoreToCount(save);
                }
            } finally { BackdropSurface.END.invoke(node); }
            return true;
        } catch (Throwable unavailable) { solid("recording the views under it", unavailable); return false; }
    }

    static boolean solidLogged;
    void solid(String what, Throwable why) {
        fill.setColor(tint | 0xff000000); region = null; node = null; off = true;
        if (!solidLogged) { solidLogged = true; Module.log("frost pill: " + what + " unavailable, solid tint instead", why); }
    }

    @Override protected void onBoundsChange(Rect b) {
        clip.reset(); float r = radius(b);
        clip.addRoundRect(new RectF(b), r, r, Path.Direction.CW);
        if (region != null) {
            region.setBounds(b);
            try { Xp.callMethod(region, "setCornerRadius", r); } catch (Throwable ignored) { }
        }
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds(); if (b.isEmpty()) return;
        float r = radius(b);
        if (region != null) region.draw(c);
        else if (blur && sameWindow && !off && !c.isHardwareAccelerated()) softwareFrost(c, b);
        else if (blur && sameWindow && !off && c.isHardwareAccelerated() && record(b.width(), b.height())) {
            int save = c.save(); c.clipPath(clip); c.translate(b.left, b.top);
            try { BackdropSurface.DRAW_NODE.invoke(c, node); } catch (Throwable error) { solid("drawing the blur", error); }
            c.restoreToCount(save);
        }
        box.set(b);
        DRAWING.set(Boolean.TRUE);
        try { c.drawRoundRect(box, r, r, fill); } finally { DRAWING.set(null); }
    }
    private final RectF box = new RectF();
    static final ThreadLocal<Boolean> DRAWING = new ThreadLocal<Boolean>();

    @Override public void getOutline(Outline o) { Rect b = getBounds(); o.setRoundRect(b, radius(b)); o.setAlpha(0f); }
    @Override public void setAlpha(int a) { fill.setAlpha(a == 255 ? Color.alpha(tint) : a * Color.alpha(tint) / 255); }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
