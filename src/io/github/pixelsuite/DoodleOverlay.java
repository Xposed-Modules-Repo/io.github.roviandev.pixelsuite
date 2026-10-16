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

/**
 * The Doodles tool of the screenshot editor: a layer over the editor's canvas for placing and
 * editing shapes, and a panel above the toolbar (shapes, colors, thickness, fill, delete, done).
 *
 * Shapes belong to the editor's renderer (irq) and are drawn by ScreenshotEditor into its own
 * picture, so this layer only edits them and draws the selection handles. Touches outside the
 * picture area and the panel go through to the editor (top bar, toolbar), so Save, Share and
 * the other tools keep working while Doodles is open.
 */
final class DoodleOverlay extends FrameLayout {
    static final int[] COLORS = {0xffec5f59, 0xffa1322d, 0xffdf7630, 0xfff6c644, 0xff49c562, 0xff34c8bc,
            0xff2a84c8, 0xff0f558a, 0xffa657c0, 0xffec5fbb, 0xfffafafa, 0xff000000};
    static final String[] COLOR_NAMES = {"Red", "Dark red", "Orange", "Yellow", "Green", "Turquoise",
            "Blue", "Dark blue", "Purple", "Pink", "White", "Black"};
    static final Map<Object, List<Doodle>> models = new WeakHashMap<Object, List<Doodle>>();
    static WeakReference<DoodleOverlay> current = new WeakReference<DoodleOverlay>(null);

    // Tool choices, kept for the life of the editor process.
    static int sKind = Doodle.ARROW, sColor = COLORS[0];
    static float sWidthDp = 5;
    static boolean sFill;
    static float sPanelShift;   // how far the menu was dragged from its place above the toolbar

    final Activity activity;
    final View canvasView;
    final Object renderer;
    final List<Doodle> shapes;
    Doodle selected;
    final Layer layer;
    final LinearLayout panel;
    final float density;
    final int surface, onSurface, onSurfaceVariant, accent, onAccent, outline;
    float toolbarTopRaw;
    Object backCallback;
    final List<ImageView> shapeButtons = new ArrayList<ImageView>(), colorButtons = new ArrayList<ImageView>();
    ImageView fillButton;
    SeekBar size;

    // ------------------------------------------------------------------ static entry points

    static boolean active() {
        DoodleOverlay o = current.get();
        return o != null && o.isAttachedToWindow();
    }

    static void toggle() {
        if (active()) leave(true); else enter();
    }

    static void enter() {
        Activity a = ScreenshotEditor.activity();
        View canvas = a == null ? null : ScreenshotEditor.canvasFor(a);
        if (a == null || canvas == null) { ScreenshotEditor.logOnce("doodles: editor canvas not found", null); return; }
        try {
            ScreenshotEditor.closeNativeTool();
            sKind = Doodle.ARROW;   // every time Doodles opens it starts on the first (pointer) shape
            DoodleOverlay o = new DoodleOverlay(a, canvas, ScreenshotEditor.lastDownRawY);
            ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
            content.addView(o, new ViewGroup.LayoutParams(-1, -1));
            current = new WeakReference<DoodleOverlay>(o);
            o.registerBack(true);
            ScreenshotEditor.setSelected(true);
            Module.log("screenshot-editor: doodles opened");
        } catch (Throwable error) {
            ScreenshotEditor.logOnce("doodles could not open", error);
        }
    }

    /** Closes Doodles; the shapes stay on the picture. */
    static void leave(boolean fromButton) {
        DoodleOverlay o = current.get();
        current = new WeakReference<DoodleOverlay>(null);
        ScreenshotEditor.setSelected(false);
        if (o == null) return;
        o.selected = null;
        o.refresh();
        o.registerBack(false);
        if (o.getParent() instanceof ViewGroup) ((ViewGroup) o.getParent()).removeView(o);
    }

    /** Copies of the shapes for one renderer, for drawing into its picture (any thread). */
    static List<Doodle> snapshot(Object renderer) {
        synchronized (models) {
            List<Doodle> list = models.get(renderer);
            if (list == null || list.isEmpty()) return java.util.Collections.emptyList();
            List<Doodle> copy = new ArrayList<Doodle>(list.size());
            for (Doodle d : list) copy.add(d.copy());
            return copy;
        }
    }

    static List<Doodle> model(Object renderer) {
        synchronized (models) {
            List<Doodle> list = models.get(renderer);
            if (list == null) { list = new ArrayList<Doodle>(); models.put(renderer, list); }
            return list;
        }
    }

    // ------------------------------------------------------------------ history (editor's Undo / Redo)

    static final class History {
        final ArrayList<List<Doodle>> undo = new ArrayList<List<Doodle>>(), redo = new ArrayList<List<Doodle>>();
    }
    static final Map<Object, History> histories = new WeakHashMap<Object, History>();
    static WeakReference<Object> lastRenderer = new WeakReference<Object>(null);

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

    /** Saves the shapes as they are now, before a change; a new change drops the redo steps. */
    static void checkpoint(Object renderer) {
        if (renderer == null) return;
        synchronized (models) {
            History h = history(renderer);
            h.undo.add(copies(model(renderer)));
            if (h.undo.size() > 100) h.undo.remove(0);
            h.redo.clear();
        }
        ScreenshotEditor.doodleHistoryChanged();
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
        if (r == null) return false;
        synchronized (models) { List<Doodle> l = models.get(r); return l != null && !l.isEmpty(); }
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
        else {
            Activity a = ScreenshotEditor.activity();
            View canvas = a == null ? null : ScreenshotEditor.canvasFor(a);
            if (canvas != null) canvas.invalidate();
        }
        ScreenshotEditor.doodleHistoryChanged();
        return true;
    }

    void checkpoint() { checkpoint(renderer); }

    // ------------------------------------------------------------------ colors

    static int system(Context c, String name, int fallback) {
        int id = c.getResources().getIdentifier(name, "color", "android");
        try { return id == 0 ? fallback : c.getResources().getColor(id, c.getTheme()); } catch (Throwable ignored) { return fallback; }
    }
    static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    DoodleOverlay(Activity a, View canvas, float toolbarRawY) throws Exception {
        super(a);
        activity = a;
        canvasView = canvas;
        renderer = Bridge.renderer(canvas);
        lastRenderer = new WeakReference<Object>(renderer);
        shapes = model(renderer);
        density = a.getResources().getDisplayMetrics().density;
        boolean dark = dark(a);
        surface = system(a, dark ? "system_surface_container_high_dark" : "system_surface_container_high_light", dark ? 0xff2b2930 : 0xffece6f0);
        onSurface = system(a, dark ? "system_on_surface_dark" : "system_on_surface_light", dark ? 0xffe6e0e9 : 0xff1d1b20);
        onSurfaceVariant = system(a, dark ? "system_on_surface_variant_dark" : "system_on_surface_variant_light", dark ? 0xffcac4d0 : 0xff49454f);
        accent = system(a, dark ? "system_accent1_200" : "system_accent1_600", dark ? 0xffd0bcff : 0xff6750a4);
        onAccent = system(a, dark ? "system_accent1_800" : "system_accent1_0", dark ? 0xff381e72 : 0xffffffff);
        outline = system(a, dark ? "system_outline_variant_dark" : "system_outline_variant_light", dark ? 0xff49454f : 0xffcac4d0);
        // The Doodles button was just tapped: its centre is the last touch, the toolbar's middle.
        toolbarTopRaw = toolbarRawY > 0 ? toolbarRawY - dp(36) : -1;
        layer = new Layer(a);
        addView(layer, new LayoutParams(-1, -1));
        panel = buildPanel(a);
        LayoutParams lp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.leftMargin = lp.rightMargin = dp(12);
        lp.bottomMargin = dp(140);
        addView(panel, lp);
        setClipChildren(false);
    }

    int dp(float v) { return Math.round(v * density); }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Sit just above the editor's toolbar (the Doodles button's row).
        if (toolbarTopRaw > 0) {
            int[] at = new int[2]; getLocationOnScreen(at);
            int margin = Math.max(dp(8), Math.round((b - t) - (toolbarTopRaw - at[1]) + dp(8)));
            LayoutParams lp = (LayoutParams) panel.getLayoutParams();
            if (lp.bottomMargin != margin) { lp.bottomMargin = margin; panel.setLayoutParams(lp); }
        }
        super.onLayout(changed, l, t, r, b);
        placePanel();
    }

    void registerBack(boolean on) {
        try {
            Object dispatcher = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(activity);
            Class<?> dispatcherType = Class.forName("android.window.OnBackInvokedDispatcher");
            Class<?> callbackType = Class.forName("android.window.OnBackInvokedCallback");
            if (on) {
                backCallback = java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, new java.lang.reflect.InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        if ("onBackInvoked".equals(m.getName())) leave(false);
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

    /** Redraw the editor's picture (it draws the shapes) and our handles. */
    void refresh() {
        canvasView.invalidate();
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
        Bridge.Space s = Bridge.space(canvasView, this);
        return sWidthDp * density / Math.max(0.0001f, s == null ? 1 : s.scale);
    }

    // ------------------------------------------------------------------ the editing layer

    final class Layer extends View {
        static final int NONE = 0, PENDING = 1, CREATE = 2, MOVE = 3, HANDLE = 4, PINCH = 5;
        int mode = NONE, handle = -1;
        float downX, downY, lastX, lastY;
        boolean hadSelection, saved;
        float pinchStart;
        float[] pinchMid = new float[2];
        Doodle pinchFrom;
        final int slop;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

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

        /** Touches this layer takes: the picture area (a little wider), above the toolbar. */
        boolean ours(Bridge.Space s, float x, float y) {
            if (selected != null && Doodle.handleAt(selected.isLine(), screen(selected, s), x, y, dp(26)) >= 0) return true;
            RectF area = new RectF(s.image.left - dp(24), s.image.top - dp(8), s.image.right + dp(24), s.image.bottom + dp(24));
            if (toolbarTopRaw > 0) {
                int[] at = new int[2]; getLocationOnScreen(at);
                area.bottom = Math.min(area.bottom, toolbarTopRaw - at[1]);
            }
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

        @Override public boolean onTouchEvent(MotionEvent e) {
            Bridge.Space s = Bridge.space(canvasView, DoodleOverlay.this);
            if (s == null) return false;
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (!ours(s, x, y)) return false;
                    downX = lastX = x; downY = lastY = y;
                    hadSelection = selected != null;
                    saved = false;
                    handle = selected == null ? -1 : Doodle.handleAt(selected.isLine(), screen(selected, s), x, y, dp(26));
                    if (handle >= 0) { mode = HANDLE; dim(true); return true; }
                    Doodle d = hit(s, x, y);
                    if (d != null) {
                        if (d != selected) { selected = d; syncPanel(); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                        mode = MOVE; dim(true); invalidate(); return true;
                    }
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
                    if ((mode == MOVE || mode == HANDLE) && !saved && Math.hypot(x - downX, y - downY) > slop / 2f) { checkpoint(); saved = true; }
                    if (mode == CREATE) {
                        float[] b = image(s, x, y);
                        synchronized (models) { selected.x2 = b[0]; selected.y2 = b[1]; }
                    } else if (mode == MOVE && selected != null && saved) {
                        float[] a = image(s, lastX, lastY), b = image(s, x, y);
                        synchronized (models) { selected.offset(b[0] - a[0], b[1] - a[1]); }
                    } else if (mode == HANDLE && selected != null && saved) {
                        if (selected.isLine()) {
                            float[] b = image(s, x, y);
                            synchronized (models) { if (handle == 0) { selected.x1 = b[0]; selected.y1 = b[1]; } else { selected.x2 = b[0]; selected.y2 = b[1]; } }
                        } else {
                            float[] box = Doodle.dragBoxHandle(screen(selected, s), handle, x, y);
                            s.toImage.mapPoints(box);
                            synchronized (models) { selected.x1 = box[0]; selected.y1 = box[1]; selected.x2 = box[2]; selected.y2 = box[3]; }
                            // The fixed corner is now (x1,y1): keep dragging the same visual corner.
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
                    refresh();
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    if (mode == PINCH) mode = NONE;   // the remaining finger does nothing more
                    return true;
                case MotionEvent.ACTION_UP:
                    if (mode == PENDING) {
                        if (hadSelection) {
                            selected = null;
                        } else {
                            // A tap places a ready-made shape there, to drag and resize.
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
                    mode = NONE; dim(false);
                    refresh();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mode = NONE; dim(false); refresh();
                    return true;
            }
            return true;
        }

        float spacing(MotionEvent e) {
            return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
        }

        @Override protected void onDraw(Canvas canvas) {
            Doodle d = selected;
            if (d == null) return;
            Bridge.Space s = Bridge.space(canvasView, DoodleOverlay.this);
            if (s == null) return;
            float[] p = screen(d, s);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(0xccffffff);
            paint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            if (d.isLine()) canvas.drawLine(p[0], p[1], p[2], p[3], paint);
            else canvas.drawRect(Math.min(p[0], p[2]), Math.min(p[1], p[3]), Math.max(p[0], p[2]), Math.max(p[1], p[3]), paint);
            paint.setPathEffect(null);
            float[] h = Doodle.handlePoints(d.isLine(), p);
            for (int i = 0; i < h.length / 2; i++) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x33000000);
                canvas.drawCircle(h[i * 2], h[i * 2 + 1] + dp(1), dp(10), paint);
                paint.setColor(0xffffffff);
                canvas.drawCircle(h[i * 2], h[i * 2 + 1], dp(9), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3));
                paint.setColor(accent);
                canvas.drawCircle(h[i * 2], h[i * 2 + 1], dp(6.5f), paint);
            }
        }
    }

    void dim(boolean busy) {
        // While a shape is being dragged the whole Doodles menu is out of the way.
        panel.animate().alpha(busy ? 0f : 1f).setDuration(busy ? 80 : 160).start();
    }

    // ------------------------------------------------------------------ the panel

    LinearLayout buildPanel(final Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(2), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(dp(28));
        box.setBackground(bg);
        box.setElevation(dp(6));
        box.setClickable(true);   // the panel keeps its touches
        box.addView(dragHandle(c), new LinearLayout.LayoutParams(-1, dp(22)));

        HorizontalScrollView shapeScroll = new HorizontalScrollView(c);
        shapeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout shapeRow = new LinearLayout(c);
        for (int k = 0; k < Doodle.KINDS; k++) {
            final int kind = k;
            ImageView b = new ImageView(c);
            b.setImageDrawable(new ShapeIcon(kind, onSurface, density));
            b.setContentDescription(Doodle.NAMES[k]);
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setOnClickListener(new OnClickListener() { public void onClick(View v) {
                sKind = kind;
                if (selected != null) {
                    // Any shape turns the selected one into it, lines and boxes alike.
                    checkpoint();
                    synchronized (models) { selected.convertTo(kind); selected.fill = selected.fillable() && sFill; }
                    refresh();
                }
                syncPanel();
            }});
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.rightMargin = dp(4);
            shapeRow.addView(b, lp);
            shapeButtons.add(b);
        }
        shapeScroll.addView(shapeRow);
        box.addView(shapeScroll, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView colorScroll = new HorizontalScrollView(c);
        colorScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout colorRow = new LinearLayout(c);
        colorRow.setPadding(dp(4), dp(6), dp(4), dp(6));
        for (int i = 0; i < COLORS.length; i++) {
            final int color = COLORS[i];
            ImageView swatch = new ImageView(c);
            swatch.setContentDescription(COLOR_NAMES[i]);
            swatch.setOnClickListener(new OnClickListener() { public void onClick(View v) { sColor = color; applyToSelected(); syncPanel(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
            lp.rightMargin = dp(8);
            colorRow.addView(swatch, lp);
            colorButtons.add(swatch);
        }
        colorScroll.addView(colorRow);
        box.addView(colorScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thin = new ImageView(c);
        thin.setImageDrawable(new StrokeIcon(onSurfaceVariant, density));
        thin.setContentDescription("Thickness");
        controls.addView(thin, new LinearLayout.LayoutParams(dp(28), dp(40)));
        size = new SeekBar(c);
        size.setMax(23);
        size.setProgress(Math.round(sWidthDp) - 1);
        size.setContentDescription("Thickness");
        size.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        size.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        size.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(outline));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { if (user) { sWidthDp = value + 1; applyToSelected(false); } }
            public void onStartTrackingTouch(SeekBar bar) { if (selected != null) checkpoint(); }
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        controls.addView(size, new LinearLayout.LayoutParams(0, dp(40), 1));
        fillButton = roundButton(c, new FillIcon(onSurface, density), "Fill shape", new OnClickListener() { public void onClick(View v) {
            sFill = !sFill; applyToSelected(); syncPanel();
        }});
        controls.addView(fillButton, buttonParams());
        controls.addView(roundButton(c, new TrashIcon(onSurface, density), "Delete shape", new OnClickListener() { public void onClick(View v) {
            if (selected != null || !shapes.isEmpty()) checkpoint();
            synchronized (models) {
                if (selected != null) shapes.remove(selected);
                else if (!shapes.isEmpty()) shapes.remove(shapes.size() - 1);
            }
            selected = null; refresh(); syncPanel();
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }}), buttonParams());
        ImageView done = roundButton(c, new CheckIcon(onAccent, density), "Done", new OnClickListener() { public void onClick(View v) { leave(false); } });
        GradientDrawable doneBg = new GradientDrawable(); doneBg.setShape(GradientDrawable.OVAL); doneBg.setColor(accent);
        done.setBackground(doneBg);
        controls.addView(done, buttonParams());
        box.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        syncPanel();
        return box;
    }

    /** The pill at the top of the menu: hold it (it buzzes once) and drag the menu anywhere. */
    View dragHandle(Context c) {
        final View pill = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                p.setColor(onSurfaceVariant);
                p.setAlpha(isPressed() ? 255 : 150);
                float w = dp(isPressed() ? 48 : 40), h = dp(5), cx = getWidth() / 2f, cy = getHeight() / 2f;
                canvas.drawRoundRect(new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), h / 2, h / 2, p);
            }
        };
        pill.setContentDescription("Move Doodles menu");
        pill.setOnTouchListener(new View.OnTouchListener() {
            float startRaw, startShift;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (Math.abs(e.getX() - v.getWidth() / 2f) > dp(56)) return false;   // only the pill itself
                        startRaw = e.getRawY(); startShift = sPanelShift;
                        v.setPressed(true); v.invalidate();
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);   // moving is now active
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        sPanelShift = startShift + (e.getRawY() - startRaw);
                        placePanel();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false); v.invalidate();
                        return true;
                }
                return true;
            }
        });
        return pill;
    }

    /** Applies the dragged shift, kept inside the screen. */
    void placePanel() {
        if (getHeight() <= 0 || panel.getHeight() <= 0) { panel.setTranslationY(sPanelShift); return; }
        float top = dp(32), lowest = getHeight() - dp(8);
        float min = top - panel.getTop(), max = lowest - panel.getTop() - panel.getHeight();
        sPanelShift = Math.max(min, Math.min(Math.max(min, max), sPanelShift));
        panel.setTranslationY(sPanelShift);
    }

    LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.leftMargin = dp(6);
        return lp;
    }

    ImageView roundButton(Context c, Drawable icon, String label, OnClickListener click) {
        ImageView b = new ImageView(c);
        b.setImageDrawable(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(label);
        b.setOnClickListener(click);
        return b;
    }

    /** Shows the current choices (or the selected shape's) on the panel. */
    void syncPanel() {
        if (shapeButtons.isEmpty()) return;
        Doodle d = selected;
        int kind = d != null ? d.kind : sKind;
        int color = d != null ? d.color : sColor;
        boolean fill = d != null ? d.fill : sFill;
        if (d != null) { sKind = d.kind; sColor = d.color; sFill = d.fillable() ? d.fill : sFill; }
        for (int i = 0; i < shapeButtons.size(); i++) {
            ImageView b = shapeButtons.get(i);
            boolean on = i == kind;
            GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(on ? accent : Color.TRANSPARENT);
            b.setBackground(bg);
            ((ShapeIcon) b.getDrawable()).color = on ? onAccent : onSurface;
            b.getDrawable().invalidateSelf();
            b.setSelected(on);
        }
        for (int i = 0; i < colorButtons.size(); i++) {
            ImageView b = colorButtons.get(i);
            boolean on = COLORS[i] == color;
            GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(COLORS[i]);
            dot.setStroke(on ? dp(3) : dp(1), on ? accent : outline);
            b.setBackground(dot);
            b.setSelected(on);
        }
        GradientDrawable fillBg = new GradientDrawable(); fillBg.setShape(GradientDrawable.OVAL);
        fillBg.setColor(fill ? (accent & 0x00ffffff) | 0x40000000 : Color.TRANSPARENT);
        fillButton.setBackground(fillBg);
        ((FillIcon) fillButton.getDrawable()).filled = fill;
        fillButton.getDrawable().invalidateSelf();
        fillButton.setAlpha(Doodle.isLine(kind) ? 0.35f : 1f);
        if (d != null && size != null) {
            Bridge.Space s = Bridge.space(canvasView, this);
            if (s != null) { sWidthDp = Math.max(1, Math.min(24, d.width * s.scale / density)); size.setProgress(Math.round(sWidthDp) - 1); }
        }
    }

    // ------------------------------------------------------------------ icons (drawn, no resources)

    abstract static class Icon extends Drawable {
        int color; final float density;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(int color, float density) { this.color = color; this.density = density; }
        @Override public int getIntrinsicWidth() { return Math.round(24 * density); }
        @Override public int getIntrinsicHeight() { return Math.round(24 * density); }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        void stroke(float width) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width * density); paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        /** Draws in a 24x24 box centred on the bounds. */
        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            float size = 24 * density;
            canvas.translate(getBounds().exactCenterX() - size / 2, getBounds().exactCenterY() - size / 2);
            canvas.scale(density, density);
            paint.setColor(color);
            draw24(canvas);
            canvas.restoreToCount(save);
        }
        abstract void draw24(Canvas canvas);
        void stroke24(float width) { stroke(width / density); }
    }

    static final class ShapeIcon extends Icon {
        final int kind;
        ShapeIcon(int kind, int color, float density) { super(color, density); this.kind = kind; }
        @Override void draw24(Canvas c) {
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(2f);
            if (Doodle.isLine(kind)) Doodle.drawLine(c, paint, kind, 4, 20, 20, 4, 2f);
            else { paint.setStyle(Paint.Style.STROKE); c.drawPath(Doodle.outline(kind, 4, kind == Doodle.RECT ? 5 : 4, 20, kind == Doodle.RECT ? 19 : 20, 2f), paint); }
        }
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
            c.drawRoundRect(new RectF(5, 5, 19, 19), 3, 3, paint);
        }
    }

    static final class TrashIcon extends Icon {
        TrashIcon(int color, float density) { super(color, density); }
        @Override void draw24(Canvas c) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            c.drawLine(4.5f, 6.5f, 19.5f, 6.5f, paint);
            c.drawLine(9.5f, 6.5f, 10, 4, paint); c.drawLine(10, 4, 14, 4, paint); c.drawLine(14, 4, 14.5f, 6.5f, paint);
            Path can = new Path(); can.moveTo(6.5f, 6.5f); can.lineTo(7.5f, 19); can.quadTo(7.6f, 20, 8.6f, 20); can.lineTo(15.4f, 20); can.quadTo(16.4f, 20, 16.5f, 19); can.lineTo(17.5f, 6.5f);
            c.drawPath(can, paint);
            c.drawLine(10.2f, 10, 10.4f, 16.5f, paint); c.drawLine(13.8f, 10, 13.6f, 16.5f, paint);
        }
    }

    static final class CheckIcon extends Icon {
        CheckIcon(int color, float density) { super(color, density); }
        @Override void draw24(Canvas c) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.4f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            Path p = new Path(); p.moveTo(5.5f, 12.5f); p.lineTo(10, 17); p.lineTo(18.5f, 7.5f);
            c.drawPath(p, paint);
        }
    }

    /** The toolbar icon: a square, a circle and an arrow (same drawing as ic_ps_doodles), white on clear for tinting. */
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

    // ------------------------------------------------------------------ the editor's canvas geometry

    /** Reads the editor canvas view (irt) and its scene (irz) by reflection. */
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
            viewScene = ScreenshotEditor.field(canvas.getClass(), "a");
            Class<?> scene = Xp.findClass("irz", cl), area = Xp.findClass("ira", cl);
            sceneRenderer = ScreenshotEditor.field(scene, "b");
            sceneRect = ScreenshotEditor.field(scene, "e");
            sceneCanvas = ScreenshotEditor.method(scene, "k");
            canvasWidth = ScreenshotEditor.method(area, "d");
            canvasMatrix = ScreenshotEditor.method(area, "f");
        }

        static Object renderer(View canvas) throws Exception {
            resolve(canvas);
            return sceneRenderer.get(viewScene.get(canvas));
        }

        /** Image space <-> this overlay's coordinates, from the editor's own transform. */
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
