package io.github.pixelsuite;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class DoodleOverlay extends FrameLayout {
    static final int[] COLORS = {0xffec5f59, 0xfff6c644, 0xff49c562, 0xff2a84c8,
            0xffdf7630, 0xffa1322d, 0xff34c8bc, 0xff0f558a, 0xffa657c0, 0xffec5fbb, 0xfffafafa, 0xff000000};
    static final String[] COLOR_NAMES = {"Red", "Yellow", "Green", "Blue",
            "Orange", "Dark red", "Turquoise", "Dark blue", "Purple", "Pink", "White", "Black"};
    static final Map<Object, List<Doodle>> models = new WeakHashMap<Object, List<Doodle>>();
    static WeakReference<DoodleOverlay> current = new WeakReference<DoodleOverlay>(null);

    static int sKind = Doodle.ARROW, sColor = COLORS[0];
    static float sWidthDp = 5;
    static boolean sFill;
    static float sPanelShift;

    interface Host {
        Object key();
        Bridge.Space space(View overlay);
        void picture();
        void selected(boolean on);
        void history();
        boolean drawsShapes();
        void outside(float rawX, float rawY);
        RectF[] slots(View overlay);
        boolean fixed();
        boolean editorTouch(Bridge.Space s, float x, float y);
    }

    static final class StudioHost implements Host {
        final View canvas; final Object renderer;
        StudioHost(View canvas) throws Exception { this.canvas = canvas; renderer = Bridge.renderer(canvas); }
        public Object key() { return renderer; }
        public Bridge.Space space(View overlay) { return Bridge.space(canvas, overlay); }
        public void picture() { canvas.invalidate(); }
        public void selected(boolean on) { ScreenshotEditor.setSelected(on); }
        public void history() { ScreenshotEditor.doodleHistoryChanged(); }
        public boolean drawsShapes() { return true; }
        public void outside(float rawX, float rawY) { }
        public RectF[] slots(View overlay) { return null; }
        public boolean fixed() { return false; }
        public boolean editorTouch(Bridge.Space s, float x, float y) { return false; }
    }

    static Host lastHost;

    final Activity activity;
    final Host host;
    final Object renderer;
    final List<Doodle> shapes;
    Doodle selected;
    final Layer layer;
    final FrameLayout panel;
    final float density;
    int surface;
    final int onSurface, onSurfaceVariant, accent, onAccent, outline;
    boolean superDark;
    float toolbarTopRaw;
    Object backCallback;
    final List<ImageView> shapeButtons = new ArrayList<ImageView>(), colorButtons = new ArrayList<ImageView>();
    ImageView fillButton, trashButton, doneButton;
    SeekBar size;

    static boolean active() {
        DoodleOverlay o = current.get();
        return o != null && o.isAttachedToWindow();
    }

    static boolean studioCanvasGone() {
        DoodleOverlay o = current.get();
        return o != null && o.host instanceof StudioHost && !((StudioHost) o.host).canvas.isAttachedToWindow();
    }

    static void redraw() { DoodleOverlay o = current.get(); if (o != null && o.isAttachedToWindow()) o.layer.invalidate(); }

    static void toggle() {
        if (active()) leave(true); else enter();
    }

    static void enter() {
        Activity a = ScreenshotEditor.activity();
        View canvas = a == null ? null : ScreenshotEditor.canvasFor(a);
        if (a == null || canvas == null) { ScreenshotEditor.logOnce("doodles: editor canvas not found", null); return; }
        try {
            ScreenshotEditor.closeNativeTool();
            open(a, new StudioHost(canvas), ScreenshotEditor.lastDownRawY);
        } catch (Throwable error) {
            ScreenshotEditor.logOnce("doodles could not open", error);
        }
    }

    static DoodleOverlay open(Activity a, Host host, float toolbarRawY) throws Exception {
        sKind = Doodle.ARROW;
        DoodleOverlay old = current.get();
        if (old != null) { old.registerBack(false); if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old); }
        DoodleOverlay o = new DoodleOverlay(a, host, toolbarRawY);
        ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
        content.addView(o, new ViewGroup.LayoutParams(-1, -1));
        current = new WeakReference<DoodleOverlay>(o);
        o.registerBack(true);
        host.selected(true);
        if (host.fixed()) o.motion(true, null);
        return o;
    }

    static void leave(boolean fromButton) {
        final DoodleOverlay o = current.get();
        current = new WeakReference<DoodleOverlay>(null);
        if (o != null) o.host.selected(false); else if (lastHost != null) lastHost.selected(false);
        if (o == null) return;
        o.selected = null;
        o.refresh();
        o.registerBack(false);
        o.setEnabled(false);
        final Runnable gone = new Runnable() { public void run() { if (o.getParent() instanceof ViewGroup) ((ViewGroup) o.getParent()).removeView(o); } };
        if (o.host.fixed()) { o.leaving = true; o.layer.invalidate(); o.motion(false, gone); return; }
        o.animate().alpha(0f).setDuration(140).withEndAction(gone).start();
    }

    static List<Doodle> snapshot(Object renderer) {
        synchronized (models) {
            List<Doodle> list = models.get(renderer);
            if (list == null || list.isEmpty()) return java.util.Collections.emptyList();
            List<Doodle> copy = new ArrayList<Doodle>(list.size());
            for (Doodle d : list) copy.add(d.copy());
            return copy;
        }
    }

    static boolean hasShapes(Object renderer) {
        synchronized (models) { List<Doodle> l = models.get(renderer); return l != null && !l.isEmpty(); }
    }

    static List<Doodle> model(Object renderer) {
        synchronized (models) {
            List<Doodle> list = models.get(renderer);
            if (list == null) { list = new ArrayList<Doodle>(); models.put(renderer, list); }
            return list;
        }
    }

    static final class History {
        final ArrayList<List<Doodle>> undo = new ArrayList<List<Doodle>>(), redo = new ArrayList<List<Doodle>>();
    }
    static final Map<Object, History> histories = new WeakHashMap<Object, History>();
    static WeakReference<Object> lastRenderer = new WeakReference<Object>(null);

    static void forget(Object key) {
        synchronized (models) { models.remove(key); histories.remove(key); }
    }

    static History history(Object renderer) {
        synchronized (models) {
            History h = histories.get(renderer);
            if (h == null) { h = new History(); histories.put(renderer, h); }
            return h;
        }
    }

    static List<Doodle> copies(List<Doodle> list) {
        List<Doodle> copy = new ArrayList<Doodle>(list.size());
        for (Doodle d : list) copy.add(d.copy());
        return copy;
    }

    static void checkpoint(Object renderer) {
        if (renderer == null) return;
        synchronized (models) {
            History h = history(renderer);
            h.undo.add(copies(model(renderer)));
            if (h.undo.size() > 100) h.undo.remove(0);
            h.redo.clear();
        }
        if (lastHost != null) lastHost.history();
    }

    static boolean canUndo() {
        Object r = lastRenderer.get();
        if (r == null) return false;
        synchronized (models) { return !history(r).undo.isEmpty(); }
    }
    static boolean canRedo() {
        Object r = lastRenderer.get();
        if (r == null) return false;
        synchronized (models) { return !history(r).redo.isEmpty(); }
    }
    static boolean hasShapes() {
        Object r = lastRenderer.get();
        return r != null && hasShapes(r);
    }

    static boolean undo() { return step(true); }
    static boolean redo() { return step(false); }

    private static boolean step(boolean undo) {
        final Object r = lastRenderer.get();
        if (r == null) return false;
        synchronized (models) {
            History h = history(r);
            ArrayList<List<Doodle>> from = undo ? h.undo : h.redo, to = undo ? h.redo : h.undo;
            if (from.isEmpty()) return false;
            List<Doodle> target = from.remove(from.size() - 1);
            List<Doodle> list = model(r);
            to.add(copies(list));
            list.clear();
            list.addAll(target);
        }
        DoodleOverlay o = current.get();
        if (o != null && o.isAttachedToWindow() && o.renderer == r) { o.selected = null; o.refresh(); o.syncPanel(); }
        else if (lastHost != null) lastHost.picture();
        if (lastHost != null) lastHost.history();
        return true;
    }

    void checkpoint() { checkpoint(renderer); }

    static int system(Context c, String name, int fallback) {
        int id = c.getResources().getIdentifier(name, "color", "android");
        try { return id == 0 ? fallback : c.getResources().getColor(id, c.getTheme()); } catch (Throwable ignored) { return fallback; }
    }
    static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    DoodleOverlay(Activity a, Host host, float toolbarRawY) throws Exception {
        super(a);
        activity = a;
        this.host = host;
        lastHost = host;
        renderer = host.key();
        lastRenderer = new WeakReference<Object>(renderer);
        shapes = model(renderer);
        density = a.getResources().getDisplayMetrics().density;
        boolean dark = dark(a) || host.fixed();
        surface = system(a, dark ? "system_surface_container_high_dark" : "system_surface_container_high_light", dark ? 0xff2b2930 : 0xffece6f0);
        superDark = host.fixed() || (dark && SuperDark.inEffect(a));
        if (superDark) surface = 0xff000000;
        else if (dark) surface = ComposeFrost.toolbarColour != 0 ? ComposeFrost.toolbarColour : 0xff1f1f21;
        onSurface = system(a, dark ? "system_on_surface_dark" : "system_on_surface_light", dark ? 0xffe6e0e9 : 0xff1d1b20);
        onSurfaceVariant = system(a, dark ? "system_on_surface_variant_dark" : "system_on_surface_variant_light", dark ? 0xffcac4d0 : 0xff49454f);
        accent = system(a, dark ? "system_accent1_200" : "system_accent1_600", dark ? 0xffd0bcff : 0xff6750a4);
        onAccent = system(a, dark ? "system_accent1_800" : "system_accent1_0", dark ? 0xff381e72 : 0xffffffff);
        outline = system(a, dark ? "system_outline_variant_dark" : "system_outline_variant_light", dark ? 0xff49454f : 0xffcac4d0);
        toolbarTopRaw = toolbarRawY > 0 ? toolbarRawY - dp(36) : -1;
        layer = new Layer(a);
        addView(layer, new LayoutParams(-1, -1));
        panel = buildPanel(a);
        LayoutParams lp = new LayoutParams(compactWidth(), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(140);
        addView(panel, lp);
        setClipChildren(false);
    }

    int dp(float v) { return Math.round(v * density); }
    static int dpOf(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    @Override public boolean dispatchTouchEvent(MotionEvent e) { return isEnabled() && super.dispatchTouchEvent(e); }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (toolbarTopRaw > 0) {
            int[] at = new int[2]; getLocationOnScreen(at);
            int margin = Math.max(dp(8), Math.round((b - t) - (toolbarTopRaw - at[1]) + dp(8)));
            LayoutParams lp = (LayoutParams) panel.getLayoutParams();
            if (lp.bottomMargin != margin) { lp.bottomMargin = margin; panel.setLayoutParams(lp); }
        }
        super.onLayout(changed, l, t, r, b);
        if (box != null && progress > 0f) {
            android.view.ViewGroup.LayoutParams lp = panel.getLayoutParams();
            int w = widthAt(progress);
            if (lp.width != w) post(new Runnable() { public void run() { applyProgress(); } });
        }
        placePanel();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        registerBack(false);
        if (current.get() == this) { current = new WeakReference<DoodleOverlay>(null); try { host.selected(false); } catch (Throwable ignored) { } }
    }

    void registerBack(boolean on) {
        try {
            Object dispatcher = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(activity);
            Class<?> dispatcherType = Class.forName("android.window.OnBackInvokedDispatcher");
            Class<?> callbackType = Class.forName("android.window.OnBackInvokedCallback");
            if (on) {
                backCallback = java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("onBackInvoked".equals(m.getName())) {
                            if (current.get() == DoodleOverlay.this && isAttachedToWindow() && isShown() && getAlpha() > 0.05f) {
                                boolean editorGone = host instanceof StudioHost && !((StudioHost) host).canvas.isAttachedToWindow();
                                leave(false);
                                if (editorGone) try { activity.onBackPressed(); } catch (Throwable ignored) { }
                            }
                            else {
                                registerBack(false);
                                if (current.get() == DoodleOverlay.this) leave(false);
                                try { activity.onBackPressed(); } catch (Throwable ignored) { }
                            }
                        }
                        else if ("equals".equals(m.getName())) return proxy == args[0];
                        else if ("hashCode".equals(m.getName())) return System.identityHashCode(proxy);
                        return null;
                    }
                });
                dispatcherType.getMethod("registerOnBackInvokedCallback", int.class, callbackType).invoke(dispatcher, 1000000, backCallback);
            } else if (backCallback != null) {
                dispatcherType.getMethod("unregisterOnBackInvokedCallback", callbackType).invoke(dispatcher, backCallback);
                backCallback = null;
            }
        } catch (Throwable error) {
            ScreenshotEditor.logOnce("doodles back gesture not registered", error);
        }
    }

    void refresh() {
        host.picture();
        layer.invalidate();
    }

    void applyToSelected() {
        applyToSelected(true);
    }

    void applyToSelected(boolean undoable) {
        if (selected == null) return;
        if (undoable) checkpoint();
        synchronized (models) {
            selected.color = sColor;
            selected.width = widthInImage();
            if (selected.fillable()) selected.fill = sFill;
        }
        refresh();
    }

    float widthInImage() {
        Bridge.Space s = host.space(this);
        return sWidthDp * density / Math.max(0.0001f, s == null ? 1 : s.scale);
    }

    final class Layer extends View {
        static final int NONE = 0, PENDING = 1, CREATE = 2, MOVE = 3, HANDLE = 4, PINCH = 5;
        int mode = NONE, handle = -1;
        float downX, downY, lastX, lastY;
        boolean hadSelection, saved;
        float pinchStart;
        float[] pinchMid = new float[2];
        Doodle pinchFrom;
        final int slop;
        Doodle free;
        boolean snapX, snapY, snapLevel, snapUpright;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final DashPathEffect guideDash = new DashPathEffect(new float[]{dp(4), dp(4)}, 0), outlineDash = new DashPathEffect(new float[]{dp(6), dp(4)}, 0);

        Layer(Context c) { super(c); slop = ViewConfiguration.get(c).getScaledTouchSlop(); }

        float[] screen(Doodle d, Bridge.Space s) {
            float[] pts = {d.x1, d.y1, d.x2, d.y2};
            s.toScreen.mapPoints(pts);
            return pts;
        }

        float[] image(Bridge.Space s, float x, float y) {
            float[] p = {x, y};
            s.toImage.mapPoints(p);
            return p;
        }

        boolean ours(Bridge.Space s, float x, float y) {
            int[] me = new int[2]; getLocationOnScreen(me);
            if (grabberNear(x + me[0], y + me[1]) != null) return true;
            if (selected != null && Doodle.handleAt(selected.isLine(), screen(selected, s), x, y, dp(26)) >= 0) return true;
            RectF area = new RectF(s.image.left - dp(24), s.image.top - dp(8), s.image.right + dp(24), s.image.bottom + dp(24));
            if (toolbarTopRaw > 0) area.bottom = Math.min(area.bottom, toolbarTopRaw - me[1]);
            return area.contains(x, y);
        }

        Doodle hit(Bridge.Space s, float x, float y) {
            for (int i = shapes.size() - 1; i >= 0; i--) {
                Doodle d = shapes.get(i);
                float slopPx = Math.max(dp(18), d.width * s.scale / 2 + dp(8));
                if (Doodle.hits(d.isLine(), screen(d, s), x, y, slopPx)) return d;
            }
            return null;
        }

        View grabbing;

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) grabbing = grabberNear(e.getRawX(), e.getRawY());
            if (grabbing != null) {
                View pill = grabbing;
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) grabbing = null;
                return grab(pill, e);
            }
            Bridge.Space s = host.space(DoodleOverlay.this);
            if (s == null) { if (e.getActionMasked() == MotionEvent.ACTION_DOWN) host.outside(e.getRawX(), e.getRawY()); return false; }
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (!ours(s, x, y)) { host.outside(e.getRawX(), e.getRawY()); return false; }
                    downX = lastX = x; downY = lastY = y;
                    hadSelection = selected != null;
                    saved = false; free = null; snapX = snapY = snapLevel = snapUpright = false;
                    handle = selected == null ? -1 : Doodle.handleAt(selected.isLine(), screen(selected, s), x, y, dp(26));
                    if (handle >= 0) { mode = HANDLE; return true; }
                    Doodle d = hit(s, x, y);
                    if (d != null) {
                        if (d != selected) { selected = d; syncPanel(); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                        mode = MOVE; invalidate(); return true;
                    }
                    if (host.editorTouch(s, x, y)) return false;
                    mode = PENDING;
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (selected != null && e.getPointerCount() == 2) {
                        mode = PINCH;
                        pinchStart = spacing(e);
                        pinchMid = image(s, (e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2);
                        pinchFrom = selected.copy();
                        if (!saved) { checkpoint(); saved = true; }
                        dim(true);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (mode == PENDING && Math.hypot(x - downX, y - downY) > slop) {
                        float[] a = image(s, downX, downY), b = image(s, x, y);
                        Doodle d = new Doodle(sKind, a[0], a[1], b[0], b[1], sColor, widthInImage(), sFill && !Doodle.isLine(sKind));
                        checkpoint(); saved = true;
                        synchronized (models) { shapes.add(d); }
                        selected = d; syncPanel();
                        mode = CREATE; dim(true);
                    }
                    if ((mode == MOVE || mode == HANDLE) && !saved && Math.hypot(x - downX, y - downY) > slop / 2f) { checkpoint(); saved = true; dim(true); }
                    if (mode == CREATE) {
                        float[] b = selected.isLine() ? level(s, selected.x1, selected.y1, x, y) : image(s, x, y);
                        synchronized (models) { selected.x2 = b[0]; selected.y2 = b[1]; }
                    } else if (mode == MOVE && selected != null && saved) {
                        float[] a = image(s, lastX, lastY), b = image(s, x, y);
                        if (free == null) free = selected.copy();
                        free.offset(b[0] - a[0], b[1] - a[1]);
                        float[] snap = centre(s, free);
                        synchronized (models) { selected.x1 = free.x1 + snap[0]; selected.y1 = free.y1 + snap[1]; selected.x2 = free.x2 + snap[0]; selected.y2 = free.y2 + snap[1]; }
                    } else if (mode == HANDLE && selected != null && saved) {
                        if (selected.isLine()) {
                            float[] b = handle == 0 ? level(s, selected.x2, selected.y2, x, y) : level(s, selected.x1, selected.y1, x, y);
                            synchronized (models) { if (handle == 0) { selected.x1 = b[0]; selected.y1 = b[1]; } else { selected.x2 = b[0]; selected.y2 = b[1]; } }
                        } else {
                            float[] box = Doodle.dragBoxHandle(screen(selected, s), handle, x, y);
                            s.toImage.mapPoints(box);
                            synchronized (models) { selected.x1 = box[0]; selected.y1 = box[1]; selected.x2 = box[2]; selected.y2 = box[3]; }
                            handle = Doodle.handleAt(false, screen(selected, s), x, y, Float.MAX_VALUE);
                        }
                    } else if (mode == PINCH && selected != null && e.getPointerCount() >= 2 && pinchStart > 0) {
                        float factor = spacing(e) / pinchStart;
                        float[] mid = image(s, (e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2);
                        synchronized (models) {
                            selected.x1 = pinchFrom.x1; selected.y1 = pinchFrom.y1; selected.x2 = pinchFrom.x2; selected.y2 = pinchFrom.y2;
                            selected.scale(factor, pinchMid[0], pinchMid[1]);
                            selected.offset(mid[0] - pinchMid[0], mid[1] - pinchMid[1]);
                        }
                    }
                    lastX = x; lastY = y;
                    if (mode == PENDING || mode == NONE || ((mode == MOVE || mode == HANDLE) && !saved)) return true;
                    refresh();
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (mode == PINCH) mode = NONE;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (mode == PENDING) {
                        if (hadSelection) {
                            selected = null;
                        } else {
                            float half = dp(Doodle.isLine(sKind) ? 64 : 56), tall = Doodle.isLine(sKind) ? 0 : (sKind == Doodle.RECT || sKind == Doodle.BUBBLE ? half * 0.75f : half);
                            float[] a = image(s, x - half, y - tall), b = image(s, x + half, y + tall);
                            Doodle d = new Doodle(sKind, a[0], a[1], b[0], b[1], sColor, widthInImage(), sFill && !Doodle.isLine(sKind));
                            checkpoint();
                            synchronized (models) { shapes.add(d); }
                            selected = d;
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        syncPanel();
                    }
                    mode = NONE; dim(false); free = null; snapX = snapY = snapLevel = snapUpright = false;
                    refresh();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mode = NONE; dim(false); free = null; snapX = snapY = snapLevel = snapUpright = false; refresh();
                    return true;
            }
            return true;
        }

        float[] centre(Bridge.Space s, Doodle d) {
            float[] c = {(d.x1 + d.x2) / 2f, (d.y1 + d.y2) / 2f};
            s.toScreen.mapPoints(c);
            float cx = s.image.centerX(), cy = s.image.centerY(), near = dp(10);
            boolean x = Math.abs(c[0] - cx) < near, y = Math.abs(c[1] - cy) < near;
            if ((x && !snapX) || (y && !snapY)) tick();
            snapX = x; snapY = y;
            float[] to = {x ? cx : c[0], y ? cy : c[1]}, from = {c[0], c[1]};
            s.toImage.mapPoints(to); s.toImage.mapPoints(from);
            return new float[]{to[0] - from[0], to[1] - from[1]};
        }

        float[] level(Bridge.Space s, float fixedX, float fixedY, float x, float y) {
            float[] f = {fixedX, fixedY}; s.toScreen.mapPoints(f);
            float dx = x - f[0], dy = y - f[1];
            double angle = Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));
            boolean far = Math.hypot(dx, dy) > dp(24);
            boolean lv = far && angle < 6, up = far && angle > 84;
            if ((lv && !snapLevel) || (up && !snapUpright)) tick();
            snapLevel = lv; snapUpright = up;
            if (lv) y = f[1]; else if (up) x = f[0];
            return image(s, x, y);
        }

        void tick() { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }

        float spacing(MotionEvent e) {
            return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
        }

        float chrome = 1f;
        android.animation.ValueAnimator chromeAnim;

        boolean dragging() { return saved && (mode == MOVE || mode == HANDLE || mode == PINCH || mode == CREATE); }

        void showChrome(boolean on) {
            float to = on ? 1f : 0f;
            if (chromeAnim != null) chromeAnim.cancel();
            if (!on) { chrome = 0f; invalidate(); return; }
            if (chrome == to) return;
            chromeAnim = android.animation.ValueAnimator.ofFloat(chrome, to).setDuration(160);
            chromeAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(android.animation.ValueAnimator a) { chrome = (Float) a.getAnimatedValue(); invalidate(); }
            });
            chromeAnim.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            Bridge.Space s = null; boolean placed = false;
            if (!host.drawsShapes() && !leaving) { s = host.space(DoodleOverlay.this); placed = true; drawShapes(canvas, s, shapes); }
            Doodle d = selected;
            if (d == null) return;
            if (mode == MOVE && (snapX || snapY)) {
                if (!placed) { s = host.space(DoodleOverlay.this); placed = true; }
                if (s != null) {
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(accent); paint.setAlpha(0xb0);
                    paint.setPathEffect(guideDash);
                    if (snapX) canvas.drawLine(s.image.centerX(), s.image.top, s.image.centerX(), s.image.bottom, paint);
                    if (snapY) canvas.drawLine(s.image.left, s.image.centerY(), s.image.right, s.image.centerY(), paint);
                    paint.setPathEffect(null);
                }
            }
            if (dragging()) { if (chrome != 0f) showChrome(false); return; }
            if (chrome <= 0.01f && (chromeAnim == null || !chromeAnim.isRunning())) { showChrome(true); return; }
            int alpha = Math.round(255 * chrome);
            if (!placed) s = host.space(DoodleOverlay.this);
            if (s == null) return;
            float[] p = screen(d, s);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(0xccffffff);
            paint.setAlpha(Math.round(0xcc * chrome));
            paint.setPathEffect(outlineDash);
            if (d.isLine()) canvas.drawLine(p[0], p[1], p[2], p[3], paint);
            else canvas.drawRect(Math.min(p[0], p[2]), Math.min(p[1], p[3]), Math.max(p[0], p[2]), Math.max(p[1], p[3]), paint);
            paint.setPathEffect(null);
            float[] h = Doodle.handlePoints(d.isLine(), p);
            for (int i = 0; i < h.length / 2; i++) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x33000000);
                paint.setAlpha(Math.round(0x33 * chrome));
                canvas.drawCircle(h[i * 2], h[i * 2 + 1] + dp(1), dp(7.5f), paint);
                paint.setColor(0xffffffff);
                paint.setAlpha(alpha);
                canvas.drawCircle(h[i * 2], h[i * 2 + 1], dp(7), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(accent);
                paint.setAlpha(Math.round(Color.alpha(accent) * chrome));
                canvas.drawCircle(h[i * 2], h[i * 2 + 1], dp(5f), paint);
            }
        }
    }

    private static final Paint SHAPE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    static void drawShapes(Canvas canvas, Bridge.Space s, List<Doodle> list) {
        if (s == null || list == null || list.isEmpty()) return;
        List<Doodle> copy;
        synchronized (models) { copy = copies(list); }
        int save = canvas.save();
        canvas.clipRect(s.image);
        canvas.concat(s.toScreen);
        for (Doodle d : copy) d.draw(canvas, SHAPE_PAINT);
        canvas.restoreToCount(save);
    }

    void dim(boolean busy) {
        panel.animate().alpha(busy ? 0f : 1f).setDuration(busy ? 80 : 160).start();
    }

    static final int[] SHAPE_ORDER = {Doodle.ARROW, Doodle.LINE, Doodle.RECT, Doodle.OVAL, Doodle.DOUBLE_ARROW, Doodle.TRIANGLE, Doodle.STAR, Doodle.BUBBLE};
    static final int SHOWN = 4;
    static boolean sExpanded;
    final List<Integer> shapeKinds = new ArrayList<Integer>();
    final List<View> extraShapes = new ArrayList<View>(), extraColors = new ArrayList<View>();
    View moreShapes, moreColors, sideGrabber;
    LinearLayout box;
    NoScroll shapeScroll, colorScroll;
    float progress;
    android.animation.ValueAnimator widthAnim;

    final class NoScroll extends HorizontalScrollView {
        boolean scrollable;
        NoScroll(Context c) { super(c); setHorizontalScrollBarEnabled(false); setOverScrollMode(OVER_SCROLL_NEVER); setFillViewport(true); }
        @Override public boolean onInterceptTouchEvent(MotionEvent e) { return scrollable && super.onInterceptTouchEvent(e); }
        @Override public boolean onTouchEvent(MotionEvent e) { return scrollable && super.onTouchEvent(e); }
    }

    int pad() { return dp(12); }
    int grab() { return host != null && host.fixed() ? 0 : dp(24); }
    int rowH() { return dp(40); }
    int screenW() { return getResources().getDisplayMetrics().widthPixels - dp(16); }
    int compactWidth() { return slotWidth > 0 ? slotWidth : Math.min(dp(302), screenW()); }
    int fullWidth() { return slotWidth > 0 ? slotWidth : screenW(); }
    float colCompact() { return (compactWidth() - 2f * pad()) / (SHOWN + 1); }
    float colFull() { return (fullWidth() - 2f * pad() - grab()) / SHAPE_ORDER.length; }
    float col(float p) { return colCompact() + (colFull() - colCompact()) * p; }
    int widthAt(float p) { return Math.round(2 * pad() + grab() * p + col(p) * (SHOWN + 1 + (SHAPE_ORDER.length - SHOWN - 1) * p)); }

    final class Dot extends Drawable {
        final int fill, ring; final float ringW;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Dot(int fill, int ring, float ringW) { this.fill = fill; this.ring = ring; this.ringW = ringW; }
        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float r = Math.min(dp(16), Math.min(b.width(), b.height()) / 2f);
            if (r <= 0) return;
            paint.setStyle(Paint.Style.FILL); paint.setColor(fill);
            if (Color.alpha(fill) > 0) canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, paint);
            if (ringW > 0) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(ringW); paint.setColor(ring);
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r - ringW / 2, paint);
            }
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class Rim extends Drawable {
        final float radius, width; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); int shaderH = -1;
        Rim(float radius, float width) {
            this.radius = radius; this.width = width;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width * 1.6f);
            paint.setMaskFilter(new android.graphics.BlurMaskFilter(width * 1.4f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        }
        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds(); if (b.isEmpty()) return;
            if (shaderH != b.height()) {
                shaderH = b.height();
                paint.setShader(new android.graphics.LinearGradient(0, b.top, 0, b.bottom,
                        new int[]{0x30ffffff, 0x12ffffff, 0x0cffffff, 0x16ffffff}, new float[]{0f, 0.35f, 0.75f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            }
            float in = width * 0.5f;
            canvas.drawRoundRect(b.left + in, b.top + in, b.right - in, b.bottom - in, radius - in, radius - in, paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    FrameLayout buildPanel(final Context c) {
        FrameLayout shell = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(dp(28));
        shell.setBackground(bg);
        if (superDark) shell.setForeground(new Rim(dp(28), dp(1.5f)));
        shell.setElevation(dp(6));
        shell.setClipToOutline(true);
        shell.setClickable(true);

        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad(), dp(2), pad(), dp(2));
        box.addView(dragHandle(c), new LinearLayout.LayoutParams(-1, dp(12)));

        shapeScroll = new NoScroll(c);
        LinearLayout shapeRow = new LinearLayout(c);
        for (int i = 0; i < SHAPE_ORDER.length; i++) {
            if (i == SHOWN) {
                moreShapes = roundButton(c, new MoreShapesIcon(onSurface, density).big(), "More shapes", new OnClickListener() { public void onClick(View v) { setExpanded(true, true); } });
                shapeRow.addView(moreShapes, new LinearLayout.LayoutParams(Math.round(colCompact()), rowH()));
            }
            final int kind = SHAPE_ORDER[i];
            ImageView b = new ImageView(c);
            b.setImageDrawable(new ShapeIcon(kind, onSurface, density).big());
            b.setContentDescription(Doodle.NAMES[kind]);
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setOnClickListener(new OnClickListener() { public void onClick(View v) {
                sKind = kind;
                if (selected != null) {
                    checkpoint();
                    synchronized (models) { selected.convertTo(kind); selected.fill = selected.fillable() && sFill; }
                    refresh();
                }
                syncPanel();
            }});
            shapeRow.addView(b, new LinearLayout.LayoutParams(Math.round(colCompact()), rowH()));
            shapeButtons.add(b); shapeKinds.add(kind);
            if (i >= SHOWN) extraShapes.add(b);
        }
        shapeScroll.addView(shapeRow);
        box.addView(shapeScroll, new LinearLayout.LayoutParams(-1, -2));

        colorScroll = new NoScroll(c);
        LinearLayout colorRow = new LinearLayout(c);
        for (int i = 0; i < COLORS.length; i++) {
            if (i == SHOWN) {
                moreColors = new View(c);
                moreColors.setBackground(new RainbowIcon(density));
                moreColors.setContentDescription("More colours");
                moreColors.setOnClickListener(new OnClickListener() { public void onClick(View v) { setExpanded(true, true); } });
                colorRow.addView(moreColors, new LinearLayout.LayoutParams(Math.round(colCompact()), rowH()));
            }
            final int color = COLORS[i];
            ImageView swatch = new ImageView(c);
            swatch.setContentDescription(COLOR_NAMES[i]);
            swatch.setOnClickListener(new OnClickListener() { public void onClick(View v) { sColor = color; applyToSelected(); syncPanel(); } });
            colorRow.addView(swatch, new LinearLayout.LayoutParams(Math.round(colCompact()), rowH()));
            colorButtons.add(swatch);
            if (i >= SHOWN) extraColors.add(swatch);
        }
        colorScroll.addView(colorRow);
        box.addView(colorScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thin = new ImageView(c);
        thin.setImageDrawable(new StrokeIcon(onSurfaceVariant, density));
        thin.setContentDescription("Thickness");
        controls.addView(thin, new LinearLayout.LayoutParams(dp(24), rowH()));
        size = new SeekBar(c);
        size.setMax(23);
        size.setProgress(Math.round(sWidthDp) - 1);
        size.setContentDescription("Thickness");
        size.setPadding(dp(8), 0, dp(8), 0);
        size.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        size.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        size.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(outline));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) { sWidthDp = value + 1; applyToSelected(false); } }
            public void onStartTrackingTouch(SeekBar bar) { if (selected != null) checkpoint(); }
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        controls.addView(size, new LinearLayout.LayoutParams(0, rowH(), 1));
        FillIcon fillIcon = new FillIcon(onSurface, density); fillIcon.scale = 1.25f;
        fillButton = roundButton(c, fillIcon, "Fill shape", new OnClickListener() { public void onClick(View v) {
            sFill = !sFill; applyToSelected(); syncPanel();
        }});
        controls.addView(fillButton, new LinearLayout.LayoutParams(dp(38), dp(38)));
        controls.addView(trashButton = roundButton(c, trashIcon(), "Delete shape", new OnClickListener() { public void onClick(View v) {
            if (selected != null || !shapes.isEmpty()) checkpoint();
            synchronized (models) {
                if (selected != null) shapes.remove(selected);
                else if (!shapes.isEmpty()) shapes.remove(shapes.size() - 1);
            }
            selected = null; refresh(); syncPanel();
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }}), new LinearLayout.LayoutParams(dp(38), dp(38)));
        CheckIcon check = new CheckIcon(onAccent, density); check.scale = 1.15f;
        doneButton = roundButton(c, check, "Done", new OnClickListener() { public void onClick(View v) { leave(false); } });
        doneButton.setBackground(new Dot(accent, 0, 0));
        controls.addView(doneButton, new LinearLayout.LayoutParams(Math.round(colCompact()), rowH()));
        box.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        box.addView(dragHandle(c), new LinearLayout.LayoutParams(-1, dp(12)));
        shell.addView(box, new FrameLayout.LayoutParams(-1, -2));

        sideGrabber = sideGrabber(c);
        shell.addView(sideGrabber, new FrameLayout.LayoutParams(pad() + grab(), dp(96), Gravity.END | Gravity.CENTER_VERTICAL));
        progress = sExpanded ? 1f : 0f;
        if (host.fixed()) {
            progress = 1f;
            box.getChildAt(0).setVisibility(GONE);
            box.getChildAt(box.getChildCount() - 1).setVisibility(GONE);
            topPill = null; bottomPill = null;
            sideGrabber.setVisibility(GONE);
            box.setPadding(pad(), dp(8), pad(), dp(8));
        }
        applyProgress();
        syncPanel();
        return shell;
    }

    private static void width(View v, int w) {
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.width != w) { lp.width = w; v.setLayoutParams(lp); }
    }

    void applyProgress() {
        float p = Math.max(0f, Math.min(1f, progress));
        android.view.ViewGroup.LayoutParams lp = panel_lp();
        int w = widthAt(p);
        if (lp != null && lp.width != w) { lp.width = w; ((View) box.getParent()).setLayoutParams(lp); }
        float c = col(p);
        distribute((ViewGroup) shapeScroll.getChildAt(0), c, p);
        distribute((ViewGroup) colorScroll.getChildAt(0), c, p);
        if (doneButton != null) width(doneButton, Math.round(c));
        if (trashButton != null && trashButton.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams t = (LinearLayout.LayoutParams) trashButton.getLayoutParams();
            int m = Math.max(0, Math.round((c - dp(38)) / 2f));
            if (t.rightMargin != m) { t.rightMargin = m; trashButton.setLayoutParams(t); }
        }
        for (View more : new View[]{moreShapes, moreColors}) {
            if (more == null) continue;
            more.setEnabled(p < 0.5f);
            float fold = Math.min(1f, p * 1.4f);
            more.setAlpha(fold >= 1f ? 0f : 1f);
            more.setScaleX(1 - fold); more.setScaleY(1 - fold);
        }
        grow(extraShapes, p, c);
        grow(extraColors, p, c);
        sideGrabber.setAlpha(Math.max(0f, (p - 0.5f) * 2f));
        sideGrabber.setTranslationX((1 - p) * dp(16));
        if (host.fixed()) { sideGrabber.setVisibility(GONE); colorScroll.scrollable = true; return; }
        sideGrabber.setVisibility(p > 0.01f ? VISIBLE : INVISIBLE);
        box.setPadding(pad(), dp(2), pad() + Math.round(grab() * p), dp(2));
        colorScroll.scrollable = p >= 1f;
        if (p <= 0f) colorScroll.scrollTo(0, 0);
    }

    private void distribute(ViewGroup row, float c, float p) {
        if (row == null) return;
        float x = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            float w = (v == moreShapes || v == moreColors) ? c * (1 - p) : (extraShapes.contains(v) || extraColors.contains(v)) ? c * p : c;
            width(v, Math.round(x + w) - Math.round(x));
            x += w;
        }
    }

    private void grow(List<View> views, float p, float c) {
        int w = Math.round(c * p);
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            float start = Math.min(i, 7) * 0.05f, local = Math.max(0f, Math.min(1f, (p - 0.2f - start) / 0.45f));
            v.setAlpha(w > dp(2) ? 1f : 0f);
            float sc = 0.5f + 0.5f * local;
            v.setScaleX(sc); v.setScaleY(sc);
            v.setEnabled(local > 0.5f);
        }
    }

    private android.view.ViewGroup.LayoutParams panel_lp() {
        View shell = (View) box.getParent();
        return shell == null ? null : shell.getLayoutParams();
    }

    void setExpanded(boolean open, boolean animate) {
        sExpanded = open;
        if (widthAnim != null) widthAnim.cancel();
        float to = open ? 1f : 0f;
        if (!animate) { progress = to; applyProgress(); return; }
        widthAnim = android.animation.ValueAnimator.ofFloat(progress, to);
        widthAnim.setDuration(open ? 380 : 300);
        widthAnim.setInterpolator(open ? new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                                       : new android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f));
        widthAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) { progress = (Float) a.getAnimatedValue(); applyProgress(); }
        });
        widthAnim.start();
    }

    View sideGrabber(Context c) {
        final View g = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path arrow = new Path();
            @Override protected void onDraw(Canvas canvas) {
                float cx = getWidth() - dp(12), cy = getHeight() / 2f, h = dp(isPressed() ? 36 : 30), w = dp(4);
                p.setStyle(Paint.Style.FILL);
                p.setColor(onSurfaceVariant); p.setAlpha(isPressed() ? 255 : 150);
                canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, w / 2, w / 2, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
                float ax = cx - dp(9), s = dp(3.5f);
                arrow.rewind();
                arrow.moveTo(ax + s * 0.6f, cy - s); arrow.lineTo(ax - s * 0.4f, cy); arrow.lineTo(ax + s * 0.6f, cy + s);
                canvas.drawPath(arrow, p);
            }
        };
        g.setContentDescription("Make Doodles menu smaller");
        g.setOnTouchListener(new View.OnTouchListener() {
            float startX, startP; android.view.VelocityTracker vt; boolean moved;
            public boolean onTouch(View v, MotionEvent e) {
                if (progress <= 0.01f && e.getActionMasked() == MotionEvent.ACTION_DOWN) return false;
                if (vt != null) { MotionEvent r = MotionEvent.obtain(e); r.setLocation(e.getRawX(), e.getRawY()); vt.addMovement(r); r.recycle(); }
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        if (widthAnim != null) widthAnim.cancel();
                        startX = e.getRawX(); startP = progress; moved = false;
                        if (vt != null) vt.recycle();
                        vt = android.view.VelocityTracker.obtain();
                        MotionEvent r = MotionEvent.obtain(e); r.setLocation(e.getRawX(), e.getRawY()); vt.addMovement(r); r.recycle();
                        v.setPressed(true); v.invalidate();
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - startX;
                        if (Math.abs(dx) > dp(4)) moved = true;
                        float span = Math.max(1, (fullWidth() - compactWidth()) / 2f);
                        progress = Math.max(0f, Math.min(1f, startP + dx / span));
                        applyProgress();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        v.setPressed(false); v.invalidate();
                        float vx = 0;
                        if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                        boolean close = !moved || vx < -dp(300) || (vx < dp(300) && progress < 0.7f);
                        if (close) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        setExpanded(!close, true);
                        return true;
                    }
                }
                return true;
            }
        });
        return g;
    }

    View dragHandle(Context c) {
        final View pill = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                p.setColor(onSurfaceVariant);
                p.setAlpha(isPressed() ? 255 : 150);
                float w = dp(isPressed() ? 48 : 40), h = dp(5), cx = getWidth() / 2f, cy = getHeight() / 2f;
                canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, p);
            }
        };
        pill.setContentDescription("Move Doodles menu");
        pill.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && Math.abs(e.getX() - v.getWidth() / 2f) > dp(110)) return false;
                return grab(v, e);
            }
        });
        if (topPill == null) topPill = pill; else bottomPill = pill;
        return pill;
    }

    View topPill, bottomPill;
    float grabStartRaw, grabStartShift;
    android.view.VelocityTracker grabVelocity;

    boolean grab(View pill, MotionEvent e) {
        if (grabVelocity != null) {
            MotionEvent raw = MotionEvent.obtain(e);
            raw.setLocation(e.getRawX(), e.getRawY());
            grabVelocity.addMovement(raw);
            raw.recycle();
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                stopFling();
                grabStartRaw = e.getRawY(); grabStartShift = sPanelShift; grabStartT = sSlotT;
                if (slotAnim != null) slotAnim.cancel();
                if (grabVelocity != null) grabVelocity.recycle();
                grabVelocity = android.view.VelocityTracker.obtain();
                MotionEvent raw = MotionEvent.obtain(e); raw.setLocation(e.getRawX(), e.getRawY()); grabVelocity.addMovement(raw); raw.recycle();
                pill.setPressed(true); pill.invalidate();
                pill.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (pill.getParent() != null) pill.getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                RectF[] s = slots();
                if (s != null) {
                    float span = s[0].centerY() - s[1].centerY();
                    if (span != 0) applySlots(grabStartT - (e.getRawY() - grabStartRaw) / span);
                    return true;
                }
                sPanelShift = grabStartShift + (e.getRawY() - grabStartRaw);
                placePanel();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                pill.setPressed(false); pill.invalidate();
                float vy = 0;
                if (grabVelocity != null) {
                    grabVelocity.computeCurrentVelocity(1000);
                    vy = grabVelocity.getYVelocity();
                    grabVelocity.recycle(); grabVelocity = null;
                }
                int fling = ViewConfiguration.get(pill.getContext()).getScaledMinimumFlingVelocity();
                if (slots() != null) {
                    boolean flick = e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(vy) > Math.max(fling * 4, dp(500));
                    settleSlot(flick ? (vy < 0 ? 1f : 0f) : (sSlotT >= 0.5f ? 1f : 0f));
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(vy) > Math.max(fling * 4, dp(500))) flingPanel(vy);
                return true;
            }
        }
        return true;
    }

    View grabberNear(float rawX, float rawY) {
        if (panel.getAlpha() < 0.5f || topPill == null) return null;
        int[] at = new int[2]; panel.getLocationOnScreen(at);
        float top = at[1], bottom = at[1] + panel.getHeight() * panel.getScaleY(), cx = at[0] + panel.getWidth() * panel.getScaleX() / 2f;
        if (Math.abs(rawX - cx) > dp(110)) return null;
        if (rawY >= top - dp(24) && rawY < top) return topPill;
        if (bottomPill != null && rawY > bottom && rawY <= bottom + dp(24)) return bottomPill;
        return null;
    }

    android.animation.ValueAnimator flight;

    void stopFling() { if (flight != null) { flight.cancel(); flight = null; } }

    void flingPanel(float vy) {
        if (getHeight() <= 0 || panel.getHeight() <= 0) return;
        float min = topLimit() - panel.getTop(), max = Math.max(min, bottomLimit() - panel.getTop() - panel.getHeight());
        final float from = sPanelShift, edge = vy < 0 ? min : max;
        final float distance = Math.abs(edge - from);
        final float speed = Math.max(Math.abs(vy), dp(1400));
        final float travel = distance < 1 ? 0 : Math.max(90, Math.min(320, distance / speed * 1000f));
        final float amp = Math.min(dp(30), Math.max(dp(8), speed * 0.012f));
        final float bounce = 520;
        final float inward = vy < 0 ? 1 : -1;
        stopFling();
        flight = android.animation.ValueAnimator.ofFloat(0f, travel + bounce);
        flight.setDuration((long) (travel + bounce));
        flight.setInterpolator(new android.view.animation.LinearInterpolator());
        flight.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float t = (Float) a.getAnimatedValue(), y;
                if (t < travel) {
                    float f = t / travel;
                    y = from + (edge - from) * (f * (1.15f - 0.15f * f));
                } else {
                    float b = (t - travel) / bounce;
                    float arc = (float) Math.abs(Math.sin(Math.PI * 2.2 * Math.pow(b, 0.8)));
                    y = edge + inward * amp * arc * (float) Math.pow(1 - b, 2.2);
                }
                sPanelShift = y;
                panel.setTranslationY(y);
            }
        });
        flight.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { sPanelShift = edge; panel.setTranslationY(edge); }
        });
        flight.start();
        postDelayed(new Runnable() { public void run() { if (flight != null && flight.isRunning()) panel.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); } }, (long) travel);
    }

    float sTopLimit = -1, sBottomLimit = -1;
    static final float FRAME_DP = 18;
    float topLimit() {
        if (sTopLimit < 0) {
            Bridge.Space s = host.space(this);
            if (s != null && s.image.top > dp(40)) {
                sTopLimit = Math.max(dp(32), Math.min(dp(160), s.image.top - FRAME_DP * density));
                sBottomLimit = s.image.bottom + FRAME_DP * density;
            }
            if (sTopLimit < 0) return dp(110);
        }
        return sTopLimit;
    }

    float bottomLimit() {
        int nav = 0;
        try {
            android.view.WindowInsets in = getRootWindowInsets();
            if (in != null) nav = in.getStableInsetBottom();
        } catch (Throwable ignored) { }
        View root = getRootView();
        int[] at = new int[2]; getLocationInWindow(at);
        float screenBottom = (root != null && root.getHeight() > 0 ? root.getHeight() : at[1] + getHeight()) - nav - dp(4);
        float limit = Math.min(getHeight(), screenBottom - at[1]);
        topLimit();
        if (sBottomLimit > 0) limit = Math.min(limit, sBottomLimit);
        return limit;
    }

    static float sSlotT;
    int slotWidth;
    float grabStartT;
    android.animation.ValueAnimator slotAnim;

    RectF[] slots() { return host.slots(this); }

    void applySlots(float t) {
        RectF[] s = slots();
        if (s == null || panel.getHeight() <= 0) return;
        t = Math.max(0f, Math.min(1f, t));
        sSlotT = t;
        float h = panel.getHeight();
        float k = lerp(s[0].height() / h, s[1].height() / h, t);
        float w = lerp(s[0].width(), s[1].width(), t);
        int layoutW = Math.max(dp(160), Math.round(w / k));
        if (layoutW != slotWidth) { slotWidth = layoutW; applyProgress(); }
        panel.setPivotX(panel.getWidth() / 2f); panel.setPivotY(h / 2f);
        panel.setScaleX(k * enterScale); panel.setScaleY(k * enterScale);
        float cx = lerp(s[0].centerX(), s[1].centerX(), t), cy = lerp(s[0].centerY(), s[1].centerY(), t);
        panel.setTranslationX(cx - (panel.getLeft() + panel.getWidth() / 2f));
        panel.setTranslationY(cy - (panel.getTop() + h / 2f) + enterOffset);
    }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    float enterOffset, enterScale = 1f;
    boolean leaving;
    android.animation.ValueAnimator motionAnim;
    void motion(final boolean in, final Runnable end) {
        if (motionAnim != null) motionAnim.cancel();
        final float from = in ? 0f : 1f - Math.min(1f, Math.abs(enterOffset) / dp(28));
        motionAnim = android.animation.ValueAnimator.ofFloat(from, in ? 1f : 0f);
        motionAnim.setDuration(in ? 280 : 200);
        motionAnim.setInterpolator(in ? new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f) : new android.view.animation.PathInterpolator(0.3f, 0f, 0.8f, 0.15f));
        motionAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float v = (Float) a.getAnimatedValue();
                enterOffset = (1f - v) * dp(28); enterScale = 0.94f + 0.06f * v;
                panel.setAlpha(v);
                applySlots(sSlotT);
            }
        });
        motionAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { if (end != null) end.run(); }
        });
        if (in) { enterOffset = dp(28); enterScale = 0.94f; panel.setAlpha(0f); }
        motionAnim.start();
    }

    void settleSlot(float to) {
        if (slotAnim != null) slotAnim.cancel();
        slotAnim = android.animation.ValueAnimator.ofFloat(sSlotT, to);
        slotAnim.setDuration(300);
        slotAnim.setInterpolator(new android.view.animation.OvershootInterpolator(0.9f));
        slotAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float v = (Float) a.getAnimatedValue();
                applySlots(Math.max(-0.04f, Math.min(1.04f, v)));
            }
        });
        final float end = to;
        slotAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { applySlots(end); panel.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
        });
        slotAnim.start();
    }

    void placePanel() {
        if (slots() != null) { applySlots(sSlotT); return; }
        if (getHeight() <= 0 || panel.getHeight() <= 0) { panel.setTranslationY(sPanelShift); return; }
        float min = topLimit() - panel.getTop(), max = bottomLimit() - panel.getTop() - panel.getHeight();
        sPanelShift = Math.max(min, Math.min(Math.max(min, max), sPanelShift));
        panel.setTranslationY(sPanelShift);
    }

    ImageView roundButton(Context c, Drawable icon, String label, OnClickListener click) {
        ImageView b = new ImageView(c);
        b.setImageDrawable(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(label);
        b.setOnClickListener(click);
        return b;
    }

    void syncPanel() {
        if (shapeButtons.isEmpty()) return;
        Doodle d = selected;
        int kind = d != null ? d.kind : sKind;
        int color = d != null ? d.color : sColor;
        boolean fill = d != null ? d.fill : sFill;
        if (d != null) { sKind = d.kind; sColor = d.color; sFill = d.fillable() ? d.fill : sFill; }
        for (int i = 0; i < shapeButtons.size(); i++) {
            ImageView b = shapeButtons.get(i);
            boolean on = shapeKinds.get(i) == kind;
            b.setBackground(new Dot(on ? accent : Color.TRANSPARENT, 0, 0));
            ((ShapeIcon) b.getDrawable()).color = on ? onAccent : onSurface;
            b.getDrawable().invalidateSelf();
            b.setSelected(on);
        }
        for (int i = 0; i < colorButtons.size(); i++) {
            ImageView b = colorButtons.get(i);
            boolean on = COLORS[i] == color;
            b.setBackground(new Dot(COLORS[i], on ? accent : outline, on ? dp(3) : dp(1)));
            b.setSelected(on);
        }
        boolean extraKind = true; for (int i = 0; i < SHOWN; i++) if (SHAPE_ORDER[i] == kind) extraKind = false;
        boolean extraColor = true; for (int i = 0; i < SHOWN; i++) if (COLORS[i] == color) extraColor = false;
        if (moreShapes != null) {
            moreShapes.setBackground(new Dot(extraKind ? accent : Color.TRANSPARENT, 0, 0));
            Drawable icon = ((ImageView) moreShapes).getDrawable();
            if (icon instanceof Icon) { ((Icon) icon).color = extraKind ? onAccent : onSurface; icon.invalidateSelf(); }
        }
        if (moreColors != null) {
            Drawable icon = moreColors.getBackground();
            if (icon instanceof RainbowIcon) { ((RainbowIcon) icon).ring = extraColor ? accent : 0; icon.invalidateSelf(); }
        }
        GradientDrawable fillBg = new GradientDrawable(); fillBg.setShape(GradientDrawable.OVAL);
        fillBg.setColor(fill ? (accent & 0x00ffffff) | 0x40000000 : Color.TRANSPARENT);
        fillButton.setBackground(fillBg);
        ((FillIcon) fillButton.getDrawable()).filled = fill;
        fillButton.getDrawable().invalidateSelf();
        fillButton.setAlpha(Doodle.isLine(kind) ? 0.35f : 1f);
        if (d != null && size != null) {
            Bridge.Space s = host.space(this);
            if (s != null) { sWidthDp = Math.max(1, Math.min(24, d.width * s.scale / density)); size.setProgress(Math.round(sWidthDp) - 1); }
        }
    }

    abstract static class Icon extends Drawable {
        int color; final float density; float scale = 1f;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(int color, float density) { this.color = color; this.density = density; }
        Icon big() { scale = 1.15f; return this; }
        @Override public int getIntrinsicWidth() { return Math.round(24 * density * scale); }
        @Override public int getIntrinsicHeight() { return Math.round(24 * density * scale); }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            float size = 24 * density * scale;
            canvas.translate(getBounds().exactCenterX() - size / 2, getBounds().exactCenterY() - size / 2);
            canvas.scale(density * scale, density * scale);
            paint.setColor(color);
            draw24(canvas);
            canvas.restoreToCount(save);
        }
        abstract void draw24(Canvas canvas);
    }

    static final class ShapeIcon extends Icon {
        final int kind; final Path box;
        ShapeIcon(int kind, int color, float density) {
            super(color, density); this.kind = kind;
            box = Doodle.isLine(kind) ? null : Doodle.outline(kind, 4, kind == Doodle.RECT ? 5 : 4, 20, kind == Doodle.RECT ? 19 : 20, 2f);
        }
        @Override void draw24(Canvas c) {
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(2f);
            if (box == null) Doodle.drawLine(c, paint, kind, 4, 20, 20, 4, 2f);
            else { paint.setStyle(Paint.Style.STROKE); c.drawPath(box, paint); }
        }
    }

    static final class MoreShapesIcon extends Icon {
        final Path triangle = Doodle.outline(Doodle.TRIANGLE, 3, 3, 13, 12, 1.8f), star = Doodle.outline(Doodle.STAR, 12, 11, 22, 21, 1.8f);
        MoreShapesIcon(int color, float density) { super(color, density); }
        @Override void draw24(Canvas c) {
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(triangle, paint);
            c.drawPath(star, paint);
            c.drawCircle(17.5f, 6.5f, 3.5f, paint);
            c.drawRoundRect(3, 15, 9.5f, 21, 1.5f, 1.5f, paint);
        }
    }
    static final class RainbowIcon extends Drawable {
        final float density; int ring;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        android.graphics.SweepGradient sweep; float sweepX, sweepY;
        RainbowIcon(float density) { this.density = density; }
        @Override public int getIntrinsicWidth() { return Math.round(34 * density); }
        @Override public int getIntrinsicHeight() { return Math.round(34 * density); }
        @Override public void draw(Canvas canvas) {
            float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY(), r = Math.min(16 * density, Math.min(getBounds().width(), getBounds().height()) / 2f);
            if (r <= 0) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xff000000);
            if (sweep == null || sweepX != cx || sweepY != cy) {
                sweep = new android.graphics.SweepGradient(cx, cy, new int[]{0xffec5f59, 0xfff6c644, 0xff49c562, 0xff34c8bc, 0xff2a84c8, 0xffa657c0, 0xffec5fbb, 0xffec5f59}, null);
                sweepX = cx; sweepY = cy;
            }
            paint.setShader(sweep);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((ring != 0 ? 3 : 1) * density);
            paint.setColor(ring != 0 ? ring : 0x33ffffff);
            canvas.drawCircle(cx, cy, r - paint.getStrokeWidth() / 2, paint);
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    static final class StrokeIcon extends Icon {
        StrokeIcon(int color, float density) { super(color, density); }
        @Override void draw24(Canvas c) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(1.5f); c.drawLine(5, 7, 19, 7, paint);
            paint.setStrokeWidth(2.5f); c.drawLine(5, 12, 19, 12, paint);
            paint.setStrokeWidth(4f); c.drawLine(5, 17.5f, 19, 17.5f, paint);
        }
    }

    static final class FillIcon extends Icon {
        boolean filled;
        FillIcon(int color, float density) { super(color, density); }
        @Override void draw24(Canvas c) {
            paint.setStrokeWidth(2f); paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            c.drawRoundRect(4, 4, 20, 20, 3.5f, 3.5f, paint);
        }
    }

    TrashIcon trashIcon() { TrashIcon t = new TrashIcon(onSurface, density); t.scale = 1.25f; return t; }

    static final class TrashIcon extends Icon {
        final Path can = new Path();
        TrashIcon(int color, float density) {
            super(color, density);
            can.moveTo(6.5f, 6.5f); can.lineTo(7.5f, 19); can.quadTo(7.6f, 20, 8.6f, 20); can.lineTo(15.4f, 20); can.quadTo(16.4f, 20, 16.5f, 19); can.lineTo(17.5f, 6.5f);
        }
        @Override void draw24(Canvas c) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawLine(4.5f, 6.5f, 19.5f, 6.5f, paint);
            c.drawLine(9.5f, 6.5f, 10, 4, paint); c.drawLine(10, 4, 14, 4, paint); c.drawLine(14, 4, 14.5f, 6.5f, paint);
            c.drawPath(can, paint);
            c.drawLine(10.2f, 10, 10.4f, 16.5f, paint); c.drawLine(13.8f, 10, 13.6f, 16.5f, paint);
        }
    }

    static final class CheckIcon extends Icon {
        final Path check = new Path();
        CheckIcon(int color, float density) { super(color, density); check.moveTo(6f, 12.5f); check.lineTo(10, 16.5f); check.lineTo(18f, 8f); }
        @Override void draw24(Canvas c) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.2f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(check, paint);
        }
    }

    static Bitmap toolIcon(Context c) {
        float density = c == null ? 3f : c.getResources().getDisplayMetrics().density;
        int size = Math.round(24 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(density, density);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xffffffff); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawRoundRect(new RectF(3.5f, 3.5f, 10.5f, 10.5f), 1.5f, 1.5f, p);
        canvas.drawCircle(17, 7, 3.5f, p);
        canvas.drawLine(4, 19.5f, 18.6f, 19.5f, p);
        Path head = new Path(); head.moveTo(16.5f, 16.5f); head.lineTo(19.5f, 19.5f); head.lineTo(16.5f, 22.5f);
        canvas.drawPath(head, p);
        return bitmap;
    }

    static final class Bridge {
        static final class Space {
            final Matrix toScreen = new Matrix(), toImage = new Matrix();
            final RectF image = new RectF();
            float scale = 1;
        }
        static java.lang.reflect.Field viewScene, sceneRenderer, sceneRect;
        static Method sceneCanvas, canvasWidth, canvasMatrix;

        static void resolve(View canvas) throws Exception {
            if (viewScene != null) return;
            ClassLoader cl = canvas.getClass().getClassLoader();
            Class<?> scene = StudioNames.cls("scene", cl), area = StudioNames.cls("area", cl);
            viewScene = ScreenshotEditor.fieldOfType(canvas.getClass(), scene);
            sceneRenderer = ScreenshotEditor.fieldOfType(scene, StudioNames.cls("renderer", cl));
            sceneRect = firstRect(scene);
            sceneCanvas = ScreenshotEditor.method(scene, StudioNames.m("scene.area"));
            canvasWidth = ScreenshotEditor.method(area, StudioNames.m("area.width"));
            canvasMatrix = ScreenshotEditor.method(area, StudioNames.m("area.matrix"));
        }

        static java.lang.reflect.Field firstRect(Class<?> scene) throws NoSuchFieldException {
            java.lang.reflect.Field best = null;
            for (java.lang.reflect.Field f : scene.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != RectF.class) continue;
                if (best == null || f.getName().compareTo(best.getName()) < 0) best = f;
            }
            if (best == null) throw new NoSuchFieldException("RectF in " + scene.getName());
            best.setAccessible(true);
            return best;
        }

        static Object renderer(View canvas) throws Exception {
            resolve(canvas);
            return sceneRenderer.get(viewScene.get(canvas));
        }

        static Space space(View canvas, View overlay) {
            try {
                resolve(canvas);
                Object scene = viewScene.get(canvas);
                RectF e = (RectF) sceneRect.get(scene);
                Object area = sceneCanvas.invoke(scene);
                int width = (Integer) canvasWidth.invoke(area);
                Matrix f = (Matrix) canvasMatrix.invoke(area);
                if (width <= 0 || e.width() <= 0) return null;
                float b = e.width() / width;
                int[] lc = new int[2], lo = new int[2];
                canvas.getLocationOnScreen(lc); overlay.getLocationOnScreen(lo);
                float dx = e.left + lc[0] - lo[0], dy = e.top + lc[1] - lo[1];
                Space s = new Space();
                s.toScreen.set(f);
                s.toScreen.postScale(b, b);
                s.toScreen.postTranslate(dx, dy);
                if (!s.toScreen.invert(s.toImage)) return null;
                s.image.set(dx, dy, dx + e.width(), dy + e.height());
                s.scale = s.toScreen.mapRadius(1f);
                return s;
            } catch (Throwable error) {
                ScreenshotEditor.logOnce("doodles: canvas geometry unavailable", error);
                return null;
            }
        }
    }
}
