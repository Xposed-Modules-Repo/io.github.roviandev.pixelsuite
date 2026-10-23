package io.github.pixelsuite;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class PrivacyFolders {
    private PrivacyFolders() {}
    static final String KEY = "pixelsuite_privacy_folders";
    static final String MODE_LOCK = "lockfolder", MODE_UNLOCK = "unlockfolder", MODE_OPEN = "folder";
    static final String EXTRA_FOLDER = "pixelsuite_folder", EXTRA_NAME = "pixelsuite_folder_name";
    static final String ACTION_OK = "io.github.pixelsuite.APP_LOCK_FOLDER_OK";

    static boolean validKey(String k) { return k != null && k.matches("[hd]:[0-9]{1,12}"); }

    static String key(Object info) {
        try {
            if (DrawerFolders.isFolder(info)) { LauncherData.Folder f = DrawerFolders.record(info); return f == null ? null : "d:" + f.id; }
            return "h:" + ((Number) LauncherFeatures.get(info, "id")).intValue();
        } catch (Throwable t) { return null; }
    }
    private static String name(Object info) {
        try {
            if (DrawerFolders.isFolder(info)) { LauncherData.Folder f = DrawerFolders.record(info); if (f != null && f.name != null && !f.name.isEmpty()) return f.name; }
            Object t = LauncherFeatures.get(info, "title"); if (t != null && t.toString().length() > 0) return t.toString();
        } catch (Throwable ignored) { }
        return "Folder";
    }

    private static long readAt; private static Set<String> readList = Collections.emptySet();
    static void changed() { readAt = 0; }
    static Set<String> locked(Context c) {
        long now = SystemClock.uptimeMillis();
        if (now - readAt > 500) {
            try { readList = PrivacyLock.onValue(Settings.Secure.getString(c.getContentResolver(), PrivacyLock.ENABLED_KEY)) ? PrivacyLock.parse(Settings.Secure.getString(c.getContentResolver(), KEY)) : Collections.<String>emptySet(); } catch (Throwable ignored) { }
            readAt = now;
        }
        return readList;
    }

    private static final Map<String, Long> open = new ConcurrentHashMap<String, Long>();
    private static boolean unlocked(Context c, String key) {
        Long at = open.get(key);
        if (at == null) return false;
        if (PrivacyLock.graceNow(c) && SystemClock.elapsedRealtime() - at < PrivacyLock.AWAY_MS) return true;
        open.remove(key); return false;
    }
    private static final Map<String, Boolean> passing = new ConcurrentHashMap<String, Boolean>();

    private static java.lang.ref.WeakReference<Object> lockedCopy; private static long lockedCopyAt;
    static void draggedToHome(Context c, Object drawerFolder, Object copy) {
        try {
            String key = key(drawerFolder);
            if (key != null && locked(c).contains(key)) { lockedCopy = new java.lang.ref.WeakReference<Object>(copy); lockedCopyAt = android.os.SystemClock.uptimeMillis(); }
            else lockedCopy = null;
        } catch (Throwable ignored) { lockedCopy = null; }
    }
    private static void installCopyLock(ClassLoader cl) {
        Xp.Callback saved = new Xp.Callback() {
            @Override protected void afterHookedMethod(Xp.Param p) {
                try {
                    java.lang.ref.WeakReference<Object> ref = lockedCopy;
                    Object copy = ref == null ? null : ref.get();
                    if (copy == null || p.args.length == 0 || p.args[0] != copy) return;
                    if (android.os.SystemClock.uptimeMillis() - lockedCopyAt > 60000) { lockedCopy = null; return; }
                    int id = ((Number) LauncherFeatures.get(copy, "id")).intValue();
                    if (id < 0) return;
                    lockedCopy = null;
                    PrivacyGuard.relock("h:" + id);
                    Module.log("App lock: locked drawer folder put on the home screen, the copy is locked too");
                } catch (Throwable error) { LauncherFeatures.logOnce("App lock: home copy of a locked folder not locked", error); }
            }
        };
        try {
            Class<?> writer = Xp.findClass("com.android.launcher3.model.ModelWriter", cl);
            int n = Xp.hookAllMethods(writer, "addItemToDatabase", saved).size() + Xp.hookAllMethods(writer, "addOrMoveItemInDatabase", saved).size();
            if (n == 0) Module.log("App lock: home copies of locked drawer folders stay unlocked (save step not found)");
        } catch (Throwable error) { Module.log("App lock: home copies of locked drawer folders stay unlocked", error); }
    }

    static void install(ClassLoader cl) {
        installCopyLock(cl);
        int n = 0;
        try {
            Class<?> folder = Xp.findClass("com.android.launcher3.folder.Folder", cl);
            n += Xp.hookAllMethods(folder, "animateOpen", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        Object f = p.thisObject;
                        Object info = LauncherFeatures.get(f, "mInfo");
                        String key = key(info); if (key == null) return;
                        Activity a = LauncherFeatures.activity.get(); if (a == null || !locked(a).contains(key)) return;
                        if (passing.remove(key) != null || unlocked(a, key)) { open.put(key, SystemClock.elapsedRealtime()); return; }
                        p.setResult(null);
                        boolean hovering = false; try { hovering = Boolean.TRUE.equals(LauncherFeatures.get(f, "mIsExternalDrag")); } catch (Throwable ignored) { }
                        if (hovering) return;
                        Object icon = null; try { icon = LauncherFeatures.get(f, "mFolderIcon"); } catch (Throwable ignored) { }
                        ask(a, key, name(info), icon instanceof View ? (View) icon : null);
                    } catch (Throwable error) { LauncherFeatures.logOnce("App lock: folder check unavailable (it opens)", error); }
                }
            }).size();
            n += Xp.hookAllMethods(folder, "handleClose", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        String key = key(LauncherFeatures.get(p.thisObject, "mInfo")); if (key == null || !open.containsKey(key)) return;
                        if (PrivacyLock.graceNow(((View) p.thisObject).getContext())) open.put(key, SystemClock.elapsedRealtime()); else open.remove(key);
                        PrivacyBadges.folderClosed(((View) p.thisObject).getContext(), key);
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable error) { Module.log("App lock: folders unavailable", error); }
        Module.log("App lock folders: " + n + " hooks");
    }

    static void addHomeRow(final ViewGroup rows, final Object popup, final Object info) {
        try {
            final String key = key(info); if (key == null) return;
            final boolean isLocked = locked(rows.getContext()).contains(key);
            int at = rows.getChildCount();
            View sort = rows.findViewWithTag("pixelsuite_folder_sort"); if (sort != null) at = rows.indexOfChild(sort) + 1;
            for (int i = 0; i < rows.getChildCount(); i++) {
                View r = rows.getChildAt(i); CharSequence t = null;
                try { Object text = LauncherFeatures.get(r, "mBubbleText"); if (text instanceof TextView) t = ((TextView) text).getText(); } catch (Throwable ignored) { }
                if (t == null && r instanceof TextView) t = ((TextView) r).getText();
                if (t != null && "Remove".contentEquals(t)) { at = i; break; }
            }
            LauncherFeatures.addFolderRow(rows, isLocked ? "Unlock folder" : "Lock folder", "pixelsuite_folder_privacy", 1, at, new View.OnClickListener() {
                public void onClick(View v) { LauncherFeatures.call(popup, "close", true); change(key, name(info), isLocked); }
            });
            View row = rows.findViewWithTag("pixelsuite_folder_privacy");
            if (row != null) {
                View icon = (View) LauncherFeatures.get(row, "mIconView");
                int color = ((TextView) LauncherFeatures.get(row, "mBubbleText")).getCurrentTextColor();
                icon.setBackground(new RecentsLock.Lock(color, isLocked ? 1f : 0f));
            }
        } catch (Throwable error) { LauncherFeatures.logOnce("App lock: home folder menu row unavailable", error); }
    }

    static void addDrawerRow(final android.widget.LinearLayout card, final Object info, final Runnable dismiss) {
        try {
            final String key = key(info); if (key == null) return;
            final boolean isLocked = locked(card.getContext()).contains(key);
            FolderOptions.row(card, isLocked ? "Unlock folder" : "Lock folder", 1, false, new Runnable() { public void run() { dismiss.run(); change(key, name(info), isLocked); } });
            View row = card.getChildAt(card.getChildCount() - 1);
            if (row instanceof FrameLayout && ((FrameLayout) row).getChildAt(0) instanceof ImageView) {
                ImageView icon = (ImageView) ((FrameLayout) row).getChildAt(0);
                int color = LauncherFeatures.surface(card.getContext(), FrostedSurface.dark(card.getContext()) ? "system_on_surface_dark" : "system_on_surface_light", FrostedSurface.dark(card.getContext()) ? 0xffeee9f1 : 0xff202027);
                icon.setImageDrawable(new RecentsLock.Lock(color, isLocked ? 1f : 0f));
            }
        } catch (Throwable error) { LauncherFeatures.logOnce("App lock: drawer folder menu row unavailable", error); }
    }

    private static void change(String key, String name, boolean isLocked) {
        Activity a = LauncherFeatures.activity.get(); if (a == null) return;
        Intent i = new Intent().setComponent(new ComponentName(PrivacyLock.OUR, PrivacyLock.MENU_ACTIVITY));
        i.putExtra(PrivacyLock.EXTRA_MODE, isLocked ? MODE_UNLOCK : MODE_LOCK);
        i.putExtra(EXTRA_FOLDER, key); i.putExtra(EXTRA_NAME, name);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { a.startActivity(i, identity()); readAt = 0; } catch (Throwable error) { Module.log("App lock: unlock screen unavailable", error); }
    }

    private static final Map<String, Object[]> asking = new ConcurrentHashMap<String, Object[]>();
    private static boolean listening;

    private static void ask(Activity a, String key, String name, View icon) {
        final String token = java.util.UUID.randomUUID().toString();
        asking.put(token, new Object[] { key, new WeakReference<View>(icon) });
        if (!listening) {
            listening = true;
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String t = i.getStringExtra(PrivacyRecents.EXTRA_TOKEN); if (t == null) return;
                    Object[] job = asking.remove(t); if (job == null) return;
                    final String key = (String) job[0];
                    final View folderIcon = (View) ((WeakReference<?>) job[1]).get();
                    open.put(key, SystemClock.elapsedRealtime());
                    passing.put(key, Boolean.TRUE);
                    PrivacyBadges.unlocked(key);
                    if (folderIcon != null && folderIcon.isAttachedToWindow()) folderIcon.postDelayed(new Runnable() { public void run() {
                        if (folderIcon.isAttachedToWindow()) folderIcon.performClick(); else passing.remove(key);
                    } }, 240);
                    else passing.remove(key);
                }
            };
            try {
                Context app = a.getApplicationContext();
                try { Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class).invoke(app, r, new IntentFilter(ACTION_OK), 2); }
                catch (Throwable older) { app.registerReceiver(r, new IntentFilter(ACTION_OK)); }
                app.registerReceiver(new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { open.clear(); } }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            } catch (Throwable error) { listening = false; Module.log("App lock: folder answer unavailable", error); return; }
        }
        Haptics.tick(a);
        Intent i = new Intent().setComponent(new ComponentName(PrivacyLock.OUR, PrivacyLock.MENU_ACTIVITY));
        i.putExtra(PrivacyLock.EXTRA_MODE, MODE_OPEN); i.putExtra(EXTRA_FOLDER, key); i.putExtra(EXTRA_NAME, name);
        i.putExtra(PrivacyRecents.EXTRA_TOKEN, token);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { a.startActivity(i, identity()); } catch (Throwable error) { Module.log("App lock: unlock screen unavailable", error); }
    }

    private static android.os.Bundle identity() {
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            android.app.ActivityOptions.class.getMethod("setShareIdentityEnabled", boolean.class).invoke(o, true);
            return o.toBundle();
        } catch (Throwable ignored) { return null; }
    }
}
