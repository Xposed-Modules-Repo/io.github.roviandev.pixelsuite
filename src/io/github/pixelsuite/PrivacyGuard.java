package io.github.pixelsuite;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2.0.6-preview68 (owner's spec part 2, launcher side): removing a locked folder from the home screen
 * asks first (preview71: a locked app's icon is removed without asking, the app stays locked) (fingerprint / PIN / face), from the long-press menu's "Remove" and by dragging it onto "Remove".
 * The drawer folder menu's "Remove" asks too (DrawerFolderMenu). Confirmed, it is removed the launcher's usual way
 * (with its "Undo"); dismissed, nothing changes (a dragged icon goes back to its place).
 */
final class PrivacyGuard {
    private PrivacyGuard() {}
    static final String MODE_REMOVE_APP = "removeapp", MODE_REMOVE_FOLDER = "removefolder";
    static final String ACTION_OK = "io.github.pixelsuite.APP_LOCK_REMOVE_OK";

    /** Asked just now and confirmed: the same action goes through once. */
    private static final Map<Object, Boolean> passing = new WeakHashMap<Object, Boolean>();

    private static volatile boolean passingDelete;

    static void install(final ClassLoader cl) {
        int n = 0;
        try {
            // preview74 (owner's videos: a locked folder flicked to Remove never came back after dismissing the sheet,
            // even with Undo pressed): the launcher's delete itself now waits. The flick / drag still plays the
            // launcher's fly-away (the folder's tile leaves the screen as usual), but DropTargetHandler.onDeleteComplete
            // (the step that deletes it and shows "Item removed  Undo") runs only once the owner confirmed (its lock is
            // cleared first). Dismissed: nothing was deleted, the home screen is loaded again from the launcher's own
            // records and the folder is back in its place. Menu Remove goes the same way (the folder simply stays).
            Class<?> handler = Xp.findClass("com.android.launcher3.DropTargetHandler", cl);
            n += Xp.hookAllMethods(handler, "onDeleteComplete", new Xp.Callback() {
                @Override protected void beforeHookedMethod(final Xp.Param p) {
                    try {
                        if (p.args.length < 2) return;
                        final Object info = p.args[1];
                        final View view = p.args[0] instanceof View ? (View) p.args[0] : null;
                        final Object self = p.thisObject;
                        if (passingDelete) { remember(self, view, info, passingKey); return; }   // confirmed: it goes now
                        Activity a = LauncherFeatures.activity.get(); if (a == null) return;
                        final String key = PrivacyBadges.lockedKey(a, info);
                        if (key == null || key.startsWith("a:")) { remember(self, view, info, null); return; }   // only locked folders ask
                        boolean asked = ask(a, key, folderName(info), new Runnable() { public void run() {
                            passingDelete = true; passingKey = key;
                            try { Xp.callMethod(self, "onDeleteComplete", view, info); Module.log("App lock: locked folder deleted after confirming"); }
                            catch (Throwable error) { Module.log("App lock: folder delete failed", error); }
                            finally { passingDelete = false; passingKey = null; }
                        } }, new Runnable() { public void run() { restore(self, view != null ? view : heldView(info), info); } });
                        if (asked) p.setResult(null);   // not deleted (yet)
                        else remember(self, view, info, null);   // protection off: deleted now, its lock stays as it is
                    } catch (Throwable error) { LauncherFeatures.logOnce("App lock: folder delete check unavailable (it is deleted)", error); }
                }
            }).size();
            // The folder's own tile, kept from the moment it is dropped on Remove (the delete step gets no view).
            n += Xp.hookAllMethods(Xp.findClass("com.android.launcher3.DeleteDropTarget", cl), "onDrop", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        Object d = p.args[0];
                        Object info = Xp.getObjectField(d, "dragInfo"), view = Xp.getObjectField(d, "originalView");
                        if (view instanceof View) { held = new java.lang.ref.WeakReference<View>((View) view); heldInfo = new java.lang.ref.WeakReference<Object>(info); }
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable error) { Module.log("App lock: folder delete check unavailable", error); }
        // 2.0.8-preview4 (owner's video: a folder removed by dragging it onto Remove, locked or not, never came back
        // with the pill's Undo): the launcher's Undo cancels the delete and then loads the home screen again from its
        // records, which does not bring the folder back. Undo now puts the folder's own tile back in its place with the
        // same spring as cancelling the fingerprint sheet (X); a locked folder gets its lock back. If its page is gone
        // (it was alone there), the launcher's own Undo runs as before.
        try {
            Class<?> pill = Xp.findClass("com.android.launcher3.DropTargetHandler$onDeleteComplete$onDismissed$1", cl);
            final Field kind = intField(pill);
            if (kind == null) Module.log("Folder undo: the pill's action is unreadable (the launcher's own Undo is kept)");
            else n += Xp.hookAllMethods(pill, "run", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        Removed r = lastRemoved;
                        if (kind.getInt(p.thisObject) == 0) { lastRemoved = null; return; }   // the pill went away: the delete stands
                        if (r == null || android.os.SystemClock.uptimeMillis() - r.at > 30000) return;
                        Object handler = null; try { handler = Xp.getObjectField(p.thisObject, "this$0"); } catch (Throwable ignored) { }
                        if (handler != null && handler != r.handler) return;
                        lastRemoved = null;
                        if (r.lockKey != null) relock(r.lockKey);
                        if (putBackAfterUndo(r)) p.setResult(null);   // done here; the launcher's reload is not needed
                    } catch (Throwable error) { LauncherFeatures.logOnce("Folder undo failed (the launcher's own Undo runs)", error); }
                }
            }).size();
        } catch (Throwable error) { Module.log("Folder undo unavailable (the launcher's own Undo is kept)", error); }
        Module.log("App lock remove checks: " + n + " hooks");
    }

    /** The folder just removed (until its pill goes away), so its Undo can put it back. */
    private static final class Removed {
        final Object handler, info; final java.lang.ref.WeakReference<View> view; final String lockKey; final long at; final int page;
        Removed(Object handler, View view, Object info, String lockKey, int page) {
            this.handler = handler; this.info = info; this.view = new java.lang.ref.WeakReference<View>(view); this.lockKey = lockKey;
            this.at = android.os.SystemClock.uptimeMillis(); this.page = page;
        }
    }
    private static volatile Removed lastRemoved;
    private static volatile String passingKey;

    /** A folder is being deleted now: its tile and record are kept for the pill's Undo. Anything else: forgotten. */
    private static void remember(Object handler, View view, Object info, String lockKey) {
        try {
            if (!folder(info)) { lastRemoved = null; return; }
            View tile = view != null ? view : heldView(info);
            Activity a = LauncherFeatures.activity.get();
            Object workspace = a == null ? null : LauncherFeatures.get(a, "mWorkspace");
            if (tile == null && workspace != null) {
                Object v = Xp.callMethod(workspace, "getViewByItemId", ((Number) LauncherFeatures.get(info, "id")).intValue());
                if (v instanceof View) tile = (View) v;
            }
            int page = -1;   // its page, in case the page goes with it (it was alone there)
            try { if (workspace != null && ((Number) LauncherFeatures.get(info, "container")).intValue() == -100) page = ((Number) Xp.callMethod(workspace, "getPageIndexForScreenId", ((Number) LauncherFeatures.get(info, "screenId")).intValue())).intValue(); } catch (Throwable ignored) { }
            lastRemoved = tile == null ? null : new Removed(handler, tile, info, lockKey, page);
        } catch (Throwable error) { lastRemoved = null; }
    }
    private static Class<?> collection;
    private static boolean folder(Object info) {
        if (info == null) return false;
        try { if (collection == null) collection = LauncherFeatures.type("model.data.CollectionInfo"); if (collection.isInstance(info)) return true; } catch (Throwable ignored) { }
        String n = info.getClass().getName();
        return n.endsWith(".FolderInfo") || n.endsWith(".CollectionInfo") || DrawerFolders.isFolder(info);
    }

    /**
     * Undo: the delete is cancelled exactly as the launcher's own Undo does it (nothing was written yet), then the
     * folder's tile goes back into its place, growing in. False: the launcher's own Undo should run instead.
     */
    private static boolean putBackAfterUndo(Removed r) {
        Activity a = LauncherFeatures.activity.get();
        View view = r.view.get();
        if (a == null || view == null) return false;
        Object workspace = LauncherFeatures.get(a, "mWorkspace");
        if (workspace == null) return false;
        try {
            int container = ((Number) LauncherFeatures.get(r.info, "container")).intValue();
            int screen = ((Number) LauncherFeatures.get(r.info, "screenId")).intValue();
            if (container == -100 && Xp.callMethod(workspace, "getScreenWithId", screen) == null) {
                // it was alone on its page, which went with it: the page comes back in the same place first
                try {
                    int count = ((android.view.ViewGroup) workspace).getChildCount();
                    int at = r.page < 0 ? count : Math.min(r.page, count);
                    Xp.callMethod(workspace, "insertNewWorkspaceScreen", screen, at);
                } catch (Throwable ignored) { }
                if (Xp.callMethod(workspace, "getScreenWithId", screen) == null) {
                    Module.log("Folder undo: its page is gone, the launcher's own Undo runs");
                    return false;
                }
            }
        } catch (Throwable error) { return false; }
        try {
            Object controller = Xp.getObjectField(r.handler, "undoDeleteController");
            ((java.util.List<?>) Xp.getObjectField(controller, "pendingDeletes")).clear();
            Object writer = Xp.callMethod(Xp.getObjectField(controller, "activityContext"), "getModelWriter");
            boolean resumed = false;
            for (java.lang.reflect.Method m : writer.getClass().getMethods()) if (m.getName().equals("resumeWrites") && m.getParameterTypes().length == 2) { m.invoke(writer, null, true); resumed = true; break; }
            if (!resumed) return false;
        } catch (Throwable error) { Module.log("Folder undo: the delete could not be cancelled here, the launcher's own Undo runs", error); return false; }
        try {
            if (view.getParent() == null) Xp.callMethod(workspace, "addInScreenFromBind", view, r.info);
            if (view.getParent() == null) { Module.log("Folder undo: tile not placed, home screen loaded again"); reload(); return true; }
            inflate(view);
            Module.log("Folder undo: folder put back");
        } catch (Throwable error) { Module.log("Folder undo: tile not placed, home screen loaded again", error); reload(); }
        return true;
    }

    /** The folder's tile grows back into its place (the same spring as cancelling the sheet). */
    private static void inflate(View view) {
        view.setVisibility(View.VISIBLE);
        try { Xp.callMethod(view, "setIconVisible", true); } catch (Throwable ignored) { }
        view.setTranslationX(0f); view.setTranslationY(0f);
        view.setPivotX(view.getWidth() / 2f); view.setPivotY(view.getHeight() / 2f);
        view.setScaleX(0.3f); view.setScaleY(0.3f); view.setAlpha(0f);
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120).setDuration(420)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.8f)).start();
    }

    /** A locked folder's lock was taken off when its delete was confirmed; Undo gives it back (the system writes it). */
    static final String ACTION_RELOCK = "io.github.pixelsuite.APP_LOCK_FOLDER_RELOCK";
    static void relock(String key) {
        try {
            Activity a = LauncherFeatures.activity.get(); if (a == null) return;
            a.sendBroadcast(new Intent(ACTION_RELOCK).setPackage("android").putExtra(PrivacyFolders.EXTRA_FOLDER, key));
        } catch (Throwable error) { Module.log("Folder undo: lock not given back", error); }
    }

    /** Dismissed: writes go on again (nothing was deleted) and the home screen is loaded again, the folder with it. */
    private static java.lang.ref.WeakReference<View> held; private static java.lang.ref.WeakReference<Object> heldInfo;
    private static View heldView(Object info) {
        if (held == null || heldInfo == null || heldInfo.get() != info) return null;
        return held.get();
    }

    /**
     * Dismissed (preview75, owner: "the folder would inflate on my homescreen again with good animations"): nothing
     * was deleted, so writes go on again, and the folder's own tile goes back into its place on the home screen,
     * growing in with a small spring. (Build 295 loaded the whole home screen again instead: it flickered, and the
     * folder did not come back.)
     */
    private static void restore(Object handler, final View view, Object info) {
        try {
            Object controller = Xp.getObjectField(handler, "undoDeleteController");
            ((java.util.List<?>) Xp.getObjectField(controller, "pendingDeletes")).clear();
            Object writer = Xp.callMethod(Xp.getObjectField(controller, "activityContext"), "getModelWriter");
            for (java.lang.reflect.Method m : writer.getClass().getMethods()) if (m.getName().equals("resumeWrites") && m.getParameterTypes().length == 2) { m.invoke(writer, null, true); break; }
        } catch (Throwable error) { Module.log("App lock: writes not resumed", error); }
        Activity a = LauncherFeatures.activity.get();
        if (view == null || a == null) { Module.log("App lock: folder delete cancelled, no tile to put back (" + (view == null) + ")"); reload(); return; }
        try {
            if (view.getParent() == null) {
                Object workspace = LauncherFeatures.get(a, "mWorkspace");
                Xp.callMethod(workspace, "addInScreenFromBind", view, info);
            }
            inflate(view);
            Module.log("App lock: folder delete cancelled, folder put back");
        } catch (Throwable error) { Module.log("App lock: folder tile could not be put back", error); reload(); }
    }
    private static void reload() {
        try {
            Activity a = LauncherFeatures.activity.get();
            Object model = a == null ? null : LauncherFeatures.get(a, "mModel");
            if (model != null) Xp.callMethod(model, "forceReload", "pixelsuite locked folder kept");
        } catch (Throwable ignored) { }
    }

    private static Field intField(Class<?> k) {
        for (Field f : k.getDeclaredFields()) if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); return f; }
        return null;
    }

    /** A locked app or folder: the sheet asks, and {@code ok} runs once confirmed; true when it asked. */
    static boolean ask(Context c, Object info, Runnable ok) {
        String key = PrivacyBadges.lockedKey(c, info);
        if (key == null) return false;
        boolean folder = !key.startsWith("a:");
        // preview71 (owner): an app's icon comes off the home screen without asking (menu, drag or flick): the app
        // stays installed and locked. Only a locked folder asks (deleting it takes its lock away).
        if (!folder) return false;
        String name = folder ? folderName(info) : null;
        return ask(c, key, name, ok);
    }

    private static final Map<String, Runnable> asking = new ConcurrentHashMap<String, Runnable>();
    private static final Map<String, Runnable> cancels = new ConcurrentHashMap<String, Runnable>();
    static final String ACTION_NO = "io.github.pixelsuite.APP_LOCK_REMOVE_NO";
    private static boolean listening;

    static boolean ask(Context any, String key, String folderName, Runnable ok) { return ask(any, key, folderName, ok, null); }
    static boolean ask(Context any, String key, String folderName, Runnable ok, Runnable no) {
        Activity a = LauncherFeatures.activity.get(); if (a == null) return false;
        if (!PrivacyLock.onValue(android.provider.Settings.Secure.getString(a.getContentResolver(), PrivacyLock.PROTECT_KEY))) return false;   // protection off
        final String token = java.util.UUID.randomUUID().toString();
        asking.put(token, ok); if (no != null) cancels.put(token, no);
        if (!listening) {
            listening = true;
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String t = i.getStringExtra(PrivacyRecents.EXTRA_TOKEN); if (t == null) return;
                    Runnable job = asking.remove(t), no = cancels.remove(t);
                    if (ACTION_NO.equals(i.getAction())) { Module.log("App lock: remove dismissed (" + (no != null) + ")"); if (no != null) no.run(); return; }
                    if (job != null) job.run();
                }
            };
            try {
                Context app = a.getApplicationContext();
                IntentFilter f = new IntentFilter(ACTION_OK); f.addAction(ACTION_NO);
                try { Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class).invoke(app, r, f, 2); }
                catch (Throwable older) { app.registerReceiver(r, f); }
            } catch (Throwable error) { listening = false; Module.log("App lock: remove answer unavailable", error); return false; }
        }
        Haptics.tick(a);
        Intent i = new Intent().setComponent(new ComponentName(PrivacyLock.OUR, PrivacyLock.MENU_ACTIVITY));
        boolean folder = !key.startsWith("a:");
        i.putExtra(PrivacyLock.EXTRA_MODE, folder ? MODE_REMOVE_FOLDER : MODE_REMOVE_APP);
        if (folder) { i.putExtra(PrivacyFolders.EXTRA_FOLDER, key); i.putExtra(PrivacyFolders.EXTRA_NAME, folderName == null ? "Folder" : folderName); }
        else i.putExtra(PrivacyLock.EXTRA_PACKAGE, key.substring(2));
        i.putExtra(PrivacyRecents.EXTRA_TOKEN, token);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        android.os.Bundle options = null;
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            android.app.ActivityOptions.class.getMethod("setShareIdentityEnabled", boolean.class).invoke(o, true);
            options = o.toBundle();
        } catch (Throwable ignored) { }
        try { a.startActivity(i, options); return true; } catch (Throwable error) { asking.remove(token); cancels.remove(token); Module.log("App lock: unlock screen unavailable", error); return false; }
    }

    private static String folderName(Object info) {
        try {
            if (DrawerFolders.isFolder(info)) { LauncherData.Folder f = DrawerFolders.record(info); if (f != null && f.name != null && !f.name.isEmpty()) return f.name; }
            Object t = LauncherFeatures.get(info, "title"); if (t != null && t.toString().length() > 0) return t.toString();
        } catch (Throwable ignored) { }
        return "Folder";
    }
}
