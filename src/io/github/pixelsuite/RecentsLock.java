package io.github.pixelsuite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

final class RecentsLock {
    static final String KEY = "pixelsuite_recents_lock";
    private static final Feature.Cached sOn = new Feature.Cached(KEY);
    private static final String QS = "com.android.quickstep.";
    private static final String PREFS = "pixelsuite_launcher", PREF_KEY = "recents_locked";

    private static ClassLoader cl;
    private static Class<?> cRecents, cTaskView, cRemove, cTaskData;
    private static final Set<String> locked = new HashSet<String>();
    private static SharedPreferences prefs;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<Integer> partial = new HashSet<Integer>();
    private static final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static boolean failedLogged;

    private RecentsLock() {}

    static void install(ClassLoader loader) {
        cl = loader;
        int n = 0;
        try {
            cRecents = Xp.findClass(QS + "views.RecentsView", cl);
            cTaskView = Xp.findClass(QS + "views.TaskView", cl);
            cRemove = Xp.findClass(QS + "TaskViewShortFactory$RemoveTaskSystemShortcut", cl);
            cTaskData = Xp.findClass(QS + "views.RecentsDismissUtils$DismissedTaskData", cl);
        } catch (Throwable error) { Module.log("Recents lock unavailable", error); return; }
        n += menuHooks();
        n += snapshotHook();
        n += dismissHooks();
        n += badgeHooks();
        Module.log("Recents lock: " + n + " hooks");
    }

    static boolean on(Context c) { try { return c != null && sOn.on(c); } catch (Throwable t) { return false; } }

    private static void fail(String what, Throwable error) {
        if (failedLogged) return;
        failedLogged = true;
        Module.log("Recents lock: " + what, error);
    }

    private static void load(Context c) {
        if (prefs != null || c == null) return;
        try {
            prefs = c.getApplicationContext().getSharedPreferences(PREFS, 0);
            Set<String> saved = prefs.getStringSet(PREF_KEY, null);
            if (saved != null) locked.addAll(saved);
        } catch (Throwable error) { fail("locks could not be read", error); }
    }
    private static void save() {
        try { if (prefs != null) prefs.edit().putStringSet(PREF_KEY, new HashSet<String>(locked)).apply(); } catch (Throwable error) { fail("locks could not be saved", error); }
    }

    static String identity(Object container) {
        try {
            Object task = Xp.callMethod(container, "getTask"); if (task == null) return null;
            Object key = Xp.getObjectField(task, "key"); if (key == null) return null;
            String pkg = (String) Xp.callMethod(key, "getPackageName"); if (pkg == null) return null;
            return pkg + "|" + Xp.getObjectField(key, "userId");
        } catch (Throwable t) { return null; }
    }
    static int taskId(Object container) {
        try { return (Integer) Xp.getObjectField(Xp.getObjectField(Xp.callMethod(container, "getTask"), "key"), "id"); } catch (Throwable t) { return -1; }
    }
    static List<?> containers(Object taskView) {
        try { Object l = Xp.callMethod(taskView, "getTaskContainers"); return l instanceof List ? (List<?>) l : new ArrayList<Object>(); } catch (Throwable t) { return new ArrayList<Object>(); }
    }
    static boolean isLocked(Object container) {
        String id = identity(container);
        return id != null && locked.contains(id);
    }
    static int lockedCount(Object taskView) {
        load(((View) taskView).getContext());
        if (locked.isEmpty()) return 0;
        int n = 0;
        for (Object c : containers(taskView)) if (isLocked(c)) n++;
        return n;
    }
    static boolean active(Object taskView) {
        return taskView instanceof View && on(((View) taskView).getContext()) && lockedCount(taskView) > 0;
    }
    static List<View> taskViews(Object recents) {
        List<View> out = new ArrayList<View>();
        try { for (Object o : (Iterable<?>) Xp.callMethod(recents, "getTaskViews")) if (o instanceof View) out.add((View) o); }
        catch (Throwable t) {
            if (recents instanceof ViewGroup) { ViewGroup g = (ViewGroup) recents; for (int i = 0; i < g.getChildCount(); i++) if (cTaskView.isInstance(g.getChildAt(i))) out.add(g.getChildAt(i)); }
        }
        return out;
    }

    private static void toggle(View taskView, Object container) {
        String id = identity(container); if (id == null) return;
        load(taskView.getContext());
        boolean now = !locked.contains(id);
        if (now) { locked.add(id); fresh.add(id); } else locked.remove(id);
        save();
        Object recents = recentsOf(taskView);
        for (View tv : recents == null ? java.util.Collections.singletonList(taskView) : taskViews(recents)) refresh(tv, !now ? id : null);
    }

    private static Object recentsOf(View taskView) {
        try { return Xp.callMethod(taskView, "getRecentsView"); } catch (Throwable t) {
            for (ViewParent p = taskView.getParent(); p != null; p = p.getParent()) if (cRecents.isInstance(p)) return p;
            return null;
        }
    }

    private static int snapshotHook() {
        try {
            final Class<?> thumb = Xp.findClass(QS + "task.thumbnail.TaskThumbnailView", cl);
            Xp.Callback after = new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { fill(p.thisObject); }
            };
            Xp.Callback later = new Xp.Callback() {
                @Override protected void afterHookedMethod(final Xp.Param p) { ((View) p.thisObject).post(new Runnable() { public void run() { fill(p.thisObject); } }); }
            };
            int n = Xp.hookAllMethods(thumb, "setImageMatrix", after).size();
            n += Xp.hookAllMethods(thumb, "setState", new Xp.Callback() {
                @Override protected void afterHookedMethod(final Xp.Param p) {
                    try { if (p.args.length >= 2 && p.args[1] instanceof Integer) Xp.setAdditionalInstanceField(p.thisObject, "pixelsuite_task", p.args[1]); } catch (Throwable ignored) { }
                    fill(p.thisObject);
                    ((View) p.thisObject).post(new Runnable() { public void run() { fill(p.thisObject); } });
                }
            }).size();
            n += Xp.hookAllMethods(thumb, "onSizeChanged", later).size();
            Xp.Callback launching = new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) { settleForLaunch((View) p.thisObject); }
            };
            n += Xp.hookAllMethods(cTaskView, "launchWithAnimation", launching).size();
            n += Xp.hookAllMethods(cTaskView, "launchWithoutAnimation", launching).size();
            try {
                n += Xp.hookAllMethods(Xp.findClass(QS + "TopTaskTracker", cl), "onTaskStageChanged", new Xp.Callback() {
                    @Override protected void afterHookedMethod(Xp.Param p) {
                        try {
                            if (p.args.length == 3 && Boolean.TRUE.equals(p.args[2]) && ((Integer) p.args[1]) != -1)
                                synchronized (wasSplit) { wasSplit.add((Integer) p.args[0]); }
                        } catch (Throwable ignored) { }
                    }
                }).size();
            } catch (Throwable error) { Module.log("Recents lock: split tracking unavailable", error); }
            return n;
        } catch (Throwable error) { Module.log("Recents lock: picture fill unavailable", error); return 0; }
    }
    private static final Set<Integer> wasSplit = java.util.Collections.newSetFromMap(RecentsLock.<Boolean>lastUsedIds(64));
    private static <V> java.util.Map<Integer, V> lastUsedIds(final int max) {
        return new java.util.LinkedHashMap<Integer, V>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer, V> eldest) { return size() > max; }
        };
    }
    private static final WeakHashMap<android.graphics.Bitmap, Band> bands = new WeakHashMap<android.graphics.Bitmap, Band>();
    private static final Band NO_BAND = new Band(0, 0, null);
    private static final WeakHashMap<android.graphics.Bitmap, Boolean> made = new WeakHashMap<android.graphics.Bitmap, Boolean>();
    private static final class Band {
        final int top, bottom; final android.graphics.Bitmap card;
        Band(int top, int bottom, android.graphics.Bitmap card) { this.top = top; this.bottom = bottom; this.card = card; }
    }

    private static void fill(Object thumbnail) {
        try {
            if (!(thumbnail instanceof View) || !on(((View) thumbnail).getContext())) return;
            android.widget.ImageView image = null;
            try { image = (android.widget.ImageView) Xp.callMethod(thumbnail, "getThumbnailView"); } catch (Throwable ignored) { }
            if (image == null && thumbnail instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) thumbnail;
                for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) instanceof android.widget.ImageView && (((android.widget.ImageView) g.getChildAt(i)).getDrawable() instanceof android.graphics.drawable.BitmapDrawable || ((android.widget.ImageView) g.getChildAt(i)).getDrawable() instanceof Made)) { image = (android.widget.ImageView) g.getChildAt(i); break; }
            }
            Drawable picture = image == null ? null : image.getDrawable();
            if (picture == null || image.getVisibility() != View.VISIBLE) { if (image != null) unband(image); return; }
            float bw = picture.getIntrinsicWidth(), bh = picture.getIntrinsicHeight();
            float vw = image.getWidth() > 0 ? image.getWidth() : ((View) thumbnail).getWidth(), vh = image.getHeight() > 0 ? image.getHeight() : ((View) thumbnail).getHeight();
            android.graphics.Matrix current = image.getImageMatrix();
            float[] m = new float[9]; current.getValues(m);
            if (picture instanceof Made) {
                if (m[android.graphics.Matrix.MSKEW_X] != 0f || m[android.graphics.Matrix.MSKEW_Y] != 0f) {
                    Object o = Xp.getAdditionalInstanceField(image, "pixelsuite_band_original");
                    if (o instanceof Drawable) image.setImageDrawable((Drawable) o);
                    unband(image);
                    return;
                }
                if (((Made) picture).squat) fitWidth(image, bw, bh, vw, vh, m);
                return;
            }
            if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0 || bw > bh) { unband(image); return; }
            if (m[android.graphics.Matrix.MSKEW_X] != 0f || m[android.graphics.Matrix.MSKEW_Y] != 0f) { unband(image); return; }
            if ((bh / bw) > (vh / vw) * 0.7f) { band((View) thumbnail, image, picture, m, bw, bh, vw, vh); return; }
            if (squat((View) thumbnail, image, picture, m, bw, bh, vw, vh)) return;
            unband(image);
            float scale = Math.max(vw / bw, vh / bh);
            float tx = (vw - bw * scale) / 2f, ty = (vh - bh * scale) / 2f;
            if (Math.abs(m[android.graphics.Matrix.MSCALE_X] - scale) < 0.001f && Math.abs(m[android.graphics.Matrix.MTRANS_X] - tx) < 0.5f && Math.abs(m[android.graphics.Matrix.MTRANS_Y] - ty) < 0.5f) return;
            android.graphics.Matrix fill = new android.graphics.Matrix();
            fill.setScale(scale, scale);
            fill.postTranslate(tx, ty);
            image.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
            image.setImageMatrix(fill);
        } catch (Throwable ignored) { }
    }

    private static void band(final View thumbnail, android.widget.ImageView image, Drawable picture, float[] m, float bw, float bh, float vw, float vh) {
        int id = singleTaskOf(thumbnail);
        boolean split; synchronized (wasSplit) { split = id >= 0 && wasSplit.contains(id); }
        if (!split || !(picture instanceof android.graphics.drawable.BitmapDrawable)) { unband(image); return; }
        final android.graphics.Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) picture).getBitmap();
        if (bitmap == null) { unband(image); return; }
        synchronized (made) { if (made.containsKey(bitmap)) return; }
        Band rows; boolean known;
        synchronized (bands) { known = bands.containsKey(bitmap); rows = bands.get(bitmap); }
        if (rows == null) {
            Object[] last; synchronized (byTask) { last = byTask.get(id); }
            if (last != null && "band".equals(last[0]) && sameShape(last, bitmap)) {
                rows = (Band) last[3];
                synchronized (bands) { bands.put(bitmap, rows); }
            }
        }
        if (rows == null) {
            if (known) return;
            synchronized (bands) { bands.put(bitmap, null); }
            worker.execute(new Runnable() { public void run() {
                Band r = measure(bitmap);
                synchronized (bands) { bands.put(bitmap, r); }
                main.post(new Runnable() { public void run() { fill(thumbnail); } });
            } });
            return;
        }
        if (rows == NO_BAND || rows.card == null) { unband(image); return; }
        if (Boolean.TRUE.equals(Xp.getAdditionalInstanceField(image, "pixelsuite_launching"))) return;
        Xp.setAdditionalInstanceField(image, "pixelsuite_band", Boolean.TRUE);
        Xp.setAdditionalInstanceField(image, "pixelsuite_band_original", picture);
        unwait(image);
        synchronized (byTask) { byTask.put(id, new Object[]{"band", bitmap.getWidth(), bitmap.getHeight(), rows}); }
        image.setImageDrawable(new Made(rows.card, picture.getIntrinsicWidth(), picture.getIntrinsicHeight(), false));
    }

    private static void prepare(Object container, int h, int w) {
        try {
            Object thumb = Xp.callMethod(container, "getSnapshotView");
            android.widget.ImageView image = (android.widget.ImageView) Xp.callMethod(thumb, "getThumbnailView");
            Drawable d = image == null ? null : image.getDrawable();
            if (!(d instanceof android.graphics.drawable.BitmapDrawable) || h <= 0 || w <= 0) return;
            final android.graphics.Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (bitmap == null || bitmap.getWidth() <= 0) return;
            final int task = taskId(container);
            final int outH = Math.round(bitmap.getWidth() * (float) h / w);
            if (outH <= bitmap.getHeight() * 1.3f) return;
            worker.execute(new Runnable() { public void run() {
                android.graphics.Bitmap b = bitmap, card = null;
                try {
                    if (!b.isRecycled()) {
                        if ("HARDWARE".equals(String.valueOf(b.getConfig()))) b = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                        if (b != null) card = compose(b, 0, b.getHeight(), outH, Boolean.FALSE);
                    }
                } catch (Throwable ignored) { }
                finally { if (b != bitmap && b != null) try { b.recycle(); } catch (Throwable ignored) { } }
                if (card != null) synchronized (ahead) { ahead.put(task, new Object[]{card, bitmap.getWidth(), bitmap.getHeight()}); }
            } });
        } catch (Throwable ignored) { }
    }
    private static final java.util.Map<Integer, Object[]> byTask = lastUsed(12);
    private static final java.util.Map<Integer, Object[]> ahead = lastUsed(6);
    private static <V> java.util.Map<Integer, V> lastUsed(final int max) {
        return new java.util.LinkedHashMap<Integer, V>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<Integer, V> eldest) { return size() > max; }
        };
    }

    private static final WeakHashMap<android.graphics.Bitmap, android.graphics.Bitmap> squats = new WeakHashMap<android.graphics.Bitmap, android.graphics.Bitmap>();
    private static boolean squat(final View thumbnail, android.widget.ImageView image, Drawable picture, float[] m, float bw, float bh, float vw, float vh) {
        if (!(picture instanceof android.graphics.drawable.BitmapDrawable)) return false;
        if (Boolean.TRUE.equals(Xp.getAdditionalInstanceField(image, "pixelsuite_launching"))) return true;
        final android.graphics.Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) picture).getBitmap();
        if (bitmap == null || bitmap.getWidth() <= 0) return false;
        synchronized (squatFailed) { if (squatFailed.containsKey(bitmap)) { unwait(image); return false; } }
        android.graphics.Bitmap card; boolean known;
        synchronized (squats) { known = squats.containsKey(bitmap); card = squats.get(bitmap); }
        final int taskNow = singleTaskOf(thumbnail);
        if (card == null) {
            Object[] last; synchronized (byTask) { last = byTask.get(taskNow); }
            if (last != null && "squat".equals(last[0]) && sameShape(last, bitmap)) {
                card = (android.graphics.Bitmap) last[3];
                synchronized (squats) { squats.put(bitmap, card); }
            }
        }
        if (card == null) {
            int task = taskNow;
            Object[] a; synchronized (ahead) { a = ahead.get(task); }
            if (a != null && sameShape(new Object[]{null, a[1], a[2]}, bitmap)) {
                card = (android.graphics.Bitmap) a[0];
                synchronized (squats) { squats.put(bitmap, card); }
                synchronized (ahead) { ahead.remove(task); }
            }
        }
        if (card == null) {
            image.setAlpha(0f);
            Xp.setAdditionalInstanceField(image, "pixelsuite_waiting", Boolean.TRUE);
            if (known) { main.postDelayed(new Runnable() { public void run() { fill(thumbnail); } }, 60); return true; }
            synchronized (squats) { squats.put(bitmap, null); }
            final int outH = Math.round(bitmap.getWidth() * vh / vw);
            worker.execute(new Runnable() { public void run() {
                android.graphics.Bitmap b = bitmap, made1 = null;
                try {
                    if (!b.isRecycled()) {
                        if ("HARDWARE".equals(String.valueOf(b.getConfig()))) b = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                        if (b != null) made1 = compose(b, 0, b.getHeight(), outH, Boolean.FALSE);
                    }
                } catch (Throwable ignored) { }
                finally { if (b != bitmap && b != null) try { b.recycle(); } catch (Throwable ignored) { } }
                synchronized (squats) { if (made1 != null) squats.put(bitmap, made1); else squats.remove(bitmap); }
                if (made1 == null) synchronized (squatFailed) { squatFailed.put(bitmap, Boolean.TRUE); }
                main.post(new Runnable() { public void run() { fill(thumbnail); } });
            } });
            return true;
        }
        Xp.setAdditionalInstanceField(image, "pixelsuite_band", Boolean.TRUE);
        Xp.setAdditionalInstanceField(image, "pixelsuite_band_original", picture);
        if (taskNow >= 0) synchronized (byTask) { byTask.put(taskNow, new Object[]{"squat", bitmap.getWidth(), bitmap.getHeight(), card}); }
        Made shown = new Made(card, card.getWidth(), card.getHeight(), true);
        image.setImageDrawable(shown);
        fitWidth(image, shown.getIntrinsicWidth(), shown.getIntrinsicHeight(), vw, vh, m);
        if (Boolean.TRUE.equals(Xp.getAdditionalInstanceField(image, "pixelsuite_waiting"))) {
            Xp.setAdditionalInstanceField(image, "pixelsuite_waiting", null);
            image.animate().alpha(1f).setDuration(140).start();
        } else image.setAlpha(1f);
        return true;
    }
    private static boolean sameShape(Object[] last, android.graphics.Bitmap b) {
        float w = ((Integer) last[1]), h = ((Integer) last[2]);
        if (w <= 0 || h <= 0 || b.getWidth() <= 0 || b.getHeight() <= 0) return false;
        return Math.abs(w / h - b.getWidth() / (float) b.getHeight()) < 0.03f * (w / h);
    }
    static final class Made extends Drawable {
        final android.graphics.Bitmap card; final int iw, ih; final boolean squat;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Made(android.graphics.Bitmap card, int iw, int ih, boolean squat) { this.card = card; this.iw = iw; this.ih = ih; this.squat = squat; }
        @Override public void draw(Canvas c) { if (!card.isRecycled()) c.drawBitmap(card, null, getBounds(), paint); }
        @Override public int getIntrinsicWidth() { return iw; }
        @Override public int getIntrinsicHeight() { return ih; }
        @Override public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static void fitWidth(android.widget.ImageView image, float bw, float bh, float vw, float vh, float[] m) {
        if (bw <= 0 || vw <= 0) return;
        float scale = vw / bw, ty = (vh - bh * scale) / 2f;
        if (Math.abs(m[0] - scale) < 0.001f && Math.abs(m[2]) < 0.5f && Math.abs(m[5] - ty) < 0.5f) return;
        android.graphics.Matrix fit = new android.graphics.Matrix();
        fit.setScale(scale, scale); fit.postTranslate(0, ty);
        image.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
        image.setImageMatrix(fit);
    }

    private static void settleForLaunch(View taskView) {
        try {
            if (!cTaskView.isInstance(taskView)) return;
            for (Object c : containers(taskView)) {
                Object thumb = Xp.callMethod(c, "getSnapshotView");
                if (!(thumb instanceof View)) continue;
                android.widget.ImageView image = null;
                try { image = (android.widget.ImageView) Xp.callMethod(thumb, "getThumbnailView"); } catch (Throwable ignored) { }
                if (image == null || !Boolean.TRUE.equals(Xp.getAdditionalInstanceField(image, "pixelsuite_band"))) continue;
                Object original = Xp.getAdditionalInstanceField(image, "pixelsuite_band_original");
                if (!(original instanceof Drawable) || image.getDrawable() == null) continue;
                final android.widget.ImageView iv = image;
                Xp.setAdditionalInstanceField(iv, "pixelsuite_launching", Boolean.TRUE);
                Drawable now = image.getDrawable();
                if (now instanceof Made && ((Made) now).squat) {
                    Drawable o = (Drawable) original;
                    image.setImageDrawable(o);
                    if (o.getIntrinsicWidth() > 0 && image.getWidth() > 0) {
                        android.graphics.Matrix top = new android.graphics.Matrix(); float sc = image.getWidth() / (float) o.getIntrinsicWidth();
                        top.setScale(sc, sc); image.setImageMatrix(top);
                    }
                    image.setAlpha(0.6f); image.animate().alpha(1f).setDuration(200).start();
                    main.postDelayed(new Runnable() { public void run() { Xp.setAdditionalInstanceField(iv, "pixelsuite_launching", null); } }, 1500);
                    continue;
                }
                android.graphics.drawable.TransitionDrawable blend = new android.graphics.drawable.TransitionDrawable(new Drawable[]{image.getDrawable(), (Drawable) original});
                blend.setCrossFadeEnabled(true);
                image.setImageDrawable(blend);
                blend.startTransition(220);
                main.postDelayed(new Runnable() { public void run() { Xp.setAdditionalInstanceField(iv, "pixelsuite_launching", null); } }, 1500);
            }
        } catch (Throwable error) { fail("launch settle", error); }
    }

    private static void unband(android.widget.ImageView image) {
        unwait(image);
        try {
            if (!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(image, "pixelsuite_band"))) return;
            Xp.setAdditionalInstanceField(image, "pixelsuite_band", null);
            Xp.setAdditionalInstanceField(image, "pixelsuite_band_original", null);
            Xp.setAdditionalInstanceField(image, "pixelsuite_launching", null);
        } catch (Throwable ignored) { }
    }

    private static void unwait(android.widget.ImageView image) {
        try {
            if (!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(image, "pixelsuite_waiting"))) return;
            Xp.setAdditionalInstanceField(image, "pixelsuite_waiting", null);
            image.animate().cancel(); image.setAlpha(1f);
        } catch (Throwable ignored) { }
    }
    private static final WeakHashMap<android.graphics.Bitmap, Boolean> squatFailed = new WeakHashMap<android.graphics.Bitmap, Boolean>();

    private static int singleTaskOf(View thumbnail) {
        Object given = Xp.getAdditionalInstanceField(thumbnail, "pixelsuite_task");
        ViewParent v = thumbnail.getParent();
        while (v != null && !cTaskView.isInstance(v)) v = v.getParent();
        if (v == null) return given instanceof Integer ? (Integer) given : -1;
        List<?> list = containers(v);
        if (list.size() != 1) return -1;
        return given instanceof Integer ? (Integer) given : taskId(list.get(0));
    }

    private static Band measure(android.graphics.Bitmap source) {
        android.graphics.Bitmap b = source;
        try {
            if (b.isRecycled()) return NO_BAND;
            if ("HARDWARE".equals(String.valueOf(b.getConfig()))) b = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            if (b == null) return NO_BAND;
            int w = b.getWidth(), h = b.getHeight();
            if (w < 20 || h < 20) return NO_BAND;
            int top = -1, bottom = -1;
            int edge = flatFrom(b, w, h, h - 3, -1);
            if (h - edge >= h * 0.3f) { top = 0; bottom = edge + 1; }
            else {
                edge = flatFrom(b, w, h, 2, 1);
                if (edge >= h * 0.3f) { top = edge; bottom = h; }
            }
            if (top < 0 || bottom - top < 20) return NO_BAND;
            return new Band(top, bottom, compose(b, top, bottom));
        } catch (Throwable t) { return NO_BAND; }
        finally { if (b != source && b != null) try { b.recycle(); } catch (Throwable ignored) { } }
    }
    private static android.graphics.Bitmap compose(android.graphics.Bitmap b, int top, int bottom) { return compose(b, top, bottom, b.getHeight(), Boolean.TRUE); }
    private static android.graphics.Bitmap compose(android.graphics.Bitmap b, int top, int bottom, int outH, Boolean kind) {
        try {
            android.graphics.Bitmap soft = soften(b, top, bottom);
            int w = b.getWidth(), h = outH, ch = Math.min(bottom - top, outH);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            if (soft != null) {
                float s = Math.max(w / (float) soft.getWidth(), h / (float) soft.getHeight());
                float sw = soft.getWidth() * s, sh = soft.getHeight() * s;
                c.drawBitmap(soft, null, new RectF((w - sw) / 2f, (h - sh) / 2f, (w + sw) / 2f, (h + sh) / 2f), filter);
                c.drawColor(0x40000000);
            } else c.drawColor(0xff000000);
            float y0 = (h - ch) / 2f;
            int layer = c.saveLayer(0, y0, w, y0 + ch, null);
            c.drawBitmap(b, new android.graphics.Rect(0, top, w, bottom), new RectF(0, y0, w, y0 + ch), filter);
            Paint mask = new Paint();
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            mask.setShader(new android.graphics.LinearGradient(0, y0, 0, y0 + ch,
                    new int[]{0x00000000, 0xff000000, 0xff000000, 0x00000000}, new float[]{0f, 0.2f, 0.8f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, y0, w, y0 + ch, mask);
            c.restoreToCount(layer);
            if (soft != null) soft.recycle();
            synchronized (made) { made.put(out, kind); }
            return out;
        } catch (Throwable t) { return null; }
    }
    private static android.graphics.Bitmap soften(android.graphics.Bitmap b, int top, int bottom) {
        try {
            int sw = Math.max(8, b.getWidth() / 18), sh = Math.max(8, (bottom - top) / 18);
            android.graphics.Bitmap small = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888);
            new Canvas(small).drawBitmap(b, new android.graphics.Rect(0, top, b.getWidth(), bottom), new android.graphics.Rect(0, 0, sw, sh), new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] px = new int[sw * sh], tmp = new int[sw * sh];
            small.getPixels(px, 0, sw, 0, 0, sw, sh);
            for (int pass = 0; pass < 2; pass++) { box(px, tmp, sw, sh, 2, true); box(tmp, px, sw, sh, 2, false); }
            small.setPixels(px, 0, sw, 0, 0, sw, sh);
            return small;
        } catch (Throwable t) { return null; }
    }
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, g = 0, bl = 0, n = 0;
            for (int d = -r; d <= r; d++) {
                int xx = horizontal ? Math.min(w - 1, Math.max(0, x + d)) : x, yy = horizontal ? y : Math.min(h - 1, Math.max(0, y + d));
                int c = in[yy * w + xx];
                a += c >>> 24; rr += (c >> 16) & 0xff; g += (c >> 8) & 0xff; bl += c & 0xff; n++;
            }
            out[y * w + x] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (bl / n);
        }
    }
    private static int flatFrom(android.graphics.Bitmap b, int w, int h, int start, int step) {
        int ref = b.getPixel(w / 2, start);
        int y = start;
        for (; y >= 0 && y < h; y += step) {
            boolean flat = true;
            for (int i = 0; i < 24 && flat; i++) {
                int x = (int) (w * (0.04f + 0.92f * i / 23f));
                int c = b.getPixel(x, y);
                if (Math.abs(((c >> 16) & 0xff) - ((ref >> 16) & 0xff)) > 8 || Math.abs(((c >> 8) & 0xff) - ((ref >> 8) & 0xff)) > 8 || Math.abs((c & 0xff) - (ref & 0xff)) > 8) flat = false;
            }
            if (!flat) return y;
        }
        return y;
    }

    private static int menuHooks() {
        int n = 0;
        try {
            n += Xp.hookAllMethods(Xp.findClass(QS + "TaskOverlayFactory", cl), "getEnabledShortcuts", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (!(p.getResult() instanceof List) || p.args.length < 2) return;
                        for (Object item : (List<?>) p.getResult()) if (cRemove.isInstance(item)) Xp.setAdditionalInstanceField(item, "pixelsuite_container", p.args[1]);
                    } catch (Throwable ignored) { }
                }
            }).size();
            n += Xp.hookAllMethods(Xp.findClass("com.android.launcher3.popup.SystemShortcut", cl), "setIconAndLabelFor", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (!cRemove.isInstance(p.thisObject) || p.args.length != 2 || !(p.args[0] instanceof View) || !(p.args[1] instanceof TextView)) return;
                        View icon = (View) p.args[0]; TextView text = (TextView) p.args[1];
                        if (!on(icon.getContext())) return;
                        Object container = Xp.getAdditionalInstanceField(p.thisObject, "pixelsuite_container");
                        if (container == null) return;
                        load(icon.getContext());
                        boolean isOn = isLocked(container);
                        Lock glyph = new Lock(text.getCurrentTextColor(), isOn ? 1f : 0f);
                        icon.setBackground(glyph);
                        text.setText(isOn ? "Unlock" : "Lock");
                        Xp.setAdditionalInstanceField(p.thisObject, "pixelsuite_glyph", glyph);
                        Xp.setAdditionalInstanceField(p.thisObject, "pixelsuite_text", text);
                        p.setResult(null);
                    } catch (Throwable error) { fail("menu row", error); }
                }
            }).size();
            n += Xp.hookAllMethods(cRemove, "onClick", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        final Object item = p.thisObject;
                        final View taskView = (View) Xp.getObjectField(item, "taskView");
                        final Object container = Xp.getAdditionalInstanceField(item, "pixelsuite_container");
                        if (taskView == null || container == null || !on(taskView.getContext())) return;
                        p.setResult(null);
                        load(taskView.getContext());
                        final boolean locking = !isLocked(container);
                        Object g = Xp.getAdditionalInstanceField(item, "pixelsuite_glyph");
                        Object t = Xp.getAdditionalInstanceField(item, "pixelsuite_text");
                        if (t instanceof TextView) ((TextView) t).setText(locking ? "Unlock" : "Lock");
                        long close = 0;
                        if (g instanceof Lock) { ((Lock) g).animateTo(locking ? 1f : 0f); close = 300; }
                        Haptics.tick(taskView.getContext());
                        main.postDelayed(new Runnable() { public void run() {
                            try { Xp.callMethod(item, "dismissTaskMenuView"); } catch (Throwable ignored) { }
                            toggle(taskView, container);
                        } }, close);
                    } catch (Throwable error) { fail("menu click", error); }
                }
            }).size();
        } catch (Throwable error) { Module.log("Recents lock: menu unavailable", error); }
        return n;
    }

    private static int dismissHooks() {
        int n = 0;
        try {
            Class<?> touch = Xp.findClass("com.android.launcher3.uioverrides.touchcontrollers.TaskViewDismissTouchController", cl);
            n += Xp.hookAllMethods(touch, "onControllerInterceptTouchEvent", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (!(p.args[0] instanceof MotionEvent) || ((MotionEvent) p.args[0]).getAction() != MotionEvent.ACTION_DOWN) return;
                        Object tv = Xp.getObjectField(p.thisObject, "taskBeingDragged");
                        if (tv != null && active(tv)) Xp.setObjectField(p.thisObject, "allowDetach", Boolean.FALSE);
                    } catch (Throwable error) { fail("swipe guard", error); }
                }
            }).size();
            n += Xp.hookAllMethods(touch, "onDrag", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) { if (p.args.length == 1) Xp.setAdditionalInstanceField(p.thisObject, "pixelsuite_drag", p.args[0]); }
            }).size();
            n += Xp.hookAllMethods(touch, "onDragEnd", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        View tv = (View) Xp.getObjectField(p.thisObject, "taskBeingDragged");
                        if (tv == null || !active(tv)) return;
                        float velocity = (Float) p.args[0];
                        Object last = Xp.getAdditionalInstanceField(p.thisObject, "pixelsuite_drag");
                        float travel = ((Number) Xp.getObjectField(p.thisObject, "initialDisplacement")).floatValue() + (last instanceof Float ? (Float) last : 0f);
                        int length = ((Number) Xp.getObjectField(p.thisObject, "dismissLength")).intValue();
                        Object handler = Xp.callMethod(Xp.getObjectField(p.thisObject, "recentsView"), "getPagedOrientationHandler");
                        Object rtl = Xp.getObjectField(p.thisObject, "isRtl");
                        boolean upFling = false, upTravel = false;
                        try { upFling = (Boolean) Xp.callMethod(Xp.getObjectField(p.thisObject, "detector"), "isFling", velocity) && (Boolean) Xp.callMethod(handler, "isGoingUp", velocity, rtl); } catch (Throwable ignored) { }
                        try { upTravel = (Boolean) Xp.callMethod(handler, "isGoingUp", travel, rtl) && Math.abs(travel) > length * 0.2f; } catch (Throwable ignored) { }
                        boolean tried = upFling || upTravel;
                        Xp.setAdditionalInstanceField(p.thisObject, "pixelsuite_tried", tried ? tv : null);
                    } catch (Throwable error) { fail("swipe end", error); }
                }
                @Override protected void afterHookedMethod(Xp.Param p) {
                    Object tv = Xp.getAdditionalInstanceField(p.thisObject, "pixelsuite_tried");
                    if (tv instanceof View) { Xp.setAdditionalInstanceField(p.thisObject, "pixelsuite_tried", null); refused((View) tv); }
                }
            }).size();
            n += Xp.hookAllMethods(cRecents, "runTaskDismissSettlingSpringAnimation", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length < 4 || !Boolean.TRUE.equals(p.args[1]) || !active(p.args[0])) return;
                        p.args[1] = Boolean.FALSE; p.args[3] = Boolean.FALSE;
                        Object d = p.args[2];
                        if (d != null && cTaskData.isInstance(d)) p.args[2] = Xp.newInstance(cTaskData, ((Number) Xp.getObjectField(d, "startVelocity")).floatValue(), ((Number) Xp.getObjectField(d, "dismissLength")).intValue(), 0f, ((Number) Xp.getObjectField(d, "dismissThreshold")).intValue());
                    } catch (Throwable error) { fail("settle guard", error); }
                }
            }).size();
            n += Xp.hookAllMethods(cRecents, "dismissTaskView", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length < 2 || !Boolean.TRUE.equals(p.args[1]) || !active(p.args[0])) return;
                        p.setResult(null);
                        refused((View) p.args[0]);
                    } catch (Throwable error) { fail("dismiss guard", error); }
                }
            }).size();
            n += Xp.hookAllMethods(cRecents, "dismissTask", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length == 2 && p.args[0] instanceof Integer && Boolean.FALSE.equals(p.args[1]) && partial.contains((Integer) p.args[0])) p.setResult(null);
                    } catch (Throwable ignored) { }
                }
            }).size();
            n += Xp.hookAllMethods(Xp.findClass(QS + "views.RecentsDismissUtils", cl), "dismissAllTasks", new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 0) return;
                        Object recents = Xp.getObjectField(p.thisObject, "recentsView");
                        if (!(recents instanceof View) || !on(((View) recents).getContext())) return;
                        List<View> all = taskViews(recents);
                        boolean any = false;
                        for (View tv : all) if (lockedCount(tv) > 0) { any = true; break; }
                        if (!any) return;
                        p.setResult(null);
                        clearUnlocked(recents, all);
                    } catch (Throwable error) { fail("clear all", error); }
                }
            }).size();
        } catch (Throwable error) { Module.log("Recents lock: dismiss guard unavailable", error); }
        return n;
    }

    private static void refused(View taskView) {
        List<?> list = containers(taskView);
        int count = list.size(), lockedApps = lockedCount(taskView);
        Haptics.tick(taskView.getContext());
        if (count <= 1) toast(taskView, "App is locked");
        else if (lockedApps >= count) toast(taskView, "Two apps locked");
        else { toast(taskView, "App remains locked"); splitOff(taskView, list); }
    }

    private static void splitOff(final View taskView, List<?> list) {
        Object keep = null, go = null;
        for (Object c : list) { if (isLocked(c)) keep = c; else go = c; }
        if (keep == null || go == null) return;
        final int goId = taskId(go);
        if (goId < 0) return;
        try {
            View keepView = (View) Xp.callMethod(keep, "getTaskContentView"), goView = (View) Xp.callMethod(go, "getTaskContentView");
            Xp.setAdditionalInstanceField(taskView, "pixelsuite_split_off", Boolean.TRUE);
            float top = Math.min(keepView.getY(), goView.getY()), bottom = Math.max(keepView.getY() + keepView.getHeight(), goView.getY() + goView.getHeight());
            float left = Math.min(keepView.getX(), goView.getX()), right = Math.max(keepView.getX() + keepView.getWidth(), goView.getX() + goView.getWidth());
            float scale = Math.min((right - left) / Math.max(1, keepView.getWidth()), (bottom - top) / Math.max(1, keepView.getHeight()));
            float cx = (left + right) / 2f, cy = (top + bottom) / 2f;
            PathInterpolator ease = new PathInterpolator(0.2f, 0f, 0f, 1f);
            keepView.setTranslationZ(2f); goView.setTranslationZ(-2f);
            try { View chip = (View) Xp.callMethod(go, "getIconView"); if (chip != null) { chip.animate().alpha(0f).setDuration(160).start(); Xp.setAdditionalInstanceField(taskView, "pixelsuite_chip", chip); } } catch (Throwable ignored) { }
            boolean goBelow = goView.getY() > keepView.getY();
            if (goBelow) {
                goView.setPivotX(goView.getWidth() / 2f); goView.setPivotY(0f);
                goView.animate().translationYBy(keepView.getY() - goView.getY()).scaleX(0.9f).scaleY(0.9f).alpha(0f)
                        .setDuration(300).setInterpolator(ease).start();
            } else goView.animate().translationYBy(-(bottom - top) * 0.6f).alpha(0f).setDuration(240).setInterpolator(ease).start();
            prepare(keep, Math.round(bottom - top), Math.round(right - left));
            keepView.animate()
                    .translationXBy(cx - (keepView.getX() + keepView.getWidth() / 2f))
                    .translationYBy(cy - (keepView.getY() + keepView.getHeight() / 2f))
                    .setStartDelay(60).setDuration(320).setInterpolator(ease).start();
        } catch (Throwable error) { fail("split animation", error); }
        final Object rv = recentsOf(taskView);
        final Runnable remove = new Runnable() { public void run() { removeTasks(rv, java.util.Collections.singletonList(goId), true); } };
        boolean running = false;
        try { running = Boolean.TRUE.equals(Xp.callMethod(taskView, "isRunningTask")); } catch (Throwable ignored) { }
        if (running && rv != null) {
            main.postDelayed(new Runnable() { public void run() {
                try {
                    Xp.callMethod(rv, "switchToScreenshot", (Runnable) new Runnable() { public void run() {
                        try { Xp.callMethod(rv, "finishRecentsAnimation", true, false, remove); }
                        catch (Throwable error) { remove.run(); }
                    } });
                } catch (Throwable error) { remove.run(); }
            } }, 380);
        } else main.postDelayed(remove, 380);
    }

    private static void removeTasks(final Object recents, final List<Integer> ids, boolean fromPair) {
        if (ids.isEmpty()) return;
        if (fromPair) partial.addAll(ids);
        worker.execute(new Runnable() { public void run() {
            try {
                Object wrapper = Xp.callStaticMethod(Xp.findClass("com.android.systemui.shared.system.ActivityManagerWrapper", cl), "getInstance");
                for (int id : ids) Xp.callMethod(wrapper, "removeTask", id);
            } catch (Throwable error) { fail("remove task", error); }
        } });
        if (recents != null) {
            Runnable reload = new Runnable() { public void run() { try { Xp.callMethod(recents, "reloadIfNeeded"); } catch (Throwable ignored) { } } };
            main.postDelayed(reload, fromPair ? 350 : 900);
            main.postDelayed(reload, 900);
            main.postDelayed(reload, 1600);
        }
        if (fromPair) main.postDelayed(new Runnable() { public void run() { partial.removeAll(ids); } }, 3000);
    }

    private static void clearUnlocked(final Object recents, List<View> all) {
        try { Xp.callStaticMethod(Xp.findClass("com.android.systemui.shared.system.InteractionJankMonitorWrapper", cl), "cancel", 139); } catch (Throwable ignored) { }
        final List<Integer> going = new ArrayList<Integer>(), fromPairs = new ArrayList<Integer>();
        final List<View> shown = new ArrayList<View>(), comeBack = new ArrayList<View>();
        final List<View> hiddenHalves = new ArrayList<View>();
        View anchor = null;
        for (View tv : all) {
            List<?> list = containers(tv);
            int lockedApps = 0; for (Object c : list) if (isLocked(c)) lockedApps++;
            for (Object c : list) if (!isLocked(c)) { int id = taskId(c); if (id >= 0) (lockedApps > 0 ? fromPairs : going).add(id); }
            boolean visible = true;
            try { visible = Boolean.TRUE.equals(Xp.callMethod(recents, "isTaskViewVisible", tv)); } catch (Throwable ignored) { }
            if (!visible) try { visible = tv.isShown() && tv.getGlobalVisibleRect(new android.graphics.Rect()); } catch (Throwable ignored) { }
            if (visible) shown.add(tv);
            if (lockedApps > 0) {
                boolean mostly = visible && onScreen(tv) >= 0.5f;
                if (mostly) comeBack.add(tv);
                if (anchor == null || isCentred(tv) || (mostly && !isCentred(anchor) && onScreen(anchor) < 0.5f)) anchor = tv;
                if (lockedApps < list.size()) for (Object c : list) if (!isLocked(c)) try { hiddenHalves.add((View) Xp.callMethod(c, "getTaskContentView")); } catch (Throwable ignored) { }
            }
        }
        if (going.isEmpty() && fromPairs.isEmpty()) {
            try { Haptics.tick(((View) recents).getContext()); } catch (Throwable ignored) { }
            if (anchor != null) toast(anchor, "Cannot dismiss locked apps");
            return;
        }
        try { Haptics.click(((View) recents).getContext()); } catch (Throwable ignored) { }
        final PathInterpolator ease = new PathInterpolator(0.3f, 0f, 0.1f, 1f);
        for (int i = 0; i < shown.size(); i++) {
            View tv = shown.get(i);
            Xp.setAdditionalInstanceField(tv, "pixelsuite_cleared", Boolean.TRUE);
            tv.animate().cancel();
            tv.animate().translationY(-tv.getHeight() * 0.55f).alpha(0f).setStartDelay(25L * i).setDuration(200).setInterpolator(ease).start();
        }
        final View toastAt = anchor;
        main.postDelayed(new Runnable() { public void run() {
            for (View half : hiddenHalves) half.setAlpha(0f);
            for (View tv : comeBack) {
                tv.animate().cancel();
                tv.setTranslationY(0f); tv.setScaleX(0.94f); tv.setScaleY(0.94f);
                tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(190).setInterpolator(new DecelerateInterpolator()).start();
            }
            if (toastAt != null) toast(toastAt, "Cannot dismiss locked apps");
        } }, 25L * Math.max(0, shown.size() - 1) + 230);
        main.postDelayed(new Runnable() { public void run() {
            Runnable after = new Runnable() { public void run() {
                removeTasks(null, going, false);
                removeTasks(null, fromPairs, true);
                main.postDelayed(new Runnable() { public void run() {
                    for (View tv : taskViews(recents)) restore(tv);
                    for (View half : hiddenHalves) half.setAlpha(1f);
                    try { Xp.callMethod(recents, "reloadIfNeeded"); } catch (Throwable ignored) { }
                } }, 500);
            } };
            boolean closed = false;
            try { Xp.callMethod(recents, "finishRecentsAnimation", true, false, (Runnable) new Runnable() { public void run() { try { Xp.callMethod(recents, "startHome"); } catch (Throwable ignored) { } } }); closed = true; }
            catch (Throwable ignored) { }
            if (!closed) try { Xp.callMethod(recents, "startHome"); } catch (Throwable error) { fail("clear close", error); }
            main.postDelayed(after, 150);
            main.postDelayed(new Runnable() { public void run() {
                try { View rv = (View) recents; if (rv.isShown() && rv.getAlpha() > 0.5f) Xp.callMethod(recents, "startHome"); } catch (Throwable ignored) { }
            } }, 450);
        } }, 25L * Math.max(0, shown.size() - 1) + 520);
    }
    private static void restore(View tv) {
        if (!Boolean.TRUE.equals(Xp.getAdditionalInstanceField(tv, "pixelsuite_cleared"))) return;
        Xp.setAdditionalInstanceField(tv, "pixelsuite_cleared", null);
        tv.animate().cancel(); tv.setAlpha(1f); tv.setTranslationY(0f); tv.setScaleX(1f); tv.setScaleY(1f);
    }
    static float onScreen(View v) {
        try {
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return 0f;
            android.graphics.RectF r = new android.graphics.RectF(0, 0, v.getWidth(), v.getHeight());
            View at = v;
            while (true) {
                at.getMatrix().mapRect(r);
                r.offset(at.getLeft(), at.getTop());
                Object p = at.getParent();
                if (!(p instanceof View)) break;
                View pv = (View) p;
                r.offset(-pv.getScrollX(), -pv.getScrollY());
                at = pv;
            }
            int[] win = new int[2]; at.getLocationOnScreen(win);
            r.offset(win[0] - at.getLeft(), win[1] - at.getTop());
            float full = r.width() * r.height();
            if (full <= 0) return 0f;
            android.util.DisplayMetrics m = v.getResources().getDisplayMetrics();
            float w = Math.max(0f, Math.min(r.right, m.widthPixels) - Math.max(r.left, 0f));
            float h = Math.max(0f, Math.min(r.bottom, m.heightPixels) - Math.max(r.top, 0f));
            return Math.min(1f, w * h / full);
        } catch (Throwable t) { return 1f; }
    }
    private static boolean isCentred(View tv) {
        try {
            int[] at = new int[2]; tv.getLocationOnScreen(at);
            int w = tv.getResources().getDisplayMetrics().widthPixels;
            float mid = at[0] + tv.getWidth() * scaleOf(tv) / 2f;
            return Math.abs(mid - w / 2f) < w * 0.25f;
        } catch (Throwable t) { return false; }
    }

    private static final Set<String> fresh = new HashSet<String>();
    private static final WeakHashMap<View, Boolean> badges = new WeakHashMap<View, Boolean>();

    private static int badgeHooks() {
        int n = 0;
        try {
            n += Xp.hookAllMethods(cTaskView, "onBind", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    final View tv = (View) p.thisObject;
                    tv.post(new Runnable() { public void run() { refresh(tv, null); } });
                }
            }).size();
            n += Xp.hookAllMethods(cTaskView, "setModalness", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) {
                    try {
                        if (p.args.length != 1 || !(p.args[0] instanceof Float)) return;
                        float shown = 1f - Math.max(0f, Math.min(1f, (Float) p.args[0]));
                        ViewGroup g = (ViewGroup) p.thisObject;
                        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) instanceof Badge) transitionAlpha(g.getChildAt(i), shown);
                    } catch (Throwable ignored) { }
                }
            }).size();
            n += Xp.hookAllMethods(cTaskView, "onRecycle", new Xp.Callback() {
                @Override protected void afterHookedMethod(Xp.Param p) { reset((View) p.thisObject); }
            }).size();
            Xp.Callback taps = new Xp.Callback() {
                @Override protected void beforeHookedMethod(Xp.Param p) {
                    try {
                        if (badges.isEmpty() || p.args.length != 1 || !(p.args[0] instanceof MotionEvent)) return;
                        View base = null;
                        if (p.thisObject instanceof Activity) { try { base = ((Activity) p.thisObject).getWindow().getDecorView(); } catch (Throwable ignored) { } }
                        else if (p.thisObject instanceof View) base = (View) p.thisObject;
                        if (tap((MotionEvent) p.args[0], base)) p.setResult(Boolean.TRUE);
                    } catch (Throwable error) { fail("badge tap", error); }
                }
            };
            int t = Xp.hookAllMethods(Xp.findClass("com.android.launcher3.Launcher", cl), "dispatchTouchEvent", taps).size();
            if (t == 0) t = Xp.hookAllMethods(Xp.findClass("com.android.launcher3.views.BaseDragLayer", cl), "dispatchTouchEvent", taps).size();
            n += t;
        } catch (Throwable error) { Module.log("Recents lock: badges unavailable", error); }
        return n;
    }

    static void refresh(View tv, String unlocked) {
        try {
            if (!(tv instanceof ViewGroup) || !tv.isAttachedToWindow()) return;
            boolean enabled = on(tv.getContext());
            load(tv.getContext());
            List<?> list = containers(tv);
            for (int i = 0; i < list.size(); i++) {
                Object c = list.get(i);
                String id = identity(c);
                Badge b = badgeFor(tv, i);
                boolean want = enabled && id != null && locked.contains(id);
                if (want && b == null) {
                    b = new Badge(tv.getContext(), c, i);
                    ((ViewGroup) tv).addView(b, new FrameLayout.LayoutParams(b.size, b.size, Gravity.TOP | Gravity.LEFT));
                    ((ViewGroup) tv).setClipChildren(false);
                    badges.put(b, Boolean.TRUE);
                    b.follow(tv);
                    if (fresh.remove(id)) b.popIn(); else b.place();
                } else if (want) {
                    b.container = c; b.place();
                } else if (b != null) {
                    if (unlocked != null && unlocked.equals(b.owner)) b.popOut(); else b.remove();
                }
                if (b != null) b.owner = id;
            }
            for (int i = list.size(); i < 3; i++) { Badge b = badgeFor(tv, i); if (b != null) b.remove(); }
        } catch (Throwable error) { fail("badge", error); }
    }
    private static Badge badgeFor(View tv, int slot) {
        ViewGroup g = (ViewGroup) tv;
        for (int i = 0; i < g.getChildCount(); i++) { View v = g.getChildAt(i); if (v instanceof Badge && ((Badge) v).slot == slot && !((Badge) v).leaving) return (Badge) v; }
        return null;
    }
    private static void reset(View tv) {
        try {
            ViewGroup g = (ViewGroup) tv;
            for (int i = g.getChildCount() - 1; i >= 0; i--) if (g.getChildAt(i) instanceof Badge) ((Badge) g.getChildAt(i)).remove();
            restore(tv);
            if (Boolean.TRUE.equals(Xp.getAdditionalInstanceField(tv, "pixelsuite_split_off"))) {
                Xp.setAdditionalInstanceField(tv, "pixelsuite_split_off", null);
                Object chip = Xp.getAdditionalInstanceField(tv, "pixelsuite_chip");
                Xp.setAdditionalInstanceField(tv, "pixelsuite_chip", null);
                if (chip instanceof View) { ((View) chip).animate().cancel(); ((View) chip).setAlpha(1f); }
                for (Object c : containers(tv)) try {
                    View v = (View) Xp.callMethod(c, "getTaskContentView");
                    v.animate().cancel(); v.setAlpha(1f); v.setScaleX(1f); v.setScaleY(1f); v.setTranslationZ(0f);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private static Badge pressed;
    private static float downX, downY;
    private static boolean tap(MotionEvent e, View base) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pressed = null;
            for (View v : new ArrayList<View>(badges.keySet())) {
                if (!(v instanceof Badge) || !v.isShown() || ((Badge) v).leaving || seen(v) < .5f) continue;
                if (base != null ? ((Badge) v).hitIn(base, e.getX(), e.getY()) : ((Badge) v).hit(e.getRawX(), e.getRawY())) { pressed = (Badge) v; break; }
            }
            if (pressed == null) return false;
            downX = e.getX(); downY = e.getY();
            pressed.animate().scaleX(.9f).scaleY(.9f).setDuration(90).start();
            return true;
        }
        if (pressed == null) return false;
        Badge b = pressed;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            pressed = null;
            b.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            float slop = 24 * b.getResources().getDisplayMetrics().density;
            if (action == MotionEvent.ACTION_UP && Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) {
                View tv = (View) b.getParent();
                Haptics.tick(b.getContext());
                if (tv != null && b.container != null) toggle(tv, b.container);
            }
        }
        return true;
    }

    private static java.lang.reflect.Method setTA, getTA;
    static void transitionAlpha(View v, float a) { try { if (setTA == null) setTA = View.class.getMethod("setTransitionAlpha", float.class); setTA.invoke(v, a); } catch (Throwable ignored) { } }
    static float transitionAlpha(View v) { try { if (getTA == null) getTA = View.class.getMethod("getTransitionAlpha"); return (Float) getTA.invoke(v); } catch (Throwable t) { return 1f; } }
    static float seen(View v) {
        float a = 1f;
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) a *= ((View) p).getAlpha() * transitionAlpha((View) p);
        return a;
    }

    static float[] centreIn(View v, View base) {
        float[] p = new float[]{v.getWidth() / 2f, v.getHeight() / 2f};
        View view = v;
        while (view != base) {
            view.getMatrix().mapPoints(p);
            p[0] += view.getLeft(); p[1] += view.getTop();
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) return null;
            View pv = (View) parent;
            p[0] -= pv.getScrollX(); p[1] -= pv.getScrollY();
            view = pv;
        }
        return p;
    }
    static float[] centreOnScreen(View v) {
        float[] p = new float[]{v.getWidth() / 2f, v.getHeight() / 2f};
        View view = v;
        while (true) {
            view.getMatrix().mapPoints(p);
            p[0] += view.getLeft(); p[1] += view.getTop();
            ViewParent parent = view.getParent();
            if (!(parent instanceof View)) break;
            View pv = (View) parent;
            p[0] -= pv.getScrollX(); p[1] -= pv.getScrollY();
            view = pv;
        }
        int[] win = new int[2];
        try { view.getLocationOnScreen(win); } catch (Throwable ignored) { }
        p[0] += win[0]; p[1] += win[1];
        return p;
    }

    static float scaleOf(View v) {
        float s = 1f;
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) s *= ((View) p).getScaleY();
        return s;
    }

    static final class Badge extends View {
        final int size, slot; Object container; String owner; boolean leaving;
        final Lock glyph; final GradientDrawable round = new GradientDrawable();
        private ViewTreeObserver.OnPreDrawListener follower;
        Badge(Context c, Object container, int slot) {
            super(c);
            this.container = container; this.slot = slot;
            float d = c.getResources().getDisplayMetrics().density;
            size = Math.round(36 * d);
            boolean dark = FrostedSurface.dark(c), black = SuperDark.inEffect(c);
            int bg = black ? 0xff000000 : dark ? 0xff2b2930 : 0xfffdf7fa, ink = dark || black ? 0xffe6e0e9 : 0xff1d1b20;
            round.setShape(GradientDrawable.OVAL); round.setColor(bg);
            if (black) round.setStroke(Math.max(1, Math.round(d)), 0x40ffffff);
            setBackground(round);
            glyph = new Lock(ink, 1f);
            glyph.setCallback(this);
            setElevation(3 * d);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setContentDescription("Locked");
        }
        @Override protected boolean verifyDrawable(Drawable who) { return who == glyph || super.verifyDrawable(who); }
        @Override protected void onDraw(Canvas canvas) {
            int inset = Math.round(size * 0.25f);
            glyph.setBounds(inset, inset, size - inset, size - inset);
            glyph.draw(canvas);
        }
        void follow(final View tv) {
            follower = new ViewTreeObserver.OnPreDrawListener() { public boolean onPreDraw() { place(); return true; } };
            getViewTreeObserver().addOnPreDrawListener(follower);
        }
        void place() {
            try {
                View tv = (View) getParent(); if (tv == null || container == null) return;
                View content = (View) Xp.callMethod(container, "getTaskContentView");
                float d = getResources().getDisplayMetrics().density, gap = 10 * d;
                float x = content.getX() + content.getWidth() / 2f - size / 2f, y;
                float rot = 0f;
                try { View chip = (View) Xp.callMethod(container, "getIconView"); if (chip != null) rot = ((chip.getRotation() % 360f) + 360f) % 360f; } catch (Throwable ignored) { }
                if (rot > 45f && rot < 135f) {
                    x = content.getX() + content.getWidth() + gap; y = content.getY() + content.getHeight() / 2f - size / 2f;
                    if (getRotation() != 90f) setRotation(90f);
                } else if (rot > 225f && rot < 315f) {
                    x = content.getX() - gap - size; y = content.getY() + content.getHeight() / 2f - size / 2f;
                    if (getRotation() != 270f) setRotation(270f);
                } else {
                    if (getRotation() != 0f) setRotation(0f);
                    boolean sideBySide = false;
                    for (Object c : containers(tv)) {
                        if (c == container) continue;
                        View other = (View) Xp.callMethod(c, "getTaskContentView");
                        if (other != null && Math.abs(other.getY() - content.getY()) < content.getHeight() / 2f) sideBySide = true;
                    }
                    if (slot == 0 || sideBySide) y = content.getY() - size - gap;
                    else y = content.getY() + content.getHeight() + gap;
                }
                if (getTranslationX() != x) setTranslationX(x);
                if (getTranslationY() != y) setTranslationY(y);
            } catch (Throwable ignored) { }
        }
        boolean hitIn(View base, float x, float y) {
            float[] c = centreIn(this, base);
            if (c == null) return false;
            float s = scaleOf(this), r = getWidth() * s / 2f + 10 * getResources().getDisplayMetrics().density;
            float dx = x - c[0], dy = y - c[1];
            return dx * dx + dy * dy <= r * r;
        }
        boolean hit(float rx, float ry) {
            float[] c = centreOnScreen(this);
            float s = scaleOf(this), r = getWidth() * s / 2f + 10 * getResources().getDisplayMetrics().density;
            float dx = rx - c[0], dy = ry - c[1];
            return dx * dx + dy * dy <= r * r;
        }
        void popIn() {
            place();
            setScaleX(.4f); setScaleY(.4f); setAlpha(0f);
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).setInterpolator(new OvershootInterpolator(1.6f)).start();
        }
        void popOut() {
            leaving = true;
            badges.remove(this);
            glyph.animateTo(0f);
            animate().cancel();
            animate().scaleX(1.12f).scaleY(1.12f).setDuration(170).setInterpolator(new DecelerateInterpolator())
                    .setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator a) {
                        animate().setListener(null);
                        animate().scaleX(.3f).scaleY(.3f).alpha(0f).setStartDelay(160).setDuration(230).setInterpolator(new android.view.animation.AnticipateInterpolator(1.2f))
                                .setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator b) { remove(); } }).start();
                    } }).start();
        }
        void remove() {
            leaving = true;
            badges.remove(this);
            try { if (follower != null) getViewTreeObserver().removeOnPreDrawListener(follower); } catch (Throwable ignored) { }
            follower = null;
            animate().setListener(null).cancel();
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        }
    }

    static final class Lock extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(); private final RectF rect = new RectF();
        private float closed; private ValueAnimator anim;
        Lock(int color, float closed) { paint.setColor(color); this.closed = closed; }
        void animateTo(float to) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(closed, to);
            anim.setDuration(260); anim.setInterpolator(new OvershootInterpolator(2f));
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator a) { closed = (Float) a.getAnimatedValue(); invalidateSelf(); } });
            anim.start();
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height(), x0 = b.left, y0 = b.top;
            float open = 1f - Math.max(0f, Math.min(1.2f, closed));
            paint.setStyle(Paint.Style.FILL);
            rect.set(x0 + w * .18f, y0 + h * .5f, x0 + w * .82f, y0 + h * .96f);
            c.drawRoundRect(rect, w * .1f, w * .1f, paint);
            float r = w * .19f, cx = x0 + w * .5f, lift = h * .12f * open, top = y0 + h * .2f - lift + r, legR = y0 + h * .52f, legL = y0 + h * .52f - (h * .18f) * open;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(w * .11f); paint.setStrokeCap(Paint.Cap.ROUND);
            path.reset();
            path.moveTo(cx - r, legL - lift);
            path.lineTo(cx - r, top);
            rect.set(cx - r, top - r, cx + r, top + r);
            path.arcTo(rect, 180, 180, false);
            path.lineTo(cx + r, legR);
            c.drawPath(path, paint);
        }
        @Override public void setTintList(ColorStateList tint) { if (tint != null) { paint.setColor(tint.getDefaultColor()); invalidateSelf(); } }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static int turnOf(View card) {
        try {
            for (Object c : containers(card)) {
                View chip = (View) Xp.callMethod(c, "getIconView");
                if (chip == null) continue;
                float r = ((chip.getRotation() % 360f) + 360f) % 360f;
                if (r > 45f && r < 135f) return 90;
                if (r > 225f && r < 315f) return 270;
                return 0;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    private static TextView shown;
    static void toast(View card, String message) {
        try {
            Activity a = LauncherFeatures.activity.get(); if (a == null) return;
            final ViewGroup layer = (ViewGroup) Xp.callMethod(a, "getDragLayer"); if (layer == null) return;
            if (shown != null) { TextView old = shown; shown = null; old.animate().setListener(null).cancel(); if (old.getParent() != null) ((ViewGroup) old.getParent()).removeView(old); }
            Context c = layer.getContext();
            float d = c.getResources().getDisplayMetrics().density;
            final TextView pill = new TextView(c);
            pill.setText(message);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            pill.setSingleLine(true);
            pill.setGravity(Gravity.CENTER);
            pill.setPaddingRelative(Math.round(16 * d), Math.round(10 * d), Math.round(16 * d), Math.round(10 * d));
            try { pill.setTypeface(android.graphics.Typeface.create("google-sans-text-medium", android.graphics.Typeface.NORMAL)); } catch (Throwable ignored) { }
            boolean dark = FrostedSurface.dark(c);
            GradientDrawable plain = new GradientDrawable(); plain.setCornerRadius(100 * d);
            plain.setColor(dark ? 0xff36343b : 0xffece6f0);
            pill.setBackground(plain); pill.setTextColor(dark ? 0xffe6e0e9 : 0xff1d1b20);
            layer.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
            if (SurfaceStyle.restyled(c)) try { PillFrost.apply(pill, pill, true); } catch (Throwable ignored) { }
            pill.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int[] at = new int[2], base = new int[2]; card.getLocationOnScreen(at); layer.getLocationOnScreen(base);
            float s = scaleOf(card);
            float cx = at[0] - base[0] + card.getWidth() * s / 2f, cardTop = at[1] - base[1];
            boolean overCard = onScreen(card) >= 0.5f;
            if (!overCard) cx = layer.getWidth() / 2f;
            float half = pill.getMeasuredWidth() / 2f + 16 * d;
            if (layer.getWidth() > 2 * half) cx = Math.max(half, Math.min(layer.getWidth() - half, cx));
            int turn = turnOf(card);
            if (turn != 0) {
                float cardLeft = at[0] - base[0], cardRight = cardLeft + card.getWidth() * s;
                float gx = turn == 90 ? cardLeft / 2f : (cardRight + layer.getWidth()) / 2f;
                gx += (turn == 90 ? -14 : 14) * d;
                float edge = pill.getMeasuredHeight() / 2f + 8 * d;
                gx = Math.max(edge, Math.min(layer.getWidth() - edge, gx));
                if ((turn == 90 ? cardLeft : layer.getWidth() - cardRight) < pill.getMeasuredHeight() + 8 * d)
                    gx = turn == 90 ? pill.getMeasuredHeight() / 2f + 8 * d : layer.getWidth() - pill.getMeasuredHeight() / 2f - 8 * d;
                float gy = layer.getHeight() / 2f;
                int mt = 0, ml = 0;
                if (pill.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) { mt = ((ViewGroup.MarginLayoutParams) pill.getLayoutParams()).topMargin; ml = ((ViewGroup.MarginLayoutParams) pill.getLayoutParams()).leftMargin; }
                pill.setRotation(turn);
                pill.setTranslationX(gx - pill.getMeasuredWidth() / 2f - ml);
                pill.setTranslationY(gy - pill.getMeasuredHeight() / 2f - mt);
                pill.setAlpha(0f); pill.setScaleX(.92f); pill.setScaleY(.92f);
                pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
                shown = pill;
                pill.postDelayed(new Runnable() { public void run() {
                    pill.animate().alpha(0f).setDuration(200).setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator x) {
                        if (pill.getParent() != null) ((ViewGroup) pill.getParent()).removeView(pill);
                        if (shown == pill) shown = null;
                    } }).start();
                } }, 2500);
                return;
            }
            float y = cardTop - pill.getMeasuredHeight() - 10 * d;
            int topInset = 0;
            try { topInset = layer.getRootWindowInsets().getSystemWindowInsetTop(); } catch (Throwable ignored) { }
            final List<View> hidden = new ArrayList<View>();
            Badge above = overCard && card instanceof ViewGroup ? badgeFor(card, 0) : null;
            if (above != null) {
                float badgeTop = cardTop + (above.getTranslationY()) * s;
                float over = badgeTop - pill.getMeasuredHeight() - 6 * d;
                if (over >= topInset + 2 * d) y = over;
                else { y = badgeTop + (above.size * s - pill.getMeasuredHeight()) / 2f; hidden.add(above); above.animate().alpha(0f).setDuration(120).start(); }
            }
            y = Math.max(topInset + 2 * d, y);
            int marginTop = 0, marginLeft = 0;
            if (pill.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) { marginTop = ((ViewGroup.MarginLayoutParams) pill.getLayoutParams()).topMargin; marginLeft = ((ViewGroup.MarginLayoutParams) pill.getLayoutParams()).leftMargin; }
            pill.setTranslationX(cx - pill.getMeasuredWidth() / 2f - marginLeft);
            pill.setTranslationY(y - marginTop);
            pill.setAlpha(0f); pill.setScaleX(.92f); pill.setScaleY(.92f);
            pill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
            shown = pill;
            pill.postDelayed(new Runnable() { public void run() {
                pill.animate().alpha(0f).setDuration(200).setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator x) {
                    if (pill.getParent() != null) ((ViewGroup) pill.getParent()).removeView(pill);
                    if (shown == pill) shown = null;
                } }).start();
                for (View v : hidden) if (v.getParent() != null && !((Badge) v).leaving) v.animate().alpha(1f).setDuration(160).start();
            } }, 2500);
        } catch (Throwable error) { fail("message", error); }
    }
}
