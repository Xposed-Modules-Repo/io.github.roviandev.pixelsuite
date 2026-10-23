package io.github.pixelsuite;

import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TopBarKeep {
    private TopBarKeep() {}

    private static final Map<Class<?>, List<Field>> pagePath = new HashMap<Class<?>, List<Field>>();
    private static final Map<Class<?>, Field> pageField = new HashMap<Class<?>, Field>();

    static void install(ClassLoader cl) {
        try {
            String names = StudioNames.get("touch"), m = StudioNames.get("touch.m");
            if (names == null || m == null) { ScreenshotEditor.logOnce("top bar: not known for this Pixel Studio version, stock kept", null); return; }
            int n = 0;
            for (String name : names.split("\\|")) {
                try {
                    Method touch = Class.forName(name, false, cl).getDeclaredMethod(m, MotionEvent.class);
                    Xp.hookMethod(touch, new Xp.Callback() {
                        @Override protected void beforeHookedMethod(Xp.Param p) {
                            try {
                                MotionEvent e = (MotionEvent) p.args[0];
                                last = new java.lang.ref.WeakReference<Object>(p.thisObject);
                                if (e == null || !"CROP_TOOL".equals(page(p.thisObject))) return;
                                watchCopies(stateOf(p.thisObject));
                                CROP.set(Boolean.TRUE);
                            } catch (Throwable error) { ScreenshotEditor.logOnce("top bar keep failed, stock kept", error); }
                        }
                        @Override protected void afterHookedMethod(Xp.Param p) { CROP.set(null); }
                    });
                    n++;
                } catch (Throwable missing) { ScreenshotEditor.logOnce("top bar: " + name + " not found", missing); }
            }
            Module.log("screenshot-editor: top bar keep: " + n + " hooks");
        } catch (Throwable error) { ScreenshotEditor.logOnce("top bar keep unavailable", error); }
    }

    static final ThreadLocal<Boolean> CROP = new ThreadLocal<Boolean>();
    private static Class<?> watched;

    static Object stateOf(Object viewModel) {
        Class<?> k = viewModel.getClass();
        List<Field> path; synchronized (pagePath) { path = pagePath.get(k); }
        if (path == null) { page(viewModel); synchronized (pagePath) { path = pagePath.get(k); } }
        if (path == null) return null;
        Object o = viewModel;
        try { for (Field f : path) { if (o == null) return null; o = f.get(o); } } catch (Throwable t) { return null; }
        return o;
    }

    static synchronized void watchCopies(Object state) {
        if (state == null || watched == state.getClass()) return;
        Class<?> k = state.getClass(); watched = k;
        int n = 0;
        for (Method m : k.getDeclaredMethods()) {
            Class<?>[] t = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != k || t.length < 20 || t[0] != k) continue;
            if (t[4] != boolean.class || t[t.length - 2] != int.class) continue;
            Xp.hookMethod(m, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    if (!Boolean.TRUE.equals(CROP.get())) return;
                    Object mask = p.args[p.args.length - 2];
                    if (mask instanceof Integer && (Integer) mask == ~8) p.args[4] = Boolean.TRUE;
                }
            });
            n++;
        }
        Module.log("screenshot-editor: top bar keep: " + n + " state copies watched");
    }

    static volatile java.lang.ref.WeakReference<Object> last = new java.lang.ref.WeakReference<Object>(null);
    static String currentPage() { Object vm = last.get(); return vm == null ? null : page(vm); }

    static String page(Object viewModel) {
        Class<?> k = viewModel.getClass();
        List<Field> path;
        synchronized (pagePath) {
            path = pagePath.get(k);
            if (path == null && !pagePath.containsKey(k)) { path = find(viewModel, new ArrayList<Field>(), 0); pagePath.put(k, path); }
        }
        if (path == null) return null;
        Object o = viewModel;
        try { for (Field f : path) { if (o == null) return null; o = f.get(o); } } catch (Throwable t) { return null; }
        if (o == null) return null;
        Field pf = pageOf(o.getClass());
        if (pf == null) return null;
        try { Object v = pf.get(o); return v instanceof Enum ? ((Enum<?>) v).name() : null; } catch (Throwable t) { return null; }
    }

    private static List<Field> find(Object o, List<Field> path, int depth) {
        if (o == null || depth > 3) return null;
        if (depth > 0 && pageOf(o.getClass()) != null) return path;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("java.") || c.getName().startsWith("android.")) break;
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                Object v;
                try { f.setAccessible(true); v = f.get(o); } catch (Throwable t) { continue; }
                if (v == null || v instanceof android.content.Context || v instanceof android.view.View) continue;
                String vn = v.getClass().getName();
                if (vn.startsWith("java.") && !vn.startsWith("java.util.concurrent.atomic.")) continue;
                if (vn.startsWith("android.") || vn.startsWith("kotlin.")) continue;
                List<Field> next = new ArrayList<Field>(path); next.add(f);
                List<Field> r = find(v, next, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static Field pageOf(Class<?> k) {
        synchronized (pageField) {
            if (pageField.containsKey(k)) return pageField.get(k);
            Field found = null;
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().isEnum()) continue;
                for (Object c : f.getType().getEnumConstants()) if ("CROP_TOOL".equals(((Enum<?>) c).name())) { found = f; break; }
                if (found != null) break;
            }
            if (found != null) found.setAccessible(true);
            pageField.put(k, found);
            return found;
        }
    }
}
