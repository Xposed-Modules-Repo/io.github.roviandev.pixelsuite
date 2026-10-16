package io.github.pixelsuite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import java.util.Random;

final class ScatterEffect {
    static final String KEY = "pixelsuite_folder_scatter";
    private static final PathInterpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final PathInterpolator EMPHASIZED_ACCELERATE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private ScatterEffect() {}

    static void play(final View view) {
        try {
            String skip = view == null ? "no folder view" : !view.isAttachedToWindow() ? "folder view not on screen" : view.getWidth() <= 0 || view.getHeight() <= 0 ? "folder view not laid out" : null;
            if (skip != null) { Module.log("Folder remove animation skipped: " + skip); return; }
            Activity a = LauncherFeatures.activity.get(); if (a == null) return;
            if (android.provider.Settings.Global.getFloat(a.getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return;
            final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            int[] at = new int[2], base = new int[2], inWindow = new int[2]; view.getLocationOnScreen(at); decor.getLocationOnScreen(base); view.getLocationInWindow(inWindow);
            final float left = at[0] - base[0], top = at[1] - base[1], density = view.getContext().getResources().getDisplayMetrics().density;
            Rect source = new Rect(inWindow[0], inWindow[1], inWindow[0] + view.getWidth(), inWindow[1] + view.getHeight());
            if (!source.intersect(0, 0, decor.getWidth(), decor.getHeight())) return;
            if (source.width() <= 0 || source.height() <= 0) { Module.log("Folder remove animation skipped: folder outside the window"); return; }
            final Bitmap picture = Bitmap.createBitmap(source.width(), source.height(), Bitmap.Config.ARGB_8888);
            final float sx = left + (source.left - inWindow[0]), sy = top + (source.top - inWindow[1]);
            Class<?> copy = Class.forName("android.view.PixelCopy"), listener = Class.forName("android.view.PixelCopy$OnPixelCopyFinishedListener");
            Object done = java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, new java.lang.reflect.InvocationHandler() {
                public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                    if (!"onPixelCopyFinished".equals(method.getName())) return null;
                    try {
                        if (args == null || ((Number) args[0]).intValue() != 0 || !decor.isAttachedToWindow()) { picture.recycle(); Module.log("Folder remove animation skipped: screen copy failed or window closed"); return null; }
                        start(decor, picture, sx, sy, density);
                    } catch (Throwable error) { LauncherFeatures.logOnce("Folder remove animation unavailable", error); }
                    return null;
                }
            });
            copy.getMethod("request", android.view.Window.class, Rect.class, Bitmap.class, listener, android.os.Handler.class)
                    .invoke(null, a.getWindow(), source, picture, done, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (Throwable error) { LauncherFeatures.logOnce("Folder remove animation unavailable", error); }
    }

    private static void start(final ViewGroup decor, Bitmap picture, float left, float top, float density) {
        final Piece effect = new Piece(picture, left, top, density);
        effect.setBounds(0, 0, decor.getWidth(), decor.getHeight());
        decor.getOverlay().add(effect);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(Piece.DURATION);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) { effect.progress = (Float) v.getAnimatedValue(); decor.invalidate(); }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator x) { decor.getOverlay().remove(effect); decor.invalidate(); effect.release(); }
        });
        animator.start();
        Module.log("Folder remove animation: " + effect.count + " pieces at " + Math.round(effect.left) + "," + Math.round(effect.top));
    }

    private static final class Piece extends Drawable {
        static final long DURATION = 720;
        final Bitmap picture; final float left, top, density; final int count;
        final float[] x, y, dx, dy, size, spin, delay; final int[] color;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); final RectF rect = new RectF();
        float progress;

        Piece(Bitmap picture, float left, float top, float density) {
            this.picture = picture; this.left = left; this.top = top; this.density = density;
            int w = picture.getWidth(), h = picture.getHeight(), step = Math.max(2, Math.round(5 * density));
            Random random = new Random(0x5CA77E2L ^ w * 31L + h);
            int cap = 110; float[] px = new float[cap], py = new float[cap]; int[] pc = new int[cap]; int n = 0;
            int total = ((w + step - 1) / step) * ((h + step - 1) / step); int[] order = new int[total];
            for (int i = 0; i < total; i++) order[i] = i;
            for (int i = total - 1; i > 0; i--) { int j = random.nextInt(i + 1); int t = order[i]; order[i] = order[j]; order[j] = t; }
            int columns = (w + step - 1) / step;
            for (int i = 0; i < total && n < cap; i++) {
                int cx = Math.min(w - 1, (order[i] % columns) * step + random.nextInt(step)), cy = Math.min(h - 1, (order[i] / columns) * step + random.nextInt(step));
                int c = picture.getPixel(cx, cy); if ((c >>> 24) < 90) continue;
                px[n] = cx; py[n] = cy; pc[n] = c | 0xff000000; n++;
            }
            count = n; x = new float[n]; y = new float[n]; dx = new float[n]; dy = new float[n]; size = new float[n]; spin = new float[n]; delay = new float[n]; color = new int[n];
            float mx = w / 2f, my = h * 0.42f;
            for (int i = 0; i < n; i++) {
                x[i] = px[i]; y[i] = py[i]; color[i] = pc[i];
                float ax = px[i] - mx, ay = py[i] - my, len = (float) Math.max(1, Math.hypot(ax, ay));
                float angle = (float) Math.atan2(ay, ax) + (random.nextFloat() - .5f) * 0.9f;
                float far = (46 + random.nextFloat() * 120) * density;
                dx[i] = (float) Math.cos(angle) * far; dy[i] = (float) Math.sin(angle) * far - 14 * density * random.nextFloat();
                size[i] = (2.2f + random.nextFloat() * 3.6f) * density; spin[i] = (random.nextFloat() - .5f) * 360f;
                delay[i] = random.nextFloat() * 0.16f * (len / Math.max(1, w / 2f));
            }
        }

        void release() { try { picture.recycle(); } catch (Throwable ignored) { } }

        public void draw(Canvas canvas) {
            float t = progress, w = picture.getWidth(), h = picture.getHeight();
            float swell = t < .12f ? t / .12f * .06f : .06f - EMPHASIZED_ACCELERATE.getInterpolation(Math.min(1f, (t - .12f) / .5f)) * 1.06f;
            float scale = 1f + swell, alpha = 1f - EMPHASIZED_ACCELERATE.getInterpolation(Math.min(1f, Math.max(0f, (t - .08f) / .42f)));
            if (alpha > 0.01f && scale > 0.02f) {
                canvas.save(); canvas.translate(left + w / 2f, top + h * .42f); canvas.scale(scale, scale); canvas.translate(-w / 2f, -h * .42f);
                paint.setAlpha(Math.round(255 * alpha)); canvas.drawBitmap(picture, 0, 0, paint); canvas.restore();
            }
            for (int i = 0; i < count; i++) {
                float local = Math.min(1f, Math.max(0f, (t - 0.06f - delay[i]) / (0.86f - delay[i])));
                if (local <= 0f) continue;
                float move = EMPHASIZED_DECELERATE.getInterpolation(local), fade = 1f - Math.max(0f, (local - .35f) / .65f);
                if (fade <= 0.01f) continue;
                float px = left + x[i] + dx[i] * move, py = top + y[i] + dy[i] * move + 10 * density * local * local, s = size[i] * (1f - .55f * local);
                paint.setColor(color[i]); paint.setAlpha(Math.round(255 * fade));
                canvas.save(); canvas.rotate(spin[i] * local, px, py);
                rect.set(px - s, py - s, px + s, py + s); canvas.drawRoundRect(rect, s * .45f, s * .45f, paint); canvas.restore();
            }
        }
        public void setAlpha(int alpha) { }
        public void setColorFilter(ColorFilter filter) { }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static volatile Object armedFolder; private static volatile long armedAt;

    static void arm(Object folderInfo) { armedFolder = folderInfo; armedAt = android.os.SystemClock.uptimeMillis(); }

    static void install() {
        try {
            java.util.Set<?> hooks = Xp.hookAllMethods(LauncherFeatures.type("Launcher"), "removeItem", new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length < 2 || !(p.args[0] instanceof View)) return;
                        Object info = p.args[1];
                        if (info == null || info != armedFolder || android.os.SystemClock.uptimeMillis() - armedAt > 30000) return;
                        armedFolder = null;
                        Activity a = LauncherFeatures.activity.get();
                        if (a != null && FolderDrag.dragging(a)) return;
                        play((View) p.args[0]);
                    } catch (Throwable error) { LauncherFeatures.logOnce("Home folder remove animation unavailable", error); }
                }
            });
            if (hooks.isEmpty()) Module.log("Launcher.removeItem not found: home folders remove without the animation");
        } catch (Throwable error) { LauncherFeatures.logOnce("Home folder remove animation unavailable", error); }
    }
}
