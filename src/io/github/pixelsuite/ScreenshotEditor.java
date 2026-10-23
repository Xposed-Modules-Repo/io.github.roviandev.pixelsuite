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

/**
 * Pixel's screenshot editor: Pixel Studio's ExternalEditActivity
 * (com.google.android.apps.pixel.creativeassistant). The app is Compose and R8-renamed, and the names
 * change with every update: they come from {@link StudioNames} (checked tables for 192285 and 192717,
 * otherwise found by reading the app itself). Comments below use the 192285 names. Field and member
 * details are found at run time (by type, by probing, by the state objects involved) rather than by
 * name, so an update that only renames things keeps working. Anything missing is logged once and that
 * feature simply stays off; the user's settings are never touched.
 *
 * Free crop: the crop box's pointer handler (jfs) gets the minimum size as a float, 100dp in
 * px; the hook passes 1px instead. Its handle hit test (hsj case 20) used 20% of the box's WIDTH
 * as the touch radius for every edge, which shrinks to nothing on a thin crop, so that one case
 * is answered here with at least 24dp per edge and the nearer edge winning.
 *
 * Doodles: a toolbar button placed right after the pen, by calling the editor's own tool-button
 * composable (jix.L) once more, right after the pen's (jix.k) in the same composition. Its icon
 * is a sentinel drawable id that painterResource (dee.J) turns into our own bitmap. The shapes
 * live in image coordinates and are drawn into the editor's own render picture (irq.a), the one
 * used both on screen and for Save/Share/Copy, so they are saved like the pen's strokes.
 */
final class ScreenshotEditor {
    static final String PACKAGE = "com.google.android.apps.pixel.creativeassistant";
    static final int ICON_ID = 0x7f7ffd01;                 // no resource has this id: our painter
    private static final Feature.Cached sFreeCrop = new Feature.Cached(Feature.SCREENSHOT_FREE_CROP);
    private static Boolean sDoodles;                        // read once per process (see doodlesOn)
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
        CropLook.install(cl);
        TopBarKeep.install(cl);
        installDoodles(cl);
        installUndoRedo(cl);
        // preview14 (owner): with Super Dark-Mode on, the tool bar is black with the Doodles menu's soft rim (DarkChips).
        try {
            Xp.hookMethod(android.app.Activity.class.getDeclaredMethod("onResume"), new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { if (SuperDark.inEffectNow()) DarkChips.on("Pixel Studio"); }
            });
        } catch (Throwable error) { logOnce("dark tool bar unavailable", error); }
        // build 191 (owner's video: the editor's frame white for a quarter of a second before its grey): the editor's
        // window in the folders' frost from its first frame (WindowFrost), so the white is never shown.
        try {
            Xp.hookMethod(android.app.Activity.class.getDeclaredMethod("onCreate", android.os.Bundle.class), new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    // build 192 (log of 191: never matched): ExternalEditActivity is an alias in the manifest; the class
                    // is com.google.android.apps.pixel.merlin.tiktok.gandalf.GandalfActivity (the Pixel Studio APK).
                    String name = p.thisObject.getClass().getName();
                    Activity a = (Activity) p.thisObject;
                    boolean edit = false; try { edit = a.getIntent() != null && android.content.Intent.ACTION_EDIT.equals(a.getIntent().getAction()); } catch (Throwable ignored) { }
                    if (name.endsWith("GandalfActivity") || name.endsWith("ExternalEditActivity") || edit) {
                        // build 207 (owner: under Super Dark-Mode the editor came out see-through around the picture):
                        // the frosted editor is for light and normal dark theme; under Super Dark it is the stock black
                        // editor again (its own black surface, Super Dark's black buttons), as before build 191.
                        boolean superDark = SuperDark.inEffect(a);
                        if (!superDark) WindowFrost.frost(a, "screenshot editor");
                        // build 194 (owner's slow-motion video: frost, then white, then the editor's grey): the editor's first
                        // composition paints a white full-window surface before its grey; skipped in this window.
                        // build 196: the grey too, and in dark theme as well (its dark grey over the dark frost was the same
                        // shift), whenever the window was frosted (every style but Stock, and Blur Off in light theme).
                        // build 197: by activity, so its Pop-Up Window counts too (ComposeFrost.editor).
                        // build 199: every style (a stock editor's scrim and grey go too, so it stays white).
                        ComposeFrost.editor(a, SurfaceStyle.restyled(a) && !superDark);
                    }
                }
            });
        } catch (Throwable error) { logOnce("editor window frost unavailable", error); }
        // build 194: Pixel Studio's dialogs ("Delete screenshot?", "Saving video...": Compose) frosted under Frosted Glass.
        ComposeFrost.install("Pixel Studio", null, true);   // build 195: its dark-only dialogs follow a light phone
    }

    // ------------------------------------------------------------------ undo / redo

    private static WeakReference<Object> sHistoryOwner = new WeakReference<Object>(null);   // khq
    private static Field sHistoryState;
    private static Method sStateGet, sStateSet, sHistoryRefresh;
    private static Constructor<?> sHistoryMake;
    private static Field sCanUndo, sCanRedo, sModified;

    /**
     * Makes the editor's own Undo and Redo buttons cover the doodles. The buttons read a
     * state (khq.c, UndoRedoState) that is republished by khq.f(); after it runs the doodles'
     * state is added to it, and the two click lambdas given to the toolbar (jix.e) are wrapped
     * so a click undoes/redoes the latest doodle change first, then the editor's own history.
     */
    static void installUndoRedo(ClassLoader cl) {
        try {
            final Class<?> khq = StudioNames.cls("history", cl), jqx = StudioNames.cls("undoState", cl), state = StudioNames.cls("state", cl);
            final Class<?> svf = StudioNames.cls("fn0", cl), jix = StudioNames.cls("ui", cl), composer = StudioNames.cls("composer", cl);
            sStateGet = getter(state);
            sStateSet = setter(state);
            sHistoryMake = jqx.getDeclaredConstructor(boolean.class, boolean.class, boolean.class);
            sHistoryMake.setAccessible(true);
            // canUndo / canRedo / modified: whichever boolean field holds the one flag set
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
                        boolean here = doodlesHere();
                        boolean canUndo = Boolean.TRUE.equals(p.args[0]) || (here && DoodleOverlay.canUndo());
                        boolean canRedo = Boolean.TRUE.equals(p.args[1]) || (here && DoodleOverlay.canRedo());
                        // 2.0.8-preview6 (owner: the greyed-out Undo / Redo were hard to see): they are always drawn at
                        // full strength, like Share; with nothing to undo / redo a tap does nothing.
                        // preview11 (owner, the outline of preview9 reverted): Undo is faded until there is something
                        // to undo; Redo is always drawn at full strength. preview12 (owner: "faded, but not this much"):
                        // the editor's own disabled look (38%) is too faint, so the button stays on and its arrow is
                        // drawn at 55% instead (fadedIcons).
                        p.args[0] = Boolean.TRUE; p.args[1] = Boolean.TRUE;
                        undoFaded = !canUndo;
                        p.args[2] = wrapClick(function, p.args[2], true, unit, canUndo);
                        p.args[3] = wrapClick(function, p.args[3], false, unit, canRedo);
                    } catch (Throwable error) { logOnce("undo/redo wrap failed", error); }
                }
            });
            fadedIcons(cl);
            boldIcons(cl);
            Module.log("screenshot-editor: undo/redo hooks installed");
        } catch (Throwable error) {
            logOnce("undo/redo for doodles unavailable in this Pixel Studio version", error);
        }
    }

    /**
     * preview12: with nothing to undo, the editor's own undo arrow (gs_undo) is swapped in painterResource for the same
     * arrow at 55% opacity; the editor tints it with its content colour as usual, so it follows every theme. The bar
     * composes again whenever its state changes (its click handlers are new each time), so the arrow follows at once.
     */
    private static volatile boolean undoFaded;
    private static int undoIcon = -1;
    private static Object fadedPainter;
    private static void fadedIcons(ClassLoader cl) {
        try {
            Method painterResource = method(StudioNames.cls("painterRes", cl), StudioNames.m("painterRes.m"), int.class, StudioNames.cls("composer", cl), int.class);
            final Class<?> imageType = StudioNames.cls("imageBitmap", cl);
            final Constructor<?> bitmapPainter = StudioNames.cls("bitmapPainter", cl).getDeclaredConstructor(imageType);
            final Constructor<?> imageBitmap = imageType.getDeclaredConstructor(Bitmap.class);
            bitmapPainter.setAccessible(true); imageBitmap.setAccessible(true);
            Xp.hookMethod(painterResource, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (!undoFaded) return;
                        int id = (Integer) p.args[0];
                        if (undoIcon < 0) {
                            Context c = app(); if (c == null) return;
                            undoIcon = c.getResources().getIdentifier("gs_undo_rond100_vd_theme_24", "drawable", c.getPackageName());
                            if (undoIcon == 0) logOnce("undo icon not found, the editor's own faint look stays", null);
                        }
                        if (id == 0 || id != undoIcon) return;
                        if (fadedPainter == null) {
                            Bitmap b = faded(app(), id, 120);   // 47% (preview16, owner: 8% more faded than 55%)
                            if (b == null) return;
                            fadedPainter = bitmapPainter.newInstance(imageBitmap.newInstance(b));
                        }
                        p.setResult(fadedPainter);
                    } catch (Throwable error) { logOnce("faded undo icon unavailable", error); }
                }
            });
        } catch (Throwable error) { logOnce("faded undo icon unavailable", error); }
    }
    /**
     * preview39 (owner's video, measured: the crop icon got heavier a moment after the editor opened): the editor draws
     * the selected tool's icon in a bold weight (gs_crop_wght700…) and the others regular (gs_crop_rond100…). In the
     * frosted editor the selection circle is left out (ComposeFrost.editorPill), so the bold icon is the only thing
     * that changes; it takes the regular weight there, so nothing changes. Other themes keep the bold icon.
     */
    private static final Map<Integer, Integer> regularIcons = new java.util.HashMap<Integer, Integer>();
    private static void boldIcons(ClassLoader cl) {
        try {
            Method painterResource = method(StudioNames.cls("painterRes", cl), StudioNames.m("painterRes.m"), int.class, StudioNames.cls("composer", cl), int.class);
            Xp.hookMethod(painterResource, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        int id = (Integer) p.args[0];
                        Context c = app(); if (c == null || !ComposeFrost.frostedPanels(c)) return;
                        Integer regular;
                        synchronized (regularIcons) { regular = regularIcons.get(id); }
                        if (regular == null) {
                            regular = 0;
                            try {
                                String name = c.getResources().getResourceEntryName(id);
                                int at = name.indexOf("wght700");
                                if (at > 0) regular = c.getResources().getIdentifier(name.substring(0, at) + name.substring(at + 7), "drawable", c.getPackageName());
                            } catch (Throwable ignored) { }
                            synchronized (regularIcons) { regularIcons.put(id, regular); }
                        }
                        if (regular != 0) p.args[0] = regular;
                    } catch (Throwable error) { logOnce("regular tool icons unavailable", error); }
                }
            });
        } catch (Throwable error) { logOnce("regular tool icons unavailable", error); }
    }

    /** The editor's icon drawn at the given opacity (0-255), white, for the editor to tint. */
    static Bitmap faded(Context c, int id, int alpha) {
        if (c == null) return null;
        android.graphics.drawable.Drawable d = c.getDrawable(id);
        if (d == null) return null;
        int size = Math.round(24 * c.getResources().getDisplayMetrics().density);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d = d.mutate(); d.setBounds(0, 0, size, size); d.setTint(0xffffffff); d.setAlpha(alpha);
        d.draw(new Canvas(b));
        return b;
    }

    private static Object wrapClick(Class<?> function, final Object original, final boolean undo, final Object unit, final boolean can) {
        if (original == null) return null;
        return Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                if (m.getDeclaringClass() != Object.class && m.getParameterTypes().length == 0) {
                    if (!can) return unit;   // nothing to undo / redo: the always-shown button does nothing
                    if (doodlesHere() && (undo ? DoodleOverlay.undo() : DoodleOverlay.redo())) return unit;
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

    /**
     * 2.0.8-preview15 (owner: Undo looked on for a fresh screenshot): the doodles' history is the one of the last picture
     * Doodles was opened on. A new screenshot (Doodles not opened yet) still pointed at the previous picture's history,
     * so Undo showed as available. It only counts now while that picture is the one on the screen.
     */
    static boolean doodlesHere() {
        try {
            Object r = DoodleOverlay.lastRenderer.get();
            View v = canvasView.get();
            if (r == null || v == null || !v.isAttachedToWindow()) return false;
            return DoodleOverlay.Bridge.renderer(v) == r;
        } catch (Throwable error) { return false; }
    }

    /** Adds the doodles' undo/redo/modified flags to the editor's published state. */
    static void publishDoodleHistory(Object owner) {
        try {
            if (sHistoryState == null) sHistoryState = historyField(owner);
            Object state = sHistoryState == null ? null : sHistoryState.get(owner);
            if (state == null || sStateSet == null || !sStateGet.getDeclaringClass().isInstance(state)) return;
            if (!doodlesHere()) return;
            boolean undo = DoodleOverlay.canUndo(), redo = DoodleOverlay.canRedo(), any = DoodleOverlay.hasShapes();
            if (!undo && !redo && !any) return;
            Object now = sStateGet.invoke(state);
            sStateSet.invoke(state, sHistoryMake.newInstance(
                    sCanUndo.getBoolean(now) || undo, sCanRedo.getBoolean(now) || redo, sModified.getBoolean(now) || any));
        } catch (Throwable error) { logOnce("undo/redo state not published", error); }
    }

    /** The history's field holding the published UndoRedoState (khq.c): found by what it holds. */
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

    /** Called when the doodles changed: the editor republishes its undo state, and we add ours. */
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

    /** Fixed for the life of the editor process: the toolbar must compose the same way each time. */
    static boolean doodlesOn() {
        if (sDoodles == null) {
            if (app() == null) return true;   // too early to read; ask again on the next composition
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

    // Members found by what they are rather than by their (renamed) names.

    /** A Kotlin function interface's one method (svf.a, svq.a). */
    static Method sam(Class<?> iface) throws NoSuchMethodException {
        for (Method m : iface.getMethods()) if (m.getDeclaringClass() != Object.class && !m.isDefault() && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) return m;
        throw new NoSuchMethodException("no method in " + iface.getName());
    }
    /** Compose State.getValue: no arguments, returns Object. */
    static Method getter(Class<?> state) throws NoSuchMethodException {
        for (Method m : state.getMethods()) if (m.getParameterTypes().length == 0 && m.getReturnType() == Object.class && m.getDeclaringClass() != Object.class) return m;
        throw new NoSuchMethodException("state getter in " + state.getName());
    }
    /** MutableState.setValue: one Object, returns nothing. */
    static Method setter(Class<?> state) throws NoSuchMethodException {
        for (Method m : state.getMethods()) { Class<?>[] t = m.getParameterTypes(); if (t.length == 1 && t[0] == Object.class && m.getReturnType() == void.class) return m; }
        throw new NoSuchMethodException("state setter in " + state.getName());
    }
    /** kotlin.Unit's instance (ssh.a). */
    static Object unit(ClassLoader cl) throws Exception {
        Class<?> u = StudioNames.cls("unit", cl);
        for (Field f : u.getDeclaredFields()) if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == u) { f.setAccessible(true); return f.get(null); }
        throw new NoSuchFieldException("kotlin.Unit instance");
    }
    /** The only instance field of that type. */
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
    /** The boolean field of a (canUndo, canRedo, modified) value that comes out true when only that flag is set. */
    private static Field probeFlag(Class<?> type, boolean a, boolean b, boolean c) throws Exception {
        Object v = sHistoryMake.newInstance(a, b, c);
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != boolean.class) continue;
            f.setAccessible(true);
            if (f.getBoolean(v)) return f;
        }
        throw new NoSuchFieldException("undo flag in " + type.getName());
    }
    /** Static fields of an obfuscated enum, by their kept names (LEFT, TOP, ...). */
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

    // ------------------------------------------------------------------ free crop

    static void installFreeCrop(ClassLoader cl) {
        try {
            final Class<?> handler = StudioNames.cls("crop", cl);
            final Class<?> rect = StudioNames.cls("rect", cl), state = StudioNames.cls("state", cl), svq = StudioNames.cls("fn1", cl);
            Constructor<?> ctor = handler.getDeclaredConstructor(state, state, rect, float.class, svq);
            Xp.hookMethod(ctor, new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    // (crop rect state, dragged handle state): the hit test below is recognised by holding exactly these
                    remember(sRectStates, p.args[0]);
                    remember(sHandleStates, p.args[1]);
                    sCropBounds = p.args[2];   // the box's limits (the picture), in the same units as the box
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

    private static volatile Object sCropBounds;
    private static volatile Field[] sLtrb;
    private static volatile Method sRectGet;

    /**
     * 2.0.8-preview8 (owner's video: with Doodles open, dragging a crop corner sometimes drew an arrow instead): the
     * crop box as it is on screen, mapped onto {@code image} (the picture's rect in the Doodles overlay), or null when
     * unknown. The box and its limits share units, and the limits are the picture, so the box maps by proportion.
     */
    static android.graphics.RectF cropFrame(android.graphics.RectF image) {
        try {
            Field[] e = sLtrb; Method get = sRectGet; Object bounds = sCropBounds;
            if (e == null || get == null || bounds == null || image == null || image.isEmpty()) return null;
            Object state = null;
            synchronized (sRectStates) { for (int i = sRectStates.size() - 1; i >= 0 && state == null; i--) state = sRectStates.get(i).get(); }
            if (state == null) return null;
            Object r = get.invoke(state);
            if (r == null || r.getClass() != bounds.getClass()) return null;
            float bl = e[0].getFloat(bounds), bt = e[1].getFloat(bounds), br = e[2].getFloat(bounds), bb = e[3].getFloat(bounds);
            if (br - bl <= 0 || bb - bt <= 0) return null;
            float kx = image.width() / (br - bl), ky = image.height() / (bb - bt);
            return new android.graphics.RectF(image.left + (e[0].getFloat(r) - bl) * kx, image.top + (e[1].getFloat(r) - bt) * ky,
                    image.left + (e[2].getFloat(r) - bl) * kx, image.top + (e[3].getFloat(r) - bt) * ky);
        } catch (Throwable t) { return null; }
    }

    /** CropLook: the crop box's state, the box and its limits (the picture), in the box's px. */
    static boolean isCropState(Object o) { return holds(sRectStates, o); }
    static android.graphics.RectF cropRect(Object state) {
        try {
            Field[] e = sLtrb; Method get = sRectGet;
            if (e == null || get == null || state == null) return null;
            return ltrb(get.invoke(state));
        } catch (Throwable t) { return null; }
    }
    static android.graphics.RectF cropBounds() { try { return ltrb(sCropBounds); } catch (Throwable t) { return null; } }
    private static android.graphics.RectF ltrb(Object r) throws Exception {
        Field[] e = sLtrb;
        if (r == null || e == null || !e[0].getDeclaringClass().isInstance(r)) return null;
        return new android.graphics.RectF(e[0].getFloat(r), e[1].getFloat(r), e[2].getFloat(r), e[3].getFloat(r));
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

    /**
     * The crop handle hit test (hsj case 20 in 192285, hyx case 14 in 192717): a lambda built by the crop
     * handler with its two states. It is recognised by holding those very state objects, not by its case
     * number or field names, which both moved between builds.
     */
    static void installCropHandles(ClassLoader cl, Class<?> rectType, Class<?> stateType) throws Exception {
        final Class<?> lambda = StudioNames.cls("cropLambda", cl);
        final List<Field> slots = new java.util.ArrayList<Field>();
        for (Field f : lambda.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true); slots.add(f);
        }
        final Method get = getter(stateType), set = setter(stateType);
        // left/top/right/bottom: build a rect (1, 2, 3, 4) and see which field holds which
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
        sLtrb = ltrb; sRectGet = get;   // for cropFrame
        final Map<String, Object> edges = enumByName(StudioNames.cls("edges", cl));
        final Object[] handle = new Object[9];   // the order CropHit.pick answers in
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

    // ------------------------------------------------------------------ doodles: toolbar

    static Class<?> svfType, stateType;
    static Method penButton, toolButton, spacer, width, readState, writeState;
    static Object emptyModifier, selectedState, clickProxy, painter;
    static final Map<String, Boolean> nativeSelected = new HashMap<String, Boolean>();
    static Object doneClick;
    static WeakReference<View> canvasView = new WeakReference<View>(null);
    static float lastDownRawY = -1, lastDownRawX = -1;
    static long lastDownAt;
    /** Where the Doodles button was tapped (the tool bar's row), -1 until it is. */
    static float doodlesButtonX = -1, doodlesButtonY = -1;

    /**
     * preview23 (owner's video of preview21: tapping another tool still left Doodles open): a tap on the tool bar's row
     * (same height as the Doodles button, away from it, not on the Doodles menu) closes Doodles; the tool's own tap goes
     * on to Pixel Studio as usual, so that tool opens. Read from the touch itself, so it does not depend on telling
     * which tool turned on.
     */
    static void toolbarTap(MotionEvent e) {
        try {
            if (doodlesButtonY < 0 || !DoodleOverlay.active()) return;
            Context c = app(); if (c == null) return;
            float d = c.getResources().getDisplayMetrics().density;
            float x = e.getRawX(), y = e.getRawY();
            if (Math.hypot(x - lastDownRawX, y - lastDownRawY) > 12 * d || e.getEventTime() - lastDownAt > 600) return;   // a tap
            if (Math.abs(y - doodlesButtonY) > 30 * d || Math.abs(x - doodlesButtonX) < 28 * d) return;              // another button in the row
            if (DoodleOverlay.onPanel(x, y)) return;
            main.post(new Runnable() { public void run() { if (DoodleOverlay.active()) DoodleOverlay.leave(false); } });
        } catch (Throwable error) { logOnce("tool bar tap not read", error); }
    }
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
            try {   // the 4dp gap before our button: optional, only from a checked table
                spacer = method(StudioNames.cls("spacer", cl), StudioNames.m("spacer.m"), modifier, composer);
                width = method(StudioNames.cls("width", cl), StudioNames.m("width.m"), modifier, float.class);
            } catch (Throwable missing) { spacer = width = null; logOnce("doodles button gap left out (" + missing + ")", null); }
            emptyModifier = emptyModifier(modifier);
            readState = getter(stateType);
            writeState = setter(stateType);
            // A Compose mutable state (btx(value, policy), bso's base): reading it while the
            // toolbar composes makes the button recompose when Doodles turns on or off.
            Class<?> mutable = StudioNames.cls("mutable", cl), policyType = StudioNames.cls("policy", cl);
            Constructor<?> state = mutable.getDeclaredConstructor(Object.class, policyType);
            state.setAccessible(true);
            selectedState = state.newInstance(Boolean.FALSE, structuralPolicy(StudioNames.cls("policy.impl", cl)));
            final Method doneButton = method(jix, StudioNames.m("ui.done"), String.class, boolean.class, svfType, int.class, composer, int.class, int.class);

            // painterResource(ICON_ID) -> our icon. Every other painter it hands out is noted with its drawable,
            // which is how the pen button is told apart from the highlighter (both are the same composable).
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

            // After the pen's button: our spacer + button, only when the toolbar row composes it.
            Xp.hookMethod(penButton, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    Integer which;
                    synchronized (painterIds) { which = painterIds.get(p.args[1]); }
                    // preview17: only the tool bar's own pen buttons say which tool is on (a pen drawn elsewhere, as in the
                    // owner's "toolbar row not found" log, left a tool marked on, and opening Doodles pressed an old ✓).
                    // preview21 (owner: tapping another tool left Doodles open): a pen button that recomposes on its own
                    // (its restart lambda, when only its selection changes) is still the tool bar's button; its on/off
                    // counts too, so a tool turning on closes Doodles (and opening Doodles closes the pen that is on).
                    int where = toolbarCaller();
                    boolean row = where == ROW;
                    if (where != OTHER) track("stroke" + which, Boolean.TRUE.equals(p.args[4]));
                    if (which == null || which != normalPen() || !doodlesOn() || !row) return;
                    try {
                        Object composer = p.args[6];
                        boolean selected = Boolean.TRUE.equals(readState.invoke(selectedState));
                        if (spacer != null && width != null) spacer.invoke(null, width.invoke(null, emptyModifier, 4f), composer);
                        toolButton.invoke(null, ICON_ID, click(), selected, emptyModifier, composer, 0);
                    } catch (Throwable error) { logOnce("doodles button could not be added", error); }
                }
            });
            // Crop and eraser (and ours): note which native tool is on, to leave doodles when one starts.
            Xp.hookMethod(toolButton, new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    int id = (Integer) p.args[0];
                    if (id == ICON_ID) return;
                    track("tool" + id, Boolean.TRUE.equals(p.args[2]));
                }
            });
            // The ✓ shown while a native tool is active: used to close it when Doodles starts.
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
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { lastDownRawX = e.getRawX(); lastDownRawY = e.getRawY(); lastDownAt = e.getEventTime(); }
                    else if (e.getActionMasked() == MotionEvent.ACTION_UP) toolbarTap(e);
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

    /** The drawable of the normal pen's bristles: the button our button goes after. */
    static int normalPen() {
        if (sNormalPen == 0) {
            Context c = app();
            if (c != null) sNormalPen = c.getResources().getIdentifier("brush_normal_bristles", "drawable", PACKAGE);
            if (sNormalPen == 0) { logOnce("pen icon not found, doodles button skipped", null); return -1; }
        }
        return sNormalPen;
    }

    /** Modifier (the empty one, Modifier.Companion): the Modifier interface's own static instance. */
    static Object emptyModifier(Class<?> modifier) throws Exception {
        for (Field f : modifier.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object v = f.get(null);
            if (modifier.isInstance(v)) return v;
        }
        throw new NoSuchFieldException("empty Modifier in " + modifier.getName());
    }

    /** structuralEqualityPolicy(): the policy instance whose toString says so. */
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

    /**
     * True when the pen button is being composed by the toolbar row (its lambda jgn), not by the
     * pen button's own recomposition (jip): only then may another button follow it. If neither
     * lambda's name is known, the caller is told apart by shape: the recomposition lambda keeps
     * the pen's two painters, the row's lambda does not.
     */
    static final int ROW = 0, RESTART = 1, OTHER = 2;
    static boolean fromToolbarRow() { return toolbarCaller() == ROW; }
    /** Who composes the pen button: the tool bar row, the button's own recomposition, or something else. */
    static int toolbarCaller() {
        String row = StudioNames.get("toolbarLambda"), restart = StudioNames.get("penRestart");
        StackTraceElement[] stack = new Throwable().getStackTrace();
        String caller = null;
        for (int i = 0; i < stack.length && i < 240; i++) {   // preview21: Compose stacks run deep (80 frames missed the row)
            String name = stack[i].getClassName();
            if (name.equals(row)) return ROW;
            if (name.equals(restart)) return RESTART;
            if (caller == null && name.indexOf('.') < 0 && !name.equals(uiClass) && !name.startsWith("$") && !name.startsWith("LSP")) caller = name;
        }
        if (caller != null && painterClass != null) {
            try {
                int painters = 0;
                for (Field f : Class.forName(caller, false, loader).getDeclaredFields()) if (f.getType() == painterClass) painters++;
                return painters < 2 ? ROW : RESTART;
            } catch (Throwable ignored) { }
        }
        logOnce("toolbar row not found on the stack, doodles button skipped", null);
        return OTHER;
    }

    /**
     * A native tool turning on while Doodles is active ends Doodles (the tool takes over).
     * 2.0.4 (owner's video: Doodles opened and faded out at once, or blinked with the pen): only a real switch from
     * off to on counts, and not while Doodles is just opening — the tool bar recomposes then and re-reports the
     * tools it had, which used to look like a tool turning on and closed Doodles straight away.
     */
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

    /** The button's onClick, a Kotlin () -> Unit built as a proxy of the app's own interface. */
    static Object click() {
        if (clickProxy != null) return clickProxy;
        clickProxy = Proxy.newProxyInstance(loader, new Class<?>[]{svfType}, new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                String name = m.getName();
                if ("equals".equals(name)) return proxy == args[0];
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("toString".equals(name)) return "PixelSuiteDoodlesClick";
                doodlesButtonX = lastDownRawX; doodlesButtonY = lastDownRawY;
                main.post(new Runnable() { public void run() { DoodleOverlay.toggle(); } });
                return unit(loader);
            }
        });
        return clickProxy;
    }

    /** Closes an active native tool (pen, highlighter, eraser, crop) through its own ✓. */
    static long doneSeen, toolOnAt;
    static void closeNativeTool() {
        Object done = doneClick;
        // preview17: only a ✓ shown since the tool turned on, and only once (an old ✓ left Pixel Studio unable to exit).
        openedAt = android.os.SystemClock.uptimeMillis();
        // The tools' on/off marks are kept (clearing them made the next recomposition look like tools turning on).
        // preview24 (owner's videos: a pen left on under Doodles kept drawing): the editor's own page says whether a tool
        // is open (its ✓ is then on screen), whatever the tracking of the tool buttons saw.
        String page = TopBarKeep.currentPage();
        boolean toolPage = page != null && !"CROP_TOOL".equals(page) && !"READY".equals(page) && !"INITIAL".equals(page);
        if (done == null || (!toolPage && (!anyNativeToolOn() || doneSeen < toolOnAt))) { doneClick = null; return; }
        doneClick = null;
        for (Map.Entry<String, Boolean> e : nativeSelected.entrySet()) e.setValue(Boolean.FALSE);
        try { sam(svfType).invoke(done); } catch (Throwable error) { logOnce("native tool could not be closed", error); }
    }

    // ------------------------------------------------------------------ doodles: rendering

    static Field rendererCanvas;
    static Method canvasMatrix;

    static void installRendering(ClassLoader cl) throws Exception {
        final Class<?> renderer = StudioNames.cls("renderer", cl), config = StudioNames.cls("config", cl), view = StudioNames.cls("view", cl);
        Class<?> area = StudioNames.cls("area", cl);
        rendererCanvas = fieldOfType(renderer, area);
        canvasMatrix = method(area, StudioNames.m("area.matrix"));
        Xp.hookMethod(method(renderer, StudioNames.m("renderer.picture"), config), new Xp.Callback() {
            @Override protected void afterHookedMethod(Xp.Param p) {
                if (p.getResult() instanceof Picture) CropLook.picture((Picture) p.getResult());
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
                    CropLook.picture(out);
                } catch (Throwable error) { logOnce("doodles could not be drawn into the image", error); }
            }
        });
        Xp.hookAllConstructors(view, new Xp.Callback() {
            @Override protected void afterHookedMethod(Xp.Param p) {
                canvasView = new WeakReference<View>((View) p.thisObject);
                // preview17: the editor rebuilt its canvas (switching screens): an open Doodles belongs to the old one.
                // 2.0.4: only once the old canvas is really off the screen (Pixel Studio builds a new one while the old
                // stays, e.g. when a tool's ✓ is pressed as Doodles opens, and Doodles must stay then).
                // Doodles opened a moment ago (a tool's ✓ was just pressed for it) moves to the new canvas instead.
                final View fresh = (View) p.thisObject;
                main.postDelayed(new Runnable() { public void run() {
                    if (!DoodleOverlay.active() || !DoodleOverlay.studioCanvasGone()) return;
                    if (DoodleOverlay.rehost(fresh)) return;   // preview39: the same picture on a new canvas (cropping)
                    DoodleOverlay.leave(false);
                    if (android.os.SystemClock.uptimeMillis() - openedAt < 1500) main.post(new Runnable() { public void run() { if (!DoodleOverlay.active()) DoodleOverlay.enter(); } });
                } }, 300);
            }
        });
    }

    /** The editor's canvas view if it belongs to {@code a} and is showing. */
    static View canvasFor(Activity a) {
        View v = canvasView.get();
        if (v == null || !v.isAttachedToWindow()) return null;
        Context c = v.getContext();
        while (c instanceof android.content.ContextWrapper && !(c instanceof Activity)) c = ((android.content.ContextWrapper) c).getBaseContext();
        return c == a || c == null ? v : null;
    }

    /** Current activity of the editor, from the canvas view's context. */
    static Activity activity() {
        View v = canvasView.get(); if (v == null) return null;
        Context c = v.getContext();
        while (c instanceof android.content.ContextWrapper && !(c instanceof Activity)) c = ((android.content.ContextWrapper) c).getBaseContext();
        return c instanceof Activity ? (Activity) c : null;
    }
}
