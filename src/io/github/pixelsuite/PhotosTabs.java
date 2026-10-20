package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 2.0.4-preview6: the Doodles tab in Google Photos' current editor, whose tab row (Auto, Crop, Adjust, Actions,
 * Markup...) is Jetpack Compose. preview5 looked for the older View-based tab bar, which this editor no longer shows.
 *
 * The tabs are a list of "toolbox groupings" handed to two composables. Everything here is found without Photos'
 * renamed (R8) names:
 *  - The grouping and its label classes are recognised by their own toString() text, "ToolboxGrouping(" and
 *    "ToolboxGroupingResourceConstants(" (Kotlin data classes; R8 keeps the text).
 *  - The two composables are found the first time they run: each looks up the string resource
 *    photos_photoeditor_enchilada_landingpage_carousel_toolbox (looked up by name). The caller in the stack at that
 *    moment that takes a java.util.List is the composable. Its class and method are kept in Photos' cache folder
 *    for this Photos version, so later launches hook it from the start. (The very first launch shows the tab the
 *    next time the row redraws.)
 *  - The list argument that holds groupings gets our grouping inserted right after Crop's (the grouping whose label
 *    is photos_photoeditor_enchilada_effects_crop). It has no tools of its own; its label is a resource id no other
 *    string uses, answered with "Doodles" by Resources.getText / getString.
 *  - Our tab is selected when the editor asks our (empty) tool list whether it is empty: that only happens for the
 *    selected tab. Selecting it opens Doodles; another tab closes it. While it stays selected after Done, a tap on
 *    the picture opens Doodles again.
 */
final class PhotosTabs {
    private PhotosTabs() {}

    static int cropLabel, anchor, fakeLabel;
    static Object grouping;
    static Class<?> groupingClass, labelClass;                   // our "Doodles" grouping (one instance, so Compose sees no change)
    static final Entries entries = new Entries();
    static final Set<String> hooked = new HashSet<String>();
    static ClassLoader loader;
    static boolean selected, asked, bodyRan;
    static long lastSearch;
    static final Handler main = new Handler(Looper.getMainLooper());

    /** Our tab's tool list: always empty, and it notices when the editor shows the selected tab's tools. */
    static final class Entries extends AbstractList<Object> {
        public Object get(int i) { throw new IndexOutOfBoundsException(); }
        public int size() { return 0; }
        @Override public boolean isEmpty() { asked = true; return true; }
    }

    static void install(ClassLoader cl, Context app) {
        loader = cl;
        try {
            Resources r = app.getResources(); String pkg = app.getPackageName();
            cropLabel = r.getIdentifier("photos_photoeditor_enchilada_effects_crop", "string", pkg);
            anchor = r.getIdentifier("photos_photoeditor_enchilada_landingpage_carousel_toolbox", "string", pkg);
            if (cropLabel == 0 || anchor == 0) { PhotosEditor.log("tab row resources not found, no Doodles tab"); return; }
            fakeLabel = (cropLabel & 0xffff0000) | 0xfff7;   // the string type's id range, far past its last entry
        } catch (Throwable error) { PhotosEditor.logOnce("tab row unavailable", error); return; }
        Xp.Callback label = new Xp.Callback() {
            protected void beforeHookedMethod(Xp.Param p) {
                if (p.args.length == 0 || !(p.args[0] instanceof Integer)) return;
                int id = (Integer) p.args[0];
                if (id == fakeLabel) { p.setResult("Doodles"); return; }
                if (id == anchor) { bodyRan = true; discover(); }
            }
        };
        int n = 0;
        for (String m : new String[]{"getText", "getString"})
            try { for (Method x : Resources.class.getDeclaredMethods()) if (x.getName().equals(m) && x.getParameterTypes().length == 1 && x.getParameterTypes()[0] == int.class) { Xp.hookMethod(x, label); n++; } }
            catch (Throwable error) { PhotosEditor.logOnce("tab label hook unavailable", error); }
        // Composables found on an earlier launch of this Photos version.
        for (String key : load(app)) hook(key);
        if (n == 0) PhotosEditor.log("tab label hooks unavailable, no Doodles tab");
    }

    /** Called while a composable looks up the tab row's string: the caller taking a List is the composable. */
    static void discover() {
        long now = android.os.SystemClock.uptimeMillis();
        if (hooked.size() >= 2 || now - lastSearch < 1500) return;   // the row's two composables; the search is not repeated every frame
        lastSearch = now;
        try {
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                String c = e.getClassName();
                if (c.startsWith("android.") || c.startsWith("java.") || c.startsWith("dalvik.") || c.startsWith("com.android.")
                        || c.startsWith("io.github.") || c.startsWith("org.lsposed") || c.startsWith("de.robv") || c.contains("LSPHooker")) continue;
                String key = c + "#" + e.getMethodName();
                if (hooked.contains(key)) return;
                Class<?> type; try { type = Class.forName(c, false, loader); } catch (Throwable notApp) { continue; }
                if (!takesList(type, e.getMethodName())) continue;
                if (hook(key)) save(key);   // the tab shows when the row next redraws
                return;
            }
        } catch (Throwable error) { PhotosEditor.logOnce("tab row search failed", error); }
    }

    static boolean takesList(Class<?> type, String name) {
        for (Method m : type.getDeclaredMethods()) if (m.getName().equals(name)) for (Class<?> p : m.getParameterTypes()) if (p == List.class) return true;
        return false;
    }

    static boolean hook(String key) {
        if (hooked.contains(key)) return true;
        try {
            int at = key.indexOf('#');
            Class<?> type = Class.forName(key.substring(0, at), false, loader); String name = key.substring(at + 1);
            int n = 0;
            for (Method m : type.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                boolean list = false; for (Class<?> p : m.getParameterTypes()) if (p == List.class) list = true;
                if (!list) continue;
                Xp.hookMethod(m, new Xp.Callback() {
                    protected void beforeHookedMethod(Xp.Param p) {
                        try {
                            for (int i = 0; i < p.args.length; i++) {
                                if (!(p.args[i] instanceof List)) continue;
                                List<?> list = (List<?>) p.args[i];
                                // preview11: Compose re-runs the row with the list it was given last time, which already
                                // holds our tab (preview10 log: choosing tabs was noticed only after something else
                                // rebuilt the row, like a crop). Such a run is ours too.
                                boolean has = grouping != null && list.contains(grouping);
                                List<?> with = has ? null : insert(list);
                                if (with != null) p.args[i] = with;
                                if (has || with != null) { asked = false; bodyRan = false; Xp.setAdditionalInstanceField(p, "pixelsuite_tabs", Boolean.TRUE); }
                            }
                        } catch (Throwable error) { PhotosEditor.logOnce("Doodles tab could not be added", error); }
                    }
                    protected void afterHookedMethod(Xp.Param p) {
                        if (!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(p, "pixelsuite_tabs"))) return;
                        // Compose skips a composable whose inputs did not change; only a run that drew the row counts.
                        if (!bodyRan) return;
                        final boolean now = asked;
                        if (now == selected) return;
                        selected = now;
                        main.post(new Runnable() { public void run() { if (now) open(); else if (DoodleOverlay.active()) DoodleOverlay.leave(false); } });
                    }
                });
                n++;
            }
            if (n > 0) hooked.add(key);
            return n > 0;
        } catch (Throwable error) { PhotosEditor.logOnce("tab row hook failed for " + key, error); return false; }
    }

    /** The list with our grouping after Crop's, or null when this list is not the tab row or already has it. */
    static List<?> insert(List<?> list) throws Exception {
        if (list.isEmpty() || list instanceof Entries) return null;
        int crop = -1;
        for (int i = 0; i < list.size(); i++) {
            Object g = list.get(i);
            if (g == null) return null;
            if (g == grouping) return null;
            if (g.getClass() != groupingClass) {
                if (groupingClass != null || !String.valueOf(g).startsWith("ToolboxGrouping(")) return null;
                groupingClass = g.getClass();   // recognised once by its text, then by its class
            }
            if (crop < 0 && labelOf(g) == cropLabel) crop = i;
        }
        if (crop < 0) return null;
        Object ours = ours(list.get(crop));
        if (ours == null) return null;
        List<Object> out = new ArrayList<Object>(list);
        out.add(crop + 1, ours);
        return out;
    }

    /** The label (string resource) of a grouping: the int field of its ToolboxGroupingResourceConstants. */
    static int labelOf(Object grouping) throws Exception {
        for (Field f : grouping.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true); Object v = f.get(grouping);
            if (v != null && (v.getClass() == labelClass || (labelClass == null && String.valueOf(v).startsWith("ToolboxGroupingResourceConstants(")))) {
                labelClass = v.getClass();
                for (Field g : v.getClass().getDeclaredFields()) if (g.getType() == int.class && !java.lang.reflect.Modifier.isStatic(g.getModifiers())) { g.setAccessible(true); return g.getInt(v); }
            }
        }
        return 0;
    }

    /** Our grouping, built like Crop's: no tools, our label, Crop's other values (its log tag and flags). */
    static Object ours(Object crop) throws Exception {
        if (grouping != null) return grouping;
        Class<?> type = crop.getClass();
        Object res = null;
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true); Object v = f.get(crop);
            if (v != null && String.valueOf(v).startsWith("ToolboxGroupingResourceConstants(")) res = v;
        }
        if (res == null) return null;
        Object label = null;
        for (Constructor<?> c : res.getClass().getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == int.class) { c.setAccessible(true); label = c.newInstance(fakeLabel, null); break; }
        }
        if (label == null) return null;
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length != 4 || p[0] != List.class || p[1] != res.getClass()) continue;
            Object third = null; boolean fourth = false;
            for (Field f : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == p[2]) third = f.get(crop);
            }
            c.setAccessible(true);
            grouping = c.newInstance(entries, label, third, fourth);
            return grouping;
        }
        return null;
    }

    static java.lang.ref.WeakReference<Activity> editor = new java.lang.ref.WeakReference<Activity>(null);

    /** The editor closed (PhotosEditor.end): our tab is no longer the selected one. */
    static void reset() { selected = false; asked = false; editor = new java.lang.ref.WeakReference<Activity>(null); }

    static void open() { open(0); }
    /** Tries again for a moment while the editor is still placing the picture (just after the tab was chosen). */
    static void open(final int attempt) {
        Activity a = editor.get();
        if (a == null || a.isFinishing() || DoodleOverlay.active() || !selected) return;
        if (PhotosEditor.openFrom(a, null, null, -1) || attempt >= 12) return;
        main.postDelayed(new Runnable() { public void run() { open(attempt + 1); } }, 120);
    }

    /**
     * While our tab stays selected after Done, a tap opens Doodles again: on the picture or in the empty band under it
     * at once, or on the Doodles tab itself (2.0.4, owner). Only a tap on the tab row waits a moment (150 ms), and
     * only if our tab is still the selected one then, so a tap on another tab (Crop, Adjust...) just switches tabs.
     */
    static float downX, downY; static long downAt;
    static void touch(Activity a, MotionEvent e) {
        if (editor.get() != a) editor = new java.lang.ref.WeakReference<Activity>(a);   // every touch event: no new reference each time
        if (!selected || !PhotosEditor.session || DoodleOverlay.active()) return;   // preview14: only inside the editor
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = e.getRawX(); downY = e.getRawY(); downAt = e.getEventTime(); return; }
        if (e.getActionMasked() != MotionEvent.ACTION_UP || e.getEventTime() - downAt > 400 || Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > DoodleOverlay.dpOf(a, 12)) return;
        DoodleOverlay.Bridge.Space s = PhotosEditor.space(a, a.getWindow().getDecorView());
        if (s == null) return;
        float x = e.getRawX(), y = e.getRawY();
        if (y > bottomLimit(a)) return;
        boolean tabRow = y >= tabRowTop(a);
        if (s.image.contains(x, y) || (y > s.image.bottom && !tabRow)) main.post(new Runnable() { public void run() { open(); } });
        else if (tabRow) main.postDelayed(new Runnable() { public void run() { open(); } }, 150);
    }

    /** The top of the editor's tab row on the screen: the bottom of the Doodles menu's band (PhotosEditor slots). */
    static float tabRowTop(Activity a) {
        View d = a.getWindow().getDecorView();
        int[] at = new int[2]; d.getLocationOnScreen(at);
        return at[1] + 0.8875f * d.getHeight();
    }

    /** Where taps stop counting: the top of the navigation bar (no reopening from the gesture handle). */
    static float bottomLimit(Activity a) {
        View d = a.getWindow().getDecorView();
        int[] at = new int[2]; d.getLocationOnScreen(at);
        float bottom = at[1] + d.getHeight();
        try {
            android.view.WindowInsets in = d.getRootWindowInsets();
            if (in != null) bottom -= in.getStableInsetBottom();
        } catch (Throwable ignored) { }
        return bottom;
    }

    // ------------------------------------------------------------------ remembered per Photos version

    static File file(Context app) {
        long version = StudioNames.versionCode(app.getApplicationInfo());
        return new File(app.getCacheDir(), "pixelsuite-photos-tabs-" + version);
    }
    static List<String> load(Context app) {
        List<String> out = new ArrayList<String>();
        try {
            File f = file(app); if (!f.isFile()) return out;
            byte[] b = new byte[(int) f.length()]; FileInputStream in = new FileInputStream(f);
            try { int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; } } finally { in.close(); }
            for (String s : new String(b, "UTF-8").split("\n")) if (s.contains("#")) out.add(s.trim());
        } catch (Throwable ignored) { }
        return out;
    }
    static void save(String key) {
        try {
            Context app = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app == null) return;
            File f = file(app);
            File dir = f.getParentFile(); File[] old = dir == null ? null : dir.listFiles();
            if (old != null) for (File o : old) if (o.getName().startsWith("pixelsuite-photos-tabs-") && !o.getName().equals(f.getName())) o.delete();
            List<String> keys = load(app); if (!keys.contains(key)) keys.add(key);
            StringBuilder b = new StringBuilder(); for (String k : keys) b.append(k).append('\n');
            FileOutputStream out = new FileOutputStream(f); try { out.write(b.toString().getBytes("UTF-8")); } finally { out.close(); }
        } catch (Throwable error) { PhotosEditor.logOnce("tab row not remembered", error); }
    }
}
