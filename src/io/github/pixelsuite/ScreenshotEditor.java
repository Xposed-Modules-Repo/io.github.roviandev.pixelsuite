package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Picture;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScreenshotEditor {
    static final String PACKAGE = "com.google.android.apps.pixel.creativeassistant";
    static final int ICON_ID = 0x7f7ffd01;
    static final String TOOLBAR_LAMBDA = "jgn", PEN_RESTART = "jip";
    private static final Feature.Cached sFreeCrop = new Feature.Cached(Feature.SCREENSHOT_FREE_CROP);
    private static Boolean sDoodles;
    static ClassLoader loader;
    private static final java.util.Set<String> sLogged = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private ScreenshotEditor() {}

    static void logOnce(String message, Throwable error) {
        if (!sLogged.add(message)) return;
        if (error == null) Module.log("screenshot-editor: " + message); else Module.log("screenshot-editor: " + message, error);
    }

    static void install(ClassLoader cl) {
        loader = cl;
        installFreeCrop(cl);
        installDoodles(cl);
        installUndoRedo(cl);
    }

    private static WeakReference<Object> sHistoryOwner = new WeakReference<Object>(null);
    private static Field sHistoryState;
    private static Method sStateGet, sStateSet, sHistoryRefresh;
    private static Constructor<?> sHistoryMake;
    private static Field sCanUndo, sCanRedo, sModified;

    static void installUndoRedo(ClassLoader cl) {
        try {
            final Class<?> khq = Xp.findClass("khq", cl), jqx = Xp.findClass("jqx", cl), state = Xp.findClass("bsd", cl);
            final Class<?> svf = Xp.findClass("svf", cl), jix = Xp.findClass("jix", cl);
            sHistoryState = field(khq, "c");
            sStateGet = state.getMethod("a");
            sStateSet = state.getMethod("b", Object.class);
            sHistoryMake = jqx.getConstructor(boolean.class, boolean.class, boolean.class);
            sCanUndo = field(jqx, "a"); sCanRedo = field(jqx, "b"); sModified = field(jqx, "c");
            sHistoryRefresh = method(khq, "f");
            final Object unit = staticField(Xp.findClass("ssh", cl), "a");
            Xp.hookMethod(sHistoryRefresh, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    sHistoryOwner = new WeakReference<Object>(p.thisObject);
                    publishDoodleHistory(p.thisObject);
                }
            });
            Method bar = null;
            for (Method m : jix.getDeclaredMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (m.getName().equals("e") && t.length == 6 && t[0] == boolean.class && t[1] == boolean.class && t[2] == svf && t[3] == svf) bar = m;
            }
            if (bar == null) throw new NoSuchMethodException("jix.e(undo, redo)");
            bar.setAccessible(true);
            final Class<?> function = svf;
            Xp.hookMethod(bar, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        p.args[0] = Boolean.TRUE.equals(p.args[0]) || DoodleOverlay.canUndo();
                        p.args[1] = Boolean.TRUE.equals(p.args[1]) || DoodleOverlay.canRedo();
                        p.args[2] = wrapClick(function, p.args[2], true, unit);
                        p.args[3] = wrapClick(function, p.args[3], false, unit);
                    } catch (Throwable error) { logOnce("undo/redo wrap failed", error); }
                }
            });
            Module.log("screenshot-editor: undo/redo hooks installed");
        } catch (Throwable error) {
            logOnce("undo/redo for doodles unavailable in this Pixel Studio version", error);
        }
    }

    private static Object wrapClick(Class<?> function, final Object original, final boolean undo, final Object unit) {
        if (original == null) return null;
        return Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                if (m.getName().equals("a") && m.getParameterTypes().length == 0) {
                    if (undo ? DoodleOverlay.undo() : DoodleOverlay.redo()) return unit;
                }
                if (m.getDeclaringClass() == Object.class) {
                    if (m.getName().equals("equals")) return proxy == args[0];
                    if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (m.getName().equals("toString")) return original.toString();
                }
                try { return m.invoke(original, args); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            }
        });
    }

    static void publishDoodleHistory(Object owner) {
        try {
            Object state = sHistoryState.get(owner);
            if (state == null || sStateSet == null || !sStateGet.getDeclaringClass().isInstance(state)) return;
            boolean undo = DoodleOverlay.canUndo(), redo = DoodleOverlay.canRedo(), any = DoodleOverlay.hasShapes();
            if (!undo && !redo && !any) return;
            Object now = sStateGet.invoke(state);
            sStateSet.invoke(state, sHistoryMake.newInstance(
                    sCanUndo.getBoolean(now) || undo, sCanRedo.getBoolean(now) || redo, sModified.getBoolean(now) || any));
        } catch (Throwable error) { logOnce("undo/redo state not published", error); }
    }

    static void doodleHistoryChanged() {
        try {
            Object owner = sHistoryOwner.get();
            if (owner != null && sHistoryRefresh != null) sHistoryRefresh.invoke(owner);
        } catch (Throwable error) { logOnce("undo/redo state not refreshed", error); }
    }

    static Context app() {
        try { Object a = Xp.callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication"); return (Context) a; }
        catch (Throwable ignored) { return null; }
    }

    static boolean setting(Feature.Cached cached, String key) {
        Context c = app(); if (c == null) return true;
        try { Settings.Secure.getInt(c.getContentResolver(), key); }
        catch (Settings.SettingNotFoundException unset) { return true; }
        catch (Throwable unreadable) { logOnce("settings not readable here, " + key + " stays on", unreadable); return true; }
        return cached.on(c);
    }

    static boolean doodlesOn() {
        if (sDoodles == null) {
            if (app() == null) return true;
            sDoodles = setting(new Feature.Cached(Feature.SCREENSHOT_DOODLES), Feature.SCREENSHOT_DOODLES);
            Module.log("screenshot-editor: doodles " + (sDoodles ? "on" : "off") + " for this editor process");
        }
        return sDoodles;
    }

    static Object staticField(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }
    static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    static Method method(Class<?> type, String name, Class<?>... params) throws Exception {
        Method m = type.getDeclaredMethod(name, params); m.setAccessible(true); return m;
    }

    static void installFreeCrop(ClassLoader cl) {
        try {
            final Class<?> handler = Xp.findClass("jfs", cl);
            final Class<?> rect = Xp.findClass("cer", cl), state = Xp.findClass("bsd", cl), svq = Xp.findClass("svq", cl);
            Constructor<?> ctor = handler.getDeclaredConstructor(state, state, rect, float.class, svq);
            Xp.hookMethod(ctor, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (!setting(sFreeCrop, Feature.SCREENSHOT_FREE_CROP)) return;
                    float stock = ((Number) p.args[3]).floatValue();
                    p.args[3] = 1f;
                    logOnce("free crop on: minimum crop size " + Math.round(stock) + " px -> 1 px", null);
                }
            });
            installCropHandles(cl, rect, state);
            Module.log("screenshot-editor: free crop hooks installed");
        } catch (Throwable error) {
            logOnce("free crop unavailable in this Pixel Studio version", error);
        }
    }

    static void installCropHandles(ClassLoader cl, Class<?> rectType, Class<?> stateType) throws Exception {
        final Class<?> lambda = Xp.findClass("hsj", cl);
        final Field which = field(lambda, "c"), rectState = field(lambda, "a"), handleState = field(lambda, "b");
        final Method get = stateType.getMethod("a"), set = stateType.getMethod("b", Object.class);
        final Field left = field(rectType, "b"), top = field(rectType, "c"), right = field(rectType, "d"), bottom = field(rectType, "e");
        final Field offset = field(Xp.findClass("ceq", cl), "a");
        Class<?> edges = Xp.findClass("jez", cl);
        final Object[] handle = new Object[9];
        String[] names = {"a", "b", "c", "d", "e", "f", "g", "h", "i"};
        for (int i = 0; i < 9; i++) handle[i] = staticField(edges, names[i]);
        if (!"INSIDE".equals(String.valueOf(handle[8])) && !String.valueOf(handle[8]).contains("INSIDE")) {
            logOnce("crop handle names changed (" + handle[8] + "), stock touch areas kept", null);
            return;
        }
        final Object unit = staticField(Xp.findClass("ssh", cl), "a");
        Method invoke = lambda.getDeclaredMethod("a", Object.class);
        Xp.hookMethod(invoke, new Xp.Callback() {
            @Override protected void beforeHookedMethod(Xp.Param p) {
                try {
                    if (which.getInt(p.thisObject) != 20 || p.args[0] == null) return;
                    if (!setting(sFreeCrop, Feature.SCREENSHOT_FREE_CROP)) return;
                    Object r = get.invoke(rectState.get(p.thisObject));
                    if (r == null) return;
                    long packed = offset.getLong(p.args[0]);
                    float x = Float.intBitsToFloat((int) (packed >> 32)), y = Float.intBitsToFloat((int) packed);
                    int pick = CropHit.pick(left.getFloat(r), top.getFloat(r), right.getFloat(r), bottom.getFloat(r), x, y,
                            24 * Resources.getSystem().getDisplayMetrics().density);
                    set.invoke(handleState.get(p.thisObject), pick < 0 ? null : handle[pick]);
                    p.setResult(unit);
                } catch (Throwable error) {
                    logOnce("crop handle hit test failed, stock one used", error);
                }
            }
        });
    }

    static Class<?> svfType, stateType;
    static Method penButton, toolButton, spacer, width, readState, writeState;
    static Object emptyModifier, selectedState, clickProxy, painter;
    static final Map<String, Boolean> nativeSelected = new HashMap<String, Boolean>();
    static Object doneClick;
    static WeakReference<View> canvasView = new WeakReference<View>(null);
    static float lastDownRawY = -1, lastDownRawX = -1;
    static final Handler main = new Handler(Looper.getMainLooper());

    static void installDoodles(final ClassLoader cl) {
        try {
            final Class<?> jix = Xp.findClass("jix", cl), composer = Xp.findClass("bqi", cl), modifier = Xp.findClass("cbw", cl);
            final Class<?> painterType = Xp.findClass("chx", cl);
            svfType = Xp.findClass("svf", cl);
            stateType = Xp.findClass("bsd", cl);
            penButton = method(jix, "k", modifier, painterType, painterType, long.class, boolean.class, svfType, composer, int.class);
            toolButton = method(jix, "L", int.class, svfType, boolean.class, modifier, composer, int.class);
            spacer = method(Xp.findClass("afq", cl), "z", modifier, composer);
            width = method(Xp.findClass("ajd", cl), "i", modifier, float.class);
            emptyModifier = staticField(modifier, "g");
            readState = stateType.getMethod("a");
            writeState = stateType.getMethod("b", Object.class);
            Class<?> mutable = Xp.findClass("btx", cl), policyType = Xp.findClass("bty", cl);
            Constructor<?> state = mutable.getDeclaredConstructor(Object.class, policyType);
            state.setAccessible(true);
            selectedState = state.newInstance(Boolean.FALSE, staticField(Xp.findClass("buf", cl), "a"));
            final Method doneButton = method(jix, "f", String.class, boolean.class, svfType, int.class, composer, int.class, int.class);
            final Class<?> clickLambda = Xp.findClass("jgi", cl);
            final Field clickCase = field(clickLambda, "c");

            Method painterResource = method(Xp.findClass("dee", cl), "J", int.class, composer, int.class);
            final Constructor<?> bitmapPainter = Xp.findClass("chv", cl).getDeclaredConstructor(Xp.findClass("cey", cl));
            final Constructor<?> imageBitmap = Xp.findClass("cey", cl).getDeclaredConstructor(Bitmap.class);
            bitmapPainter.setAccessible(true); imageBitmap.setAccessible(true);
            Xp.hookMethod(painterResource, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (((Integer) p.args[0]) != ICON_ID) return;
                    try {
                        if (painter == null) painter = bitmapPainter.newInstance(imageBitmap.newInstance(DoodleOverlay.toolIcon(app())));
                        p.setResult(painter);
                    } catch (Throwable error) { logOnce("doodles icon unavailable", error); }
                }
            });

            Xp.hookMethod(penButton, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    Object click = p.args[5];
                    int which = -1;
                    try { if (clickLambda.isInstance(click)) which = clickCase.getInt(click); } catch (Throwable ignored) { }
                    track("stroke" + which, Boolean.TRUE.equals(p.args[4]));
                    if (which != 4 || !doodlesOn() || !fromToolbarRow()) return;
                    try {
                        Object composer = p.args[6];
                        boolean selected = Boolean.TRUE.equals(readState.invoke(selectedState));
                        spacer.invoke(null, width.invoke(null, emptyModifier, 4f), composer);
                        toolButton.invoke(null, ICON_ID, click(), selected, emptyModifier, composer, 0);
                        logOnce("doodles button added after the pen", null);
                    } catch (Throwable error) { logOnce("doodles button could not be added", error); }
                }
            });
            Xp.hookMethod(toolButton, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    int id = (Integer) p.args[0];
                    if (id == ICON_ID) return;
                    track("tool" + id, Boolean.TRUE.equals(p.args[2]));
                }
            });
            Xp.hookMethod(doneButton, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        Context c = app();
                        int id = c == null ? 0 : c.getResources().getIdentifier("edit_menu_icon_done_accessibility", "string", PACKAGE);
                        if (id != 0 && c.getString(id).equals(p.args[0])) doneClick = p.args[2];
                    } catch (Throwable ignored) { }
                }
            });

            installRendering(cl);
            Xp.hookMethod(android.app.Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class), new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    MotionEvent e = (MotionEvent) p.args[0];
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { lastDownRawX = e.getRawX(); lastDownRawY = e.getRawY(); }
                }
            });
            Module.log("screenshot-editor: doodles hooks installed");
        } catch (Throwable error) {
            logOnce("doodles unavailable in this Pixel Studio version", error);
        }
    }

    static boolean fromToolbarRow() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (int i = 0; i < stack.length && i < 80; i++) {
            String name = stack[i].getClassName();
            if (TOOLBAR_LAMBDA.equals(name)) return true;
            if (PEN_RESTART.equals(name)) return false;
        }
        logOnce("toolbar row not found on the stack, doodles button skipped", null);
        return false;
    }

    static void track(String key, boolean selected) {
        Boolean before = nativeSelected.put(key, selected);
        if (selected && !Boolean.TRUE.equals(before) && DoodleOverlay.active()) {
            main.post(new Runnable() { public void run() { DoodleOverlay.leave(false); } });
        }
    }

    static boolean anyNativeToolOn() {
        for (Boolean b : nativeSelected.values()) if (Boolean.TRUE.equals(b)) return true;
        return false;
    }

    static void setSelected(boolean on) {
        try { writeState.invoke(selectedState, on); } catch (Throwable error) { logOnce("doodles button state not updated", error); }
    }

    static Object click() {
        if (clickProxy != null) return clickProxy;
        clickProxy = Proxy.newProxyInstance(loader, new Class<?>[]{svfType}, new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                String name = m.getName();
                if ("equals".equals(name)) return proxy == args[0];
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("toString".equals(name)) return "PixelSuiteDoodlesClick";
                main.post(new Runnable() { public void run() { DoodleOverlay.toggle(); } });
                return staticField(Xp.findClass("ssh", loader), "a");
            }
        });
        return clickProxy;
    }

    static void closeNativeTool() {
        Object done = doneClick;
        if (done == null || !anyNativeToolOn()) return;
        try { svfType.getMethod("a").invoke(done); } catch (Throwable error) { logOnce("native tool could not be closed", error); }
    }

    static Field rendererCanvas;
    static Method canvasMatrix;

    static void installRendering(ClassLoader cl) throws Exception {
        final Class<?> renderer = Xp.findClass("irq", cl), config = Xp.findClass("irl", cl), view = Xp.findClass("irt", cl);
        rendererCanvas = field(renderer, "c");
        canvasMatrix = method(Xp.findClass("ira", cl), "f");
        final Field toExport = field(config, "f");
        Xp.hookMethod(method(renderer, "a", config), new Xp.Callback() {
            @Override protected void afterHookedMethod(Xp.Param p) {
                List<Doodle> shapes = DoodleOverlay.snapshot(p.thisObject);
                if (shapes.isEmpty() || !(p.getResult() instanceof Picture)) return;
                try {
                    Picture original = (Picture) p.getResult();
                    Picture out = new Picture();
                    Canvas canvas = out.beginRecording(original.getWidth(), original.getHeight());
                    canvas.drawPicture(original);
                    int save = canvas.save();
                    canvas.concat((Matrix) canvasMatrix.invoke(rendererCanvas.get(p.thisObject)));
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    for (Doodle d : shapes) d.draw(canvas, paint);
                    canvas.restoreToCount(save);
                    out.endRecording();
                    p.setResult(out);
                    if (toExport.getBoolean(p.args[0])) Module.log("screenshot-editor: " + shapes.size() + " doodles saved into the image");
                } catch (Throwable error) { logOnce("doodles could not be drawn into the image", error); }
            }
        });
        Xp.hookAllConstructors(view, new Xp.Callback() {
            @Override protected void afterHookedMethod(Xp.Param p) { canvasView = new WeakReference<View>((View) p.thisObject); }
        });
    }

    static View canvasFor(Activity a) {
        View v = canvasView.get();
        if (v == null || !v.isAttachedToWindow()) return null;
        Context c = v.getContext();
        while (c instanceof android.content.ContextWrapper && !(c instanceof Activity)) c = ((android.content.ContextWrapper) c).getBaseContext();
        return c == a || c == null ? v : null;
    }

    static Activity activity() {
        View v = canvasView.get(); if (v == null) return null;
        Context c = v.getContext();
        while (c instanceof android.content.ContextWrapper && !(c instanceof Activity)) c = ((android.content.ContextWrapper) c).getBaseContext();
        return c instanceof Activity ? (Activity) c : null;
    }
}
