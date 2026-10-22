package io.github.pixelsuite;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

import java.lang.ref.WeakReference;

final class IconEditor {
    private IconEditor() {}

    interface Result {
        void save(Bitmap picture, RectF area, Done done);
    }
    interface Done { void run(boolean ok); }

    static void show(Activity a, Bitmap picture, String label, View icon, Result result) {
        Editor editor = null;
        try { editor = new Editor(a, picture, label, icon, result); editor.open(); }
        catch (Throwable error) {
            Module.log("Icon editor unavailable", error);
            if (editor != null && editor.dialog != null && editor.dialog.isShowing()) return;
            int side = Math.min(picture.getWidth(), picture.getHeight());
            RectF middle = new RectF((picture.getWidth() - side) / 2f, (picture.getHeight() - side) / 2f, 0, 0);
            middle.right = middle.left + side; middle.bottom = middle.top + side;
            final Bitmap p = picture;
            result.save(p, middle, new Done() { public void run(boolean ok) { p.recycle(); } });
        }
    }

    static Bitmap render(Bitmap picture, RectF area, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        Bitmap piece = picture; RectF at = new RectF(area);
        if (size / area.width() < 0.5f) {
            int l = Math.max(0, (int) Math.floor(area.left)), t = Math.max(0, (int) Math.floor(area.top));
            int r = Math.min(picture.getWidth(), (int) Math.ceil(area.right)), b = Math.min(picture.getHeight(), (int) Math.ceil(area.bottom));
            if (r - l > 1 && b - t > 1) {
                piece = Bitmap.createBitmap(picture, l, t, r - l, b - t);
                at.offset(-l, -t);
                while (size / at.width() < 0.5f && piece.getWidth() > 2 && piece.getHeight() > 2) {
                    int w = piece.getWidth() / 2, h = piece.getHeight() / 2;
                    float kx = w / (float) piece.getWidth(), ky = h / (float) piece.getHeight();
                    Bitmap half = Bitmap.createScaledBitmap(piece, w, h, true);
                    if (piece != picture && half != piece) piece.recycle();
                    piece = half;
                    at.set(at.left * kx, at.top * ky, at.right * kx, at.bottom * ky);
                }
            }
        }
        Matrix m = new Matrix();
        m.setRectToRect(at, new RectF(0, 0, size, size), Matrix.ScaleToFit.FILL);
        c.drawBitmap(piece, m, paint);
        if (piece != picture) piece.recycle();
        return out;
    }

    static Path shape(Context c, float size) {
        Path p = new Path();
        try {
            Class<?> tm = LauncherFeatures.type("graphics.ThemeManager");
            Object single = tm.getField("INSTANCE").get(null);
            Object manager = Xp.callMethod(single, "get", c);
            Object state = Xp.getObjectField(manager, "iconState");
            Object delegate = Xp.getObjectField(state, "iconShape");
            Xp.callMethod(delegate, "addToPath", 0f, 0f, size / 2f, p);
            RectF b = new RectF(); p.computeBounds(b, true);
            if (b.width() > size * 0.8f && b.height() > size * 0.8f && b.width() < size * 1.2f) return p;
        } catch (Throwable error) { LauncherFeatures.logOnce("Icon editor: launcher icon shape unavailable, circle used", error); }
        p.reset();
        p.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
        return p;
    }

    private static final class Editor {
        final Activity a; final Bitmap picture; final String label; final WeakReference<View> icon; final Result result;
        final float d;
        final boolean superDark, frosted;
        final int stageColor, buttonColor, accent, onAccent;
        Dialog dialog; FrameLayout root; Stage stage; Preview preview; TextView hint; LinearLayout reset; View close, check; TextView title;
        int insetTop, insetBottom, insetLeft, insetRight, iconSize;
        boolean closing, saving, compact;
        android.app.Application.ActivityLifecycleCallbacks watch;

        Editor(Activity a, Bitmap picture, String label, View icon, Result result) {
            this.a = a; this.picture = picture; this.label = label == null ? "" : label;
            this.icon = new WeakReference<View>(icon); this.result = result;
            d = a.getResources().getDisplayMetrics().density;
            superDark = SuperDark.inEffect(a);
            frosted = SurfaceStyle.blur(a) && FrostedSurface.blurEnabled(a) && !superDark;
            int style = SurfaceStyle.get(a);
            stageColor = superDark ? 0xff000000 : frosted ? 0xe00b0b0e : 0xff0b0b0e;
            buttonColor = superDark ? 0xff1a1a1a : frosted ? 0x30ffffff : style == SurfaceStyle.STOCK ? 0xff2d2d31 : 0xff26262b;
            accent = LauncherFeatures.surface(a, "system_accent1_200", 0xffd0bcff);
            onAccent = LauncherFeatures.surface(a, "system_accent1_800", 0xff381e72);
            picture.setHasMipMap(true);
            iconSize = launcherIconSize(a);
        }

        int dp(float v) { return Math.round(v * d); }

        void open() {
            dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar) {
                @Override public void onBackPressed() { cancel(); }
            };
            dialog.setCancelable(false);
            root = new FrameLayout(a);
            root.setClipChildren(false);
            stage = new Stage(a);
            root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            close = roundButton(new LauncherGlyph(a, 12), buttonColor, 0xffffffff, "Cancel");
            close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { cancel(); } });
            root.addView(close, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.LEFT));

            CheckGlyph tick = new CheckGlyph(onAccent, d);
            check = roundButton(tick, accent, onAccent, "Save icon");
            check.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirm(); } });
            check.setElevation(dp(3));
            root.addView(check, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.RIGHT));

            title = new TextView(a);
            title.setText("Adjust icon");
            title.setTextColor(0xffffffff);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setTypeface(Typeface.create("google-sans-medium", Typeface.NORMAL));
            title.setGravity(Gravity.CENTER);
            title.setSingleLine(true);
            root.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

            hint = new TextView(a);
            hint.setText("Pinch to zoom, drag to move");
            hint.setTextColor(0xb3ffffff);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setTypeface(Typeface.create("google-sans-text", Typeface.NORMAL));
            hint.setGravity(Gravity.CENTER);
            root.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

            reset = new LinearLayout(a);
            reset.setOrientation(LinearLayout.HORIZONTAL);
            reset.setGravity(Gravity.CENTER_VERTICAL);
            reset.setPadding(dp(14), 0, dp(16), 0);
            GradientDrawable pill = new GradientDrawable(); pill.setColor(buttonColor); pill.setCornerRadius(dp(20));
            reset.setBackground(pill);
            ImageView back = new ImageView(a);
            LauncherGlyph arrow = new LauncherGlyph(a, 4); arrow.setTintList(ColorStateList.valueOf(0xffffffff));
            back.setImageDrawable(arrow);
            reset.addView(back, new LinearLayout.LayoutParams(dp(18), dp(18)));
            TextView resetText = new TextView(a);
            resetText.setText("Reset");
            resetText.setTextColor(0xffffffff);
            resetText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            resetText.setTypeface(Typeface.create("google-sans-text-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = dp(8);
            reset.addView(resetText, tp);
            reset.setContentDescription("Reset position and zoom");
            reset.setAlpha(0f); reset.setVisibility(View.INVISIBLE);
            reset.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stage.toStart(); } });
            root.addView(reset, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

            LinearLayout result = new LinearLayout(a);
            result.setOrientation(LinearLayout.VERTICAL);
            result.setGravity(Gravity.CENTER_HORIZONTAL);
            preview = new Preview(a);
            result.addView(preview, new LinearLayout.LayoutParams(iconSize, iconSize));
            TextView name = new TextView(a);
            name.setText(label);
            name.setTextColor(0xe6ffffff);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setTypeface(Typeface.create("google-sans-text", Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setMaxWidth(dp(120));
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.topMargin = dp(8);
            result.addView(name, np);
            result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            root.addView(result, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    insetTop = in.getSystemWindowInsetTop(); insetBottom = in.getSystemWindowInsetBottom();
                    insetLeft = in.getSystemWindowInsetLeft(); insetRight = in.getSystemWindowInsetRight();
                    place();
                    return in;
                }
            });
            dialog.setContentView(root);
            window(dialog.getWindow());
            dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                public void onDismiss(android.content.DialogInterface di) { stage.stop(); unwatch(); release(); }
            });
            watch = new android.app.Application.ActivityLifecycleCallbacks() {
                public void onActivityDestroyed(Activity x) { if (x == a) { closing = true; stage.stop(); dismiss(); unwatch(); release(); } }
                public void onActivityCreated(Activity x, android.os.Bundle b) { }
                public void onActivityStarted(Activity x) { }
                public void onActivityResumed(Activity x) { }
                public void onActivityPaused(Activity x) { }
                public void onActivityStopped(Activity x) { }
                public void onActivitySaveInstanceState(Activity x, android.os.Bundle b) { }
            };
            a.getApplication().registerActivityLifecycleCallbacks(watch);
            dialog.show();
            enter(tick);
        }
        void unwatch() { if (watch != null) { try { a.getApplication().unregisterActivityLifecycleCallbacks(watch); } catch (Throwable ignored) { } watch = null; } }
        void release() { if (!saving && !picture.isRecycled()) picture.recycle(); }

        void window(Window w) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.windowAnimations = 0;
            try { WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode").setInt(lp, 3); } catch (Throwable ignored) { }
            if (frosted) {
                lp.flags |= 0x4;
                try { WindowManager.LayoutParams.class.getMethod("setBlurBehindRadius", int.class).invoke(lp, FrostedSurface.blurRadius(a)); } catch (Throwable ignored) { }
            }
            w.setAttributes(lp);
            try { Window.class.getMethod("setDecorFitsSystemWindows", boolean.class).invoke(w, false); } catch (Throwable ignored) { }
            try { Window.class.getMethod("setNavigationBarContrastEnforced", boolean.class).invoke(w, false); } catch (Throwable ignored) { }
            View decor = w.getDecorView();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            try {
                Object controller = Window.class.getMethod("getInsetsController").invoke(w);
                controller.getClass().getMethod("setSystemBarsAppearance", int.class, int.class).invoke(controller, 0, 8 | 16);
            } catch (Throwable ignored) { }
        }

        void place() {
            ((FrameLayout.LayoutParams) close.getLayoutParams()).setMargins(insetLeft + dp(16), insetTop + dp(12), 0, 0);
            ((FrameLayout.LayoutParams) check.getLayoutParams()).setMargins(0, insetTop + dp(8), insetRight + dp(16), 0);
            ((FrameLayout.LayoutParams) title.getLayoutParams()).setMargins(0, insetTop + dp(8), 0, 0);
            View block = (View) preview.getParent();
            ((FrameLayout.LayoutParams) block.getLayoutParams()).setMargins(0, 0, 0, insetBottom + dp(28));
            stage.frameFor(root.getWidth(), root.getHeight());
            placeBelow();
            root.requestLayout();
        }
        void placeBelow() {
            if (!stage.ready) return;
            View block = (View) preview.getParent();
            int gone = compact ? View.GONE : View.VISIBLE;
            if (block.getVisibility() != gone) block.setVisibility(gone);
            if (compact) { hint.setVisibility(View.GONE); reset.setVisibility(View.GONE); title.setVisibility(View.GONE); }
            else if (title.getVisibility() == View.GONE) {
                title.setVisibility(View.VISIBLE);
                boolean moved = stage.touched;
                hint.setVisibility(moved ? View.INVISIBLE : View.VISIBLE); hint.setAlpha(moved ? 0f : 1f);
                reset.setVisibility(moved ? View.VISIBLE : View.INVISIBLE); reset.setAlpha(moved ? 1f : 0f);
            }
            int below = Math.round(stage.frame.bottom) + dp(18);
            FrameLayout.LayoutParams h = (FrameLayout.LayoutParams) hint.getLayoutParams(), r = (FrameLayout.LayoutParams) reset.getLayoutParams();
            if (h.topMargin == below && r.topMargin == below) return;
            h.topMargin = below; r.topMargin = below;
            hint.setLayoutParams(h); reset.setLayoutParams(r);
        }

        void enter(final CheckGlyph tick) {
            final View[] chrome = { close, title, check, hint, (View) preview.getParent() };
            for (View v : chrome) { v.setAlpha(0f); v.setTranslationY(dp(-6)); }
            ((View) preview.getParent()).setTranslationY(dp(10));
            ValueAnimator in = ValueAnimator.ofFloat(0f, 1f);
            in.setDuration(320);
            in.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
            in.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator v) {
                stage.enter = (Float) v.getAnimatedValue(); stage.invalidate();
            } });
            in.start();
            long delay = 90;
            for (View v : chrome) { v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(240).setInterpolator(new DecelerateInterpolator()).start(); delay += 30; }
            tick.drawIn(check, 260);
        }

        void cancel() {
            if (closing) return;
            closing = true;
            stage.stop();
            reset.setEnabled(false);
            for (View v : new View[] { close, title, check, hint, reset, (View) preview.getParent() }) v.animate().cancel();
            root.animate().alpha(0f).setDuration(170).setInterpolator(new DecelerateInterpolator()).setListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator x) { dismiss(); }
            }).start();
        }

        void confirm() {
            if (closing) return;
            closing = true;
            Haptics.click(a);
            stage.settleNow();
            RectF area = stage.area();
            final RectF to = target();
            check.setEnabled(false); close.setEnabled(false); reset.setEnabled(false);
            for (View v : new View[] { close, title, check, hint, reset, (View) preview.getParent() }) v.animate().alpha(0f).setStartDelay(0).setDuration(150).start();
            ValueAnimator focus = ValueAnimator.ofFloat(0f, 1f);
            focus.setDuration(180);
            focus.setInterpolator(new DecelerateInterpolator());
            focus.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator v) {
                stage.only = (Float) v.getAnimatedValue(); stage.invalidate();
            } });
            focus.start();
            saving = true;
            result.save(picture, area, new Done() { public void run(boolean ok) {
                saving = false;
                if (dialog == null || !dialog.isShowing()) { release(); return; }
                if (!ok) { android.widget.Toast.makeText(a, "Could not save this icon. Try another image.", android.widget.Toast.LENGTH_SHORT).show(); land(null, false); return; }
                land(to, true);
            } });
        }

        RectF target() {
            View v = icon.get();
            if (v == null || !v.isAttachedToWindow() || !v.isShown() || v.getWidth() <= 0) return null;
            Rect bounds = new Rect();
            try { Xp.callMethod(v, "getIconBounds", bounds); } catch (Throwable t) { bounds.setEmpty(); }
            if (bounds.isEmpty()) { int s = Math.min(iconSize, v.getWidth()); bounds.set((v.getWidth() - s) / 2, v.getPaddingTop(), (v.getWidth() + s) / 2, v.getPaddingTop() + s); }
            RectF r = onScreen(v, new RectF(bounds));
            int[] me = new int[2]; stage.getLocationOnScreen(me);
            r.offset(-me[0], -me[1]);
            DisplayBounds screen = new DisplayBounds(stage);
            if (r.width() <= 0 || !screen.contains(r)) return null;
            return r;
        }

        void land(final RectF to, final boolean ok) {
            final RectF from = new RectF(stage.frame);
            final RectF end = to != null ? to : scaled(from, 0.82f);
            ValueAnimator fly = ValueAnimator.ofFloat(0f, 1f);
            fly.setDuration(to != null ? 380 : 220);
            fly.setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f));
            fly.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator v) {
                float t = (Float) v.getAnimatedValue();
                stage.only = 1f;
                stage.flight = new RectF(lerp(from.left, end.left, t), lerp(from.top, end.top, t), lerp(from.right, end.right, t), lerp(from.bottom, end.bottom, t));
                if (to == null) stage.setAlpha(1f - t);
                stage.invalidate();
            } });
            fly.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator x) {
                if (to == null) { if (ok) android.widget.Toast.makeText(a, "Icon updated", android.widget.Toast.LENGTH_SHORT).show(); dismiss(); return; }
                root.animate().alpha(0f).setDuration(180).setStartDelay(40).setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator y) { dismiss(); }
                }).start();
            } });
            fly.start();
        }

        void dismiss() { try { if (dialog != null && dialog.isShowing()) dialog.dismiss(); } catch (Throwable ignored) { } }

        void changed(boolean atStart) {
            View show = atStart ? hint : reset, hide = atStart ? reset : hint;
            if (closing || compact) return;
            if (show.getVisibility() != View.VISIBLE || show.getAlpha() < 1f) {
                show.setVisibility(View.VISIBLE);
                show.animate().alpha(1f).setStartDelay(0).setDuration(160).setListener(null).start();
            }
            if (hide.getVisibility() == View.VISIBLE) {
                final View h = hide;
                h.animate().alpha(0f).setStartDelay(0).setDuration(120).setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator x) { if (h.getAlpha() == 0f) h.setVisibility(View.INVISIBLE); }
                }).start();
            }
            hint.setText("Pinch to zoom, drag to move");
        }

        View roundButton(Drawable glyph, int fill, int tint, String what) {
            FrameLayout b = new FrameLayout(a);
            GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(fill);
            b.setBackground(bg);
            GradientDrawable mask = new GradientDrawable(); mask.setShape(GradientDrawable.OVAL); mask.setColor(0xffffffff);
            android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, mask);
            b.setForeground(ripple);
            ImageView g = new ImageView(a);
            glyph.setTintList(ColorStateList.valueOf(tint));
            g.setImageDrawable(glyph);
            b.addView(g, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            b.setContentDescription(what);
            b.setClickable(true); b.setFocusable(true);
            return b;
        }

        final class Stage extends View {
            final RectF frame = new RectF();
            Path shapeAt = new Path(), outside = new Path();
            float s = 1f, tx, ty, sMin = 1f, sMax = 4f;
            float enter, only, grid;
            RectF flight;
            boolean ready, touched;
            final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Matrix m = new Matrix();
            final ScaleGestureDetector pinch;
            final GestureDetector taps;
            final OverScroller glide;
            VelocityTracker speed;
            float lastX, lastY, focusX, focusY;
            boolean pinching, atMin, atMax, doubled;
            float doubleX, doubleY;
            int glideId;
            Paint shade;
            ValueAnimator settle, gridFade;

            Stage(Context c) {
                super(c);
                dim.setColor(superDark ? 0xb8000000 : 0xa0000000);
                line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(Math.max(1f, d * 0.75f)); line.setColor(0xffffffff);
                edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(1.5f * d); edge.setColor(0xd9ffffff);
                glide = new OverScroller(c, new DecelerateInterpolator(1.6f));
                pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(ScaleGestureDetector g) { pinching = true; return true; }
                    @Override public boolean onScale(ScaleGestureDetector g) { zoomBy(g.getScaleFactor(), g.getFocusX(), g.getFocusY()); focusX = g.getFocusX(); focusY = g.getFocusY(); return true; }
                    @Override public void onScaleEnd(ScaleGestureDetector g) { }
                });
                try { ScaleGestureDetector.class.getMethod("setQuickScaleEnabled", boolean.class).invoke(pinch, false); } catch (Throwable ignored) { }
                taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) { doubled = true; doubleX = e.getX(); doubleY = e.getY(); return true; }
                });
                setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            }

            void frameFor(int w, int h) {
                if (w <= 0 || h <= 0) return;
                RectF old = ready ? area() : null;
                float top = insetTop + dp(8) + dp(56) + dp(20);
                float bottom = h - (insetBottom + dp(28) + iconSize + dp(8) + dp(18) + dp(18) + dp(40) + dp(18));
                float across = w - insetLeft - insetRight - dp(64);
                compact = bottom - top < dp(200);
                if (compact) { top = insetTop + dp(12); bottom = h - insetBottom - dp(12); across = w - insetLeft - insetRight - dp(2 * (16 + 56 + 16)); }
                float side = Math.max(dp(96), Math.min(across, bottom - top));
                float left = insetLeft + (w - insetLeft - insetRight - side) / 2f, y = top + Math.max(0f, (bottom - top - side) / 2f);
                if (ready && Math.abs(frame.left - left) < 0.5f && Math.abs(frame.top - y) < 0.5f && Math.abs(frame.width() - side) < 0.5f) return;
                frame.set(left, y, left + side, y + side);
                Path p = shape(getContext(), side);
                p.offset(left, y);
                shapeAt = p;
                outside = new Path(p);
                outside.setFillType(Path.FillType.INVERSE_WINDING);
                sMin = Math.max(side / picture.getWidth(), side / picture.getHeight());
                sMax = Math.max(sMin * 2f, Math.min(sMin * 8f, side / 96f));
                if (old == null) start(); else show(old);
                ready = true;
                if (preview != null) preview.invalidate();
                invalidate();
            }

            void start() {
                s = sMin;
                tx = frame.centerX() - s * picture.getWidth() / 2f;
                ty = frame.centerY() - s * picture.getHeight() / 2f;
                touched = false;
            }
            void show(RectF area) {
                s = Math.max(sMin, Math.min(sMax, frame.width() / Math.max(1f, area.width())));
                tx = frame.left - area.left * s; ty = frame.top - area.top * s;
                clampNow();
            }
            RectF area() { return new RectF((frame.left - tx) / s, (frame.top - ty) / s, (frame.right - tx) / s, (frame.bottom - ty) / s); }

            float minX() { return frame.right - s * picture.getWidth(); }
            float maxX() { return frame.left; }
            float minY() { return frame.bottom - s * picture.getHeight(); }
            float maxY() { return frame.top; }
            boolean inside() { return s >= sMin - 1e-4f && s <= sMax + 1e-4f && tx <= maxX() + 0.5f && tx >= minX() - 0.5f && ty <= maxY() + 0.5f && ty >= minY() - 0.5f; }
            void clampNow() { s = Math.max(sMin, Math.min(sMax, s)); tx = Math.max(minX(), Math.min(maxX(), tx)); ty = Math.max(minY(), Math.min(maxY(), ty)); }

            float give(float over) { float k = frame.width() * 0.12f; return 0.5f * k / (k + over); }

            void panBy(float dx, float dy) {
                tx = pull(tx, dx, minX(), maxX());
                ty = pull(ty, dy, minY(), maxY());
            }
            float pull(float v, float delta, float lo, float hi) {
                if (delta > 0 && v + delta > hi) {
                    float free = Math.max(0f, hi - v); v += free; delta -= free;
                    return v + delta * give(Math.max(0f, v - hi));
                }
                if (delta < 0 && v + delta < lo) {
                    float free = Math.min(0f, lo - v); v += free; delta -= free;
                    return v + delta * give(Math.max(0f, lo - v));
                }
                return v + delta;
            }

            void zoomBy(float f, float fx, float fy) {
                if (f <= 0) return;
                float next = s * f;
                if (f < 1f && next < sMin) { float under = Math.max(0f, 1f - s / sMin); next = s * (float) Math.pow(f, 0.4f * (1f - Math.min(0.9f, under * 3f))); next = Math.max(next, sMin * 0.6f); }
                if (f > 1f && next > sMax) { float over = Math.max(0f, s / sMax - 1f); next = s * (float) Math.pow(f, 0.4f * (1f - Math.min(0.9f, over * 3f))); next = Math.min(next, sMax * 1.5f); }
                float k = next / s;
                tx = fx - (fx - tx) * k; ty = fy - (fy - ty) * k; s = next;
                boolean minNow = s <= sMin * 1.001f, maxNow = s >= sMax * 0.999f;
                if (minNow && !atMin && f < 1f) Haptics.tick(getContext());
                if (maxNow && !atMax && f > 1f) Haptics.tick(getContext());
                atMin = minNow; atMax = maxNow;
            }

            @Override public boolean onTouchEvent(MotionEvent e) {
                if (closing || !ready) return true;
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) { stop(); doubled = false; }
                taps.onTouchEvent(e);
                if (doubled && (action == MotionEvent.ACTION_POINTER_DOWN
                        || (action == MotionEvent.ACTION_MOVE && Math.hypot(e.getX() - doubleX, e.getY() - doubleY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()))) doubled = false;
                if (doubled && action == MotionEvent.ACTION_UP) {
                    doubled = false;
                    showGrid(false);
                    if (speed != null) { speed.recycle(); speed = null; }
                    doubleTap(doubleX, doubleY);
                    return true;
                }
                if (doubled && action == MotionEvent.ACTION_MOVE) return true;
                pinch.onTouchEvent(e);
                float fx = 0, fy = 0; int n = 0;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (action == MotionEvent.ACTION_POINTER_UP && i == e.getActionIndex()) continue;
                    fx += e.getX(i); fy += e.getY(i); n++;
                }
                if (n > 0) { fx /= n; fy /= n; }
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        if (speed != null) speed.recycle();
                        speed = VelocityTracker.obtain();
                        speed.addMovement(e);
                        lastX = fx; lastY = fy; pinching = false;
                        atMin = s <= sMin * 1.001f; atMax = s >= sMax * 0.999f;
                        showGrid(true);
                        getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_POINTER_UP:
                        lastX = fx; lastY = fy;
                        if (speed != null) speed.clear();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (speed != null) speed.addMovement(e);
                        panBy(fx - lastX, fy - lastY);
                        lastX = fx; lastY = fy;
                        if (!touched && (Math.abs(s - sMin) > 1e-3f || Math.abs(tx - (frame.centerX() - s * picture.getWidth() / 2f)) > 1f || Math.abs(ty - (frame.centerY() - s * picture.getHeight() / 2f)) > 1f)) { touched = true; changed(false); }
                        moved();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        showGrid(false);
                        float vx = 0, vy = 0;
                        if (speed != null) {
                            speed.addMovement(e);
                            speed.computeCurrentVelocity(1000, ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity());
                            vx = speed.getXVelocity(); vy = speed.getYVelocity();
                            speed.recycle(); speed = null;
                        }
                        float min = ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity() * 2f;
                        if (!inside()) springBack(pinching ? focusX : frame.centerX(), pinching ? focusY : frame.centerY());
                        else if (!pinching && action == MotionEvent.ACTION_UP && Math.hypot(vx, vy) > min) fling(vx, vy);
                        pinching = false;
                        break;
                }
                return true;
            }

            void fling(float vx, float vy) {
                glide.forceFinished(true);
                final int id = ++glideId;
                glide.fling(Math.round(tx), Math.round(ty), Math.round(vx), Math.round(vy),
                        Math.round(minX()), Math.round(maxX()), Math.round(minY()), Math.round(maxY()), dp(28), dp(28));
                postOnAnimation(new Runnable() { public void run() {
                    if (id != glideId) return;
                    if (!glide.computeScrollOffset()) { clampNow(); moved(); return; }
                    tx = glide.getCurrX(); ty = glide.getCurrY();
                    moved();
                    postOnAnimation(this);
                } });
            }

            void springBack(float fx, float fy) {
                float s1 = Math.max(sMin, Math.min(sMax, s));
                float k = s1 / s;
                float x1 = fx - (fx - tx) * k, y1 = fy - (fy - ty) * k;
                float w = s1 * picture.getWidth(), h = s1 * picture.getHeight();
                x1 = Math.max(frame.right - w, Math.min(frame.left, x1));
                y1 = Math.max(frame.bottom - h, Math.min(frame.top, y1));
                animateTo(s1, x1, y1, new Spring(0.78f, 430));
            }

            void doubleTap(float x, float y) {
                if (closing) return;
                Haptics.tick(getContext());
                if (s < sMin * 1.6f) {
                    float s1 = Math.min(sMax, sMin * 2.5f);
                    float k = s1 / s;
                    float fx = frame.contains(x, y) ? x : frame.centerX(), fy = frame.contains(x, y) ? y : frame.centerY();
                    float x1 = fx - (fx - tx) * k, y1 = fy - (fy - ty) * k;
                    float w = s1 * picture.getWidth(), h = s1 * picture.getHeight();
                    x1 = Math.max(frame.right - w, Math.min(frame.left, x1));
                    y1 = Math.max(frame.bottom - h, Math.min(frame.top, y1));
                    animateTo(s1, x1, y1, new Spring(0.86f, 380));
                } else toStart();
            }

            void toStart() {
                float s1 = sMin;
                animateTo(s1, frame.centerX() - s1 * picture.getWidth() / 2f, frame.centerY() - s1 * picture.getHeight() / 2f, new Spring(0.86f, 380));
            }

            void animateTo(final float s1, float x1, float y1, TimeInterpolator spring) {
                if (closing) return;
                stop();
                final float s0 = s;
                final float cx0 = (frame.centerX() - tx) / s, cy0 = (frame.centerY() - ty) / s;
                final float cx1 = (frame.centerX() - x1) / s1, cy1 = (frame.centerY() - y1) / s1;
                settle = ValueAnimator.ofFloat(0f, 1f);
                settle.setDuration(spring instanceof Spring ? ((Spring) spring).ms : 380);
                settle.setInterpolator(spring);
                settle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator v) {
                    float t = (Float) v.getAnimatedValue();
                    s = s0 * (float) Math.pow(s1 / s0, t);
                    float cx = lerp(cx0, cx1, t), cy = lerp(cy0, cy1, t);
                    tx = frame.centerX() - cx * s; ty = frame.centerY() - cy * s;
                    moved();
                } });
                settle.addListener(new AnimatorListenerAdapter() {
                    boolean cancelled;
                    @Override public void onAnimationCancel(Animator x) { cancelled = true; }
                    @Override public void onAnimationEnd(Animator x) {
                        if (cancelled) return;
                        s = s1; tx = frame.centerX() - cx1 * s1; ty = frame.centerY() - cy1 * s1; clampNow(); moved();
                        boolean home = Math.abs(s - sMin) < 1e-3f && Math.abs(tx - (frame.centerX() - s * picture.getWidth() / 2f)) < 1f && Math.abs(ty - (frame.centerY() - s * picture.getHeight() / 2f)) < 1f;
                        if (home && touched) { touched = false; changed(true); }
                        else if (!home && !touched) { touched = true; changed(false); }
                    }
                });
                settle.start();
            }

            void stop() {
                if (settle != null) { settle.cancel(); settle = null; }
                glide.forceFinished(true);
                glideId++;
            }
            void settleNow() {
                if (settle != null && settle.isRunning()) { ValueAnimator v = settle; v.end(); }
                if (!glide.isFinished()) { tx = glide.getFinalX(); ty = glide.getFinalY(); }
                stop(); clampNow(); moved();
            }

            void moved() { invalidate(); if (preview != null) preview.invalidate(); }

            void showGrid(final boolean on) {
                if (gridFade != null) gridFade.cancel();
                gridFade = ValueAnimator.ofFloat(grid, on ? 1f : 0f);
                gridFade.setDuration(on ? 140 : 260);
                gridFade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator v) { grid = (Float) v.getAnimatedValue(); invalidate(); } });
                gridFade.start();
            }

            Matrix matrix() { m.setScale(s, s); m.postTranslate(tx, ty); return m; }

            @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
                frameFor(w, h);
                post(new Runnable() { public void run() { placeBelow(); } });
            }

            @Override protected void onDraw(Canvas c) {
                c.drawColor(stageColor);
                if (!ready || picture.isRecycled()) return;
                float e = enter;
                if (flight != null) {
                    Matrix f = new Matrix(); f.setRectToRect(frame, flight, Matrix.ScaleToFit.FILL);
                    c.save(); c.concat(f); c.clipPath(shapeAt); c.drawBitmap(picture, matrix(), photo); c.restore();
                    return;
                }
                float grow = 0.94f + 0.06f * e;
                c.save();
                c.scale(grow, grow, frame.centerX(), frame.centerY());
                photo.setAlpha(Math.round(255 * e * (1f - only)));
                if (photo.getAlpha() > 0) c.drawBitmap(picture, matrix(), photo);
                int dimAlpha = Color.alpha(dim.getColor());
                if (shade == null) shade = new Paint(dim);
                shade.setAlpha(Math.round(dimAlpha * e * (1f - only)));
                if (shade.getAlpha() > 0) c.drawPath(outside, shade);
                if (only > 0) { photo.setAlpha(Math.round(255 * e)); c.save(); c.clipPath(shapeAt); c.drawBitmap(picture, matrix(), photo); c.restore(); }
                if (grid > 0.01f && only == 0) {
                    line.setAlpha(Math.round(90 * grid * e));
                    c.save(); c.clipPath(shapeAt);
                    for (int i = 1; i <= 2; i++) {
                        float x = frame.left + frame.width() * i / 3f, y = frame.top + frame.height() * i / 3f;
                        c.drawLine(x, frame.top, x, frame.bottom, line);
                        c.drawLine(frame.left, y, frame.right, y, line);
                    }
                    c.restore();
                }
                edge.setAlpha(Math.round(217 * e * (1f - only)));
                if (edge.getAlpha() > 0) c.drawPath(shapeAt, edge);
                c.restore();
                photo.setAlpha(255);
            }
        }

        final class Preview extends View {
            final Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            final Matrix m = new Matrix();
            Path clip; float clipSize;
            Preview(Context c) { super(c); }
            @Override protected void onDraw(Canvas c) {
                if (stage == null || !stage.ready || picture.isRecycled()) return;
                float size = Math.min(getWidth(), getHeight());
                if (clip == null || clipSize != size) { clip = shape(getContext(), size); clipSize = size; }
                RectF area = stage.area();
                m.setRectToRect(area, new RectF(0, 0, size, size), Matrix.ScaleToFit.FILL);
                c.save(); c.clipPath(clip); c.drawColor(0xff000000);  c.drawBitmap(picture, m, photo); c.restore();
            }
        }
    }

    static final class Spring implements TimeInterpolator {
        final float damping; final long ms; final float w;
        Spring(float damping, long ms) { this.damping = damping; this.ms = ms; this.w = (float) (2 * Math.PI / (ms / 1000f * 0.85f)); }
        public float getInterpolation(float t) {
            if (t >= 1f) return 1f;
            double time = t * ms / 1000.0, z = damping, wd = w * Math.sqrt(1 - z * z);
            double env = Math.exp(-z * w * time);
            return (float) (1 - env * (Math.cos(wd * time) + z * w / wd * Math.sin(wd * time)));
        }
    }

    static final class CheckGlyph extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); float drawn = 1f;
        CheckGlyph(int color, float density) {
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(2.4f);
        }
        void drawIn(View host, long delay) {
            drawn = 0f; invalidateSelf();
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setStartDelay(delay); a.setDuration(320); a.setInterpolator(new PathInterpolator(0.3f, 0f, 0.1f, 1f));
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { public void onAnimationUpdate(ValueAnimator v) { drawn = (Float) v.getAnimatedValue(); invalidateSelf(); } });
            a.start();
        }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            c.save(); c.translate(b.left, b.top); c.scale(b.width() / 24f, b.height() / 24f);
            float l1 = 6.36f, l2 = 13.43f, total = l1 + l2, at = drawn * total;
            if (at > 0) {
                float t1 = Math.min(1f, at / l1);
                c.drawLine(5f, 12.5f, 5f + 4.5f * t1, 12.5f + 4.5f * t1, paint);
                if (at > l1) { float t2 = Math.min(1f, (at - l1) / l2); c.drawLine(9.5f, 17f, 9.5f + 9.5f * t2, 17f - 9.5f * t2, paint); }
            }
            c.restore();
        }
        @Override public void setTintList(ColorStateList tint) { if (tint != null) { paint.setColor(tint.getDefaultColor()); invalidateSelf(); } }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class DisplayBounds {
        final float w, h;
        DisplayBounds(View v) { w = v.getWidth(); h = v.getHeight(); }
        boolean contains(RectF r) { return r.right > 0 && r.bottom > 0 && r.left < w && r.top < h; }
    }

    static RectF onScreen(View v, RectF local) {
        RectF r = new RectF(local);
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
        return r;
    }

    static int launcherIconSize(Activity a) {
        try {
            Object profile = Xp.callMethod(a, "getDeviceProfile");
            Object workspace = Xp.callMethod(profile, "getWorkspaceProfile");
            int s = ((Number) Xp.getObjectField(workspace, "iconSizePx")).intValue();
            if (s > 0) return s;
        } catch (Throwable ignored) { }
        try {
            Object profile = Xp.callMethod(a, "getDeviceProfile");
            int s = ((Number) Xp.getObjectField(profile, "iconSizePx")).intValue();
            if (s > 0) return s;
        } catch (Throwable ignored) { }
        return Math.round(56 * a.getResources().getDisplayMetrics().density);
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static RectF scaled(RectF r, float k) {
        float w = r.width() * k, h = r.height() * k;
        return new RectF(r.centerX() - w / 2f, r.centerY() - h / 2f, r.centerX() + w / 2f, r.centerY() + h / 2f);
    }
}
