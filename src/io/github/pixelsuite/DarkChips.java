package io.github.pixelsuite;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 2.0.4-preview14 (owner, Super Dark-Mode): the editors' dark grey buttons and bars turn black with the same soft rim
 * as the Doodles menu: Pixel Studio's tool bar (crop, pens, Doodles, eraser, text) and Google Photos' editor buttons
 * (straighten, aspect ratio, flip, rotate, search). Both apps draw them #121212 with Jetpack Compose.
 *
 * Found where they are drawn, in Android's own canvas calls (drawRect / drawRoundRect of a hardware canvas), not in
 * the apps' renamed code, so an app update cannot break anything: if a grey changes in an update it is just left
 * alone. Only a solid fill of that one grey, the size of a button or bar (20 to 100dp tall), is changed: it is drawn
 * black, then a thin blurred white rim is drawn just inside its rounded edge (brighter along the top, fading down the
 * sides), clipped by the button's own rounded shape. Selected buttons (a lighter grey) keep their colour.
 *
 * Pixel Studio: always on while Super Dark is in effect. Google Photos: the hooks only exist while its editor is
 * open (PhotosEditor), so the rest of Photos draws with no extra work.
 *
 * 2.0.4 build 168 (owner: Pixel Studio's "Delete screenshot?" dialog, the same #121212, looked bright next to the
 * black panels): in Pixel Studio, panels up to 400dp tall get the same black and rim, as long as they are not the
 * full width of the screen (a dialog or card, never a screen background).
 */
final class DarkChips {
    private DarkChips() {}

    private static final List<Object> handles = new ArrayList<Object>();
    private static final ThreadLocal<Paint> rim = new ThreadLocal<Paint>();
    private static float density = 2.6f;
    private static volatile boolean panels, wide;   // wide: System UI's recording screen, its dialog is a darker grey   // Pixel Studio: dialogs and cards too, not only buttons and bars
    private static float screenW = 1080f;

    /** Installs the canvas hooks (once). */
    static synchronized void on(String where) {
        if (!handles.isEmpty()) return;
        density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        screenW = android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
        panels = "Pixel Studio".equals(where) || "screen recording".equals(where);
        wide = "screen recording".equals(where);
        int n = 0;
        for (String name : new String[]{"android.graphics.BaseRecordingCanvas", "android.graphics.Canvas"}) {
            try {
                Class<?> c = Class.forName(name);
                Method rect = c.getDeclaredMethod("drawRect", float.class, float.class, float.class, float.class, Paint.class);
                Method round = c.getDeclaredMethod("drawRoundRect", float.class, float.class, float.class, float.class, float.class, float.class, Paint.class);
                handles.add(Xp.hookMethod(rect, new Fill(false))); n++;
                handles.add(Xp.hookMethod(round, new Fill(true))); n++;
                if (wide) {   // build 178: View-based dialogs (System UI's) draw their GradientDrawable with these
                    handles.add(Xp.hookMethod(c.getDeclaredMethod("drawRoundRect", android.graphics.RectF.class, float.class, float.class, Paint.class), new Shape(0))); n++;
                    handles.add(Xp.hookMethod(c.getDeclaredMethod("drawPath", android.graphics.Path.class, Paint.class), new Shape(1))); n++;
                }
            } catch (Throwable missing) { Module.log("dark buttons: " + name + " unavailable (" + missing + ")"); }
        }
        Module.log("dark buttons: " + n + " hooks (" + where + ")");
    }

    /** Removes the hooks (Google Photos' editor closed). */
    static synchronized void off() {
        for (Object h : handles) try { if (h instanceof io.github.libxposed.api.XposedInterface.HookHandle) ((io.github.libxposed.api.XposedInterface.HookHandle) h).unhook(); } catch (Throwable ignored) { }
        handles.clear();
    }

    /** The editors' button grey: #121212, give or take a step (any opacity). */
    static boolean grey(int c) {
        if ((c >>> 24) == 0) return false;
        int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
        if (wide) return r >= 0x0a && r <= 0x18 && Math.abs(r - g) <= 1 && Math.abs(g - b) <= 1;
        return r >= 0x10 && r <= 0x14 && g >= 0x10 && g <= 0x14 && b >= 0x10 && b <= 0x14 && Math.abs(r - g) <= 1 && Math.abs(g - b) <= 1;
    }

    static final class Fill extends Xp.Callback {
        final boolean round;
        Fill(boolean round) { this.round = round; }

        @Override protected void beforeHookedMethod(Xp.Param p) {
            try {
                Paint paint = (Paint) p.args[p.args.length - 1];
                if (paint == null || paint == rim.get()) return;
                int c = paint.getColor();
                if (!grey(c) || paint.getStyle() != Paint.Style.FILL || paint.getShader() != null) return;
                float w = (Float) p.args[2] - (Float) p.args[0], h = (Float) p.args[3] - (Float) p.args[1];
                if (h < 20 * density || w < 20 * density) return;
                if (h > 100 * density && (!panels || h > 400 * density || w > screenW - 16 * density)) return;
                if (!SuperDark.inEffectNow()) return;
                paint.setColor(c & 0xff000000);
                Xp.setAdditionalInstanceField(p, "pixelsuite_chip", c);
            } catch (Throwable ignored) { }
        }

        @Override protected void afterHookedMethod(Xp.Param p) {
            Object was = Xp.getAdditionalInstanceField(p, "pixelsuite_chip");
            if (!(was instanceof Integer)) return;
            try {
                Paint paint = (Paint) p.args[p.args.length - 1];
                paint.setColor((Integer) was);
                float l = (Float) p.args[0], t = (Float) p.args[1], r = (Float) p.args[2], b = (Float) p.args[3];
                float radius = round ? Math.min((Float) p.args[4], (Float) p.args[5]) : Math.min(r - l, b - t) / 2f;
                drawRim((Canvas) p.thisObject, l, t, r, b, radius, ((Integer) was) >>> 24);
            } catch (Throwable ignored) { }
        }
    }

    /** drawRoundRect(RectF, rx, ry, Paint) (kind 0) and drawPath(Path, Paint) (kind 1): the same rule as Fill. */
    static final class Shape extends Xp.Callback {
        final int kind;
        Shape(int kind) { this.kind = kind; }
        android.graphics.RectF bounds(Xp.Param p) {
            if (kind == 0) return (android.graphics.RectF) p.args[0];
            android.graphics.RectF r = new android.graphics.RectF(); ((android.graphics.Path) p.args[0]).computeBounds(r, true); return r;
        }
        @Override protected void beforeHookedMethod(Xp.Param p) {
            try {
                Paint paint = (Paint) p.args[p.args.length - 1];
                if (paint == null || paint == rim.get() || p.args[0] == null) return;
                int c = paint.getColor();
                if (!grey(c) || paint.getStyle() != Paint.Style.FILL || paint.getShader() != null) return;
                android.graphics.RectF b = bounds(p);
                float w = b.width(), h = b.height();
                if (h < 20 * density || w < 20 * density) return;
                if (h > 100 * density && (!panels || h > 400 * density || w > screenW - 16 * density)) return;
                if (!SuperDark.inEffectNow()) return;
                paint.setColor(c & 0xff000000);
                Xp.setAdditionalInstanceField(p, "pixelsuite_chip", c);
                Xp.setAdditionalInstanceField(p, "pixelsuite_box", new android.graphics.RectF(b));
            } catch (Throwable ignored) { }
        }
        @Override protected void afterHookedMethod(Xp.Param p) {
            Object was = Xp.getAdditionalInstanceField(p, "pixelsuite_chip");
            if (!(was instanceof Integer)) return;
            try {
                Paint paint = (Paint) p.args[p.args.length - 1];
                paint.setColor((Integer) was);
                android.graphics.RectF b = (android.graphics.RectF) Xp.getAdditionalInstanceField(p, "pixelsuite_box");
                float radius = kind == 0 ? Math.min((Float) p.args[1], (Float) p.args[2]) : Math.min(28 * density, Math.min(b.width(), b.height()) / 2f);
                drawRim((Canvas) p.thisObject, b.left, b.top, b.right, b.bottom, radius, ((Integer) was) >>> 24);
            } catch (Throwable ignored) { }
        }
    }

    /** The Doodles menu's rim (DoodleOverlay.Rim), for a button. */
    static void drawRim(Canvas canvas, float l, float t, float r, float b, float radius, int alpha) {
        Paint p = rim.get();
        float width = 1.5f * density;
        if (p == null) {
            p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(width * 1.6f);
            p.setMaskFilter(new BlurMaskFilter(width * 1.4f, BlurMaskFilter.Blur.NORMAL));
            rim.set(p);
        }
        p.setShader(new LinearGradient(0, t, 0, b, new int[]{0x30ffffff, 0x12ffffff, 0x0cffffff, 0x16ffffff},
                new float[]{0f, 0.35f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        p.setAlpha(alpha);
        float in = width * 0.5f;
        canvas.drawRoundRect(l + in, t + in, r - in, b - in, Math.max(0, radius - in), Math.max(0, radius - in), p);
    }
}
