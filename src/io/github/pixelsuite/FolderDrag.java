package io.github.pixelsuite;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;

/**
 * Dragging apps in and around folders.
 *  - A held app that touches the left or right border of an open folder switches the folder page.
 *  - It leaves the folder only after it has been completely outside the folder and its border
 *    for 1.5 s. (Home folders: the folder's hit area is widened by a border and the stock exit
 *    timer is lengthened; drawer folders use {@link Stay}.)
 *  - Two-finger drag: while one finger holds an app, a second finger swipes to change the
 *    home page, the folder page, or scroll the app drawer. Switchable in Pixel Suite settings.
 */
final class FolderDrag {
    static final long EXIT_MS = 250, EDGE_MS = 500, REPEAT_MS = 800;
    static final String TWO_FINGER = "pixelsuite_two_finger_drag";
    private static final ThreadLocal<Boolean> exiting = new ThreadLocal<Boolean>();
    private static boolean alarmHooked;
    private static int second = -1;
    private static float anchorX, anchorY, downX, downY, travel;
    private static long downAt;

    private FolderDrag() {}

    private static Object get(Object o, String n) { return LauncherFeatures.get(o, n); }
    private static Object call(Object o, String n, Object... v) { return LauncherFeatures.call(o, n, v); }
    private static Class<?> type(String n) { return LauncherFeatures.type(n); }

    static void install() {
        try {
            Xp.hookAllMethods(type("folder.Folder"), "getHitRectRelativeToDragLayer", new Xp.Callback() {
                protected void afterHookedMethod(Xp.Param p) {
                    try {
                        Rect r = (Rect) p.args[0]; int b = LauncherFeatures.dp(((View) p.thisObject).getContext(), 16);
                        r.inset(-b, -b);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Folder border area unavailable", error); }
        try {
            Xp.hookAllMethods(type("folder.Folder"), "onDragExit", new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) {
                    exiting.set(Boolean.TRUE);
                    if (alarmHooked) return;
                    try {
                        Object alarm = get(p.thisObject, "mOnExitAlarm");
                        if (alarm == null) return;
                        alarmHooked = true;
                        Xp.hookAllMethods(alarm.getClass(), "setAlarm", new Xp.Callback() {
                            protected void beforeHookedMethod(Xp.Param q) {
                                if (exiting.get() == null || q.args.length != 1 || !(q.args[0] instanceof Number)) return;
                                if (((Number) q.args[0]).longValue() < EXIT_MS) q.args[0] = Long.valueOf(EXIT_MS);
                            }
                        });
                    } catch (Throwable error) { LauncherFeatures.logOnce("Folder exit delay unavailable", error); }
                }
                protected void afterHookedMethod(Xp.Param p) { exiting.remove(); }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Folder exit delay unavailable", error); }
        try {
            // Hook the launcher's override once; MotionEvent instances are recycled between gestures.
            Xp.Callback callback = new Xp.Callback() {
                protected void beforeHookedMethod(Xp.Param p) { try { touch(p); } catch (Throwable error) { LauncherFeatures.logOnce("Two-finger drag unavailable", error); } }
            };
            // The launcher's own override when it has one; otherwise Activity's (filtered to the launcher in touch()).
            if (Xp.hookAllMethods(type("Launcher"), "dispatchTouchEvent", callback).isEmpty())
                Xp.hookMethod(Activity.class.getMethod("dispatchTouchEvent", MotionEvent.class), callback);
        } catch (Throwable error) { LauncherFeatures.logOnce("Two-finger drag unavailable", error); }
    }

    // ---- two-finger drag -----------------------------------------------------------------

    static boolean dragging(Activity a) {
        try {
            Object controller = get(a, "mDragController");
            try { return Boolean.TRUE.equals(call(controller, "isDragging")); }
            catch (Throwable t) { return get(controller, "mDragObject") != null; }
        } catch (Throwable t) { return false; }
    }

    private static void touch(Xp.Param p) {
        Activity a = LauncherFeatures.activity.get();
        if (a == null || p.thisObject != a || !(p.args[0] instanceof MotionEvent)) return;
        MotionEvent e = (MotionEvent) p.args[0];
        boolean paging = LauncherFeatures.on(TWO_FINGER), picking = MultiDrag.on();
        int action = e.getActionMasked();
        if (picking && action == MotionEvent.ACTION_MOVE) MultiDrag.follow();
        // A move with no second finger down has nothing to do below: skip the drag-state lookup on every frame of a swipe.
        if (action == MotionEvent.ACTION_MOVE && second < 0) return;
        if ((!paging && !picking) || !dragging(a)) { second = -1; return; }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (second < 0) {
                int i = e.getActionIndex(); second = e.getPointerId(i); anchorX = downX = e.getX(i); anchorY = downY = e.getY(i);
                downAt = e.getEventTime(); travel = 0; stoppedFling = stopFling(a);
                if (velocity != null) velocity.recycle(); velocity = VelocityTracker.obtain(); velocity.addMovement(e);
            }
            p.setResult(true);
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int i = e.getActionIndex();
            if (e.getPointerId(i) == second) {
                if (velocity != null) velocity.addMovement(e);
                flingDrawer(a, e.getPointerId(i), travel);
                second = -1; p.setResult(true);
                // A second-finger tap (not a swipe) picks up the app under it (MultiDrag).
                int slop = android.view.ViewConfiguration.get(a).getScaledTouchSlop();
                if (picking && !stoppedFling && travel < slop * 2 && e.getEventTime() - downAt < 400) MultiDrag.tap(a, downX, downY);
            }
        } else if (action == MotionEvent.ACTION_MOVE && second >= 0) {
            int i = e.findPointerIndex(second);
            if (i < 0) { second = -1; return; }
            travel = Math.max(travel, (float) Math.hypot(e.getX(i) - downX, e.getY(i) - downY));
            if (velocity != null) velocity.addMovement(e);
            if (paging) navigate(a, e.getX(i), e.getY(i));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) second = -1;
    }

    private static VelocityTracker velocity;
    private static boolean stoppedFling;

    /** A touch while the drawer coasts stops it on the spot, like a normal list. True when it was coasting. */
    private static boolean stopFling(Activity a) {
        try {
            Object list = drawerList(a); if (list == null) return false;
            if (((Number) call(list, "getScrollState")).intValue() == 0) return false;
            call(list, "stopScroll"); DrawerDrag.refreshHover(); return true;
        } catch (Throwable ignored) { return false; }
    }

    /** The app drawer's list when the drawer is what a second-finger swipe should scroll, else null. */
    private static Object drawerList(Activity a) {
        if (DrawerDrag.fromFolder()) return null;   // apps held from a drawer folder: the drawer behind it stays put
        boolean drawer = DrawerDrag.active();
        if (!drawer) try { drawer = LauncherFeatures.launcherState(call(a, "getStateManager")) == type("LauncherState").getField("ALL_APPS").get(null); } catch (Throwable ignored) { }
        if (!drawer) return null;
        try { return call(call(a, "getAppsView"), "getActiveRecyclerView"); } catch (Throwable error) { return null; }
    }

    /**
     * Lifting the scrolling finger flings the drawer like a normal scroll: while an app is held the
     * list followed the second finger one to one and stopped dead, which felt slow next to a stock
     * swipe. The held app keeps tracking the list while it coasts.
     */
    private static void flingDrawer(Activity a, int pointerId, float moved) {
        VelocityTracker tracker = velocity; velocity = null;
        if (tracker == null) return;
        try {
            if (!LauncherFeatures.on(TWO_FINGER) || moved < android.view.ViewConfiguration.get(a).getScaledTouchSlop()) return;
            Object list = drawerList(a); if (list == null) return;
            android.view.ViewConfiguration c = android.view.ViewConfiguration.get(a);
            tracker.computeCurrentVelocity(1000, c.getScaledMaximumFlingVelocity());
            int vy = Math.round(-tracker.getYVelocity(pointerId));
            if (Math.abs(vy) < c.getScaledMinimumFlingVelocity()) return;
            call(list, "fling", 0, vy);
            final Object recycler = list; final long until = android.os.SystemClock.uptimeMillis() + 4000;
            android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() {
                public void doFrame(long nanos) {
                    try {
                        DrawerDrag.refreshHover();
                        if (((Number) call(recycler, "getScrollState")).intValue() != 0 && android.os.SystemClock.uptimeMillis() < until && DrawerDrag.active())
                            android.view.Choreographer.getInstance().postFrameCallback(this);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable error) { LauncherFeatures.logOnce("Drawer fling unavailable", error); }
        finally { tracker.recycle(); }
    }

    private static void navigate(Activity a, float x, float y) {
        float dx = x - anchorX, dy = y - anchorY;
        int step = LauncherFeatures.dp(a, 56);
        Object top = null;
        try { top = Xp.callStaticMethod(type("AbstractFloatingView"), "getTopOpenView", a); } catch (Throwable ignored) { }
        if (top != null && type("folder.Folder").isInstance(top)) {
            if (Math.abs(dx) > step && Math.abs(dx) > Math.abs(dy)) { page(get(top, "mContent"), dx < 0 ? 1 : -1); anchorX = x; anchorY = y; }
            return;
        }
        boolean drawer = DrawerDrag.active();
        if (!drawer) try { drawer = LauncherFeatures.launcherState(call(a, "getStateManager")) == type("LauncherState").getField("ALL_APPS").get(null); } catch (Throwable ignored) { }
        if (drawer) {
            // preview65: holding apps that came out of a drawer folder, a second-finger swipe must not scroll the
            // drawer behind the open folder (a second-finger tap still picks apps).
            if (DrawerDrag.fromFolder()) return;
            try {
                Object list = call(call(a, "getAppsView"), "getActiveRecyclerView");
                int move = Math.round(-dy);
                if (move != 0) { call(list, "scrollBy", 0, move); anchorX = x; anchorY = y; DrawerDrag.refreshHover(); }
            } catch (Throwable error) { LauncherFeatures.logOnce("Two-finger drawer scroll unavailable", error); }
            return;
        }
        if (Math.abs(dx) > step && Math.abs(dx) > Math.abs(dy)) { page(get(a, "mWorkspace"), dx < 0 ? 1 : -1); anchorX = x; anchorY = y; }
    }

    /** Moves a PagedView (Workspace or folder content) one page; false at either end. */
    static boolean page(Object paged, int direction) {
        try {
            int count = ((Number) call(paged, "getPageCount")).intValue(), now = ((Number) call(paged, "getNextPage")).intValue(), to = now + direction;
            if (to < 0 || to >= count) return false;
            call(paged, "snapToPage", to);
            try { ((View) paged).performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); } catch (Throwable ignored) { }
            return true;
        } catch (Throwable error) { LauncherFeatures.logOnce("Folder page change unavailable", error); return false; }
    }

    // ---- drawer folder member drag --------------------------------------------------------

    /** The open drawer folder view, or null. */
    static View openFolder(Activity a) {
        try {
            ViewGroup layer = (ViewGroup) call(a, "getDragLayer");
            for (int i = layer.getChildCount() - 1; i >= 0; i--) {
                View child = layer.getChildAt(i);
                if (type("folder.Folder").isInstance(child) && DrawerFolders.isFolder(get(child, "mInfo")) && child.getVisibility() == View.VISIBLE
                        && Boolean.TRUE.equals(get(child, "mIsOpen"))) return child;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Tracks one held app that was picked up inside an open drawer folder: it stays in the folder
     * while the finger is over the folder or its border, switches pages when it rests on the
     * left or right border, and is released from the folder 1.5 s after it is completely outside.
     */
    static final class Stay {
        final View folder; final ViewGroup layer; final int border, edge;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Rect inner = new Rect(), outer = new Rect(), hit = new Rect();   // reused on every finger move
        volatile boolean left; private Runnable leave; private Runnable turn; private int dir;
        Stay(View folder, ViewGroup layer) {
            this.folder = folder; this.layer = layer;
            border = LauncherFeatures.dp(folder.getContext(), 16); edge = LauncherFeatures.dp(folder.getContext(), 28);
        }
        /** Feeds the finger position (drag-layer coordinates). */
        void update(int x, int y) {
            inner.set(0, 0, folder.getWidth(), folder.getHeight()); layer.offsetDescendantRectToMyCoords(folder, inner);
            outer.set(inner); outer.inset(-border, -border);
            if (outer.contains(x, y)) {
                if (leave != null) { handler.removeCallbacks(leave); leave = null; }
                left = false;
                int d = x <= inner.left + edge ? -1 : x >= inner.right - edge ? 1 : 0;
                setEdge(d);
            } else {
                setEdge(0);
                if (!left && leave == null) {
                    leave = new Runnable() { public void run() { leave = null; left = true; } };
                    handler.postDelayed(leave, EXIT_MS);
                }
            }
        }
        private void setEdge(int d) {
            if (d == dir) return;
            if (turn != null) { handler.removeCallbacks(turn); turn = null; }
            dir = d;
            if (d == 0) return;
            turn = new Runnable() { public void run() {
                if (dir == 0) return;
                page(get(folder, "mContent"), dir);
                handler.postDelayed(this, REPEAT_MS);
            }};
            handler.postDelayed(turn, EDGE_MS);
        }
        /** The folder's app view under the finger, or null. */
        View memberAt(int x, int y) {
            int[] base = new int[2]; layer.getLocationOnScreen(base);
            return find((View) get(folder, "mContent"), x + base[0], y + base[1]);
        }
        private View find(View v, int sx, int sy) {
            if (v.getVisibility() != View.VISIBLE) return null;
            Rect r = hit; if (!v.getGlobalVisibleRect(r) || !r.contains(sx, sy)) return null;
            Object tag = v.getTag();
            if (tag != null && LauncherFeatures.identity(tag) != null) return v;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = g.getChildCount() - 1; i >= 0; i--) { View hit = find(g.getChildAt(i), sx, sy); if (hit != null) return hit; }
            }
            return null;
        }
        void stop() {
            handler.removeCallbacks(leave == null ? NONE : leave); handler.removeCallbacks(turn == null ? NONE : turn);
            leave = null; turn = null; dir = 0;
        }
    }
    private static final Runnable NONE = new Runnable() { public void run() { } };
}
