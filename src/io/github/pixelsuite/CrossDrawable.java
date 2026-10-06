package io.github.pixelsuite;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Material-style "close" glyph, coloured like the neighbouring button's icon. */
final class CrossDrawable extends Drawable {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ColorStateList mColors;
    private int mColor;
    private int mAlpha = 255;

    CrossDrawable(ColorStateList colors) {
        mColors = colors;
        mColor = colors != null ? colors.getDefaultColor() : 0xFFFFFFFF;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        if (size <= 0) return;
        float half = size * 6f / 24f;            // 24dp grid: arms from 6 to 18
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        mPaint.setStrokeWidth(size * 2f / 24f);
        int a = (((mColor >>> 24) * mAlpha) / 255) << 24;
        mPaint.setColor((mColor & 0x00FFFFFF) | a);
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, mPaint);
        canvas.drawLine(cx - half, cy + half, cx + half, cy - half, mPaint);
    }

    @Override
    public boolean isStateful() {
        return mColors != null && mColors.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (mColors == null) return false;
        int c = mColors.getColorForState(state, mColors.getDefaultColor());
        if (c == mColor) return false;
        mColor = c;
        invalidateSelf();
        return true;
    }

    @Override public void setAlpha(int alpha) { mAlpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { mPaint.setColorFilter(cf); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { Rect b = getBounds(); return b.width() > 0 ? b.width() : -1; }
    @Override public int getIntrinsicHeight() { Rect b = getBounds(); return b.height() > 0 ? b.height() : -1; }
}
