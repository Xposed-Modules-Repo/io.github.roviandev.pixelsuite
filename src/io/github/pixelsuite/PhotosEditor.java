package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Doodles in Google Photos' editor (2.0.4): a "Doodles" tab right of Crop opens the same Doodles menu and shapes as in
 * the screenshot editor, and the shapes are painted into the picture Photos saves.
 *
 * Built to survive Photos updates: nothing here uses Photos' renamed (R8) names. It hangs on
 *  - names that cannot change without breaking Photos itself: the JNI class NativeRenderer and its native methods
 *    (getImageScreenRect, getPipelineParams, surfaceCreated, computeResultImage, computeResultImageAndGainMap), the
 *    EditResult fields its native code fills (sdrImage, gainmapImage), and TabContainerView, named in a layout;
 *  - resource names, looked up at run time (the Crop label, the tab layout).
 * Anything missing leaves Photos unchanged and writes one log line.
 *
 * Shapes live in "image units": the picture as shown is 1000 units wide. They are drawn on screen by this module (a
 * layer over the picture) and into the saved picture at its own size. They belong to the picture as it was when they
 * were drawn: if the picture's shape changes afterwards (a new crop), they are hidden and not saved, rather than
 * landing in the wrong place.
 */
final class PhotosEditor {
    static final String PACKAGE = "com.google.android.apps.photos";
    static final String NATIVE = "com.google.android.apps.photos.photoeditor.nativebridge.NativeRenderer";
    static final String TABS = "com.google.android.apps.photos.photoeditor.fragments.editor3.TabContainerView";
    static final float REF = 1000f;
    static final String TAG = "pixelsuite_doodles_tab";

    private PhotosEditor() {}

    private static WeakReference<Object> renderer = new WeakReference<Object>(null);
    private static Method screenRect, pipelineParams;
    /** The editor the latest shapes belong to (the shapes' key), and the picture's height/width when they were drawn. */
    private static WeakReference<Activity> editor = new WeakReference<Activity>(null);
    private static final Map<Object, Float> aspects = new WeakHashMap<Object, Float>();
    /** Editors whose shapes are kept in the photo's own coordinates (see v2ToImage). */
    private static final Map<Object, Boolean> imageSpace = new WeakHashMap<Object, Boolean>();
    private static final Map<Bitmap, Boolean> painted = new WeakHashMap<Bitmap, Boolean>();
    private static final Map<Activity, Preview> previews = new WeakHashMap<Activity, Preview>();

    static void log(String m) { Module.log("photos-doodles: " + m); }
    static void logOnce(String m, Throwable t) { ScreenshotEditor.logOnce("photos " + m, t); }

    static void install(ClassLoader cl) {
        int hooks = installV2(cl), saves = 0;
        Class<?> nr = null;
        try { nr = Class.forName(NATIVE, false, cl); }
        catch (Throwable missing) { log("older editor renderer not found (" + missing + ")"); }
        if (nr != null) {
            try {
                screenRect = find(nr, "getImageScreenRect", 1);
                pipelineParams = find(nr, "getPipelineParams", 0);
            } catch (Throwable missing) { log("older editor picture position unavailable (" + missing + ")"); }
            // The renderer drawing the older editor's picture on screen.
            Xp.Callback mine = new Xp.Callback() { protected void afterHookedMethod(Xp.Param p) { renderer = new WeakReference<Object>(p.thisObject); } };
            hooks += Xp.hookAllMethods(nr, "surfaceCreated", mine).size();
            hooks += Xp.hookAllConstructors(nr, new Xp.Callback() { protected void afterHookedMethod(Xp.Param p) { if (renderer.get() == null) renderer = new WeakReference<Object>(p.thisObject); } }).size();
            // The saved picture.
            saves += Xp.hookAllMethods(nr, "computeResultImage", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try { if (p.getResult() instanceof Bitmap) { Bitmap out = paint((Bitmap) p.getResult(), false); if (out != p.getResult()) p.setResult(out); } }
                    catch (Throwable error) { logOnce("doodles could not be drawn into the saved picture", error); }
                }
            }).size();
            saves += Xp.hookAllMethods(nr, "computeResultImageAndGainMap", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        Object r = p.getResult(); if (r == null) return;
                        Field sdr = r.getClass().getField("sdrImage"), gain = r.getClass().getField("gainmapImage");
                        Bitmap image = (Bitmap) sdr.get(r); if (image == null) return;
                        Bitmap out = paint(image, false); if (out == image && !painted.containsKey(image)) return;
                        if (out != image) sdr.set(r, out);
                        Bitmap map = (Bitmap) gain.get(r);
                        if (map != null) { Bitmap m2 = paint(map, true); if (m2 != map) gain.set(r, m2); }
                    } catch (Throwable error) { logOnce("doodles could not be drawn into the saved picture (HDR)", error); }
                }
            }).size();
        }
        // The tab, next to Crop.
        try {
            Class<?> tabs = Class.forName(TABS, false, cl);
            hooks += Xp.hookAllConstructors(tabs, new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    final View v = (View) p.thisObject;
                    v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        public void onLayoutChange(View x, int l, int t, int r, int b, int ol, int ot, int or, int ob) { addTab(v); }
                    });
                    v.post(new Runnable() { public void run() { addTab(v); } });
                }
            }).size();
        } catch (Throwable missing) { log("editor tabs not found, no Doodles tab (" + missing + ")"); }
        // Touches move the picture (zoom, pan): the shapes follow while the finger is down and a moment after.
        try {
            Xp.hookAllMethods(Activity.class, "dispatchTouchEvent", new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    Preview v = previews.get(p.thisObject); if (v != null) v.kick();
                    if (p.args.length > 0 && p.args[0] instanceof MotionEvent) PhotosTabs.touch((Activity) p.thisObject, (MotionEvent) p.args[0]);
                }
            });
            // The current editor's tab row (Compose): set up once the app's resources exist (the first activity).
            Xp.hookAllMethods(Activity.class, "onCreate", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    Activity a = (Activity) p.thisObject;
                    if (a.getClass().getName().endsWith("PhotoEditorNextActivity") && enabled(a)) restore(a);
                    if (!destroyHooked) {   // preview16: the editor activity closing ends its session (see closed)
                        destroyHooked = true;
                        try {
                            Xp.hookMethod(Activity.class.getDeclaredMethod("onDestroy"), new Xp.Callback() {
                                protected void afterHookedMethod(Xp.Param q) { closed((Activity) q.thisObject); }
                            });
                        } catch (Throwable error) { logOnce("editor close tracking unavailable", error); }
                    }
                    if (tabsReady) return; tabsReady = true;
                    if (enabled(a)) { PhotosTabs.install(a.getClassLoader(), a.getApplicationContext()); PhotosSave.install(a.getClassLoader(), a.getApplicationContext()); }
                }
            });
        } catch (Throwable error) { logOnce("picture movement tracking unavailable", error); }
        log("hooks installed (" + hooks + ", saving " + saves + ")");
    }

    /*
     * preview7: the current editor ("enchilada", owner's Photos 7.96) draws with renderer V2 and saves with edit
     * processor V2, other JNI classes than the older editor's (preview6 log: "the picture is not placed yet": the
     * older renderer never ran). Their names are fixed by the native library:
     *  - NativeRendererV2.nativeDrawFrame(long handle, ...) runs on the GL thread for every frame; right after it the
     *    picture's rectangle is read with nativeGetImageScreenRect(handle) and kept (v2Rect). When it moves (zoom,
     *    pan, a new crop), the shapes are redrawn.
     *  - NativeEditProcessorV2.nativeRender(...) returns a RenderResult (outputImage, outputGainmap) for saving.
     */
    static volatile RectF v2Rect;
    /**
     * preview8 (owner's video): the picture's place was only learned from a frame being drawn, and the editor draws
     * only when something changes, so Doodles found nothing until the picture moved once (a crop and X). The renderer
     * and its native handle are now kept from the calls that set the editor up (surface created / changed, preview
     * parameters, every frame), and the rectangle is asked for directly when Doodles opens.
     */
    static WeakReference<Object> v2Renderer = new WeakReference<Object>(null);
    static volatile long v2Handle;
    static RectF askV2() {
        Object r = v2Renderer.get(); long h = v2Handle;
        if (r == null || h == 0 || v2GetRect == null) return null;
        try { synchronized (r) { RectF got = (RectF) v2GetRect.invoke(r, h); if (got != null) { v2Rect = new RectF(got); v2ToImage = toImage(r, h); keep(); } return got; } }
        catch (Throwable error) { logOnce("picture position (V2, direct) unavailable", error); return null; }
    }
    static Method v2GetRect, v2GetToImage;
    /**
     * preview12 (owner: cropping from inside Doodles): the editor's own screen-to-image transform
     * (NativeRendererV2.nativeGetScreenToImageTransform, what Photos maps touches with). With it, shapes are kept in
     * the photo's own coordinates, so they stay on the same spot of the photo through crops, zoom and rotation, and
     * are painted into the saved photo through the same transform. Without it, the preview5 way (the picture as
     * shown, 1000 wide) is used.
     */
    static volatile android.graphics.Matrix v2ToImage;
    static android.graphics.Matrix toImage(Object r, long h) {
        android.graphics.Matrix m = rawToImage(r, h);
        return m == null ? null : new android.graphics.Matrix(m);
    }
    /** Photos' own matrix as returned (not copied), or null. */
    static android.graphics.Matrix rawToImage(Object r, long h) {
        if (v2GetToImage == null) return null;
        try { Object m = v2GetToImage.invoke(r, h); return m instanceof android.graphics.Matrix ? (android.graphics.Matrix) m : null; }
        catch (Throwable error) { logOnce("picture transform (V2) unavailable", error); return null; }
    }
    /** The picture moved on screen (posted from the GL thread). */
    static final Runnable movedOnScreen = new Runnable() { public void run() { cache = null; redraw(); } };
    /**
     * preview13 (owner's video: a small arrow became a huge stretched blob): Photos' transform gives 0..1 across the
     * photo on each side, so a tall photo's units were stretched 2x one way and one unit was the whole photo (shape
     * sizes, made for a picture about 1000 wide, filled it). The units are made square and 1000 across the photo:
     * x * 1000, y * 1000 * (photo height / width), the shape taken from the transform itself (a rotation or zoom does
     * not change it).
     */
    static android.graphics.Matrix square(android.graphics.Matrix m) {
        android.graphics.Matrix inv = new android.graphics.Matrix();
        if (m == null || !m.invert(inv)) return null;
        float[] v = {1f, 0f, 0f, 1f}; inv.mapVectors(v);
        float lx = (float) Math.hypot(v[0], v[1]), ly = (float) Math.hypot(v[2], v[3]);
        if (lx < 1e-3f || ly < 1e-3f) return null;
        android.graphics.Matrix out = new android.graphics.Matrix(m);
        out.postScale(REF, REF * ly / lx);
        return out;
    }
    static final String V2 = "com.google.android.apps.photos.photoeditor.enchilada.api.jni.";

    /*
     * preview14 (owner's video: after leaving the editor, the Doodles menu and the shapes stayed over the photo grid
     * and the viewer). The editor is a screen inside Photos' main activity, so nothing of ours went away with it, and
     * the Doodles tab still counted as selected, so a tap anywhere opened Doodles again. An editing session now:
     *  - begins when the editor makes its renderer (nativeAllocate),
     *  - ends when its renderers are all gone (nativeDispose) or its picture surface left the screen, checked 0.4 s
     *    later so a renderer that is only being remade does not end it.
     * At the end Doodles closes, the tab is no longer selected, the shapes and their history are let go, and the
     * picture position is forgotten (nothing is drawn outside the editor). The shapes are held for 90 s in case Photos
     * renders the photo again after closing the editor (preview20 log: four more renders a second after a save); a new
     * session drops them.
     */
    static final java.util.Set<Long> live = java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());
    static volatile boolean session;
    static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    static volatile RectF keptRect; static volatile android.graphics.Matrix keptMatrix;
    static void keep() { RectF r = v2Rect; if (r != null) { keptRect = r; keptMatrix = v2ToImage; } }

    static final class Held { List<Doodle> shapes; RectF rect; android.graphics.Matrix matrix; boolean imageSpace; Float aspect; long at; String key; }
    static volatile Held held;

    /*
     * preview15 (owner: "entering an already edited image with doodles resets the shapes"): Photos keeps the original
     * and re-applies its own edits when a photo is edited again, so the painted shapes were gone. The shapes painted
     * into a saved photo are now kept (Kept) for that photo, keyed by the photo the editor was opened with (its
     * intent's address), and brought back as editable shapes the next time it is edited.
     */
    static final Map<Activity, String> photoKeys = new WeakHashMap<Activity, String>();
    static final Map<Activity, Boolean> restored = new WeakHashMap<Activity, Boolean>();

    /**
     * preview16 (owner: the shapes still did not come back; the log showed no "kept"/"brought back" line): the photo
     * is now named by Photos' media key (the editor intent's com.google.android.apps.photos.editor.contract.media_key,
     * the same for a photo across edits) and by the intent's address, and kept under both, so either finds it.
     */
    static final String MEDIA_KEY = "com.google.android.apps.photos.editor.contract.media_key";

    static final String EDIT_LIST = "com.google.android.apps.photos.editor.contract.edit_list";

    /**
     * preview19 (owner's log): a fresh screenshot was saved under its media-store number only (no media key yet, not
     * backed up), and came back through Photos' editor provider (content://...editor.contentprovider/account/edit),
     * whose address has no media-store number in it. The number is now also looked for in every extra of the editor's
     * intent (Photos' own media objects carry the item's content://media/... address; they are written to a Parcel and
     * read as text), so the same photo is found by the same number before and after it is backed up.
     */
    static String keysOf(Activity a) {
        android.content.Intent i = a.getIntent(); if (i == null) return null;
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<String>();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<String>();
        storeIds(i.getDataString(), ids);
        try {
            android.os.Bundle ex = i.getExtras();
            if (ex != null) for (String k : ex.keySet()) {
                Object v; try { v = ex.get(k); } catch (Throwable unreadable) { continue; }
                if (v == null) continue;
                if (v instanceof CharSequence) storeIds(v.toString(), ids);
                else if (v instanceof android.os.Parcelable || v instanceof android.os.Bundle || v instanceof android.os.Parcelable[] || v instanceof List) storeIds(parcelText(v), ids);
            }
        } catch (Throwable ignored) { }
        // preview22: Photos' own name for the item (its dedup key) from Photos' database, the same before and after the
        // first save, online or not; an edit record also gives back the media-store number the editor's address lost.
        try { PhotosNames.fromDatabase(a.getApplicationContext(), i, ids, keys); } catch (Throwable error) { logOnce("photo's name from Photos' database unavailable", error); }
        for (String id : ids) keys.add("store:" + id);
        try { String mk = i.getStringExtra(MEDIA_KEY); if (mk != null && mk.length() > 0) keys.add("media:" + mk); } catch (Throwable ignored) { }
        // preview20: Photos' own identity of the item, the dedup key, which it keeps through its edits. Photos' media
        // objects in the intent print it ("DedupKey{dedupKeyString=...}", text kept in Photos' code).
        try {
            android.os.Bundle ex = i.getExtras();
            boolean found = false;
            for (String k : keys) if (k.startsWith("dedup:")) found = true;
            if (!found && ex != null) for (String k : ex.keySet()) {
                Object v; try { v = ex.get(k); } catch (Throwable unreadable) { continue; }
                if (v == null || v instanceof CharSequence) continue;
                String dk = dedupKey(v, 0, new java.util.IdentityHashMap<Object, Boolean>());
                if (dk != null) { keys.add("dedup:" + dk); break; }
            }
        } catch (Throwable ignored) { }
        android.net.Uri data = i.getData();
        if (data != null) keys.add("uri:" + data);
        if (keys.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String k : keys) b.append(b.length() > 0 ? "\n" : "").append(k);
        return b.toString();
    }

    static String dedupKey(Object o, int depth, java.util.IdentityHashMap<Object, Boolean> seen) {
        if (o == null || depth > 6 || seen.size() > 4000 || seen.containsKey(o)) return null;
        seen.put(o, Boolean.TRUE);
        Class<?> c = o.getClass();
        String cn = c.getName();
        if (cn.startsWith("java.lang.") || cn.startsWith("android.") || cn.startsWith("io.github.")) {
            if (o instanceof android.os.Bundle) { android.os.Bundle b = (android.os.Bundle) o; for (String k : b.keySet()) { String r = dedupKey(b.get(k), depth + 1, seen); if (r != null) return r; } }
            return null;
        }
        if (c.getClassLoader() != null && c.getClassLoader() != Object.class.getClassLoader()) {
            String s; try { c.getDeclaredMethod("toString"); s = String.valueOf(o); } catch (Throwable t) { s = null; }   // only a class with its own text
            if (s != null) {
                int at = s.indexOf("DedupKey{dedupKeyString=");
                if (at >= 0) { int end = s.indexOf('}', at); if (end > at) { String key = s.substring(at + 24, end).trim(); if (key.length() > 0 && !key.startsWith("fake:")) return key; } }
            }
        }
        if (c.isArray()) { if (c.getComponentType().isPrimitive()) return null; int n = Math.min(java.lang.reflect.Array.getLength(o), 64); for (int k = 0; k < n; k++) { String r = dedupKey(java.lang.reflect.Array.get(o, k), depth + 1, seen); if (r != null) return r; } return null; }
        if (o instanceof java.util.Collection) { int k = 0; for (Object x : (java.util.Collection<?>) o) { if (k++ > 64) break; String r = dedupKey(x, depth + 1, seen); if (r != null) return r; } return null; }
        if (o instanceof java.util.Map) { int k = 0; for (Object x : ((java.util.Map<?, ?>) o).values()) { if (k++ > 64) break; String r = dedupKey(x, depth + 1, seen); if (r != null) return r; } return null; }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                Object v; try { f.setAccessible(true); v = f.get(o); } catch (Throwable t) { continue; }
                String r = dedupKey(v, depth + 1, seen); if (r != null) return r;
            }
        return null;
    }

    static final java.util.regex.Pattern STORE = java.util.regex.Pattern.compile("content://media/[^/\\s%]+/(?:images|video|file)/media/(\\d+)");
    static void storeIds(String text, java.util.Set<String> out) {
        if (text == null || text.isEmpty()) return;
        String u = text;
        for (int i = 0; i < 3; i++) {
            java.util.regex.Matcher m = STORE.matcher(u);
            while (m.find()) out.add(m.group(1));
            String d; try { d = android.net.Uri.decode(u); } catch (Throwable t) { break; }
            if (d == null || d.equals(u)) break;
            u = d;
        }
    }
    /** A parcelable's contents as text (its strings are UTF-16 inside the Parcel). */
    static String parcelText(Object v) {
        android.os.Parcel p = android.os.Parcel.obtain();
        try { p.writeValue(v); return new String(p.marshall(), "UTF-16LE"); }
        catch (Throwable t) { return ""; }
        finally { p.recycle(); }
    }

    /** The editor was opened on a photo Photos keeps edits for (its editor provider, or an edit list in the intent). */
    static boolean editedPhoto(Activity a) {
        android.content.Intent i = a.getIntent(); if (i == null) return false;
        android.net.Uri d = i.getData();
        if (d != null && d.getAuthority() != null && d.getAuthority().endsWith(".editor.contentprovider")) return true;
        try { return i.hasExtra(EDIT_LIST); } catch (Throwable t) { return false; }
    }

    static void restore(Activity a) {
        try {
            String keys = keysOf(a);
            if (keys == null) return;
            photoKeys.put(a, keys);
            editor = new WeakReference<Activity>(a);
            if (v2GetToImage == null) return;   // shapes are kept in the photo's coordinates; without them, nothing to bring back
            List<Doodle> kept = Kept.load(a, keys);
            if (kept.isEmpty()) return;
            // preview19: the shapes belong to the edited photo Photos keeps (original + its edits). A photo without
            // saved edits (reverted, or a copy with the doodles already painted in) gets none, and its file is dropped.
            if (!editedPhoto(a)) { Kept.drop(a, keys); return; }
            synchronized (DoodleOverlay.models) { List<Doodle> m = DoodleOverlay.model(a); m.clear(); m.addAll(kept); }
            imageSpace.put(a, Boolean.TRUE);
            restored.put(a, Boolean.TRUE);
            Kept.store(keys, kept);   // under every name this session knows (a media key may be new since the save)
            // The shapes are shown once the picture is placed (redraw -> preview): a view added now, during onCreate,
            // is removed again by Photos' own setContentView (preview18 log: "brought back" but nothing drawn).
        } catch (Throwable error) { logOnce("doodles of this photo could not be brought back", error); }
    }

    /** The editor is open and alive (a render while it is closing or closed is Photos' own later work). */
    static boolean editing() {
        Activity a = editor.get();
        return session && a != null && !a.isFinishing() && !a.isDestroyed();
    }

    /**
     * preview22: Photos rendered for Revert (preview21 log: the brought-back doodles were drawn into the reverted
     * original and kept, so they came back on top of themselves). The reverted photo gets no doodles and the kept
     * ones go.
     */
    static void reverted() {
        Activity a = editor.get(); if (a == null) return;
        String key = photoKeys.get(a);
        synchronized (DoodleOverlay.models) { DoodleOverlay.model(a).clear(); }
        restored.remove(a); held = null;
        if (key != null) Kept.drop(a, key);
        main.post(new Runnable() { public void run() { redraw(); } });
    }

    static boolean destroyHooked;
    /**
     * An activity was destroyed. Only the current editor's own close ends the session; an older editor destroyed after
     * a new one opened (Photos does that late) only lets go of its own shapes.
     */
    static void closed(Activity a) {
        // The preview view holds its activity, so its map entry would never be let go by itself.
        previews.remove(a);
        if (!photoKeys.containsKey(a) && editor.get() != a) return;
        if (editor.get() == a) { end(); return; }
        DoodleOverlay.forget(a); imageSpace.remove(a); aspects.remove(a);
    }

    static void begin() {
        if (session) return;
        session = true; held = null;
        main.post(new Runnable() { public void run() { if (session) DarkChips.on("Google Photos editor"); } });
    }

    static final Runnable endCheck = new Runnable() { public void run() {
        if (!session) return;
        if (live.isEmpty()) { end(); return; }
        View s = surfaceRef.get();
        if (s == null || s.isAttachedToWindow()) return;
        Activity a = editor.get();
        if (a == null || surface(a) == null) end();   // the picture left the screen, not just swapped for another surface
    }};

    static void end() {
        if (!session) return;
        Activity a = editor.get();
        // preview16: the last editor's close can arrive after the next one opened (Photos destroys the old editor
        // activity late). Ending then wiped the new editor's shapes, brought back a moment before. A close while a
        // newer editor is open and drawing is ignored.
        if (a != null && !a.isFinishing() && !a.isDestroyed() && !live.isEmpty()) return;
        session = false;
        if (a != null) {
            List<Doodle> shapes = DoodleOverlay.snapshot(a);
            if (!shapes.isEmpty()) {
                Held h = new Held(); h.shapes = shapes; h.rect = keptRect; h.matrix = keptMatrix;
                h.imageSpace = Boolean.TRUE.equals(imageSpace.get(a)); h.aspect = aspects.get(a); h.at = android.os.SystemClock.uptimeMillis(); h.key = photoKeys.get(a);
                held = h;
            }
            DoodleOverlay.forget(a); imageSpace.remove(a); aspects.remove(a);
        }
        PhotosTabs.reset();
        PhotosSave.reset();
        if (DoodleOverlay.active()) DoodleOverlay.leave(false);
        v2Rect = null; v2ToImage = null; v2Handle = 0; v2Renderer = new WeakReference<Object>(null);
        keptRect = null; keptMatrix = null; cache = null;
        surfaceRef = new WeakReference<View>(null);
        redraw();
        DarkChips.off();
    }

    static int installV2(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> r2 = Class.forName(V2 + "NativeRendererV2", false, cl);
            v2GetRect = find(r2, "nativeGetImageScreenRect", 1);
            try { v2GetToImage = find(r2, "nativeGetScreenToImageTransform", 1); } catch (Throwable missing) { log("picture transform not found, shapes follow the picture as shown"); }
            Xp.Callback setup = new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof Long) || (Long) p.args[0] == 0) return;
                    if (v2Renderer.get() != p.thisObject || v2Handle != (Long) p.args[0]) {   // a new editor: its own picture
                        v2Renderer = new WeakReference<Object>(p.thisObject); v2Handle = (Long) p.args[0]; v2Rect = null;
                    }
                    live.add((Long) p.args[0]);
                    begin();
                }
            };
            for (String m : new String[]{"nativeSurfaceCreated", "nativeSurfaceChanged", "nativeSetPreviewParams", "nativeLoadTexture"}) n += Xp.hookAllMethods(r2, m, setup).size();
            // preview10 (owner: still hidden on the first visit): the handle straight from its creation, nativeAllocate's result.
            n += Xp.hookAllMethods(r2, "nativeAllocate", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    if (!(p.getResult() instanceof Long) || (Long) p.getResult() == 0) return;
                    v2Renderer = new WeakReference<Object>(p.thisObject); v2Handle = (Long) p.getResult(); v2Rect = null;
                    live.add((Long) p.getResult());
                    begin();
                }
            }).size();
            // preview14: the editor's renderers go away when it closes (the session ends, see endCheck).
            n += Xp.hookAllMethods(r2, "nativeDispose", new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length == 0 || !(p.args[0] instanceof Long)) return;
                    long h = (Long) p.args[0];
                    live.remove(h);
                    if (h == v2Handle) { v2Handle = 0; v2Rect = null; v2ToImage = null; v2Renderer = new WeakReference<Object>(null); }
                    main.postDelayed(endCheck, 400);
                }
            }).size();
            n += Xp.hookAllMethods(r2, "nativeDrawFrame", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length == 0 || !(p.args[0] instanceof Long)) return;
                        if (v2Renderer.get() != p.thisObject) { v2Renderer = new WeakReference<Object>(p.thisObject); v2Handle = (Long) p.args[0]; }
                        RectF r = (RectF) v2GetRect.invoke(p.thisObject, p.args[0]);
                        if (r == null) return;
                        // Every frame: Photos' own matrix is compared first and copied only when the picture moved.
                        android.graphics.Matrix m = rawToImage(p.thisObject, (Long) p.args[0]);
                        RectF was = v2Rect; android.graphics.Matrix wasM = v2ToImage;
                        if (was != null && was.equals(r) && (m == null ? wasM == null : m.equals(wasM))) return;
                        v2Rect = new RectF(r); v2ToImage = m == null ? null : new android.graphics.Matrix(m); keep();
                        main.post(movedOnScreen);
                    } catch (Throwable error) { logOnce("picture position (V2) unavailable", error); }
                }
            }).size();
        } catch (Throwable missing) { log("renderer V2 not found (" + missing + ")"); }
        try {
            Class<?> e2 = Class.forName(V2 + "NativeEditProcessorV2", false, cl);
            n += Xp.hookAllMethods(e2, "nativeRender", new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    if (p.args.length > 0 && p.args[0] instanceof Long) PhotosSave.beforeRender(p.thisObject, (Long) p.args[0]);
                }
                protected void afterHookedMethod(Xp.Param p) {
                    if (p.args.length > 0 && p.args[0] instanceof Long) PhotosSave.afterRender(p.thisObject, (Long) p.args[0]);
                    try {
                        Object r = p.getResult(); if (r == null) return;
                        if (PhotosSave.revertRender()) { reverted(); return; }
                        Field img = r.getClass().getDeclaredField("outputImage"), gain = r.getClass().getDeclaredField("outputGainmap");
                        img.setAccessible(true); gain.setAccessible(true);
                        Bitmap image = (Bitmap) img.get(r); if (image == null) return;
                        Bitmap out = paint(image, false); if (out == image && !painted.containsKey(image)) return;
                        if (out != image) img.set(r, out);
                        Bitmap map = (Bitmap) gain.get(r);
                        if (map != null) { Bitmap m2 = paint(map, true); if (m2 != map) gain.set(r, m2); }
                    } catch (Throwable error) { logOnce("doodles could not be drawn into the saved picture (V2)", error); }
                }
            }).size();
        } catch (Throwable missing) { log("edit processor V2 not found (" + missing + ")"); }
        return n;
    }

    /** The shapes moved with the picture: the open Doodles layer or the closed-state preview redraws. */
    static void redraw() {
        Activity a = editor.get();   // a session with shapes but no preview view yet (brought back at onCreate)
        if (a != null && !a.isFinishing() && DoodleOverlay.hasShapes(a)) try { preview(a); } catch (Throwable error) { logOnce("preview view unavailable", error); }
        for (Preview p : previews.values()) if (p != null) p.invalidate();
        DoodleOverlay.redraw();
    }

    private static Method find(Class<?> type, String name, int args) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == args) { m.setAccessible(true); return m; }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static boolean tabsReady;
    private static Boolean sEnabled;
    /** Read once per Photos process (the switch force-stops Photos when it changes). Unreadable settings count as on. */
    static boolean enabled(Context c) {
        if (sEnabled == null) {
            boolean on;
            try { on = Feature.on(c, Feature.PHOTOS_DOODLES); } catch (Throwable unreadable) { on = true; }
            sEnabled = on;
        }
        return sEnabled;
    }

    static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) { if (c instanceof Activity) return (Activity) c; c = ((ContextWrapper) c).getBaseContext(); }
        return null;
    }

    // ------------------------------------------------------------------ the tab

    static void addTab(final View container) {
        try {
            if (!(container instanceof ViewGroup) || ((ViewGroup) container).getChildCount() == 0) return;
            if (!(((ViewGroup) container).getChildAt(0) instanceof LinearLayout)) return;
            final LinearLayout list = (LinearLayout) ((ViewGroup) container).getChildAt(0);
            if (list.findViewWithTag(TAG) != null) return;
            Context c = list.getContext();
            final Activity a = activityOf(c);
            if (a == null || !enabled(c)) return;
            String crop = text(c, "photos_photoeditor_fragments_editor3_crop_label");
            TextView cropTab = null;
            for (int i = 0; i < list.getChildCount(); i++) {
                View v = list.getChildAt(i);
                if (v instanceof TextView && crop != null && crop.contentEquals(((TextView) v).getText())) { cropTab = (TextView) v; break; }
            }
            if (cropTab == null) return;   // not built yet (the layout listener tries again)
            TextView tab = null;
            int layout = c.getResources().getIdentifier("photos_photoeditor_fragments_editor3_tool_tab", "layout", c.getPackageName());
            if (layout != 0) try { View made = LayoutInflater.from(c).inflate(layout, null); if (made instanceof TextView) tab = (TextView) made; } catch (Throwable ignored) { }
            if (tab == null) {
                tab = new TextView(c);
                tab.setTextSize(0, cropTab.getTextSize()); tab.setTypeface(cropTab.getTypeface());
                tab.setPadding(cropTab.getPaddingLeft(), cropTab.getPaddingTop(), cropTab.getPaddingRight(), cropTab.getPaddingBottom());
                tab.setGravity(cropTab.getGravity());
            }
            tab.setTag(TAG);
            tab.setText("Doodles");
            tab.setContentDescription("Doodles tab");
            tab.setCompoundDrawablesRelative(null, null, null, null);
            tab.setTextColor(restColor(list, cropTab));
            final TextView mine = tab;
            tab.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggle(a, container, mine); } });
            ViewGroup.LayoutParams lp = cropTab.getLayoutParams();
            list.addView(tab, list.indexOfChild(cropTab) + 1, lp instanceof LinearLayout.LayoutParams ? new LinearLayout.LayoutParams((LinearLayout.LayoutParams) lp) : new LinearLayout.LayoutParams(-2, -2));
            preview(a);
        } catch (Throwable error) { logOnce("Doodles tab could not be added", error); }
    }

    static String text(Context c, String name) {
        int id = c.getResources().getIdentifier(name, "string", c.getPackageName());
        return id == 0 ? null : c.getString(id);
    }

    /** The colour most tabs have (the tabs that are not selected). */
    static int restColor(LinearLayout list, TextView fallback) {
        Map<Integer, Integer> count = new HashMap<Integer, Integer>();
        int best = fallback.getCurrentTextColor(), most = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i); if (!(v instanceof TextView) || TAG.equals(v.getTag())) continue;
            int color = ((TextView) v).getCurrentTextColor();
            Integer n = count.get(color); n = n == null ? 1 : n + 1; count.put(color, n);
            if (n > most) { most = n; best = color; }
        }
        return best;
    }
    /** The selected tab's colour: the one colour only one tab has. */
    static int selectedColor(LinearLayout list, int rest) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i); if (!(v instanceof TextView) || TAG.equals(v.getTag())) continue;
            int color = ((TextView) v).getCurrentTextColor(); if (color != rest) return color;
        }
        return Color.WHITE;
    }

    static void toggle(Activity a, View container, TextView tab) {
        if (DoodleOverlay.active()) { DoodleOverlay.leave(true); return; }
        int[] at = new int[2]; container.getLocationOnScreen(at);
        openFrom(a, container, tab, at[1] + DoodleOverlay.dpOf(a, 36));
    }

    /** Opens Doodles over the editor. tabs / tab: the View tab bar and our tab (older editor), or null (Compose tab row). */
    static boolean openFrom(Activity a, View tabs, TextView tab, float toolbarRawY) {
        try {
            cache = null;
            if (space(a, a.getWindow().getDecorView()) == null) return false;   // not placed yet, or a new crop since the shapes
            preview(a);
            DoodleOverlay.open(a, new Host(a, tabs, tab), toolbarRawY);
            begin();
            return true;
        } catch (Throwable error) { logOnce("Doodles could not open", error); return false; }
    }

    // ------------------------------------------------------------------ where the picture is

    private static Cache cache;
    private static final class Cache { long at; View overlay; DoodleOverlay.Bridge.Space space; }

    /** Image units <-> the overlay's coordinates; null when the picture is not shown or changed shape since the shapes were drawn. */
    static DoodleOverlay.Bridge.Space space(Activity a, View overlay) {
        long now = android.os.SystemClock.uptimeMillis();
        Cache c = cache;
        if (c != null && c.overlay == overlay && now - c.at < 24) return c.space;
        DoodleOverlay.Bridge.Space s = compute(a, overlay);
        Cache n = new Cache(); n.at = now; n.overlay = overlay; n.space = s; cache = n;
        return s;
    }

    private static DoodleOverlay.Bridge.Space compute(Activity a, View overlay) {
        try {
            RectF rect = v2Rect;   // the current editor (renderer V2), kept up to date every frame it draws
            if (rect == null) rect = askV2();
            if (rect == null) {
                Object r = renderer.get();
                if (r == null || screenRect == null) return null;   // no renderer seen yet
                rect = (RectF) screenRect.invoke(r, pipelineParams.invoke(r));
            }
            if (rect == null || rect.width() < 2 || rect.height() < 2) return null;
            View surface = surface(a); if (surface == null) return null;
            android.graphics.Matrix m = v2Rect != null ? square(v2ToImage) : null;
            if (m != null) {
                // The photo's own coordinates: overlay -> surface -> image.
                int[] ls = new int[2], lo = new int[2]; surface.getLocationOnScreen(ls); overlay.getLocationOnScreen(lo);
                float dx = ls[0] - lo[0], dy = ls[1] - lo[1];
                DoodleOverlay.Bridge.Space s = new DoodleOverlay.Bridge.Space();
                s.toImage.setTranslate(-dx, -dy);
                s.toImage.postConcat(m);
                if (!s.toImage.invert(s.toScreen)) return null;
                s.image.set(rect); s.image.offset(dx, dy);
                s.scale = s.toScreen.mapRadius(1f);
                imageSpace.put(a, Boolean.TRUE);
                return s;
            }
            float aspect = rect.height() / rect.width();
            synchronized (DoodleOverlay.models) {
                List<Doodle> shapes = DoodleOverlay.model(a);
                Float was = aspects.get(a);
                if (shapes.isEmpty() || was == null) aspects.put(a, aspect);
                else if (Math.abs(was - aspect) / was > 0.02f) return null;   // a different crop: the shapes do not belong here
            }
            int[] ls = new int[2], lo = new int[2]; surface.getLocationOnScreen(ls); overlay.getLocationOnScreen(lo);
            float dx = ls[0] - lo[0], dy = ls[1] - lo[1], k = rect.width() / REF;
            DoodleOverlay.Bridge.Space s = new DoodleOverlay.Bridge.Space();
            s.toScreen.setScale(k, k);
            s.toScreen.postTranslate(rect.left + dx, rect.top + dy);
            if (!s.toScreen.invert(s.toImage)) return null;
            s.image.set(rect); s.image.offset(dx, dy);
            s.scale = k;
            return s;
        } catch (Throwable error) { logOnce("picture position unavailable", error); return null; }
    }

    private static WeakReference<View> surfaceRef = new WeakReference<View>(null);
    /** The editor's picture surface: the largest visible SurfaceView or TextureView in the window. */
    static View surface(Activity a) {
        View s = surfaceRef.get();
        if (s != null && s.isAttachedToWindow() && s.isShown()) return s;
        View[] best = new View[1];
        largest(a.getWindow().getDecorView(), best);
        surfaceRef = new WeakReference<View>(best[0]);
        if (best[0] != null && Xp.getAdditionalInstanceField(best[0], "pixelsuite_watched") == null) {
            Xp.setAdditionalInstanceField(best[0], "pixelsuite_watched", Boolean.TRUE);
            best[0].addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View v) { }
                public void onViewDetachedFromWindow(View v) { main.postDelayed(endCheck, 400); }
            });
        }
        return best[0];
    }
    private static void largest(View v, View[] best) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof SurfaceView || v instanceof android.view.TextureView) {
            if (best[0] == null || v.getWidth() * v.getHeight() > best[0].getWidth() * best[0].getHeight()) best[0] = v;
            return;
        }
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) largest(g.getChildAt(i), best); }
    }

    // ------------------------------------------------------------------ the shapes on screen while Doodles is closed

    static void preview(Activity a) {
        Preview v = previews.get(a);
        if (v != null && v.getParent() != null) return;
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
        if (content == null) return;
        v = new Preview(a);
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
        previews.put(a, v);
    }

    /** Draws the shapes over the picture; takes no touches. Follows the picture while it is being moved. */
    static final class Preview extends View {
        final Activity activity; long until;
        Preview(Activity a) { super(a); activity = a; setWillNotDraw(false); setClickable(false); setFocusable(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override public boolean onTouchEvent(MotionEvent e) { return false; }
        /*
         * A touch: follow the picture for a moment. Only with shapes to show and Doodles closed (otherwise there is
         * nothing to draw), so touches elsewhere in Photos (the grid under a left-behind preview) cost no frames.
         */
        void kick() {
            until = android.os.SystemClock.uptimeMillis() + 900;
            if (!DoodleOverlay.active() && DoodleOverlay.hasShapes(activity)) postInvalidateOnAnimation();
        }
        @Override protected void onDraw(Canvas canvas) {
            if (DoodleOverlay.active()) return;   // the open Doodles layer draws them
            List<Doodle> shapes;
            synchronized (DoodleOverlay.models) { shapes = DoodleOverlay.models.get(activity); }
            if (shapes == null || shapes.isEmpty()) return;
            DoodleOverlay.drawShapes(canvas, space(activity, this), shapes);
            if (android.os.SystemClock.uptimeMillis() < until) postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------------------ the host for DoodleOverlay

    static final class Host implements DoodleOverlay.Host {
        final Activity activity; final View tabs; final TextView tab; int rest;
        Host(Activity a, View tabs, TextView tab) { activity = a; this.tabs = tabs; this.tab = tab; editor = new WeakReference<Activity>(a); }
        public Object key() { return activity; }
        public DoodleOverlay.Bridge.Space space(View overlay) { return PhotosEditor.space(activity, overlay); }
        public void picture() { Preview p = previews.get(activity); if (p != null) p.invalidate(); }
        public void selected(boolean on) {
            if (tab != null) try {
                LinearLayout list = (LinearLayout) tab.getParent();
                if (on) { rest = tab.getCurrentTextColor(); tab.setTextColor(selectedColor(list, rest)); }
                else tab.setTextColor(rest != 0 ? rest : restColor(list, tab));
            } catch (Throwable ignored) { }
            picture();
        }
        public void history() { PhotosSave.changed(activity); }   // preview18: Photos offers Save for doodles alone
        public boolean drawsShapes() { return false; }
        /**
         * The owner's place for the menu (screenshot of the editor, preview10): the whole band between the picture's
         * bottom corner brackets and the tab row, edge to edge. As fractions of the screen, so it follows its size.
         */
        public RectF[] slots(View overlay) {
            View root = overlay.getRootView();
            float w = root.getWidth(), h = root.getHeight();
            if (w <= 0 || h <= 0) return null;
            int[] at = new int[2]; overlay.getLocationOnScreen(at); int[] r = new int[2]; root.getLocationOnScreen(r);
            float dx = r[0] - at[0], dy = r[1] - at[1];
            RectF place = new RectF(0.0060f * w + dx, 0.7400f * h + dy, 0.9930f * w + dx, 0.8875f * h + dy);
            return new RectF[]{place, place};
        }
        public boolean fixed() { return true; }
        /**
         * preview12 (owner's video: dragging the photo's edges drew a shape instead of cropping): a touch within 28dp
         * of the picture's edges (either side of them) that is not on a shape goes to the editor, which crops with it.
         * Shapes are still drawn from anywhere else on the picture.
         */
        public boolean editorTouch(DoodleOverlay.Bridge.Space s, float x, float y) {
            float band = DoodleOverlay.dpOf(activity, 28);
            RectF r = s.image;
            boolean nearX = x > r.left - band && x < r.right + band, nearY = y > r.top - band && y < r.bottom + band;
            if (!nearX || !nearY) return false;
            return Math.abs(x - r.left) < band || Math.abs(x - r.right) < band || Math.abs(y - r.top) < band || Math.abs(y - r.bottom) < band;
        }
        /** Another tab (or the tab bar) was touched: Doodles closes and the editor takes the touch. */
        public void outside(float x, float y) {
            if (tabs == null) return;   // Compose tab row: choosing another tab closes Doodles (PhotosTabs)
            if (inside(tab, x, y)) return;
            if (inside(tabs, x, y)) DoodleOverlay.leave(false);
        }
        static boolean inside(View v, float x, float y) {
            if (v == null || !v.isShown()) return false;
            int[] at = new int[2]; v.getLocationOnScreen(at);
            return new Rect(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight()).contains((int) x, (int) y);
        }
    }

    // ------------------------------------------------------------------ the saved picture

    /**
     * Paints the shapes into a picture Photos made for saving or sharing. Only a picture with the same shape as the
     * one the doodles were drawn on (a 3% margin for rounding), and each picture once. A gain map (Ultra HDR) gets the
     * shapes as "no brightening", so they keep their colours instead of glowing.
     */
    static Bitmap paint(Bitmap b, boolean gainmap) {
        if (b == null || b.isRecycled()) return b;
        Activity a = editor.get();
        List<Doodle> shapes = a == null ? java.util.Collections.<Doodle>emptyList() : DoodleOverlay.snapshot(a);
        RectF shownNow = v2Rect != null ? v2Rect : keptRect;
        android.graphics.Matrix mNow = v2Rect != null ? v2ToImage : keptMatrix;
        boolean inImage = a != null && Boolean.TRUE.equals(imageSpace.get(a));
        Float aspect = a == null ? null : aspects.get(a);
        String key = a == null ? null : photoKeys.get(a);
        if (shapes.isEmpty()) {   // preview14: the editor may already be closed while Photos renders the saved copy
            Held h = held;
            if (h == null || android.os.SystemClock.uptimeMillis() - h.at > 90000) {
                // preview15: every shape that was brought back was deleted and the photo is saved: forget them too.
                if (a != null && !gainmap && b.getWidth() >= 256 && Boolean.TRUE.equals(restored.get(a))) { Kept.drop(a, key); restored.remove(a); }
                return b;
            }
            shapes = new java.util.ArrayList<Doodle>(); for (Doodle d : h.shapes) shapes.add(d.copy());
            shownNow = h.rect; mNow = h.matrix; inImage = h.imageSpace; aspect = h.aspect; key = h.key;
        }
        if (shapes.isEmpty()) return b;
        if (painted.containsKey(b)) return b;
        android.graphics.Matrix toOut = null;
        if (inImage) {
            // photo coordinates -> screen (the picture as last shown) -> this output picture
            RectF shown = shownNow; android.graphics.Matrix m = square(mNow);
            if (shown == null || m == null || shown.width() < 2) { logOnce("picture position unknown at saving, doodles left out", null); return b; }
            if (Math.abs(shown.height() / shown.width() - (float) b.getHeight() / b.getWidth()) / (shown.height() / shown.width()) > 0.03f) {
                if (!gainmap) logOnce("picture of a different shape, doodles left out", null);
                return b;
            }
            toOut = new android.graphics.Matrix();
            if (!m.invert(toOut)) return b;
            toOut.postTranslate(-shown.left, -shown.top);
            toOut.postScale(b.getWidth() / shown.width(), b.getHeight() / shown.height());
        }
        float mine = (float) b.getHeight() / b.getWidth();
        if (toOut == null && (aspect == null || Math.abs(aspect - mine) / aspect > 0.03f)) { if (!gainmap) logOnce("picture of a different shape, doodles left out", null); return b; }
        if (!gainmap && b.getWidth() < 256) return b;   // a thumbnail
        Bitmap out = b;
        boolean hardware = b.getConfig() != null && "HARDWARE".equals(b.getConfig().name());
        if (!b.isMutable() || hardware) {
            Bitmap.Config config = b.getConfig() == null || hardware ? Bitmap.Config.ARGB_8888 : b.getConfig();
            out = b.copy(config, true);
            if (out == null) return b;
            try {   // keep an Ultra HDR gain map (API 34; by reflection, the build's SDK is older)
                Object map = Bitmap.class.getMethod("getGainmap").invoke(b);
                if (map != null) Bitmap.class.getMethod("setGainmap", map.getClass()).invoke(out, map);
            } catch (Throwable noGainmap) { }
        }
        Canvas canvas = new Canvas(out);
        if (toOut != null) canvas.concat(toOut);
        else { float k = out.getWidth() / REF; canvas.scale(k, k); }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (gainmap) {
            if (out.getConfig() == Bitmap.Config.ALPHA_8) paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            else paint.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));
        }
        for (Doodle d : shapes) d.draw(canvas, paint);
        painted.put(out, Boolean.TRUE);
        if (!gainmap && toOut != null) Kept.store(key, shapes);   // preview15: editable again next time this photo is edited
        return out;
    }

}
