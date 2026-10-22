package io.github.pixelsuite;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

final class RestartStatusIcon extends Drawable {
    private final Context context;
    private final LauncherGlyph arrow;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tick = new Path();
    private ValueAnimator morph, spin;
    private float state, angle;
    private int target, alpha = 255;
    private boolean active;

    RestartStatusIcon(Context context, int color) {
        this.context = context;
        arrow = new LauncherGlyph(context, 4);
        arrow.setTintList(android.content.res.ColorStateList.valueOf(color));
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
    void setColor(int color){arrow.setTintList(android.content.res.ColorStateList.valueOf(color));paint.setColor(color);invalidateSelf();}
    boolean motion() {
        return android.provider.Settings.Global.getFloat(context.getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0;
    }
    void setActive(boolean value) {
        active = value;
        if (!value) { cancel(); state = target; }
        else if (target == 1) startSpin();
        invalidateSelf();
    }
    void show(int value) {
        target = value;
        if (morph != null) { morph.cancel(); morph = null; }
        if (!active || !motion()) { state = value; cancel(); invalidateSelf(); return; }
        if (value == 1) startSpin();
        morph = ValueAnimator.ofFloat(state, value);
        morph.setDuration(320); morph.setInterpolator(new PathInterpolator(.2f,0f,0f,1f));
        morph.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) { state = (Float)a.getAnimatedValue(); invalidateSelf(); }
        });
        morph.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            public void onAnimationEnd(android.animation.Animator a) {
                morph = null;
                if (!cancelled && target != 1 && spin != null) { spin.cancel(); spin = null; }
            }
        });
        morph.start();
    }
    private void startSpin() {
        if (spin != null || !active || !motion()) return;
        spin = ValueAnimator.ofFloat(angle, angle + 360);
        spin.setDuration(800); spin.setRepeatCount(ValueAnimator.INFINITE); spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) { angle = (Float)a.getAnimatedValue(); invalidateSelf(); }
        });
        spin.start();
    }
    private void cancel() {
        if (morph != null) { morph.cancel(); morph = null; }
        if (spin != null) { spin.cancel(); spin = null; }
    }
    @Override public void draw(Canvas c) {
        android.graphics.Rect b = getBounds(); float w=b.width(), h=b.height();
        if (w<=0 || h<=0) return;
        float restart=Math.max(0,1-state), progress=Math.max(0,1-Math.abs(state-1)), done=Math.max(0,state-1);
        if (restart>0) { arrow.setBounds(b); arrow.setAlpha(Math.round(alpha*restart)); arrow.draw(c); }
        paint.setStrokeWidth(Math.min(w,h)*.105f);
        if (progress>0) {
            paint.setAlpha(Math.round(alpha*progress));
            c.drawArc(new RectF(b.left+w*.15f,b.top+h*.15f,b.right-w*.15f,b.bottom-h*.15f),angle-90,245,false,paint);
        }
        if (done>0) {
            paint.setAlpha(Math.round(alpha*done)); tick.reset();
            float x=b.left+w*.20f,y=b.top+h*.52f;
            tick.moveTo(x,y);
            if(done<.35f) tick.lineTo(x+w*.23f*done/.35f,y+h*.23f*done/.35f);
            else { tick.lineTo(b.left+w*.43f,b.top+h*.75f); float t=(done-.35f)/.65f; tick.lineTo(b.left+w*(.43f+.39f*t),b.top+h*(.75f-.49f*t)); }
            c.drawPath(tick,paint);
        }
    }
    @Override public void setAlpha(int value) { alpha=value; invalidateSelf(); }
    @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); arrow.setColorFilter(f); invalidateSelf(); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
