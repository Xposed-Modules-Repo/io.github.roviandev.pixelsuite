package io.github.pixelsuite;

import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2.0.8-preview16 (owner's video: the editor's top bar blinked while a crop corner was dragged). Pixel Studio hides
 * its top bar while a finger is on the picture (EditUiState.showTopBar = false until the finger lifts) so it doesn't
 * cover a drawing. A crop drag starts on the picture too, then the crop box takes the gesture and the picture is told
 * the touch ended, so the bar went away and came back at once. On the crop screen nothing is drawn, so the picture's
 * touch is left out there; every other screen keeps the editor's own behaviour.
 */
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
                                // preview40 (owner: after cropping, the ✓ and X did nothing): skipping the editor's touch
                                // handler also skipped its "the finger lifted" step, which the ✓ and X wait for. It runs
                                // again; only its hiding of the top bar is undone (keepBar, in the state's copy).
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

    /** The editor's UI state object (the one holding its page), or null. */
    static Object stateOf(Object viewModel) {
        Class<?> k = viewModel.getClass();
        List<Field> path; synchronized (pagePath) { path = pagePath.get(k); }
        if (path == null) { page(viewModel); synchronized (pagePath) { path = pagePath.get(k); } }
        if (path == null) return null;
        Object o = viewModel;
        try { for (Field f : path) { if (o == null) return null; o = f.get(o); } } catch (Throwable t) { return null; }
        return o;
    }

    /**
     * The state's copy method (static, returns the state, takes it first, many parameters; its last two ints are the
     * "keep" mask). While the crop screen handles a touch, a copy that changes only the top-bar flag (mask ~8) keeps
     * the bar shown.
     */
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

    /** preview24: the editor's view model last seen (any touch on the picture), for the page it is on now. */
    static volatile java.lang.ref.WeakReference<Object> last = new java.lang.ref.WeakReference<Object>(null);
    static String currentPage() { Object vm = last.get(); return vm == null ? null : page(vm); }

    /** The editor's page (EditPageState name, e.g. CROP_TOOL), read from its UI state, or null. */
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

    /** The field of an enum type that has a CROP_TOOL constant (EditUiState.editPageState), or null. */
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
