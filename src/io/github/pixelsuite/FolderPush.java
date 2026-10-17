package io.github.pixelsuite;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class FolderPush {
    private static final long DELAY = 250, MOVE_MS = 230;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(1.6f);

    private final View source;
    private final ViewGroup layer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<View, Boolean> moved = new IdentityHashMap<View, Boolean>();
    private int shown = -1, pending = -1;
    private Runnable task;
    private boolean done;

    FolderPush(View source, ViewGroup layer) { this.source = cellOf(source); this.layer = layer; }

    private static View cellOf(View v) {
        View cell = v;
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            Object tag = ((View) p).getTag();
            if (tag != null && DrawerFolders.isFolder(tag)) cell = (View) p; else break;
        }
        return cell;
    }

    void over(int x, int y) {
        if (done) return;
        List<View> cells = cells(); if (cells.size() < 2 || !cells.contains(source)) return;
        if (!overFolders(cells, x, y)) { away(); return; }
        int nearest = -1; long best = Long.MAX_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            int[] c = centre(cells.get(i)); long dx = c[0] - x, dy = c[1] - y, d = dx * dx + dy * dy;
            if (d < best) { best = d; nearest = i; }
        }
        if (nearest < 0 || nearest == pending) return;
        pending = nearest;
        if (task != null) handler.removeCallbacks(task);
        final int to = nearest;
        task = new Runnable() { public void run() { task = null; if (!done && to != shown) show(to); } };
        handler.postDelayed(task, DELAY);
    }

    private boolean overFolders(List<View> cells, int x, int y) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = Integer.MIN_VALUE, bottom = Integer.MIN_VALUE, mx = 0, my = 0;
        for (View v : cells) {
            int[] b = base(v);
            left = Math.min(left, b[0]); top = Math.min(top, b[1]); right = Math.max(right, b[0] + v.getWidth()); bottom = Math.max(bottom, b[1] + v.getHeight());
            mx = Math.max(mx, v.getWidth() / 4); my = Math.max(my, v.getHeight() / 4);
        }
        return y >= top - my && y <= bottom + my;
    }

    private void away() {
        if (task != null) { handler.removeCallbacks(task); task = null; }
        if (pending < 0 && shown < 0) return;
        pending = -1; shown = -1;
        reset(true);
    }

    boolean active() { return !done && (pending >= 0 || shown >= 0); }

    boolean commit() {
        if (done) return false;
        if (task != null) { handler.removeCallbacks(task); task = null; }
        List<View> cells = cells(); int from = cells.indexOf(source);
        int to = pending >= 0 ? pending : shown;
        done = true;
        Integer moving = from < 0 ? null : DrawerFolders.folderIds.get(source.getTag());
        Integer target = to < 0 || to >= cells.size() ? null : DrawerFolders.folderIds.get(cells.get(to).getTag());
        if (from < 0 || to < 0 || to == from || moving == null || target == null) { reset(true); return false; }
        source.setAlpha(0f);
        final View list = (View) source.getParent();
        boolean ok = DrawerFolders.moveFolder(moving, target, to > from);
        if (!ok) { source.setAlpha(1f); reset(false); return false; }
        settleAfterRebind(list);
        return true;
    }

    private void settleAfterRebind(View anyInList) {
        final View root = anyInList != null ? anyInList.getRootView() : source.getRootView();
        final boolean[] settled = new boolean[1];
        final Runnable settle = new Runnable() { public void run() {
            if (settled[0]) return; settled[0] = true;
            reset(false); source.setAlpha(1f);
        }};
        final android.view.ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                try { root.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ignored) { }
                settle.run();
            }
        });
        handler.postDelayed(settle, 500);
    }

    void restore() {
        if (done) return;
        done = true;
        if (task != null) { handler.removeCallbacks(task); task = null; }
        reset(true);
    }

    private void show(int to) {
        List<View> cells = cells(); int from = cells.indexOf(source);
        if (from < 0 || to >= cells.size()) return;
        List<int[]> slots = new ArrayList<int[]>();
        for (View v : cells) slots.add(base(v));
        long delay = 0; float step = 30f;
        for (int i = 0; i < cells.size(); i++) {
            View v = cells.get(i); if (v == source) continue;
            int slot = from < to ? (i > from && i <= to ? i - 1 : i) : (i >= to && i < from ? i + 1 : i);
            float dx = slots.get(slot)[0] - slots.get(i)[0], dy = slots.get(slot)[1] - slots.get(i)[1];
            if (Math.abs(v.getTranslationX() - dx) < 1 && Math.abs(v.getTranslationY() - dy) < 1) continue;
            moved.put(v, Boolean.TRUE);
            v.animate().cancel();
            v.animate().translationX(dx).translationY(dy).setDuration(MOVE_MS).setStartDelay(delay).setInterpolator(EASE).start();
            delay += Math.round(step); step *= .9f;
        }
        shown = to;
    }

    private void reset(boolean animate) {
        for (View v : moved.keySet()) {
            v.animate().cancel();
            if (animate && v.isAttachedToWindow()) v.animate().translationX(0f).translationY(0f).setDuration(MOVE_MS).setStartDelay(0).setInterpolator(EASE).start();
            else { v.setTranslationX(0f); v.setTranslationY(0f); }
        }
        moved.clear();
    }

    private List<View> cells() {
        final List<View> out = new ArrayList<View>();
        try { collect((View) LauncherFeatures.call(LauncherFeatures.activity.get(), "getAppsView"), out); } catch (Throwable ignored) { }
        final Map<View, int[]> at = new IdentityHashMap<View, int[]>();
        for (View v : out) at.put(v, base(v));
        Collections.sort(out, new Comparator<View>() {
            public int compare(View a, View b) {
                int[] p = at.get(a), q = at.get(b);
                if (Math.abs(p[1] - q[1]) > a.getHeight() / 2) return p[1] < q[1] ? -1 : 1;
                return p[0] < q[0] ? -1 : p[0] > q[0] ? 1 : 0;
            }
        });
        return out;
    }

    private void collect(View v, List<View> out) {
        if (v == null || v.getVisibility() == View.GONE) return;
        if (v instanceof FrameLayout && v.getTag() != null && DrawerFolders.isFolder(v.getTag())) {
            if (v.isAttachedToWindow() && v.getWidth() > 0 && (v == source || v.isShown())) out.add(v);
            return;
        }
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out); }
    }

    private int[] base(View v) {
        int[] a = new int[2], o = new int[2]; v.getLocationOnScreen(a); layer.getLocationOnScreen(o);
        return new int[] {Math.round(a[0] - o[0] - v.getTranslationX()), Math.round(a[1] - o[1] - v.getTranslationY())};
    }

    private int[] centre(View v) { int[] b = base(v); return new int[] {b[0] + v.getWidth() / 2, b[1] + v.getHeight() / 2}; }
}
