package io.github.pixelsuite;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 2.0.8-preview16 (owner): the Pixel Studio crop box.
 *  - The part of the picture outside the crop box is blurred, live while a corner is dragged (instead of zooming in).
 *    Pixel Studio draws a 30% black veil there, in its own layer, then cuts the box out of it. The blurred picture is
 *    drawn into that same layer first, so the same cut takes it out of the box too: only the left-out part is blurred.
 *  - The four corner marks get an outline (light around a dark mark, dark around a light one), so they are easy to
 *    see on a dark or a light picture. The marks keep the editor's own colour, so they follow the theme.
 * Both are the editor's own draw lambdas of the crop box (qw cases 6 and 7 in 192717), recognised by holding the
 * crop box's state. Nothing else in the editor changes.
 */
final class CropLook {
    private CropLook() {}

    private static Field fLong, fObj, fCase;
    private static int cutCase = -1, arcsCase = -1;
    private static Object unit;

    static void install(ClassLoader cl) {
        try {
            String name = StudioNames.get("cropDraw"), cut = StudioNames.get("cropDraw.cut"), arcs = StudioNames.get("cropDraw.arcs");
            if (name == null || cut == null || arcs == null) { ScreenshotEditor.logOnce("crop look: not known for this Pixel Studio version, stock look kept", null); return; }
            cutCase = Integer.parseInt(cut); arcsCase = Integer.parseInt(arcs);
            Class<?> k = Class.forName(name, false, cl);
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == long.class) fLong = f; else if (f.getType() == int.class) fCase = f; else if (f.getType() == Object.class) fObj = f;
            }
            if (fLong == null || fCase == null || fObj == null) throw new NoSuchFieldException("crop draw fields in " + name);
            unit = ScreenshotEditor.unit(cl);
            Method sam = ScreenshotEditor.sam(StudioNames.cls("fn1", cl));
            Method invoke = k.getDeclaredMethod(sam.getName(), Object.class);
            invoke.setAccessible(true);
            Xp.hookMethod(invoke, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        int c = fCase.getInt(p.thisObject);
                        if (c != cutCase && c != arcsCase) return;
                        Object state = fObj.get(p.thisObject);
                        if (!ScreenshotEditor.isCropState(state)) return;
                        Canvas canvas = nativeCanvas(p.args[0]);
                        if (canvas == null) return;
                        if (c == cutCase) blur(canvas, fLong.getLong(p.thisObject));
                        else if (arcs(canvas, state, fLong.getLong(p.thisObject))) p.setResult(unit);
                    } catch (Throwable error) { ScreenshotEditor.logOnce("crop look failed, stock look kept", error); }
                }
            });
            try {
                Xp.hookMethod(StudioNames.cls("view", cl).getDeclaredMethod("onDraw", Canvas.class), new Xp.Callback() {
                    @Override protected void afterHookedMethod(Xp.Param p) {
                        canvasDrew();
                    }
                });
            } catch (Throwable error) { ScreenshotEditor.logOnce("crop blur: canvas draw not found, the blur follows only when the box draws", error); }
            Module.log("screenshot-editor: crop look hooks installed");
        } catch (Throwable error) { ScreenshotEditor.logOnce("crop look unavailable in this Pixel Studio version", error); }
    }

    // ------------------------------------------------------------------ the Android canvas behind a Compose DrawScope

    /*
     * preview18 (owner: after a dark-theme screenshot, the next one in light theme opened all blurred until a corner was
     * dragged): the canvas was found by walking fields, which could land on the window's own canvas (drawn while the
     * whole window draws) instead of the crop layer's. It is now read the way Compose itself does:
     * drawScope.drawContext.canvas, then that canvas's Android canvas.
     */
    private static final java.util.Map<Class<?>, Method[]> chains = new java.util.HashMap<Class<?>, Method[]>();
    private static final java.util.Map<Class<?>, Field> innerCanvas = new java.util.HashMap<Class<?>, Field>();

    static Canvas nativeCanvas(Object scope) {
        if (scope == null) return null;
        Method[] chain;
        synchronized (chains) {
            chain = chains.get(scope.getClass());
            if (chain == null && !chains.containsKey(scope.getClass())) { chain = findChain(scope); chains.put(scope.getClass(), chain); }
        }
        if (chain == null) { ScreenshotEditor.logOnce("crop look: drawing canvas not found", null); return null; }
        try {
            Object c = chain[1].invoke(chain[0].invoke(scope));
            Canvas n = android(c);
            return n != null && n.isHardwareAccelerated() ? n : null;
        } catch (Throwable t) { return null; }
    }

    /** drawContext (a getter of the scope) then canvas (a getter of the context) whose object holds an Android canvas. */
    private static Method[] findChain(Object scope) {
        for (Method m1 : getters(scope.getClass())) {
            Object context;
            try { context = m1.invoke(scope); } catch (Throwable t) { continue; }
            if (context == null) continue;
            for (Method m2 : getters(context.getClass())) {
                try {
                    Object c = m2.invoke(context);
                    if (c != null && android(c) != null) return new Method[]{m1, m2};
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    /** Public no-argument methods that return an app object (not a number, text, enum or framework type). */
    private static List<Method> getters(Class<?> k) {
        List<Method> out = new ArrayList<Method>();
        for (Method m : k.getMethods()) {
            Class<?> r = m.getReturnType();
            if (m.getParameterTypes().length != 0 || Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() == Object.class) continue;
            if (r.isPrimitive() || r.isEnum() || r.isArray() || r == Object.class) continue;
            String n = r.getName();
            if (n.startsWith("java.") || n.startsWith("kotlin.") || n.startsWith("android.")) continue;
            m.setAccessible(true);
            out.add(m);
        }
        return out;
    }

    /** The Android canvas a Compose canvas wraps (its only field of that type), or null. */
    private static Canvas android(Object composeCanvas) {
        Class<?> k = composeCanvas.getClass();
        Field f;
        synchronized (innerCanvas) {
            if (innerCanvas.containsKey(k)) f = innerCanvas.get(k);
            else {
                f = null;
                for (Field x : k.getDeclaredFields())
                    if (!Modifier.isStatic(x.getModifiers()) && Canvas.class.isAssignableFrom(x.getType())) { x.setAccessible(true); f = x; break; }
                innerCanvas.put(k, f);
            }
        }
        if (f == null) return null;
        try { Object v = f.get(composeCanvas); return v instanceof Canvas ? (Canvas) v : null; } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------ the blurred picture outside the box

    private static Method BEGIN, END, POSITION, SET_EFFECT, CREATE_BLUR, DRAW_NODE;
    private static Class<?> NODE;
    private static Object node;
    private static Picture nodePicture;
    private static volatile java.lang.ref.WeakReference<Picture> lastPicture = new java.lang.ref.WeakReference<Picture>(null);

    /** Pixel Studio made the picture it shows (with the doodles). */
    static void picture(Picture p) { if (p != null) lastPicture = new java.lang.ref.WeakReference<Picture>(p); }

    private static void init() throws Exception {
        if (NODE != null) return;
        Class<?> nt = Class.forName("android.graphics.RenderNode"), et = Class.forName("android.graphics.RenderEffect");
        BEGIN = nt.getMethod("beginRecording", int.class, int.class); END = nt.getMethod("endRecording");
        POSITION = nt.getMethod("setPosition", int.class, int.class, int.class, int.class);
        SET_EFFECT = nt.getMethod("setRenderEffect", et);
        CREATE_BLUR = et.getMethod("createBlurEffect", float.class, float.class, Shader.TileMode.class);
        DRAW_NODE = Canvas.class.getMethod("drawRenderNode", nt);
        NODE = nt;
    }

    /*
     * preview20 (owner's video: after the pen tool and back to crop, the blur covered only part of the picture): the
     * blur was laid out once, when the crop box drew, from where the picture was at that moment; coming back from a
     * tool the picture is still zooming out then, and the crop box doesn't draw again until it changes. The blur layer
     * is now always the crop box's size, and its content is redone each time the editor's canvas draws (the zoom's
     * every frame), so it follows the picture until it settles. Nothing in the editor redraws because of it.
     */
    private static RectF nodeBounds;
    private static int nodeW, nodeH;

    /** Inside the veil's layer, before the veil: the blur layer, over the picture's place in the box. */
    private static void blur(Canvas canvas, long corner) throws Exception {
        RectF bounds = ScreenshotEditor.cropBounds();   // the picture in the box's units (px of the box)
        if (bounds == null || bounds.isEmpty()) return;
        init();
        nodeBounds = new RectF(bounds);
        if (!record()) return;
        float radius = Float.intBitsToFloat((int) (corner >> 32));
        int save = canvas.save();
        try {
            Path clip = new Path();
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.translate(bounds.left, bounds.top);
            DRAW_NODE.invoke(canvas, node);
        } finally { canvas.restoreToCount(save); }
    }

    /** The editor's canvas drew: the blur follows the picture (only once the crop box has drawn one). */
    static void canvasDrew() {
        if (node == null || nodeBounds == null) return;
        try { record(); } catch (Throwable error) { ScreenshotEditor.logOnce("crop blur could not follow the picture", error); }
    }

    /** The blur layer's content: the picture as it is on the canvas now, fitted to the box's picture area. */
    private static boolean record() throws Exception {
        RectF bounds = nodeBounds;
        View v = ScreenshotEditor.canvasView.get();
        Picture picture = lastPicture.get();
        if (bounds == null || v == null || !v.isAttachedToWindow() || picture == null) return node != null;
        DoodleOverlay.Bridge.Space s = DoodleOverlay.Bridge.space(v, v);   // the picture on the editor's canvas view
        if (s == null || s.image.isEmpty()) return node != null;
        int w = Math.max(1, Math.round(bounds.width())), h = Math.max(1, Math.round(bounds.height()));
        float[] m = new float[9];
        s.toScreen.getValues(m);
        float[] key = new float[13];
        System.arraycopy(m, 0, key, 0, 9);
        key[9] = s.image.left; key[10] = s.image.top; key[11] = s.image.width(); key[12] = s.image.height();
        if (node != null && nodePicture == picture && nodeW == w && nodeH == h && java.util.Arrays.equals(key, nodeKey)) return true;
        if (node == null) {
            node = NODE.getConstructor(String.class).newInstance("PixelSuiteCropBlur");
            float r = 14f * v.getResources().getDisplayMetrics().density;
            SET_EFFECT.invoke(node, CREATE_BLUR.invoke(null, r, r, Shader.TileMode.CLAMP));
        }
        POSITION.invoke(node, 0, 0, w, h);
        Canvas rc = (Canvas) BEGIN.invoke(node, w, h);
        try {
            rc.scale(w / s.image.width(), h / s.image.height());
            rc.translate(-s.image.left, -s.image.top);
            rc.concat(s.toScreen);
            rc.drawPicture(picture);
        } finally { END.invoke(node); }
        nodePicture = picture; nodeW = w; nodeH = h; nodeKey = key;
        return true;
    }
    private static float[] nodeKey = new float[0];

    // ------------------------------------------------------------------ the corner marks with an outline

    private static final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    static { for (Paint p : new Paint[]{mark, rim}) { p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); } }

    /** The editor's four arcs (same places and sizes), each over a thin outline in the opposite tone. */
    private static boolean arcs(Canvas canvas, Object state, long colour) {
        RectF box = ScreenshotEditor.cropRect(state);
        android.content.Context c = ScreenshotEditor.app();
        if (box == null || c == null) return false;
        float d = c.getResources().getDisplayMetrics().density;
        int main = composeColor(colour, 0xff444746);
        boolean darkMark = luminance(main) < 0.5f;
        mark.setColor(main);
        mark.setStrokeWidth(4f * d);
        rim.setColor(darkMark ? 0xf2ffffff : 0xcc000000);
        rim.setStrokeWidth(4f * d + 3f * d);
        float size = 32f * d, out = 5f * d;
        float l = box.left - out, t = box.top - out, r = box.right - size + out, b = box.bottom - size + out;
        RectF[] ovals = {new RectF(l, t, l + size, t + size), new RectF(r, t, r + size, t + size),
                new RectF(r, b, r + size, b + size), new RectF(l, b, l + size, b + size)};
        float[] start = {180f, 270f, 0f, 90f};
        for (int i = 0; i < 4; i++) canvas.drawArc(ovals[i], start[i], 90f, false, rim);
        for (int i = 0; i < 4; i++) canvas.drawArc(ovals[i], start[i], 90f, false, mark);
        return true;
    }

    /** A Compose colour (packed long) as an ARGB int; sRGB colours only, else the fallback. */
    static int composeColor(long v, int fallback) {
        if ((v & 0x3fL) != 0) return fallback;
        return (int) (v >>> 32);
    }

    static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }
}
