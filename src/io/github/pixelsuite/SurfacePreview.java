package io.github.pixelsuite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

final class SurfacePreview extends View implements android.view.ViewTreeObserver.OnPreDrawListener {
    int style = SurfaceStyle.FROSTED;
    final boolean dark; final int frost, off, stock, text, accent;
    Bitmap wall, blurred;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), textPaint = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path clip = new Path();
    final RectF all = new RectF(), card = new RectF();
    private static final int[] DOTS_DARK = {0xffef9a9a, 0xff80cbc4, 0xffffcc80, 0xff9fa8da}, DOTS_LIGHT = {0xffe53935, 0xff00897b, 0xffff8f00, 0xff3949ab};

    SurfacePreview(Context c, boolean dark, int accent, int text) {
        super(c);
        this.dark = dark; this.accent = accent; this.text = text;
        frost = FrostedSurface.composite(FrostedSurface.resourceColor(c, "blur_shade_panel_fg", dark ? 0x522c303d : 0x66dedee8), FrostedSurface.resourceColor(c, "blur_shade_panel_bg", dark ? 0x520e0e0f : 0x66f9f9fe));
        off = dark ? FrostedSurface.offDark(c) : Color.WHITE;
        stock = LauncherFeatures.surface(c, dark ? "system_surface_container_high_dark" : "system_surface_container_high_light", dark ? 0xff2f3036 : 0xfff3edf7) | 0xff000000;
        textPaint.setColor(text); textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    }

    private android.animation.ValueAnimator animator;
    private final android.graphics.Rect visible = new android.graphics.Rect();
    private boolean focused, attached, waiting, interacting, motionAllowed;
    private int from = SurfaceStyle.FROSTED, to = SurfaceStyle.FROSTED;
    private float mix = 1;
    private float displayedPosition = SurfaceStyle.FROSTED;
    private long resumeAt;
    private final Runnable advance = new Runnable() { public void run() {
        waiting = false;
        if (!canRun()) return;
        from = style; to = (style + 1) % 3; mix = 0;
        animator = android.animation.ValueAnimator.ofFloat(0, 1);
        animator.setDuration(500);
        animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        animator.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) { mix = (Float) a.getAnimatedValue(); displayedPosition = from + (to - from) * mix; invalidate(); }
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            public void onAnimationEnd(android.animation.Animator a) {
                animator = null;
                if (!cancelled) { style = to; from = to; mix = 1; displayedPosition = style; resumeAt = android.os.SystemClock.uptimeMillis() + 950; invalidate(); check(); }
            }
        });
        animator.start();
    }};
    void setStyle(int s) {
        stop(); style = Math.max(0, Math.min(2, s)); from = to = style; mix = 1; displayedPosition = style;
        resumeAt = android.os.SystemClock.uptimeMillis() + 3500; invalidate(); check();
    }
    void setInteracting(boolean value) { interacting = value; if(value) stop(); else { resumeAt = android.os.SystemClock.uptimeMillis() + 3500; check(); } }
    private boolean canRun() {
        if (!motionAllowed || interacting || !attached || !focused || getWindowVisibility() != VISIBLE || !isShown() || getHeight() <= 0 || !getGlobalVisibleRect(visible) || visible.height() < getHeight()/2) return false;
        return true;
    }
    private boolean readMotionAllowed() {
        try {
            if (android.provider.Settings.Global.getFloat(getContext().getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0) return false;
            android.os.PowerManager power = (android.os.PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            if (power != null && power.isPowerSaveMode()) return false;
            android.view.accessibility.AccessibilityManager access = (android.view.accessibility.AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (access != null && access.isTouchExplorationEnabled()) return false;
        } catch (Throwable ignored) { }
        return true;
    }
    private void check() {
        if (!canRun()) { stop(); return; }
        if (animator != null || waiting) return;
        waiting = true; postDelayed(advance, Math.max(0, resumeAt - android.os.SystemClock.uptimeMillis()));
    }
    private void stop() {
        removeCallbacks(advance); waiting = false;
        android.animation.ValueAnimator running = animator; animator = null;
        if (running != null) { running.cancel(); from = to = style; mix = 1; displayedPosition = style; }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; motionAllowed = readMotionAllowed(); focused = hasWindowFocus(); resumeAt = android.os.SystemClock.uptimeMillis() + 950; getViewTreeObserver().addOnPreDrawListener(this); check(); }
    @Override protected void onDetachedFromWindow() { attached = false; stop(); if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(this); super.onDetachedFromWindow(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); focused = focus; if (focus) { motionAllowed = readMotionAllowed(); check(); } else stop(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); if (v == VISIBLE) check(); else stop(); }
    @Override public boolean onPreDraw() { check(); return true; }

    private void drawSurface(Canvas c, RectF all, RectF card, float r, int value, int alpha) {
        if (alpha <= 0) return;
        int save = c.saveLayerAlpha(card, alpha, Canvas.ALL_SAVE_FLAG);
        if (value == SurfaceStyle.FROSTED) {
            c.drawBitmap(blurred, null, all, paint);
            paint.setColor((frost & 0xffffff) | 0xcc000000);
        } else paint.setColor(value == SurfaceStyle.OFF ? off : stock);
        c.drawRoundRect(card, r, r, paint);
        c.restoreToCount(save);
        paint.setColor(Color.WHITE);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        int bw = Math.max(8, w / 4), bh = Math.max(8, h / 4);
        wall = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(wall);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, bw, bh, dark ? new int[]{0xff1b2a4a, 0xff4a1f5c, 0xff0e3d3a} : new int[]{0xff8fb8ff, 0xffffc27a, 0xff7fd3c1}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bw, bh, p);
        p.setShader(null);
        p.setColor(dark ? 0xffff8a65 : 0xffff5a3c); c.drawCircle(bw * 0.22f, bh * 0.3f, bh * 0.28f, p);
        p.setColor(dark ? 0xff64b5f6 : 0xff2a6df4); c.drawCircle(bw * 0.78f, bh * 0.68f, bh * 0.34f, p);
        p.setColor(dark ? 0xfffff176 : 0xffffd600); c.drawCircle(bw * 0.6f, bh * 0.22f, bh * 0.16f, p);
        p.setColor(dark ? 0xffce93d8 : 0xff7c4dff); c.drawRoundRect(new RectF(bw * 0.05f, bh * 0.7f, bw * 0.45f, bh * 0.95f), bh * 0.08f, bh * 0.08f, p);
        blurred = wall.copy(Bitmap.Config.ARGB_8888, true);
        PillFrost.boxBlur(blurred, Math.max(2, bw / 14));
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight(); if (wall == null || w <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        all.set(0, 0, w, h);
        int save = c.save();
        clip.reset(); clip.addRoundRect(all, 20 * d, 20 * d, Path.Direction.CW); c.clipPath(clip);
        c.drawBitmap(wall, null, all, paint);
        card.set(w * 0.14f, h * 0.17f, w * 0.86f, h * 0.71f);
        float r = 24 * d;
        clip.reset(); clip.addRoundRect(card, r, r, Path.Direction.CW);
        int inner = c.save(); c.clipPath(clip);
        int lower = Math.max(0, Math.min(2, (int)Math.floor(displayedPosition)));
        int upper = Math.min(2, lower + 1);
        float fraction = displayedPosition - lower;
        drawSurface(c, all, card, r, lower, 255);
        if (fraction > 0) drawSurface(c, all, card, r, upper, Math.round(fraction * 255));
        c.restoreToCount(inner);
        textPaint.setTextSize(13 * d);
        textPaint.setColor(style == SurfaceStyle.OFF && !dark ? 0xff1f1f1f : text);
        c.drawText("Folder", card.left + 18 * d, card.top + 26 * d, textPaint);
        int[] dots = dark ? DOTS_DARK : DOTS_LIGHT;
        float cy = card.centerY() + 8 * d, step = (card.width() - 36 * d) / 4f, radius = Math.min(14 * d, step * 0.32f);
        for (int i = 0; i < 4; i++) { fill.setColor(dots[i]); c.drawCircle(card.left + 18 * d + step * (i + 0.5f), cy, radius, fill); }
        float y = h * 0.84f, left = w * 0.24f, right = w * 0.76f;
        RectF meter = new RectF(w*.09f, h*.755f, w*.91f, h*.99f);
        int meterSave = c.save();
        clip.reset(); clip.addRoundRect(meter, 16*d, 16*d, Path.Direction.CW); c.clipPath(clip);
        drawSurface(c, all, meter, 16*d, lower, 255);
        if (fraction > 0) drawSurface(c, all, meter, 16*d, upper, Math.round(fraction * 255));
        c.restoreToCount(meterSave);
        fill.setColor(dark ? 0xff91939a : 0xff73757c); fill.setStrokeWidth(2*d);
        c.drawLine(left, y, right, y, fill);
        for (int i=0;i<3;i++) c.drawCircle(left + (right-left)*i/2f, y, 3*d, fill);
        float position = displayedPosition;
        fill.setColor(accent); c.drawCircle(left + (right-left)*position/2f, y, 7*d, fill);
        textPaint.setTextAlign(Paint.Align.CENTER); textPaint.setTextSize(11*d); textPaint.setColor(text);
        String label = SurfaceStyle.NAMES[Math.max(0, Math.min(2, Math.round(displayedPosition)))];
        c.drawText(label, w*.5f, h*.955f, textPaint); textPaint.setTextAlign(Paint.Align.LEFT);
        c.restoreToCount(save);
    }
}
