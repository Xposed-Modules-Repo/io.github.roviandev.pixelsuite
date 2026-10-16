package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-app drag. While one finger moves an app (home screen, a folder or the app drawer), a tap
 * with another finger on more apps picks them up too: each one flies to the held app and joins a
 * small stack under it, and a count badge on the held app shows how many apps are being moved.
 * The stack trails the finger with a soft ghost. On drop the extra apps go where the held app went:
 *  - into the same folder (home folder, or drawer folder / new drawer folder);
 *  - out of drawer folders, when the held app was taken out of one;
 *  - onto the same home page, in the free cells nearest the drop.
 * Home apps that were picked up move (the old icon is removed); drawer apps are added as
 * shortcuts, as the stock drag from the drawer does. If the drop is cancelled the apps fly back.
 */
final class MultiDrag {
    static final String KEY = "pixelsuite_multi_drag";
    static final PathInterpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final PathInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    static final class Pick {
        View view; Object info; String key; Bitmap icon; final RectF from = new RectF();
        float alpha, scaleX, scaleY; long added; float x, y; boolean placed;
    }

    static final List<Pick> picks = new ArrayList<Pick>();
    private static StackView stack; private static Badge badge; private static ViewGroup badgeHost;
    private static Object primaryInfo; private static String primaryKey;
    private static boolean drawerHandled, workspaceDropped, ending; private static Object droppedFolder;
    private static Object buttonDropped; private static View removeButton, uninstallButton; private static boolean barShifted;
    private static int dropX, dropY;           // DragLayer coordinates of the last drag position
    private static boolean looping;
    private static int generation;

    private MultiDrag() {}

    static boolean on() { return LauncherFeatures.on(KEY); }
    private static Object get(Object o, String n) { return LauncherFeatures.get(o, n); }
    private static Object call(Object o, String n, Object... v) { return LauncherFeatures.call(o, n, v); }
    private static Class<?> type(String n) { return LauncherFeatures.type(n); }
    static boolean active() { return !picks.isEmpty(); }

    // ---- hooks ---------------------------------------------------------------------------

    static void install() {
        try {
            Xp.hookAllMethods(type("Workspace"), "onDrop", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) { if (active()) workspaceDropped = true; }
            });
            Xp.hookAllMethods(type("folder.Folder"), "onDrop", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    if (active() && !DrawerFolders.isFolder(get(p.thisObject, "mInfo"))) droppedFolder = p.thisObject;
                }
            });
            try {
            Xp.hookAllMethods(type("ButtonDropTarget"), "onDragStart", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) { try { frost((View) p.thisObject); } catch (Throwable error) { LauncherFeatures.logOnce("Remove/Uninstall frosting unavailable", error); } }
            });
            Xp.hookAllMethods(type("ButtonDropTarget"), "onDragEnter", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) { try { hover((View) p.thisObject, true); } catch (Throwable error) { LauncherFeatures.logOnce("Remove/Uninstall highlight unavailable", error); } }
            });
            Xp.hookAllMethods(type("ButtonDropTarget"), "onDragExit", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) { try { hover((View) p.thisObject, false); } catch (Throwable ignored) { } }
            });
            Xp.hookAllMethods(type("ButtonDropTarget"), "onDrop", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) { if (active()) { buttonDropped = p.thisObject; why("dropped on " + p.thisObject.getClass().getSimpleName()); } }
            });
            Xp.Callback noUninstall = new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) { if (active() && p.thisObject == uninstallButton) p.setResult(false); }
            };
            Xp.hookAllMethods(type("ButtonDropTarget"), "isDropEnabled", noUninstall);
            Xp.hookAllMethods(type("ButtonDropTarget"), "acceptDrop", noUninstall);
            } catch (Throwable error) { LauncherFeatures.logOnce("Multi-app drag: Remove/Uninstall buttons unavailable", error); }
            Xp.hookAllMethods(type("dragndrop.DragController"), "callOnDragEnd", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) { try { restoreStarters(); ended(); } catch (Throwable error) { Module.log("Multi-app drag end failed", error); reset(); } }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Multi-app drag unavailable", error); }
    }

    private static final java.util.HashSet<String> told = new java.util.HashSet<String>();
    /** Logs why a second-finger tap did nothing, once per reason. */
    static void why(String reason) { if (told.add(reason)) Module.log("Multi-app drag: " + reason); }

    // ---- picking -------------------------------------------------------------------------

    /** A second-finger tap at window coordinates (x, y) while an app is being dragged. */
    static void tap(Activity a, float x, float y) {
        try {
            if (ending) { why("tap ignored: previous drop still finishing"); return; }
            Object controller = get(a, "mDragController"), drag = get(controller, "mDragObject");
            if (drag == null) { why("tap ignored: no drag object"); return; }
            Object info = get(drag, "dragInfo");
            if (info == null || type("model.data.FolderInfo").isInstance(info) || DrawerFolders.isFolder(info)) { why("tap ignored: held item is " + (info == null ? "null" : info.getClass().getSimpleName()) + " (folders move alone)"); return; }
            if (picks.isEmpty()) {
                primaryInfo = info; primaryKey = LauncherFeatures.identity(info);
                drawerHandled = false; workspaceDropped = false; droppedFolder = null;
            }
            View hit = findApp(a.getWindow().getDecorView(), Math.round(x), Math.round(y));
            if (hit == null) { why("tap ignored: no app icon under the second finger at " + Math.round(x) + "," + Math.round(y)); return; }
            Object tag = hit.getTag(); String key = LauncherFeatures.identity(tag);
            if (key == null || tag == info || tag == primaryInfo) { why("tap ignored: that icon is the held app or has no identity"); return; }
            boolean drawer = drawerItem(tag);
            // Selection never mixes sources. Apps held out of a drawer folder only join with apps of that same
            // folder; from the drawer, the top featured apps (copies of the same apps below) and folder members
            // are off limits.
            boolean inFolder = DrawerFolders.isDrawerMember(tag);
            if (DrawerDrag.fromFolder() && !inFolder) { why("tap ignored: apps held from a folder can only join with apps of the same folder"); return; }
            if (DrawerDrag.fromFolder() && !DrawerDrag.inHeldFolder(hit)) { why("tap ignored: app belongs to another folder"); return; }
            if (DrawerDrag.active() && !DrawerDrag.fromFolder() && (inFolder || !drawer || featured(hit, tag))) { why("tap ignored: featured apps and folder apps cannot be picked from the drawer"); return; }
            if (!DrawerDrag.active() && inFolder) { why("tap ignored: folder apps cannot join a drag that did not start in the folder"); return; }
            if (drawer && key.equals(primaryKey)) return;                    // the held app itself
            for (Pick old : picks) if (old.view == hit || (drawer && key.equals(old.key))) return;
            Pick pick = new Pick(); pick.view = hit; pick.info = tag; pick.key = key; pick.added = SystemClock.uptimeMillis();
            pick.icon = iconOf(hit, pick.from);
            if (pick.icon == null) { why("tap ignored: could not copy the icon picture"); return; }
            pick.alpha = hit.getAlpha(); pick.scaleX = hit.getScaleX(); pick.scaleY = hit.getScaleY();
            picks.add(pick);
            if (!attach(a)) { picks.remove(pick); why("tap ignored: could not attach the stack to the drag layer"); return; }
            // Picked apps leave their place completely; rebound() keeps that true when the drawer list recycles its views.
            Xp.setAdditionalInstanceField(hit, "pixelsuite_picked", Boolean.TRUE);
            float left = 0f;
            hit.animate().alpha(left).scaleX(.82f).scaleY(.82f).setDuration(160).setInterpolator(STANDARD).start();
            badge.count(1 + picks.size());
            shiftBar(a, true);
            Haptics.tick(a);
            loop();
            Module.log("Multi-app drag: " + (1 + picks.size()) + " apps");
        } catch (Throwable error) { LauncherFeatures.logOnce("Multi-app pick failed", error); }
    }

    /** The drawer list bound {@code v} to an app: hide it if that app is picked, show it again if it is not. */
    static void rebound(View v) {
        boolean flagged = Boolean.TRUE.equals(Xp.getAdditionalInstanceField(v, "pixelsuite_picked"));
        boolean starter = Boolean.TRUE.equals(Xp.getAdditionalInstanceField(v, "pixelsuite_starter"));
        Object tag = v.getTag(); String key = tag == null ? null : LauncherFeatures.identity(tag);
        // The app being dragged out of the drawer: the list recycled its cell while scrolling away and
        // back, which left a second copy of the icon behind the one under the finger.
        String held = heldDrawerKey();
        boolean isStarter = held != null && key != null && key.equals(held) && drawerItem(tag);
        if (isStarter) {
            v.animate().cancel(); v.setAlpha(0f);
            if (!starter) { Xp.setAdditionalInstanceField(v, "pixelsuite_starter", Boolean.TRUE); starters.add(new java.lang.ref.WeakReference<View>(v)); }
            return;
        }
        if (starter) { Xp.setAdditionalInstanceField(v, "pixelsuite_starter", null); if (!flagged) v.setAlpha(1f); }
        if (picks.isEmpty() && !flagged) return;
        Pick match = null;
        for (Pick p : picks) if (drawerItem(p.info) ? key != null && key.equals(p.key) : p.view == v && p.info == tag) match = p;
        v.animate().cancel();
        if (match != null) {
            match.view = v; Xp.setAdditionalInstanceField(v, "pixelsuite_picked", Boolean.TRUE);
            v.setAlpha(0f); v.setScaleX(.82f); v.setScaleY(.82f);
        } else if (flagged) {
            Xp.setAdditionalInstanceField(v, "pixelsuite_picked", null);
            v.setAlpha(1f); v.setScaleX(1f); v.setScaleY(1f);
        }
    }

    private static final java.util.ArrayList<java.lang.ref.WeakReference<View>> starters = new java.util.ArrayList<java.lang.ref.WeakReference<View>>();

    /** Identity of the drawer app being dragged right now, else null. */
    private static String heldDrawerKey() {
        try {
            // Only while a drawer drag is really running: DragController keeps mDragObject (the last dragged app)
            // after the drag ends, which used to hide that app's cell in the drawer for good.
            if (!DrawerDrag.active() || !drawerOpenDrag()) return null;
            Activity a = LauncherFeatures.activity.get(); if (a == null) return null;
            Object drag = get(get(a, "mDragController"), "mDragObject"); if (drag == null) return null;
            Object info = get(drag, "dragInfo");
            return info != null && drawerItem(info) ? LauncherFeatures.identity(info) : null;
        } catch (Throwable ignored) { return null; }
    }

    private static boolean drawerOpenDrag() {
        try { Activity a = LauncherFeatures.activity.get(); return a != null && Boolean.TRUE.equals(call(get(a, "mDragController"), "isDragging")); } catch (Throwable t) { return false; }
    }

    /** The drag is over: the starter's cell shows again unless it is a picked app that is still leaving. */
    private static void restoreStarters() {
        for (java.lang.ref.WeakReference<View> r : starters) {
            View v = r.get(); if (v == null) continue;
            Xp.setAdditionalInstanceField(v, "pixelsuite_starter", null);
            if (!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(v, "pixelsuite_picked"))) { v.animate().cancel(); v.setAlpha(1f); }
        }
        starters.clear();
    }

    /** A top "featured" (predicted) app: not an item of the A-Z list. */
    private static boolean featured(View v, Object tag) {
        try { if (((Number) get(tag, "container")).intValue() == -176) return true; } catch (Throwable ignored) { }
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) if (p.getClass().getSimpleName().toLowerCase().contains("predict")) return true;
        return false;
    }

    /** Dropped on the Remove pill: the held app and the apps picked from its folder leave the folder. */
    static void takeOut(String heldKey) {
        List<String> keys = new ArrayList<String>();
        if (heldKey != null) keys.add(heldKey);
        for (Pick p : picks) if (drawerItem(p.info) && p.key != null && !keys.contains(p.key)) keys.add(p.key);
        if (DrawerFolders.takeOut(keys)) drawerHandled = true;
        Module.log("Drawer folder: " + keys.size() + " app(s) removed from the folder");
    }

    static boolean drawerItem(Object tag) { return type("model.data.AppInfo").isInstance(tag) || DrawerFolders.isDrawerMember(tag); }

    /** The app icon (not a folder, not the dragged view) under window point (x, y), topmost first. */
    static View findApp(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < .05f) return null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) { View hit = findApp(g.getChildAt(i), x, y); if (hit != null) return hit; }
        }
        if (!type("BubbleTextView").isInstance(v)) return null;
        Object tag = v.getTag(); if (tag == null || LauncherFeatures.privateItem(tag)) return null;
        try { if (((Number) get(tag, "itemType")).intValue() != 0) return null; } catch (Throwable t) { return null; }
        if (LauncherFeatures.identity(tag) == null) return null;
        Rect r = new Rect(); if (!v.getGlobalVisibleRect(r) || !r.contains(x, y) || !v.isShown()) return null;
        return v;
    }

    /** The app's icon as a bitmap, and its on-screen square in {@code out} (window coordinates). */
    static Bitmap iconOf(View v, RectF out) {
        try {
            TextView text = (TextView) v; Drawable d = text.getCompoundDrawables()[1];
            int size; try { size = ((Number) get(v, "mIconSize")).intValue(); } catch (Throwable t) { size = d == null ? 0 : d.getBounds().width(); }
            if (d == null || size <= 0) return null;
            float scale = v.getScaleX();
            Bitmap b = null;
            // The launcher's icons are hardware bitmaps, which a software canvas refuses to draw: take the
            // icon's own bitmap and copy it to a software one; draw the drawable only when it holds none.
            Bitmap own = findBitmap(d, 0);
            if (own != null) {
                try { b = own.copy(Bitmap.Config.ARGB_8888, false); if (b != null && (b.getWidth() != size || b.getHeight() != size)) { Bitmap fit = Bitmap.createScaledBitmap(b, size, size, true); if (fit != b) { b.recycle(); b = fit; } } }
                catch (Throwable t) { why("icon bitmap copy failed: " + t); b = null; }
            }
            if (b == null) {
                try { b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); Rect old = d.copyBounds(); d.setBounds(0, 0, size, size); d.draw(new Canvas(b)); d.setBounds(old); }
                catch (Throwable t) { why("icon draw failed (" + d.getClass().getName() + "): " + t.getMessage()); return null; }
            }
            int[] at = new int[2]; v.getLocationInWindow(at);
            float left = at[0] + (v.getWidth() - size) / 2f * scale, top = at[1] + v.getPaddingTop() * scale;
            out.set(left, top, left + size * scale, top + size * scale);
            return shaped(b);
        } catch (Throwable error) { return null; }
    }

    private static String maskPackage, maskData; private static android.graphics.Path maskPath;
    /** The icon-shape mask path (100x100) that is chosen right now: read from the shape overlay itself, because the system's own copy can lag behind a change. */
    private static android.graphics.Path currentMask(Context c) {
        try {
            String json = android.provider.Settings.Secure.getString(c.getContentResolver(), "theme_customization_overlay_packages");
            String pkg = json == null ? "" : new org.json.JSONObject(json).optString("android.theme.customization.adaptive_icon_shape", "");
            if (maskPath != null && pkg.equals(maskPackage)) return maskPath;
            String data = null;
            if (!pkg.isEmpty()) {
                Resources r = c.createPackageContext(pkg, 0).getResources();
                int id = r.getIdentifier("config_icon_mask", "string", pkg); if (id == 0) id = r.getIdentifier("config_icon_mask", "string", "android");
                if (id != 0) data = r.getString(id);
            }
            if (data == null) { Resources sys = Resources.getSystem(); data = sys.getString(sys.getIdentifier("config_icon_mask", "string", "android")); }
            android.graphics.Path path = (android.graphics.Path) Class.forName("android.util.PathParser").getMethod("createPathFromPathData", String.class).invoke(null, data);
            maskPackage = pkg; maskData = data; maskPath = path;
            Module.log("Multi-app drag: icon shape " + (pkg.isEmpty() ? "default" : pkg));
            return path;
        } catch (Throwable error) { why("icon shape unavailable: " + error); return null; }
    }

    /** The icon cut to the shape chosen in Wallpaper & style > Icons. */
    private static Bitmap shaped(Bitmap b) {
        try {
            Activity a = LauncherFeatures.activity.get(); android.graphics.Path mask = a == null ? null : ShapeWatch.path(a);
            if (mask == null && a != null) mask = currentMask(a);
            if (mask == null) return b;
            int n = b.getWidth();
            Bitmap out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.save(); canvas.scale(n / 100f, n / 100f); canvas.drawPath(mask, paint); canvas.restore();
            paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(b, 0, 0, paint);
            b.recycle();
            return out;
        } catch (Throwable error) { why("icon shaping failed: " + error); return b; }
    }

    /** The first Bitmap held by {@code o} or the objects it points to (two levels), skipping tiny ones. */
    private static Bitmap findBitmap(Object o, int depth) {
        if (o == null || depth > 2) return null;
        if (o instanceof Bitmap) { Bitmap b = (Bitmap) o; return b.getWidth() >= 16 && b.getHeight() >= 16 && !b.isRecycled() ? b : null; }
        for (Class<?> c = o.getClass(); c != null && c != Object.class && c != Drawable.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType().isEnum()) continue;
                String type = f.getType().getName();
                if (type.startsWith("java.") || type.startsWith("android.content") || type.startsWith("android.os") || type.equals("android.graphics.Paint") || type.equals("android.graphics.Rect")) continue;
                try {
                    f.setAccessible(true); Object v = f.get(o);
                    if (v instanceof Bitmap || (v instanceof Drawable) || type.startsWith("com.android.launcher3")) { Bitmap b = findBitmap(v, depth + 1); if (b != null) return b; }
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    // ---- views ---------------------------------------------------------------------------

    private static boolean attach(Activity a) {
        ViewGroup layer = (ViewGroup) call(a, "getDragLayer");
        View dragView = dragView(a);
        if (stack == null || stack.getParent() != layer) {
            detach();
            stack = new StackView(a);
            Object params = call(layer, "generateLayoutParams", new FrameLayout.LayoutParams(-1, -1));
            try { LauncherFeatures.set(params, "ignoreInsets", true); } catch (Throwable ignored) { }
            int index = dragView != null && dragView.getParent() == layer ? layer.indexOfChild(dragView) : layer.getChildCount();
            layer.addView(stack, index, (ViewGroup.LayoutParams) params);
            badgeHost = (ViewGroup) a.getWindow().getDecorView();
            badge = new Badge(a); badge.setBounds(0, 0, badgeHost.getWidth(), badgeHost.getHeight());
            badgeHost.getOverlay().add(badge);
        }
        return true;
    }

    private static void detach() {
        if (stack != null && stack.getParent() instanceof ViewGroup) ((ViewGroup) stack.getParent()).removeView(stack);
        if (badge != null && badgeHost != null) { badgeHost.getOverlay().remove(badge); badgeHost.invalidate(); }
        stack = null; badge = null; badgeHost = null;
    }

    static View dragView(Activity a) {
        try { Object drag = get(get(a, "mDragController"), "mDragObject"); return drag == null ? null : (View) get(drag, "dragView"); }
        catch (Throwable t) { return null; }
    }

    /** Called on finger moves: keeps the drop point current. */
    static void follow() {
        if (!active() || ending) return;
        Activity a = LauncherFeatures.activity.get(); if (a == null) return;
        try { Object drag = get(get(a, "mDragController"), "mDragObject"); if (drag != null) { dropX = ((Number) get(drag, "x")).intValue(); dropY = ((Number) get(drag, "y")).intValue(); } }
        catch (Throwable ignored) { }
    }

    private static void loop() {
        if (looping) return; looping = true;
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            public void doFrame(long nanos) {
                if (stack == null) { looping = false; return; }
                stack.frame(); stack.invalidate(); if (badgeHost != null) badgeHost.invalidate();
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    // ---- drop ----------------------------------------------------------------------------

    /** The drawer drop (DrawerDrag) put the held app somewhere: the picked drawer apps follow it. */
    /** The held group was dropped inside its own drawer folder and FolderReorder already saved the order. */
    static void handledInFolder() { if (active()) drawerHandled = true; }

    static void drawerDrop() {
        if (!active()) return;
        try {
            List<LauncherData.Folder> folders = LauncherFeatures.data.folders();
            LauncherData.Folder target = null;
            for (LauncherData.Folder f : folders) if (primaryKey != null && f.apps.contains(primaryKey)) target = f;
            List<String> keys = new ArrayList<String>();
            for (Pick p : picks) if (drawerItem(p.info) && !keys.contains(p.key) && !p.key.equals(primaryKey)) keys.add(p.key);
            if (keys.isEmpty()) return;
            for (LauncherData.Folder f : folders) f.apps.removeAll(keys);
            if (target != null) { int at = target.apps.indexOf(primaryKey) + 1; target.apps.addAll(Math.max(0, Math.min(at, target.apps.size())), keys); }
            for (int i = folders.size() - 1; i >= 0; i--) if (folders.get(i).apps.isEmpty()) folders.remove(i);
            if (LauncherFeatures.data.save(folders)) { drawerHandled = true; LauncherFeatures.refresh(); }
            Module.log("Multi-app drag: " + keys.size() + " drawer apps " + (target == null ? "taken out of folders" : "moved into “" + target.name + "”"));
        } catch (Throwable error) { Module.log("Multi-app drawer drop failed", error); }
    }

    private static void ended() {
        if (!active() || ending) return;
        ending = true;
        final int session = generation;
        final Activity a = LauncherFeatures.activity.get();
        final boolean removing = buttonDropped != null && !drawerHandled && isRemoveButton(buttonDropped);
        final boolean success = drawerHandled || workspaceDropped || droppedFolder != null || removing;
        if (removing && a != null) {
            int n = 0;
            for (Pick p : picks) if (homeItem(p)) n++;
            removeOriginals(a, new ArrayList<Pick>(picks));
            Module.log("Multi-app drag: " + n + " more home apps removed");
        }
        if (stack != null) stack.finish(success);
        if (badge != null) badge.finish();
        if (success && !drawerHandled && !removing && a != null) {
            // Let the stock drop finish binding (a new folder icon, the held app's new cell) first.
            stack.postDelayed(new Runnable() { public void run() {
                if (session != generation) return;
                try { placeOnHome(a); } catch (Throwable error) { Module.log("Multi-app home drop failed", error); }
                restoreViews(); reset();
            }}, 320);
        } else {
            View host = stack;
            Runnable done = new Runnable() { public void run() { if (session == generation) { restoreViews(); reset(); } } };
            if (host != null) host.postDelayed(done, success ? 240 : 380); else done.run();
        }
    }

    private static void placeOnHome(Activity a) throws Exception {
        List<Pick> moving = new ArrayList<Pick>(picks);
        Object folder = droppedFolder;
        int container = -1;
        try { container = ((Number) get(primaryInfo, "container")).intValue(); } catch (Throwable ignored) { }
        if (folder == null && container >= 0) folder = folderById(a, container);
        ViewGroup layer = (ViewGroup) call(a, "getDragLayer");
        int[] base = new int[2]; layer.getLocationInWindow(base);
        int wx = dropX + base[0], wy = dropY + base[1];
        if (folder == null) { View icon = folderIconAt(a.getWindow().getDecorView(), wx, wy); if (icon != null) folder = get(icon, "mFolder"); }
        if (folder != null) { intoFolder(a, folder, moving); return; }
        onPage(a, moving, wx, wy);
    }

    /** New home item for a picked app: a fresh copy of the app (drawer apps are copied, home apps moved). */
    private static Object newItem(Activity a, Pick p) {
        try {
            if (type("model.data.AppInfo").isInstance(p.info)) return call(p.info, "makeWorkspaceItem", a);
            Object app = HomeFolderApps.find(LauncherFeatures.apps(), p.key);
            return app == null ? null : call(app, "makeWorkspaceItem", a);
        } catch (Throwable error) { Module.log("Multi-app drag: no copy for " + p.key, error); return null; }
    }

    private static boolean homeItem(Pick p) { return !drawerItem(p.info); }

    private static void intoFolder(Activity a, Object folder, List<Pick> moving) throws Exception {
        Object info = get(folder, "mInfo"); int id = FolderSort.homeId(info), added = 0; List<Pick> gone = new ArrayList<Pick>();
        for (Pick p : moving) {
            int from = -1; try { from = ((Number) get(p.info, "container")).intValue(); } catch (Throwable ignored) { }
            if (homeItem(p) && from == id) continue;                                     // already in this folder
            Object item = newItem(a, p); if (item == null) continue;
            HomeFolderApps.add(folder, info, item); added++;
            if (homeItem(p)) gone.add(p);
        }
        removeOriginals(a, gone);
        try { Object icon = get(folder, "mFolderIcon"); call(icon, "updatePreviewItems", false); ((View) icon).invalidate(); } catch (Throwable ignored) { }
        Module.log("Multi-app drag: " + added + " apps added to a home folder");
    }

    /** Free cells on the drop page, nearest to the drop point first. */
    private static void onPage(Activity a, List<Pick> moving, int wx, int wy) throws Exception {
        Object workspace = get(a, "mWorkspace");
        int pageIndex = ((Number) call(workspace, "getNextPage")).intValue();
        ViewGroup page = (ViewGroup) ((ViewGroup) workspace).getChildAt(pageIndex);
        int countX = ((Number) get(page, "mCountX")).intValue(), countY = ((Number) get(page, "mCountY")).intValue();
        boolean[][] used = new boolean[countX][countY];
        ViewGroup cells = (ViewGroup) get(page, "mShortcutsAndWidgets");
        int screenId = -1;
        for (int i = 0; i < cells.getChildCount(); i++) {
            View child = cells.getChildAt(i); Object lp = child.getLayoutParams();
            try {
                int x = ((Number) get(lp, "mCellX")).intValue(), y = ((Number) get(lp, "mCellY")).intValue();
                int w = Math.max(1, ((Number) get(lp, "cellHSpan")).intValue()), h = Math.max(1, ((Number) get(lp, "cellVSpan")).intValue());
                for (int xx = x; xx < Math.min(countX, x + w); xx++) for (int yy = y; yy < Math.min(countY, y + h); yy++) if (xx >= 0 && yy >= 0) used[xx][yy] = true;
            } catch (Throwable error) { throw new IllegalStateException("Cannot read occupied home cells", error); }
            if (screenId < 0) try { screenId = ((Number) get(child.getTag(), "screenId")).intValue(); } catch (Throwable ignored) { }
        }
        if (screenId < 0) try { screenId = ((Number) call(workspace, "getScreenIdForPageIndex", pageIndex)).intValue(); } catch (Throwable ignored) { }
        if (screenId < 0) try { screenId = ((Number) call(workspace, "getIdForScreen", page)).intValue(); } catch (Throwable ignored) { }
        if (screenId < 0) { Module.log("Multi-app drag: home page id unknown, extra apps not placed"); return; }
        // The drop cell, from the page's on-screen grid.
        int[] at = new int[2]; page.getLocationInWindow(at);
        float cw = (page.getWidth() - page.getPaddingLeft() - page.getPaddingRight()) * page.getScaleX() / countX;
        float ch = (page.getHeight() - page.getPaddingTop() - page.getPaddingBottom()) * page.getScaleY() / countY;
        int dropCellX = Math.max(0, Math.min(countX - 1, (int) ((wx - at[0] - page.getPaddingLeft() * page.getScaleX()) / Math.max(1, cw))));
        int dropCellY = Math.max(0, Math.min(countY - 1, (int) ((wy - at[1] - page.getPaddingTop() * page.getScaleY()) / Math.max(1, ch))));
        List<int[]> free = new ArrayList<int[]>();
        for (int y = 0; y < countY; y++) for (int x = 0; x < countX; x++) if (!used[x][y]) free.add(new int[]{x, y, (x - dropCellX) * (x - dropCellX) + 2 * (y - dropCellY) * (y - dropCellY), y * countX + x});
        java.util.Collections.sort(free, new java.util.Comparator<int[]>() { public int compare(int[] p, int[] q) { return p[2] != q[2] ? p[2] - q[2] : p[3] - q[3]; } });
        Object writer = call(a, "getModelWriter");
        List<Object> items = new ArrayList<Object>(); List<Pick> placed = new ArrayList<Pick>(); int next = 0;
        for (Pick p : moving) {
            if (next >= free.size()) break;
            Object item = newItem(a, p); if (item == null) continue;
            int[] cell = free.get(next++);
            call(writer, "addItemToDatabase", item, -100, screenId, cell[0], cell[1]);
            items.add(item); placed.add(p);
        }
        if (items.isEmpty()) { if (moving.size() > 0) toast(a, "No room on this page for the other apps"); return; }
        try { call(get(a, "modelCallbacks"), "bindItems", items, true); }
        catch (Throwable noBindItems) {
            for (Object item : items) { View view = (View) call(a, "createShortcut", item); call(workspace, "addInScreenFromBind", view, item); }
        }
        removeOriginals(a, placed);
        if (placed.size() < moving.size()) toast(a, (moving.size() - placed.size()) + " apps did not fit on this page");
        Module.log("Multi-app drag: " + placed.size() + " apps placed on home page " + screenId + " near cell " + dropCellX + "," + dropCellY);
    }

    /**
     * Removes moved home apps from their old places. Apps that came out of a home folder wait until the
     * stock folder has finished closing (it replaces itself with its last app, or goes away, and must not
     * be edited while it does), then go; a folder that would be left empty is removed whole.
     */
    private static void removeOriginals(final Activity a, List<Pick> list) {
        final List<Pick> todo = new ArrayList<Pick>();
        boolean fromFolder = false;
        for (Pick p : list) if (homeItem(p)) { todo.add(p); try { if (((Number) get(p.info, "container")).intValue() >= 0) fromFolder = true; } catch (Throwable ignored) { } }
        if (todo.isEmpty()) return;
        final long start = SystemClock.uptimeMillis(); final boolean folderMoves = fromFolder;
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() { public void run() {
            if (LauncherFeatures.activity.get() != a || a.isDestroyed()) return;
            long waited = SystemClock.uptimeMillis() - start;
            if (folderMoves) {
                boolean busy = waited < 900;
                for (Pick p : todo) {
                    try {
                        int container = ((Number) get(p.info, "container")).intValue();
                        Object folder = container >= 0 ? folderById(a, container) : null;
                        if (folder != null && (Boolean.TRUE.equals(get(folder, "mIsOpen")) || ((Number) get(folder, "mState")).intValue() == 1)) busy = true;
                    } catch (Throwable ignored) { }
                }
                if (busy) {
                    if (waited >= 3000) { Module.log("Multi-app drag: old icons retained because source folder is still open or animating"); return; }
                    handler.postDelayed(this, 120); return;
                }
            }
            try { removeNow(a, todo); } catch (Throwable error) { Module.log("Multi-app drag: could not remove the old icons", error); }
        } });
    }

    private static void removeNow(Activity a, List<Pick> todo) throws Exception {
        java.util.LinkedHashMap<Integer, List<Object>> byFolder = new java.util.LinkedHashMap<Integer, List<Object>>();
        for (Pick p : todo) {
            int container = ((Number) get(p.info, "container")).intValue();
            if (container >= 0) { List<Object> l = byFolder.get(container); if (l == null) byFolder.put(container, l = new ArrayList<Object>()); l.add(p.info); continue; }
            View view = p.view != null && p.view.isAttachedToWindow() && p.view.getTag() == p.info ? p.view : viewOf(a.getWindow().getDecorView(), p.info);
            if (view == null) { Module.log("Multi-app drag: old icon of " + p.key + " is not on screen"); continue; }
            try { call(a, "removeItem", view, p.info, true, "Pixel Suite: moved with other apps"); }
            catch (Throwable fourArgs) { call(a, "removeItem", view, p.info, true); }
        }
        for (java.util.Map.Entry<Integer, List<Object>> e : byFolder.entrySet()) {
            View icon = findFolderIcon(a.getWindow().getDecorView(), e.getKey());
            if (icon == null) { Module.log("Multi-app drag: folder " + e.getKey() + " is gone, nothing to remove"); continue; }
            Object folder = get(icon, "mFolder"), info = get(icon, "mInfo");
            List<?> contents = (List<?>) get(info, "contents");
            List<Object> removing = new ArrayList<Object>();
            for (Object item : e.getValue()) if (contents.contains(item)) removing.add(item);
            if (removing.isEmpty()) continue;
            int count = contents.size();
            if (removing.size() >= count) {
                try { call(a, "removeItem", icon, info, true, "Pixel Suite: moved with other apps"); }
                catch (Throwable fourArgs) { call(a, "removeItem", icon, info, true); }
            } else HomeFolderApps.remove(a, folder, removing);
        }
    }

    private static View viewOf(View v, Object tag) {
        if (v.getTag() == tag && type("BubbleTextView").isInstance(v)) return v;
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { View r = viewOf(g.getChildAt(i), tag); if (r != null) return r; } }
        return null;
    }

    static Object folderById(Activity a, int id) {
        View icon = findFolderIcon(a.getWindow().getDecorView(), id);
        return icon == null ? null : get(icon, "mFolder");
    }
    private static View findFolderIcon(View v, int id) {
        try { if (type("folder.FolderIcon").isInstance(v) && FolderSort.homeId(get(v, "mInfo")) == id && !DrawerFolders.isFolder(get(v, "mInfo"))) return v; } catch (Throwable ignored) { }
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) { View r = findFolderIcon(g.getChildAt(i), id); if (r != null) return r; } }
        return null;
    }
    private static View folderIconAt(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = g.getChildCount() - 1; i >= 0; i--) { View r = folderIconAt(g.getChildAt(i), x, y); if (r != null) return r; } }
        try {
            if (type("folder.FolderIcon").isInstance(v) && !DrawerFolders.isFolder(get(v, "mInfo"))) { Rect r = new Rect(); if (v.getGlobalVisibleRect(r) && r.contains(x, y)) return v; }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void toast(Context c, String text) { try { android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { } }

    private static void restoreViews() {
        for (Pick p : picks) {
            View v = p.view; if (v == null) continue;
            v.animate().cancel(); v.setScaleX(p.scaleX); v.setScaleY(p.scaleY); Xp.setAdditionalInstanceField(v, "pixelsuite_picked", null);
            v.setAlpha(0f); v.animate().alpha(p.alpha <= 0 ? 1f : p.alpha).setDuration(180).start();
        }
    }

    private static void reset() {
        restoreStarters();
        generation++;
        for (Pick p : picks) try { p.icon.recycle(); } catch (Throwable ignored) { }
        picks.clear(); detach(); ending = false; primaryInfo = null; primaryKey = null;
        drawerHandled = false; workspaceDropped = false; droppedFolder = null; buttonDropped = null;
        Activity a = LauncherFeatures.activity.get(); if (a != null) shiftBar(a, false);
    }

    // ---- the Remove / Uninstall buttons ------------------------------------------------------

    /** The top Remove / Uninstall buttons take the same blur and tint as the module's other panels. */
    static void frost(final View button) {
        if (Boolean.TRUE.equals(Xp.getAdditionalInstanceField(button, "pixelsuite_frosted"))) return;
        Xp.setAdditionalInstanceField(button, "pixelsuite_frosted", Boolean.TRUE);
        Context c = button.getContext();
        float height = button.getHeight() > 0 ? button.getHeight() : FrostedSurface.dp(c, 48);
        FrostedSurface.frost(button, height / 2f / c.getResources().getDisplayMetrics().density, "Drop target");
        int ink = FrostedSurface.dark(c) ? 0xffeee9f1 : 0xff202027;
        if (button instanceof TextView) {
            TextView t = (TextView) button; t.setTextColor(ink);
            t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(ink));
        }
    }

    /** A held app over Remove or Uninstall lights the button up (accent tint, a small swell) and eases back on leaving. */
    static void hover(final View button, boolean on) {
        Context c = button.getContext(); boolean dark = FrostedSurface.dark(c);
        int accent = LauncherFeatures.surface(c, dark ? "system_accent1_700" : "system_accent1_200", dark ? 0xff0842a0 : 0xffa8c7fa);
        int ink = on ? (dark ? 0xffd3e3fd : 0xff041e49) : (dark ? 0xffeee9f1 : 0xff202027);
        android.graphics.drawable.Drawable bg = button.getBackground();
        if (bg instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable layers = (android.graphics.drawable.LayerDrawable) bg;
            Drawable tint = layers.getNumberOfLayers() > 1 ? layers.getDrawable(1) : null;
            if (tint instanceof android.graphics.drawable.GradientDrawable) {
                int base[] = FrostedSurface.colors(c), lit = (accent & 0xffffff) | 0xe6000000;
                ((android.graphics.drawable.GradientDrawable) tint).setColors(on ? new int[]{lit, lit} : base);
            }
        }
        if (button instanceof TextView) {
            TextView t = (TextView) button; t.setTextColor(ink); t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(ink));
        }
        button.invalidate();
        button.animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).setDuration(on ? 220 : 180).setStartDelay(0).setInterpolator(on ? EMPHASIZED_DECELERATE : STANDARD).start();
    }

    private static boolean isRemoveButton(Object target) {
        String n = target.getClass().getSimpleName().toLowerCase(), t = text(target);
        return n.contains("delete") || n.contains("remove") || (!n.contains("secondary") && t.startsWith("remove"));
    }
    private static boolean isUninstallButton(Object target) {
        String n = target.getClass().getSimpleName().toLowerCase(), t = text(target);
        return n.contains("secondary") || n.contains("uninstall") || t.contains("uninstall");
    }
    private static String text(Object v) { try { return String.valueOf(((TextView) v).getText()).toLowerCase(); } catch (Throwable t) { return ""; } }
    private static void buttons(View v, List<View> out) {
        try { if (type("ButtonDropTarget").isInstance(v)) { out.add(v); return; } } catch (Throwable ignored) { }
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) buttons(g.getChildAt(i), out); }
    }

    /**
     * With two or more apps held, Uninstall fades and shrinks away (it cannot remove several apps)
     * and Remove glides to the middle of the top bar; both return when the drag ends.
     */
    private static void shiftBar(Activity a, boolean multi) {
        try {
            if (multi == barShifted) return;
            List<View> all = new ArrayList<View>(); buttons(a.getWindow().getDecorView(), all);
            if (all.isEmpty()) return;
            ViewGroup bar = null;
            for (android.view.ViewParent q = all.get(0).getParent(); q instanceof ViewGroup; q = q.getParent()) {
                if (bar == null) bar = (ViewGroup) q;
                try { if (type("DropTargetBar").isInstance(q)) { bar = (ViewGroup) q; break; } } catch (Throwable ignored) { }
            }
            if (bar == null) return;
            if (multi) {
                removeButton = null; uninstallButton = null;
                for (View b : all) frost(b);
                for (View b : all) { if (isUninstallButton(b)) uninstallButton = b; else if (isRemoveButton(b)) removeButton = b; }
                why("drop targets: " + all.size() + ", remove=" + (removeButton != null) + ", uninstall=" + (uninstallButton != null));
                if (uninstallButton != null) uninstallButton.animate().cancel();
                if (uninstallButton != null) uninstallButton.animate().alpha(0f).scaleX(.8f).scaleY(.8f).setDuration(220).setInterpolator(STANDARD).start();
                if (removeButton != null) {
                    int[] bl = new int[2], rl = new int[2]; bar.getLocationOnScreen(bl); removeButton.getLocationOnScreen(rl);
                    float dx = bl[0] + bar.getWidth() / 2f - (rl[0] - removeButton.getTranslationX() + removeButton.getWidth() / 2f);
                    removeButton.animate().cancel();
                    removeButton.animate().translationX(dx).setDuration(420).setStartDelay(60).setInterpolator(EMPHASIZED_DECELERATE).start();
                }
                barShifted = true;
            } else {
                barShifted = false;
                final View u = uninstallButton, r = removeButton;
                if (u != null) { u.animate().cancel(); u.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(200).setInterpolator(STANDARD).start(); }
                if (r != null) { r.animate().cancel(); r.animate().translationX(0f).setStartDelay(0).setDuration(240).setInterpolator(STANDARD).start(); }
                uninstallButton = null; removeButton = null;
            }
        } catch (Throwable error) { LauncherFeatures.logOnce("Multi-app drag: drop bar shift unavailable", error); }
    }

    /** Stop overlays and pending placements when the launcher leaves the foreground. */
    static void cancel() {
        for (Pick p : picks) {
            View v = p.view; if (v == null) continue;
            v.animate().cancel(); v.setAlpha(p.alpha); v.setScaleX(p.scaleX); v.setScaleY(p.scaleY);
            Xp.setAdditionalInstanceField(v, "pixelsuite_picked", null);
        }
        reset();
    }

    // ---- the stack under the held app, with its ghost trail --------------------------------

    static final class StackView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density; float cx, cy, size; boolean known; long last;
        final float[] hx = new float[24], hy = new float[24]; final long[] ht = new long[24]; int head;
        int state; long stateAt;                    // 0 live, 1 dropped (absorb), 2 cancelled (fly back)
        StackView(Context c) { super(c); density = c.getResources().getDisplayMetrics().density; setWillNotDraw(false); setClickable(false); setFocusable(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        public boolean onTouchEvent(android.view.MotionEvent e) { return false; }

        void finish(boolean success) { state = success ? 1 : 2; stateAt = SystemClock.uptimeMillis(); }

        /** Tracks the held app (DragView) and moves every card toward its slot with a soft lag. */
        void frame() {
            Activity a = LauncherFeatures.activity.get(); long now = SystemClock.uptimeMillis();
            View drag = a == null ? null : dragView(a);
            if (drag != null && state == 0) {
                int[] at = new int[2], me = new int[2]; drag.getLocationInWindow(at); getLocationInWindow(me);
                float w = drag.getWidth() * drag.getScaleX(), h = drag.getHeight() * drag.getScaleY();
                cx = at[0] - me[0] + w / 2f; cy = at[1] - me[1] + h / 2f; size = Math.min(w, h); known = true;
            }
            if (!known) return;
            float dt = last == 0 ? 16 : Math.min(48, now - last); last = now;
            head = (head + 1) % hx.length; hx[head] = cx; hy[head] = cy; ht[head] = now;
            int n = picks.size();
            for (int i = 0; i < n; i++) {
                Pick p = picks.get(i); int depth = n - 1 - i;        // 0 = newest, nearest the held app
                float tx = cx + (depth + 1) * 5f * density, ty = cy + (depth + 1) * 4f * density;
                if (!p.placed) { p.x = tx; p.y = ty; p.placed = true; }
                float k = 1f - (float) Math.pow(1f - Math.max(.10f, .34f - depth * .07f), dt / 16f);
                p.x += (tx - p.x) * k; p.y += (ty - p.y) * k;
            }
        }

        private float[] past(long ago) {
            long want = SystemClock.uptimeMillis() - ago;
            for (int i = 0; i < hx.length; i++) { int j = (head - i + hx.length) % hx.length; if (ht[j] != 0 && ht[j] <= want) return new float[]{hx[j], hy[j]}; }
            return null;
        }

        protected void onDraw(Canvas c) {
            if (!known || picks.isEmpty()) return;
            long now = SystemClock.uptimeMillis(); int n = picks.size();
            float card = size * .86f;
            float end = state == 0 ? 0f : Math.min(1f, (now - stateAt) / (state == 1 ? 220f : 340f));
            // Ghost trail: fading copies of the stack where it was a moment ago, only while moving.
            if (state == 0) {
                Pick top = picks.get(n - 1);
                float[] alphas = {.22f, .13f, .07f}; long[] ago = {45, 90, 140};
                for (int g = 2; g >= 0; g--) {
                    float[] at = past(ago[g]); if (at == null) continue;
                    float moved = (float) Math.hypot(at[0] - cx, at[1] - cy);
                    float strength = Math.min(1f, moved / (24 * density)); if (strength <= .02f) continue;
                    draw(c, top.icon, at[0], at[1], card * (1f - .05f * (g + 1)), 0, alphas[g] * strength);
                }
            }
            for (int i = Math.max(0, n - 4); i < n; i++) {
                Pick p = picks.get(i); int depth = n - 1 - i;
                float x = p.x, y = p.y, s = card, alpha = depth >= 3 ? .0f : 1f - depth * .12f, turn = (depth % 2 == 0 ? 5f : -4f) * (depth + 1) * .6f;
                // Flying in from where the app was.
                float fly = Math.min(1f, (now - p.added) / 420f);
                if (fly < 1f) {
                    float e = EMPHASIZED_DECELERATE.getInterpolation(fly);
                    x = p.from.centerX() + (x - p.from.centerX()) * e; y = p.from.centerY() + (y - p.from.centerY()) * e - (float) Math.sin(Math.PI * e) * 28 * density;
                    s = p.from.width() + (s - p.from.width()) * e; turn *= e; alpha = 1f;
                }
                if (state == 1) { float e = STANDARD.getInterpolation(end); x += (cx - x) * e; y += (cy - y) * e; s *= 1f - .6f * e; alpha *= 1f - e; }
                if (state == 2) {
                    float e = EMPHASIZED_DECELERATE.getInterpolation(end);
                    int[] me = new int[2]; getLocationInWindow(me);
                    x += (p.from.centerX() - me[0] - x) * e; y += (p.from.centerY() - me[1] - y) * e; s += (p.from.width() - s) * e; turn *= 1f - e; alpha = alpha + (1f - alpha) * e;
                }
                if (alpha > .01f) draw(c, p.icon, x, y, s, turn, alpha);
            }
        }

        private void draw(Canvas c, Bitmap icon, float x, float y, float s, float turn, float alpha) {
            if (icon == null || icon.isRecycled()) return;
            c.save(); c.rotate(turn, x, y);
            // Soft shadow under each card.
            shade.setColor(0xff000000); shade.setAlpha(Math.round(46 * alpha));
            c.drawOval(x - s * .42f, y + s * .30f, x + s * .42f, y + s * .52f, shade);
            paint.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(icon, null, new RectF(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f), paint);
            c.restore();
        }
    }

    // ---- the count badge (above the held app) ---------------------------------------------

    static final class Badge extends Drawable {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float density; final Context context; int count, previous; long shownAt, changedAt, endAt;
        /** Overshoot (tension 2.2): the badge pops slightly past full size and settles. */
        static float pop(float t) { float u = t - 1f, k = 2.2f; return u * u * ((k + 1) * u + k) + 1f; }
        Badge(Context c) {
            context = c; density = c.getResources().getDisplayMetrics().density;
            boolean dark = FrostedSurface.dark(c);
            fill.setColor(LauncherFeatures.surface(c, dark ? "system_accent1_200" : "system_accent1_600", dark ? 0xffaac7ff : 0xff3d5f99));
            ring.setColor(LauncherFeatures.surface(c, dark ? "system_neutral1_900" : "system_neutral1_10", dark ? 0xff1b1b1f : 0xfffdfcff));
            text.setColor(LauncherFeatures.surface(c, dark ? "system_accent1_800" : "system_accent1_0", dark ? 0xff0a305f : 0xffffffff));
            text.setTextSize(12.5f * density); text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        }
        void count(int n) { long now = SystemClock.uptimeMillis(); if (count == 0) shownAt = now; previous = count; count = n; changedAt = now; }
        void finish() { endAt = SystemClock.uptimeMillis(); }
        public void draw(Canvas c) {
            Activity a = LauncherFeatures.activity.get(); if (a == null || count < 2) return;
            View drag = dragView(a); if (drag == null && stack == null) return;
            float x, y;
            if (drag != null && endAt == 0) {
                int[] at = new int[2]; drag.getLocationInWindow(at);
                float w = drag.getWidth() * drag.getScaleX();
                x = at[0] + w * .86f; y = at[1] + drag.getHeight() * drag.getScaleY() * .12f;
                lastX = x; lastY = y;
            } else { x = lastX; y = lastY; }
            long now = SystemClock.uptimeMillis();
            float appear = pop(Math.min(1f, (now - shownAt) / 320f));
            float pulse = (now - changedAt) < 240 && previous >= 2 ? 1f + .18f * (float) Math.sin(Math.PI * (now - changedAt) / 240f) : 1f;
            float vanish = endAt == 0 ? 1f : 1f - Math.min(1f, (now - endAt) / 160f);
            float scale = appear * pulse * vanish; if (scale <= .01f) return;
            String label = count > 99 ? "99+" : String.valueOf(count);
            float h = 22 * density, w = Math.max(h, text.measureText(label) + 14 * density);
            c.save(); c.translate(x, y); c.scale(scale, scale);
            c.drawRoundRect(new RectF(-w / 2 - 2 * density, -h / 2 - 2 * density, w / 2 + 2 * density, h / 2 + 2 * density), h, h, ring);
            c.drawRoundRect(new RectF(-w / 2, -h / 2, w / 2, h / 2), h / 2, h / 2, fill);
            // The number rolls: the old one slides up and fades, the new one rises in.
            float roll = previous >= 2 ? STANDARD.getInterpolation(Math.min(1f, (now - changedAt) / 220f)) : 1f;
            float base = -(text.descent() + text.ascent()) / 2f;
            c.clipRect(-w / 2, -h / 2, w / 2, h / 2);
            int alpha = text.getAlpha();
            if (roll < 1f) { text.setAlpha(Math.round(255 * (1f - roll))); c.drawText(previous > 99 ? "99+" : String.valueOf(previous), 0, base - roll * 9 * density, text); }
            text.setAlpha(Math.round(255 * roll)); c.drawText(label, 0, base + (1f - roll) * 9 * density, text); text.setAlpha(alpha);
            c.restore();
        }
        float lastX, lastY;
        public void setAlpha(int alpha) { }
        public void setColorFilter(ColorFilter filter) { }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
