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
    private static final Feature.Cached sFreeCrop = new Feature.Cached(Feature.SCREENSHOT_FREE_CROP);
    private static Boolean sDoodles;
    static ClassLoader loader;
    private static final java.util.Set<String> sLogged = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private ScreenshotEditor() {}

    static void logOnce(String message, Throwable error) {
        if (!sLogged.add(message)) return;
        if (error == null) Module.log("screenshot-editor: " + message); else Module.log("screenshot-editor: " + message, error);
    }

    static void install(ClassLoader cl, android.content.pm.ApplicationInfo ai) {
        loader = cl;
        StudioNames.resolve(cl, ai);
        installFreeCrop(cl);
        installDoodles(cl);
        installUndoRedo(cl);
        try {
            Xp.hookMethod(android.app.Activity.class.getDeclaredMethod("onResume"), new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { if (SuperDark.inEffectNow()) DarkChips.on("Pixel Studio"); }
            });
        } catch (Throwable error) { logOnce("dark tool bar unavailable", error); }
        try {
            Xp.hookMethod(android.app.Activity.class.getDeclaredMethod("onCreate", android.os.Bundle.class), new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    String name = p.thisObject.getClass().getName();
                    Activity a = (Activity) p.thisObject;
                    boolean edit = false; try { edit = a.getIntent() != null && android.content.Intent.ACTION_EDIT.equals(a.getIntent().getAction()); } catch (Throwable ignored) { }
                    if (name.endsWith("GandalfActivity") || name.endsWith("ExternalEditActivity") || edit) {
                        boolean superDark = SuperDark.inEffect(a);
                        if (!superDark) WindowFrost.frost(a, "screenshot editor");
                        ComposeFrost.editor(a, SurfaceStyle.restyled(a) && !superDark);
                    }
                }
            });
        } catch (Throwable error) { logOnce("editor window frost unavailable", error); }
        ComposeFrost.install("Pixel Studio", null, true);
    }

    private static WeakReference<Object> sHistoryOwner = new WeakReference<Object>(null);
    private static Field sHistoryState;
    private static Method sStateGet, sStateSet, sHistoryRefresh;
    private static Constructor<?> sHistoryMake;
    private static Field sCanUndo, sCanRedo, sModified;

    static void installUndoRedo(ClassLoader cl) {
        try {
            final Class<?> khq = StudioNames.cls("history", cl), jqx = StudioNames.cls("undoState", cl), state = StudioNames.cls("state", cl);
            final Class<?> svf = StudioNames.cls("fn0", cl), jix = StudioNames.cls("ui", cl), composer = StudioNames.cls("composer", cl);
            sStateGet = getter(state);
            sStateSet = setter(state);
            sHistoryMake = jqx.getDeclaredConstructor(boolean.class, boolean.class, boolean.class);
            sHistoryMake.setAccessible(true);
            sCanUndo = probeFlag(jqx, true, false, false); sCanRedo = probeFlag(jqx, false, true, false); sModified = probeFlag(jqx, false, false, true);
            sHistoryRefresh = method(khq, StudioNames.m("history.refresh"));
            final Object unit = unit(cl);
            Xp.hookMethod(sHistoryRefresh, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    sHistoryOwner = new WeakReference<Object>(p.thisObject);
                    publishDoodleHistory(p.thisObject);
                }
            });
            Method bar = method(jix, StudioNames.m("ui.undoBar"), boolean.class, boolean.class, svf, svf, composer, int.class);
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
                if (m.getDeclaringClass() != Object.class && m.getParameterTypes().length == 0) {
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
            if (sHistoryState == null) sHistoryState = historyField(owner);
            Object state = sHistoryState == null ? null : sHistoryState.get(owner);
            if (state == null || sStateSet == null || !sStateGet.getDeclaringClass().isInstance(state)) return;
            boolean undo = DoodleOverlay.canUndo(), redo = DoodleOverlay.canRedo(), any = DoodleOverlay.hasShapes();
            if (!undo && !redo && !any) return;
            Object now = sStateGet.invoke(state);
            sStateSet.invoke(state, sHistoryMake.newInstance(
                    sCanUndo.getBoolean(now) || undo, sCanRedo.getBoolean(now) || redo, sModified.getBoolean(now) || any));
        } catch (Throwable error) { logOnce("undo/redo state not published", error); }
    }

    private static Field historyField(Object owner) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v != null && sStateGet.getDeclaringClass().isInstance(v) && sHistoryMake.getDeclaringClass().isInstance(sStateGet.invoke(v))) return f;
                } catch (Throwable ignored) { }
            }
        }
        logOnce("undo/redo state field not found", null);
        return null;
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

    static Method sam(Class<?> iface) throws NoSuchMethodException {
        for (Method m : iface.getMethods()) if (m.getDeclaringClass() != Object.class && !m.isDefault() && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) return m;
        throw new NoSuchMethodException("no method in " + iface.getName());
    }
    static Method getter(Class<?> state) throws NoSuchMethodException {
        for (Method m : state.getMethods()) if (m.getParameterTypes().length == 0 && m.getReturnType() == Object.class && m.getDeclaringClass() != Object.class) return m;
        throw new NoSuchMethodException("state getter in " + state.getName());
    }
    static Method setter(Class<?> state) throws NoSuchMethodException {
        for (Method m : state.getMethods()) { Class<?>[] t = m.getParameterTypes(); if (t.length == 1 && t[0] == Object.class && m.getReturnType() == void.class) return m; }
        throw new NoSuchMethodException("state setter in " + state.getName());
    }
    static Object unit(ClassLoader cl) throws Exception {
        Class<?> u = StudioNames.cls("unit", cl);
        for (Field f : u.getDeclaredFields()) if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == u) { f.setAccessible(true); return f.get(null); }
        throw new NoSuchFieldException("kotlin.Unit instance");
    }
    static Field fieldOfType(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        Field found = null;
        for (Field f : owner.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != type) continue;
            if (found != null) throw new NoSuchFieldException("more than one " + type.getName() + " in " + owner.getName());
            found = f;
        }
        if (found == null) throw new NoSuchFieldException(type.getName() + " in " + owner.getName());
        found.setAccessible(true);
        return found;
    }
    private static Field probeFlag(Class<?> type, boolean a, boolean b, boolean c) throws Exception {
        Object v = sHistoryMake.newInstance(a, b, c);
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != boolean.class) continue;
            f.setAccessible(true);
            if (f.getBoolean(v)) return f;
        }
        throw new NoSuchFieldException("undo flag in " + type.getName());
    }
    static Map<String, Object> enumByName(Class<?> type) throws Exception {
        Map<String, Object> out = new HashMap<String, Object>();
        for (Field f : type.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != type) continue;
            f.setAccessible(true);
            Object v = f.get(null);
            if (v != null) out.put(String.valueOf(v), v);
        }
        return out;
    }

    static void installFreeCrop(ClassLoader cl) {
        try {
            final Class<?> handler = StudioNames.cls("crop", cl);
            final Class<?> rect = StudioNames.cls("rect", cl), state = StudioNames.cls("state", cl), svq = StudioNames.cls("fn1", cl);
            Constructor<?> ctor = handler.getDeclaredConstructor(state, state, rect, float.class, svq);
            Xp.hookMethod(ctor, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    remember(sRectStates, p.args[0]);
                    remember(sHandleStates, p.args[1]);
                    if (!setting(sFreeCrop, Feature.SCREENSHOT_FREE_CROP)) return;
                    p.args[3] = 1f;
                }
            });
            installCropHandles(cl, rect, state);
            Module.log("screenshot-editor: free crop hooks installed");
        } catch (Throwable error) {
            logOnce("free crop unavailable in this Pixel Studio version", error);
        }
    }

    private static final List<WeakReference<Object>> sRectStates = new java.util.ArrayList<WeakReference<Object>>();
    private static final List<WeakReference<Object>> sHandleStates = new java.util.ArrayList<WeakReference<Object>>();

    private static void remember(List<WeakReference<Object>> list, Object o) {
        if (o == null) return;
        synchronized (list) {
            for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).get() == null || list.size() > 8) list.remove(i);
            list.add(new WeakReference<Object>(o));
        }
    }
    private static boolean holds(List<WeakReference<Object>> list, Object o) {
        if (o == null) return false;
        synchronized (list) { for (WeakReference<Object> r : list) if (r.get() == o) return true; }
        return false;
    }

    static void installCropHandles(ClassLoader cl, Class<?> rectType, Class<?> stateType) throws Exception {
        final Class<?> lambda = StudioNames.cls("cropLambda", cl);
        final List<Field> slots = new java.util.ArrayList<Field>();
        for (Field f : lambda.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true); slots.add(f);
        }
        final Method get = getter(stateType), set = setter(stateType);
        final Field[] ltrb = new Field[4];
        Constructor<?> make = rectType.getDeclaredConstructor(float.class, float.class, float.class, float.class);
        make.setAccessible(true);
        Object probe = make.newInstance(1f, 2f, 3f, 4f);
        for (Field f : rectType.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != float.class) continue;
            f.setAccessible(true);
            int v = Math.round(f.getFloat(probe));
            if (v >= 1 && v <= 4) ltrb[v - 1] = f;
        }
        for (Field f : ltrb) if (f == null) throw new NoSuchFieldException("crop rect edges in " + rectType.getName());
        final Map<String, Object> edges = enumByName(StudioNames.cls("edges", cl));
        final Object[] handle = new Object[9];
        String[] names = {"LEFT", "TOP", "RIGHT", "BOTTOM", "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT", "INSIDE"};
        for (int i = 0; i < 9; i++) {
            handle[i] = edges.get(names[i]);
            if (handle[i] == null) { logOnce("crop handle names changed (" + edges.keySet() + "), stock touch areas kept", null); return; }
        }
        final Object unit = unit(cl);
        Method invoke = lambda.getDeclaredMethod(sam(StudioNames.cls("fn1", cl)).getName(), Object.class);
        invoke.setAccessible(true);
        final Map<Class<?>, Field> offsetField = new HashMap<Class<?>, Field>();
        Xp.hookMethod(invoke, new Xp.Callback() {
            @Override protected void beforeHookedMethod(Xp.Param p) {
                try {
                    Object at = p.args[0];
                    if (at == null) return;
                    Object rectState = null, handleState = null;
                    for (Field f : slots) {
                        Object v = f.get(p.thisObject);
                        if (holds(sRectStates, v)) rectState = v; else if (holds(sHandleStates, v)) handleState = v;
                    }
                    if (rectState == null || handleState == null) return;
                    if (!setting(sFreeCrop, Feature.SCREENSHOT_FREE_CROP)) return;
                    Field off = offsetField.get(at.getClass());
                    if (off == null) { off = fieldOfType(at.getClass(), long.class); offsetField.put(at.getClass(), off); }
                    Object r = get.invoke(rectState);
                    if (!rectType.isInstance(r)) return;
                    long packed = off.getLong(at);
                    float x = Float.intBitsToFloat((int) (packed >> 32)), y = Float.intBitsToFloat((int) packed);
                    int pick = CropHit.pick(ltrb[0].getFloat(r), ltrb[1].getFloat(r), ltrb[2].getFloat(r), ltrb[3].getFloat(r), x, y,
                            24 * Resources.getSystem().getDisplayMetrics().density);
                    set.invoke(handleState, pick < 0 ? null : handle[pick]);
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
            final Class<?> jix = StudioNames.cls("ui", cl), composer = StudioNames.cls("composer", cl), modifier = StudioNames.cls("modifier", cl);
            final Class<?> painterType = StudioNames.cls("painter", cl);
            uiClass = jix.getName(); painterClass = painterType;
            svfType = StudioNames.cls("fn0", cl);
            stateType = StudioNames.cls("state", cl);
            penButton = method(jix, StudioNames.m("ui.pen"), modifier, painterType, painterType, long.class, boolean.class, svfType, composer, int.class);
            toolButton = method(jix, StudioNames.m("ui.tool"), int.class, svfType, boolean.class, modifier, composer, int.class);
            try {
                spacer = method(StudioNames.cls("spacer", cl), StudioNames.m("spacer.m"), modifier, composer);
                width = method(StudioNames.cls("width", cl), StudioNames.m("width.m"), modifier, float.class);
            } catch (Throwable missing) { spacer = width = null; logOnce("doodles button gap left out (" + missing + ")", null); }
            emptyModifier = emptyModifier(modifier);
            readState = getter(stateType);
            writeState = setter(stateType);
            Class<?> mutable = StudioNames.cls("mutable", cl), policyType = StudioNames.cls("policy", cl);
            Constructor<?> state = mutable.getDeclaredConstructor(Object.class, policyType);
            state.setAccessible(true);
            selectedState = state.newInstance(Boolean.FALSE, structuralPolicy(StudioNames.cls("policy.impl", cl)));
            final Method doneButton = method(jix, StudioNames.m("ui.done"), String.class, boolean.class, svfType, int.class, composer, int.class, int.class);

            Method painterResource = method(StudioNames.cls("painterRes", cl), StudioNames.m("painterRes.m"), int.class, composer, int.class);
            final Class<?> imageType = StudioNames.cls("imageBitmap", cl);
            final Constructor<?> bitmapPainter = StudioNames.cls("bitmapPainter", cl).getDeclaredConstructor(imageType);
            final Constructor<?> imageBitmap = imageType.getDeclaredConstructor(Bitmap.class);
            bitmapPainter.setAccessible(true); imageBitmap.setAccessible(true);
            Xp.hookMethod(painterResource, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (((Integer) p.args[0]) != ICON_ID) return;
                    try {
                        if (painter == null) painter = bitmapPainter.newInstance(imageBitmap.newInstance(DoodleOverlay.toolIcon(app())));
                        p.setResult(painter);
                    } catch (Throwable error) { logOnce("doodles icon unavailable", error); }
                }
                @Override protected void afterHookedMethod(Xp.Param p) {
                    Object r = p.getResult();
                    if (r != null && r != painter) synchronized (painterIds) { painterIds.put(r, (Integer) p.args[0]); }
                }
            });

            Xp.hookMethod(penButton, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    Integer which;
                    synchronized (painterIds) { which = painterIds.get(p.args[1]); }
                    boolean row = fromToolbarRow();
                    if (row) track("stroke" + which, Boolean.TRUE.equals(p.args[4]));
                    if (which == null || which != normalPen() || !doodlesOn() || !row) return;
                    try {
                        Object composer = p.args[6];
                        boolean selected = Boolean.TRUE.equals(readState.invoke(selectedState));
                        if (spacer != null && width != null) spacer.invoke(null, width.invoke(null, emptyModifier, 4f), composer);
                        toolButton.invoke(null, ICON_ID, click(), selected, emptyModifier, composer, 0);
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
                        if (id != 0 && c.getString(id).equals(p.args[0])) { doneClick = p.args[2]; doneSeen = android.os.SystemClock.uptimeMillis(); }
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

    static String uiClass; static Class<?> painterClass;
    static final Map<Object, Integer> painterIds = new java.util.WeakHashMap<Object, Integer>();
    private static int sNormalPen;

    static int normalPen() {
        if (sNormalPen == 0) {
            Context c = app();
            if (c != null) sNormalPen = c.getResources().getIdentifier("brush_normal_bristles", "drawable", PACKAGE);
            if (sNormalPen == 0) { logOnce("pen icon not found, doodles button skipped", null); return -1; }
        }
        return sNormalPen;
    }

    static Object emptyModifier(Class<?> modifier) throws Exception {
        for (Field f : modifier.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object v = f.get(null);
            if (modifier.isInstance(v)) return v;
        }
        throw new NoSuchFieldException("empty Modifier in " + modifier.getName());
    }

    static Object structuralPolicy(Class<?> impl) throws Exception {
        Object any = null;
        for (Field f : impl.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != impl) continue;
            f.setAccessible(true);
            Object v = f.get(null);
            if (v != null && String.valueOf(v).contains("Structural")) return v;
            if (any == null) any = v;
        }
        if (any == null) throw new NoSuchFieldException("equality policy in " + impl.getName());
        return any;
    }

    static boolean fromToolbarRow() {
        String row = StudioNames.get("toolbarLambda"), restart = StudioNames.get("penRestart");
        StackTraceElement[] stack = new Throwable().getStackTrace();
        String caller = null;
        for (int i = 0; i < stack.length && i < 80; i++) {
            String name = stack[i].getClassName();
            if (name.equals(row)) return true;
            if (name.equals(restart)) return false;
            if (caller == null && name.indexOf('.') < 0 && !name.equals(uiClass) && !name.startsWith("$") && !name.startsWith("LSP")) caller = name;
        }
        if (caller != null && painterClass != null) {
            try {
                int painters = 0;
                for (Field f : Class.forName(caller, false, loader).getDeclaredFields()) if (f.getType() == painterClass) painters++;
                return painters < 2;
            } catch (Throwable ignored) { }
        }
        logOnce("toolbar row not found on the stack, doodles button skipped", null);
        return false;
    }

    static long openedAt;
    static void track(String key, boolean selected) {
        Boolean before = nativeSelected.put(key, selected);
        if (!selected || !Boolean.FALSE.equals(before)) { if (selected && before == null) toolOnAt = android.os.SystemClock.uptimeMillis(); return; }
        toolOnAt = android.os.SystemClock.uptimeMillis();
        if (DoodleOverlay.active() && toolOnAt - openedAt > 700) {
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
                return unit(loader);
            }
        });
        return clickProxy;
    }

    static long doneSeen, toolOnAt;
    static void closeNativeTool() {
        Object done = doneClick;
        openedAt = android.os.SystemClock.uptimeMillis();
        if (done == null || !anyNativeToolOn() || doneSeen < toolOnAt) { doneClick = null; return; }
        doneClick = null;
        for (Map.Entry<String, Boolean> e : nativeSelected.entrySet()) e.setValue(Boolean.FALSE);
        try { sam(svfType).invoke(done); } catch (Throwable error) { logOnce("native tool could not be closed", error); }
    }

    static Field rendererCanvas;
    static Method canvasMatrix;

    static void installRendering(ClassLoader cl) throws Exception {
        final Class<?> renderer = StudioNames.cls("renderer", cl), config = StudioNames.cls("config", cl), view = StudioNames.cls("view", cl);
        Class<?> area = StudioNames.cls("area", cl);
        rendererCanvas = fieldOfType(renderer, area);
        canvasMatrix = method(area, StudioNames.m("area.matrix"));
        Xp.hookMethod(method(renderer, StudioNames.m("renderer.picture"), config), new Xp.Callback() {
            @Override protected void afterHookedMethod(Xp.Param p) {
                if (!(p.getResult() instanceof Picture) || !DoodleOverlay.hasShapes(p.thisObject)) return;
                List<Doodle> shapes = DoodleOverlay.snapshot(p.thisObject);
                if (shapes.isEmpty()) return;
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
                } catch (Throwable error) { logOnce("doodles could not be drawn into the image", error); }
            }
        });
        Xp.hookAllConstructors(view, new Xp.Callback() {
            @Override protected void afterHookedMethod(Xp.Param p) {
                canvasView = new WeakReference<View>((View) p.thisObject);
                main.postDelayed(new Runnable() { public void run() {
                    if (!DoodleOverlay.active() || !DoodleOverlay.studioCanvasGone()) return;
                    DoodleOverlay.leave(false);
                    if (android.os.SystemClock.uptimeMillis() - openedAt < 1500) main.post(new Runnable() { public void run() { if (!DoodleOverlay.active()) DoodleOverlay.enter(); } });
                } }, 300);
            }
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
