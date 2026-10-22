package io.github.pixelsuite;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2.0.6-preview59 (owner's spec, app lock step 3): recents for apps under the app lock (the fingerprint lock, not the
 * recents card lock, which stays as it is):
 *  - their preview is heavily blurred, with a lock in its middle;
 *  - tapping the card asks first; unlocked, the app opens from it as usual;
 *  - "Split screen" and "Save app pair" are not offered for them;
 *  - closing them works as usual (a swipe or Clear all), and locks them again (see PrivacyLock).
 */
final class PrivacyRecents {
    private PrivacyRecents() {}
    static final String MODE_RECENTS = "recents";
    static final String ACTION_RECENTS_OK = "io.github.pixelsuite.APP_LOCK_RECENTS_OK", EXTRA_TOKEN = "pixelsuite_token";
    private static final String TAG_LOCK = "pixelsuite_privacy_lock";

    static void install(ClassLoader cl) {
        int n = 0;
        try {
            final Class<?> taskView = Xp.findClass("com.android.quickstep.views.TaskView", cl);
            n += Xp.hookAllMethods(taskView, "onBind", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    final View tv = (View) p.thisObject;
                    // preview61 (owner: the content showed a moment before the blur): blurred at once, and again once
                    // the card is on screen
                    try { decorate(tv); } catch (Throwable ignored) { }
                    tv.post(new Runnable() { public void run() { decorate(tv); } });
                }
            }).size();
            // Coming to recents from a locked app, the launcher first shows the live app in its card (no picture to
            // blur): for a locked app it switches to the picture straight away.
            Class<?> recents = Xp.findClass("com.android.quickstep.views.RecentsView", cl);
            n += Xp.hookAllMethods(recents, "setEnableDrawingLiveTile", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 1 || !Boolean.TRUE.equals(p.args[0])) return;
                        final Object rv = p.thisObject;
                        Object running = Xp.callMethod(rv, "getRunningTaskView");
                        if (!(running instanceof View) || lockedPackage((View) running) == null) return;
                        decorate((View) running);
                        Xp.callMethod(rv, "switchToScreenshot", (Runnable) new Runnable() { public void run() {
                            try { Xp.callMethod(rv, "finishRecentsAnimation", true, false, (Runnable) null); } catch (Throwable ignored) { }
                        } });
                    } catch (Throwable error) { LauncherFeatures.logOnce("App lock: recents picture switch unavailable", error); }
                }
            }).size();
            n += Xp.hookAllMethods(taskView, "onLayout", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { try { place((View) p.thisObject); } catch (Throwable ignored) { } }
            }).size();
            n += Xp.hookAllMethods(taskView, "onRecycle", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { try { clear((View) p.thisObject); } catch (Throwable ignored) { } }
            }).size();
            // A tap on the card asks first (the card's own click, before the launcher opens the app).
            n += Xp.hookAllMethods(taskView, "onClick", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 0) return;
                        View tv = (View) p.thisObject;
                        String pkg = lockedPackage(tv); if (pkg == null) return;
                        if (passing.containsKey(tv)) return;   // confirmed just now
                        p.setResult(null);
                        ask(tv, pkg);
                    } catch (Throwable error) { LauncherFeatures.logOnce("App lock: recents tap check unavailable", error); }
                }
            }).size();
            // No split screen (and no app pair) for a locked app.
            n += Xp.hookAllMethods(Xp.findClass("com.android.quickstep.TaskOverlayFactory", cl), "getEnabledShortcuts", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (!(p.getResult() instanceof List) || p.args.length < 2) return;
                        String pkg = packageOf(p.args[1]); if (pkg == null) return;
                        Activity a = LauncherFeatures.activity.get(); if (a == null || !PrivacyLock.lockedNow(a).contains(pkg)) return;
                        List<Object> kept = new ArrayList<Object>();
                        for (Object item : (List<?>) p.getResult()) {
                            String name = item == null ? "" : item.getClass().getName();
                            if (name.contains("SplitSelectSystemShortcut") || name.contains("SaveAppPairSystemShortcut")) continue;
                            kept.add(item);
                        }
                        p.setResult(kept);
                    } catch (Throwable ignored) { }
                }
            }).size();
        } catch (Throwable error) { Module.log("App lock: recents unavailable", error); }
        Module.log("App lock recents: " + n + " hooks");
    }

    private static String packageOf(Object container) {
        String id = RecentsLock.identity(container);
        return id == null ? null : id.substring(0, id.indexOf('|'));
    }
    /** The first app of the card that is under the app lock, or null. */
    static String lockedPackage(View tv) {
        Set<String> list = PrivacyLock.lockedNow(tv.getContext()); if (list.isEmpty()) return null;
        for (Object c : RecentsLock.containers(tv)) { String p = packageOf(c); if (p != null && list.contains(p)) return p; }
        return null;
    }

    // ------------------------------------------------------------------------------------------ the blur and the lock

    private static final Map<View, Boolean> blurred = new WeakHashMap<View, Boolean>();

    static void decorate(View tv) {
        if (!(tv instanceof ViewGroup)) return;
        Set<String> list = PrivacyLock.lockedNow(tv.getContext());
        List<?> containers = RecentsLock.containers(tv);
        for (int i = 0; i < containers.size(); i++) {
            Object c = containers.get(i);
            String pkg = packageOf(c);
            boolean lock = pkg != null && list.contains(pkg);
            View snapshot = null; try { snapshot = (View) Xp.callMethod(c, "getSnapshotView"); } catch (Throwable ignored) { }
            if (snapshot != null) blur(snapshot, lock);
            View glyph = glyphFor((ViewGroup) tv, i);
            if (lock && glyph == null) {
                glyph = makeGlyph(tv.getContext());
                glyph.setTag(TAG_LOCK + i);
                ((ViewGroup) tv).addView(glyph, new FrameLayout.LayoutParams(size(tv), size(tv), Gravity.TOP | Gravity.LEFT));
                ((ViewGroup) tv).setClipChildren(false);
            } else if (!lock && glyph != null) ((ViewGroup) tv).removeView(glyph);
        }
        for (int i = containers.size(); i < 3; i++) { View g = glyphFor((ViewGroup) tv, i); if (g != null) ((ViewGroup) tv).removeView(g); }
        place(tv);
    }

    private static int size(View v) { return Math.round(56 * v.getResources().getDisplayMetrics().density); }
    private static View glyphFor(ViewGroup tv, int slot) { return tv.findViewWithTag(TAG_LOCK + slot); }

    /** A lock in a soft dark circle, white, in the middle of the blurred preview. */
    private static View makeGlyph(Context c) {
        ImageView v = new ImageView(c);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(0x73000000);
        v.setBackground(circle);
        int pad = Math.round(14 * c.getResources().getDisplayMetrics().density);
        v.setPadding(pad, pad, pad, pad);
        v.setImageDrawable(new RecentsLock.Lock(0xffffffff, 1f));
        v.setClickable(false); v.setFocusable(false);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /** Heavy blur over the preview (Android 12+: the view's own blur, so every picture the launcher draws there is blurred). */
    private static void blur(View snapshot, boolean on) {
        boolean was = Boolean.TRUE.equals(blurred.get(snapshot));
        if (on == was) return;
        try {
            Object effect = null;
            if (on) {
                float r = 42 * snapshot.getResources().getDisplayMetrics().density;
                Class<?> re = Class.forName("android.graphics.RenderEffect");
                effect = re.getMethod("createBlurEffect", float.class, float.class, android.graphics.Shader.TileMode.class).invoke(null, r, r, android.graphics.Shader.TileMode.CLAMP);
            }
            View.class.getMethod("setRenderEffect", Class.forName("android.graphics.RenderEffect")).invoke(snapshot, effect);
            if (on) blurred.put(snapshot, Boolean.TRUE); else blurred.remove(snapshot);
        } catch (Throwable error) { LauncherFeatures.logOnce("App lock: recents blur unavailable", error); }
    }

    /** Each lock sits in the middle of its app's preview (as the user sees it, also on a card turned sideways). */
    static void place(View tv) {
        if (!(tv instanceof ViewGroup)) return;
        List<?> containers = RecentsLock.containers(tv);
        for (int i = 0; i < containers.size(); i++) {
            View g = glyphFor((ViewGroup) tv, i); if (g == null) continue;
            View content = null; try { content = (View) Xp.callMethod(containers.get(i), "getTaskContentView"); } catch (Throwable ignored) { }
            if (content == null || content.getWidth() == 0) continue;
            RectF r = RecentsLandscape.drawn(content);
            float s = size(tv);
            g.setTranslationX(r.centerX() - s / 2f - g.getLeft());
            g.setTranslationY(r.centerY() - s / 2f - g.getTop());
            float rot = 0; try { View chip = (View) Xp.callMethod(containers.get(i), "getIconView"); if (chip != null) rot = chip.getRotation(); } catch (Throwable ignored) { }
            g.setRotation(rot);
            g.bringToFront();
        }
    }

    private static void clear(View tv) {
        if (!(tv instanceof ViewGroup)) return;
        for (int i = 0; i < 3; i++) { View g = glyphFor((ViewGroup) tv, i); if (g != null) ((ViewGroup) tv).removeView(g); }
        for (Object c : RecentsLock.containers(tv)) try { View s = (View) Xp.callMethod(c, "getSnapshotView"); if (s != null) blur(s, false); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------------------------------ tap to unlock

    private static final Map<String, WeakReference<View>> asking = new ConcurrentHashMap<String, WeakReference<View>>();
    private static final Map<View, Boolean> passing = new WeakHashMap<View, Boolean>();
    private static boolean listening;

    private static void ask(View tv, String pkg) {
        Activity a = LauncherFeatures.activity.get(); if (a == null) return;
        Haptics.tick(a);
        final String token = java.util.UUID.randomUUID().toString();
        asking.put(token, new WeakReference<View>(tv));
        if (!listening) {
            listening = true;
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String t = i.getStringExtra(EXTRA_TOKEN); if (t == null) return;
                    WeakReference<View> ref = asking.remove(t);
                    final View card = ref == null ? null : ref.get();
                    if (card == null) return;
                    passing.put(card, Boolean.TRUE);
                    try { Xp.callMethod(card, "onClick"); } catch (Throwable error) { Module.log("App lock: opening from recents failed", error); }
                    card.postDelayed(new Runnable() { public void run() { passing.remove(card); } }, 2000);
                }
            };
            try {
                Context app = a.getApplicationContext();
                try { Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class).invoke(app, r, new IntentFilter(ACTION_RECENTS_OK), 2); }   // RECEIVER_EXPORTED: from Pixel Suite
                catch (Throwable older) { app.registerReceiver(r, new IntentFilter(ACTION_RECENTS_OK)); }
            } catch (Throwable error) { listening = false; Module.log("App lock: recents answer unavailable", error); return; }
        }
        Intent i = new Intent().setComponent(new ComponentName(PrivacyLock.OUR, PrivacyLock.MENU_ACTIVITY));
        i.putExtra(PrivacyLock.EXTRA_MODE, MODE_RECENTS); i.putExtra(PrivacyLock.EXTRA_PACKAGE, pkg); i.putExtra(EXTRA_TOKEN, token);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        android.os.Bundle options = null;
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            android.app.ActivityOptions.class.getMethod("setShareIdentityEnabled", boolean.class).invoke(o, true);
            options = o.toBundle();
        } catch (Throwable ignored) { }
        try { a.startActivity(i, options); } catch (Throwable error) { Module.log("App lock: unlock screen unavailable", error); }
    }
}
