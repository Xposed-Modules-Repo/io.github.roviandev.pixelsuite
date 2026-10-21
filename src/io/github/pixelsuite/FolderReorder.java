package io.github.pixelsuite;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class FolderReorder {
    private static final long DELAY = 250, MOVE_MS = 230;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    private final View folder, source;
    private final ViewGroup paged;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> original;
    private final int startRank;
    private int shownRank, pendingRank = -1;
    private boolean arranged, done;
    private Runnable pending;
    private int[] dropFrom;

    private FolderReorder(View folder, ViewGroup paged, View source, List<View> original) {
        this.folder = folder; this.paged = paged; this.source = source; this.original = original;
        startRank = Math.max(0, original.indexOf(source)); shownRank = startRank;
    }

    static FolderReorder start(View folder, View source) {
        try {
            Object info = LauncherFeatures.get(folder, "mInfo");
            LauncherData.Folder f = DrawerFolders.record(info);
            if (f == null || f.sort == FolderSort.AUTO) return null;
            ViewGroup paged = (ViewGroup) LauncherFeatures.get(folder, "mContent");
            List<View> views = new ArrayList<View>();
            for (Object v : (List<?>) LauncherFeatures.call(folder, "getIconsInReadingOrder")) if (v instanceof View) views.add((View) v);
            if (!views.contains(source)) return null;
            return new FolderReorder(folder, paged, source, views);
        } catch (Throwable error) { LauncherFeatures.logOnce("Drawer folder reorder unavailable", error); return null; }
    }

    void over(Object dragObject, int x, int y) {
        if (done) return;
        int rank = targetRank(dragObject, x, y);
        if (rank < 0 || rank == pendingRank) return;
        pendingRank = rank;
        if (pending != null) handler.removeCallbacks(pending);
        final int to = rank;
        pending = new Runnable() { public void run() { pending = null; if (!done && (to != shownRank || groupChanged())) show(to); } };
        handler.postDelayed(pending, DELAY);
    }

    boolean commit(Object dragObject) {
        if (done) return false;
        try {
            View drag = (View) LauncherFeatures.get(dragObject, "dragView");
            if (drag != null && drag.getWidth() > 0) { int[] at = new int[2]; drag.getLocationOnScreen(at); dropFrom = new int[] {at[0] + Math.round(drag.getWidth() * drag.getScaleX() / 2f), at[1]}; }
        } catch (Throwable ignored) { }
        cancelPending();
        int rank = pendingRank >= 0 ? pendingRank : shownRank;
        List<View> rest = rest(), group = group();
        rank = Math.max(0, Math.min(rank, rest.size()));
        List<View> order = new ArrayList<View>(rest);
        order.addAll(rank, group);
        done = true;
        if (order.equals(original)) { if (arranged) layout(order); return false; }
        try {
            List<String> keys = new ArrayList<String>();
            for (View v : order) { String k = LauncherFeatures.identity(v.getTag()); if (k != null && !keys.contains(k)) keys.add(k); }
            Object info = LauncherFeatures.get(folder, "mInfo");
            Integer id = DrawerFolders.folderIds.get(info);
            if (id == null) { layout(original); return false; }
            List<LauncherData.Folder> list = LauncherFeatures.data.folders(); boolean found = false;
            for (LauncherData.Folder f : list) if (f.id == id) {
                List<String> apps = new ArrayList<String>(keys);
                for (String k : f.apps) if (!apps.contains(k)) apps.add(k);
                f.apps.clear(); f.apps.addAll(apps); found = true;
            }
            if (!found || !LauncherFeatures.data.save(list)) { layout(original); return false; }
            layout(order);
            try {
                @SuppressWarnings("unchecked") List<Object> contents = (List<Object>) LauncherFeatures.get(info, "contents");
                List<Object> sorted = new ArrayList<Object>();
                for (View v : order) { Object t = v.getTag(); if (t != null && contents.contains(t) && !sorted.contains(t)) sorted.add(t); }
                for (Object t : contents) if (!sorted.contains(t)) sorted.add(t);
                if (sorted.size() == contents.size()) {
                    contents.clear(); contents.addAll(sorted);
                    for (int i = 0; i < sorted.size(); i++) try { LauncherFeatures.set(sorted.get(i), "rank", i); } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
            Xp.setAdditionalInstanceField(folder, "pixelsuite_after_close", new Runnable() { public void run() { LauncherFeatures.refresh(); } });
            return true;
        } catch (Throwable error) {
            Module.log("Drawer folder reorder failed", error);
            try { layout(original); } catch (Throwable ignored) { }
            return false;
        }
    }

    void restore() {
        if (done) return;
        done = true; cancelPending();
        if (arranged) try { layout(original); } catch (Throwable error) { LauncherFeatures.logOnce("Drawer folder reorder restore failed", error); }
    }

    void leave() {
        if (done) return;
        cancelPending(); pendingRank = -1;
        if (arranged) { try { layout(original); } catch (Throwable ignored) { } arranged = false; }
        shownRank = startRank;
    }

    private void cancelPending() { if (pending != null) { handler.removeCallbacks(pending); pending = null; } }

    private List<View> group() {
        List<View> g = new ArrayList<View>(); g.add(source);
        for (View v : original) if (v != source && picked(v)) g.add(v);
        return g;
    }
    private List<View> rest() {
        List<View> r = new ArrayList<View>();
        for (View v : original) if (v != source && !picked(v)) r.add(v);
        return r;
    }
    private static boolean picked(View v) { return Boolean.TRUE.equals(Xp.getAdditionalInstanceField(v, "pixelsuite_picked")); }
    private int lastGroupSize = 1;
    private boolean groupChanged() { return group().size() != lastGroupSize; }

    private int targetRank(Object dragObject, int x, int y) {
        try {
            int cx = x, cy = y;
            try {
                View drag = (View) LauncherFeatures.get(dragObject, "dragView");
                if (drag != null && drag.getWidth() > 0) {
                    int[] at = new int[2]; drag.getLocationOnScreen(at);
                    int[] base = new int[2]; ((View) folder.getParent()).getLocationOnScreen(base);
                    cx = at[0] - base[0] + Math.round(drag.getWidth() * drag.getScaleX() / 2f);
                    cy = at[1] - base[1] + Math.round(drag.getHeight() * drag.getScaleY() / 2f);
                }
            } catch (Throwable ignored) { }
            int[] content = new int[2]; paged.getLocationOnScreen(content);
            int[] base = new int[2]; ((View) folder.getParent()).getLocationOnScreen(base);
            int px = cx - (content[0] - base[0]), py = cy - (content[1] - base[1]);
            int rank;
            try { rank = ((Number) LauncherFeatures.call(paged, "findNearestArea", px, py)).intValue(); }
            catch (Throwable noLegacyMethod) {
                int next = page(); View cell = paged.getChildAt(next);
                if (cell == null) return -1;
                int[] at = new int[2]; cell.getLocationOnScreen(at);
                int[] nearest = new int[2];
                float scaleX = 1f, scaleY = 1f;
                for (View v = cell; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                    scaleX *= v.getScaleX(); scaleY *= v.getScaleY();
                }
                if (scaleX <= 0f || scaleY <= 0f) return -1;
                int cellX = Math.round((cx + base[0] - at[0]) / scaleX);
                int cellY = Math.round((cy + base[1] - at[1]) / scaleY);
                LauncherFeatures.call(cell, "findNearestArea", cellX, cellY, 1, 1, 1, 1, true, nearest, null);
                int countX = ((Number) LauncherFeatures.get(paged, "mGridCountX")).intValue();
                if (Boolean.TRUE.equals(LauncherFeatures.call(folder, "isLayoutRtl"))) nearest[0] = countX - nearest[0] - 1;
                rank = next * itemsPerPage() + nearest[1] * countX + nearest[0];
            }
            return Math.max(0, Math.min(rank, rest().size()));
        } catch (Throwable error) { LauncherFeatures.logOnce("Drawer folder target slot unavailable", error); return -1; }
    }

    private void show(int rank) {
        List<View> rest = rest(); int gap = group().size(); lastGroupSize = gap;
        rank = Math.max(0, Math.min(rank, rest.size()));
        List<View> order = new ArrayList<View>(rest);
        for (int i = 0; i < gap; i++) order.add(rank, null);
        try { layout(order); arranged = true; shownRank = rank; }
        catch (Throwable error) { LauncherFeatures.logOnce("Drawer folder reorder failed", error); }
    }

    private void layout(List<View> order) {
        final int visible = page();
        int per = itemsPerPage();
        final int perPage = Math.max(1, per);
        final Map<View, int[]> before = new IdentityHashMap<View, int[]>();
        for (View v : original) {
            if (v.getParent() == null) continue;
            v.animate().cancel();
            int[] at = local(v);
            before.put(v, new int[] {Math.round(at[0] + v.getTranslationX()), Math.round(at[1] + v.getTranslationY()), pageOf(v)});
            v.setTranslationX(0f); v.setTranslationY(0f);
            if (!picked(v) && v != source) v.setAlpha(1f);
        }
        for (int rank = 0; rank < order.size(); rank++) {
            View v = order.get(rank); if (v == null) continue;
            int[] was = before.get(v);
            int now = rank / perPage;
            if (was != null && was[2] == visible && now != visible && v.isShown() && v.getAlpha() > 0f) ghost(v, now > visible ? 1 : -1);
        }
        LauncherFeatures.call(paged, "arrangeChildren", order);
        try { LauncherFeatures.set(folder, "mItemsInvalidated", true); } catch (Throwable ignored) { }
        final List<View> moved = new ArrayList<View>(); for (View v : order) if (v != null) moved.add(v);
        final ViewTreeObserver observer = folder.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                try { if (observer.isAlive()) observer.removeOnPreDrawListener(this); else folder.getViewTreeObserver().removeOnPreDrawListener(this); } catch (Throwable ignored) { }
                long delay = 0; float step = 30f;
                for (View v : moved) {
                    int[] was = before.get(v); if (was == null || v.getParent() == null) continue;
                    int page = pageOf(v); if (page != visible) continue;
                    if (v == source && dropFrom != null) {
                        int[] at = new int[2]; v.getLocationOnScreen(at);
                        View icon = v;
                        float dx = dropFrom[0] - (at[0] + icon.getWidth() / 2f), dy = dropFrom[1] - (at[1] + icon.getPaddingTop());
                        v.setTranslationX(dx); v.setTranslationY(dy);
                        v.animate().translationX(0f).translationY(0f).setDuration(200).setStartDelay(0).setInterpolator(EASE).start();
                        dropFrom = null; continue;
                    }
                    if (v == source || picked(v)) continue;
                    if (was[2] == visible) {
                        int[] at = local(v); float dx = was[0] - at[0], dy = was[1] - at[1];
                        if (Math.abs(dx) < 1 && Math.abs(dy) < 1) continue;
                        v.setTranslationX(dx); v.setTranslationY(dy);
                        v.animate().translationX(0f).translationY(0f).setDuration(MOVE_MS).setStartDelay(delay).setInterpolator(EASE).start();
                    } else {
                        v.setTranslationX((was[2] > visible ? 1 : -1) * v.getWidth() * .6f); v.setAlpha(0f);
                        v.animate().translationX(0f).alpha(1f).setDuration(MOVE_MS).setStartDelay(delay).setInterpolator(EASE).start();
                    }
                    delay += Math.round(step); step *= .9f;
                }
                return true;
            }
        });
    }

    private void ghost(View v, int direction) {
        try {
            int w = v.getWidth(), h = v.getHeight(); if (w <= 0 || h <= 0) return;
            Bitmap picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(picture));
            final BitmapDrawable d = new BitmapDrawable(v.getResources(), picture);
            int[] at = new int[2], origin = new int[2]; v.getLocationOnScreen(at); folder.getLocationOnScreen(origin);
            final Rect start = new Rect(at[0] - origin[0], at[1] - origin[1], at[0] - origin[0] + w, at[1] - origin[1] + h);
            d.setBounds(start);
            final android.view.ViewOverlay overlay = folder.getOverlay(); overlay.add(d);
            final int distance = direction * w;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(MOVE_MS); a.setInterpolator(EASE);
            a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(android.animation.ValueAnimator va) {
                    float t = (Float) va.getAnimatedValue(); int dx = Math.round(distance * t);
                    d.setBounds(start.left + dx, start.top, start.right + dx, start.bottom); d.setAlpha(Math.round(255 * (1f - t)));
                }
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                public void onAnimationEnd(android.animation.Animator an) { overlay.remove(d); }
                public void onAnimationCancel(android.animation.Animator an) { overlay.remove(d); }
            });
            a.start();
        } catch (Throwable error) { LauncherFeatures.logOnce("Drawer folder page push animation unavailable", error); }
    }

    private int page() { try { return ((Number) LauncherFeatures.call(paged, "getNextPage")).intValue(); } catch (Throwable t) { return 0; } }

    private int itemsPerPage() {
        try { return Math.max(1, ((Number) LauncherFeatures.get(LauncherFeatures.get(paged, "mOrganizer"), "mMaxItemsPerPage")).intValue()); }
        catch (Throwable noField) { return Math.max(1, ((Number) LauncherFeatures.call(paged, "itemsPerPage")).intValue()); }
    }

    private int pageOf(View v) {
        try {
            View container = (View) v.getParent(); if (container == null) return -1;
            View cellLayout = (View) container.getParent(); if (cellLayout == null) return -1;
            return paged.indexOfChild(cellLayout);
        } catch (Throwable t) { return -1; }
    }

    private static int[] local(View v) {
        View container = (View) v.getParent();
        int x = v.getLeft(), y = v.getTop();
        if (container != null) { x += container.getLeft(); y += container.getTop(); }
        return new int[] {x, y};
    }
}
