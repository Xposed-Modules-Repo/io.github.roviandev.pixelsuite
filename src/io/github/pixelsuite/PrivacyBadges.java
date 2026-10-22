package io.github.pixelsuite;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

final class PrivacyBadges {
    private PrivacyBadges() {}
    static final String ACTION_UNLOCKED = "io.github.pixelsuite.APP_LOCK_UNLOCKED";
    private static final String FIELD = "pixelsuite_badge";
    private static final float SIZE = 0.38f;

    private static final Map<String, Boolean> open = new ConcurrentHashMap<String, Boolean>();
    private static final Map<String, Long> fresh = new ConcurrentHashMap<String, Long>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<View, Boolean> drawn = Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private static volatile String dragKey; private static volatile boolean dragging; private static volatile int dragGen; private static volatile long droppedAt;
    private static volatile boolean resumed; private static volatile int resumeGen;
    private static boolean listening;
    private static Set<String> lastApps = Collections.emptySet(), lastFolders = Collections.emptySet();

    static void install(ClassLoader cl) {
        int n = 0;
        try {
            Class<?> bubble = Xp.findClass("com.android.launcher3.BubbleTextView", cl);
            Xp.Callback draw = new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { drawOnApp((View) p.thisObject, (Canvas) p.args[0]); } catch (Throwable error) { LauncherFeatures.logOnce("App lock badge unavailable (icons)", error); }
                }
            };
            int d = Xp.hookAllMethods(bubble, "drawRunningAppIndicatorIfNecessary", draw).size();
            if (d == 0) d = Xp.hookAllMethods(bubble, "onDraw", draw).size();
            n += d;
            n += Xp.hookAllMethods(bubble, "setIconVisible", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { if (p.args.length == 1 && Boolean.TRUE.equals(p.args[0])) settled((View) p.thisObject); }
            }).size();
            Class<?> folderIcon = Xp.findClass("com.android.launcher3.folder.FolderIcon", cl);
            n += Xp.hookAllMethods(folderIcon, "dispatchDraw", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try { drawOnFolder((View) p.thisObject, (Canvas) p.args[0]); } catch (Throwable error) { LauncherFeatures.logOnce("App lock badge unavailable (folders)", error); }
                }
            }).size();
            n += Xp.hookAllMethods(folderIcon, "setIconVisible", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { if (p.args.length == 1 && Boolean.TRUE.equals(p.args[0])) settled((View) p.thisObject); }
            }).size();
        } catch (Throwable error) { Module.log("App lock badge unavailable", error); }
        try {
            Class<?> drag = Xp.findClass("com.android.launcher3.dragndrop.DragController", cl);
            n += Xp.hookAllMethods(drag, "startDrag", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { try { dragStarted(p); } catch (Throwable ignored) { } }
            }).size();
            Xp.Callback end = new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { if (dragging) { dragging = false; droppedAt = SystemClock.uptimeMillis(); } }
            };
            n += Xp.hookAllMethods(drag, "endDrag", end).size();
            try { n += Xp.hookAllMethods(Xp.findClass("com.android.launcher3.dragndrop.LauncherDragController", cl), "endDrag", end).size(); } catch (Throwable ignored) { }
        } catch (Throwable error) { Module.log("App lock badge: drag fade unavailable", error); }
        try {
            Class<?> launcher = Xp.findClass("com.android.launcher3.Launcher", cl);
            n += Xp.hookAllMethods(launcher, "onResume", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { try { onResume((Activity) p.thisObject); } catch (Throwable ignored) { } }
            }).size();
            n += Xp.hookAllMethods(launcher, "onPause", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { resumed = false; resumeGen++; }
            }).size();
        } catch (Throwable error) { Module.log("App lock badge: return tracking unavailable", error); }
        Module.log("App lock badges: " + n + " hooks");
    }

    private static boolean isFolder(Object info) {
        if (info == null) return false;
        try { if (DrawerFolders.isFolder(info)) return true; } catch (Throwable ignored) { }
        for (Class<?> k = info.getClass(); k != null && k != Object.class; k = k.getSuperclass()) if (k.getName().endsWith(".FolderInfo") || k.getName().endsWith(".CollectionInfo")) return true;
        return false;
    }
    private static final Map<String, Long> leaving = new ConcurrentHashMap<String, Long>();

    static String itemKey(Object info) {
        if (info == null) return null;
        if (isFolder(info)) return PrivacyFolders.key(info);
        try { if (((Number) Xp.getObjectField(info, "itemType")).intValue() != 0) return null; } catch (Throwable ignored) { }
        Object component = null; try { component = LauncherFeatures.call(info, "getTargetComponent"); } catch (Throwable ignored) { }
        if (!(component instanceof android.content.ComponentName)) return null;
        String pkg = ((android.content.ComponentName) component).getPackageName();
        return pkg == null ? null : "a:" + pkg;
    }
    private static boolean isLocked(Context c, String key) {
        return key.startsWith("a:") ? PrivacyLock.lockedNow(c).contains(key.substring(2)) : PrivacyFolders.locked(c).contains(key);
    }
    static String lockedKey(Context c, Object info) {
        if (PrivacyLock.lockedNow(c).isEmpty() && PrivacyFolders.locked(c).isEmpty()) return null;
        String key = itemKey(info);
        return key != null && isLocked(c, key) ? key : null;
    }
    private static String drawKey(View v, Object info) {
        Context c = v.getContext();
        boolean none = PrivacyLock.lockedNow(c).isEmpty() && PrivacyFolders.locked(c).isEmpty();
        if (none && leaving.isEmpty()) return null;
        String key = itemKey(info);
        if (key == null) return null;
        if (isLocked(c, key)) return key;
        Long at = leaving.get(key);
        Object b = Xp.getAdditionalInstanceField(v, FIELD);
        return at != null && SystemClock.uptimeMillis() - at < 3000 && b != null ? key : null;
    }

    private static final Rect bounds = new Rect();

    private static void drawOnApp(View v, Canvas canvas) {
        String name = v.getClass().getName();
        if (name.contains("DeepShortcut") || name.contains("IconButtonView")) return;
        Object info = v.getTag();
        String key = drawKey(v, info);
        if (key == null) { forget(v); return; }
        try { if (!Boolean.TRUE.equals(Xp.getObjectField(v, "mIsIconVisible"))) return; } catch (Throwable ignored) { }
        int size; try { size = ((Number) Xp.getObjectField(v, "mIconSize")).intValue(); } catch (Throwable t) { return; }
        Xp.callMethod(v, "getIconBounds", bounds);
        if (bounds.isEmpty()) return;
        canvas.save();
        canvas.translate(v.getScrollX(), v.getScrollY());
        paint(v, canvas, key, bounds.exactCenterX(), bounds.exactCenterY(), size);
        canvas.restore();
    }

    private static void drawOnFolder(View v, Canvas canvas) {
        Object info = null; try { info = Xp.getObjectField(v, "mInfo"); } catch (Throwable ignored) { }
        String key = drawKey(v, info);
        if (key == null) { forget(v); return; }
        try { if (!Boolean.TRUE.equals(Xp.getObjectField(v, "mBackgroundIsVisible"))) return; } catch (Throwable ignored) { }
        Xp.callMethod(v, "getPreviewBounds", bounds);
        if (bounds.isEmpty()) return;
        float size = bounds.width() / 1.125f;
        paint(v, canvas, key, bounds.exactCenterX(), bounds.exactCenterY(), size);
    }

    private static void forget(View v) { if (Xp.getAdditionalInstanceField(v, FIELD) != null) { Badge b = (Badge) Xp.getAdditionalInstanceField(v, FIELD); b.cancel(); Xp.setAdditionalInstanceField(v, FIELD, null); } }

    private static void paint(final View v, Canvas canvas, String key, float cx, float cy, float iconSize) {
        listen(v.getContext());
        drawn.put(v, Boolean.TRUE);
        Badge b = (Badge) Xp.getAdditionalInstanceField(v, FIELD);
        long now = SystemClock.uptimeMillis();
        if (b == null || !key.equals(b.key)) {
            if (b != null) b.cancel();
            b = new Badge(v, key);
            b.gen = dragGen;
            Long at = fresh.get(key);
            if (at != null && now - at < 2500) { b.shown = 0f; b.show(1f, 520, new Spring(0.45f, 520)); }
            Xp.setAdditionalInstanceField(v, FIELD, b);
        }
        if (key.equals(dragKey) && b.gen != dragGen) {
            if (dragging) { if (b.target != 0f) b.show(0f, 160, null); }
            else { b.gen = dragGen; if (now - droppedAt < 2500) { b.shown = 0f; b.show(1f, 520, new Spring(0.45f, 520)); } }
        }
        if (!isLocked(v.getContext(), key)) {
            if (!b.leaving) b.leave();
            else if (b.gone) { forget(v); return; }
        } else {
            if (b.leaving) { b.cancel(); b.leaving = false; b.gone = false; b.show(1f, 520, new Spring(0.45f, 520)); }
            boolean unlocked = open.containsKey(key);
            if (unlocked != b.unlockTarget) { b.unlockTarget = unlocked; b.turn(unlocked ? 1f : 0f); }
        }
        if (b.shown <= 0.01f) return;
        float s = iconSize * SIZE * b.shown;
        glyph(v.getContext()).draw(canvas, cx, cy, s, b.opened, Math.min(1f, b.shown));
    }

    private static final class Badge {
        final java.lang.ref.WeakReference<View> view; final String key;
        float shown = 1f, target = 1f, opened; boolean unlockTarget, leaving, gone; int gen;
        ValueAnimator showing, turning;
        Badge(View v, String key) { this.view = new java.lang.ref.WeakReference<View>(v); this.key = key; unlockTarget = open.containsKey(key); opened = unlockTarget ? 1f : 0f; }
        void show(float to, long ms, TimeInterpolator spring) {
            target = to;
            if (showing != null) showing.cancel();
            showing = ValueAnimator.ofFloat(shown, to); showing.setDuration(ms);
            if (spring != null) showing.setInterpolator(spring);
            showing.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator a) { shown = (Float) a.getAnimatedValue(); redraw(); } });
            showing.start();
        }
        void turn(float to) {
            if (turning != null) turning.cancel();
            turning = ValueAnimator.ofFloat(opened, to);
            turning.setDuration(to > 0.5f ? 420 : 560);
            turning.setInterpolator(new Spring(to > 0.5f ? 0.55f : 0.4f, to > 0.5f ? 420 : 560));
            turning.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator a) { opened = (Float) a.getAnimatedValue(); redraw(); } });
            turning.start();
        }
        void leave() {
            leaving = true; unlockTarget = true;
            if (opened < 0.9f) turn(1f);
            main.postDelayed(new Runnable() { public void run() {
                if (!leaving) return;
                show(0f, 300, new android.view.animation.AccelerateInterpolator(1.4f));
                showing.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator a) { if (leaving) { gone = true; redraw(); } } });
            } }, opened < 0.9f ? 320 : 60);
        }
        void redraw() { View v = view.get(); if (v != null) v.invalidate(); else cancel(); }
        void cancel() { if (showing != null) showing.cancel(); if (turning != null) turning.cancel(); }
    }

    private static final class Spring implements TimeInterpolator {
        final float damping; final long ms; final float w;
        Spring(float damping, long ms) { this.damping = damping; this.ms = ms; this.w = (float) (2 * Math.PI / (ms / 1000f * 0.85f)); }
        public float getInterpolation(float t) {
            if (t >= 1f) return 1f;
            double time = t * ms / 1000.0, z = damping, wd = w * Math.sqrt(1 - z * z);
            double env = Math.exp(-z * w * time);
            return (float) (1 - env * (Math.cos(wd * time) + z * w / wd * Math.sin(wd * time)));
        }
    }

    private static Glyph light, dark;
    private static Glyph glyph(Context c) {
        boolean d = FrostedSurface.dark(c);
        float density = c.getResources().getDisplayMetrics().density;
        if (d) { if (dark == null) dark = new Glyph(0xffffffff, 0x8c000000, density); return dark; }
        if (light == null) light = new Glyph(0xff1c1b1f, 0xb3ffffff, density); return light;
    }

    static android.graphics.Picture withPadlock(Context c, android.graphics.Picture icon, int size) {
        android.graphics.Picture out = new android.graphics.Picture();
        Canvas canvas = out.beginRecording(size, size);
        canvas.drawPicture(icon);
        glyph(c).draw(canvas, size / 2f, size / 2f, size * 0.92f * SIZE, 0f, 1f);
        out.endRecording();
        return out;
    }

    static final class Glyph {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int color, halo; final float density;
        final Path path = new Path(); final RectF rect = new RectF();
        Glyph(int color, int halo, float density) {
            this.color = color; this.halo = halo; this.density = density;
            fill.setStyle(Paint.Style.FILL);
            line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND); line.setStrokeJoin(Paint.Join.ROUND);
        }
        void draw(Canvas c, float cx, float cy, float size, float opened, float alpha) {
            float w = size, h = size, x0 = cx - w / 2f, y0 = cy - h / 2f;
            float open = Math.max(-0.2f, Math.min(1.2f, opened));
            float hw = Math.max(1f * density, w * .07f);
            float r = w * .19f, mid = x0 + w * .5f, lift = h * .12f * open, top = y0 + h * .2f - lift + r, legR = y0 + h * .52f, legL = y0 + h * .52f - (h * .18f) * Math.max(0f, open);
            path.reset();
            path.moveTo(mid - r, legL - lift);
            path.lineTo(mid - r, top);
            rect.set(mid - r, top - r, mid + r, top + r);
            path.arcTo(rect, 180, 180, false);
            path.lineTo(mid + r, legR);
            int a = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
            fill.setColor(halo); fill.setAlpha(Math.round(Color.alpha(halo) * a / 255f));
            line.setColor(halo); line.setAlpha(fill.getAlpha()); line.setStrokeWidth(w * .11f + 2 * hw);
            c.drawPath(path, line);
            rect.set(x0 + w * .18f - hw, y0 + h * .5f - hw, x0 + w * .82f + hw, y0 + h * .96f + hw);
            c.drawRoundRect(rect, w * .1f + hw, w * .1f + hw, fill);
            line.setColor(color); line.setAlpha(a); line.setStrokeWidth(w * .11f);
            c.drawPath(path, line);
            fill.setColor(color); fill.setAlpha(a);
            rect.set(x0 + w * .18f, y0 + h * .5f, x0 + w * .82f, y0 + h * .96f);
            c.drawRoundRect(rect, w * .1f, w * .1f, fill);
        }
    }
    private static final class Color { static int alpha(int c) { return c >>> 24; } }

    private static final class Fading extends Drawable {
        final Glyph glyph; float shown = 1f; final float size;
        Fading(Glyph glyph, float size) { this.glyph = glyph; this.size = size; }
        @Override public void draw(Canvas c) { Rect b = getBounds(); if (shown > 0.01f) glyph.draw(c, b.exactCenterX(), b.exactCenterY(), size * shown, 0f, shown); }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static void dragStarted(Xp.Param p) {
        Object info = null; View original = null;
        for (Object a : p.args) {
            if (info == null && a != null && a.getClass().getName().contains("Info") && !(a instanceof View)) {
                for (Class<?> k = a.getClass(); k != null; k = k.getSuperclass()) if (k.getName().equals("com.android.launcher3.model.data.ItemInfo")) { info = a; break; }
            }
            if (original == null && a instanceof View && (a.getClass().getName().contains("BubbleTextView") || a.getClass().getName().contains("FolderIcon") || a.getClass().getName().contains("Icon"))) original = (View) a;
        }
        final View dragView = p.getResult() instanceof View ? (View) p.getResult() : null;
        if (info == null || dragView == null) return;
        String key = lockedKey(dragView.getContext(), info);
        if (key == null) return;
        dragKey = key; dragGen++; dragging = true;
        float icon = 0;
        if (original != null) {
            try { icon = ((Number) Xp.getObjectField(original, "mIconSize")).floatValue(); } catch (Throwable ignored) { }
            if (icon == 0) try { Object bg = Xp.getObjectField(original, "mBackground"); icon = ((Number) Xp.getObjectField(bg, "previewSize")).floatValue(); } catch (Throwable ignored) { }
        }
        final float size = (icon > 0 ? icon : Math.min(dragView.getWidth(), dragView.getHeight())) * SIZE;
        final Fading f = new Fading(glyph(dragView.getContext()), size);
        dragView.post(new Runnable() { public void run() {
            try {
                if (dragView.getWidth() == 0) return;
                f.setBounds(0, 0, dragView.getWidth(), dragView.getHeight());
                dragView.getOverlay().add(f);
                ValueAnimator out = ValueAnimator.ofFloat(1f, 0f); out.setDuration(180); out.setStartDelay(60);
                out.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator a) { f.shown = (Float) a.getAnimatedValue(); dragView.invalidate(); } });
                out.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator a) { try { dragView.getOverlay().remove(f); } catch (Throwable ignored) { } } });
                out.start();
            } catch (Throwable ignored) { }
        } });
        refresh();
    }

    static void unlocked(String key) {
        if (key == null) return;
        open.put(key, Boolean.TRUE);
        refresh();
    }
    static void relock(String key) { if (open.remove(key) != null) refresh(); }

    static void folderClosed(Context c, final String key) {
        if (!open.containsKey(key)) return;
        if (PrivacyLock.graceNow(c)) { main.postDelayed(new Runnable() { public void run() { relock(key); } }, PrivacyLock.AWAY_MS); return; }
        pendingClose.put(key, SystemClock.uptimeMillis());
        main.postDelayed(new Runnable() { public void run() { if (pendingClose.remove(key) != null) relock(key); } }, 750);
    }
    private static final Map<String, Long> pendingClose = new ConcurrentHashMap<String, Long>();

    private static void onResume(Activity a) {
        resumed = true; final int gen = ++resumeGen;
        listen(a);
        final boolean grace = PrivacyLock.graceNow(a);
        for (final String key : open.keySet()) {
            if (!key.startsWith("a:")) continue;
            if (grace) { main.postDelayed(new Runnable() { public void run() { relock(key); } }, PrivacyLock.AWAY_MS); continue; }
            pendingClose.put(key, SystemClock.uptimeMillis());
            main.postDelayed(new Runnable() { public void run() {
                if (resumed && resumeGen == gen && pendingClose.remove(key) != null) relock(key);
            } }, 900);
        }
    }

    private static void settled(View v) {
        try {
            Object info = v.getTag(); if (v.getClass().getName().contains("FolderIcon")) info = Xp.getObjectField(v, "mInfo");
            final String key = lockedKey(v.getContext(), info);
            if (key == null || !open.containsKey(key) || pendingClose.get(key) == null) return;
            if (key.startsWith("a:") && !resumed) return;
            main.postDelayed(new Runnable() { public void run() { if (pendingClose.remove(key) != null) relock(key); } }, 140);
        } catch (Throwable ignored) { }
    }

    private static void listen(Context any) {
        if (listening) return;
        listening = true;
        final Context app = any.getApplicationContext();
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String pkg = i.getStringExtra(PrivacyLock.EXTRA_PACKAGE);
                    if (PrivacyLock.validPackage(pkg)) unlocked("a:" + pkg);
                }
            };
            try { Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class).invoke(app, r, new IntentFilter(ACTION_UNLOCKED), 2); }
            catch (Throwable older) { app.registerReceiver(r, new IntentFilter(ACTION_UNLOCKED)); }
            app.registerReceiver(new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { open.clear(); pendingClose.clear(); refresh(); } }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            lastApps = new HashSet<String>(PrivacyLock.lockedNow(app)); lastFolders = new HashSet<String>(PrivacyFolders.locked(app));
            ContentObserver changed = new ContentObserver(main) {
                @Override public void onChange(boolean self) {
                    PrivacyLock.changed(); PrivacyFolders.changed();
                    Set<String> apps = PrivacyLock.lockedNow(app), folders = PrivacyFolders.locked(app);
                    long now = SystemClock.uptimeMillis();
                    for (String p : apps) if (!lastApps.contains(p)) fresh.put("a:" + p, now);
                    for (String k : folders) if (!lastFolders.contains(k)) fresh.put(k, now);
                    for (String p : lastApps) if (!apps.contains(p)) leaving.put("a:" + p, now);
                    for (String k : lastFolders) if (!folders.contains(k)) leaving.put(k, now);
                    boolean appsChanged = !lastApps.equals(apps);
                    lastApps = new HashSet<String>(apps); lastFolders = new HashSet<String>(folders);
                    refreshAll();
                    if (appsChanged) try { GoogleAliases.publish(); } catch (Throwable ignored) { }
                }
            };
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(PrivacyLock.KEY), false, changed);
            app.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(PrivacyFolders.KEY), false, changed);
        } catch (Throwable error) { listening = false; Module.log("App lock badge: changes not followed", error); }
    }

    private static void refresh() {
        main.post(new Runnable() { public void run() {
            View[] views; synchronized (drawn) { views = drawn.keySet().toArray(new View[0]); }
            for (View v : views) if (v != null) v.invalidate();
        } });
    }
    private static void refreshAll() {
        refresh();
        Activity a = LauncherFeatures.activity.get(); if (a == null) return;
        try { invalidateTree(a.getWindow().getDecorView()); } catch (Throwable ignored) { }
    }
    private static void invalidateTree(View v) {
        String name = v.getClass().getName();
        if (name.contains("BubbleTextView") || name.contains("FolderIcon") || name.contains("Icon")) v.invalidate();
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i)); }
    }
}
