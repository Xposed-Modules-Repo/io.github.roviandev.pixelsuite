package io.github.pixelsuite;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
/** Small vector-style menu glyphs, inheriting the host theme's foreground. */
final class LauncherGlyph extends Drawable {
    private final Paint paint = new Paint(3); private final int kind;
    /** 0 = sort arrow down, 1 = up (animated by flip). */
    private float turn; private android.animation.ValueAnimator turning;
    /** Turns the sort arrow to point up (true) or back down, with a short eased spin. */
    void flip(boolean up){
        if(turning!=null)turning.cancel();
        turning=android.animation.ValueAnimator.ofFloat(turn,up?1f:0f);
        turning.setDuration(340);turning.setInterpolator(new android.view.animation.OvershootInterpolator(1.1f));
        turning.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener(){public void onAnimationUpdate(android.animation.ValueAnimator a){turn=(Float)a.getAnimatedValue();invalidateSelf();}});
        turning.start();
    }
    LauncherGlyph(Context c, int kind) {
        this.kind=kind; TypedValue v=new TypedValue(); c.getTheme().resolveAttribute(android.R.attr.textColorPrimary,v,true);
        try { paint.setColor(c.getResources().getColorStateList(v.resourceId).getDefaultColor()); }
        catch(Exception e) { paint.setColor(0xff888888); }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
    public void draw(Canvas c) {
        c.save(); c.translate(getBounds().left,getBounds().top); c.scale(getBounds().width()/24f,getBounds().height()/24f);
        if(kind>=53&&kind<=55){int n=kind-50;for(int i=0;i<=n;i++){float p=4+16*i/(float)n;c.drawLine(p,4,p,20,paint);c.drawLine(4,p,20,p,paint);}}
        else if(kind==7){c.drawLine(12,4,12,20,paint);c.drawLine(4,12,20,12,paint);}
        else if(kind==8){c.drawLine(4,6,20,6,paint);c.drawLine(9,3,15,3,paint);c.drawLine(6,6,7,21,paint);c.drawLine(7,21,17,21,paint);c.drawLine(17,21,18,6,paint);c.drawLine(10,10,10,17,paint);c.drawLine(14,10,14,17,paint);}
        else if(kind==0) { c.drawRoundRect(3,7,21,20,2,2,paint); c.drawLine(3,7,3,4,paint); c.drawLine(3,4,10,4,paint); c.drawLine(10,4,13,7,paint); c.drawLine(12,11,12,17,paint); c.drawLine(9,14,15,14,paint); }
        else if(kind==3){c.drawLine(3,10,12,3,paint);c.drawLine(12,3,21,10,paint);c.drawLine(6,9,6,21,paint);c.drawLine(6,21,18,21,paint);c.drawLine(18,21,18,9,paint);c.drawLine(12,12,12,18,paint);c.drawLine(9,15,15,15,paint);}
        else if(kind==4){c.drawArc(5,5,19,19,-135,300,false,paint);c.drawLine(7,7,7,3,paint);c.drawLine(7,7,11,7,paint);}
        else if(kind==2) { c.drawRoundRect(3,3,21,21,3,3,paint);c.drawCircle(8,8,1.5f,paint);c.drawLine(4,18,11,11,paint);c.drawLine(11,11,15,15,paint);c.drawLine(15,15,18,12,paint);c.drawLine(18,12,21,15,paint); }
        else if(kind==10){   // sliders: Pixel Suite settings
            c.drawLine(3,6,5.6f,6,paint);c.drawLine(10.4f,6,21,6,paint);c.drawCircle(8,6,2.4f,paint);
            c.drawLine(3,12,13.6f,12,paint);c.drawLine(18.4f,12,21,12,paint);c.drawCircle(16,12,2.4f,paint);
            c.drawLine(3,18,7.6f,18,paint);c.drawLine(12.4f,18,21,18,paint);c.drawCircle(10,18,2.4f,paint);}
        else if(kind==11){c.drawCircle(10.5f,10.5f,6.5f,paint);c.drawLine(15.3f,15.3f,20.5f,20.5f,paint);}   // magnifier
        else if(kind==12){c.drawLine(6,6,18,18,paint);c.drawLine(18,6,6,18,paint);}                          // clear
        else if(kind==9){   // sort; the arrow turns to point up while the Sort choices are open (flip())
            c.drawLine(4,6,13,6,paint);c.drawLine(4,12,11,12,paint);c.drawLine(4,18,9,18,paint);
            c.save();c.rotate(180f*turn,18,12);c.drawLine(18,4,18,20,paint);c.drawLine(15,17,18,20,paint);c.drawLine(18,20,21,17,paint);c.restore();}
        else if(kind==13){  // Auto (A-Z): an A over a Z, with the downward arrow of the order
            c.drawLine(3.5f,10.5f,6.25f,3.5f,paint);c.drawLine(6.25f,3.5f,9f,10.5f,paint);c.drawLine(4.6f,7.8f,7.9f,7.8f,paint);
            c.drawLine(3.5f,13.5f,9f,13.5f,paint);c.drawLine(9f,13.5f,3.5f,20.5f,paint);c.drawLine(3.5f,20.5f,9f,20.5f,paint);
            c.drawLine(16.5f,4,16.5f,20,paint);c.drawLine(13.5f,17,16.5f,20,paint);c.drawLine(16.5f,20,19.5f,17,paint);}
        else if(kind==14){  // Custom: tiles placed by hand, one of them lifted and turned as if being moved
            c.drawRoundRect(3.5f,3.5f,10f,10f,1.8f,1.8f,paint);c.drawRoundRect(14f,3.5f,20.5f,10f,1.8f,1.8f,paint);c.drawRoundRect(3.5f,14f,10f,20.5f,1.8f,1.8f,paint);
            c.save();c.rotate(-16f,17.6f,17.6f);c.drawRoundRect(14.4f,14.4f,20.9f,20.9f,1.8f,1.8f,paint);c.restore();}
        else { c.drawLine(5,18,17,6,paint); c.drawLine(17,6,20,9,paint); c.drawLine(20,9,8,21,paint); c.drawLine(8,21,4,22,paint); c.drawLine(4,22,5,18,paint); c.drawLine(4,4,13,4,paint); c.drawLine(8,4,8,12,paint); }
        c.restore();
    }
    /** Follows the tint the host view sets (stock menu icons are tinted), so the glyph matches them. */
    public void setTintList(android.content.res.ColorStateList tint){if(tint!=null){paint.setColor(tint.getColorForState(getState(),tint.getDefaultColor()));invalidateSelf();}}
    public void setAlpha(int a){paint.setAlpha(a); invalidateSelf();} public void setColorFilter(ColorFilter f){paint.setColorFilter(f);invalidateSelf();} public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
