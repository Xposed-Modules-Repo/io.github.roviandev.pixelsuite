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

final class PrivacyGuard {
    private PrivacyGuard() {}
    static final String MODE_REMOVE_APP = "removeapp", MODE_REMOVE_FOLDER = "removefolder";
    static final String ACTION_OK = "io.github.pixelsuite.APP_LOCK_REMOVE_OK";

    private static final Map<Object, Boolean> passing = new WeakHashMap<Object, Boolean>();

    private static volatile boolean passingDelete;

    static void install(final ClassLoader cl) {
        int n = 0;
        try {
            Class<?> handler = Xp.findClass("com.android.launcher3.DropTargetHandler", cl);
            n += Xp.hookAllMethods(handler, "onDeleteComplete", new Xp.Callback() {
                @Override protected void beforeHookedMethod(final Xp.Param p) {
                    try {
                        if (passingDelete || p.args.length < 2) return;
                        final Object info = p.args[1];
                        final View view = p.args[0] instanceof View ? (View) p.args[0] : null;
                        Activity a = LauncherFeatures.activity.get(); if (a == null) return;
                        String key = PrivacyBadges.lockedKey(a, info);
                        if (key == null || key.startsWith("a:")) return;
                        final Object self = p.thisObject;
                        boolean asked = ask(a, key, folderName(info), new Runnable() { public void run() {
                            passingDelete = true;
                            try { Xp.callMethod(self, "onDeleteComplete", view, info); Module.log("App lock: locked folder deleted after confirming"); }
                            catch (Throwable error) { Module.log("App lock: folder delete failed", error); }
                            finally { passingDelete = false; }
                        } }, new Runnable() { public void run() { restore(self, view != null ? view : heldView(info), info); } });
                        if (asked) p.setResult(null);
                    } catch (Throwable error) { LauncherFeatures.logOnce("App lock: folder delete check unavailable (it is deleted)", error); }
                }
            }).size();
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
        Module.log("App lock remove checks: " + n + " hooks");
    }

    private static java.lang.ref.WeakReference<View> held; private static java.lang.ref.WeakReference<Object> heldInfo;
    private static View heldView(Object info) {
        if (held == null || heldInfo == null || heldInfo.get() != info) return null;
        return held.get();
    }

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
            view.setVisibility(View.VISIBLE);
            try { Xp.callMethod(view, "setIconVisible", true); } catch (Throwable ignored) { }
            view.setPivotX(view.getWidth() / 2f); view.setPivotY(view.getHeight() / 2f);
            view.setScaleX(0.3f); view.setScaleY(0.3f); view.setAlpha(0f);
            view.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(120).setDuration(420)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.8f)).start();
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

    private static void restoreOld(Object handler) {
        try {
            Object controller = Xp.getObjectField(handler, "undoDeleteController");
            ((java.util.List<?>) Xp.getObjectField(controller, "pendingDeletes")).clear();
            Object writer = Xp.callMethod(Xp.getObjectField(controller, "activityContext"), "getModelWriter");
            for (java.lang.reflect.Method m : writer.getClass().getMethods()) if (m.getName().equals("resumeWrites") && m.getParameterTypes().length == 2) { m.invoke(writer, null, true); break; }
            Activity a = LauncherFeatures.activity.get();
            Object model = a == null ? null : LauncherFeatures.get(a, "mModel");
            if (model != null) Xp.callMethod(model, "forceReload", "pixelsuite locked folder kept");
            Module.log("App lock: folder delete cancelled, folder kept");
        } catch (Throwable error) { Module.log("App lock: folder could not be brought back", error); }
    }

    private static View findUndo(View v) {
        if (v.getClass().getName().endsWith(".Snackbar") && v instanceof android.view.ViewGroup) {
            int id = v.getResources().getIdentifier("action", "id", v.getContext().getPackageName());
            View b = id == 0 ? null : v.findViewById(id);
            if (b != null && b.isShown()) return b;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { View r = findUndo(g.getChildAt(i)); if (r != null) return r; }
        }
        return null;
    }

    private static Field intField(Class<?> k) {
        for (Field f : k.getDeclaredFields()) if (f.getType() == int.class && !Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); return f; }
        return null;
    }

    static boolean ask(Context c, Object info, Runnable ok) {
        String key = PrivacyBadges.lockedKey(c, info);
        if (key == null) return false;
        boolean folder = !key.startsWith("a:");
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
        if (!PrivacyLock.onValue(android.provider.Settings.Secure.getString(a.getContentResolver(), PrivacyLock.PROTECT_KEY))) return false;
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
