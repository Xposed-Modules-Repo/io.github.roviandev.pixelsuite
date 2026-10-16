package io.github.pixelsuite;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The drawer's "drop here to make a folder" indicator, like the home screen's: while an app is
 * dragged over the middle of another app (or over a drawer folder), a folder-colored shape grows
 * behind that icon; it shrinks away when the finger leaves the middle or the drag ends.
 *
 * It is drawn by the list itself, right before it draws the target cell (ViewGroup.drawChild,
 * the hook BackdropSurface already uses), so it sits behind the icon like the home screen's
 * circle, which CellLayout draws under its icons. preview26 drew it as the cell's own
 * background, which the list clips to the cell: a folder's indicator, 1.2x its preview, lost
 * its top and left edges. Nothing of the cell (background, padding) is changed any more.
 * Shape: the launcher's folder shape when it can be read, otherwise a circle.
 */
final class DropIndicator {
    /** Indicators on screen (growing, shown or shrinking). Main thread only. */
    private static final List<DropIndicator> active = new ArrayList<DropIndicator>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final View view;
    private float cx, cy, radius, progress;
    private ValueAnimator animator;

    private DropIndicator(View v) {
        view = v;
        paint.setColor(FrostedSurface.closedFolderColor(v.getContext()));
    }

    /** Grows the indicator behind the icon of {@code target} (an app cell or a drawer folder cell). */
    static void show(View target) {
        DropIndicator d = of(target);
        if (d == null) {
            shape = null;                               // re-read: the folder shape may have changed
            d = new DropIndicator(target);
            if (!d.measure()) return;
            active.add(d);
        }
        d.animateTo(1f, 160);
    }

    /** Shrinks it away. */
    static void hide(View target) {
        if (target == null) return;
        DropIndicator d = of(target);
        if (d != null) d.animateTo(0f, 120);
    }

    static DropIndicator of(View v) {
        for (int i = 0; i < active.size(); i++) if (active.get(i).view == v) return active.get(i);
        return null;
    }

    /** From the ViewGroup.drawChild hook: draws the indicator of {@code child}, if any, just before it. */
    static void beforeDrawChild(ViewGroup parent, Canvas canvas, View child) {
        if (active.isEmpty()) return;
        for (int i = 0; i < active.size(); i++) {
            DropIndicator d = active.get(i);
            if (d.view != child || child.getParent() != parent) continue;
            int save = canvas.save();
            canvas.translate(child.getLeft() + child.getTranslationX(), child.getTop() + child.getTranslationY());
            if (child.getScaleX() != 1f || child.getScaleY() != 1f) canvas.scale(child.getScaleX(), child.getScaleY(), child.getPivotX(), child.getPivotY());
            d.draw(canvas);
            canvas.restoreToCount(save);
        }
    }

    private void remove() {
        active.remove(this);
        redraw();
    }

    private void redraw() {
        if (view.getParent() instanceof View) ((View) view.getParent()).invalidate();
        view.invalidate();
    }

    /** Center and size of the target's icon in the cell's own coordinates. */
    private boolean measure() {
        Context c = view.getContext();
        int w = view.getWidth();
        if (w <= 0) return false;
        int size = 0, top = view.getPaddingTop();
        float centerX = w / 2f;
        try {
            if (DrawerFolders.isFolder(view.getTag()) && view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0) {
                // A drawer folder cell: the FolderIcon's preview circle.
                View icon = ((ViewGroup) view).getChildAt(0);
                Object preview = LauncherFeatures.get(icon, "mBackground");
                size = ((Number) LauncherFeatures.get(preview, "previewSize")).intValue();
                top = icon.getTop() + ((Number) LauncherFeatures.get(preview, "basePreviewOffsetY")).intValue();
                centerX = icon.getLeft() + ((Number) LauncherFeatures.get(preview, "basePreviewOffsetX")).intValue() + size / 2f;
            } else {
                size = ((Number) LauncherFeatures.get(view, "mIconSize")).intValue();
            }
        } catch (Throwable ignored) { }
        if (size <= 0) size = FrostedSurface.dp(c, 56);
        cx = centerX; cy = top + size / 2f;
        radius = size * 1.2f / 2f;                      // the stock accept scale (1.2x the icon)
        return true;
    }

    private void animateTo(final float target, long duration) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                if (target == 0f && a.getAnimatedFraction() >= 1f) remove(); else redraw();
            }
        });
        animator.start();
    }

    private void draw(Canvas canvas) {
        if (progress <= 0f) return;
        float r = radius * (0.75f + 0.25f * progress);
        int alpha = paint.getAlpha();
        paint.setAlpha(Math.round(alpha * Math.min(1f, progress)));
        if (!drawShape(canvas, cx - r, cy - r, r)) canvas.drawCircle(cx, cy, r, paint);
        paint.setAlpha(alpha);
    }

    private static Object shape;
    private static Method drawShape;
    private static boolean shapeMissing;

    /** The launcher's folder shape (ThemeManager folderShape.drawShape), if this launcher has it. */
    private boolean drawShape(Canvas canvas, float left, float top, float r) {
        if (shapeMissing) return false;
        try {
            if (shape == null) {
                Object holder = LauncherFeatures.type("graphics.ThemeManager").getField("INSTANCE").get(null);
                Object manager = LauncherFeatures.call(holder, "get", view.getContext());
                Object found;
                try { found = LauncherFeatures.call(manager, "getFolderShape"); }
                catch (Throwable notThere) {
                    try { found = LauncherFeatures.call(LauncherFeatures.call(manager, "getIconState"), "getFolderShape"); }
                    catch(Throwable fieldsOnly){found=LauncherFeatures.get(LauncherFeatures.get(manager,"iconState"),"folderShape");}
                }
                Method m = found.getClass().getMethod("drawShape", Canvas.class, float.class, float.class, float.class, Paint.class);
                m.setAccessible(true);
                shape = found; drawShape = m;
            }
            drawShape.invoke(shape, canvas, left, top, r, paint);
            return true;
        } catch (Throwable missing) { shapeMissing = true; return false; }
    }
}
