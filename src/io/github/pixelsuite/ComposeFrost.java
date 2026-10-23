package io.github.pixelsuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class ComposeFrost {
    private ComposeFrost() {}

    static final class Frame {
        Object root; View view; int editor; boolean dialog; int regions; int want = -1;
    }
    static final ThreadLocal<Frame> frame = new ThreadLocal<Frame>();

    static final Map<Object, List<Drawable>> regions = new WeakHashMap<Object, List<Drawable>>();
    static final Map<Object, Boolean> editorWindows = new WeakHashMap<Object, Boolean>();
    static final Map<Object, Boolean> stockDecors = new WeakHashMap<Object, Boolean>();
    static final Map<android.app.Activity, Boolean> editors = new WeakHashMap<android.app.Activity, Boolean>();
    static final Map<Object, Boolean> wantedCache = new WeakHashMap<Object, Boolean>();
    static final Map<Object, Boolean> inverted = new WeakHashMap<Object, Boolean>();
    static volatile android.app.Activity resumedEditor;
    static float density = 2.6f, screenW = 1080f, screenH = 2400f;
    static String titleFilter, activityFilter;
    static boolean installed, invertDark, editorMode;
    static int lightSurface;
    static volatile int toolbarColour;

    static final float K = -1.2f, O = 272f;

    static synchronized void install(String where, String windowTitle) { install(where, windowTitle, false, null); }
    static synchronized void install(String where, String windowTitle, boolean invertDarkCards) { install(where, windowTitle, invertDarkCards, null); }
    static synchronized void install(String where, String windowTitle, boolean invertDarkCards, String activityName) {
        if (installed) return;
        installed = true;
        titleFilter = windowTitle; invertDark = invertDarkCards; activityFilter = activityName;
        editorMode = "Pixel Studio".equals(where);
        android.util.DisplayMetrics m = android.content.res.Resources.getSystem().getDisplayMetrics();
        density = m.density; screenW = m.widthPixels; screenH = m.heightPixels;
        int n = 0;
        if (invertDarkCards) {
            try {
                n += Xp.hookAllMethods(android.app.Dialog.class, "onStart", new Xp.Callback() {
                    @Override protected void afterHookedMethod(Xp.Param p) { viewDialog((android.app.Dialog) p.thisObject); }
                }).size();
            } catch (Throwable missing) { Module.log("compose frost: Dialog.onStart unavailable", missing); }
        }
        if (editorMode) {
            n += editorHooks();
            n += enableDraw();
        } else if (titleFilter != null) {
            n += windowHooks();
        } else if (activityFilter != null) {
            n += activityHooks();
        } else {
            n += enableDraw();
        }
        Module.log("compose frost: " + n + " hooks (" + where + ")");
    }

    private static final List<Object> drawHandles = new ArrayList<Object>();
    private static int users;

    static synchronized int enableDraw() {
        if (users++ > 0) return 0;
        int before = drawHandles.size();
        try {
            Class<?> vri = Class.forName("android.view.ViewRootImpl");
            drawHandles.addAll(Xp.hookAllMethods(vri, "performDraw", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) { frame.set(newFrame(p.thisObject)); }
                @Override protected void afterHookedMethod(Xp.Param p) {
                    Frame f = frame.get(); frame.set(null);
                    if (f != null) fade(f);
                }
            }));
        } catch (Throwable error) { Module.log("compose frost: ViewRootImpl unavailable", error); return 0; }
        for (String name : new String[]{"android.graphics.BaseRecordingCanvas", "android.graphics.Canvas"}) {
            try {
                Class<?> c = Class.forName(name);
                add(c.getDeclaredMethod("drawRoundRect", float.class, float.class, float.class, float.class, float.class, float.class, Paint.class), new Card(false));
                add(c.getDeclaredMethod("drawRect", float.class, float.class, float.class, float.class, Paint.class), new Fill());
                try { add(c.getDeclaredMethod("drawRoundRect", RectF.class, float.class, float.class, Paint.class), new Card(true)); } catch (Throwable ignored) { }
                if (editorMode) {
                    for (Class<?>[] sig : new Class<?>[][]{{RectF.class, Paint.class}, {android.graphics.Rect.class, Paint.class}}) try { add(c.getDeclaredMethod("drawRect", sig), new Fill()); } catch (Throwable ignored) { }
                    try { add(c.getDeclaredMethod("drawPaint", Paint.class), new Fill()); } catch (Throwable ignored) { }
                    try { add(c.getDeclaredMethod("drawPath", android.graphics.Path.class, Paint.class), new Fill()); } catch (Throwable ignored) { }
                    try { add(c.getDeclaredMethod("drawColor", int.class), new Fill()); } catch (Throwable ignored) { }
                    try { add(c.getDeclaredMethod("drawColor", int.class, android.graphics.PorterDuff.Mode.class), new Fill()); } catch (Throwable ignored) { }
                    try { add(c.getDeclaredMethod("drawColor", long.class), new Fill()); } catch (Throwable ignored) { }
                    try { add(c.getDeclaredMethod("drawColor", int.class, Class.forName("android.graphics.BlendMode")), new Fill()); } catch (Throwable ignored) { }
                    try { add(c.getDeclaredMethod("drawColor", long.class, Class.forName("android.graphics.BlendMode")), new Fill()); } catch (Throwable ignored) { }
                    try { drawHandles.addAll(Xp.hookAllMethods(c, "drawBitmap", new Icon())); } catch (Throwable ignored) { }
                }
            } catch (Throwable missing) { Module.log("compose frost: " + name + " unavailable", missing); }
        }
        try {
            Class<?> rc = Class.forName("android.graphics.RecordingCanvas"), rn = Class.forName("android.graphics.RenderNode");
            sCanvasNode = rc.getDeclaredField("mNode"); sCanvasNode.setAccessible(true);
            sBlurNode = Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable").getDeclaredField("mRenderNode"); sBlurNode.setAccessible(true);
            sNodeAlpha = rn.getMethod("getAlpha");
            add(rc.getDeclaredMethod("drawRenderNode", rn), new Parent());
        } catch (Throwable error) {
            sNodeAlpha = null;
            if (!sFadeFailed) { sFadeFailed = true; Module.log("compose frost: the frost can't follow its card's fade", error); }
        }
        return drawHandles.size() - before;
    }
    private static void add(Method m, Xp.Callback cb) { Object h = Xp.hookMethod(m, cb); if (h != null) drawHandles.add(h); }

    static synchronized void disableDraw() {
        if (users == 0 || --users > 0) return;
        for (Object h : drawHandles) try { if (h instanceof io.github.libxposed.api.XposedInterface.HookHandle) ((io.github.libxposed.api.XposedInterface.HookHandle) h).unhook(); } catch (Throwable ignored) { }
        drawHandles.clear();
        frame.remove();
    }

    private static final Map<Object, Boolean> titled = new WeakHashMap<Object, Boolean>();
    private static int windowHooks() {
        int n = 0;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Xp.Callback seen = new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length < 2 || !(p.args[0] instanceof View) || !(p.args[1] instanceof android.view.WindowManager.LayoutParams)) return;
                        CharSequence title = ((android.view.WindowManager.LayoutParams) p.args[1]).getTitle();
                        if (title == null || !title.toString().contains(titleFilter)) return;
                        synchronized (titled) { if (titled.containsKey(p.args[0])) return; titled.put(p.args[0], Boolean.TRUE); }
                        enableDraw();
                    } catch (Throwable ignored) { }
                }
            };
            n += Xp.hookAllMethods(wmg, "addView", seen).size();
            n += Xp.hookAllMethods(wmg, "updateViewLayout", seen).size();
            n += Xp.hookAllMethods(wmg, "removeView", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        boolean was; synchronized (titled) { was = p.args.length > 0 && titled.remove(p.args[0]) != null; }
                        if (was) disableDraw();
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable error) { Module.log("compose frost: window hooks unavailable, always on", error); n += enableDraw(); }
        return n;
    }

    private static int activityHooks() {
        int n = 0;
        try {
            if (Xp.hookMethod(android.app.Activity.class.getDeclaredMethod("onCreate", android.os.Bundle.class), new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { if (named(p.thisObject.getClass().getName())) enableDraw(); }
            }) != null) n++;
            if (Xp.hookMethod(android.app.Activity.class.getDeclaredMethod("onDestroy"), new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { if (named(p.thisObject.getClass().getName())) disableDraw(); }
            }) != null) n++;
        } catch (Throwable error) { Module.log("compose frost: activity hooks unavailable, always on", error); n += enableDraw(); }
        return n;
    }

    static Frame newFrame(Object vri) {
        Frame f = new Frame();
        f.root = vri;
        f.view = view(vri);
        f.editor = editorState(f.view);
        if (f.editor > 0) f.dialog = dialogWindow(vri);
        return f;
    }

    private static int editorHooks() {
        int n = 0;
        try {
            n += Xp.hookAllMethods(Class.forName("com.android.internal.policy.PhoneWindow"), "setBackgroundDrawable", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 1 || p.args[0] instanceof PillFrost) return;
                        Object decor = Xp.callMethod(p.thisObject, "peekDecorView"); if (decor == null) return;
                        boolean editor; synchronized (editorWindows) { editor = editorWindows.containsKey(decor); }
                        if (editor && ((View) decor).getBackground() instanceof PillFrost) p.setResult(null);
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable missing) { Module.log("compose frost: PhoneWindow unavailable", missing); }
        try {
            n += Xp.hookAllMethods(View.class, "setBackgroundDrawable", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 1 || p.args[0] instanceof PillFrost) return;
                        boolean editor; synchronized (editorWindows) { editor = editorWindows.containsKey(p.thisObject); }
                        if (editor && ((View) p.thisObject).getBackground() instanceof PillFrost) p.setResult(null);
                    } catch (Throwable ignored) { }
                }
            }).size();
            n += Xp.hookAllMethods(android.app.Activity.class, "onResume", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { synchronized (editors) { if (editors.containsKey(p.thisObject)) resumedEditor = (android.app.Activity) p.thisObject; } }
            }).size();
            n += Xp.hookAllMethods(android.app.Activity.class, "onPause", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) { if (resumedEditor == p.thisObject) resumedEditor = null; }
            }).size();
        } catch (Throwable missing) { Module.log("compose frost: editor lifecycle hooks unavailable", missing); }
        Xp.Callback keep = new Xp.Callback() {
            @Override protected void beforeHookedMethod(Xp.Param p) {
                try {
                    boolean translucent = p.method.getName().equals("setTranslucent");
                    if (translucent && Boolean.TRUE.equals(p.args[0])) return;
                    Boolean frosted; synchronized (editors) { frosted = editors.get(p.thisObject); }
                    if (Boolean.TRUE.equals(frosted)) p.setResult(translucent ? (Object) Boolean.TRUE : null);
                } catch (Throwable ignored) { }
            }
        };
        try { n += Xp.hookAllMethods(android.app.Activity.class, "convertFromTranslucent", keep).size(); } catch (Throwable missing) { Module.log("compose frost: convertFromTranslucent unavailable", missing); }
        try { n += Xp.hookAllMethods(android.app.Activity.class, "setTranslucent", keep).size(); } catch (Throwable ignored) { }
        return n;
    }

    static void editor(android.app.Activity a, boolean frosted) {
        synchronized (editors) { editors.put(a, frosted); }

        View decor = a.getWindow().getDecorView();
        if (frosted) { synchronized (editorWindows) { editorWindows.put(decor, Boolean.TRUE); } }
        else { synchronized (stockDecors) { stockDecors.put(decor, Boolean.TRUE); } }
    }
    static boolean noEditors() { synchronized (editors) { return editors.isEmpty(); } }
    static int editorState(View v) {
        if (v == null || noEditors()) return 0;
        synchronized (editorWindows) { if (editorWindows.containsKey(v)) return 2; }
        synchronized (stockDecors) { if (stockDecors.containsKey(v)) return 1; }
        android.app.Activity a = activityOf(v.getContext());
        Boolean f; synchronized (editors) { f = a == null ? null : editors.get(a); }
        return f == null ? 0 : f ? 2 : 1;
    }

    static boolean named(String className) {
        if (activityFilter == null) return true;
        for (String n : activityFilter.split("\\|")) if (className.endsWith(n)) return true;
        return false;
    }
    static android.app.Activity activityOf(Context c) {
        while (c instanceof android.content.ContextWrapper && !(c instanceof android.app.Activity)) c = ((android.content.ContextWrapper) c).getBaseContext();
        return c instanceof android.app.Activity ? (android.app.Activity) c : null;
    }
    static boolean activityWanted(Context c) {
        if (activityFilter == null) return true;
        android.app.Activity a = activityOf(c);
        return a != null && named(a.getClass().getName());
    }
    static boolean phoneDark() {
        return (android.content.res.Resources.getSystem().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
    static boolean dialogWindow(Object vri) {
        try { return ((android.view.WindowManager.LayoutParams) Xp.getObjectField(vri, "mWindowAttributes")).type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION; } catch (Throwable t) { return false; }
    }
    static View view(Object vri) {
        try { Object v = Xp.callMethod(vri, "getView"); return v instanceof View ? (View) v : null; } catch (Throwable t) { return null; }
    }
    static Context context(Frame f) { return f.view == null ? null : f.view.getContext(); }

    static boolean wanted(Frame f) {
        if (f.want >= 0) return f.want == 1;
        boolean ok = wantedWindow(f);
        f.want = ok ? 1 : 0;
        return ok;
    }
    private static boolean wantedWindow(Frame f) {
        Boolean w;
        synchronized (wantedCache) { w = wantedCache.get(f.root); }
        if (w != null) return w;
        boolean ok = false;
        try {
            if (titleFilter != null) {
                Object attrs = Xp.getObjectField(f.root, "mWindowAttributes");
                CharSequence title = attrs == null ? null : ((android.view.WindowManager.LayoutParams) attrs).getTitle();
                ok = title != null && title.toString().contains(titleFilter);
            } else {
                View v = f.view;
                android.app.Activity a = v == null ? null : activityOf(v.getContext());
                ok = v != null && (a == null || a.getWindow().getDecorView() != v) && (activityFilter == null || (a != null && named(a.getClass().getName())));
            }
        } catch (Throwable t) { ok = false; }
        synchronized (wantedCache) { wantedCache.put(f.root, ok); }
        return ok;
    }

    static boolean frostedPanels(Context c) { return c != null && editorMode && !phoneDark() && active(c); }

    static boolean active(Context c) {
        return c != null && SurfaceStyle.get(c) == SurfaceStyle.FROSTED && !SuperDark.inEffect(c) && FrostedSurface.blurEnabled(c);
    }

    static int luminance(int c) { return (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000; }

    static int tone(Context c, boolean darkCard) {
        int fg = FrostedSurface.resourceColor(c, "blur_shade_panel_fg", darkCard ? 0x522c303d : 0x66dedee8);
        int bg = FrostedSurface.resourceColor(c, "blur_shade_panel_bg", darkCard ? 0x520e0e0f : 0x66f9f9fe);
        if (phoneDark() != darkCard) {
            fg = darkCard ? 0x522c303d : 0x66dedee8; bg = darkCard ? 0x520e0e0f : 0x66f9f9fe;
        }
        return (FrostedSurface.composite(fg, bg) & 0xffffff) | 0xcc000000;
    }

    static int preInvert(int target) {
        int r = Math.max(0, Math.min(255, Math.round((O - ((target >> 16) & 0xff)) / -K)));
        int g = Math.max(0, Math.min(255, Math.round((O - ((target >> 8) & 0xff)) / -K)));
        int b = Math.max(0, Math.min(255, Math.round((O - (target & 0xff)) / -K)));
        return (target & 0xff000000) | (r << 16) | (g << 8) | b;
    }
    static void invert(final View v) {
        if (v == null) return;
        synchronized (inverted) { if (inverted.containsKey(v)) return; inverted.put(v, Boolean.TRUE); }
        v.post(new Runnable() { public void run() {
            try {
                Paint inv = new Paint();
                inv.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{K,0,0,0,O, 0,K,0,0,O, 0,0,K,0,O, 0,0,0,1,0}));
                v.setLayerType(View.LAYER_TYPE_HARDWARE, inv);
            } catch (Throwable ignored) { }
        } });
    }

    static Drawable region(Frame f, int i) {
        List<Drawable> list;
        synchronized (regions) { list = regions.get(f.root); if (list == null) { list = new ArrayList<Drawable>(); regions.put(f.root, list); } }
        while (list.size() <= i) {
            try {
                Drawable d = (Drawable) Xp.callMethod(f.root, "createBackgroundBlurDrawable");
                Xp.callMethod(d, "setBlurRadius", FrostedSurface.depthBlurRadius(context(f)));
                try { Xp.callMethod(d, "setColor", 0); } catch (Throwable ignored) { }
                list.add(d);
            } catch (Throwable error) { return null; }
        }
        return list.get(i);
    }

    private static final Map<Object, java.lang.ref.WeakReference<Object>> parents = new WeakHashMap<Object, java.lang.ref.WeakReference<Object>>();
    private static final Map<Drawable, Integer> shownAlpha = new WeakHashMap<Drawable, Integer>();
    private static java.lang.reflect.Field sCanvasNode, sBlurNode;
    private static volatile Method sNodeAlpha;
    private static boolean sFadeFailed;

    static final class Parent extends Xp.Callback {
        @Override protected void beforeHookedMethod(Xp.Param p) {
            try {
                Frame f = frame.get();
                if (f == null || p.args.length != 1 || p.args[0] == null || !wanted(f)) return;
                Object parent = sCanvasNode.get(p.thisObject); if (parent == null) return;
                synchronized (parents) {
                    java.lang.ref.WeakReference<Object> was = parents.get(p.args[0]);
                    if (was == null || was.get() != parent) parents.put(p.args[0], new java.lang.ref.WeakReference<Object>(parent));
                }
            } catch (Throwable ignored) { }
        }
    }

    static void fade(Frame f) {
        Method alpha = sNodeAlpha; if (alpha == null) return;
        List<Drawable> list; synchronized (regions) { list = regions.get(f.root); }
        if (list == null) return;
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Drawable d = list.get(i);
            int a;
            try { a = Math.round(255f * shown(sBlurNode.get(d), alpha)); } catch (Throwable t) { continue; }
            Integer was; synchronized (shownAlpha) { was = shownAlpha.get(d); }
            if ((was == null ? 255 : was) == a) continue;
            synchronized (shownAlpha) { shownAlpha.put(d, a); }
            d.setAlpha(a * paintAlpha(d) / 255); changed = true;
        }
        if (changed && f.view != null) f.view.invalidate();
    }
    static final Map<Drawable, Integer> paintAlphas = new WeakHashMap<Drawable, Integer>();
    static int paintAlpha(Drawable d) { synchronized (paintAlphas) { Integer v = paintAlphas.get(d); return v == null ? 255 : v; } }
    static void colourAlpha(Drawable d, int alpha) {
        synchronized (paintAlphas) { Integer was = paintAlphas.get(d); if (was != null && was == alpha) return; paintAlphas.put(d, alpha); }
        Integer layer; synchronized (shownAlpha) { layer = shownAlpha.get(d); }
        d.setAlpha((layer == null ? 255 : layer) * alpha / 255);
    }
    static float shown(Object node, Method alpha) throws Exception {
        float a = 1f;
        for (int i = 0; node != null && i < 64; i++) {
            a *= (Float) alpha.invoke(node);
            if (a < 0.002f) return 0f;
            java.lang.ref.WeakReference<Object> up; synchronized (parents) { up = parents.get(node); }
            node = up == null ? null : up.get();
        }
        return Math.min(1f, a);
    }

    static final class Card extends Xp.Callback {
        final boolean rectF; Card(boolean rectF) { this.rectF = rectF; }
        @Override protected void beforeHookedMethod(Xp.Param p) {
            try {
                if (blends((Paint) p.args[rectF ? 3 : 6])) return;
                if (fullFill(p)) return;
                Frame f = frame.get(); if (f == null) return;
                Paint paint = (Paint) p.args[rectF ? 3 : 6];
                if (f.editor == 2 && frameCard(p, paint)) return;
                if (!wanted(f)) return;
                if (paint == null || paint.getStyle() != Paint.Style.FILL || paint.getShader() != null) return;
                int c = paint.getColor();
                float l, t, r, b, rx;
                if (rectF) { RectF box = (RectF) p.args[0]; if (box == null) return; l = box.left; t = box.top; r = box.right; b = box.bottom; rx = (Float) p.args[1]; }
                else { l = (Float) p.args[0]; t = (Float) p.args[1]; r = (Float) p.args[2]; b = (Float) p.args[3]; rx = (Float) p.args[4]; }
                if (f.editor == 2 && editorPill(p, f, paint, l, t, r, b, rx)) return;
                if ((c >>> 24) != 0xff) return;
                float w = r - l, h = b - t;
                int lum = luminance(c); boolean darkCard = lum <= 70;
                if (w < 150 * density || w > screenW - 8 * density || h < 40 * density || h > screenH * 0.7f || (!darkCard && lum < 230)) return;
                Context ctx = context(f); if (ctx == null) return;
                if (f.editor > 0 && darkCard && phoneDark()) {
                    if (w >= 330 * density && h <= 200 * density) toolbarColour = c;
                    return;
                }
                if (darkCard && phoneDark() && SuperDark.inEffect(ctx)) {
                    if (c == 0xff000000) return;
                    paint.setColor(0xff000000);
                    Xp.setAdditionalInstanceField(p, "pixelsuite_card", c);
                    Xp.setAdditionalInstanceField(p, "pixelsuite_rim", new float[]{l, t, r, b, rx});
                    return;
                }
                boolean flip = invertDark && darkCard && !phoneDark() && !SuperDark.inEffect(ctx);
                if (flip) invert(f.view);
                if (!active(ctx)) return;
                if (f.editor == 2 && !f.dialog) {
                    paint.setColor(tone(ctx, darkCard));
                    Xp.setAdditionalInstanceField(p, "pixelsuite_card", c);
                    return;
                }
                Drawable d = region(f, f.regions++); if (d == null) return;
                colourAlpha(d, 255);
                d.setBounds(Math.round(l), Math.round(t), Math.round(r), Math.round(b));
                try { Xp.callMethod(d, "setCornerRadius", rx); } catch (Throwable ignored) { }
                d.draw((Canvas) p.thisObject);
                paint.setColor(flip ? preInvert(tone(ctx, false)) : tone(ctx, darkCard));
                Xp.setAdditionalInstanceField(p, "pixelsuite_card", c);
            } catch (Throwable ignored) { }
        }
        @Override protected void afterHookedMethod(Xp.Param p) {
            restore(p);
            Object was = Xp.getAdditionalInstanceField(p, "pixelsuite_card");
            if (was instanceof Integer) try { ((Paint) p.args[rectF ? 3 : 6]).setColor((Integer) was); } catch (Throwable ignored) { }
            Object rim = Xp.getAdditionalInstanceField(p, "pixelsuite_rim");
            if (rim instanceof float[]) try { float[] k = (float[]) rim; DarkChips.drawRim((Canvas) p.thisObject, k[0], k[1], k[2], k[3], k[4], 255); } catch (Throwable ignored) { }
        }
    }

    static boolean editorPill(Xp.Param p, Frame f, Paint paint, float l, float t, float r, float b, float rx) {
        try {
            if (f == null || f.editor != 2 || f.dialog || paint == null || Boolean.TRUE.equals(PillFrost.DRAWING.get())) return false;
            Canvas canvas = (Canvas) p.thisObject;
            if (!canvas.isHardwareAccelerated() || !plain(paint)) return false;
            int c = paint.getColor(), alpha = c >>> 24; if (alpha < 0x08) return false;
            int cr = (c >> 16) & 0xff, cg = (c >> 8) & 0xff, cb = c & 0xff;
            int lum = luminance(c), chroma = Math.max(cr, Math.max(cg, cb)) - Math.min(cr, Math.min(cg, cb));
            float w = r - l, h = b - t;
            if (chroma > 14 || lum < 215 || lum > 244) return false;
            if (w < 36 * density || h < 36 * density || h > 220 * density || w >= screenW * 0.95f) return false;
            Context ctx = context(f); if (ctx == null || !active(ctx) || phoneDark()) return false;
            boolean square = Math.abs(w - h) < 8 * density && w <= 64 * density;
            if (square && lum < 230 && cb - cr >= 5) { p.setResult(null); return true; }
            if (square && c == barColour) { p.setResult(null); return true; }
            if (w >= 150 * density) barColour = c;
            paint.setColor(HoleFrost.scaled(HoleFrost.solid(ctx), alpha));
            Xp.setAdditionalInstanceField(p, "pixelsuite_card", c);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static volatile int barColour;

    private static Method sPaintBlend; private static boolean sPaintBlendMissing;
    static boolean blends(Paint paint) {
        if (paint == null) return false;
        if (paint.getXfermode() == null || sPaintBlendMissing) return false;
        try {
            if (sPaintBlend == null) sPaintBlend = Paint.class.getMethod("getBlendMode");
            Object m = sPaintBlend.invoke(paint);
            String mode = String.valueOf(m);
            return m != null && !"SRC_OVER".equals(mode) && !"SRC".equals(mode);
        } catch (Throwable t) { sPaintBlendMissing = true; return false; }
    }

    static boolean frameCard(Xp.Param p, Paint paint) {
        try {
            if (!((Canvas) p.thisObject).isHardwareAccelerated()) return false;
            if (Boolean.TRUE.equals(PillFrost.DRAWING.get())) return false;
            if (paint == null || paint.getStyle() != Paint.Style.FILL || paint.getShader() != null || (paint.getColor() >>> 24) != 0xff) return false;
            float l, t, r, b;
            if (p.args[0] instanceof RectF) { RectF box = (RectF) p.args[0]; l = box.left; t = box.top; r = box.right; b = box.bottom; }
            else { l = (Float) p.args[0]; t = (Float) p.args[1]; r = (Float) p.args[2]; b = (Float) p.args[3]; }
            float w = r - l, h = b - t;
            if (w < 100 * density || h < 100 * density || w >= screenW * 0.95f) return false;
            float aspect = w / h, screen = screenW / screenH;
            if (Math.abs(aspect - screen) > screen * 0.04f) return false;
            p.setResult(null);
            return true;
        } catch (Throwable t) { return false; }
    }

    static void viewDialog(android.app.Dialog dialog) {
        try {
            if (dialog == null || dialog.getWindow() == null || !activityWanted(dialog.getContext())) return;
            View decor = dialog.getWindow().getDecorView();
            View content = decor.findViewById(android.R.id.content);
            View card = backgrounded(content == null ? decor : content);
            if (card == null && fillOf(decor.getBackground()) != null) card = decor;
            if (card == null) return;
            if (card.getBackground() instanceof PillFrost) return;
            Integer colour = fillOf(card.getBackground()); if (colour == null) return;
            int lum = luminance(colour); boolean darkCard = lum <= 70;
            if (!darkCard && lum < 230) return;
            Context ctx = card.getContext();
            if (darkCard && phoneDark() && SuperDark.inEffect(ctx)) {
                float r = FrostedSurface.dp(ctx, 28);
                try { Object inner = unwrap(card.getBackground()); if (inner instanceof android.graphics.drawable.GradientDrawable) { float x = (Float) Xp.callMethod(inner, "getCornerRadius"); if (x > 0) r = x; } } catch (Throwable ignored) { }
                android.graphics.drawable.GradientDrawable black = new android.graphics.drawable.GradientDrawable();
                black.setColor(0xff000000); black.setCornerRadius(r);
                Drawable sd = new android.graphics.drawable.LayerDrawable(new Drawable[]{black, new DoodleOverlay.Rim(r, FrostedSurface.dp(ctx, 1.5f))});
                if (card == decor) dialog.getWindow().setBackgroundDrawable(sd); else card.setBackground(sd);
                return;
            }
            boolean flip = invertDark && darkCard && !phoneDark() && !SuperDark.inEffect(ctx);
            if (flip) invert(decor);
            if (!active(ctx)) return;
            float corner = FrostedSurface.dp(ctx, 28);
            try { Object inner = unwrap(card.getBackground()); if (inner instanceof android.graphics.drawable.GradientDrawable) { float r = (Float) Xp.callMethod(inner, "getCornerRadius"); if (r > 0) corner = r; } } catch (Throwable ignored) { }
            PillFrost d = PillFrost.frost(card, false, corner);
            if (flip) d.fill.setColor(preInvert(tone(ctx, false)));
            if (card == decor) dialog.getWindow().setBackgroundDrawable(d);
        } catch (Throwable error) { if (!viewDialogFailed) { viewDialogFailed = true; Module.log("compose frost: view dialog not handled", error); } }
    }
    private static boolean viewDialogFailed;
    static View backgrounded(View v) {
        if (fillOf(v.getBackground()) != null && v.getWidth() > 0) return v;
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { View f = backgrounded(g.getChildAt(i)); if (f != null) return f; } }
        return null;
    }
    static Drawable unwrap(Drawable d) {
        for (int i = 0; i < 3 && d != null; i++) {
            if (d instanceof android.graphics.drawable.InsetDrawable) d = ((android.graphics.drawable.InsetDrawable) d).getDrawable();
            else if (d instanceof android.graphics.drawable.LayerDrawable && ((android.graphics.drawable.LayerDrawable) d).getNumberOfLayers() > 0) d = ((android.graphics.drawable.LayerDrawable) d).getDrawable(0);
            else break;
        }
        return d;
    }
    static Integer fillOf(Drawable d) {
        d = unwrap(d); if (d == null) return null;
        try {
            Object c = null;
            if (d instanceof android.graphics.drawable.ColorDrawable) c = ((android.graphics.drawable.ColorDrawable) d).getColor();
            else if (d instanceof android.graphics.drawable.GradientDrawable) c = Xp.callMethod(d, "getColor");
            else if (d.getClass().getName().endsWith("MaterialShapeDrawable")) c = Xp.callMethod(d, "getFillColor");
            if (c instanceof android.content.res.ColorStateList) c = ((android.content.res.ColorStateList) c).getDefaultColor();
            if (!(c instanceof Integer) || (((Integer) c) >>> 24) < 0xf0) return null;
            return (Integer) c;
        } catch (Throwable t) { return null; }
    }

    private static Method sToArgb, sPack, sBlendMode;
    private static int argbOf(Object packed) throws Exception {
        if (sToArgb == null) sToArgb = android.graphics.Color.class.getMethod("toArgb", long.class);
        return (Integer) sToArgb.invoke(null, packed);
    }
    private static Object packOf(int argb) throws Exception {
        if (sPack == null) sPack = android.graphics.Color.class.getMethod("pack", int.class);
        return sPack.invoke(null, argb);
    }

    static boolean fullFill(Xp.Param p) {
        try {
            if (noEditors() || Boolean.TRUE.equals(PillFrost.DRAWING.get())) return false;
            Canvas canvas = (Canvas) p.thisObject;
            if (!canvas.isHardwareAccelerated()) return false;
            Frame f = frame.get();
            int state; boolean dialog;
            if (f != null) {
                state = f.editor; if (state == 0) return false;
                dialog = f.dialog;
            } else {
                if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return false;
                android.app.Activity a = resumedEditor; if (a == null) return false;
                Boolean frosted; synchronized (editors) { frosted = editors.get(a); } if (frosted == null) return false;
                state = frosted ? 2 : 1; dialog = false;
            }
            int colour; float w, h, corner = 0; Paint paint = null;
            Object a0 = p.args.length > 0 ? p.args[0] : null;
            Object last = p.args[p.args.length - 1];
            if (last instanceof Paint) {
                paint = (Paint) last; colour = paint.getColor();
                if (a0 instanceof RectF) { w = ((RectF) a0).width(); h = ((RectF) a0).height(); if (p.args.length == 4) corner = (Float) p.args[1]; }
                else if (a0 instanceof android.graphics.Rect) { w = ((android.graphics.Rect) a0).width(); h = ((android.graphics.Rect) a0).height(); }
                else if (a0 instanceof android.graphics.Path) { RectF b = new RectF(); ((android.graphics.Path) a0).computeBounds(b, true); w = b.width(); h = b.height(); corner = -1; }
                else if (a0 instanceof Paint) { w = canvas.getWidth(); h = canvas.getHeight(); }
                else if (a0 instanceof Float && p.args.length >= 5) { w = (Float) p.args[2] - (Float) p.args[0]; h = (Float) p.args[3] - (Float) p.args[1]; if (p.args.length == 7) corner = (Float) p.args[4]; }
                else return false;
            } else if (a0 instanceof Integer) {
                colour = (Integer) a0; w = canvas.getWidth(); h = canvas.getHeight();
                if (p.args.length > 1 && !srcOver(p.args[1])) return false;
            } else if (a0 instanceof Long) {
                colour = argbOf(a0); w = canvas.getWidth(); h = canvas.getHeight();
                if (p.args.length > 1 && !srcOver(p.args[1])) return false;
            } else return false;
            if (w < screenW * 0.5f || h < 12 * density) return false;
            if (paint != null && !plain(paint)) return false;
            int alpha = colour >>> 24, r = (colour >> 16) & 0xff, g = (colour >> 8) & 0xff, b = colour & 0xff;
            int lum = luminance(colour), chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
            boolean wide = w >= screenW * 0.9f, tall = h >= screenH * 0.5f;
            if (alpha < 0xff) {
                if (alpha == 0 || !wide || !tall || dialog) return false;
                boolean fade = state == 2 && chroma <= 14 && (lum >= 215 || lum <= 50);
                boolean scrim = alpha <= 0xb0 && Math.max(r, Math.max(g, b)) <= 8;
                if (!fade && !scrim) return false;
                p.setResult(null);
                return true;
            }
            if (state == 2) {
                if (dialog) return false;
                boolean surface = chroma <= 14 && (lum >= 215 || lum <= 50);
                boolean square = corner >= 0 && corner <= 4 * density;
                if ((wide && tall) || (surface && square && w >= screenW * 0.95f)) { p.setResult(null); return true; }
                return false;
            }
            if (lum >= 235) { lightSurface = colour; return false; }
            boolean grey = Math.abs(r - g) <= 10 && Math.abs(g - b) <= 10 && lum >= 120 && lum <= 215;
            if (grey && wide && tall && lightSurface != 0 && f != null && !FrostedSurface.dark(context(f))) {
                if (paint != null) { Xp.setAdditionalInstanceField(p, "pixelsuite_fill", colour); paint.setColor(lightSurface); }
                else if (a0 instanceof Integer) p.args[0] = lightSurface;
                else p.args[0] = packOf(lightSurface);
            }
            return false;
        } catch (Throwable t) { return false; }
    }
    static boolean srcOver(Object mode) {
        return mode == null || mode == android.graphics.PorterDuff.Mode.SRC_OVER || mode == android.graphics.PorterDuff.Mode.SRC || "SRC_OVER".equals(String.valueOf(mode)) || "SRC".equals(String.valueOf(mode));
    }
    static void restore(Xp.Param p) {
        Object was = Xp.getAdditionalInstanceField(p, "pixelsuite_fill");
        if (was instanceof Integer) try { ((Paint) p.args[p.args.length - 1]).setColor((Integer) was); } catch (Throwable ignored) { }
    }
    static boolean plain(Paint paint) {
        if (paint.getStyle() != Paint.Style.FILL || paint.getShader() != null) return false;
        if (paint.getXfermode() == null) return true;
        try {
            if (sBlendMode == null) sBlendMode = Paint.class.getMethod("getBlendMode");
            String mode = String.valueOf(sBlendMode.invoke(paint));
            return "SRC_OVER".equals(mode) || "SRC".equals(mode);
        } catch (Throwable t) { return false; }
    }

    private static final Map<Class<?>, Method> sFilterColor = new java.util.HashMap<Class<?>, Method>();
    static boolean boostIcon(Xp.Param p, Paint paint) {
        try {
            if (paint == null) return false;
            Frame f = frame.get(); if (f == null || f.editor != 2 || phoneDark()) return false;
            android.graphics.ColorFilter filter = paint.getColorFilter();
            if (filter != null) {
                Method m;
                synchronized (sFilterColor) { m = sFilterColor.get(filter.getClass()); if (m == null) { m = filter.getClass().getMethod("getColor"); sFilterColor.put(filter.getClass(), m); } }
                int c = (Integer) m.invoke(filter);
                int v = iconInk(c); if (v == c) return false;
                Xp.setAdditionalInstanceField(p, "pixelsuite_filter", filter);
                paint.setColorFilter(new android.graphics.PorterDuffColorFilter(v, android.graphics.PorterDuff.Mode.SRC_IN));
                return true;
            }
            int c = paint.getColor();
            if (paint.getShader() != null) return false;
            int v = iconInk(c); if (v == c) return false;
            Xp.setAdditionalInstanceField(p, "pixelsuite_ink", c);
            paint.setColor(v);
            return true;
        } catch (Throwable t) { return false; }
    }
    static int iconInk(int c) {
        int a = c >>> 24, cr = (c >> 16) & 0xff, cg = (c >> 8) & 0xff, cb = c & 0xff;
        int lum = luminance(c), chroma = Math.max(cr, Math.max(cg, cb)) - Math.min(cr, Math.min(cg, cb));
        if (a >= 0x10 && lum >= 55 && lum <= 130 && chroma <= 24) { int ink = iconColour; return ink == 0 ? c : (a << 24) | (ink & 0xffffff); }
        if (a >= 0xf0 && lum >= 20 && lum < 55 && chroma <= 16) iconColour = c;
        if (a >= 0x58 && a <= 0x6a && lum < 55) return (c & 0xffffff) | 0x8c000000;
        return c;
    }
    private static volatile int iconColour;
    static void unboost(Xp.Param p, Paint paint) {
        if (paint == null) return;
        Object filter = Xp.getAdditionalInstanceField(p, "pixelsuite_filter");
        if (filter instanceof android.graphics.ColorFilter) paint.setColorFilter((android.graphics.ColorFilter) filter);
        Object c = Xp.getAdditionalInstanceField(p, "pixelsuite_ink");
        if (c instanceof Integer) paint.setColor((Integer) c);
    }
    static Paint lastPaint(Xp.Param p) { return p.args.length > 0 && p.args[p.args.length - 1] instanceof Paint ? (Paint) p.args[p.args.length - 1] : null; }

    static final class Icon extends Xp.Callback {
        @Override protected void beforeHookedMethod(Xp.Param p) {
            try {
                if (noEditors() || p.args.length == 0 || !(p.args[0] instanceof android.graphics.Bitmap)) return;
                android.graphics.Bitmap icon = (android.graphics.Bitmap) p.args[0];
                if (icon.getWidth() <= 48 * density && icon.getHeight() <= 48 * density) boostIcon(p, lastPaint(p));
            } catch (Throwable ignored) { }
        }
        @Override protected void afterHookedMethod(Xp.Param p) { unboost(p, lastPaint(p)); }
    }

    static final class Fill extends Xp.Callback {
        @Override protected void beforeHookedMethod(Xp.Param p) {
            try {
                if (fullFill(p)) return;
                if (!noEditors() && p.args.length >= 2 && p.args[p.args.length - 1] instanceof Paint) {
                    Object a0 = p.args[0]; RectF b = null;
                    if (a0 instanceof RectF) b = new RectF((RectF) a0);
                    else if (a0 instanceof android.graphics.Rect) b = new RectF((android.graphics.Rect) a0);
                    else if (a0 instanceof Float && p.args.length == 5) b = new RectF((Float) p.args[0], (Float) p.args[1], (Float) p.args[2], (Float) p.args[3]);
                    if (b != null && editorPill(p, frame.get(), (Paint) p.args[p.args.length - 1], b.left, b.top, b.right, b.bottom, 0f)) return;
                }
                if (p.args.length == 2 && p.args[0] instanceof android.graphics.Path && !noEditors()) {
                    RectF b = new RectF(); ((android.graphics.Path) p.args[0]).computeBounds(b, true);
                    if (b.width() <= 48 * density && b.height() <= 48 * density) boostIcon(p, lastPaint(p));
                    else if (p.args[1] instanceof Paint && editorPill(p, frame.get(), (Paint) p.args[1], b.left, b.top, b.right, b.bottom, Math.min(b.width(), b.height()) / 2f)) return;
                }
            } catch (Throwable ignored) { }
        }
        @Override protected void afterHookedMethod(Xp.Param p) {
            restore(p);
            Object was = Xp.getAdditionalInstanceField(p, "pixelsuite_card");
            if (was instanceof Integer) try { ((Paint) p.args[p.args.length - 1]).setColor((Integer) was); } catch (Throwable ignored) { }
            if (p.args.length == 2 && p.args[0] instanceof android.graphics.Path) unboost(p, lastPaint(p));
        }
    }
}
